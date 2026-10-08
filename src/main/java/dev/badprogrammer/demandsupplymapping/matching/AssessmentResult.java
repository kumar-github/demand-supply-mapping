package dev.badprogrammer.demandsupplymapping.matching;

/**
 * Mirrors the dict shape returned by assessment_signal() in build_matches.py (lines 104-115). Unlike SignalResult,
 * there's no quality/hardExclude here — assessment never excludes or caps a tier by itself, it only supplies the rank
 * cap_employee_rows() is NOT keyed on (ageing_rank/balance_positions are) and the one-liner text.
 */
public record AssessmentResult(
        int rank,
        String collapsed,
        String expanded
) {
}
