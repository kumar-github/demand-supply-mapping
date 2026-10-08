package dev.badprogrammer.demandsupplymapping.refresh;

import dev.badprogrammer.demandsupplymapping.entity.DemandEnriched;
import dev.badprogrammer.demandsupplymapping.entity.RefreshRun;
import dev.badprogrammer.demandsupplymapping.entity.SupplyEnriched;
import dev.badprogrammer.demandsupplymapping.matching.ClassificationResult;
import dev.badprogrammer.demandsupplymapping.repository.DemandEnrichedRepository;
import dev.badprogrammer.demandsupplymapping.repository.RefreshRunRepository;
import dev.badprogrammer.demandsupplymapping.repository.SupplyEnrichedRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Excel -> DB ingestion, implementing the hash-diff/upsert/soft-delete refresh-safety algorithm documented at the
 * bottom of full_db_design.sql. Filtering and classification themselves live in RefreshLogic (plain, no Spring) so that
 * logic can be verified standalone — see RefreshLogicVerification. This class is the thin persistence layer on top of
 * it.
 * <p>
 * Known gaps, deliberately out of scope for this slice (see README): - Step 4 of the documented algorithm ("if an open
 * review/decision exists for this id, set needs_reattention = TRUE") is NOT implemented. demand_review_state DOES now
 * have an entity (built 2026-10-06 for the Review feature's "Flag for hiring" action) but that action deliberately
 * writes its OWN flagged_for_hiring/flagged_for_hiring_reason columns, not needs_reattention/reattention_reason — see
 * V12__flagged_for_hiring_column.sql and DemandReviewState's Javadoc for why those two signals were kept separate.
 * needs_reattention/reattention_reason remain exactly as V1 defined them and are still free for this step whenever it's
 * built: reclassification and hashing both happen correctly here already; only the reviewer-facing flag write is
 * missing. - sub_capability_note / mas_mapping_note (the human-readable reason text) are NOT persisted — the schema
 * only has the boolean flags. Both are fully re-derivable on demand from the stored raw value + persona
 * (RefreshLogic.subCapabilityNote / masMappingNote), so nothing is actually lost; a future controller/view can call
 * those instead of storing redundant text. - cluster_name_raw is populated from the raw Excel column literally named
 * "cluster" — a best-effort mapping based on the schema comment ("free-text demand ask, as typed by the requesting
 * team", which doesn't match "Final Skill Cluster"'s own description). Worth eyeballing against one real demand row
 * before trusting it.
 * <p>
 * Fixed 2026-09-29: supply_enriched was missing a `sub_band` column entirely (V2 migration). RefreshLogic was already
 * reading and hashing Sub Band correctly for the band-ladder override, but had nowhere to persist it —
 * MatchingService.bandSignal() needs Sub Band, not the coarse Band, so this would have silently broken band scoring the
 * moment matching reads from this table instead of a CSV. Also added `bench_ageing_days` (same migration) — picked over
 * the source file's bucketed 'Duration' column since the day count is strictly more useful. demand_enriched needed no
 * equivalent fix: its existing `band` column already held the fine-grained value.
 * <p>
 * Step 1 of 2026-09-29's match_candidates-parity work: added `rating`/ `score` (supply, V3 migration) and
 * `balance_positions`/`due_category` (demand, V3 migration) — raw fields build_matches.py's assessment_signal() and
 * cap_employee_rows() need and that had no home in the schema until now. All four are always-upserted the same way as
 * the other descriptive columns, regardless of hash.
 * <p>
 * Per the 2026-09-28 decision on the hash-unchanged case: ALL descriptive columns
 * (name/band/location/capability/afd_status/skill_cluster_raw/ etc.) are upserted on every refresh regardless of
 * whether the hash changed. The hash only gates whether persona/sub_persona/
 * named_accessories/classification_status/the mismatch flag are recomputed — skipping that recompute when the hash is
 * unchanged is a pure optimization (the result would be identical either way, since classification is a deterministic
 * function of exactly the columns the hash covers).
 */
@Service
public class RefreshService {

    private static final Logger log = LoggerFactory.getLogger(RefreshService.class);

    private final SupplyEnrichedRepository supplyRepo;
    private final DemandEnrichedRepository demandRepo;
    private final RefreshRunRepository refreshRunRepo;
    private final PersonaCatalog personaCatalog;
    private final MasMappingScope masMappingScope;

    public RefreshService(SupplyEnrichedRepository supplyRepo, DemandEnrichedRepository demandRepo,
                          RefreshRunRepository refreshRunRepo, PersonaCatalog personaCatalog,
                          MasMappingScope masMappingScope) {
        this.supplyRepo = supplyRepo;
        this.demandRepo = demandRepo;
        this.refreshRunRepo = refreshRunRepo;
        this.personaCatalog = personaCatalog;
        this.masMappingScope = masMappingScope;
    }

    @Transactional
    public RefreshRun refreshSupply(Path xlsxPath) {
        // Load the current active/inactive MAS Mapping categories from the DB (V7 migration) into
        // Engine.PHASE1_MAS_MAPPING before classifying anything, so a category someone flipped
        // in the table takes effect on this very run — see MasMappingScope's javadoc.
        masMappingScope.applyToEngine();
        RefreshRun run = new RefreshRun("supply", xlsxPath.getFileName().toString());
        run = refreshRunRepo.save(run);
        try {
            List<java.util.Map<String, String>> raw = ExcelSheetReader.readFirstSheet(xlsxPath);
            List<java.util.Map<String, String>> phase1 = RefreshLogic.filterPhase1Supply(raw);
            List<RefreshLogic.SupplyClassifiedRow> classified = RefreshLogic.classifySupply(phase1);

            int rowsNew = 0, rowsChanged = 0, rowsFlagged = 0;
            Set<Long> seenIds = new HashSet<>();

            for (RefreshLogic.SupplyClassifiedRow row : classified) {
                if (row.employeeId() == null) {
                    continue; // unparseable Employee Code — can't upsert without a PK
                }
                seenIds.add(row.employeeId());
                SupplyEnriched entity = supplyRepo.findById(row.employeeId()).orElse(null);
                boolean isNew = entity == null;
                if (isNew) {
                    entity = new SupplyEnriched(row.employeeId());
                    rowsNew++;
                }

                // Always upserted, regardless of hash.
                entity.setEmployeeName(row.employeeName());
                entity.setBand(row.band());
                entity.setSubBand(row.subBand());
                entity.setLocation(row.location());
                entity.setCapability(row.capability());
                entity.setAfdStatus(row.afdStatus());
                entity.setSkillClusterRaw(row.skillClusterRaw());
                entity.setSubCapabilityRaw(row.subCapabilityRaw());
                entity.setMasMappingRaw(row.masMappingRaw());
                entity.setBenchAgeingDays(row.benchAgeingDays());
                entity.setRating(row.rating());
                entity.setScore(row.score());
                entity.setPrimeNv(row.primeNv());
                entity.setActive(true);
                entity.setInactiveSince(null);
                entity.setLastRefreshedAt(OffsetDateTime.now());

                boolean hashChanged = isNew || !row.sourceRowHash().equals(entity.getSourceRowHash());
                if (hashChanged) {
                    if (!isNew) {
                        rowsChanged++;
                    }
                    applyClassification(entity, row.classification());
                    entity.setClassificationStatus(row.classificationStatus());
                    entity.setSubCapabilityMismatchFlag(row.subCapabilityMismatchFlag());
                    entity.setSourceRowHash(row.sourceRowHash());
                }
                if (entity.isSubCapabilityMismatchFlag()) {
                    rowsFlagged++;
                }
                supplyRepo.save(entity);
            }

            int rowsDeactivated = deactivateMissing(supplyRepo.findByIsActiveTrue(), seenIds,
                    SupplyEnriched::getEmployeeId, e -> {
                        e.setActive(false);
                        e.setInactiveSince(OffsetDateTime.now());
                        supplyRepo.save(e);
                    });

            log.info("Supply refresh: {} raw rows -> {} Phase 1 scope -> {} classified. "
                            + "new={}, changed={}, deactivated={}, flagged={}",
                    raw.size(), phase1.size(), classified.size(), rowsNew, rowsChanged, rowsDeactivated, rowsFlagged);
            finishRun(run, raw.size(), rowsNew, rowsChanged, rowsFlagged, rowsDeactivated, null);
        } catch (IOException | RuntimeException e) {
            finishRun(run, null, null, null, null, null, e.getMessage());
            throw new RefreshFailedException("Supply refresh failed: " + e.getMessage(), e);
        }
        return run;
    }

    @Transactional
    public RefreshRun refreshDemand(Path xlsxPath) {
        // Same reason as refreshSupply() above — see MasMappingScope's javadoc. Demand needs this
        // every bit as much as supply now that classifyDemand() has its own MAS Mapping scope gate
        // (2026-10-01) — previously demand had none at all.
        masMappingScope.applyToEngine();
        RefreshRun run = new RefreshRun("demand", xlsxPath.getFileName().toString());
        run = refreshRunRepo.save(run);
        try {
            List<java.util.Map<String, String>> raw = ExcelSheetReader.readFirstSheet(xlsxPath);
            List<java.util.Map<String, String>> cleaned = RefreshLogic.stripFooterAndFilterApproved(raw);
            List<RefreshLogic.DemandClassifiedRow> classified = RefreshLogic.classifyDemand(cleaned);

            int rowsNew = 0, rowsChanged = 0, rowsFlagged = 0;
            Set<String> seenIds = new HashSet<>();

            for (RefreshLogic.DemandClassifiedRow row : classified) {
                if (row.demandId() == null) {
                    continue;
                }
                seenIds.add(row.demandId());
                DemandEnriched entity = demandRepo.findById(row.demandId()).orElse(null);
                boolean isNew = entity == null;
                if (isNew) {
                    entity = new DemandEnriched(row.demandId());
                    rowsNew++;
                }

                entity.setClusterNameRaw(row.clusterNameRaw());
                entity.setFinalSkillClusterRaw(row.finalSkillClusterRaw());
                entity.setMasMappingRaw(row.masMappingRaw());
                entity.setAdditionalRequestRaw(row.additionalRequestRaw());
                entity.setLocation(row.location());
                entity.setBand(row.band());
                entity.setBalancePositions(row.balancePositions());
                entity.setDueCategory(row.dueCategory());
                entity.setCustomer(row.customer());
                entity.setProjectName(row.projectName());
                entity.setNewAgeing(row.newAgeing());
                entity.setActive(true);
                entity.setInactiveSince(null);
                entity.setLastRefreshedAt(OffsetDateTime.now());

                boolean hashChanged = isNew || !row.sourceRowHash().equals(entity.getSourceRowHash());
                if (hashChanged) {
                    if (!isNew) {
                        rowsChanged++;
                    }
                    applyClassification(entity, row.classification());
                    entity.setClassificationStatus(row.classificationStatus());
                    entity.setMasMappingMismatchFlag(row.masMappingMismatchFlag());
                    entity.setFrontendAnchorFlag(row.frontendAnchorFlag());
                    entity.setSourceRowHash(row.sourceRowHash());
                }
                if (entity.isMasMappingMismatchFlag()) {
                    rowsFlagged++;
                }
                demandRepo.save(entity);
            }

            int rowsDeactivated = deactivateMissing(demandRepo.findByIsActiveTrue(), seenIds,
                    DemandEnriched::getDemandId, e -> {
                        e.setActive(false);
                        e.setInactiveSince(OffsetDateTime.now());
                        demandRepo.save(e);
                    });

            log.info("Demand refresh: {} raw rows -> {} after footer-strip/Approved -> {} classified. "
                            + "new={}, changed={}, deactivated={}, flagged={}",
                    raw.size(), cleaned.size(), classified.size(), rowsNew, rowsChanged, rowsDeactivated, rowsFlagged);
            finishRun(run, raw.size(), rowsNew, rowsChanged, rowsFlagged, rowsDeactivated, null);
        } catch (IOException | RuntimeException e) {
            finishRun(run, null, null, null, null, null, e.getMessage());
            throw new RefreshFailedException("Demand refresh failed: " + e.getMessage(), e);
        }
        return run;
    }

    private void applyClassification(SupplyEnriched entity, ClassificationResult c) {
        entity.setPersona(personaCatalog.resolvePersona(c.persona()));
        entity.setSubPersona(personaCatalog.resolveSubPersona(c.persona(), c.subPersona()));
        entity.setNamedAccessories(c.namedAccessories());
        entity.setFrameworkConfirmed(c.frameworkConfirmed());
        // Fixed 2026-09-29 (V4 migration): completeness was already being
        // computed by classification but silently dropped here — see the
        // migration file's comment for how this was found.
        entity.setCompleteness(c.completeness());
        // Fixed 2026-09-30 (V6 migration): status was already being computed
        // by classification but silently dropped here, same bug the demand
        // side had before V4 — see that migration's comment. Needed for the
        // shortlist workbook's DATA-ISSUE trailer block (task #19).
        entity.setClassificationNote(c.status());
    }

    private void applyClassification(DemandEnriched entity, ClassificationResult c) {
        entity.setPersona(personaCatalog.resolvePersona(c.persona()));
        entity.setSubPersona(personaCatalog.resolveSubPersona(c.persona(), c.subPersona()));
        entity.setNamedAccessories(c.namedAccessories());
        entity.setFrameworkConfirmed(c.frameworkConfirmed());
        // Fixed 2026-09-29 (V4 migration): status was already being computed
        // by classification but silently dropped here — see the migration
        // file's comment for why this matters (skillSignal()'s weak-MERN
        // detection).
        entity.setClassificationNote(c.status());
    }

    /**
     * Step 6 of the algorithm: an id previously active but absent from this export is soft-deleted.
     */
    private <ID, T> int deactivateMissing(List<T> currentlyActive, Set<ID> seenIds,
                                          java.util.function.Function<T, ID> idFn,
                                          java.util.function.Consumer<T> deactivate) {
        int count = 0;
        for (T entity : currentlyActive) {
            if (!seenIds.contains(idFn.apply(entity))) {
                deactivate.accept(entity);
                count++;
            }
        }
        return count;
    }

    private void finishRun(RefreshRun run, Integer rowsIn, Integer rowsNew, Integer rowsChanged,
                           Integer rowsFlagged, Integer rowsDeactivated, String errorMessage) {
        run.setFinishedAt(OffsetDateTime.now());
        run.setStatus(errorMessage == null ? "succeeded" : "failed");
        run.setRowsIn(rowsIn);
        run.setRowsNew(rowsNew);
        run.setRowsChanged(rowsChanged);
        run.setRowsFlagged(rowsFlagged);
        // rows_deactivated has no column in refresh_runs (full_db_design.sql
        // doesn't track it separately) — folded into the error message when
        // present isn't right either, so it's just not persisted; callers
        // that need it should read it off the RefreshRun before it's
        // discarded, or this can be added as a real column later.
        run.setErrorMessage(errorMessage);
        refreshRunRepo.save(run);
    }

    public static class RefreshFailedException extends RuntimeException {
        public RefreshFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
