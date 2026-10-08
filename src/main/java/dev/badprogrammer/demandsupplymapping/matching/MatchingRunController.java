package dev.badprogrammer.demandsupplymapping.matching;

import dev.badprogrammer.demandsupplymapping.entity.RefreshRun;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal trigger for MatchingRunService, same pattern/rationale as RefreshController (com.hcltech.benchmatch.refresh)
 * — exercisable end to end without waiting on a full REST layer or Vaadin UI. No request body: matching always runs
 * against whatever is currently active in supply_enriched/demand_enriched, there's no file to point at.
 * <p>
 * Note: unlike RefreshController's summarize(), this uses a mutable map rather than Map.of(...) — a matching run
 * deliberately leaves rowsChanged null (see MatchingRunService.finishRun()'s comment), and Map.of(...) throws
 * NullPointerException on a null value.
 */
@RestController
public class MatchingRunController {

    private final MatchingRunService matchingRunService;

    public MatchingRunController(MatchingRunService matchingRunService) {
        this.matchingRunService = matchingRunService;
    }

    @PostMapping("/api/matching/run")
    public Map<String, Object> runMatching() {
        RefreshRun run = matchingRunService.runMatching();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", run.getRunId());
        summary.put("source", run.getSource());
        summary.put("status", run.getStatus());
        summary.put("rowsIn", run.getRowsIn());
        summary.put("rowsNew", run.getRowsNew());
        summary.put("rowsChanged", run.getRowsChanged());
        summary.put("rowsFlagged", run.getRowsFlagged());
        return summary;
    }
}
