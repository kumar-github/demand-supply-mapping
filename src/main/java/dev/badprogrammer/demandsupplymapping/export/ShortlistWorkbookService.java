package dev.badprogrammer.demandsupplymapping.export;

import dev.badprogrammer.demandsupplymapping.entity.DemandEnriched;
import dev.badprogrammer.demandsupplymapping.entity.SupplyEnriched;
import dev.badprogrammer.demandsupplymapping.matching.*;
import dev.badprogrammer.demandsupplymapping.refresh.RefreshLogic;
import dev.badprogrammer.demandsupplymapping.repository.DemandEnrichedRepository;
import dev.badprogrammer.demandsupplymapping.repository.SupplyEnrichedRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Java port of write_shortlist_workbook.py + build_matches.py's main() — builds the "Phase 1 Shortlist" workbook (task
 * #19). Runs a fresh matching pass at export time (same candidate scope and per-employee cap as
 * MatchingRunService.runMatching()) rather than reading match_candidates, because match_candidates only persists each
 * signal's quality code and the one-liner (see MatchingRunService/MatchCandidate) — not the full collapsed/expanded
 * text this workbook shows in the "— summary"/"— raw detail" column pairs. Recomputing here keeps this export
 * self-contained and always current, at the cost of duplicating MatchingRunService's small candidate-scope filter
 * (candidateDemandsFor below) — see that method's Javadoc.
 * <p>
 * Formatting (colors, fonts, column widths, freeze panes, employee/DATA-ISSUE block layout) mirrors the Python source
 * as closely as Apache POI allows. The "Read Me" sheet keeps the Python version's structure (scope, tier definitions,
 * capping rule, classification approach, known limitations) but recomputes every number live from the current database
 * rather than hard-coding the original Phase-1 run's snapshot figures (e.g. "16 employees have zero Strong/Good match")
 * — those were true of one specific historical run, not a general fact worth freezing into every future export.
 */
@Service
public class ShortlistWorkbookService {

    /**
     * Demands per run shown on the main "Demand Shortlist" tab, after sorting by urgency (ageing rank, then open
     * positions). This is a genuine placeholder, not a verified number — the user had no existing figure for how many
     * demands TAG/TSC can realistically act on in a week when this was built, and said to pick one and adjust after
     * seeing real output. 50 was chosen as a plausible middle ground (small enough to not reproduce the "overwhelming"
     * complaint this export exists to fix, large enough to be worth a weekly run) — treat it as the first number to
     * revisit once someone who actually works the list has an opinion. Exposed as a parameter on generateByDemand()
     * (and as a field in the UI/REST layer) specifically so it can be tuned without a code change.
     */
    public static final int DEFAULT_MAX_DEMANDS_PER_RUN = 50;
    private static final String FONT_NAME = "Arial";
    private static final Map<String, Integer> TIER_ORDER = Map.of("Strong", 0, "Good", 1, "Weak", 2, "Excluded", 3);
    private static final String[][] COLUMNS = {
            {"Overall Signal", "14"}, {"Employee Band", "12"}, {"Employee Location", "14"},
            {"Job Req ID", "11"}, {"Customer", "16"}, {"Project", "20"},
            {"Demand Persona / Sub-persona", "24"}, {"Demand Band", "10"}, {"Demand Location", "14"},
            {"Balance Positions", "10"}, {"Due Category", "16"}, {"Ageing Bucket", "12"},
            {"Skill — summary", "46"}, {"Band — summary", "20"}, {"Location — summary", "16"},
            {"Assessment — summary", "24"}, {"Skill — raw detail", "46"}, {"Band — raw detail", "30"},
            {"Location — raw detail", "40"}, {"Assessment — raw detail", "28"}, {"Reasoning (one-line)", "60"},
            {"MAS Mapping check", "30"}, {"Frontend Anchor check", "40"},
            // Added 2026-10-01 — mirrors write_shortlist_workbook.py's "MAS Mapping cross-check"
            // column exactly, same position (right after Frontend Anchor check, before Additional
            // Request). See ShortlistRow.masMappingCrossCheckNote's javadoc for what it checks.
            {"MAS Mapping cross-check", "40"},
            {"Additional Request (manual review only)", "50"}
    };
    // 0-indexed column positions of the two review-flag columns that only get a value (and bold amber styling) when
    // actually flagged — mirrors write_shortlist_workbook.py's `elif c in (22, 23) and v` (its 1-indexed columns).
    private static final int COL_MAS_MAPPING_CHECK = 21;
    private static final int COL_FRONTEND_ANCHOR_CHECK = 22;

    // ------------------------------------------------------------------
    // Demand-centric export (added 2026-10-03) — same engine, data and tiers as the employee-centric sheet
    // above, reorganized around the fulfillment team's actual unit of work (one demand, many candidates)
    // instead of the proposal team's (one employee, many demands). See the class-level discussion that led
    // here: TAG/TSC (who fulfill demands) were getting the employee-grouped sheet and had no way to jump to
    // "who's available for demand X" without reading past every employee. This does NOT replace generate() —
    // both exports stay available, each for the team whose job matches its grouping.
    // ------------------------------------------------------------------
    private static final int COL_MAS_MAPPING_CROSS_CHECK = 23; // added 2026-10-01
    // Mirrors MAX_GOOD_PER_EMPLOYEE's role on the other axis — kept at the same value for consistency with the
    // employee-centric sheet's cap, not because it's been separately validated for this axis. Revisit independently
    // if a demand with many plausible Good candidates turns out to need more than 3 shown.
    private static final int MAX_GOOD_PER_DEMAND = 3;

    private static final String[][] DEMAND_CANDIDATE_COLUMNS = {
            {"Overall Signal", "14"}, {"Employee Name", "26"}, {"Employee Band", "12"}, {"Employee Location", "14"},
            {"Bench Ageing (days)", "14"},
            {"Skill — summary", "46"}, {"Band — summary", "20"}, {"Location — summary", "16"},
            {"Assessment — summary", "24"}, {"Skill — raw detail", "46"}, {"Band — raw detail", "30"},
            {"Location — raw detail", "40"}, {"Assessment — raw detail", "28"}, {"Reasoning (one-line)", "60"},
            {"MAS Mapping cross-check", "40"}, {"Additional Request (manual review only)", "50"}
    };
    private static final int DCOL_MAS_MAPPING_CROSS_CHECK = 14;

