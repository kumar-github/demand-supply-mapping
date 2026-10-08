package dev.badprogrammer.demandsupplymapping.export;

/**
 * One employee x demand shortlist row, carrying every field write_shortlist_workbook.py's write_employee_block() needs
 * — display fields plus the full collapsed/expanded text for all four signals (skill/band/location/assessment). This is
 * deliberately NOT MatchRow (matching.MatchRow only carries what capEmployeeRows() needs to sort/cap, by design — see
 * that record's own Javadoc) and NOT MatchCandidate (which persists only each signal's quality code, not its display
 * text — see MatchingRunService). Built fresh at export time from a live matching pass, same candidate scope and cap as
 * a matching run, so this always reflects current supply_enriched/demand_enriched data, not a possibly-stale
 * match_candidates table from an earlier run.
 */
public record ShortlistRow(
        Long employeeId,
        String employeeName,
        String employeePersona,
        String employeeSubPersona,
        String employeeCompleteness,
        String employeeBand,        // Sub Band, not coarse Band — see build_matches.py's employee_band=emp['Sub Band']
        String employeeLocation,
        String employeePrimeNv,
        String employeeAfdStatus,
        Integer employeeBenchAgeing,
        // Added 2026-10-03 for the demand-centric export's per-demand candidate ranking (capDemandRows in
        // ShortlistWorkbookService) — MatchingService.assessmentSignal()'s AssessmentResult.rank(), higher is better
        // (Expert=6 ... Entry=1, 0 = not yet assessed). Not itself a sheet column; the employee-centric sheet never
        // needed this as a standalone field because its cap (capEmployeeRows) sorts by demand urgency, not assessment.
        int employeeAssessmentRank,

        String demandId,            // "Job Req ID" column — demand_id IS the Job Requisition ID (see V1 schema)
        String customer,
        String project,
        String demandPersona,
        String demandSubPersona,
        String demandBand,
        String demandLocation,
        Integer balancePositions,
        String dueCategory,
        String ageingBucket,        // "New-Ageing" raw column — display text, distinct from dueCategory's sort rank

        String overallTier,         // Strong | Good | Weak | Excluded
        int ageingRank,
        String skillQuality,
        String skillCollapsed,
        String skillExpanded,
        String bandCollapsed,
        String bandExpanded,
        String locationCollapsed,
        String locationExpanded,
        String assessmentCollapsed,
        String assessmentExpanded,
        String oneLiner,

        // Display-only reviewer flags (task #19 re-port, 2026-09-30) — never used to filter/score, only shown for
        // manual review, mirroring write_shortlist_workbook.py's mas_mapping_note/frontend_anchor_note/
        // additional_request columns. null/blank means "nothing to flag", same as the Python source's None.
        String masMappingNote,
        String frontendAnchorNote,
        // Added 2026-10-01 — MatchingService.masMappingCrossCheckNote(), the pairing-level
        // cross-check (employee's own raw MAS Mapping vs. the matched demand's raw MAS Mapping),
        // distinct from masMappingNote above (which only ever looks at the demand side). Same
        // display-only, check-never-filters treatment as its two siblings.
        String masMappingCrossCheckNote,
        String additionalRequest
) {
}
