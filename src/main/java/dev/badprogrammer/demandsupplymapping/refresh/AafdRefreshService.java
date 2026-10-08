package dev.badprogrammer.demandsupplymapping.refresh;

import dev.badprogrammer.demandsupplymapping.entity.AafdSupplyEnriched;
import dev.badprogrammer.demandsupplymapping.entity.RefreshRun;
import dev.badprogrammer.demandsupplymapping.repository.AafdSupplyEnrichedRepository;
import dev.badprogrammer.demandsupplymapping.repository.RefreshRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Excel -&gt; DB ingestion for AAFD-Supply.xlsx, into its own aafd_supply_enriched table — deliberately NOT touching
 * supply_enriched (see V13__aafd_supply_enriched.sql and AafdSupplyEnriched's Javadoc for why: a separate table, not a
 * discriminator column, so this refresh can never interact with RefreshService.refreshSupply()'s truncate/upsert cycle
 * over AFD-Supply.xlsx).
 * <p>
 * Follows RefreshService's same upsert-by-natural-key + soft-delete-missing algorithm, but WITHOUT the hash-gated
 * reclassification step RefreshService has: there is no persona/classification logic for AAFD's shape yet, so every
 * column is simply always-upserted on every refresh (same "always upserted" treatment RefreshService already gives its
 * own purely-descriptive columns — there's just nothing here that's conditionally recomputed). Row key is the source
 * file's "Emp code" column, parsed the same defensive way Employee Code is parsed on the AFD side. Uses
 * {@link RefreshLogic#nz(String)} directly (package-private, same package) to normalize blank/pandas-NA-token cells to
 * null, exactly like RefreshLogic's own row parsing does.
 */
@Service
public class AafdRefreshService {

    private static final Logger log = LoggerFactory.getLogger(AafdRefreshService.class);

    private final AafdSupplyEnrichedRepository aafdRepo;
    private final RefreshRunRepository refreshRunRepo;

    public AafdRefreshService(AafdSupplyEnrichedRepository aafdRepo, RefreshRunRepository refreshRunRepo) {
        this.aafdRepo = aafdRepo;
        this.refreshRunRepo = refreshRunRepo;
    }

    @Transactional
    public RefreshRun refreshAafd(Path xlsxPath) {
        RefreshRun run = new RefreshRun("aafd", xlsxPath.getFileName().toString());
        run = refreshRunRepo.save(run);
        try {
            java.util.List<Map<String, String>> raw = ExcelSheetReader.readFirstSheet(xlsxPath);

            int rowsNew = 0;
            Set<Long> seenIds = new HashSet<>();

            for (Map<String, String> row : raw) {
                Long empCode = parseEmpCode(row.get("Emp code"));
                if (empCode == null) {
                    continue; // unparseable Emp code — can't upsert without a PK
                }
                seenIds.add(empCode);
                AafdSupplyEnriched entity = aafdRepo.findById(empCode).orElse(null);
                boolean isNew = entity == null;
                if (isNew) {
                    entity = new AafdSupplyEnriched(empCode);
                    rowsNew++;
                }

                entity.setName(RefreshLogic.nz(row.get("Name")));
                entity.setOffshoreOnshore(RefreshLogic.nz(row.get("Offshore/Onshore")));
                entity.setWpcNoticeSharedOn(RefreshLogic.nz(row.get("WPC Notice Shared on")));
                entity.setAvailabilityDate(RefreshLogic.nz(row.get("Availability Date")));
                entity.setMonth(RefreshLogic.nz(row.get("Month")));
                entity.setStatus(RefreshLogic.nz(row.get("Status")));
                entity.setBand(RefreshLogic.nz(row.get("Band")));
                entity.setSubBand(RefreshLogic.nz(row.get("Sub band")));
                entity.setSkillConfirmedByEmp(RefreshLogic.nz(row.get("Skill Confirmed by Emp")));
                entity.setPrimarySkill(RefreshLogic.nz(row.get("Primary skill (Resource confirmed)")));
                entity.setCapability(RefreshLogic.nz(row.get("Capability")));
                entity.setSubCapability(RefreshLogic.nz(row.get("Sub Capability")));
                entity.setLocation(RefreshLogic.nz(row.get("Location")));
                entity.setPrimeNv(RefreshLogic.nz(row.get("Prime/NV")));
                entity.setCustomer(RefreshLogic.nz(row.get("Customer")));
                entity.setReasonOfRelease(RefreshLogic.nz(row.get("Reason of Release")));
                entity.setDeploymentStatus(RefreshLogic.nz(row.get("DEPLOYMENT STATUS")));
                entity.setSapNonSap(RefreshLogic.nz(row.get("SAP/Non SAP")));
                entity.setResourceRoleType(RefreshLogic.nz(row.get("Resource Role Type")));
                entity.setDurationInProjectMonths(RefreshLogic.nz(row.get("Duration in Project(in Months)")));
                entity.setDetailedRemarks(RefreshLogic.nz(row.get("Detailed Remarks")));
                entity.setL3(RefreshLogic.nz(row.get("L3")));
                entity.setL4(RefreshLogic.nz(row.get("L4")));
                entity.setGlobalVertical(RefreshLogic.nz(row.get("Global Vertical")));
                entity.setMasMapping(RefreshLogic.nz(row.get("MAS Mapping")));
                entity.setHrL4(RefreshLogic.nz(row.get("HR L4")));
                entity.setMasRaw(RefreshLogic.nz(row.get("MAS")));
                entity.setSkillCluster(RefreshLogic.nz(row.get("Skill Cluster")));
                entity.setSkillClusterPersona(RefreshLogic.nz(row.get("Skill Cluster Persona")));
                entity.setRating(RefreshLogic.nz(row.get("Rating")));
                entity.setActive(true);
                entity.setInactiveSince(null);
                entity.setLastRefreshedAt(OffsetDateTime.now());

                aafdRepo.save(entity);
            }

            int rowsDeactivated = 0;
            for (AafdSupplyEnriched entity : aafdRepo.findByIsActiveTrue()) {
                if (!seenIds.contains(entity.getEmpCode())) {
                    entity.setActive(false);
                    entity.setInactiveSince(OffsetDateTime.now());
                    aafdRepo.save(entity);
                    rowsDeactivated++;
                }
            }

            log.info("AAFD refresh: {} raw rows. new={}, deactivated={}", raw.size(), rowsNew, rowsDeactivated);
            finishRun(run, raw.size(), rowsNew, null, null, null);
        } catch (IOException | RuntimeException e) {
            finishRun(run, null, null, null, null, e.getMessage());
            throw new RefreshFailedException("AAFD refresh failed: " + e.getMessage(), e);
        }
        return run;
    }

    /**
     * Same defensive parse RefreshLogic.parseEmployeeId uses for Employee Code (that method is private, so this is a
     * duplicate, not a reuse): routes through {@code Double.parseDouble} so "12345" and "12345.0" (what a
     * numeric-formatted Excel cell can leave behind) both parse correctly.
     */
    private Long parseEmpCode(String raw) {
        String s = RefreshLogic.nz(raw);
        if (s == null) {
            return null;
        }
        try {
            return (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void finishRun(RefreshRun run, Integer rowsIn, Integer rowsNew, Integer rowsChanged,
                           Integer rowsFlagged, String errorMessage) {
        run.setFinishedAt(OffsetDateTime.now());
        run.setStatus(errorMessage == null ? "succeeded" : "failed");
        run.setRowsIn(rowsIn);
        run.setRowsNew(rowsNew);
        run.setRowsChanged(rowsChanged);
        run.setRowsFlagged(rowsFlagged);
        run.setErrorMessage(errorMessage);
        refreshRunRepo.save(run);
    }

    public static class RefreshFailedException extends RuntimeException {
        public RefreshFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
