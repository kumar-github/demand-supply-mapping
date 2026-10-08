package dev.badprogrammer.demandsupplymapping.review;

import dev.badprogrammer.demandsupplymapping.review.dto.DecisionRequest;
import dev.badprogrammer.demandsupplymapping.review.dto.ReviewQueueItemDto;
import dev.badprogrammer.demandsupplymapping.review.dto.ReviewWorkspaceDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read/write API over the demand-side Review feature — same role as DemandController/ SupplyController play for their
 * grids, but this one also writes (the Propose/Reject decision), since Review is the first part of this app that isn't
 * read-only.
 */
@RestController
@RequestMapping("/api/review")
public class DemandReviewController {

    private final DemandReviewService service;

    public DemandReviewController(DemandReviewService service) {
        this.service = service;
    }

    @GetMapping("/queue")
    public List<ReviewQueueItemDto> queue() {
        return service.queue();
    }

    @GetMapping("/demand/{demandId}")
    public ReviewWorkspaceDto workspace(@PathVariable String demandId) {
        return service.workspace(demandId);
    }

    @PostMapping("/demand/{demandId}/candidates/{employeeId}/decision")
    public ReviewWorkspaceDto decide(@PathVariable String demandId, @PathVariable Long employeeId,
                                     @RequestBody DecisionRequest request) {
        return service.decide(demandId, employeeId, request);
    }
}
