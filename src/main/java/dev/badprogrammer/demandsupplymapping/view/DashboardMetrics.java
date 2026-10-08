package dev.badprogrammer.demandsupplymapping.view;

import dev.badprogrammer.demandsupplymapping.entity.DemandEnriched;
import dev.badprogrammer.demandsupplymapping.entity.MatchCandidate;
import dev.badprogrammer.demandsupplymapping.entity.RefreshRun;
import dev.badprogrammer.demandsupplymapping.entity.SupplyEnriched;
import dev.badprogrammer.demandsupplymapping.repository.DemandEnrichedRepository;
import dev.badprogrammer.demandsupplymapping.repository.MatchCandidateRepository;
import dev.badprogrammer.demandsupplymapping.repository.RefreshRunRepository;
import dev.badprogrammer.demandsupplymapping.repository.SupplyEnrichedRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Backs {@link DashboardView}. Everything here is computed directly from existing tables (supply_enriched /
 * demand_enriched / match_candidates / refresh_runs) — there is deliberately no invented number on this dashboard.
 * <p>
 * One real gap this surfaces: the mockup's "Awaiting review" / "128 of 243 reviewed" / "4 days left in cycle" widget
 * assumes a reviewer-workflow state (demand_review_state) that doesn't exist in this codebase yet (no entity, no table
 * — see the readme's "Status as of this handoff"). Rather than fabricate those numbers, this service reports what IS
 * real and derivable today: which active, classified employees have no Strong/Good match at all in the latest matching
 * run. That's a genuinely useful "needs attention" signal on its own, just not the same thing as a reviewer's
 * open/closed queue — the queue widget and progress bar will need a real rebuild once demand_review_state exists.
 */
@Service
public class DashboardMetrics {

    private static final Set<String> NEEDS_ATTENTION_EXCLUDED_TIERS = Set.of("Strong", "Good");

    private final SupplyEnrichedRepository supplyRepository;
    private final DemandEnrichedRepository demandRepository;
    private final MatchCandidateRepository matchCandidateRepository;
    private final RefreshRunRepository refreshRunRepository;

    public DashboardMetrics(SupplyEnrichedRepository supplyRepository,
                            DemandEnrichedRepository demandRepository,
                            MatchCandidateRepository matchCandidateRepository,
                            RefreshRunRepository refreshRunRepository) {
        this.supplyRepository = supplyRepository;
        this.demandRepository = demandRepository;
        this.matchCandidateRepository = matchCandidateRepository;
        this.refreshRunRepository = refreshRunRepository;
    }

    private static String betterTier(String a, String b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(String tier) {
        return switch (tier) {
            case "Strong" -> 3;
            case "Good" -> 2;
            case "Weak" -> 1;
            default -> 0; // Excluded, or anything unexpected
        };
    }

    public Snapshot load() {
        List<SupplyEnriched> activeSupply = supplyRepository.findByIsActiveTrue();
        List<SupplyEnriched> classifiedSupply = activeSupply.stream()
                .filter(s -> "classified".equals(s.getClassificationStatus()))
                .toList();

        List<DemandEnriched> activeDemand = demandRepository.findByIsActiveTrue();
        List<DemandEnriched> classifiedDemand = activeDemand.stream()
                .filter(d -> "classified".equals(d.getClassificationStatus()))
                .toList();
        int outOfScopeOrUnclassifiedDemand = activeDemand.size() - classifiedDemand.size();

        // match_candidates is fully rewritten on every matching run (see its table comment), so
        // every row currently in the table belongs to whichever run_id is highest. No separate
        // "latest run" query exists yet on MatchCandidateRepository, so it's derived here.
        List<MatchCandidate> allCandidates = matchCandidateRepository.findAll();
        Long latestRunId = allCandidates.stream().map(MatchCandidate::getRunId)
                .max(Comparator.naturalOrder()).orElse(null);
        List<MatchCandidate> latestRun = latestRunId == null
                ? List.of()
                : allCandidates.stream().filter(c -> latestRunId.equals(c.getRunId())).toList();

        Map<Long, String> bestTierByEmployee = latestRun.stream()
                .collect(Collectors.toMap(
                        MatchCandidate::getEmployeeId,
                        MatchCandidate::getOverallTier,
                        DashboardMetrics::betterTier));

        long strong = bestTierByEmployee.values().stream().filter("Strong"::equals).count();
        long good = bestTierByEmployee.values().stream().filter("Good"::equals).count();
        long weak = bestTierByEmployee.values().stream().filter("Weak"::equals).count();

        List<QueueRow> needsAttention = classifiedSupply.stream()
                .filter(s -> !NEEDS_ATTENTION_EXCLUDED_TIERS.contains(
                        bestTierByEmployee.getOrDefault(s.getEmployeeId(), "None")))
                .sorted(Comparator.comparing(SupplyEnriched::getBenchAgeingDays,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(s -> new QueueRow(s.getEmployeeId(), s.getEmployeeName(), s.getBand(), s.getLocation()))
                .limit(5)
                .toList();

        // Specifically the MATCHING run that produced `latestRun` above — not "any refresh of any
        // kind", which is what a naive max(finishedAt) over the whole refresh_runs table would
        // give (supply_enriched and demand_enriched refreshes write rows to that same table too,
        // under source="supply"/"demand"). A supply refresh running after the last matching pass
        // would otherwise make this card claim a "last refreshed" time newer than the match
        // results it's actually showing — technically true of *a* refresh, false of *this* one.
        // match_candidates.run_id is exactly refresh_runs.run_id for a "matching" source row (see
        // MatchingRunService), so looking it up by that id is precise, not another max-scan.
        OffsetDateTime lastRefreshedAt = latestRunId == null
                ? null
                : refreshRunRepository.findById(latestRunId).map(RefreshRun::getFinishedAt).orElse(null);

        return new Snapshot(
                classifiedSupply.size(),
                classifiedDemand.size(),
                outOfScopeOrUnclassifiedDemand,
                needsAttention.size(),
                (int) strong, (int) good, (int) weak,
                needsAttention,
                lastRefreshedAt
        );
    }

    public record QueueRow(Long employeeId, String employeeName, String band, String location) {
    }

    public record Snapshot(
            int activeBenchCount,
            int activeDemandCount,
            int outOfScopeOrUnclassifiedDemandCount,
            int needsAttentionCount,
            int strongCount,
            int goodCount,
            int weakCount,
            List<QueueRow> needsAttentionQueue,
            OffsetDateTime lastRefreshedAt) {
    }
}