    private static final String[][] NO_COVERAGE_COLUMNS = {
            {"Job Req ID", "11"}, {"Customer", "16"}, {"Project", "20"}, {"Demand Persona / Sub-persona", "24"},
            {"Demand Band", "10"}, {"Demand Location", "14"}, {"Balance Positions", "10"}, {"Due Category", "16"},
            {"Ageing Bucket", "12"}, {"Why no candidate", "50"}
    };

    private final SupplyEnrichedRepository supplyRepo;
    private final DemandEnrichedRepository demandRepo;
    private final MatchingService matchingService;

    public ShortlistWorkbookService(SupplyEnrichedRepository supplyRepo, DemandEnrichedRepository demandRepo,
                                    MatchingService matchingService) {
        this.supplyRepo = supplyRepo;
        this.demandRepo = demandRepo;
        this.matchingService = matchingService;
    }

    private static void setValue(Cell cell, Object v) {
        if (v == null) {
            cell.setBlank();
        } else if (v instanceof Integer i) {
            cell.setCellValue(i);
        } else if (v instanceof Double d) {
            cell.setCellValue(d);
        } else {
            cell.setCellValue(v.toString());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String fmtNum(Integer v) {
        return v == null ? "" : String.valueOf(v);
    }

    // ------------------------------------------------------------------
    // Demand-centric sheets
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public byte[] generate() {
        List<SupplyEnriched> allSupply = supplyRepo.findByIsActiveTrue();
        List<SupplyEnriched> classifiedSupply = allSupply.stream().filter(s -> s.getPersona() != null).toList();
        List<SupplyEnriched> unclassifiedSupply = allSupply.stream()
                .filter(s -> s.getPersona() == null)
                .sorted(Comparator.comparing(SupplyEnriched::getEmployeeName, Comparator.nullsLast(String::compareTo)))
                .toList();

        List<DemandEnriched> classifiedDemand = demandRepo.findByIsActiveTrue().stream()
                .filter(d -> d.getPersona() != null)
                .toList();
        Map<String, List<DemandEnriched>> demandByPersona = classifiedDemand.stream()
                .collect(Collectors.groupingBy(d -> d.getPersona().getName()));

        // employeeId -> capped, tier/ageing/demandId-sorted rows for that employee
        Map<Long, List<ShortlistRow>> byEmployee = new LinkedHashMap<>();
        for (SupplyEnriched emp : classifiedSupply) {
            List<DemandEnriched> candidates = candidateDemandsFor(emp, demandByPersona);
            if (candidates.isEmpty()) {
                continue;
            }
            List<ShortlistRow> rows = buildRows(emp, candidates);
            if (rows.isEmpty()) {
                continue;
            }
            List<ShortlistRow> kept = capAndSort(rows);
            if (!kept.isEmpty()) {
                byEmployee.put(emp.getEmployeeId(), kept);
            }
        }

        // Employees ordered by name, same as ordered_emp_codes in the Python source.
        List<SupplyEnriched> orderedEmployees = classifiedSupply.stream()
                .filter(e -> byEmployee.containsKey(e.getEmployeeId()))
                .sorted(Comparator.comparing(SupplyEnriched::getEmployeeName, Comparator.nullsLast(String::compareTo)))
                .toList();

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles styles = new Styles(wb);
            writeShortlistSheet(wb, styles, orderedEmployees, byEmployee, unclassifiedSupply);
            writeReadMeSheet(wb, styles, orderedEmployees, byEmployee, classifiedSupply.size() + unclassifiedSupply.size(),
                    unclassifiedSupply.size(), classifiedDemand);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build shortlist workbook", e);
        }
    }

    /**
     * Demand-centric counterpart to generate() — see the class block comment above "DEFAULT_MAX_DEMANDS_PER_RUN" for
     * why this exists. Runs the same matching pass (same scope, same signals, same tiers) but groups by demand instead
     * of employee, keeps only demands with at least one Strong/Good candidate on the main tab (everything else goes to
     * "No Coverage" — a demand with nobody Strong/Good in scope needs external sourcing, not a candidate list), and
     * caps the main tab to the {@code maxDemands} most urgent such demands so one run is an actionable weekly worklist
     * rather than the entire open portfolio at once.
     *
     * @param maxDemands how many demands (by urgency) to include on the main tab; see DEFAULT_MAX_DEMANDS_PER_RUN
     */
    @Transactional(readOnly = true)
    public byte[] generateByDemand(int maxDemands) {
        List<SupplyEnriched> classifiedSupply = supplyRepo.findByIsActiveTrue().stream()
                .filter(s -> s.getPersona() != null)
                .toList();

        List<DemandEnriched> classifiedDemand = demandRepo.findByIsActiveTrue().stream()
                .filter(d -> d.getPersona() != null)
                .toList();
        Map<String, List<DemandEnriched>> demandByPersona = classifiedDemand.stream()
                .collect(Collectors.groupingBy(d -> d.getPersona().getName()));

        // Every employee x demand row the engine can justify, uncapped — capping happens below, per demand,
        // not per employee (that's the whole point of this export). Same buildRows() as generate() uses, so
        // the signals/tiers/one-liners are identical; only the grouping and capping axis differ.
        List<ShortlistRow> allRows = new ArrayList<>();
        for (SupplyEnriched emp : classifiedSupply) {
            List<DemandEnriched> candidates = candidateDemandsFor(emp, demandByPersona);
            if (!candidates.isEmpty()) {
                allRows.addAll(buildRows(emp, candidates));
            }
        }
        Map<String, List<ShortlistRow>> byDemand = allRows.stream()
                .collect(Collectors.groupingBy(ShortlistRow::demandId));

        Comparator<DemandEnriched> byUrgencyThenOpenPositions = Comparator
                .<DemandEnriched>comparingInt(d -> matchingService.ageingRank(d.getDueCategory()))
                .thenComparingInt(d -> -(d.getBalancePositions() == null ? 0 : d.getBalancePositions()));

        List<DemandEnriched> actionable = new ArrayList<>();
        List<DemandEnriched> noCoverage = new ArrayList<>();
        for (DemandEnriched dem : classifiedDemand) {
            List<ShortlistRow> rows = byDemand.getOrDefault(dem.getDemandId(), List.of());
            boolean hasStrongOrGood = rows.stream().anyMatch(r -> "Strong".equals(r.overallTier()) || "Good".equals(r.overallTier()));
            if (hasStrongOrGood) {
                actionable.add(dem);
            } else {
                noCoverage.add(dem);
            }
        }
        actionable.sort(byUrgencyThenOpenPositions);
        noCoverage.sort(byUrgencyThenOpenPositions);

        List<DemandEnriched> shown = actionable.stream().limit(Math.max(0, maxDemands)).toList();
        int overflow = actionable.size() - shown.size();

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles styles = new Styles(wb);
            writeDemandShortlistSheet(wb, styles, shown, byDemand, overflow, maxDemands);
            writeNoCoverageSheet(wb, styles, noCoverage, byDemand);
            writeDemandReadMeSheet(wb, styles, classifiedDemand.size(), actionable.size(), shown.size(), overflow,
                    noCoverage.size(), maxDemands);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build demand-centric shortlist workbook", e);
        }
    }

