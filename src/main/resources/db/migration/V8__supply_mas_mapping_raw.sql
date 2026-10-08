-- Supply-side MAS Mapping raw value (2026-10-01) — closes a real, pre-existing gap found while
-- porting the Python CLI's mas_mapping_cross_check (build_matches.py, 2026-10-01) to this app.
--
-- demand_enriched has carried mas_mapping_raw since V1 (see its own comment there). supply_enriched
-- never gained the equivalent column — supply's own "MAS Mapping" Excel value was read only far
-- enough to drive the Phase 1 scope filter (filterPhase1Supply(), via Engine.PHASE1_MAS_MAPPING /
-- the V7 scope table) and was then discarded; nothing stored it for later use.
--
-- That was fine as long as nothing needed supply's MAS Mapping value after classification. It is
-- needed now: the cross-check compares the demand's raw MAS Mapping directly against the MATCHED
-- employee's own raw MAS Mapping (not the engine-derived persona on either side) — two
-- independently filled-in source labels (one from the demand team, one from HR/the bench team)
-- that can disagree with each other even when each individually agrees with its own side's
-- derived persona. See MatchingService.masMappingCrossCheck() for the comparison logic itself,
-- ported directly from build_matches.py's mas_mapping_cross_check().
--
-- Additive only, same as V2-V7. Existing rows default to NULL (not FALSE/empty-string — NULL
-- correctly means "not yet known," matching the Python side's pd.isna() handling) and backfill on
-- the next refresh once source_row_hash is nulled, same as every other classification/raw column.

ALTER TABLE supply_enriched
    ADD COLUMN mas_mapping_raw TEXT; -- 'MAS Mapping' column, kept verbatim, NEVER used to filter/match beyond the existing Phase 1 scope gate
