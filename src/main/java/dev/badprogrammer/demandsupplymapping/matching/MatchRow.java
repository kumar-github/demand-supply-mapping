package dev.badprogrammer.demandsupplymapping.matching;

/**
 * One employee x demand pairing row, carrying exactly the fields cap_employee_rows() (build_matches.py lines 317-364)
 * needs to sort and cap. Mirrors that function's per-row dict — display/identifying fields (customer, project,
 * band/location text, etc.) are NOT needed by the capping algorithm itself and are deliberately left out of this type;
 * MatchingRunService (step 3) owns building the full MatchCandidate entity from whichever row survives capping.
 */
public record MatchRow(
        Long employeeId,
        String demandId,
        String overallTier,       // Strong | Good | Weak | Excluded
        String skillQuality,       // SignalResult.quality() from skillSignal()
        int ageingRank,            // MatchingService.ageingRank(dueCategory)
        Integer balancePositions,
        // raw Balance Positions from demand_enriched; null treated as 0 for sorting (see MatchingService.capEmployeeRows)
        String oneLiner,
        String skillSignal,
        String bandSignal,
        String locationSignal,
        // NOT part of the build_matches.py port — see MatchingService.isOneBandBelow()'s Javadoc.
        // Used only to route an Excluded row into capEmployeeRows()'s separate Override-eligible
        // bucket instead of the generic near-miss bucket; never persisted onto MatchCandidate
        // itself (DemandReviewService re-derives this from raw sub-band values at review time).
        boolean bandOverrideEligible
) {
}
