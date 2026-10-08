-- =====================================================================
-- Bench Match — Complete Database Design (skill-matching + workflow scope)
-- =====================================================================
--
-- This is the full design arrived at through discussion, not a partial
-- or "just skill matching" version like the earlier schema. It covers:
--   1. Reference/rules tables       — how we classify skills (admin-owned)
--   2. Supply side                  — bench employees
--   3. Demand side                  — client/internal requisitions
--   4. Matching engine output       — the actual candidate proposals
--   5. Reviewer workflow            — decisions, history, identity
--   6. Refresh/import audit         — traceability of periodic Excel loads
--
-- CORE PRINCIPLE running through this whole design (this is the part
-- that matters more than any single column): every table is either
--   (a) RE-DERIVABLE  — fully rebuilt from the source Excel on every
--       refresh, safe to overwrite wholesale, never holds anything a
--       human typed in, or
--   (b) PERSISTENT    — written only by a human/reviewer action, a
--       refresh job must NEVER write to these tables.
-- Each table below is labelled with which one it is. This split is what
-- lets us treat the source Excel exports as an external, not-owned,
-- periodically-refreshed feed (an anti-corruption layer) without ever
-- losing reviewer work when a new export lands.
--
-- Scope note: band/location matching logic and full scoring/tiering
-- math still live in the Python engine (build_matches.py), not SQL —
-- this schema stores the inputs and outputs of that logic, it doesn't
-- reimplement it.
--
-- Design already reflects decisions made but not yet implemented in
-- the Python engine (cross-persona matching removed; REST/RESTful API
-- no longer tracked as a separate named accessory; MAS Mapping is a
-- validation flag, not a filter; Additional Request is a surfaced,
-- never-auto-parsed field). This file is a design deliverable only —
-- engine.py / build_matches.py / classify_inputs.py are unchanged.
--
-- Revision note (Tasks #5-#7, added after the CLI build):
--   - Task #5 (MEAN sub-persona) needs no schema change — it's just a
--     new row value in sub_personas, same as any other sub-persona.
--   - Task #6 (Spring Boot / Web API-MVC-Entity Framework must be
--     confirmed, not assumed, before a Java/.NET match is scored full
--     strength) DOES need a schema change: a new framework_confirmed
--     column, added below to skill_cluster_rules, supply_enriched, and
--     demand_enriched. NULL means "not applicable" (any persona other
--     than Fullstack Java/.NET), not a penalty.
--   - Task #7 (supply/demand share one accessory vocabulary via a
--     canonical registry) needs no schema change — named_accessories
--     already stores resolved display names (e.g. 'Cloud'), and the
--     canonical grouping itself is engine.py logic (ACCESSORY_CANONICAL),
--     the same status as the rest of the classification rules that
--     skill_tokens/skill_cluster_rules mirror rather than store directly.
--   - Task #8 (skill-weighting: Critical/Important/Minor severity per
--     accessory, replacing the old flat one-discount-for-any-gap rule, and
--     folding Task #6's framework check and Frontend's framework-
--     unconfirmed bucket into the same severity concept) DOES need a
--     schema change: a new accessory_weights table, added below, right
--     after skill_tokens. match_candidates.skill_signal's possible values
--     also change (see that column's comment).
--   - Task #9 (MEAN sub-persona wiring, dead-constant cleanup, explicit
--     Kotlin weight) needs no schema change — all three are Python-logic
--     fixes with no new data to store.
--   - Task #10 (Messaging/Kafka/JMS/RabbitMQ handling; canonical accessory
--     vocabulary and persona-specific weight overrides as real DB columns)
--     needs schema changes: skill_tokens.canonical_accessory and
--     accessory_weights.persona_id, both added below — see the comments at
--     each column for detail. JMS/RabbitMQ themselves need no schema
--     change — they're just new accessory_weights seed rows, same shape
--     as any other accessory.
--   - Task #11 (E0/E2.3 and any other supply-side Sub Band outside the
--     E1.1-E3.2 ladder are now a visible, flagged data-quality issue)
--     needs no schema change — supply_enriched.classification_status
--     already has a 'data_issue' value for exactly this purpose; the fix
--     is entirely in classify_inputs.py deciding when to set it.
--   - Task #12 (Sub-Capability checked against the engine-derived persona
--     as a reviewer flag, mirroring MAS Mapping's existing check — NOT
--     used as a scoping filter; checked against real data first and doing
--     so would have dropped 17 genuine Full Stack/Front End employees)
--     DOES need a schema change: sub_capability_raw and
--     sub_capability_mismatch_flag added to supply_enriched below, mirroring
--     demand_enriched's mas_mapping_raw/mas_mapping_mismatch_flag pair.
--   - Task #14 (demand-side Sub Band values outside the E1.1-E3.2 ladder —
--     Task #11's fix, extended to the demand side) needs no schema change.
--     demand_enriched.classification_status already has a 'data_issue'
--     value for exactly this purpose, same as Task #11 on the supply side —
--     the fix is entirely in classify_inputs.py deciding when to set it.
--   - Task #15 (React/Angular now tracked as a named accessory when they
--     co-occur with Java/.NET, the same as Cloud/DevOps/Docker/etc. —
--     previously silently dropped entirely) needs no schema change. It's
--     two new seed rows in accessory_weights (React=Minor, Angular=Minor)
--     and two new entries in skill_tokens.canonical_accessory, not a
--     structural change — both tables already exist for exactly this kind
--     of addition (Task #10).
--
-- Revision note (2026-09-27, pre-Phase-2 design review — NOT yet run
-- against a real database; this schema has been verified only by
-- inspection against engine.py, never actually created, loaded, or
-- queried. Doing that is a recommended first Phase 2 step before any
-- web app work depends on it):
--   - Dropped demand_source_snapshots entirely (was section 6, "OPTIONAL").
--     It was already marked speculative — nothing in current scope needs
--     "what did this demand say last Tuesday" — so rather than carry an
--     unused table from day one, it's removed. Add it back if/when
--     point-in-time demand history becomes an actual requirement; nothing
--     else in this design depends on it existing.
--   - supply_enriched gains is_active/inactive_since, mirroring the
--     pattern demand_enriched already had. This was a real gap, not a
--     style inconsistency: supply_overrides, demand_candidate_decisions,
--     and match_candidates all FK to supply_enriched.employee_id, and the
--     original design said supply_enriched is "fully truncated/upserted"
--     on every refresh with no soft-delete path — meaning an employee
--     dropping off the next AFD-Supply export (staffed, left bench, etc.)
--     could orphan or cascade-delete review history and match rows that
--     depend on that employee_id still existing. Fixed the same way
--     demand already handles it: never hard-delete, flip is_active=FALSE
--     when an employee_id stops appearing in a refresh.
--   - accessory_weights' UNIQUE (accessory_name, persona_id) didn't
--     actually protect "one generic default per accessory": standard SQL
--     treats NULL <> NULL for uniqueness purposes, so multiple
--     (accessory_name, persona_id=NULL) rows could be inserted without
--     violating it. Replaced with a real UNIQUE constraint for the
--     non-NULL case plus a partial unique index for the NULL/generic case.
--   - match_candidates.run_id changed to NOT NULL — every row here is
--     produced by a run, and a nullable run_id silently weakened the
--     table's own UNIQUE (employee_id, demand_id, run_id) constraint the
--     same way accessory_weights' did.
--   - Added CHECK constraints on every small-fixed-value-set free-text
--     status/signal column that didn't already have one
--     (supply_enriched.classification_status, demand_enriched.
--     classification_status, match_candidates.skill_signal/band_signal/
--     location_signal, demand_review_state.status,
--     demand_candidate_decisions.status) — cheap now, and the
--     alternative is a typo silently breaking a UI filter later with no
--     error at insert time.
--   - decision_history.changed_by is now NOT NULL — a nullable actor on
--     the one table that exists specifically to answer "who did this"
--     undercut its own purpose. If a genuinely system-driven status
--     change is ever needed (an automated close, say), model it as a
--     real system user row rather than a NULL actor.
--   - RESOLVED (same day): the open question above — does the engine get
--     refactored to read from skill_cluster_rules/skill_tokens/
--     accessory_weights, or do they stay documentation-only — is decided.
--     Neither. engine.py/build_matches.py/classify_inputs.py stay exactly
--     as they are: they remain the interim delivery mechanism (Excel
--     shortlists, run by hand, no DB dependency) for as long as the web
--     app isn't ready, and they are NOT touched to read from this schema.
--     The web app (Phase 2) is a SEPARATE implementation that reads and
--     writes these DB tables directly — it does not call into or wrap the
--     Python code. The Python code's role going forward is as a reference
--     for the web app's own re-implementation of the same classification/
--     matching logic, not a dependency of it.
--     This makes skill_cluster_rules/skill_tokens/accessory_weights the
--     REAL, live source of truth for the web app once it exists — not
--     documentation-only — but only for the web app's own logic, which is
--     a distinct codebase from engine.py.
--
--     Retire-vs-parallel plan (resolved same day): (1) BUILD — web app v1
--     is implemented directly from the current Python code as spec, same
--     rules/scoring/severity, reading/writing this schema instead of
--     Excel. (2) PARALLEL-RUN — for the first several releases, web app
--     output is checked against Python output on the same input data
--     (same Strong/Good/Weak/Excluded counts, same per-pair reasoning);
--     Python keeps running during this window, both as the comparison
--     baseline and as the still-live way the team gets shortlists.
--     (3) SUNSET — once no logic gap remains between the two, Python is
--     retired; every fix from then on happens only in the web app, so
--     drift is a bounded, explicitly-managed risk during step 2, not a
--     permanent state. Exact comparison methodology (which releases,
--     what counts as "confident," who signs off) is Phase 2 planning
--     work, not decided here.
-- =====================================================================


-- =====================================================================
-- 0. IDENTITY
-- =====================================================================
-- PERSISTENT. Needed as soon as any table wants a real "who did this"
-- reference instead of a free-text name. Kept deliberately small —
-- this is not a full auth system, just enough to FK against.

CREATE TABLE users
(
    user_id      SERIAL PRIMARY KEY,
    display_name TEXT        NOT NULL,
    email        TEXT UNIQUE,
    role         TEXT        NOT NULL DEFAULT 'reviewer', -- reviewer / admin / etc.
    is_active    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- =====================================================================
-- 1. REFERENCE / RULES  (skill-cluster classification)
-- =====================================================================
-- PERSISTENT, but admin-maintained rather than reviewer-maintained —
-- a refresh of the AFD-Supply/Demand Excel never touches these. They
-- change only when the business's skill taxonomy itself changes.
--
-- STATUS: two-track by design, decided 2026-09-27 (see revision note at
-- top of file). skill_cluster_rules, skill_tokens, and accessory_weights
-- mirror engine.py's Python dicts (SUPPLY_TABLE, the token vocabularies,
-- ACCESSORY_WEIGHT) as a reference — the interim Python tool (engine.py /
-- build_matches.py / classify_inputs.py) keeps running unchanged, reading
-- none of this schema, for as long as it's needed to hand the team Excel
-- shortlists. The web app is a SEPARATE implementation built to read and
-- write these tables directly, using engine.py as its spec. So: editing a
-- row here changes nothing about the Python tool's output (it never reads
-- this schema), but IS the real, live source of truth once the web app's
-- own logic is built against it. Keeping engine.py's fixes and these
-- tables in sync over time is a manual responsibility of whoever
-- maintains each side — nothing in this design makes it automatic.

