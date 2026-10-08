-- Found 2026-09-29 while building MatchingRunService (match_candidates
-- parity, step 3): ClassificationResult already computes `completeness`
-- ("Core only" | "Core + Partial" | "Core + Rich") during supply
-- classification, but RefreshService.applyClassification() never copied
-- it onto the entity — it was silently dropped every refresh. Demand-side
-- classification never produces a completeness value (persona.py only
-- computes it for supply), so demand_enriched needs no equivalent column.
--
-- completeness only feeds display text (skillSignal()'s one-liner
-- "compNote", e.g. "core plus some named extras — solid profile") — it
-- never affects tier/quality/hard_exclude decisions or cap_employee_rows'
-- sort order. Without this column, MatchingRunService would have shipped
-- every one-liner with the degraded "profile detail unavailable" fallback
-- text instead of the real value.
--
-- Second gap found in the same pass, this one correctness-affecting (not
-- just display text): demand_enriched has no column for
-- ClassificationResult.status() either. DemandClassifier.classify()
-- already computes it correctly (e.g. "Reviewed — weak MERN match
-- (Node.js only)" for a Node-only demand with no real frontend
-- framework) but RefreshService never persisted it. skillSignal()'s
-- Frontend branch needs this exact text to detect the weak-MERN case
-- (checks .toLowerCase().contains("weak")) and route it to the
-- discounted weak_node quality instead of a normal match. Without it,
-- a weak-MERN demand would be wrongly treated as a full match against
-- React/MERN candidates (promoted to Strong/Good instead of capped at
-- Weak) AND would wrongly hard-exclude a MEAN candidate Python still
-- accepts at Weak — a real tier-outcome bug, not a cosmetic one.
--
-- Additive only, same as V2/V3.

ALTER TABLE supply_enriched
    ADD COLUMN completeness TEXT;
ALTER TABLE demand_enriched
    ADD COLUMN classification_note TEXT;
