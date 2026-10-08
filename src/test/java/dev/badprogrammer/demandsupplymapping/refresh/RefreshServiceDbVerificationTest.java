package dev.badprogrammer.demandsupplymapping.refresh;

import dev.badprogrammer.demandsupplymapping.entity.DemandEnriched;
import dev.badprogrammer.demandsupplymapping.entity.RefreshRun;
import dev.badprogrammer.demandsupplymapping.entity.SupplyEnriched;
import dev.badprogrammer.demandsupplymapping.repository.*;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * First real run of RefreshService against an actual database (step (a) of the 2026-09-30 web-app work: "verify
 * persistence before building the upload UI on top of it"). Everything RefreshLogic does has been checked standalone
 * (RefreshLogicVerification) — this class is the first time anyone checks the persistence half: upsert, soft-delete,
 * hashing, and PersonaCatalog's get-or-create, against a real Spring context and a real (H2) database, not a CSV.
 * <p>
 * Same "reproduce independently" discipline as every other *Verification class in this project: expected values are
 * recomputed here directly via RefreshLogic, not read back from what RefreshService itself just wrote — a bug that
 * corrupts persistence on the way in would otherwise pass a test that only checks "is anything in the table."
 * <p>
 * DATABASE: a real Postgres, started by hand before running this test:
 * <p>
 * docker run --name benchmatch-test-pg -e POSTGRES_PASSWORD=test \ -e POSTGRES_DB=benchmatch_test -p 5433:5432 -d
 * postgres:16-alpine
 * <p>
 * (connection details in src/test/resources/OLDapplication.yml). Not H2 — V1__init_schema.sql's `TEXT[]` array-column
 * syntax is rejected by every H2 version tried (2.2.224, 2.3.232); H2's grammar only accepts `TEXT ARRAY`, a hard
 * parser limitation, not something an init script or domain alias can work around. Not Testcontainers either —
 * docker-java couldn't reliably talk to Docker Desktop on the machine this was verified on (API-version negotiation,
 * then a transport/parsing failure), despite the Docker CLI itself working fine. A manually-started container with a
 * fixed JDBC URL sidesteps all of that: Maven never talks to the Docker API at all, only to Postgres's own wire
 * protocol.
 * <p>
 * Re-run note: this test does not tear down the container's schema between runs. If a second run fails on "relation
 * already exists" or similar, recreate the container: `docker rm -f benchmatch-test-pg` then the `docker run` command
 * above again.
 * <p>
 * INPUT FILES: point at real AFD-Supply.xlsx/Demand.xlsx via -Dsupply.xlsx=/path/to/AFD-Supply.xlsx
 * -Ddemand.xlsx=/path/to/Demand.xlsx, or rely on the default (../bench-match-cli/AFD-Supply.xlsx and
 * ../bench-match-cli/Demand.xlsx, relative to this module — matches this project's usual sibling-folder layout). If
 * neither the override nor the default resolves to a real file, every test method skips (does not fail) via
 * Assumptions.assumeTrue, so `mvn test` stays green for anyone without the source Excel files on their machine.
 * <p>
 * Run with: mvn test -Dtest=RefreshServiceDbVerificationTest (or just `mvn test` — this runs alongside everything else
 * under src/test/java once Maven picks it up).
 * <p>
 * STRUCTURE: originally five separate @Test @Order(1..5) methods relying on JUnit's MethodOrderer.OrderAnnotation to
 * run in declared order (each phase depends on DB state left behind by the previous one — "first refresh", then "re-run
 * is a no-op", etc.). On the machine this was first verified on, that ordering guarantee did not hold in practice —
 *
 * @Order(4) was observed executing before @Order(1) (confirmed via RefreshService's own log output showing the "first"
 * refresh's row counts appearing out of sequence), even with imports and annotations verified correct. Root cause
 * wasn't pinned down. Rather than keep debugging JUnit/Surefire's ordering behavior on that environment, this was
 * restructured to not depend on it at all: one @Test method calling the five phases as plain private methods in
 * guaranteed source order. Strictly more robust regardless of the original cause.
 */
@SpringBootTest
class RefreshServiceDbVerificationTest {

    private static Path supplyXlsx;
    private static Path demandXlsx;
    private static boolean filesAvailable;
    @Autowired
    private RefreshService refreshService;
    @Autowired
    private SupplyEnrichedRepository supplyRepo;
    @Autowired
    private DemandEnrichedRepository demandRepo;
    @Autowired
    private RefreshRunRepository refreshRunRepo;
    @Autowired
    private PersonaRepository personaRepo;
    @Autowired
    private SubPersonaRepository subPersonaRepo;
    @TempDir
    private Path tempDir;

    @BeforeAll
    static void resolveInputFiles() {
        supplyXlsx = Path.of(System.getProperty("supply.xlsx", "../bench-match-cli/AFD-Supply.xlsx"));
        demandXlsx = Path.of(System.getProperty("demand.xlsx", "../bench-match-cli/Demand.xlsx"));
        filesAvailable = Files.exists(supplyXlsx) && Files.exists(demandXlsx);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ------------------------------------------------------------------
    // 1. First refresh: every classified row lands correctly, field for
    //    field, against RefreshLogic's own (independently recomputed)
    //    output — not against what RefreshService itself just wrote.
    // ------------------------------------------------------------------

    /**
     * Copies xlsxSource to xlsxDest with every cell in the one data row whose "Employee Code" matches employeeId
     * blanked out (not row-deleted) -- ExcelSheetReader treats an all-blank row as absent from the file, which is
     * exactly the "this employee wasn't in this week's export" scenario RefreshService's soft-delete path is meant to
     * handle.
     */
    private static void blankOneDataRowWhereEmployeeCodeEquals(Path xlsxSource, Path xlsxDest, long employeeId) throws IOException {
        try (InputStream in = new FileInputStream(xlsxSource.toFile());
             XSSFWorkbook wb = new XSSFWorkbook(in)) {
            var sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            int employeeCodeCol = -1;
            for (Cell c : headerRow) {
                if ("Employee Code".equalsIgnoreCase(c.getStringCellValue().trim())) {
                    employeeCodeCol = c.getColumnIndex();
                    break;
                }
            }
            if (employeeCodeCol < 0) {
                throw new IOException("Couldn't find an 'Employee Code' column in " + xlsxSource);
            }
            boolean blanked = false;
            for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum() && !blanked; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Cell codeCell = row.getCell(employeeCodeCol);
                if (codeCell == null) {
                    continue;
                }
                long code;
                try {
                    code = (long) (codeCell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                            ? codeCell.getNumericCellValue()
                            : Double.parseDouble(codeCell.getStringCellValue().trim()));
                } catch (RuntimeException e) {
                    continue;
                }
                if (code == employeeId) {
                    for (Cell c : row) {
                        c.setBlank();
                    }
                    blanked = true;
                }
            }
            if (!blanked) {
                throw new IOException("Employee " + employeeId + " not found in " + xlsxSource + " to blank out");
            }
            try (var out = new FileOutputStream(xlsxDest.toFile())) {
                wb.write(out);
            }
        }
    }

    /**
     * The five phases below (each a former @Order(n) method, now a plain private step) run in exactly this sequence,
     * guaranteed by ordinary Java method-call order rather than a JUnit test-ordering guarantee. Each phase's own
     * comment block (just above its method) still explains what it verifies and why.
     *
     * @Transactional here keeps one Hibernate session open for the whole method: RefreshService's own @Transactional
     * methods (refreshSupply/ refreshDemand) join this outer transaction instead of opening and closing their own, so
     * entities fetched afterward via supplyRepo/ demandRepo (e.g. actual.getPersona().getName() below) can still
     * lazy-load their associations -- without this, findById() returns a detached entity once its own transaction
     * closes, and touching a lazy association on it throws LazyInitializationException ("no Session"). Bonus: Spring's
     * test-managed transaction rolls back automatically at the end, so this run leaves no data behind -- no more
     * needing to `docker rm -f`/recreate the Postgres container between runs.
     */
    @Test
    @Transactional
    void refreshServiceEndToEndVerification() throws IOException {
        assumeTrue(filesAvailable, "AFD-Supply.xlsx/Demand.xlsx not found -- see class Javadoc for -Dsupply.xlsx/-Ddemand.xlsx");

        firstSupplyRefreshPersistsEveryClassifiedFieldCorrectly();
        firstDemandRefreshPersistsEveryClassifiedFieldCorrectly();
        personaCatalogDoesNotDuplicateRows();
        reRunningAgainstSameFileIsANoOp();
        missingEmployeeIsSoftDeletedNotHardDeleted();
    }

    // ------------------------------------------------------------------
    // 2. PersonaCatalog get-or-create: exactly one row per distinct
    //    persona/(persona,sub-persona) pair -- no duplicates from
    //    processing hundreds of rows that share the same persona.
    // ------------------------------------------------------------------

    private void firstSupplyRefreshPersistsEveryClassifiedFieldCorrectly() throws IOException {
        List<Map<String, String>> raw = ExcelSheetReader.readFirstSheet(supplyXlsx);
        List<Map<String, String>> phase1 = RefreshLogic.filterPhase1Supply(raw);
        List<RefreshLogic.SupplyClassifiedRow> expected = RefreshLogic.classifySupply(phase1);
        Map<Long, RefreshLogic.SupplyClassifiedRow> expectedById = new HashMap<>();
        for (RefreshLogic.SupplyClassifiedRow row : expected) {
            if (row.employeeId() != null) {
                expectedById.put(row.employeeId(), row);
            }
        }

        RefreshRun run = refreshService.refreshSupply(supplyXlsx);
        assertEquals("succeeded", run.getStatus(), "first supply refresh should succeed: " + run.getErrorMessage());
        assertEquals(expectedById.size(), run.getRowsNew(), "rowsNew should equal every classifiable row on an empty table");
        assertEquals(0, (int) run.getRowsChanged(), "nothing existed before, so rowsChanged should be 0 (new rows aren't 'changed')");

        int mismatches = 0;
        for (var entry : expectedById.entrySet()) {
            SupplyEnriched actual = supplyRepo.findById(entry.getKey()).orElse(null);
            assertNotNull(actual, "employee " + entry.getKey() + " missing from DB after refresh");
            RefreshLogic.SupplyClassifiedRow exp = entry.getValue();

            String expPersona = exp.classification().persona();
            String actPersona = actual.getPersona() == null ? null : actual.getPersona().getName();
            String expSubPersona = blankToNull(exp.classification().subPersona());
            String actSubPersona = actual.getSubPersona() == null ? null : actual.getSubPersona().getName();

            boolean rowMismatch = !Objects.equals(expPersona, actPersona)
                    || !Objects.equals(expSubPersona, actSubPersona)
                    || !Objects.equals(exp.classification().namedAccessories(), actual.getNamedAccessories())
                    || !Objects.equals(exp.classification().frameworkConfirmed(), actual.getFrameworkConfirmed())
                    || !Objects.equals(exp.classificationStatus(), actual.getClassificationStatus())
                    || exp.subCapabilityMismatchFlag() != actual.isSubCapabilityMismatchFlag()
                    || !Objects.equals(exp.subBand(), actual.getSubBand())
                    || !Objects.equals(exp.skillClusterRaw(), actual.getSkillClusterRaw())
                    || !Objects.equals(exp.rating(), actual.getRating())
                    || !Objects.equals(exp.score(), actual.getScore())
                    || !Objects.equals(exp.benchAgeingDays(), actual.getBenchAgeingDays())
                    || !exp.sourceRowHash().equals(actual.getSourceRowHash());
            if (rowMismatch) {
                mismatches++;
                if (mismatches <= 8) {
                    System.out.println("SUPPLY DB MISMATCH employee=" + entry.getKey()
                            + " expected(persona=" + expPersona + ", sub=" + expSubPersona
                            + ", subBand=" + exp.subBand() + ", flag=" + exp.subCapabilityMismatchFlag() + ")"
                            + " actual(persona=" + actPersona + ", sub=" + actSubPersona
                            + ", subBand=" + actual.getSubBand() + ", flag=" + actual.isSubCapabilityMismatchFlag() + ")");
                }
            }
        }
        assertEquals(0, mismatches, mismatches + " supply row(s) persisted incorrectly -- see stdout above");
    }

    // ------------------------------------------------------------------
    // 3. Idempotency: re-running against the SAME file changes nothing.
    //    No new rows, no duplicate rows, no reclassification (hash
    //    unchanged), same persona/sub-persona row counts.
    // ------------------------------------------------------------------

    private void firstDemandRefreshPersistsEveryClassifiedFieldCorrectly() throws IOException {
        List<Map<String, String>> raw = ExcelSheetReader.readFirstSheet(demandXlsx);
        List<Map<String, String>> cleaned = RefreshLogic.stripFooterAndFilterApproved(raw);
        List<RefreshLogic.DemandClassifiedRow> expected = RefreshLogic.classifyDemand(cleaned);
        Map<String, RefreshLogic.DemandClassifiedRow> expectedById = new HashMap<>();
        for (RefreshLogic.DemandClassifiedRow row : expected) {
            if (row.demandId() != null) {
                expectedById.put(row.demandId(), row);
            }
        }

        RefreshRun run = refreshService.refreshDemand(demandXlsx);
        assertEquals("succeeded", run.getStatus(), "first demand refresh should succeed: " + run.getErrorMessage());
        assertEquals(expectedById.size(), run.getRowsNew(), "rowsNew should equal every classifiable row on an empty table");

        int mismatches = 0;
        for (var entry : expectedById.entrySet()) {
            DemandEnriched actual = demandRepo.findById(entry.getKey()).orElse(null);
            assertNotNull(actual, "demand " + entry.getKey() + " missing from DB after refresh");
            RefreshLogic.DemandClassifiedRow exp = entry.getValue();

            String expPersona = exp.classification().persona();
            String actPersona = actual.getPersona() == null ? null : actual.getPersona().getName();
            String expSubPersona = blankToNull(exp.classification().subPersona());
            String actSubPersona = actual.getSubPersona() == null ? null : actual.getSubPersona().getName();

            boolean rowMismatch = !Objects.equals(expPersona, actPersona)
                    || !Objects.equals(expSubPersona, actSubPersona)
                    || !Objects.equals(exp.classification().namedAccessories(), actual.getNamedAccessories())
                    || !Objects.equals(exp.classification().frameworkConfirmed(), actual.getFrameworkConfirmed())
                    || !Objects.equals(exp.classificationStatus(), actual.getClassificationStatus())
                    || exp.masMappingMismatchFlag() != actual.isMasMappingMismatchFlag()
                    || exp.frontendAnchorFlag() != actual.isFrontendAnchorFlag()
                    || !Objects.equals(exp.classification().status(), actual.getClassificationNote())
                    || !Objects.equals(exp.balancePositions(), actual.getBalancePositions())
                    || !Objects.equals(exp.dueCategory(), actual.getDueCategory())
                    || !exp.sourceRowHash().equals(actual.getSourceRowHash());
            if (rowMismatch) {
                mismatches++;
                if (mismatches <= 8) {
                    System.out.println("DEMAND DB MISMATCH demand=" + entry.getKey()
                            + " expected(persona=" + expPersona + ", masFlag=" + exp.masMappingMismatchFlag()
                            + ", frontendAnchorFlag=" + exp.frontendAnchorFlag() + ")"
                            + " actual(persona=" + actPersona + ", masFlag=" + actual.isMasMappingMismatchFlag()
                            + ", frontendAnchorFlag=" + actual.isFrontendAnchorFlag() + ")");
                }
            }
        }
        assertEquals(0, mismatches, mismatches + " demand row(s) persisted incorrectly -- see stdout above");
    }

    // ------------------------------------------------------------------
    // 4. Soft-delete: an employee present in the first refresh but absent
    //    from a later one is deactivated (is_active=false,
    //    inactive_since set), never deleted, and every other row is left
    //    untouched. Built here by blanking one real data row from a copy
    //    of the real workbook (ExcelSheetReader treats an all-blank row
    //    as absent), rather than needing a second hand-maintained fixture
    //    file.
    // ------------------------------------------------------------------

    private void personaCatalogDoesNotDuplicateRows() {
        long personaCount = personaRepo.count();
        // Phase 1 scope is exactly 3 personas (Fullstack Java, Fullstack
        // .NET, Frontend) -- see engine.py/DemandClassifier's own scope.
        assertEquals(3, personaCount, "expected exactly the 3 Phase 1 personas, found " + personaCount
                + " -- PersonaCatalog may be creating duplicate rows for the same name");

        long subPersonaCountAfterFirstPass = subPersonaRepo.count();
        assertTrue(subPersonaCountAfterFirstPass > 0, "expected at least one sub-persona row (Frontend always has one)");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void reRunningAgainstSameFileIsANoOp() {
        long supplyCountBefore = supplyRepo.count();
        long demandCountBefore = demandRepo.count();
        long personaCountBefore = personaRepo.count();
        long subPersonaCountBefore = subPersonaRepo.count();

        RefreshRun supplyRun2 = refreshService.refreshSupply(supplyXlsx);
        RefreshRun demandRun2 = refreshService.refreshDemand(demandXlsx);

        assertEquals("succeeded", supplyRun2.getStatus());
        assertEquals("succeeded", demandRun2.getStatus());
        assertEquals(0, (int) supplyRun2.getRowsNew(), "same file again should create 0 new supply rows");
        assertEquals(0, (int) supplyRun2.getRowsChanged(), "same file again should reclassify 0 supply rows (hash unchanged)");
        assertEquals(0, (int) demandRun2.getRowsNew(), "same file again should create 0 new demand rows");
        assertEquals(0, (int) demandRun2.getRowsChanged(), "same file again should reclassify 0 demand rows (hash unchanged)");

        assertEquals(supplyCountBefore, supplyRepo.count(), "row count must not grow on a repeat refresh (no duplicate rows)");
        assertEquals(demandCountBefore, demandRepo.count(), "row count must not grow on a repeat refresh (no duplicate rows)");
        assertEquals(personaCountBefore, personaRepo.count(), "PersonaCatalog must not create duplicate persona rows on a repeat refresh");
        assertEquals(subPersonaCountBefore, subPersonaRepo.count(), "PersonaCatalog must not create duplicate sub-persona rows on a repeat refresh");
    }

    private void missingEmployeeIsSoftDeletedNotHardDeleted() throws IOException {
        List<SupplyEnriched> activeBefore = supplyRepo.findByIsActiveTrue();
        assertFalse(activeBefore.isEmpty(), "expected at least one active supply row before the trimmed refresh");
        long removedEmployeeId = activeBefore.get(0).getEmployeeId();

        Path trimmed = tempDir.resolve("AFD-Supply-trimmed.xlsx");
        blankOneDataRowWhereEmployeeCodeEquals(supplyXlsx, trimmed, removedEmployeeId);

        RefreshRun run = refreshService.refreshSupply(trimmed);
        assertEquals("succeeded", run.getStatus(), "trimmed-file refresh should still succeed: " + run.getErrorMessage());

        SupplyEnriched removed = supplyRepo.findById(removedEmployeeId).orElse(null);
        assertNotNull(removed, "soft-deleted employee must still exist in the table (never hard-deleted)");
        assertFalse(removed.isActive(), "employee absent from the latest file must be marked inactive");
        assertNotNull(removed.getInactiveSince(), "inactive_since must be set when an employee is soft-deleted");

        for (SupplyEnriched stillThere : activeBefore) {
            if (stillThere.getEmployeeId().equals(removedEmployeeId)) {
                continue;
            }
            SupplyEnriched current = supplyRepo.findById(stillThere.getEmployeeId()).orElseThrow();
            assertTrue(current.isActive(), "employee " + stillThere.getEmployeeId()
                    + " was present in the trimmed file too and must remain active");
        }

        // Restore full active state for any later test run against the same
        // context (not strictly needed since each `mvn test` gets a fresh
        // in-memory H2 instance, but cheap insurance if that ever changes).
        RefreshRun restoreRun = refreshService.refreshSupply(supplyXlsx);
        assertEquals("succeeded", restoreRun.getStatus());
        assertTrue(supplyRepo.findById(removedEmployeeId).orElseThrow().isActive(),
                "employee must be reactivated once they reappear in a later file");
        assertNull(supplyRepo.findById(removedEmployeeId).orElseThrow().getInactiveSince(),
                "inactive_since must be cleared on reactivation");
    }
}
