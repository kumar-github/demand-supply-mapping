package dev.badprogrammer.demandsupplymapping.review.dto;

import java.time.OffsetDateTime;

/**
 * One candidate card's data — the "two states, four dimensions" shape from demand-supply-mapping-requirements.md's
 * Output Design section. Each dimension carries both its Collapsed plain-English text (always shown) and its Expanded
 * raw-value text (shown only once the reviewer expands the card, either per-card or via "expand all") — recomputed
 * fresh per {@code DemandReviewService.workspace()} call via {@code MatchingService}'s signal methods rather than read
 * back from {@code match_candidates}, which only persists the quality CODE (e.g. "core", "same_city") and the
 * already-folded {@link #oneLiner()}, not this full text.
 */
public record ReviewCandidateDto(
        Long employeeId,
        String employeeName,
        String band,
        String subBand,
        String location,
        Integer benchAgeingDays,
        String rating,
        String overallTier,     // Strong / Good / Weak / Excluded (Excluded only when overrideEligible)
        String oneLiner,
        String skillCollapsed,
        String skillExpanded,
        String bandCollapsed,
        String bandExpanded,
        String locationCollapsed,
        String locationExpanded,
        String assessmentCollapsed,
        String assessmentExpanded,
        /**
         * True only for the one-sub-band-below "below_one" case carved out in
         * MatchingService.capEmployeeRows() — the sole case the Review interaction model's
         * Override action applies to. overallTier() is "Excluded" for these, same as any other
         * hard exclude, so this flag (not the tier) is what the view uses to decide whether to
         * render Propose/Reject or Override.
         */
        boolean overrideEligible,
        String decisionStatus,  // pending / approved / rejected / staffed / overridden / null (never decided)
        OffsetDateTime decidedAt,
        String decidedByName
) {
    public boolean isDecided() {
        return decisionStatus != null
                && !decisionStatus.equals("pending");
    }
}
