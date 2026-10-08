package dev.badprogrammer.demandsupplymapping.refresh;

import dev.badprogrammer.demandsupplymapping.matching.ClassificationResult;
import dev.badprogrammer.demandsupplymapping.matching.DemandClassifier;
import dev.badprogrammer.demandsupplymapping.matching.Engine;
import dev.badprogrammer.demandsupplymapping.matching.SupplyClassifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plain port of classify_inputs.py's filtering + classification pipeline (Phase 1 supply scope, demand
 * footer-stripping, both band-ladder data-quality overrides, Sub-Capability/MAS-Mapping validation flags). Deliberately
 * has NO Spring/JPA dependency, exactly like the matching package before it, so it can be verified standalone with a
 * main() harness (see RefreshLogicVerification) before RefreshService wires it to the database.
 * <p>
 * Does NOT touch classify_inputs.py, engine.py or build_matches.py — this is a from-scratch Java re-implementation of
 * the same rules, checked against their real output in verification-fixtures/.
 */
public final class RefreshLogic {

    // pandas.read_excel()'s default na_values list — applied to every column
    // unless a caller overrides it, which classify_inputs.py does not. A raw
    // cell literally containing one of these tokens (e.g. "NA" in a Rating
    // cell) is silently read as missing by pandas; ExcelSheetReader (plain
    // POI) has no such behavior, so nz() replicates it here for parity with
    // the ground truth. Found 2026-09-29 via employee 52022094's Rating cell.
    private static final java.util.Set<String> PANDAS_NA_TOKENS = java.util.Set.of(
            "#N/A", "#N/A N/A", "#NA", "-1.#IND", "-1.#QNAN", "-NaN", "-nan",
            "1.#IND", "1.#QNAN", "<NA>", "N/A", "NA", "NULL", "NaN", "None", "n/a", "nan", "null"
    );

    // ------------------------------------------------------------------
    // Supply
    // ------------------------------------------------------------------

    private RefreshLogic() {
    }