CREATE TABLE personas
(
    persona_id SERIAL PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE -- e.g. 'Fullstack Java', 'Frontend'
);

CREATE TABLE sub_personas
(
    sub_persona_id SERIAL PRIMARY KEY,
    persona_id     INTEGER NOT NULL REFERENCES personas (persona_id),
    name           TEXT    NOT NULL, -- e.g. 'React', 'Angular', 'iOS'
    UNIQUE (persona_id, name)
);

-- Exact-match lookup used on the SUPPLY side: a literal skill-cluster
-- string maps to exactly one persona/sub-persona/anchor/accessory set.
-- One row per known supply Skill Cluster value (mirrors engine.py's
-- SUPPLY_TABLE dict).
CREATE TABLE skill_cluster_rules
(
    rule_id             SERIAL PRIMARY KEY,
    skill_cluster_text  TEXT    NOT NULL UNIQUE,      -- raw literal text, e.g. 'Java,React.js,Spring Boot'
    persona_id          INTEGER NOT NULL REFERENCES personas (persona_id),
    sub_persona_id      INTEGER REFERENCES sub_personas (sub_persona_id),
    anchor_skill        TEXT    NOT NULL,
    named_accessories   TEXT[] NOT NULL DEFAULT '{}', -- REST intentionally never appears here (implied by Spring/Spring Boot)
    framework_confirmed BOOLEAN                       -- Task #6: Java/.NET only — TRUE only if Spring Boot / Web API-MVC-Entity
    -- Framework is actually present in this skill-cluster text, not merely
    -- assumed from the base language. NULL = not applicable (other personas).
);

