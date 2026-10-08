package dev.badprogrammer.demandsupplymapping.matching;

import dev.badprogrammer.demandsupplymapping.entity.DemandEnriched;
import dev.badprogrammer.demandsupplymapping.entity.MatchCandidate;
import dev.badprogrammer.demandsupplymapping.entity.RefreshRun;
import dev.badprogrammer.demandsupplymapping.entity.SupplyEnriched;
import dev.badprogrammer.demandsupplymapping.repository.DemandEnrichedRepository;
import dev.badprogrammer.demandsupplymapping.repository.MatchCandidateRepository;
import dev.badprogrammer.demandsupplymapping.repository.RefreshRunRepository;
import dev.badprogrammer.demandsupplymapping.repository.SupplyEnrichedRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Java port of bench-match-cli/build_matches.py's main() — the actual matching RUN, as opposed to MatchingService (the
 * signal/scoring/capping logic itself, which this class is the only real caller of). Step 3 of 2026-09-29's
 * match_candidates-parity work.
 * <p>
 * Iterates active, classified supply x same-persona-family candidate demand (build_candidate_demands()'s scope
 * restriction — Java-Java, .NET-.NET, Frontend-Frontend across sub-personas; cross-persona matching was removed from
 * the design entirely, see MatchingService's own class Javadoc), computes all four signals per pair, applies
 * capEmployeeRows() per employee, and rewrites match_candidates in full — match_candidates is RE-DERIVABLE
 * (V1__init_schema.sql's own comment: "Fully truncated and rewritten every time matching runs"), so every run deletes
 * the previous run's rows rather than accumulating them under distinct run_ids.
 * <p>
 * Two real gaps were found and fixed ahead of this class (both via the V4 migration — see its comment and
 * RefreshService's applyClassification() for detail): supply_enriched.completeness (display-only) and
 * demand_enriched.classification_note (correctness-affecting — needed for skillSignal()'s weak-MERN detection). Without
 * those this class would either degrade one-liner text everywhere, or actively mis-tier weak-MERN demand rows.
 * <p>
 * One deliberate non-fix: build_matches.py's location_signal() takes a `pan_india` argument, but it only ever affects
 * that function's `expanded` text (never `collapsed`, `quality`, or the tier decision) — and `expanded` text is never
 * persisted to match_candidates in this schema (only the quality tag + the collapsed-text-built one_liner are). So a
 * demand_enriched.pan_india column was deliberately NOT added; locationSignal() is always called with panIndia=null
 * here, which is provably a no-op for anything this class actually stores.
 * <p>
 * Root-caused 2026-10-06 (production incident): {@code POST /api/matching/run} was called twice in quick succession
 * against the same deployment. Each call is its own transaction doing delete-then-insert with no lock between them, so
 * under Postgres's default READ COMMITTED isolation both transactions' {@code deleteAllInBatch()} calls saw the same
 * "before" snapshot, both deleted it, and both then inserted their own new rows under different run_ids — neither
 * delete removed the other's insert, because neither had committed yet when the other's delete ran. Net result:
 * match_candidates ended up holding TWO runs' worth of rows simultaneously, violating the "fully truncated and
 * rewritten every run" invariant this class's own Javadoc states. The fix is the advisory lock below — it stops a
 * second, overlapping {@link #runMatching()} call from ever reaching the delete/insert section while one is already in
 * flight, using a Postgres TRANSACTION-level advisory lock ({@code pg_try_advisory_xact_lock}): it's acquired inside
 * this method's own transaction and auto-releases on commit or rollback (no manual unlock needed, and no long-held lock
 * surviving on a pooled connection after the method returns — a plain session-level advisory lock would have that
 * problem since connection-pooled "sessions" are reused across requests). A concurrent second call fails fast with a
 * clear error instead of silently racing.
 */
@Service
public class MatchingRunService {

    private static final Logger log = LoggerFactory.getLogger(MatchingRunService.class);

    // Arbitrary fixed key identifying "the matching run" as the thing being locked — any constant
    // works as long as it's unique to this lock's purpose within the database (pg_advisory_xact_lock
    // keys are a shared global namespace per database, not scoped to a table or object).
    private static final long MATCHING_RUN_LOCK_KEY = 88_221_133L;

    private final SupplyEnrichedRepository supplyRepo;
    private final DemandEnrichedRepository demandRepo;
    private final MatchCandidateRepository matchRepo;
    private final RefreshRunRepository refreshRunRepo;
    private final MatchingService matchingService;
    private final EntityManager entityManager;

    public MatchingRunService(SupplyEnrichedRepository supplyRepo, DemandEnrichedRepository demandRepo,
                              MatchCandidateRepository matchRepo, RefreshRunRepository refreshRunRepo,
                              MatchingService matchingService, EntityManager entityManager) {
        this.supplyRepo = supplyRepo;
        this.demandRepo = demandRepo;
        this.matchRepo = matchRepo;
        this.refreshRunRepo = refreshRunRepo;
        this.matchingService = matchingService;
        this.entityManager = entityManager;
    }

    @Transactional
    public RefreshRun runMatching() {
        // Fails fast, before even creating a RefreshRun row, if another runMatching() call already
        // holds this lock — see the class Javadoc for why this specific lock type (not a Java-level
        // synchronized/mutex, which wouldn't help across multiple app instances or processes, and not
        // a plain session-level advisory lock, which doesn't release cleanly with pooled connections).
        if (!tryAcquireMatchingRunLock()) {
            throw new MatchingRunFailedException(
                    "A matching run is already in progress — wait for it to finish before starting another.", null);
        }

        RefreshRun run = new RefreshRun("matching");
        run = refreshRunRepo.save(run);
        try {
            List<SupplyEnriched> supply = supplyRepo.findByIsActiveTrue().stream()
                    .filter(s -> s.getPersona() != null)
                    .toList();
            List<DemandEnriched> demand = demandRepo.findByIsActiveTrue().stream()
                    .filter(d -> d.getPersona() != null)
                    .toList();

            // build_candidate_demands() pre-filter, done once up front rather
            // than per employee: group classified active demand by persona
            // name.
            Map<String, List<DemandEnriched>> demandByPersona = demand.stream()
                    .collect(Collectors.groupingBy(d -> d.getPersona().getName()));

            // match_candidates is RE-DERIVABLE — full truncate-and-rewrite
            // every run (V1__init_schema.sql's own table comment).
            matchRepo.deleteAllInBatch();

            List<MatchCandidate> toSave = new ArrayList<>();
            int excludedWritten = 0;

            for (SupplyEnriched emp : supply) {
                List<DemandEnriched> candidates = candidateDemandsFor(emp, demandByPersona);
                if (candidates.isEmpty()) {
                    continue;
                }
                ClassificationResult empClassification = toClassificationResult(emp);

                List<MatchRow> empRows = new ArrayList<>();
                for (DemandEnriched dem : candidates) {
                    ClassificationResult demClassification = toClassificationResult(dem);

                    SignalResult skill = matchingService.skillSignal(empClassification, demClassification);
                    if ("no_relation".equals(skill.quality())) {
                        continue; // build_matches.py main(): "if sk['quality'] == 'no_relation': continue"
                    }
                    SignalResult band = matchingService.bandSignal(emp.getSubBand(), dem.getBand());
                    SignalResult location = matchingService.locationSignal(emp.getLocation(), dem.getLocation(), null);
                    AssessmentResult assessment = matchingService.assessmentSignal(emp.getRating(), emp.getScore());
                    String tier = matchingService.overallTier(skill, band, location);

                    String oneLiner = buildOneLiner(skill, band, location, assessment);
                    int ageingRank = matchingService.ageingRank(dem.getDueCategory());
                    boolean bandOverrideEligible = matchingService.isOneBandBelow(emp.getSubBand(), dem.getBand());

                    empRows.add(new MatchRow(
                            emp.getEmployeeId(), dem.getDemandId(), tier, skill.quality(),
                            ageingRank, dem.getBalancePositions(), oneLiner,
                            skill.quality(), band.quality(), location.quality(), bandOverrideEligible
                    ));
                }
                if (empRows.isEmpty()) {
                    continue;
                }

                for (MatchRow row : matchingService.capEmployeeRows(empRows)) {
                    toSave.add(new MatchCandidate(
                            row.employeeId(), row.demandId(), row.overallTier(),
                            row.skillSignal(), row.bandSignal(), row.locationSignal(),
                            row.oneLiner(), run.getRunId()
                    ));
                    if ("Excluded".equals(row.overallTier())) {
                        excludedWritten++;
                    }
                }
            }

            matchRepo.saveAll(toSave);

            log.info("Matching run: {} classified active supply -> {} classified active demand candidates. "
                            + "{} match_candidates rows written ({} Excluded near-misses).",
                    supply.size(), demand.size(), toSave.size(), excludedWritten);
            finishRun(run, supply.size(), toSave.size(), null, excludedWritten, null);
        } catch (RuntimeException e) {
            finishRun(run, null, null, null, null, e.getMessage());
            throw new MatchingRunFailedException("Matching run failed: " + e.getMessage(), e);
        }
        return run;
    }

    /**
     * {@code pg_try_advisory_xact_lock} (not the plain {@code pg_try_advisory_lock}) specifically — the xact variant is
     * scoped to the CURRENT transaction and releases automatically on commit or rollback, which matters because this
     * method runs under a connection-pooled DataSource: a plain session-level lock is tied to the physical connection,
     * not to this one logical call, so it can outlive this method and block a future, unrelated request that happens to
     * reuse the same pooled connection. Returns false (lock not acquired) immediately rather than blocking, so a
     * second, overlapping call fails fast instead of queueing up behind the first.
     */
    private boolean tryAcquireMatchingRunLock() {
        Object acquired = entityManager
                .createNativeQuery("SELECT pg_try_advisory_xact_lock(:lockKey)")
                .setParameter("lockKey", MATCHING_RUN_LOCK_KEY)
                .getSingleResult();
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * build_candidate_demands() (build_matches.py lines 293-309) — same broad persona family only. Cross-persona
     * matching (a Java/.NET employee's named frontend accessory proposed against a standalone Frontend demand) has been
     * removed from the design entirely; see that function's own docstring.
     */
    private List<DemandEnriched> candidateDemandsFor(SupplyEnriched emp, Map<String, List<DemandEnriched>> demandByPersona) {
        String persona = emp.getPersona() == null ? null : emp.getPersona().getName();
        if (persona == null) {
            return List.of();
        }
        if ("Fullstack Java".equals(persona) || "Fullstack .NET".equals(persona)) {
            return demandByPersona.getOrDefault(persona, List.of());
        }
        if ("Frontend".equals(persona)) {
            return demandByPersona.getOrDefault("Frontend", List.of());
        }
        return List.of();
    }

    /**
     * build_matches.py main() lines 388-399 — the one_liner assembly. Only a clean 'core' skill match gets trimmed to
     * its short lead-in (everything before the first " — "); every gap/mismatch quality keeps its full collapsed text,
     * since that's where the actual gap is named. Band/location always contribute their full collapsed text. Assessment
     * is appended only when there's something to say (skipped for "Not yet assessed").
     */
    private String buildOneLiner(SignalResult skill, SignalResult band, SignalResult location, AssessmentResult assessment) {
        List<String> bits = new ArrayList<>();
        bits.add("core".equals(skill.quality()) ? leadIn(skill.collapsed()) : skill.collapsed());
        bits.add(band.collapsed());
        bits.add(location.collapsed());
        if (!"Not yet assessed".equals(assessment.collapsed())) {
            bits.add(assessment.collapsed());
        }
        return String.join("; ", bits);
    }

    /**
     * collapsed.split(' — ')[0] — the text before the first em-dash separator.
     */
    private String leadIn(String collapsed) {
        int idx = collapsed.indexOf(" — ");
        return idx < 0 ? collapsed : collapsed.substring(0, idx);
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
                dem.getClassificationNote() // the field the V4 migration added specifically for this
        );
    }

    private void finishRun(RefreshRun run, Integer rowsIn, Integer rowsNew, Integer rowsChanged,
                           Integer rowsFlagged, String errorMessage) {
        run.setFinishedAt(OffsetDateTime.now());
        run.setStatus(errorMessage == null ? "succeeded" : "failed");
        run.setRowsIn(rowsIn);       // classified active supply employees considered
        run.setRowsNew(rowsNew);     // total match_candidates rows written (table is fully rewritten, so all are "new")
        run.setRowsChanged(rowsChanged); // not meaningful for a full rewrite — left null
        run.setRowsFlagged(rowsFlagged); // Excluded near-miss rows written, as an at-a-glance count
        run.setErrorMessage(errorMessage);
        refreshRunRepo.save(run);
    }

    public static class MatchingRunFailedException extends RuntimeException {
        public MatchingRunFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