    /**
     * Demand-side counterpart to capAndSort()/MatchingService.capEmployeeRows() — all Strong candidates kept, top
     * MAX_GOOD_PER_DEMAND Good candidates kept. No Weak-fallback or near-miss-Excluded rows here: this method is only
     * ever called for a demand already known to have at least one Strong/Good row (see generateByDemand's "actionable"
     * filter), so there's no empty-tier case to fall back from the way the employee side has. Ranks candidates within a
     * tier by assessment strength first (a real, if soft, quality signal), then by bench ageing (longest-benched first
     * — ties the ranking back to the bench-reduction goal when assessment ties), then by name for stable output.
     */
    private List<ShortlistRow> capDemandRows(List<ShortlistRow> rows) {
        Comparator<ShortlistRow> byAssessmentThenAgeingThenName = Comparator
                .<ShortlistRow>comparingInt(ShortlistRow::employeeAssessmentRank).reversed()
                .thenComparing(Comparator.<ShortlistRow>comparingInt(
                        r -> r.employeeBenchAgeing() == null ? 0 : r.employeeBenchAgeing()).reversed())
                .thenComparing(ShortlistRow::employeeName, Comparator.nullsLast(String::compareTo));

        List<ShortlistRow> strong = rows.stream().filter(r -> "Strong".equals(r.overallTier()))
                .sorted(byAssessmentThenAgeingThenName).toList();
        List<ShortlistRow> good = rows.stream().filter(r -> "Good".equals(r.overallTier()))
                .sorted(byAssessmentThenAgeingThenName)
                .limit(MAX_GOOD_PER_DEMAND)
                .toList();

        List<ShortlistRow> kept = new ArrayList<>(strong);
        kept.addAll(good);
        return kept;
    }