-- Token vocabulary used to classify DEMAND free-text (the demand side
-- has no clean literal string to look up — it has to be parsed).
--
-- canonical_accessory (Task #10 — gap review item #2): for a row whose
-- category represents a named-accessory token family, this is the display
-- name it resolves to (e.g. token_text='aws' -> canonical_accessory='Cloud';
-- token_text='jms' -> canonical_accessory='JMS'). This is the table form of
-- engine.py's ACCESSORY_CANONICAL/_TOKEN_TO_CANONICAL — several raw tokens
-- can share one canonical_accessory (many-to-one, exactly like AWS/Azure/GCP
-- all resolving to 'Cloud'), which is why this is a column here rather than
-- a separate join table: one row per raw token is already the right grain.
-- NULL for a row whose category isn't an accessory family at all (e.g.
-- 'JAVA_TOKENS', 'OUT_OF_SCOPE_TOKENS' rows, which have no canonical-
-- accessory concept). A canonical_accessory value here should also have a
-- matching row in accessory_weights below — that's where its Critical/
-- Important/Minor severity is decided, not here.
-- Note (Task #15): 'REACT_TOKENS'/'react'/'angular' now play a DUAL role,
-- same shape as engine.py's own has_react/has_angular + ACCESSORY_CANONICAL
-- split — the same raw token both (a) helps decide the Frontend persona
-- itself when no language anchor is present, and (b) resolves to
-- canonical_accessory='React'/'Angular' when a language anchor IS present.
-- One row per (category, token_text) already models this correctly: the
-- REACT_TOKENS-category row for 'react' carries canonical_accessory='React'
-- for the accessory case, while persona derivation is a separate rule (not
-- expressed in this table) that only applies when no language anchor is
-- present — this table stores the vocabulary, not the precedence logic.
CREATE TABLE skill_tokens
(
    token_id            SERIAL PRIMARY KEY,
    category            TEXT NOT NULL, -- e.g. 'JAVA_TOKENS', 'REACT_TOKENS', 'OUT_OF_SCOPE_TOKENS'
    token_text          TEXT NOT NULL,
    canonical_accessory TEXT,          -- e.g. 'Cloud', 'JMS', 'Kafka', 'React'; NULL for non-accessory categories
    UNIQUE (category, token_text)
);

-- Task #8 (skill-weighting). Replaces the old flat "any named accessory gap
-- gets one discount" rule: every gap between what a demand asks for and what
-- an employee's profile confirms is weighted Critical/Important/Minor before
-- it's allowed to cap a match's tier (Critical -> Weak, Important -> Good,
-- Minor -> no cap, informational only). 'Framework' is the one Critical row
-- and is NOT a real named accessory — it's the persona's actual framework
-- (Spring Boot for Java; Web API/MVC/Entity Framework for .NET; React or
-- Angular itself for Frontend), tracked via framework_confirmed (see
-- skill_cluster_rules/supply_enriched/demand_enriched below) rather than via
-- named_accessories — it's still listed here so ONE table is the single
-- source of truth for severity, per the explicit decision to fold Task #6's
-- framework check and Frontend's pre-existing framework-unconfirmed bucket
-- into the same concept rather than leaving them as unrelated special cases.
-- v1, explicitly a first pass — expect these weights to be corrected as real
-- shortlist outcomes get reviewed.
-- persona_id (Task #10 — gap review item #4): NULL means "applies to every
-- persona" (the generic default — every row today is like this, since
-- nothing in the real data currently needs a persona-specific override). A
-- non-NULL persona_id row OVERRIDES the generic (persona_id IS NULL) row
-- for that same accessory_name, for that persona only — e.g. if Hibernate
-- should someday matter more for Fullstack Java specifically than the
-- generic default says. This starts genuinely unused (no override rows
-- exist yet); it exists so a real future case doesn't need a schema change
-- to be represented, only a new row.
CREATE TABLE accessory_weights
(
    weight_id      SERIAL PRIMARY KEY,
    accessory_name TEXT        NOT NULL,                     -- canonical name (matches named_accessories/skill_tokens.canonical_accessory), or 'Framework'
    persona_id     INTEGER REFERENCES personas (persona_id), -- NULL = generic default, applies to all personas
    weight         TEXT        NOT NULL CHECK (weight IN ('Critical', 'Important', 'Minor')),
    updated_by     INTEGER REFERENCES users (user_id),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (accessory_name, persona_id)                      -- catches duplicate persona-specific overrides (persona_id NOT NULL)
);
-- UNIQUE (accessory_name, persona_id) above does NOT catch duplicate
-- generic-default rows (persona_id IS NULL) — standard SQL treats
-- NULL <> NULL for uniqueness, so two ('Cloud', NULL) rows would both be
-- allowed by that constraint alone. This partial index closes that gap:
CREATE UNIQUE INDEX uq_accessory_weights_generic
    ON accessory_weights (accessory_name) WHERE persona_id IS NULL;
-- Seed values (mirrors engine.py's ACCESSORY_WEIGHT, all with persona_id
-- NULL — no persona-specific override exists yet):
--   Framework=Critical; Microservices/Cloud/DevOps/Kafka/JMS/RabbitMQ=Important;
--   Docker/Hibernate/Web Services/Redis/GraphQL/Python/Kotlin/Messaging/
--   React/Angular=Minor (Task #15 — React/Angular tracked as a Java/.NET
--   accessory, not just as the Frontend persona itself).
-- An accessory with no row here defaults to 'Important' in the engine
-- (accessory_severity()) — safer to over-discount an unweighted accessory
-- than silently let it through at full strength; this table should still
-- get an explicit row for anything that matters rather than relying on
-- that default long-term.


-- =====================================================================
-- 2. SUPPLY SIDE
-- =====================================================================

-- RE-DERIVABLE. One row per bench employee. Fully truncated/upserted
-- on every AFD-Supply.xlsx refresh — nothing here survives a refresh
-- on its own merit; anything worth keeping goes in supply_overrides.
CREATE TABLE supply_enriched
(
    employee_id                  BIGINT PRIMARY KEY, -- Employee Code from source
    employee_name                TEXT        NOT NULL,
    band                         TEXT,
    location                     TEXT,
    capability                   TEXT,               -- e.g. 'MAS'
    afd_status                   TEXT,               -- Available for Deployment / Blocked-Proposed / etc.
    skill_cluster_raw            TEXT,               -- literal source text, e.g. 'Java,React.js,Spring Boot'

    persona_id                   INTEGER REFERENCES personas (persona_id),
    sub_persona_id               INTEGER REFERENCES sub_personas (sub_persona_id),
    named_accessories            TEXT[] NOT NULL DEFAULT '{}',
    framework_confirmed          BOOLEAN,            -- Task #6 — see reference-table note above; NULL outside Java/.NET
    -- classification_status also covers Task #11 (E0/E2.3 and any other
    -- Sub Band outside the E1.1-E3.2 ladder): those rows get
    -- classification_status = 'data_issue' too, same as an unrecognized
    -- Skill Cluster value — one status value, one meaning ("this employee
    -- needs a correction before they can be matched"), regardless of which
    -- source field caused it.
    classification_status        TEXT        NOT NULL DEFAULT 'classified'
        CHECK (classification_status IN ('classified', 'unclassified', 'data_issue')),

    -- Task #12: Sub-Capability, checked (never filtered/matched on) against
    -- the engine-derived persona, the same non-gating role mas_mapping_raw/
    -- mas_mapping_mismatch_flag already play on demand_enriched below.
    -- Deliberately NOT a filter: checked directly against real data first —
    -- requiring Sub-Capability to agree with the derived persona would have
    -- dropped 17 genuine Full Stack/Front End employees whose Sub-Capability
    -- tag is stale or differently scoped (most often 'Polyglot') while their
    -- actual skills and MAS Mapping both agree with each other. MAS Mapping
    -- agrees with the engine's own derived persona 97% of the time;
    -- Sub-Capability only 84% — the less reliable of the two fields checked,
    -- kept as a flag for a human to go correct the source data, not as a
    -- second gate.
    sub_capability_raw           TEXT,
    sub_capability_mismatch_flag BOOLEAN     NOT NULL DEFAULT FALSE,

    -- hash covers ONLY classification-relevant source columns
    -- (skill_cluster_raw, capability) — deliberately EXCLUDES afd_status,
    -- band, location, which churn constantly as staffing moves and would
    -- make the hash (and needs_reattention downstream) useless noise.
    source_row_hash              TEXT,
    last_refreshed_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- set when an employee_id that previously appeared stops appearing in
    -- an export (staffed, left bench, or simply dropped from the export).
    -- Never hard-deleted, so supply_overrides / demand_candidate_decisions /
    -- match_candidates referencing it stay intact. Mirrors demand_enriched's
    -- existing is_active/inactive_since pattern — added here 2026-09-27
    -- (see revision note at top of file): supply_enriched previously had no
    -- equivalent despite being described as "fully truncated/upserted" on
    -- every refresh, which risked orphaning exactly those FKs.
    is_active                    BOOLEAN     NOT NULL DEFAULT TRUE,
    inactive_since               TIMESTAMPTZ
);

-- PERSISTENT. Recruiter/reviewer corrections and constraints not
-- present in the source export at all. Never written to by a refresh.
CREATE TABLE supply_overrides
(
    override_id   SERIAL PRIMARY KEY,
    employee_id   BIGINT      NOT NULL REFERENCES supply_enriched (employee_id),
    override_type TEXT        NOT NULL, -- skill_add / skill_remove / constraint / note
    value_text    TEXT        NOT NULL,
    added_by      INTEGER REFERENCES users (user_id),
    added_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- =====================================================================
-- 3. DEMAND SIDE
-- =====================================================================

-- RE-DERIVABLE. One row per demand/requisition, keyed by the source
-- Job Requisition ID (kept as TEXT — ~3% of real IDs are alphanumeric,
-- e.g. 'HCL/DBS/2026/2820615', not purely numeric). Fully
-- upserted on every Demand.xlsx refresh.
CREATE TABLE demand_enriched
(
    demand_id                 TEXT PRIMARY KEY, -- Job Requisition ID, as-is from source
    cluster_name_raw          TEXT,             -- free-text demand ask, as typed by the requesting team
    final_skill_cluster_raw   TEXT,             -- 'Final Skill Cluster' column, kept verbatim, informational only
    mas_mapping_raw           TEXT,             -- 'MAS Mapping' column, kept verbatim, NEVER used to filter/match
    additional_request_raw    TEXT,             -- 'Additional Request' column, surfaced to reviewer, NEVER auto-parsed
    location                  TEXT,
    band                      TEXT,

    persona_id                INTEGER REFERENCES personas (persona_id),
    sub_persona_id            INTEGER REFERENCES sub_personas (sub_persona_id),
    named_accessories         TEXT[] NOT NULL DEFAULT '{}',
    framework_confirmed       BOOLEAN,          -- Task #6 — see reference-table note above; NULL outside Java/.NET
    classification_status     TEXT        NOT NULL DEFAULT 'classified'
        CHECK (classification_status IN ('classified', 'unclassified', 'data_issue')),

    -- true when mas_mapping_raw disagrees with the engine-derived persona
    -- (a validation signal for the reviewer, per Task #3 — matching logic
    -- itself never reads mas_mapping_raw)
    mas_mapping_mismatch_flag BOOLEAN     NOT NULL DEFAULT FALSE,

    source_row_hash           TEXT,
    last_refreshed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- set when a demand_id that previously appeared stops appearing in
    -- an export (closed requisition, or simply dropped from the export).
    -- Never hard-deleted, so review state/decisions tied to it survive.
    is_active                 BOOLEAN     NOT NULL DEFAULT TRUE,
    inactive_since            TIMESTAMPTZ
);

-- PERSISTENT. One row per demand — the reviewer's overall handling of
-- that requisition. Never written to by a refresh, EXCEPT that a
-- refresh may flip needs_reattention to TRUE when it detects the
-- underlying source changed under an open review (see hash comparison
-- logic below the schema).
CREATE TABLE demand_review_state
(
    demand_id          TEXT PRIMARY KEY REFERENCES demand_enriched (demand_id),
    status             TEXT    NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'in_progress', 'closed', 'on_hold')),
    assigned_reviewer  INTEGER REFERENCES users (user_id),
    notes              TEXT,
    needs_reattention  BOOLEAN NOT NULL DEFAULT FALSE,
    reattention_reason TEXT, -- e.g. 'source text changed since last review'
    closed_by          INTEGER REFERENCES users (user_id),
    closed_at          TIMESTAMPTZ
);

