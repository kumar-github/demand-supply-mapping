-- Adds the columns needed for a faithful port of build_matches.py's
-- assessment_signal() and cap_employee_rows() (the per-employee row cap:
-- all Strong kept, top-3 Good by urgency, Weak only as fallback, top-3
-- Excluded near-misses), ahead of building MatchingRunService.
--
-- supply_enriched:
--   rating  (raw 'Rating' column — one of a small fixed set of literal
--            strings: 'Expert'/'Proficient'/'Competent'/'Advanced
--            Beginner'/'Beginner'/'Entry'/'0'/'0% Score', the last two
--            both meaning "not yet assessed")
--   score   (raw 'Score' column — numeric fraction shown alongside Rating,
--            not itself used for ranking)
--
-- demand_enriched:
--   balance_positions  (raw 'Balance Positions' column — open position
--                        count, used as a tie-break when ranking rows)
--   due_category       (raw 'Due Category_New' column — a small fixed set
--                        of date-bucket strings that AGEING_RANK maps to a
--                        numeric urgency rank)
--
-- Additive only, same as V2 — existing rows get these as NULL until the
-- next refresh backfills them.

ALTER TABLE supply_enriched
    ADD COLUMN rating TEXT;
ALTER TABLE supply_enriched
    ADD COLUMN score DOUBLE PRECISION;

ALTER TABLE demand_enriched
    ADD COLUMN balance_positions INTEGER;
ALTER TABLE demand_enriched
    ADD COLUMN due_category TEXT;
