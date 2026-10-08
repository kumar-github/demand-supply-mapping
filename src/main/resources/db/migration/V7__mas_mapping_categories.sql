-- MAS Mapping scope table (2026-10-01) — replaces the old hardcoded
-- `Engine.PHASE1_MAS_MAPPING = List.of("Full Stack", "Front End")` literal with a real, editable
-- data source. Decision background: the "MAS Mapping" column (both AFD-Supply.xlsx and
-- Demand.xlsx carry it) names which broad technical/functional category a row belongs to — Full
-- Stack, Front End, Tpm (Technical Project Management), Testing, Polyglot, Devops & Sre,
-- Integration, Mainframe, Architects & Emerging. Supply already filtered to Full Stack/Front End
-- only (filterPhase1Supply(), via this same constant); demand had NO such gate at all — every MAS
-- Mapping value flowed straight into skill-cluster-text classification. That surfaced as a real
-- bug on 2026-10-01: 32 "Java,Microservices,React.js" demand rows tagged MAS Mapping='Tpm' were
-- being classified Fullstack Java purely from skill text, even though Tpm is a project-management
-- role, not an engineering one — a category mismatch, not a skill-confidence question.
--
-- Decision: only Full Stack and Front End are active for Phase 1; everything else is deferred to
-- Phase 2 and gated out of active matching on BOTH sides (demand gets the new gate in
-- RefreshLogic.classifyDemand(); supply's existing gate in filterPhase1Supply() now reads from
-- this table too, via Engine.setPhase1MasMapping() — see RefreshService).
--
-- IMPORTANT — activating a category here is necessary but NOT sufficient. Flipping a row's status
-- to 'active' only stops the gate from excluding that category's rows; it does NOT create a
-- persona, matching rules, or an eligible supply pool for it. Activating 'Tpm' without first
-- building those would just reopen the exact bug this table exists to prevent (its rows would be
-- forced into whichever of Fullstack Java/Fullstack .NET/Frontend the skill-cluster text happens
-- to resemble). Read demand-supply-mapping-requirements.md's "MAS Mapping scoping" section before
-- flipping anything.
--
-- KEEP THIS SEED DATA AND THE PYTHON CLI'S mas_mapping_categories.json IDENTICAL. This project
-- runs two parallel implementations (this web app and bench-match-cli) during a
-- build -> parallel-run -> sunset period; the CLI has no database, so there is no automatic sync
-- between this table and that file — whoever changes one must change the other by hand. This is
-- the same category of mistake the AFD Status case-sensitivity fix (2026-09-30) almost fell into
-- (fixed in Python, nearly forgotten in Java) — don't repeat it here.
--
-- status is CHECK-constrained rather than left as free text, consistent with this schema's existing
-- practice (see classification_status, skill_signal, etc. in V1/the original design notes) — cheap
-- insurance against a typo silently leaving a category permanently (in)active.

CREATE TABLE mas_mapping_categories
(
    category TEXT PRIMARY KEY,
    status   TEXT NOT NULL CHECK (status IN ('active', 'inactive')),
    note     TEXT
);

INSERT INTO mas_mapping_categories (category, status, note)
VALUES ('Full Stack', 'active', NULL),
       ('Front End', 'active', NULL),
       ('Tpm', 'inactive',
        'Technical Project Management -- not an engineering role; not a skill-confidence question like the categories below'),
       ('Testing', 'inactive', NULL),
       ('Polyglot', 'inactive', NULL),
       ('Devops & Sre', 'inactive', NULL),
       ('Integration', 'inactive', NULL),
       ('Mainframe', 'inactive', NULL),
       ('Architects & Emerging', 'inactive', NULL);
