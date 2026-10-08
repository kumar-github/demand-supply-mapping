-- PERSISTENT schema change. Un-conflates demand_review_state's two distinct "this demand needs a
-- human to look again" signals, which 2026-10-06's Review queue work had collapsed onto one
-- column (needs_reattention/reattention_reason):
--
--   1. needs_reattention/reattention_reason — the ORIGINAL, still-unimplemented purpose from
--      V1__init_schema.sql: a refresh job detecting the source Excel row changed under an open
--      review, so the reviewer's existing decision may now be stale. Automated, refresh-triggered.
--      Left exactly as V1 defined it — still unused by any Java code, reserved for that future
--      RefreshService work (see that class's "Known gaps" comment).
--   2. flagged_for_hiring/flagged_for_hiring_reason/flagged_for_hiring_by/flagged_for_hiring_at —
--      NEW. The Review queue's "Flag for hiring" action on an EXHAUSTED demand: a manual,
--      reviewer-triggered signal that every Strong/Good candidate has been decided but positions
--      remain open, so HR should open a requisition. Nothing to do with source data changing.
--
-- These are genuinely different events with different triggers and different meanings to a
-- reviewer; sharing one column risked a future RefreshService write silently stepping on a
-- deliberate "flagged for hiring" state (or vice versa), and already made the one column's
-- reattention_reason text do double duty as two unrelated messages. Splitting them now, before
-- RefreshService's side is ever implemented, costs nothing and removes that risk entirely.

ALTER TABLE demand_review_state
    ADD COLUMN flagged_for_hiring BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN flagged_for_hiring_reason TEXT,
    ADD COLUMN flagged_for_hiring_by     INTEGER REFERENCES users (user_id),
    ADD COLUMN flagged_for_hiring_at     TIMESTAMPTZ;

-- One-time backfill: any row that was flagged for hiring under the old, shared column (i.e. every
-- real use of needs_reattention to date, since RefreshService never wrote to it) moves across to
-- the new column, then the old one is cleared back to its original, unused default — so it's
-- truly free for RefreshService's future use, not left holding stale "flagged for hiring" data
-- under the wrong name.
UPDATE demand_review_state
SET flagged_for_hiring        = TRUE,
    flagged_for_hiring_reason = reattention_reason,
    flagged_for_hiring_at     = now()
WHERE needs_reattention = TRUE;

UPDATE demand_review_state
SET needs_reattention  = FALSE,
    reattention_reason = NULL
WHERE flagged_for_hiring = TRUE;
