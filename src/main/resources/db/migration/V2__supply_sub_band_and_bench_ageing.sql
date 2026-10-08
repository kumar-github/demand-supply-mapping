-- Adds two columns to supply_enriched that V1 was missing:
--
-- 1. sub_band (fine-grained: 'E1.1'..'E3.2', raw 'Sub Band' column).
--    supply_enriched previously only had the coarse `band` (E1/E2/E3).
--    Sub Band is what the band-ladder data-quality override actually
--    checks, and what MatchingService.bandSignal() needs to score
--    Strong/Good/Weak band match quality — without it, band scoring has
--    nothing to read once matching is wired to this table instead of a
--    CSV. RefreshService was already computing/hashing it correctly in
--    memory; it just had nowhere to persist it.
--
-- 2. bench_ageing_days (raw 'Bench Ageing days' column, a plain day
--    count). The source file also has a bucketed 'Duration' column
--    ('0-2 Weeks', etc.); the numeric one was chosen since a bucket is
--    trivially derivable from it and not the reverse.
--
-- Additive only — existing rows get sub_band/bench_ageing_days = NULL
-- until the next refresh backfills them (RefreshService always upserts
-- these on every run, regardless of source_row_hash — see RefreshService's
-- class Javadoc). No data loss, no rewrite of existing rows required.
--
-- demand_enriched needs NO equivalent migration: its existing `band`
-- column already stores the fine-grained value (the raw Demand Sub Band
-- Name), unlike supply_enriched's `band` — see the comment on that column
-- in full_db_design.sql.

ALTER TABLE supply_enriched
    ADD COLUMN sub_band TEXT;
ALTER TABLE supply_enriched
    ADD COLUMN bench_ageing_days INTEGER;
