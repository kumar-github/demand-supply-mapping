package dev.badprogrammer.demandsupplymapping.matching;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Step 4 of 2026-09-29's match_candidates-parity work — the final, end-to- end check. FullPersonaVerification
 * (2026-09-28) already proved skillSignal()/bandSignal()/locationSignal()/overallTier() match Python exactly across
 * every classified persona pairing, for EVERY candidate pair before capping (86,837 rows). What was still unverified is
 * everything MatchingRunService adds on top of those signals: the candidate-demand persona-family filter,
 * assessmentSignal() (rating/ score -> rank/collapsed/expanded), ageingRank(), capEmployeeRows() (only ever
 * smoke-tested by hand, on 4 small made-up scenarios), and the one_liner assembly — i.e. whether the FINAL, POST-CAP
 * row set MatchingRunService would write to match_candidates matches what build_matches.py actually wrote to
 * data/match_rows.csv, row for row.
 * <p>
 * This harness re-derives that final row set directly from the classified CSVs (not the DB — no Spring/JPA available in
 * this sandbox) using the exact same MatchingService methods MatchingRunService calls, following the same
 * reproduce-independently discipline as RefreshLogicVerification/ ParallelRunVerification/FullPersonaVerification. It
 * deliberately duplicates MatchingRunService's candidateDemandsFor()/buildOneLiner()/ leadIn() logic (small, private,
 * and not worth refactoring into a shared class for a one-off verification harness) rather than calling
 * MatchingRunService itself, which needs a live Spring context/DB.
 * <p>
 * Ground truth: cli_pkg/bench-match-cli/data/match_rows.csv, paired with
 * full_supply_classified.csv/full_demand_classified.csv from the SAME directory (confirmed byte-identical to the
 * verification-fixtures/ copies FullPersonaVerification uses, and to each other in mtime) — a real build_matches.py run
 * against real data, POST cap_employee_rows(), with every field this class produces.
 * <p>
 * Run with: javac -cp <compiled classes> -d <out> MatchingRunVerification.java java -cp <compiled classes>:<out>
 * com.hcltech.benchmatch.matching.MatchingRunVerification <csv-dir> where <csv-dir> contains
 * full_supply_classified.csv, full_demand_classified.csv, and a data/match_rows.csv subdirectory (or pass the data dir
 * separately — see main()'s arg handling).
 */
public class MatchingRunVerification {

    // Side channel for assessment_rank, since MatchRow has no field for it
    // (overall_tier/skill_quality/ageing_rank/balance_positions are the
    // only per-row fields cap_employee_rows() actually needs to sort/filter
    // on; assessment_rank only ever reaches the one_liner text in
    // production, but the ground truth CSV exposes it separately, so it's
    // worth checking directly rather than only indirectly via one_liner).
    private static final Map<String, Integer> assessmentRankByKey = new HashMap<>();

    public static void main(String[] args) throws IOException {
        String csvDir = args.length > 0 ? args[0] : ".";
        String matchRowsPath = args.length > 1 ? args[1] : csvDir + "/data/match_rows.csv";

        List<Map<String, String>> supplyRows = readCsv(Path.of(csvDir, "full_supply_classified.csv"));
        List<Map<String, String>> demandRows = readCsv(Path.of(csvDir, "full_demand_classified.csv"));
        List<Map<String, String>> groundTruth = readCsv(Path.of(matchRowsPath));

        System.out.println("Loaded: " + supplyRows.size() + " supply rows, " + demandRows.size()
                + " demand rows, " + groundTruth.size() + " ground-truth match_rows.csv rows.");

        MatchingService svc = new MatchingService();

        // ---------------------------------------------------------------
        // Reclassify (proven exact by FullPersonaVerification) and index
        // raw rows for the fields not carried on ClassificationResult
        // (band, location, rating/score, balance positions, due category).
        // ---------------------------------------------------------------
        Map<String, ClassificationResult> empClassified = new LinkedHashMap<>();
        Map<String, Map<String, String>> supplyByCode = new LinkedHashMap<>();
        for (Map<String, String> row : supplyRows) {
            String code = normalizeCode(row.get("Employee Code"));
            empClassified.put(code, SupplyClassifier.classify(row.get("Skill Cluster")));
            supplyByCode.put(code, row);
        }
        Map<String, ClassificationResult> demClassified = new LinkedHashMap<>();
        Map<String, Map<String, String>> demandByReq = new LinkedHashMap<>();
        for (Map<String, String> row : demandRows) {
            String reqId = row.get("Job Requisition ID");
            demClassified.put(reqId, DemandClassifier.classify(row.get("Final Skill Cluster")));
            demandByReq.put(reqId, row);
        }

        // Only classified (persona != null) + "active" rows are candidates
        // for matching, mirroring MatchingRunService's own filters. These
        // fixture CSVs have no is_active column (they're a full classified
        // snapshot, not a DB export), so persona != null is the only filter
        // available/needed here.
        Map<String, List<Map<String, String>>> demandByPersona = new LinkedHashMap<>();
        for (Map<String, String> row : demandRows) {
            String reqId = row.get("Job Requisition ID");
            ClassificationResult c = demClassified.get(reqId);
            if (c.persona() == null) continue;
            demandByPersona.computeIfAbsent(c.persona(), k -> new ArrayList<>()).add(row);
        }

        // ---------------------------------------------------------------
        // Reproduce MatchingRunService.runMatching()'s per-employee loop.
        // ---------------------------------------------------------------
        Map<String, MatchRow> javaOutput = new LinkedHashMap<>(); // key: empCode|reqId

        for (Map<String, String> empRow : supplyRows) {
            String empCode = normalizeCode(empRow.get("Employee Code"));
            ClassificationResult emp = empClassified.get(empCode);
            if (emp.persona() == null) continue;

            List<Map<String, String>> candidates = candidateDemandsFor(emp.persona(), demandByPersona);
            if (candidates.isEmpty()) continue;

            List<MatchRow> empRows = new ArrayList<>();
            for (Map<String, String> demRow : candidates) {
                String reqId = demRow.get("Job Requisition ID");
                ClassificationResult dem = demClassified.get(reqId);

                SignalResult skill = svc.skillSignal(emp, dem);
                if ("no_relation".equals(skill.quality())) continue;
                SignalResult band = svc.bandSignal(empRow.get("Sub Band"), demRow.get("Demand Sub Band Name"));
                SignalResult location = svc.locationSignal(empRow.get("Employee Location"),
                        demRow.get("Personnel Sub Area Name"), null); // panIndia always null in production — see MatchingRunService's Javadoc
                AssessmentResult assessment = svc.assessmentSignal(nzRating(empRow.get("Rating")),
                        parseDouble(empRow.get("Score")));
                String tier = svc.overallTier(skill, band, location);
                String oneLiner = buildOneLiner(skill, band, location, assessment);
                int ageingRank = svc.ageingRank(demRow.get("Due Category_New"));
                Integer balancePositions = parseInt(demRow.get("Balance Positions"));

                // bandOverrideEligible mirrors MatchingRunService's own computation — see
                // MatchingService.isOneBandBelow()'s Javadoc. NOTE this means capEmployeeRows()
                // here now matches MatchingRunService's production behavior (an extra, additive
                // Override-eligible bucket on top of the existing Excluded near-miss cap), which
                // is a deliberate 2026-10-06 divergence from build_matches.py's own capping — this
                // harness's ground-truth ("ground_truth" CSV) ExTRA/mismatch counts will now show
                // extra one-band-below Excluded rows that Python's output never had. That is
                // expected, not a bug to chase.
                boolean bandOverrideEligible = svc.isOneBandBelow(
                        empRow.get("Sub Band"), demRow.get("Demand Sub Band Name"));
                empRows.add(new MatchRow(null, reqId, tier, skill.quality(), ageingRank, balancePositions,
                        oneLiner, skill.quality(), band.quality(), location.quality(), bandOverrideEligible));
                // stash assessment rank alongside via a side map since MatchRow has no slot for it
                assessmentRankByKey.put(empCode + "|" + reqId, assessment.rank());
            }
            if (empRows.isEmpty()) continue;

            for (MatchRow row : svc.capEmployeeRows(empRows)) {
                javaOutput.put(empCode + "|" + row.demandId(), row);
            }
        }

        // ---------------------------------------------------------------
        // Diff against ground truth.
        // ---------------------------------------------------------------
        Map<String, Map<String, String>> gtByKey = new LinkedHashMap<>();
        for (Map<String, String> row : groundTruth) {
            String key = normalizeCode(row.get("employee_code")) + "|" + row.get("job_req_id");
            gtByKey.put(key, row);
        }

        System.out.println("Java produced " + javaOutput.size() + " post-cap rows; ground truth has "
                + gtByKey.size() + " rows.");

        int missingFromJava = 0, extraInJava = 0, fieldMismatches = 0, printed = 0;
        for (String key : gtByKey.keySet()) {
            if (!javaOutput.containsKey(key)) {
                missingFromJava++;
                if (printed < 15) {
                    printed++;
                    System.out.println("MISSING FROM JAVA (present in ground truth, capped out or never generated): " + key);
                }
            }
        }
        for (String key : javaOutput.keySet()) {
            if (!gtByKey.containsKey(key)) {
                extraInJava++;
                if (printed < 15) {
                    printed++;
                    System.out.println("EXTRA IN JAVA (not in ground truth): " + key);
                }
            }
        }
        for (var e : javaOutput.entrySet()) {
            String key = e.getKey();
            Map<String, String> gt = gtByKey.get(key);
            if (gt == null) continue; // already counted as extra above
            MatchRow row = e.getValue();
            Integer assessmentRank = assessmentRankByKey.get(key);

            List<String> diffs = new ArrayList<>();
            if (!row.overallTier().equals(gt.get("overall_tier"))) diffs.add("tier");
            if (!row.skillSignal().equals(gt.get("skill_quality"))) diffs.add("skill_quality");
            if (!row.bandSignal().equals(gt.get("band_quality"))) diffs.add("band_quality");
            if (!row.locationSignal().equals(gt.get("loc_quality"))) diffs.add("loc_quality");
            if (assessmentRank != null && !assessmentRank.toString().equals(gt.get("assessment_rank")))
                diffs.add("assessment_rank");
            if (row.ageingRank() != Integer.parseInt(gt.get("ageing_rank"))) diffs.add("ageing_rank");
            if (!row.oneLiner().equals(gt.get("one_liner"))) diffs.add("one_liner");

            if (!diffs.isEmpty()) {
                fieldMismatches++;
                if (printed < 15) {
                    printed++;
                    System.out.println("FIELD MISMATCH " + key + " diffs=" + diffs
                            + "\n  java: tier=" + row.overallTier() + " skill=" + row.skillSignal()
                            + " band=" + row.bandSignal() + " loc=" + row.locationSignal()
                            + " ageingRank=" + row.ageingRank() + " assessmentRank=" + assessmentRank
                            + "\n        one_liner=" + row.oneLiner()
                            + "\n  python: tier=" + gt.get("overall_tier") + " skill=" + gt.get("skill_quality")
                            + " band=" + gt.get("band_quality") + " loc=" + gt.get("loc_quality")
                            + " ageingRank=" + gt.get("ageing_rank") + " assessmentRank=" + gt.get("assessment_rank")
                            + "\n        one_liner=" + gt.get("one_liner"));
                }
            }
        }

        System.out.println();
        System.out.println("RESULT: rows missing from Java=" + missingFromJava
                + ", extra rows in Java=" + extraInJava
                + ", field mismatches on matched rows=" + fieldMismatches);
        boolean pass = missingFromJava == 0 && extraInJava == 0 && fieldMismatches == 0;
        System.out.println(pass
                ? "RESULT: PASS — Java's post-cap match_candidates output matches build_matches.py's data/match_rows.csv exactly."
                : "RESULT: FAIL — see mismatches above.");
        if (!pass) {
            System.exit(1);
        }
    }

    /**
     * Mirrors MatchingRunService.candidateDemandsFor(), operating on raw persona name + row maps.
     */
    private static List<Map<String, String>> candidateDemandsFor(String persona, Map<String, List<Map<String, String>>> demandByPersona) {
        if ("Fullstack Java".equals(persona) || "Fullstack .NET".equals(persona)) {
            return demandByPersona.getOrDefault(persona, List.of());
        }
        if ("Frontend".equals(persona)) {
            return demandByPersona.getOrDefault("Frontend", List.of());
        }
        return List.of();
    }

    /**
     * Verbatim copy of MatchingRunService.buildOneLiner() — see that class's Javadoc for the build_matches.py line
     * reference.
     */
    private static String buildOneLiner(SignalResult skill, SignalResult band, SignalResult location, AssessmentResult assessment) {
        List<String> bits = new ArrayList<>();
        bits.add("core".equals(skill.quality()) ? leadIn(skill.collapsed()) : skill.collapsed());
        bits.add(band.collapsed());
        bits.add(location.collapsed());
        if (!"Not yet assessed".equals(assessment.collapsed())) {
            bits.add(assessment.collapsed());
        }
        return String.join("; ", bits);
    }

    private static String leadIn(String collapsed) {
        int idx = collapsed.indexOf(" — ");
        return idx < 0 ? collapsed : collapsed.substring(0, idx);
    }

    private static String normalizeCode(String s) {
        if (s == null) return null;
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /**
     * Same pandas-NA-token normalization as RefreshLogic.nz() — these CSVs are pandas' own output, so a literal "nan"
     * cell means missing.
     */
    private static String nzRating(String s) {
        if (s == null) return null;
        String t = s.trim();
        return (t.isEmpty() || t.equalsIgnoreCase("nan")) ? null : t;
    }

    private static Double parseDouble(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("nan")) return null;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("nan")) return null;
        try {
            return (int) Double.parseDouble(t); // CSV stores "1.0" etc.
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
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
