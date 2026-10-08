-- Frontend-anchor review flag (2026-09-29), demand-side counterpart to
-- mas_mapping_mismatch_flag (V1). A demand naming a frontend framework
-- (React/Angular/MEAN) alongside a bare Java/.NET anchor token with no
-- backend-framework confirmation (Spring/ASP.NET) is genuinely ambiguous —
-- see RefreshLogic.frontendAnchorCheck()'s Javadoc and DemandClassifier's
-- Java/.NET anchor-branch comments for the full history (an earlier
-- attempt to auto-reroute such rows to Frontend broke ~180 legitimately
-- Fullstack demands, since MAS Mapping and Additional Request text
-- sometimes independently confirm Full Stack even without an explicit
-- framework token, and identical skill-cluster text was tagged two
-- different ways on two real job requisitions by different humans).
--
-- Same "checked, not used to match" principle as mas_mapping_mismatch_flag:
-- this is a reviewer safety net, never a routing input — DemandClassifier's
-- anchor precedence stays unconditional (Java/.NET always wins as anchor).
--
-- Additive only, same as V2/V3/V4. Existing rows default to false and will
-- backfill correctly once source_row_hash is nulled and refresh is re-run
-- (same hash-gated-reclassification caveat as every other classification
-- column — see RefreshService.applyClassification()).

ALTER TABLE demand_enriched
    ADD COLUMN frontend_anchor_flag BOOLEAN NOT NULL DEFAULT FALSE;
