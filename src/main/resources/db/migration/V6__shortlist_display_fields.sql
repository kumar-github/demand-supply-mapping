-- Shortlist-workbook display fields (2026-09-30, task #19), found while
-- porting write_shortlist_workbook.py/build_matches.py to Java. Four raw
-- source columns are shown on the shortlist workbook's employee header line
-- and per-row detail but were never ingested into the schema:
--
--   - demand_enriched.customer       (raw 'Customer' column)
--   - demand_enriched.project_name   (raw 'Project Name' column)
--   - demand_enriched.new_ageing     (raw 'New-Ageing' column — the display
--     "Ageing Bucket" text shown on each row; distinct from due_category
--     above, which is what MatchingService.ageingRank() sorts by. The two
--     are read from different raw columns in build_matches.py — due_category
--     is Due Category_New, this is New-Ageing.)
--   - supply_enriched.prime_nv       (raw 'Prime/NV' column)
--
-- A second, related gap found in the same pass: supply_enriched has no
-- equivalent of demand_enriched.classification_note (added in V4).
-- ClassificationResult.status() — the human-readable "why is this
-- unclassified/DATA ISSUE" reason — was already being computed by
-- RefreshLogic.classifySupply() but silently dropped in
-- RefreshService.applyClassification(SupplyEnriched, ...). Demand had this
-- exact bug and got it fixed in V4 (there it was correctness-affecting, for
-- skillSignal()'s weak-MERN detection); on the supply side it's needed for
-- the shortlist workbook's "DATA ISSUE" trailer block, which shows each
-- unclassified employee's reason text.
--
--   - supply_enriched.classification_note
--
-- customer/project_name/new_ageing/prime_nv are plain descriptive
-- pass-through text, upserted every refresh regardless of source_row_hash
-- (same as location/band/etc. — see RefreshService's class Javadoc).
-- classification_note is classification OUTPUT, not raw source text, so it
-- follows the same hash-gated recompute as persona/completeness/etc. —
-- same treatment demand_enriched.classification_note already gets. Additive
-- only, same pattern as V2/V3/V4/V5; existing rows default to NULL and
-- backfill on the next refresh either way.

ALTER TABLE demand_enriched
    ADD COLUMN customer TEXT,
    ADD COLUMN project_name TEXT,
    ADD COLUMN new_ageing   TEXT;

ALTER TABLE supply_enriched
    ADD COLUMN prime_nv TEXT,
    ADD COLUMN classification_note TEXT;
