-- Seeds a single placeholder reviewer so demand_candidate_decisions.decided_by and
-- decision_history.changed_by (NOT NULL) have a real users.user_id to point at.
--
-- This app has no login/auth yet (see demand-supply-mapping-requirements.md — Phase 1 scope has
-- no identity system), so the Review feature being added now (demand-side Propose/Reject) cannot
-- yet attribute a decision to a real, distinct person. Rather than block the feature on building
-- auth, or leaving changed_by/decided_by pointed at a made-up ID that doesn't exist, this seeds one
-- real row and DemandReviewService resolves every decision/history write to it
-- (DemandReviewService.CURRENT_USER_EMAIL). When real auth is added, that constant is replaced by
-- the signed-in user's own users row — no schema change needed, since users/decided_by/changed_by
-- were already designed for a real multi-user identity (full_db_design.sql, section 0).
INSERT INTO users (display_name, email, role, is_active)
VALUES ('TAG Reviewer (shared)', 'tag-reviewer@bench-match.local', 'reviewer', TRUE);
