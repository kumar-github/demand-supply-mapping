package dev.badprogrammer.demandsupplymapping.review.dto;

public record ReviewQueueItemDto(
        String demandId,
        String clusterNameRaw,
        String persona,
        String subPersona,
        String location,
        String band,
        String customer,
        String projectName,
        Integer balancePositions,
        String dueCategory,
        String newAgeing,
        int ageingRank,
        DemandLifecycleState lifecycleState,
        int strongCount,
        int goodCount,
        int weakCount,
        int decidedCount,
        int undecidedStrongGoodCount,
        int approvedCount,
        boolean flaggedForHiring
) {
}
