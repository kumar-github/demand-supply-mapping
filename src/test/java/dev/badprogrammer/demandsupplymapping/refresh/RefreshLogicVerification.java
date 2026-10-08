package dev.badprogrammer.demandsupplymapping.refresh;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Standalone (no JUnit/Spring) verification harness for RefreshLogic, matching the pattern of ParallelRunVerification /
 * FullPersonaVerification in the matching package: runs the real logic against the real source Excel files and diffs
 * the result against ground truth already produced by the real, unmodified classify_inputs.py functions.
 * <p>
 * Ground truth: verification-fixtures/full_supply_classified.csv and full_demand_classified.csv (from
 * export_full_ground_truth.py, which calls classify_inputs.load_phase1_supply()/classify_supply()/
 * load_and_classify_demand() directly — the same functions, same scope, just exported for every persona instead of only
 * Fullstack Java).
 * <p>
 * This needs real Apache POI on the classpath (poi-ooxml), so it can't run in a sandbox without Maven Central — run it
 * on a real machine:
 * <p>
 * cd bench-match-webapp mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt javac -d out -cp "$(cat cp.txt)" \
 * src/main/java/com/hcltech/benchmatch/matching/*.java \
 * src/main/java/com/hcltech/benchmatch/refresh/ExcelSheetReader.java \
 * src/main/java/com/hcltech/benchmatch/refresh/RefreshLogic.java \
 * src/test/java/com/hcltech/benchmatch/refresh/RefreshLogicVerification.java java -cp "out:$(cat cp.txt)"
 * com.hcltech.benchmatch.refresh.RefreshLogicVerification \ ../cli_pkg/bench-match-cli/input/AFD-Supply.xlsx \
 * ../cli_pkg/bench-match-cli/input/Demand.xlsx \ verification-fixtures
 * <p>
 * (drop the @Service import/annotation from MatchingService.java first if spring-context isn't resolvable standalone,
 * same caveat as the other harnesses — not needed once run via `mvn test-compile`/a real test.)
 */
