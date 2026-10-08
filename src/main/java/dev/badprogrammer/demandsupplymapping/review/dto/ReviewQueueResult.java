package dev.badprogrammer.demandsupplymapping.review.dto;

import java.util.List;

/**
 * The Review queue's items plus its "no coverage" count, computed together off ONE pass over match_candidates. Added
 * 2026-10-06 specifically so {@code ReviewQueueView}'s constructor stops paying for match_candidates.findAll() twice
 * per page load (once via {@code queue()}, once via the separate {@code noCoverageCount()}) — see
 * DemandReviewService.queueAndCoverage()'s Javadoc. {@code queue()} and {@code noCoverageCount()} themselves are left
 * in place, unchanged, for their other callers (DemandReviewController, DemandReviewService.nextDemandIdAfter()) that
 * only need one or the other and shouldn't have to pay for the half they don't.
 */
public record ReviewQueueResult(
        List<ReviewQueueItemDto> items,
        int noCoverageCount
) {
}
