package dev.badprogrammer.demandsupplymapping.review.dto;

import java.util.List;

public record ReviewWorkspaceDto(
        String demandId,
        String clusterNameRaw,
        String finalSkillClusterRaw,
        String additionalRequestRaw,
        String persona,
        String subPersona,
        String location,
        String band,
        String customer,
        String projectName,
        Integer balancePositions,
        String dueCategory,
        String newAgeing,
        DemandLifecycleState lifecycleState,
        List<ReviewCandidateDto> candidates,
        // For auto-advance: the next demand in the same ageing-sorted queue after this one, or
        // null if this is the last one (or the queue is otherwise exhausted). The queue view
        // computes the full order; the workspace just needs where to go next.
        String nextDemandId
) {
}