public class RefreshLogicVerification {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage: RefreshLogicVerification <AFD-Supply.xlsx> <Demand.xlsx> <verification-fixtures-dir>");
            System.exit(2);
        }
        Path supplyXlsx = Path.of(args[0]);
        Path demandXlsx = Path.of(args[1]);
        String fixturesDir = args[2];

        boolean allGood = true;
        allGood &= verifySupply(supplyXlsx, fixturesDir);
        allGood &= verifyDemand(demandXlsx, fixturesDir);
        allGood &= verifyHashStability(supplyXlsx, demandXlsx);

        System.out.println();
        System.out.println(allGood ? "RESULT: PASS — RefreshLogic matches classify_inputs.py exactly."
                : "RESULT: FAIL — see mismatches above.");
        if (!allGood) {
            System.exit(1);
        }
    }

    private static boolean verifySupply(Path xlsx, String fixturesDir) throws IOException {
        List<Map<String, String>> raw = ExcelSheetReader.readFirstSheet(xlsx);
        List<Map<String, String>> phase1 = RefreshLogic.filterPhase1Supply(raw);
        List<RefreshLogic.SupplyClassifiedRow> classified = RefreshLogic.classifySupply(phase1);

        Map<String, Map<String, String>> ground = readCsvByKey(
                Path.of(fixturesDir, "full_supply_classified.csv"), "Employee Code");

        System.out.println("Supply: " + raw.size() + " raw rows -> " + phase1.size()
                + " Phase 1 scope -> " + classified.size() + " classified. Ground truth: " + ground.size() + " rows.");

        int mismatches = 0;
        int comparable = 0;
        for (RefreshLogic.SupplyClassifiedRow row : classified) {
            if (row.classification().persona() == null) {
                // export_full_ground_truth.py filters full_supply_classified.csv to
                // persona.notna() only (see its "sup = ...[persona notna]" line) --
                // an unclassified/data-issue row is deliberately absent from ground
                // truth, not a mismatch.
                continue;
            }
            comparable++;
            String key = row.employeeId() == null ? null : String.valueOf(row.employeeId());
            Map<String, String> gt = ground.get(key);
            if (gt == null) {
                mismatches++;
                if (mismatches <= 8) System.out.println("  MISSING FROM GROUND TRUTH: employee=" + key);
                continue;
            }
            String gtPersona = nz(gt.get("persona"));
            String gtSubPersona = nz(gt.get("sub_persona"));
            String javaPersona = row.classification().persona();
            String javaSubPersona = nz(row.classification().subPersona());
            String gtSubBand = nz(gt.get("Sub Band"));
            String gtBenchAgeing = nz(gt.get("Bench Ageing days"));
            String gtRating = nz(gt.get("Rating"));
            String gtScore = nz(gt.get("Score"));
            boolean mismatch = !Objects.equals(javaPersona, gtPersona) || !Objects.equals(javaSubPersona, gtSubPersona)
                    || !Objects.equals(row.subBand(), gtSubBand)
                    || !Objects.equals(row.benchAgeingDays() == null ? null : String.valueOf(row.benchAgeingDays()),
                    gtBenchAgeing == null ? null : String.valueOf((int) Double.parseDouble(gtBenchAgeing)))
                    || !Objects.equals(row.rating(), gtRating)
                    || !scoresMatch(row.score(), gtScore);
            if (mismatch) {
                mismatches++;
                if (mismatches <= 8) {
                    System.out.println("  MISMATCH employee=" + key + " java(persona=" + javaPersona
                            + ", sub=" + javaSubPersona + ", subBand=" + row.subBand() + ", benchAgeing=" + row.benchAgeingDays()
                            + ", rating=" + row.rating() + ", score=" + row.score()
                            + ") python(persona=" + gtPersona + ", sub=" + gtSubPersona + ", subBand=" + gtSubBand
                            + ", benchAgeing=" + gtBenchAgeing + ", rating=" + gtRating + ", score=" + gtScore + ")");
                }
            }
        }
        boolean pass = mismatches == 0 && comparable == ground.size();
        System.out.println("Supply classification: " + (comparable - mismatches) + "/" + comparable
                + " classified rows match Python exactly, row counts " + (comparable == ground.size() ? "MATCH" : "DIFFER")
                + " -> " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    private static boolean verifyDemand(Path xlsx, String fixturesDir) throws IOException {
        List<Map<String, String>> raw = ExcelSheetReader.readFirstSheet(xlsx);
        List<Map<String, String>> cleaned = RefreshLogic.stripFooterAndFilterApproved(raw);
        List<RefreshLogic.DemandClassifiedRow> classified = RefreshLogic.classifyDemand(cleaned);

        Map<String, Map<String, String>> ground = readCsvByKey(
                Path.of(fixturesDir, "full_demand_classified.csv"), "Job Requisition ID");

        System.out.println("Demand: " + raw.size() + " raw rows -> " + cleaned.size()
                + " after footer-strip/Approved -> " + classified.size() + " classified. Ground truth: " + ground.size() + " rows.");

        int mismatches = 0;
        int comparable = 0;
        for (RefreshLogic.DemandClassifiedRow row : classified) {
            if (row.classification().persona() == null) {
                // Same filter as verifySupply() above -- export_full_ground_truth.py
                // excludes unclassified/data-issue/bad-band demand rows from
                // full_demand_classified.csv by design.
                continue;
            }
            comparable++;
            Map<String, String> gt = ground.get(row.demandId());
            if (gt == null) {
                mismatches++;
                if (mismatches <= 8) System.out.println("  MISSING FROM GROUND TRUTH: demand=" + row.demandId());
                continue;
            }
            String gtPersona = nz(gt.get("persona"));
            String gtSubPersona = nz(gt.get("sub_persona"));
            String javaPersona = row.classification().persona();
            String javaSubPersona = nz(row.classification().subPersona());
            String gtBalancePositions = nz(gt.get("Balance Positions"));
            String gtDueCategory = nz(gt.get("Due Category_New"));
            // frontend_anchor_flag: Step 2 of the 2026-09-29 fix — this is the
            // "don't forget the Java port" forcing function. Once ground truth
            // is regenerated by export_full_ground_truth.py (which now calls
            // classify_inputs.py's frontend_anchor_check() via
            // load_and_classify_demand()), this comparison FAILS loudly until
            // RefreshLogic.frontendAnchorCheck() is wired up to match it — the
            // absence of the Java port is a test failure, not a TODO comment.
            String gtFrontendAnchorFlagRaw = nz(gt.get("frontend_anchor_flag"));
            boolean gtFrontendAnchorFlag = gtFrontendAnchorFlagRaw != null
                    && Boolean.parseBoolean(gtFrontendAnchorFlagRaw.trim());
            boolean mismatch = !Objects.equals(javaPersona, gtPersona) || !Objects.equals(javaSubPersona, gtSubPersona)
                    || !Objects.equals(row.balancePositions() == null ? null : String.valueOf(row.balancePositions()),
                    gtBalancePositions == null ? null : String.valueOf((int) Double.parseDouble(gtBalancePositions)))
                    || !Objects.equals(row.dueCategory(), gtDueCategory)
                    || (gt.containsKey("frontend_anchor_flag") && row.frontendAnchorFlag() != gtFrontendAnchorFlag);
            if (mismatch) {
                mismatches++;
                if (mismatches <= 8) {
                    System.out.println("  MISMATCH demand=" + row.demandId() + " java(persona=" + javaPersona
                            + ", sub=" + javaSubPersona + ", balancePositions=" + row.balancePositions()
                            + ", dueCategory=" + row.dueCategory() + ", frontendAnchorFlag=" + row.frontendAnchorFlag()
                            + ") python(persona=" + gtPersona + ", sub=" + gtSubPersona
                            + ", balancePositions=" + gtBalancePositions + ", dueCategory=" + gtDueCategory
                            + ", frontend_anchor_flag=" + gtFrontendAnchorFlag + ")");
                }
            }
        }
        boolean pass = mismatches == 0 && comparable == ground.size();
        System.out.println("Demand classification: " + (comparable - mismatches) + "/" + comparable
                + " classified rows match Python exactly, row counts " + (comparable == ground.size() ? "MATCH" : "DIFFER")
                + " -> " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /**
     * source_row_hash must be stable across two independent reads of the same file (idempotency check).
     */
    private static boolean verifyHashStability(Path supplyXlsx, Path demandXlsx) throws IOException {
        var s1 = RefreshLogic.classifySupply(RefreshLogic.filterPhase1Supply(ExcelSheetReader.readFirstSheet(supplyXlsx)));
        var s2 = RefreshLogic.classifySupply(RefreshLogic.filterPhase1Supply(ExcelSheetReader.readFirstSheet(supplyXlsx)));
        boolean stable = true;
        for (int i = 0; i < s1.size(); i++) {
            if (!Objects.equals(s1.get(i).sourceRowHash(), s2.get(i).sourceRowHash())) {
                stable = false;
                System.out.println("  UNSTABLE HASH at supply row " + i);
            }
        }
        System.out.println("Hash stability (same file read twice, supply): " + (stable ? "PASS" : "FAIL"));
        return stable;
    }

    /**
     * Score is a float column round-tripped through CSV text; compare numerically with a small tolerance.
     */
    private static boolean scoresMatch(Double javaScore, String gtScoreRaw) {
        if (javaScore == null && gtScoreRaw == null) {
            return true;
        }
        if (javaScore == null || gtScoreRaw == null) {
            return false;
        }
        try {
            return Math.abs(javaScore - Double.parseDouble(gtScoreRaw)) < 1e-9;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String nz(String s) {
        return (s == null || s.isBlank() || s.equalsIgnoreCase("nan")) ? null : s;
    }

    private static Map<String, Map<String, String>> readCsvByKey(Path path, String keyColumn) throws IOException {
        List<Map<String, String>> rows = readCsv(path);
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String key = row.get(keyColumn);
            if (key != null && key.endsWith(".0")) {
                key = key.substring(0, key.length() - 2);
            }
            out.put(key, row);
        }
        return out;
    }

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        }
        List<List<String>> records = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        List<String> record = new ArrayList<>();
        boolean inQuotes = false;
        for (String line : lines) {
            int i = 0;
            while (i < line.length()) {
                char c = line.charAt(i);
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            field.append('"');
                            i++;
                        } else {
                            inQuotes = false;
                        }
                    } else {
                        field.append(c);
                    }
                } else {
                    if (c == '"') {
                        inQuotes = true;
                    } else if (c == ',') {
                        record.add(field.toString());
                        field.setLength(0);
                    } else {
                        field.append(c);
                    }
                }
                i++;
            }
            if (inQuotes) {
                field.append('\n');
            } else {
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            }
        }
        List<String> header = records.get(0);
        List<Map<String, String>> out = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < rec.size(); c++) {
                row.put(header.get(c), rec.get(c));
            }
            out.add(row);
        }
        return out;
    }
}
