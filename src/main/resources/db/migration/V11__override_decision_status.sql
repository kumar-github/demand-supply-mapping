-- PERSISTENT schema change. Adds the "Override" decision action from
-- demand-supply-mapping-requirements.md's "Review interaction model":
-- a third decision, distinct from Propose ('approved')/Reject ('rejected'),
-- for the one-band-below "below policy" tier — "kept separate from a normal
-- Propose... so it's never mistaken for an ordinary approval."
--
-- Two CHECK constraints widened, both using Postgres's default
-- <table>_<column>_check naming (neither was given an explicit name in
-- V1__init_schema.sql):
--   1. demand_candidate_decisions.status — add 'overridden'.
--   2. demand_candidate_decisions.engine_tier_at_decision — add 'Excluded',
--      since an overridden candidate's engine tier at decision time is
--      'Excluded' (that's the whole point of Override: acting on a
--      candidate the engine itself placed outside Strong/Good/Weak).
--   3. decision_history.new_status / old_status — same 'overridden' addition,
--      so the audit trail can record the transition.

ALTER TABLE demand_candidate_decisions
DROP
CONSTRAINT demand_candidate_decisions_status_check,
    ADD CONSTRAINT demand_candidate_decisions_status_check
        CHECK (status IN ('pending', 'approved', 'rejected', 'staffed', 'overridden'));

ALTER TABLE demand_candidate_decisions
DROP
CONSTRAINT demand_candidate_decisions_engine_tier_at_decision_check,
    ADD CONSTRAINT demand_candidate_decisions_engine_tier_at_decision_check
        CHECK (engine_tier_at_decision IN ('Strong', 'Good', 'Weak', 'Excluded'));

ALTER TABLE decision_history
DROP
CONSTRAINT decision_history_old_status_check,
    ADD CONSTRAINT decision_history_old_status_check
        CHECK (old_status IN ('pending', 'approved', 'rejected', 'staffed', 'overridden'));

ALTER TABLE decision_history
DROP
CONSTRAINT decision_history_new_status_check,
    ADD CONSTRAINT decision_history_new_status_check
        CHECK (new_status IN ('pending', 'approved', 'rejected', 'staffed', 'overridden'));
