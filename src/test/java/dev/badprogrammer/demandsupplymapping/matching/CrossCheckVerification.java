package dev.badprogrammer.demandsupplymapping.matching;

/**
 * Direct verification of MatchingService.masMappingCrossCheck()/masMappingCrossCheckNote() — the pairing-level MAS
 * Mapping cross-check added 2026-10-01 (the Java port of build_matches.py's new mas_mapping_cross_check logic,
 * requested so the reviewer-facing check ships identically on both sides — see demand-supply-mapping-requirements.md's
 * 2026-10-01 entry). Mirrors the four cases hand-verified for the Python sibling: 1. agreement (same value, trimmed) ->
 * no flag 2. disagreement -> flag, with a note naming both raw values 3. either side missing (null) -> no flag (nothing
 * to compare) 4. whitespace-only differences -> treated as agreement (both sides .strip()'d first)
 * <p>
 * No Spring/JPA/DB needed — MatchingService's only dependency is the harmless @Service stereotype annotation, so this
 * runs as a plain main(), same reproduce-independently discipline as RefreshLogicVerification/FullPersonaVerification.
 * Run with: javac -d out MatchingService.java MatchRow.java SignalResult.java ClassificationResult.java
 * AssessmentResult.java Engine.java CrossCheckVerification.java (against a stub org.springframework.stereotype.Service
 * annotation, since Maven Central is blocked in this sandbox) then java -cp out
 * com.example.benchmatch.matching.CrossCheckVerification
 */
public class CrossCheckVerification {

    private static int failures = 0;

    public static void main(String[] args) {
        MatchingService svc = new MatchingService();

        // Case 1: agreement
        check(svc, "Full Stack", "Full Stack", false, null, "agreement");

        // Case 2: disagreement
        check(svc, "Full Stack", "Front End", true,
                "Employee MAS Mapping = 'Full Stack' but demand MAS Mapping = 'Front End'",
                "disagreement");

        // Case 3a: employee side missing
        check(svc, null, "Full Stack", false, null, "employee side missing");

        // Case 3b: demand side missing
        check(svc, "Full Stack", null, false, null, "demand side missing");

        // Case 3c: both sides missing
        check(svc, null, null, false, null, "both sides missing");

        // Case 4: whitespace-only differences treated as agreement
        check(svc, "  Full Stack  ", "Full Stack", false, null, "whitespace-insensitive agreement");

        // Case 4b: whitespace padding on a real disagreement still flags, with trimmed text in the note
        check(svc, "  Full Stack  ", " Front End ", true,
                "Employee MAS Mapping = 'Full Stack' but demand MAS Mapping = 'Front End'",
                "whitespace-insensitive disagreement");

        if (failures == 0) {
            System.out.println("\nALL CASES PASSED (7/7).");
        } else {
            System.out.println("\n" + failures + " CASE(S) FAILED.");
            System.exit(1);
        }
    }

    private static void check(MatchingService svc, String emp, String dem, boolean expectFlag,
                              String expectNote, String label) {
        boolean flag = svc.masMappingCrossCheck(emp, dem);
        String note = svc.masMappingCrossCheckNote(emp, dem);
        boolean ok = flag == expectFlag && java.util.Objects.equals(note, expectNote);
        System.out.println((ok ? "PASS" : "FAIL") + " - " + label + ": emp=" + q(emp) + " dem=" + q(dem)
                + " -> flag=" + flag + " note=" + q(note));
        if (!ok) {
            failures++;
            System.out.println("       expected: flag=" + expectFlag + " note=" + q(expectNote));
        }
    }

    private static String q(String s) {
        return s == null ? "null" : "'" + s + "'";
    }
}