    /**
     * Phase 1 scope filter — mirrors load_phase1_supply() exactly.
     */
    public static List<Map<String, String>> filterPhase1Supply(List<Map<String, String>> rawRows) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> row : rawRows) {
            if (!"MAS".equals(nz(row.get("Capability")))) {
                continue;
            }
            String band = nz(row.get("Band"));
            if (band == null || !(band.equals("E0") || band.equals("E1") || band.equals("E2") || band.equals("E3"))) {
                continue;
            }
            // Case/whitespace-insensitive match — see Engine.isProposableStatus()'s javadoc for why a
            // plain PROPOSABLE_STATUSES.contains(...) exact match is unsafe here (2026-09-30 fix).
            if (!Engine.isProposableStatus(nz(row.get("AFD Status")))) {
                continue;
            }
            if (!Engine.PHASE1_MAS_MAPPING.contains(nz(row.get("MAS Mapping")))) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    /**
     * classify_supply() — engine classification + band-ladder override + Sub-Capability flag.
     */
    public static List<SupplyClassifiedRow> classifySupply(List<Map<String, String>> phase1Rows) {
        List<SupplyClassifiedRow> out = new ArrayList<>();
        for (Map<String, String> row : phase1Rows) {
            String subBand = nz(row.get("Sub Band"));
            ClassificationResult result = SupplyClassifier.classify(row.get("Skill Cluster"));

            boolean badBand = Engine.bandIndex(subBand) == null;
            String classificationStatus;
            if (badBand) {
                // Band-ladder data-quality override: clears any classification the
                // skill-cluster text would otherwise have produced.
                result = ClassificationResult.unclassified(
                        "DATA ISSUE — Sub Band '" + subBand + "' is outside the supported "
                                + "E1.1-E3.2 ladder (needs reviewer confirmation of the employee's real band position)");
                classificationStatus = "data_issue";
            } else if (result.persona() == null) {
                classificationStatus = "unclassified";
            } else {
                classificationStatus = "classified";
            }

            String subCapabilityRaw = nz(row.get("Sub-Capability"));
            boolean scFlag = subCapabilityCheck(subCapabilityRaw, result.persona());

            Long employeeId = parseEmployeeId(row.get("Employee Code"));
            Integer benchAgeingDays = parseIntOrNull(row.get("Bench Ageing days"));
            Double score = parseDoubleOrNull(row.get("Score"));
            String hash = badBand
                    ? sha256("BAD_BAND|" + subBand)
                    : sha256(nz(row.get("Skill Cluster")) + "|" + subBand);

            out.add(new SupplyClassifiedRow(
                    employeeId,
                    nz(row.get("Employee Name")),
                    nz(row.get("Band")),
                    subBand,
                    nz(row.get("Employee Location")),
                    nz(row.get("Capability")),
                    nz(row.get("AFD Status")),
                    nz(row.get("Skill Cluster")),
                    subCapabilityRaw,
                    benchAgeingDays,
                    nz(row.get("Rating")),
                    score,
                    nz(row.get("Prime/NV")),
                    result,
                    classificationStatus,
                    scFlag,
                    nz(row.get("MAS Mapping")),
                    hash
            ));
        }
        return out;
    }

    /**
     * Mirrors sub_capability_check() in classify_inputs.py. Validation-only (never a filter) — see that function's own
     * docstring for why: on real data, using it as a filter would have dropped 17 genuine employees. The Python side
     * also returns a human-readable note; that note is NOT persisted (the schema only has the boolean flag) but is
     * fully re-derivable on demand from subCapabilityRaw + persona — see {@link #subCapabilityNote}.
     */
    public static boolean subCapabilityCheck(String subCapabilityRaw, String persona) {
        if (persona == null) {
            return false; // nothing to compare against; classification itself already flags this row
        }
        String expected = expectedSubCapability(persona);
        if (expected == null) {
            return false; // persona outside the three Phase 1 buckets
        }
        return !expected.equals(subCapabilityRaw);
    }

    public static String subCapabilityNote(String subCapabilityRaw, String persona) {
        if (!subCapabilityCheck(subCapabilityRaw, persona)) {
            return null;
        }
        String expected = expectedSubCapability(persona);
        return "Sub-Capability = '" + subCapabilityRaw + "' but engine derived persona = '"
                + persona + "' (expected '" + expected + "')";
    }

    private static String expectedSubCapability(String persona) {
        return switch (persona) {
            case "Fullstack Java", "Fullstack .NET" -> "Full Stack";
            case "Frontend" -> "Front End";
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // Demand
    // ------------------------------------------------------------------

    /**
     * load_and_classify_demand()'s footer-stripping + Status=='Approved' filter.
     */
    public static List<Map<String, String>> stripFooterAndFilterApproved(List<Map<String, String>> rawRows) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> row : rawRows) {
            String reqId = row.get("Job Requisition ID");
            if (reqId == null || reqId.isBlank()) {
                continue;
            }
            String trimmed = reqId.trim();
            if (trimmed.equalsIgnoreCase("total")) {
                continue;
            }
            boolean looksNumeric = isNumeric(trimmed);
            boolean looksLikeId = trimmed.matches("^[\\w\\-/]+$");
            if (!looksNumeric && !looksLikeId) {
                continue; // matches Python's footer/description-row strip
            }
            if (row.containsKey("Status") && !"Approved".equals(row.get("Status"))) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    public static List<DemandClassifiedRow> classifyDemand(List<Map<String, String>> cleanedRows) {
        List<DemandClassifiedRow> out = new ArrayList<>();
        for (Map<String, String> row : cleanedRows) {
            String subBand = nz(row.get("Demand Sub Band Name"));
            String masMappingRaw = nz(row.get("MAS Mapping"));
            ClassificationResult result = DemandClassifier.classify(row.get("Final Skill Cluster"));

            boolean badBand = Engine.bandIndex(subBand) == null;
            String classificationStatus;
            if (badBand) {
                result = ClassificationResult.unclassified(
                        "DATA ISSUE — Demand Sub Band '" + subBand + "' is outside the supported "
                                + "E1.1-E3.2 ladder (needs reviewer confirmation of the requisition's real band position)");
                classificationStatus = "data_issue";
            } else {
                classificationStatus = result.persona() == null ? "unclassified" : "classified";
            }

            // MAS Mapping SCOPE GATE (2026-10-01) — mirrors classify_inputs.py's scope_mask exactly,
            // including running AFTER the band check above so the two can't silently disagree (an
            // out-of-scope row always wins, same last-one-wins order as the Python side). Demand
            // previously had NO such gate — only filterPhase1Supply() did, via Engine.PHASE1_MAS_MAPPING
            // — so every MAS Mapping value flowed straight into skill-text classification. That broke
            // once the real Demand.xlsx widened beyond Full Stack/Front End to also include
            // Tpm/Testing/Polyglot/Devops & Sre/Integration/Mainframe/Architects & Emerging: e.g. 32
            // "Java,Microservices,React.js" rows tagged MAS Mapping='Tpm' (Technical Project Management,
            // not an engineering role) were getting classified Fullstack Java purely from skill-cluster
            // text. Engine.PHASE1_MAS_MAPPING is now sourced from the mas_mapping_categories DB table
            // (see V7__mas_mapping_categories.sql and MasMappingScope — RefreshService sets it via
            // Engine.setPhase1MasMapping() before calling into this class) instead of being a fixed
            // literal, so the active set can change without a code change — see that migration's header
            // comment before changing what's active; activating a category also needs real persona/
            // supply-pool work on top, not just this flag. Excluded rows are NOT dropped — same
            // treatment as bad-band rows above — they're cleared to persona=null with an explicit SCOPE
            // status, kept visible in the data.
            boolean outOfScope = !Engine.PHASE1_MAS_MAPPING.contains(masMappingRaw);
            if (outOfScope) {
                result = ClassificationResult.unclassified(
                        "OUT OF PHASE 1 SCOPE — MAS Mapping '" + (masMappingRaw == null ? "(blank)" : masMappingRaw)
                                + "' is not an active category yet; skill-cluster text was not evaluated");
                classificationStatus = "out_of_scope";
            }

            boolean masFlag = masMappingCheck(masMappingRaw, result.persona());
            boolean frontendAnchorFlag = frontendAnchorCheck(row.get("Final Skill Cluster"),
                    result.persona(), result.frameworkConfirmed());

            Integer balancePositions = parseIntOrNull(row.get("Balance Positions"));
            String dueCategory = nz(row.get("Due Category_New"));

            String hash = badBand
                    ? sha256("BAD_BAND|" + subBand)
                    : sha256(nz(row.get("Final Skill Cluster")) + "|" + subBand);

            out.add(new DemandClassifiedRow(
                    nz(row.get("Job Requisition ID")),
                    nz(row.get("cluster")),                 // best-effort mapping — see README note
                    nz(row.get("Final Skill Cluster")),
                    masMappingRaw,
                    nz(row.get("Additional Request")),
                    nz(row.get("Personnel Sub Area Name")),
                    subBand,
                    balancePositions,
                    dueCategory,
                    nz(row.get("Customer")),
                    nz(row.get("Project Name")),
                    nz(row.get("New-Ageing")),
                    result,
                    classificationStatus,
                    masFlag,
                    frontendAnchorFlag,
                    hash
            ));
        }
        return out;
    }

    /**
     * Mirrors mas_mapping_check() in classify_inputs.py. Validation-only.
     * <p>
     * Narrowed 2026-10-01: this used to ALSO return true for any masMappingRaw outside Engine.PHASE1_MAS_MAPPING
     * ("outside Phase 1 values"). That branch is now dead code by construction — classifyDemand()'s new MAS Mapping
     * scope gate already clears persona and sets an explicit OUT OF PHASE 1 SCOPE status for exactly those rows before
     * this method ever runs on them, so {@code persona == null} below already catches them. Keeping the old branch
     * would have double-reported the same thing two different ways for every excluded row. This method now does one
     * job: among rows already in active scope, does MAS Mapping privately agree with the engine-derived persona.
     */
    public static boolean masMappingCheck(String masMappingRaw, String persona) {
        if (persona == null) {
            return false; // out of Phase 1 scope or otherwise unclassified; nothing to compare against
        }
        if (masMappingRaw.equals("Full Stack") && !(persona.equals("Fullstack Java") || persona.equals("Fullstack .NET"))) {
            return true;
        }
        return masMappingRaw.equals("Front End") && !persona.equals("Frontend");
    }

    public static String masMappingNote(String masMappingRaw, String persona) {
        if (!masMappingCheck(masMappingRaw, persona)) {
            return null;
        }
        return "MAS Mapping = '" + masMappingRaw + "' but engine derived persona = '" + persona + "'";
    }

    /**
     * Mirrors frontend_anchor_check() in classify_inputs.py. Validation-only — never changes DemandClassifier's
     * routing, only flags a Fullstack Java/.NET row for reviewer attention when its Final Skill Cluster names a
     * frontend framework (React/Angular/MEAN) without the matching backend framework (Spring/ASP.NET) confirmed. See
     * classify_inputs.py's frontend_anchor_check() docstring and DemandClassifier's Java-anchor-branch comment for the
     * full history of why this is a review flag and never an automatic reroute.
     */
    public static boolean frontendAnchorCheck(String finalSkillClusterRaw, String persona, Boolean frameworkConfirmed) {
        if (!"Fullstack Java".equals(persona) && !"Fullstack .NET".equals(persona)) {
            return false;
        }
        if (Boolean.TRUE.equals(frameworkConfirmed)) {
            return false; // backend framework already confirmed -- no ambiguity
        }
        return hasFrontendAnchor(finalSkillClusterRaw);
    }

    public static String frontendAnchorNote(String finalSkillClusterRaw, String persona, Boolean frameworkConfirmed) {
        if (!frontendAnchorCheck(finalSkillClusterRaw, persona, frameworkConfirmed)) {
            return null;
        }
        String fwName = "Fullstack Java".equals(persona) ? "Spring" : "ASP.NET";
        return "Persona = '" + persona + "' but frontend framework (React/Angular) named without "
                + fwName + " confirmation; verify persona against MAS Mapping/job description before trusting this match";
    }

    private static boolean hasFrontendAnchor(String skillText) {
        List<String> tokens = tokenize(skillText);
        java.util.Set<String> tokset = new java.util.LinkedHashSet<>(tokens);
        boolean hasReact = !java.util.Collections.disjoint(tokset, Engine.REACT_TOKENS)
                || containsWord(tokens, "next.js");
        boolean hasAngular = containsWord(tokens, "angular");
        boolean hasNode = !java.util.Collections.disjoint(tokset, Engine.NODE_TOKENS);
        boolean hasMernExtra = !java.util.Collections.disjoint(tokset, Engine.MERN_STACK_TOKENS);
        return hasReact || hasAngular || (hasNode && hasMernExtra);
    }

    private static List<String> tokenize(String text) {
        if (text == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : text.split(",")) {
            String s = t.strip().toLowerCase();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean containsWord(List<String> tokens, String word) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(word) + "\\b");
        for (String t : tokens) {
            if (p.matcher(t).find()) {
                return true;
            }
        }
        return false;
    }

    private static Long parseEmployeeId(String raw) {
        String s = nz(raw);
        if (s == null) {
            return null;
        }
        try {
            return (long) Double.parseDouble(s); // handles "12345" and "12345.0" alike
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private static boolean isNumeric(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Integer parseIntOrNull(String raw) {
        String s = nz(raw);
        if (s == null) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(s)); // ExcelSheetReader renders whole numbers without ".0" already, but tolerate either
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleOrNull(String raw) {
        String s = nz(raw);
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String nz(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String trimmed = s.trim();
        return PANDAS_NA_TOKENS.contains(trimmed) ? null : trimmed;
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always available on the JVM
        }
    }

    public record SupplyClassifiedRow(
            Long employeeId,
            String employeeName,
            String band,
            String subBand,
            String location,
            String capability,
            String afdStatus,
            String skillClusterRaw,
            String subCapabilityRaw,
            Integer benchAgeingDays,
            String rating,
            Double score,
            // Added for the shortlist-workbook export (task #19) — build_matches.py's
            // write_shortlist_workbook.py reads emp['Prime/NV'] straight through as a
            // display-only field on the employee header line. Never used by classification
            // or matching, so it's a plain pass-through, same treatment as employeeName/band/etc.
            String primeNv,
            ClassificationResult classification,   // persona()==null for unclassified/data-issue rows
            String classificationStatus,           // "classified" | "unclassified" | "data_issue"
            boolean subCapabilityMismatchFlag,
            // Added 2026-10-01 (V8 migration) — mirrors DemandClassifiedRow's masMappingRaw below.
            // Needed for MatchingService.masMappingCrossCheck(); NEVER used to filter/match beyond
            // the existing Phase 1 scope gate (filterPhase1Supply() above already consumed the raw
            // value for that purpose — this is a separate, later-stage pass-through of the same
            // source column, same relationship subCapabilityRaw has to scFlag above).
            String masMappingRaw,
            String sourceRowHash
    ) {
    }

    public record DemandClassifiedRow(
            String demandId,
            String clusterNameRaw,
            String finalSkillClusterRaw,
            String masMappingRaw,
            String additionalRequestRaw,
            String location,
            String band,
            Integer balancePositions,
            String dueCategory,
            // Added for the shortlist-workbook export (task #19) — build_matches.py reads
            // dem.get('Customer')/dem.get('Project Name') straight through as display-only
            // fields on each match row. Never used by classification or matching.
            String customer,
            String projectName,
            String newAgeing,
            ClassificationResult classification,
            String classificationStatus,
            boolean masMappingMismatchFlag,
            boolean frontendAnchorFlag,
            String sourceRowHash
    ) {
    }
}
