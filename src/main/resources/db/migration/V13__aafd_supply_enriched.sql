-- PERSISTENT schema change. AAFD (employees whose current project is ending soon, who will become
-- AFD — demand-supply-mapping-requirements.md's definition) gets its OWN table, not a column added
-- to supply_enriched, for two reasons confirmed against the real files (001-AFD Supply.xlsx vs.
-- 02-AAFD Supply.xlsx):
--   1. supply_enriched is documented as "fully truncated/upserted on every AFD-Supply.xlsx refresh"
--      — sharing that table would mean every refresh's truncate/upsert has to be scoped by a
--      discriminator column or one file's refresh silently wipes the other's rows. A genuinely
--      separate table makes that bug impossible instead of merely avoided-by-discipline.
--   2. The two files' columns overlap only partially (Band/Sub Band/Location/Capability/Sub
--      Capability/MAS Mapping/Skill Cluster/Skill Cluster Persona/Rating/Skill Confirmed by Emp are
--      shared concepts, sometimes under a different header name) — the rest is genuinely unique to
--      each side: AFD has Bench Ageing days/Project Code/Project Name/Assessment Status/Score with
--      no AAFD equivalent; AAFD has WPC Notice Shared on/Availability Date/Month/Customer/Reason of
--      Release/Deployment Status with no AFD equivalent. Forcing one schema would mean a wide column
--      set that's always half-NULL on either side.
--
-- Deliberately NOT classified/persona-linked like supply_enriched/demand_enriched: there is no
-- persona-resolution logic for AAFD's shape yet (RefreshLogic.classifySupply() is tuned entirely to
-- AFD's own columns and 'AFD Status' vocabulary; AAFD's own status column is a constant 'AAFD', not
-- that vocabulary at all), so this table is a faithful raw mirror of the AAFD export, upserted by
-- Emp Code, with the same is_active/inactive_since soft-delete pattern supply_enriched/demand_enriched
-- already use — not a drop-in replacement for real classification, which is still open, separate
-- future work (see demand-supply-mapping-requirements.md's "AAFD... isn't a simple extension" note).
CREATE TABLE aafd_supply_enriched
(
    emp_code                   BIGINT PRIMARY KEY,
    name                       TEXT        NOT NULL,
    offshore_onshore           TEXT,
    wpc_notice_shared_on       TEXT, -- raw text, not a real date column — see RefreshLogic's/the
    -- README's note that the real exports have zero genuine
    -- date-typed cells; same reasoning applies here.
    availability_date          TEXT,
    month                      TEXT,
    status                     TEXT, -- the source file's own 'Status' column (constant 'AAFD' in
    -- every real row seen so far) — kept raw rather than assumed.
    band                       TEXT,
    sub_band                   TEXT,
    skill_confirmed_by_emp     TEXT,
    primary_skill              TEXT, -- 'Primary skill (Resource confirmed)'
    capability                 TEXT,
    sub_capability             TEXT,
    location                   TEXT,
    prime_nv                   TEXT,
    customer                   TEXT,
    reason_of_release          TEXT,
    deployment_status          TEXT,
    sap_non_sap                TEXT,
    resource_role_type         TEXT,
    duration_in_project_months TEXT,
    detailed_remarks           TEXT,
    l3                         TEXT,
    l4                         TEXT,
    global_vertical            TEXT,
    mas_mapping                TEXT,
    hr_l4                      TEXT,
    mas_raw                    TEXT, -- the source file's separate, literal 'MAS' column — kept
    -- distinct from mas_mapping above since they're two different
    -- columns in the real export, even though sample data shows
    -- both holding similar-looking values.
    skill_cluster              TEXT,
    skill_cluster_persona      TEXT,
    rating                     TEXT,
    last_refreshed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active                  BOOLEAN     NOT NULL DEFAULT TRUE,
    inactive_since             TIMESTAMPTZ
);

-- Widen refresh_runs' source CHECK to allow 'aafd' — same default <table>_<column>_check naming
-- V9/V11 already relied on (neither constraint was given an explicit name in V1__init_schema.sql).
ALTER TABLE refresh_runs
DROP
CONSTRAINT refresh_runs_source_check,
    ADD CONSTRAINT refresh_runs_source_check
        CHECK (source IN ('supply', 'demand', 'matching', 'aafd'));
