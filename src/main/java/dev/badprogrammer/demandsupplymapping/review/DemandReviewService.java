package dev.badprogrammer.demandsupplymapping.review;

import dev.badprogrammer.demandsupplymapping.entity.*;
import dev.badprogrammer.demandsupplymapping.matching.ClassificationResult;
import dev.badprogrammer.demandsupplymapping.matching.MatchingService;
import dev.badprogrammer.demandsupplymapping.repository.*;
import dev.badprogrammer.demandsupplymapping.review.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Demand-side Review queue + workspace: the first entry point built against the
 * demand_candidate_decisions/decision_history PERSISTENT tables (full_db_design.sql section 3, created by
 * V1__init_schema.sql but unused by any Java code until now). Supply-side review is a separate, not-yet-built entry
 * point into these SAME rows (pair-level decisions, keyed by (demand_id, employee_id) — see
 * {@link DemandCandidateDecision}'s Javadoc) — nothing here is demand-specific at the data-model level, only at the UI
 * level.
 * <p>
 * Deliberately a straight in-memory pass over {@code findAll()}/{@code findByIsActiveTrue()} results rather than
 * per-demand queries — the same approach {@code ShortlistWorkbookService} already uses at this data volume (hundreds of
 * demands, hundreds of supply rows, a few thousand match_candidates), so this isn't a new performance tradeoff for the
 * codebase.
 * <p>
 * "Propose"/"Reject" record ONLY an in-app status for now (demand_candidate_decisions.status = 'approved'/'rejected') —
 * no external system is called, per the explicit 2026-10-05 scope decision ("remaining all takes place outside this
 * app"). No collision handling between concurrent reviewers either, per that same decision — last write wins, same as
 * every other table in this app.
 */
@Service
public class DemandReviewService {

    /**
     * Placeholder single reviewer identity until real auth exists — see V10__reviewer_default_user.sql's comment for
     * the plan to replace this.
     */
    private static final String CURRENT_USER_EMAIL = "tag-reviewer@bench-match.local";

    private static final Set<String> STRONG_GOOD = Set.of("Strong", "Good");
    /**
     * Shown as ordinary Propose/Reject tiers. The one-sub-band-below Override-eligible bucket is NOT a tier in this set
     * — it's Excluded, surfaced separately; see {@link #isOverrideEligible}. Every other Excluded reason (two+ below,
     * too senior, wrong sub-persona, unclassified) stays fully hidden, per demand-supply-mapping-requirements.md's
     * "never appears on the shortlist at all" rule for hard excludes.
     */
    private static final Set<String> SHOWN_TIERS = Set.of("Strong", "Good", "Weak");
    private static final Map<String, Integer> TIER_RANK = Map.of("Strong", 0, "Good", 1, "Weak", 2, "Excluded", 3);

    private final DemandEnrichedRepository demandRepository;
    private final SupplyEnrichedRepository supplyRepository;
    private final MatchCandidateRepository matchCandidateRepository;
    private final DemandCandidateDecisionRepository decisionRepository;
    private final DecisionHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final MatchingService matchingService;
    private final DemandReviewStateRepository reviewStateRepository;

    public DemandReviewService(DemandEnrichedRepository demandRepository, SupplyEnrichedRepository supplyRepository,
                               MatchCandidateRepository matchCandidateRepository,
                               DemandCandidateDecisionRepository decisionRepository,
                               DecisionHistoryRepository historyRepository, UserRepository userRepository,
                               MatchingService matchingService, DemandReviewStateRepository reviewStateRepository) {
        this.demandRepository = demandRepository;
        this.supplyRepository = supplyRepository;
        this.matchCandidateRepository = matchCandidateRepository;
        this.decisionRepository = decisionRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.matchingService = matchingService;
        this.reviewStateRepository = reviewStateRepository;
    }

    /**
     * Actionable (≥1 Strong/Good candidate), not-yet-Filled demands, sorted longest-overdue-first — same ageing/urgency
     * comparator ShortlistWorkbookService.generateByDemand() already uses, now applied as a grid's default sort instead
     * of only an export's.
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueItemDto> queue() {
        List<DemandEnriched> demands = activeClassifiedDemands();
        Map<String, List<MatchCandidate>> candidatesByDemand = candidatesByDemand();
        return buildQueueItems(demands, candidatesByDemand);
    }

    /**
     * Queue items + the "no coverage" count together, off ONE match_candidates.findAll() pass — see ReviewQueueResult's
     * own Javadoc for why this exists alongside {@link #queue()} and {@link #noCoverageCount()} rather than replacing
     * them: ReviewQueueView is the one caller that needs both every time it loads, and was previously paying for two
     * full scans to get them (a 2026-10-06 perf finding).
     */
    @Transactional(readOnly = true)
    public ReviewQueueResult queueAndCoverage() {
        List<DemandEnriched> demands = activeClassifiedDemands();
        Map<String, List<MatchCandidate>> candidatesByDemand = candidatesByDemand();
        return new ReviewQueueResult(buildQueueItems(demands, candidatesByDemand), noCoverageCount(demands, candidatesByDemand));
    }

    private List<DemandEnriched> activeClassifiedDemands() {
        return demandRepository.findByIsActiveTrue().stream()
                .filter(d -> d.getPersona() != null)
                .toList();
    }

    private Map<String, List<MatchCandidate>> candidatesByDemand() {
        return matchCandidateRepository.findAll().stream()
                .collect(Collectors.groupingBy(MatchCandidate::getDemandId));
    }

    private List<ReviewQueueItemDto> buildQueueItems(List<DemandEnriched> demands,
                                                     Map<String, List<MatchCandidate>> candidatesByDemand) {
        Map<String, List<DemandCandidateDecision>> decisionsByDemand = decisionRepository.findAll().stream()
                .collect(Collectors.groupingBy(DemandCandidateDecision::getDemandId));
        Map<String, Boolean> flaggedByDemand = reviewStateRepository.findAll().stream()
                .collect(Collectors.toMap(DemandReviewState::getDemandId, DemandReviewState::isFlaggedForHiring));

        return demands.stream()
                .map(d -> toQueueItem(d, candidatesByDemand.getOrDefault(d.getDemandId(), List.of()),
                        decisionsByDemand.getOrDefault(d.getDemandId(), List.of()),
                        flaggedByDemand.getOrDefault(d.getDemandId(), false)))
                .filter(item -> item != null && item.lifecycleState() != DemandLifecycleState.FILLED)
                .sorted(Comparator.<ReviewQueueItemDto>comparingInt(ReviewQueueItemDto::ageingRank)
                        .thenComparingInt(item -> -(item.balancePositions() == null ? 0 : item.balancePositions())))
                .toList();
    }

    /**
     * Count of active, classified demands with ZERO Strong/Good candidates — the mock's "89 have zero candidate" header
     * stat (Main.dc.html). These are exactly the demands {@link #toQueueItem} filters out of the queue entirely (not
     * actionable from this screen) — they belong on a future "Coverage Log"/"No Coverage" view, not yet built (today's
     * sidebar item is a disabled "SOON" placeholder), so this is currently just a count, not a link target.
     */
    @Transactional(readOnly = true)
    public int noCoverageCount() {
        return noCoverageCount(activeClassifiedDemands(), candidatesByDemand());
    }

    private int noCoverageCount(List<DemandEnriched> demands, Map<String, List<MatchCandidate>> candidatesByDemand) {
        return (int) demands.stream()
                .filter(d -> candidatesByDemand.getOrDefault(d.getDemandId(), List.of()).stream()
                        .noneMatch(c -> STRONG_GOOD.contains(c.getOverallTier())))
                .count();
    }

    @Transactional(readOnly = true)
    public ReviewWorkspaceDto workspace(String demandId) {
        DemandEnriched demand = demandRepository.findById(demandId)
                .orElseThrow(() -> new NoSuchElementException("No demand " + demandId));

        List<MatchCandidate> allMatchRows = matchCandidateRepository.findByDemandId(demandId);
        // Fetched before filtering (not after, like the old SHOWN_TIERS-only version) because
        // isOverrideEligible() below needs the employee's raw sub-band to tell a one-below
        // Excluded row apart from any other hard exclude — see MatchingService.isOneBandBelow().
        Map<Long, SupplyEnriched> allSupplyById = supplyRepository.findAllById(
                allMatchRows.stream().map(MatchCandidate::getEmployeeId).toList()
        ).stream().collect(Collectors.toMap(SupplyEnriched::getEmployeeId, s -> s));

        List<MatchCandidate> candidates = allMatchRows.stream()
                .filter(c -> SHOWN_TIERS.contains(c.getOverallTier())
                        || isOverrideEligible(c, allSupplyById.get(c.getEmployeeId()), demand))
                .sorted(Comparator.comparingInt(c -> TIER_RANK.getOrDefault(c.getOverallTier(), 9)))
                .toList();

        Map<Long, DemandCandidateDecision> decisionByEmployee = decisionRepository.findByDemandId(demandId).stream()
                .collect(Collectors.toMap(DemandCandidateDecision::getEmployeeId, d -> d));

        Map<Long, SupplyEnriched> supplyById = candidates.stream()
                .map(MatchCandidate::getEmployeeId)
                .filter(allSupplyById::containsKey)
                .collect(Collectors.toMap(id -> id, allSupplyById::get));

        Map<Integer, String> userNames = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getUserId, User::getDisplayName));

        List<ReviewCandidateDto> candidateDtos = candidates.stream()
                .map(c -> toCandidateDto(c, demand, supplyById.get(c.getEmployeeId()),
                        decisionByEmployee.get(c.getEmployeeId()), userNames))
                .toList();

        DemandLifecycleState lifecycle = lifecycleOf(demand, candidates, decisionByEmployee.values());

        return new ReviewWorkspaceDto(
                demand.getDemandId(), demand.getClusterNameRaw(), demand.getFinalSkillClusterRaw(),
                demand.getAdditionalRequestRaw(),
                demand.getPersona() == null ? null : demand.getPersona().getName(),
                demand.getSubPersona() == null ? null : demand.getSubPersona().getName(),
                demand.getLocation(), demand.getBand(), demand.getCustomer(), demand.getProjectName(),
                demand.getBalancePositions(), demand.getDueCategory(), demand.getNewAgeing(),
                lifecycle, candidateDtos, nextDemandIdAfter(demandId)
        );
    }

    /**
     * Records a Propose ("approved"), Reject ("rejected"), or Override ("overridden") decision on one (demand,
     * employee) pair — an upsert against demand_candidate_decisions' own UNIQUE (demand_id, employee_id), a full
     * status-change row in decision_history, then the refreshed workspace so the view can auto-advance if that was the
     * last undecided candidate.
     * <p>
     * Override is validated as its OWN action, not interchangeable with Propose, per the requirements doc's "kept
     * separate... so it's never mistaken for an ordinary approval": Override is only valid against the
     * one-sub-band-below Excluded candidate it exists for, and Propose/Reject are only valid against an
     * ordinarily-shown Strong/Good/Weak candidate.
     */
    @Transactional
    public ReviewWorkspaceDto decide(String demandId, Long employeeId, DecisionRequest request) {
        String action = request.action();
        boolean isOverrideAction = DemandCandidateDecision.STATUS_OVERRIDDEN.equals(action);
        if (!DemandCandidateDecision.STATUS_APPROVED.equals(action) && !DemandCandidateDecision.STATUS_REJECTED.equals(action)
                && !isOverrideAction) {
            throw new IllegalArgumentException("Unsupported decision action: " + action);
        }

        MatchCandidate candidate = matchCandidateRepository.findByDemandId(demandId).stream()
                .filter(c -> c.getEmployeeId().equals(employeeId))
                .findFirst().orElse(null);
        String engineTier = candidate == null ? null : candidate.getOverallTier();
        DemandEnriched demandForCheck = demandRepository.findById(demandId).orElse(null);
        SupplyEnriched supplyForCheck = supplyRepository.findById(employeeId).orElse(null);
        boolean overrideEligible = candidate != null
                && isOverrideEligible(candidate, supplyForCheck, demandForCheck);

        if (isOverrideAction && !overrideEligible) {
            throw new IllegalArgumentException(
                    "Override is only valid for the one-sub-band-below Excluded candidate it exists for");
        }
        if (!isOverrideAction && DemandCandidateDecision.STATUS_APPROVED.equals(action) && overrideEligible) {
            throw new IllegalArgumentException(
                    "This candidate is band-excluded — use Override instead of Propose, so it's never mistaken for an ordinary approval");
        }

        DemandCandidateDecision decision = decisionRepository.findByDemandIdAndEmployeeId(demandId, employeeId)
                .orElseGet(() -> new DemandCandidateDecision(demandId, employeeId));
        String oldStatus = decision.getDecisionId() == null ? null : decision.getStatus();

        Integer currentUserId = currentUser().getUserId();
        decision.setEngineTierAtDecision(engineTier);
        decision.setStatus(action);
        decision.setDecidedBy(currentUserId);
        decision.setDecidedAt(OffsetDateTime.now());
        decision = decisionRepository.save(decision);

        if (!action.equals(oldStatus)) {
            historyRepository.save(new DecisionHistory(decision.getDecisionId(), oldStatus, action, currentUserId));
        }

        return workspace(demandId);
    }

    private User currentUser() {
        return userRepository.findByEmail(CURRENT_USER_EMAIL)
                .orElseThrow(() -> new IllegalStateException(
                        "No reviewer user seeded — expected V10__reviewer_default_user.sql to have run"));
    }

    private String nextDemandIdAfter(String demandId) {
        List<ReviewQueueItemDto> q = queue();
        int idx = -1;
        for (int i = 0; i < q.size(); i++) {
            if (q.get(i).demandId().equals(demandId)) {
                idx = i;
                break;
            }
        }
        if (idx >= 0 && idx + 1 < q.size()) {
            return q.get(idx + 1).demandId();
        }
        // Current demand no longer in the queue (just became Filled/Exhausted via this decision,
        // or was never actionable) — fall back to the front of the queue so auto-advance still
        // lands somewhere useful, unless that's the demand the reviewer is already leaving.
        return q.stream().map(ReviewQueueItemDto::demandId).filter(id -> !id.equals(demandId)).findFirst().orElse(null);
    }

    private ReviewQueueItemDto toQueueItem(DemandEnriched d, List<MatchCandidate> candidates,
                                           List<DemandCandidateDecision> decisions, boolean flaggedForHiring) {
        List<MatchCandidate> strongGood = candidates.stream().filter(c -> STRONG_GOOD.contains(c.getOverallTier())).toList();
        if (strongGood.isEmpty()) {
            return null; // not actionable — belongs on a future "No Coverage"/Coverage Log view, not here
        }
        int strongCount = (int) candidates.stream().filter(c -> "Strong".equals(c.getOverallTier())).count();
        int goodCount = (int) candidates.stream().filter(c -> "Good".equals(c.getOverallTier())).count();
        int weakCount = (int) candidates.stream().filter(c -> "Weak".equals(c.getOverallTier())).count();

        DemandLifecycleState lifecycle = lifecycleOf(d, candidates, decisions);
        int approvedCount = (int) decisions.stream().filter(dec -> DemandCandidateDecision.STATUS_APPROVED.equals(dec.getStatus())).count();
        int decidedCount = (int) decisions.stream()
                .filter(dec -> DemandCandidateDecision.STATUS_APPROVED.equals(dec.getStatus())
                        || DemandCandidateDecision.STATUS_REJECTED.equals(dec.getStatus())
                        || DemandCandidateDecision.STATUS_OVERRIDDEN.equals(dec.getStatus()))
                .count();
        Set<Long> decidedEmployeeIds = decisions.stream()
                .filter(dec -> DemandCandidateDecision.STATUS_APPROVED.equals(dec.getStatus())
                        || DemandCandidateDecision.STATUS_REJECTED.equals(dec.getStatus()))
                .map(DemandCandidateDecision::getEmployeeId)
                .collect(Collectors.toSet());
        int undecidedStrongGood = (int) strongGood.stream().filter(c -> !decidedEmployeeIds.contains(c.getEmployeeId())).count();

        return new ReviewQueueItemDto(
                d.getDemandId(), d.getClusterNameRaw(),
                d.getPersona() == null ? null : d.getPersona().getName(),
                d.getSubPersona() == null ? null : d.getSubPersona().getName(),
                d.getLocation(), d.getBand(), d.getCustomer(), d.getProjectName(),
                d.getBalancePositions(), d.getDueCategory(), d.getNewAgeing(),
                matchingService.ageingRank(d.getDueCategory()),
                lifecycle, strongCount, goodCount, weakCount, decidedCount, undecidedStrongGood, approvedCount,
                flaggedForHiring
        );
    }

    /**
     * The Review queue's "Flag for hiring" action on an EXHAUSTED demand — writes demand_review_state's own
     * flagged_for_hiring/flagged_for_hiring_reason/_by/_at columns (added by V12__flagged_for_hiring_column.sql, split
     * out from needs_reattention/reattention_reason — see {@link DemandReviewState}'s Javadoc for why). Idempotent
     * upsert, same pattern {@link #decide} already uses against demand_candidate_decisions.
     */
    @Transactional
    public void flagForHiring(String demandId) {
        DemandReviewState state = reviewStateRepository.findById(demandId)
                .orElseGet(() -> new DemandReviewState(demandId));
        state.setFlaggedForHiring(true);
        state.setFlaggedForHiringReason("Every Strong/Good candidate already Proposed or Rejected, positions remain open");
        state.setFlaggedForHiringBy(currentUser().getUserId());
        state.setFlaggedForHiringAt(OffsetDateTime.now());
        reviewStateRepository.save(state);
    }

    private DemandLifecycleState lifecycleOf(DemandEnriched d, List<MatchCandidate> candidates,
                                             Collection<DemandCandidateDecision> decisions) {
        List<MatchCandidate> strongGood = candidates.stream().filter(c -> STRONG_GOOD.contains(c.getOverallTier())).toList();
        int totalPositions = d.getBalancePositions() == null ? 0 : d.getBalancePositions();
        int approvedCount = (int) decisions.stream().filter(dec -> DemandCandidateDecision.STATUS_APPROVED.equals(dec.getStatus())).count();

        if (totalPositions > 0 && approvedCount >= totalPositions) {
            return DemandLifecycleState.FILLED;
        }
        Set<Long> decidedEmployeeIds = decisions.stream()
                .filter(dec -> DemandCandidateDecision.STATUS_APPROVED.equals(dec.getStatus())
                        || DemandCandidateDecision.STATUS_REJECTED.equals(dec.getStatus()))
                .map(DemandCandidateDecision::getEmployeeId)
                .collect(Collectors.toSet());
        boolean allStrongGoodDecided = !strongGood.isEmpty()
                && strongGood.stream().allMatch(c -> decidedEmployeeIds.contains(c.getEmployeeId()));
        if (allStrongGoodDecided) {
            return DemandLifecycleState.EXHAUSTED;
        }
        return DemandLifecycleState.OPEN;
    }

    /**
     * True only for the one-sub-band-below Override-eligible case. Recomputed from the raw sub-band values rather than
     * read off {@code c.getBandSignal()} — match_candidates.band_signal stores the ported "below" quality for ANY
     * amount below (see MatchingService.bandSignal()'s own comment), so the one-below distinction isn't persisted
     * there; MatchingService.isOneBandBelow() is the one source of truth for it. Returns false (not eligible) if either
     * side's row has since gone missing.
     */
    private boolean isOverrideEligible(MatchCandidate c, SupplyEnriched supply, DemandEnriched demand) {
        if (!"Excluded".equals(c.getOverallTier()) || supply == null || demand == null) {
            return false;
        }
        return matchingService.isOneBandBelow(supply.getSubBand(), demand.getBand());
    }

    /**
     * Recomputes all four signals fresh against MatchingService — match_candidates only persists the quality CODE + the
     * already-folded one_liner (see ReviewCandidateDto's own Javadoc), not the Collapsed/Expanded text pairs the
     * four-dimension card needs. Mirrors the pattern MatchingRunService/ShortlistWorkbookService already use (including
     * their own private toClassificationResult() duplicates) — a third copy here follows that same established
     * precedent rather than extracting shared code, per this codebase's own stated tradeoff (independently readable
     * classes over DRY at this size).
     */
    private ReviewCandidateDto toCandidateDto(MatchCandidate c, DemandEnriched demand, SupplyEnriched supply,
                                              DemandCandidateDecision decision, Map<Integer, String> userNames) {
        if (supply == null) {
            // Supply row went inactive/was removed since the last matching run — match_candidates
            // is re-derivable but may be briefly stale; show a bare placeholder rather than fail
            // the whole workspace over one dangling row.
            return new ReviewCandidateDto(
                    c.getEmployeeId(), "Employee #" + c.getEmployeeId(), null, null, null, null, null,
                    c.getOverallTier(), c.getOneLiner(),
                    null, null, null, null, null, null, null, null,
                    false, // no supply row to check against — can't be override-eligible
                    decision == null ? null : decision.getStatus(),
                    decision == null ? null : decision.getDecidedAt(),
                    decision == null || decision.getDecidedBy() == null ? null : userNames.get(decision.getDecidedBy())
            );
        }

        var skill = matchingService.skillSignal(toClassificationResult(supply), toClassificationResult(demand));
        var band = matchingService.bandSignal(supply.getSubBand(), demand.getBand());
        var location = matchingService.locationSignal(supply.getLocation(), demand.getLocation(), null);
        var assessment = matchingService.assessmentSignal(supply.getRating(), supply.getScore());

        return new ReviewCandidateDto(
                c.getEmployeeId(), supply.getEmployeeName(), supply.getBand(), supply.getSubBand(),
                supply.getLocation(), supply.getBenchAgeingDays(), supply.getRating(),
                c.getOverallTier(), c.getOneLiner(),
                skill.collapsed(), skill.expanded(),
                band.collapsed(), band.expanded(),
                location.collapsed(), location.expanded(),
                assessment.collapsed(), assessment.expanded(),
                isOverrideEligible(c, supply, demand),
                decision == null ? null : decision.getStatus(),
                decision == null ? null : decision.getDecidedAt(),
                decision == null || decision.getDecidedBy() == null ? null : userNames.get(decision.getDecidedBy())
        );
    }

    private ClassificationResult toClassificationResult(SupplyEnriched emp) {
        return new ClassificationResult(
                emp.getPersona() == null ? null : emp.getPersona().getName(),
                emp.getSubPersona() == null ? null : emp.getSubPersona().getName(),
                emp.getNamedAccessories(),
                emp.getCompleteness(),
                emp.getFrameworkConfirmed(),
                null // status is never read by skillSignal()/assessmentSignal() on the supply side
        );
    }

    private ClassificationResult toClassificationResult(DemandEnriched dem) {
        return new ClassificationResult(
                dem.getPersona() == null ? null : dem.getPersona().getName(),
                dem.getSubPersona() == null ? null : dem.getSubPersona().getName(),
                dem.getNamedAccessories(),
                null, // demand classification never produces a completeness value
                dem.getFrameworkConfirmed(),
                dem.getClassificationNote()
        );
    }
}