    private void writeDemandShortlistSheet(XSSFWorkbook wb, Styles s, List<DemandEnriched> shown,
                                           Map<String, List<ShortlistRow>> byDemand, int overflow, int maxDemands) {
        Sheet ws = wb.createSheet("Demand Shortlist");
        ws.setDisplayGridlines(false);

        Row r1 = ws.createRow(0);
        Cell title = r1.createCell(0);
        title.setCellValue("Demand & Supply Mapping — Matching Engine Output (by Demand)");
        title.setCellStyle(s.title);
        ws.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));

        Row r2 = ws.createRow(1);
        Cell subtitle = r2.createCell(0);
        subtitle.setCellValue("Grouped by demand, most urgent first; only demands with a Strong/Good candidate are "
                + "shown here (see the 'No Coverage' tab for the rest), capped to the top " + maxDemands
                + " most urgent this run" + (overflow > 0 ? " — " + overflow + " more actionable demand(s) waiting "
                + "for next run" : "") + ". See 'Read Me' for methodology and the per-demand candidate cap.");
        subtitle.setCellStyle(s.subtitle);
        ws.addMergedRegion(new CellRangeAddress(1, 1, 0, DEMAND_CANDIDATE_COLUMNS.length - 1));

        int headerRowIdx = 3;
        Row headerRow = ws.createRow(headerRowIdx);
        headerRow.setHeightInPoints(30f);
        for (int c = 0; c < DEMAND_CANDIDATE_COLUMNS.length; c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(DEMAND_CANDIDATE_COLUMNS[c][0]);
            cell.setCellStyle(s.header);
            ws.setColumnWidth(c, Integer.parseInt(DEMAND_CANDIDATE_COLUMNS[c][1]) * 256);
        }
        ws.createFreezePane(0, headerRowIdx + 1);

        int row = headerRowIdx + 1;
        for (DemandEnriched dem : shown) {
            List<ShortlistRow> candidateRows = capDemandRows(byDemand.getOrDefault(dem.getDemandId(), List.of()));
            row = writeDemandBlock(ws, s, row, dem, candidateRows);
        }
    }

    private int writeDemandBlock(Sheet ws, Styles s, int row, DemandEnriched dem, List<ShortlistRow> candidateRows) {
        ShortlistRow first = candidateRows.get(0);
        String masMappingNote = first.masMappingNote();
        String frontendAnchorNote = first.frontendAnchorNote();
        String flagSuffix = "";
        if (masMappingNote != null && !masMappingNote.isBlank()) {
            flagSuffix += "  |  ⚠ MAS Mapping check: " + masMappingNote;
        }
        if (frontendAnchorNote != null && !frontendAnchorNote.isBlank()) {
            flagSuffix += "  |  ⚠ Frontend Anchor check: " + frontendAnchorNote;
        }

        Row headerRow = ws.createRow(row);
        headerRow.setHeightInPoints(20f);
        Cell cell = headerRow.createCell(0);
        cell.setCellValue(dem.getDemandId() + "  —  " + nz(dem.getCustomer()) + " / " + nz(dem.getProjectName())
                + "  |  " + combinePersona(first.demandPersona(), first.demandSubPersona())
                + "  |  Band: " + nz(dem.getBand())
                + "  |  Location: " + nz(dem.getLocation())
                + "  |  Open positions: " + fmtNum(dem.getBalancePositions())
                + "  |  Due: " + nz(dem.getDueCategory()) + " (" + nz(first.ageingBucket()) + ")"
                + flagSuffix);
        cell.setCellStyle(s.empHeader);
        ws.addMergedRegion(new CellRangeAddress(row, row, 0, DEMAND_CANDIDATE_COLUMNS.length - 1));
        row++;

        for (ShortlistRow r : candidateRows) {
            Row dataRow = ws.createRow(row);
            String tier = r.overallTier();
            Object[] values = {
                    tier, r.employeeName(), r.employeeBand(), r.employeeLocation(), fmtNum(r.employeeBenchAgeing()),
                    r.skillCollapsed(), r.bandCollapsed(), r.locationCollapsed(), r.assessmentCollapsed(),
                    r.skillExpanded(), r.bandExpanded(), r.locationExpanded(), r.assessmentExpanded(), r.oneLiner(),
                    nz(r.masMappingCrossCheckNote()), nz(r.additionalRequest())
            };
            for (int c = 0; c < values.length; c++) {
                Cell dc = dataRow.createCell(c);
                setValue(dc, values[c]);
                if (c == 0) {
                    dc.setCellStyle(s.tierCell(tier));
                } else if (c == DCOL_MAS_MAPPING_CROSS_CHECK && values[c] != null && !values[c].toString().isEmpty()) {
                    dc.setCellStyle(s.reviewFlagCell());
                } else {
                    boolean wrap = (c == 5 || c == 9 || c == 10 || c == 11 || c == 13 || c == 15);
                    dc.setCellStyle(s.bodyCell(false, wrap));
                }
            }
            row++;
        }
        return row;
    }

    private void writeNoCoverageSheet(XSSFWorkbook wb, Styles s, List<DemandEnriched> noCoverage,
                                      Map<String, List<ShortlistRow>> byDemand) {
        Sheet ws = wb.createSheet("No Coverage");
        ws.setDisplayGridlines(false);

        Row r1 = ws.createRow(0);
        Cell title = r1.createCell(0);
        title.setCellValue("Demands with no Strong/Good bench candidate right now");
        title.setCellStyle(s.title);
        ws.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));

        Row r2 = ws.createRow(1);
        Cell subtitle = r2.createCell(0);
        subtitle.setCellValue("Sorted most urgent first. These need external hiring or escalation, not a candidate "
                + "shortlist — a Weak/Excluded-only row means someone technically matched but not well enough to "
                + "propose; a 'no candidates at all' row means no Phase 1 employee shares this persona in the "
                + "current data.");
        subtitle.setCellStyle(s.subtitle);
        ws.addMergedRegion(new CellRangeAddress(1, 1, 0, NO_COVERAGE_COLUMNS.length - 1));

        int headerRowIdx = 3;
        Row headerRow = ws.createRow(headerRowIdx);
        headerRow.setHeightInPoints(30f);
        for (int c = 0; c < NO_COVERAGE_COLUMNS.length; c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(NO_COVERAGE_COLUMNS[c][0]);
            cell.setCellStyle(s.header);
            ws.setColumnWidth(c, Integer.parseInt(NO_COVERAGE_COLUMNS[c][1]) * 256);
        }
        ws.createFreezePane(0, headerRowIdx + 1);

        int row = headerRowIdx + 1;
        for (DemandEnriched dem : noCoverage) {
            List<ShortlistRow> rows = byDemand.getOrDefault(dem.getDemandId(), List.of());
            String why;
            if (rows.isEmpty()) {
                why = "No Phase 1 employee shares this persona at all right now.";
            } else {
                long weak = rows.stream().filter(r -> "Weak".equals(r.overallTier())).count();
                long excluded = rows.stream().filter(r -> "Excluded".equals(r.overallTier())).count();
                why = "Candidates exist but none Strong/Good — " + weak + " Weak, " + excluded
                        + " Excluded (band/sub-persona mismatch) in scope. See the employee-centric sheet for detail.";
            }
            String persona = rows.isEmpty() ? nz(dem.getPersona() == null ? null : dem.getPersona().getName())
                    : combinePersona(rows.get(0).demandPersona(), rows.get(0).demandSubPersona());
            Row dataRow = ws.createRow(row);
            Object[] values = {
                    dem.getDemandId(), dem.getCustomer(), dem.getProjectName(), persona, dem.getBand(),
                    dem.getLocation(), fmtNum(dem.getBalancePositions()), dem.getDueCategory(), dem.getNewAgeing(), why
            };
            for (int c = 0; c < values.length; c++) {
                Cell dc = dataRow.createCell(c);
                setValue(dc, values[c]);
                dc.setCellStyle(s.bodyCell(false, c == NO_COVERAGE_COLUMNS.length - 1));
            }
            row++;
        }
    }

    private void writeDemandReadMeSheet(XSSFWorkbook wb, Styles s, int totalClassifiedDemand, int actionableCount,
                                        int shownCount, int overflow, int noCoverageCount, int maxDemands) {
        Sheet rm = wb.createSheet("Read Me");
        rm.setColumnWidth(0, 100 * 256);
        int[] r = {0};

        addLine(rm, s.title, r, "Demand & Supply Mapping — Matching Engine (by Demand): Methodology & Coverage Notes");
        r[0]++;
        addLine(rm, s.bold, r, "Why this export exists");
        addLine(rm, s.body, r, "The employee-centric 'Phase 1 Shortlist' workbook groups by employee, which is the "
                + "right view for the team proposing supply but the wrong one for the team fulfilling a specific "
                + "demand — they'd have to scan every employee to find candidates for one requisition. This export "
                + "is the same matching engine and tiers, regrouped around one demand per block.");
        r[0]++;

        addLine(rm, s.bold, r, "Scope (this export)");
        addLine(rm, s.body, r, totalClassifiedDemand + " classified, active demand rows on file; " + actionableCount
                + " have at least one Strong/Good candidate right now (\"actionable\"); " + noCoverageCount
                + " do not and are listed on the 'No Coverage' tab instead.");
        r[0]++;

        addLine(rm, s.bold, r, "Weekly worklist cap");
        addLine(rm, s.body, r, "Of the " + actionableCount + " actionable demands, the " + shownCount
                + " most urgent (by ageing rank, then open positions) are shown on the main tab this run"
                + (overflow > 0 ? "; " + overflow + " more are actionable but waiting for a future run" : "")
                + ". The cap is currently " + maxDemands + " — a starting placeholder, not a measured capacity "
                + "figure; adjust it (the 'limit' parameter on this export) once real weekly throughput is known.");
        r[0]++;

        addLine(rm, s.bold, r, "Per-demand candidate cap");
        addLine(rm, s.body, r, "All Strong candidates kept; top " + MAX_GOOD_PER_DEMAND + " Good candidates kept, "
                + "ranked by assessment strength then bench ageing (longest-benched first) then name. Weak/Excluded "
                + "candidates are never shown here — a demand only appears on this tab because it already has a "
                + "Strong/Good candidate, so there's no empty-tier case to fall back to the way the employee-centric "
                + "sheet has. See the 'No Coverage' tab for demands where only Weak/Excluded candidates exist.");
        r[0]++;

        addLine(rm, s.bold, r, "Overall Signal tiers");
        addLine(rm, s.body, r, "Same definitions as the employee-centric workbook: Strong = same persona, core "
                + "skills present, band exact, same city. Good = persona match with exactly one thing discounted "
                + "(one band above, different city, or a named-accessory gap).");
    }

    /**
     * Same broad-persona-family candidate scope as MatchingRunService.candidateDemandsFor() — duplicated rather than
     * shared to keep this export independently readable and to avoid widening MatchingRunService's method visibility
     * for a single caller; see this class's own Javadoc for why a fresh pass is computed here at all.
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

    private List<ShortlistRow> buildRows(SupplyEnriched emp, List<DemandEnriched> candidates) {
        ClassificationResult empClassification = toClassificationResult(emp);
        List<ShortlistRow> rows = new ArrayList<>();
        for (DemandEnriched dem : candidates) {
            ClassificationResult demClassification = toClassificationResult(dem);
            SignalResult skill = matchingService.skillSignal(empClassification, demClassification);
            if ("no_relation".equals(skill.quality())) {
                continue;
            }
            SignalResult band = matchingService.bandSignal(emp.getSubBand(), dem.getBand());
            SignalResult location = matchingService.locationSignal(emp.getLocation(), dem.getLocation(), null);
            AssessmentResult assessment = matchingService.assessmentSignal(emp.getRating(), emp.getScore());
            String tier = matchingService.overallTier(skill, band, location);
            int ageingRank = matchingService.ageingRank(dem.getDueCategory());

            List<String> bits = new ArrayList<>();
            bits.add("core".equals(skill.quality()) ? leadIn(skill.collapsed()) : skill.collapsed());
            bits.add(band.collapsed());
            bits.add(location.collapsed());
            if (!"Not yet assessed".equals(assessment.collapsed())) {
                bits.add(assessment.collapsed());
            }
            String oneLiner = String.join("; ", bits);

            // Reviewer-only flags — checked against the engine-derived persona, never used to filter/score.
            // Mirrors build_matches.py's mas_mapping_note/frontend_anchor_note/additional_request passthrough.
            String masMappingNote = RefreshLogic.masMappingNote(dem.getMasMappingRaw(), demClassification.persona());
            String frontendAnchorNote = RefreshLogic.frontendAnchorNote(
                    dem.getFinalSkillClusterRaw(), demClassification.persona(), demClassification.frameworkConfirmed());
            String additionalRequest = dem.getAdditionalRequestRaw();
            // Pairing-level cross-check (2026-10-01) — distinct from masMappingNote above, which
            // only ever looks at the demand's own raw value vs. its engine-derived persona. This
            // compares the demand's raw MAS Mapping against the MATCHED employee's own raw MAS
            // Mapping. See MatchingService.masMappingCrossCheck()'s javadoc for the full reasoning.
            String masMappingCrossCheckNote = matchingService.masMappingCrossCheckNote(
                    emp.getMasMappingRaw(), dem.getMasMappingRaw());

            rows.add(new ShortlistRow(
                    emp.getEmployeeId(), emp.getEmployeeName(),
                    empClassification.persona(), empClassification.subPersona(), emp.getCompleteness(),
                    emp.getSubBand(), emp.getLocation(), emp.getPrimeNv(), emp.getAfdStatus(), emp.getBenchAgeingDays(),
                    assessment.rank(),
                    dem.getDemandId(), dem.getCustomer(), dem.getProjectName(),
                    demClassification.persona(), demClassification.subPersona(), dem.getBand(), dem.getLocation(),
                    dem.getBalancePositions(), dem.getDueCategory(), dem.getNewAgeing(),
                    tier, ageingRank, skill.quality(),
                    skill.collapsed(), skill.expanded(), band.collapsed(), band.expanded(),
                    location.collapsed(), location.expanded(), assessment.collapsed(), assessment.expanded(),
                    oneLiner,
                    masMappingNote, frontendAnchorNote, masMappingCrossCheckNote, additionalRequest
            ));
        }
        return rows;
    }

    private String leadIn(String collapsed) {
        int idx = collapsed.indexOf(" — ");
        return idx < 0 ? collapsed : collapsed.substring(0, idx);
    }

    private ClassificationResult toClassificationResult(SupplyEnriched emp) {
        return new ClassificationResult(
                emp.getPersona() == null ? null : emp.getPersona().getName(),
                emp.getSubPersona() == null ? null : emp.getSubPersona().getName(),
                emp.getNamedAccessories(), emp.getCompleteness(), emp.getFrameworkConfirmed(), null
        );
    }

    // ------------------------------------------------------------------
    // Sheet 1: Phase 1 Shortlist
    // ------------------------------------------------------------------

    private ClassificationResult toClassificationResult(DemandEnriched dem) {
        return new ClassificationResult(
                dem.getPersona() == null ? null : dem.getPersona().getName(),
                dem.getSubPersona() == null ? null : dem.getSubPersona().getName(),
                dem.getNamedAccessories(), null, dem.getFrameworkConfirmed(), dem.getClassificationNote()
        );
    }

    /**
     * capEmployeeRows() operates on MatchRow (matching.MatchRow — the minimal shape it needs to sort/cap); this maps
     * ShortlistRow -> MatchRow, caps, then re-joins back to the richer ShortlistRow by demandId (safe: employeeId is
     * fixed for the whole call, so demandId alone is a unique key within one employee's rows) — same approach
     * MatchingRunService uses to go the other way (MatchRow -> MatchCandidate). Final sort matches
     * write_shortlist_workbook.py's build(): tier order, then ageing_rank, then job_req_id (demandId) — capEmployeeRows
     * groups by tier but does not itself sort each tier by ageing_rank/demandId the way the workbook wants.
     */
    private List<ShortlistRow> capAndSort(List<ShortlistRow> rows) {
        // bandOverrideEligible is always false here — the Excel export's capping behavior is
        // unchanged by the web app's Override feature; only DemandReviewService's review
        // workspace carves out that separate bucket (see MatchingService.isOneBandBelow()).
        List<MatchRow> matchRows = rows.stream()
                .map(r -> new MatchRow(r.employeeId(), r.demandId(), r.overallTier(), r.skillQuality(),
                        r.ageingRank(), r.balancePositions(), r.oneLiner(), null, null, null, false))
                .toList();
        List<MatchRow> capped = matchingService.capEmployeeRows(matchRows);
        Set<String> keptDemandIds = capped.stream().map(MatchRow::demandId).collect(Collectors.toCollection(HashSet::new));

        Comparator<ShortlistRow> byTierThenUrgencyThenDemandId = Comparator
                .<ShortlistRow>comparingInt(r -> TIER_ORDER.getOrDefault(r.overallTier(), 9))
                .thenComparingInt(ShortlistRow::ageingRank)
                .thenComparing(ShortlistRow::demandId, Comparator.nullsLast(String::compareTo));

        return rows.stream()
                .filter(r -> keptDemandIds.contains(r.demandId()))
                .sorted(byTierThenUrgencyThenDemandId)
                .toList();
    }

    private String combinePersona(String persona, String sub) {
        return (sub == null || sub.isBlank()) ? persona : persona + " / " + sub;
    }

    // ------------------------------------------------------------------
    // Sheet 2: Read Me
    // ------------------------------------------------------------------

    private void writeShortlistSheet(XSSFWorkbook wb, Styles s, List<SupplyEnriched> orderedEmployees,
                                     Map<Long, List<ShortlistRow>> byEmployee, List<SupplyEnriched> unclassifiedSupply) {
        Sheet ws = wb.createSheet("Phase 1 Shortlist");
        ws.setDisplayGridlines(false);

        Row r1 = ws.createRow(0);
        Cell title = r1.createCell(0);
        title.setCellValue("Demand & Supply Mapping — Matching Engine Output");
        title.setCellStyle(s.title);
        ws.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));

        Row r2 = ws.createRow(1);
        Cell subtitle = r2.createCell(0);
        subtitle.setCellValue("Grouped by employee; within each employee, rows are sorted Strong > Good > Weak > Excluded "
                + "(see 'Read Me' tab for methodology, the row-cap applied per employee, and open/unconfirmed items).");
        subtitle.setCellStyle(s.subtitle);
        ws.addMergedRegion(new CellRangeAddress(1, 1, 0, COLUMNS.length - 1));

        int headerRowIdx = 3;
        Row headerRow = ws.createRow(headerRowIdx);
        headerRow.setHeightInPoints(30f);
        for (int c = 0; c < COLUMNS.length; c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(COLUMNS[c][0]);
            cell.setCellStyle(s.header);
            ws.setColumnWidth(c, Integer.parseInt(COLUMNS[c][1]) * 256);
        }
        ws.createFreezePane(0, headerRowIdx + 1);

        int row = headerRowIdx + 1;
        for (SupplyEnriched emp : orderedEmployees) {
            List<ShortlistRow> empRows = byEmployee.get(emp.getEmployeeId());
            row = writeEmployeeBlock(ws, s, row, emp, empRows);
        }
        for (SupplyEnriched emp : unclassifiedSupply) {
            row = writeDataIssueBlock(ws, s, row, emp);
        }
    }

    private int writeEmployeeBlock(Sheet ws, Styles s, int row, SupplyEnriched emp, List<ShortlistRow> empRows) {
        boolean hasStrongOrGood = empRows.stream().anyMatch(r -> "Strong".equals(r.overallTier()) || "Good".equals(r.overallTier()));
        String flag = hasStrongOrGood ? "" : "  ⚠ NO STRONG/GOOD MATCH IN SCOPE — ";

        Row headerRow = ws.createRow(row);
        headerRow.setHeightInPoints(20f);
        Cell cell = headerRow.createCell(0);
        ShortlistRow first = empRows.get(0);
        String subCapabilityNote = RefreshLogic.subCapabilityNote(emp.getSubCapabilityRaw(), first.employeePersona());
        String scSuffix = (subCapabilityNote == null || subCapabilityNote.isBlank())
                ? "" : "  |  ⚠ Sub-Capability check: " + subCapabilityNote;
        cell.setCellValue(flag + emp.getEmployeeName() + "  (Emp Code " + emp.getEmployeeId() + ")  —  "
                + combinePersona(first.employeePersona(), first.employeeSubPersona())
                + "  |  Completeness: " + nz(emp.getCompleteness())
                + "  |  Band: " + nz(emp.getSubBand())
                + "  |  Location: " + nz(emp.getLocation()) + " (" + nz(emp.getPrimeNv()) + ")"
                + "  |  Status: " + nz(emp.getAfdStatus())
                + "  |  Bench ageing: " + fmtNum(emp.getBenchAgeingDays()) + " days"
                + scSuffix);
        cell.setCellStyle(hasStrongOrGood ? s.empHeader : s.empHeaderNoMatch);
        ws.addMergedRegion(new CellRangeAddress(row, row, 0, COLUMNS.length - 1));
        row++;

        for (ShortlistRow r : empRows) {
            Row dataRow = ws.createRow(row);
            String tier = r.overallTier();
            Object[] values = {
                    tier, r.employeeBand(), r.employeeLocation(), r.demandId(), r.customer(), r.project(),
                    combinePersona(r.demandPersona(), r.demandSubPersona()), r.demandBand(), r.demandLocation(),
                    fmtNum(r.balancePositions()), r.dueCategory(), r.ageingBucket(),
                    r.skillCollapsed(), r.bandCollapsed(), r.locationCollapsed(), r.assessmentCollapsed(),
                    r.skillExpanded(), r.bandExpanded(), r.locationExpanded(), r.assessmentExpanded(), r.oneLiner(),
                    nz(r.masMappingNote()), nz(r.frontendAnchorNote()), nz(r.masMappingCrossCheckNote()),
                    nz(r.additionalRequest())
            };
            for (int c = 0; c < values.length; c++) {
                Cell dc = dataRow.createCell(c);
                setValue(dc, values[c]);
                if (c == 0) {
                    dc.setCellStyle(s.tierCell(tier));
                } else if ((c == COL_MAS_MAPPING_CHECK || c == COL_FRONTEND_ANCHOR_CHECK || c == COL_MAS_MAPPING_CROSS_CHECK)
                        && values[c] != null && !values[c].toString().isEmpty()) {
                    // Only has a value when actually flagged — bold amber, same treatment as the tier-discount text.
                    dc.setCellStyle(s.reviewFlagCell());
                } else {
                    // 0-indexed wrap columns: Skill/Location/Band/Location raw detail, Reasoning, Additional Request
                    // — mirrors Python's 1-indexed `c in (13, 17, 18, 19, 21, 25)`.
                    boolean wrap = (c == 12 || c == 16 || c == 17 || c == 18 || c == 20 || c == 24);
                    dc.setCellStyle(s.bodyCell("Excluded".equals(tier), wrap));
                }
            }
            row++;
        }
        return row;
    }

    private int writeDataIssueBlock(Sheet ws, Styles s, int row, SupplyEnriched emp) {
        Row headerRow = ws.createRow(row);
        Cell cell = headerRow.createCell(0);
        cell.setCellValue(emp.getEmployeeName() + "  (Emp Code " + emp.getEmployeeId() + ")  —  "
                + "Band: " + nz(emp.getSubBand()) + "  |  Location: " + nz(emp.getLocation())
                + "  |  Status: " + nz(emp.getAfdStatus())
                + "  |  Bench ageing: " + fmtNum(emp.getBenchAgeingDays()) + " days");
        cell.setCellStyle(s.empHeader);
        ws.addMergedRegion(new CellRangeAddress(row, row, 0, COLUMNS.length - 1));
        row++;

        Row dataRow = ws.createRow(row);
        Cell tierCell = dataRow.createCell(0);
        tierCell.setCellValue("DATA ISSUE");
        tierCell.setCellStyle(s.tierCell("DATA ISSUE"));
        for (int c = 1; c < COLUMNS.length - 1; c++) {
            Cell blank = dataRow.createCell(c);
            blank.setCellStyle(s.bodyCell(false, false));
        }
        Cell reasonCell = dataRow.createCell(COLUMNS.length - 1);
        reasonCell.setCellValue(nz(emp.getClassificationNote()));
        reasonCell.setCellStyle(s.bodyCell(false, true));
        row++;
        return row;
    }

    private void writeReadMeSheet(XSSFWorkbook wb, Styles s, List<SupplyEnriched> orderedEmployees,
                                  Map<Long, List<ShortlistRow>> byEmployee, int totalPhase1Supply,
                                  int unclassifiedCount, List<DemandEnriched> classifiedDemand) {
        Sheet rm = wb.createSheet("Read Me");
        rm.setColumnWidth(0, 100 * 256);
        int[] r = {0};

        addLine(rm, s.title, r, "Demand & Supply Mapping — Matching Engine: Methodology & Coverage Notes");
        r[0]++;
        addLine(rm, s.bold, r, "Scope (this export)");
        long employeesWithRows = byEmployee.size();
        addLine(rm, s.body, r, totalPhase1Supply + " Phase 1 bench employees on file; " + employeesWithRows
                + " classified into a Phase 1 persona with at least one candidate shortlist row, "
                + unclassifiedCount + " flagged as DATA ISSUE rows at the bottom of the Phase 1 Shortlist tab "
                + "(unclassified or outside the supported band ladder — see each row's Reasoning column for why).");
        r[0]++;

        addLine(rm, s.bold, r, "Overall Signal tiers");
        addLine(rm, s.body, r, "Strong — same persona (and sub-persona, for Frontend), core skills present, band exact, same city.");
        addLine(rm, s.body, r, "Good — persona match but exactly one thing discounted: one band above the ask, a different city, "
                + "or a named-accessory gap (demand asks for a named extra the employee's profile doesn't show).");
        addLine(rm, s.body, r, "Weak — a cross-persona discount, the framework-unconfirmed bucket, a weak Node.js-only MERN "
                + "signal, OR a persona match with two or more things discounted at once.");
        addLine(rm, s.body, r, "Excluded — band two-or-more levels off (either direction) or wrong sub-persona. Shown greyed "
                + "out, capped to the 3 closest near-misses per employee, for transparency — never blended into the ranked list.");
        r[0]++;

        addLine(rm, s.bold, r, "Per-employee row cap");
        addLine(rm, s.body, r, "All Strong rows kept; top 3 Good rows kept (by demand urgency, i.e. ageing rank then open "
                + "positions); Weak rows shown ONLY for an employee with zero Strong and zero Good matches (capped at 10 in "
                + "that case); the 3 closest near-miss Excluded rows kept per employee regardless. Strong and Good are both "
                + "\"propose freely\" tiers — Good is never hidden just because Strong exists.");
        r[0]++;

        long noStrongOrGood = orderedEmployees.stream()
                .filter(e -> byEmployee.get(e.getEmployeeId()).stream()
                        .noneMatch(row -> "Strong".equals(row.overallTier()) || "Good".equals(row.overallTier())))
                .count();
        addLine(rm, s.bold, r, "Employees with zero Strong and zero Good match, this export");
        addLine(rm, s.body, r, noStrongOrGood + " of " + employeesWithRows + " employees with at least one candidate row have "
                + "no Strong/Good match right now — flagged with a ⚠ NO STRONG/GOOD MATCH IN SCOPE banner in the main sheet.");
        r[0]++;

        addLine(rm, s.bold, r, "Classification approach");
        addLine(rm, s.body, r, "Supply's Skill Cluster is a closed, curated Phase 1 value set, classified via an exact-match "
                + "lookup table. Demand's Final Skill Cluster is open free text, classified via a token/rule parser "
                + "implementing the anchor/accessory/persona rules (framework implies language, named-vs-unnamed accessory "
                + "tiers, anchor precedence). Both sides resolve to the same shared persona label before matching — never "
                + "compared as raw text. No fuzzy matching: an unrecognized value is excluded and logged, never guessed.");
        r[0]++;

        addLine(rm, s.bold, r, "Reviewer flags (check-only — never filter or score)");
        addLine(rm, s.body, r, "MAS Mapping check: a demand row's own raw MAS Mapping vs. the persona the engine derived "
                + "for that same row. Frontend Anchor check: a frontend framework named alongside a bare Java/.NET anchor "
                + "with no backend-framework confirmation. MAS Mapping cross-check (added 2026-10-01): the demand's raw "
                + "MAS Mapping vs. the MATCHED employee's own raw MAS Mapping — two independently filled-in source labels "
                + "that can disagree with each other even when each individually agrees with its own side's derived "
                + "persona, a case the first two checks can't see since neither looks across the pairing. All three are "
                + "reviewer-facing only; none of them route or exclude a candidate.");
        r[0]++;

        long totalPos = classifiedDemand.stream().mapToLong(d -> d.getBalancePositions() == null ? 0 : d.getBalancePositions()).sum();
        addLine(rm, s.bold, r, "Demand classification coverage");
        addLine(rm, s.body, r, totalPos + " open positions across " + classifiedDemand.size()
                + " classified, active demand rows feed this export's matching pass. Demand rows with no recognized "
                + "persona generate no shortlist row for any employee — they are not force-fit.");
        r[0]++;

        addLine(rm, s.bold, r, "Known limitations / not yet resolved");
        for (String item : List.of(
                "Pan India location handling — not modeled; location is always scored same-city/different-city.",
                "Band-vs-location discount sizing (is one band above worse than a different city?) — both currently "
                        + "collapse to one 'discount' each in the Good tier; the relative weighting is unverified.",
                "Sub-bands outside the defined E1.1-E3.2 ladder show a 'Band ladder position unclear' note rather than "
                        + "being silently scored.",
                "Additional Request free text and Must-have/Nice-to-have tagging are not yet factored into matching."
        )) {
            addLine(rm, s.body, r, "  •  " + item);
        }
    }

    private void addLine(Sheet rm, CellStyle style, int[] r, String text) {
        Row row = rm.createRow(r[0]);
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        cell.setCellStyle(style);
        r[0]++;
    }

    /**
     * Cell styles, built once per workbook and reused (POI penalizes creating many distinct XSSFCellStyle instances).
     * Colors/fonts mirror write_shortlist_workbook.py's module-level constants.
     */
    private static final class Styles {
        final CellStyle title;
        final CellStyle subtitle;
        final CellStyle header;
        final CellStyle empHeader;
        final CellStyle empHeaderNoMatch;
        final CellStyle body;
        final CellStyle bold;
        private final Map<String, CellStyle> tierStyles = new HashMap<>();
        private final CellStyle bodyPlain;
        private final CellStyle bodyPlainWrap;
        private final CellStyle bodyExcluded;
        private final CellStyle bodyExcludedWrap;
        private final CellStyle reviewFlag;

        Styles(XSSFWorkbook wb) {
            Font titleFont = font(wb, 14, true, false, "1F4E78");
            title = base(wb);
            title.setFont(titleFont);

            Font subtitleFont = font(wb, 10, false, true, "595959");
            subtitle = base(wb);
            subtitle.setFont(subtitleFont);
            subtitle.setWrapText(true);

            Font headerFont = font(wb, 10, true, false, "FFFFFF");
            header = base(wb);
            header.setFont(headerFont);
            fill(header, "1F4E78");
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);

            Font empHeaderFont = font(wb, 11, true, false, "1F4E78");
            empHeader = base(wb);
            empHeader.setFont(empHeaderFont);
            fill(empHeader, "D9E1F2");
            empHeader.setVerticalAlignment(VerticalAlignment.CENTER);
            empHeader.setIndention((short) 1);

            empHeaderNoMatch = base(wb);
            empHeaderNoMatch.setFont(empHeaderFont);
            fill(empHeaderNoMatch, "FFD966");
            empHeaderNoMatch.setVerticalAlignment(VerticalAlignment.CENTER);
            empHeaderNoMatch.setIndention((short) 1);

            body = base(wb);
            body.setFont(font(wb, 10, false, false, "000000"));
            body.setWrapText(true);

            bold = base(wb);
            bold.setFont(font(wb, 10, true, false, "000000"));

            Font bodyFont = font(wb, 10, false, false, "000000");
            Font excludedFont = font(wb, 9, false, true, "808080");

            bodyPlain = bodyStyle(wb, bodyFont, false);
            bodyPlainWrap = bodyStyle(wb, bodyFont, true);
            bodyExcluded = bodyStyle(wb, excludedFont, false);
            bodyExcludedWrap = bodyStyle(wb, excludedFont, true);
            // MAS Mapping check / Frontend Anchor check — bold amber when actually flagged, mirrors Python's
            // `Font(name=FONT_NAME, size=10, bold=True, color='9C6500')` (same amber as the "Good" tier).
            reviewFlag = bodyStyle(wb, font(wb, 10, true, false, "9C6500"), true);

            String[][] tiers = {
                    {"Strong", "C6EFCE", "006100", "false"}, {"Good", "FFEB9C", "9C6500", "false"},
                    {"Weak", "FCE4D6", "974706", "false"}, {"Excluded", "D9D9D9", "7F7F7F", "true"},
                    {"DATA ISSUE", "FF7C80", "FFFFFF", "false"}
            };
            for (String[] t : tiers) {
                CellStyle style = base(wb);
                fill(style, t[1]);
                Font f = font(wb, 10, true, Boolean.parseBoolean(t[3]), t[2]);
                style.setFont(f);
                style.setAlignment(HorizontalAlignment.CENTER);
                style.setVerticalAlignment(VerticalAlignment.CENTER);
                tierStyles.put(t[0], style);
            }
        }

        CellStyle tierCell(String tier) {
            return tierStyles.getOrDefault(tier, bodyPlain);
        }

        CellStyle bodyCell(boolean excludedRow, boolean wrap) {
            if (excludedRow) {
                return wrap ? bodyExcludedWrap : bodyExcluded;
            }
            return wrap ? bodyPlainWrap : bodyPlain;
        }

        CellStyle reviewFlagCell() {
            return reviewFlag;
        }

        private CellStyle bodyStyle(XSSFWorkbook wb, Font f, boolean wrap) {
            CellStyle style = base(wb);
            style.setFont(f);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            style.setWrapText(wrap);
            return style;
        }

        private CellStyle base(XSSFWorkbook wb) {
            org.apache.poi.xssf.usermodel.XSSFCellStyle style = wb.createCellStyle();
            BorderStyle thin = BorderStyle.THIN;
            style.setBorderTop(thin);
            style.setBorderBottom(thin);
            style.setBorderLeft(thin);
            style.setBorderRight(thin);
            XSSFColor borderColor = new XSSFColor(Color.decode("#BFBFBF"), null);
            style.setTopBorderColor(borderColor);
            style.setBottomBorderColor(borderColor);
            style.setLeftBorderColor(borderColor);
            style.setRightBorderColor(borderColor);
            return style;
        }

        private void fill(CellStyle style, String hex) {
            if (style instanceof org.apache.poi.xssf.usermodel.XSSFCellStyle xssfStyle) {
                xssfStyle.setFillForegroundColor(new XSSFColor(Color.decode("#" + hex), null));
                xssfStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
        }

        private Font font(XSSFWorkbook wb, int size, boolean bold, boolean italic, String hex) {
            Font f = wb.createFont();
            f.setFontName(FONT_NAME);
            f.setFontHeightInPoints((short) size);
            f.setBold(bold);
            f.setItalic(italic);
            if (f instanceof org.apache.poi.xssf.usermodel.XSSFFont xssfFont) {
                xssfFont.setColor(new XSSFColor(Color.decode("#" + hex), null));
            }
            return f;
        }
    }
}