-- PERSISTENT. Many rows per demand — one per (demand, employee) pair a
-- reviewer has actually acted on. This is NOT the same as the engine's
-- proposed candidate list (that's match_candidates, below) — it's the
-- subset a human has looked at and made a call on.
CREATE TABLE demand_candidate_decisions
(
    decision_id             SERIAL PRIMARY KEY,
    demand_id               TEXT        NOT NULL REFERENCES demand_enriched (demand_id),
    employee_id             BIGINT      NOT NULL REFERENCES supply_enriched (employee_id),
    engine_tier_at_decision TEXT
        CHECK (engine_tier_at_decision IN ('Strong', 'Good', 'Weak')), -- snapshot at decision time
    status                  TEXT        NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'approved', 'rejected', 'staffed')),
    notes                   TEXT,
    decided_by              INTEGER REFERENCES users (user_id),
    decided_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (demand_id, employee_id)
);

-- PERSISTENT. Full audit trail of status changes on a decision, so
-- "approved then rejected then approved again" isn't lost to
-- last-write-wins. Written alongside every UPDATE to
-- demand_candidate_decisions.status, never by a refresh.
CREATE TABLE decision_history
(
    history_id  SERIAL PRIMARY KEY,
    decision_id INTEGER     NOT NULL REFERENCES demand_candidate_decisions (decision_id),
    old_status  TEXT
        CHECK (old_status IN ('pending', 'approved', 'rejected', 'staffed')),
    new_status  TEXT        NOT NULL
        CHECK (new_status IN ('pending', 'approved', 'rejected', 'staffed')),
    -- NOT NULL deliberately: this table exists specifically to answer "who
    -- did this," so a nullable actor would undercut its own purpose. A
    -- genuinely system-driven change (an automated close, say) should be
    -- modeled as a real system user row in `users`, not a NULL actor.
    changed_by  INTEGER     NOT NULL REFERENCES users (user_id),
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- =====================================================================
-- 4. REFRESH / IMPORT AUDIT
-- =====================================================================
-- PERSISTENT (it's a log, not derived from anything). Records every
-- time a source Excel is loaded or matching is (re)run, so "why did
-- employee X disappear" or "why didn't demand Y update" is answerable
-- after the fact instead of a mystery. Declared before match_candidates
-- since that table references it.
CREATE TABLE refresh_runs
(
    run_id        BIGSERIAL PRIMARY KEY,
    source        TEXT        NOT NULL
        CHECK (source IN ('supply', 'demand', 'matching')),
    file_name     TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        TEXT        NOT NULL DEFAULT 'running'
        CHECK (status IN ('running', 'succeeded', 'failed')),
    rows_in       INTEGER,
    rows_new      INTEGER,
    rows_changed  INTEGER,
    rows_flagged  INTEGER, -- e.g. needs_reattention raised, mas_mapping_mismatch raised
    error_message TEXT
);


-- =====================================================================
-- 5. MATCHING ENGINE OUTPUT
-- =====================================================================
-- RE-DERIVABLE. This is what was previously missing from the design:
-- the actual output of build_matches.py (Strong/Good/Weak/Excluded per
-- employee×demand pair). Fully truncated and rewritten every time
-- matching runs — this is what the web app's UI actually reads, so it
-- doesn't have to recompute scoring live on every page load.
CREATE TABLE match_candidates
(
    match_id        BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT      NOT NULL REFERENCES supply_enriched (employee_id),
    demand_id       TEXT        NOT NULL REFERENCES demand_enriched (demand_id),
    overall_tier    TEXT        NOT NULL
        CHECK (overall_tier IN ('Strong', 'Good', 'Weak', 'Excluded')),
    -- Values verified directly against build_matches.py's skill_signal()/
    -- band_signal()/location_signal() `quality` returns (2026-09-27), not
    -- guessed from the earlier comment alone — the earlier comment here
    -- was itself incomplete (missing 'weak_node', 'no_relation').
    skill_signal    TEXT
        CHECK (skill_signal IN ('core', 'accessory_gap_critical', 'accessory_gap_important',
                                'accessory_gap_minor', 'framework_unconfirmed', 'wrong_subpersona',
                                'weak_node', 'no_relation')),
    band_signal     TEXT
        CHECK (band_signal IN ('exact', 'one_above', 'below', 'too_senior', 'unresolved')),
    location_signal TEXT
        CHECK (location_signal IN ('same_city', 'diff_city')),
    one_liner       TEXT, -- the human-readable reasoning string
    -- NOT NULL deliberately: every row here is produced by a refresh_runs
    -- entry, and a nullable run_id would silently weaken the UNIQUE
    -- constraint below the same way accessory_weights' nullable persona_id
    -- did (see the 2026-09-27 revision note at the top of this file).
    run_id          BIGINT      NOT NULL REFERENCES refresh_runs (run_id),
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (employee_id, demand_id, run_id)
);


-- =====================================================================
-- 6. RAW SOURCE HISTORY — DROPPED (2026-09-27 design review)
-- =====================================================================
-- A demand_source_snapshots table ("what did this demand say last
-- Tuesday") was in an earlier version of this design, already marked
-- optional/speculative. Removed entirely — nothing in current scope
-- needs point-in-time demand history, and the re-derivable/persistent
-- split above doesn't depend on it (that only needs the rolling
-- source_row_hash already on demand_enriched/supply_enriched). Add a
-- table like this back if/when point-in-time history becomes an actual
-- requirement; nothing else in this design references it.


-- =====================================================================
-- Indexes worth having from day one (not exhaustive — add more as
-- real query patterns emerge)
-- =====================================================================
CREATE INDEX idx_supply_persona ON supply_enriched (persona_id, sub_persona_id);
CREATE INDEX idx_supply_active ON supply_enriched (is_active);
CREATE INDEX idx_demand_persona ON demand_enriched (persona_id, sub_persona_id);
CREATE INDEX idx_demand_active ON demand_enriched (is_active);
CREATE INDEX idx_match_candidates_demand ON match_candidates (demand_id, overall_tier);
CREATE INDEX idx_match_candidates_employee ON match_candidates (employee_id, overall_tier);
CREATE INDEX idx_decisions_demand ON demand_candidate_decisions (demand_id);
CREATE INDEX idx_decisions_employee ON demand_candidate_decisions (employee_id);


-- =====================================================================
-- The refresh-safety mechanism this whole design exists to support
-- (implemented in the refresh job, not in SQL — noted here so the
-- schema's intent is legible without re-reading the discussion):
--
-- For each incoming source row (supply or demand — as of the 2026-09-27
-- revision this applies identically to BOTH sides; supply_enriched now
-- carries is_active/inactive_since too, closing a gap where it previously
-- didn't):
--   1. Compute new_hash from the classification-relevant columns only.
--   2. Look up the existing row's stored source_row_hash.
--   3. If equal            -> just bump last_refreshed_at.
--   4. If different         -> recompute derived fields normally;
--                              if an open review/decision exists for
--                              this id, set needs_reattention = TRUE
--                              (demand side) with a reason.
--   5. Overwrite source_row_hash with new_hash either way — it is a
--      rolling checkpoint, not a history.
--   6. If an id that existed before is missing from the new export,
--      set is_active = FALSE, inactive_since = now() — never delete,
--      so supply_overrides / demand_review_state / demand_candidate_
--      decisions / match_candidates referencing it stay intact.
-- =====================================================================
