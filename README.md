# bench-match-webapp

Phase 2 web app for the Demand & Supply Mapping project. Built from the
Python CLI tool (`bench-match-cli`: `engine.py`/`build_matches.py`/
`classify_inputs.py`) as spec, per the build → parallel-run → sunset plan
in `demand-supply-mapping-requirements.md`. Reads and writes the Postgres
schema in `full_db_design.sql` directly — does not call into Python at
runtime, and never will (Python is retired once the parallel-run window
confirms no logic gap remains).

## Status as of this handoff (2026-09-28)

**This is a scaffold plus one fully-verified matching-engine port, not a
finished app.** What exists:

- Maven project structure (`pom.xml`), Spring Boot + Vaadin Flow + REST
  API dependencies declared, per the agreed architecture. Confirmed with a
  real `mvn test-compile` on a real machine with Maven Central access (2026-09-28) — see Verification below.
- `V1__init_schema.sql` Flyway migration — a direct copy of the reviewed
  `full_db_design.sql`.
- JPA entities + repositories for the tables needed for matching (`Persona`, `SubPersona`, `SkillClusterRule`,
  `SkillToken`,
  `AccessoryWeight`, `SupplyEnriched`, `DemandEnriched`, `RefreshRun`,
  `MatchCandidate`). Entities for the reviewer-workflow tables (`users`, `supply_overrides`, `demand_review_state`,
  `demand_candidate_decisions`, `decision_history`) are **not yet
  written** — out of scope for this slice.
- **The matching engine port** (`com.hcltech.benchmatch.matching`) is now
  ported IN FULL for every real persona pairing: `Engine` (constants,
  including `FRONTEND_EQUIV`), `SupplyClassifier` (full port of
  `SUPPLY_TABLE`), `DemandClassifier` (full port of
  `classify_demand_row`), and `MatchingService` (full port of
  `band_signal`/`location_signal`/`overall_tier`, plus BOTH branches of
  `skill_signal` — same-family Java-Java/.NET-.NET, and Frontend-Frontend
  sub-persona compatibility — see that class's own PORT STATUS comment
  for the one correction made along the way).
- **Two parallel-run verification harnesses**: `ParallelRunVerification.java`
  (Fullstack Java only, the original vertical slice) and
  `FullPersonaVerification.java` (every classified persona pairing) —
  both check the Java port against real Python output, row-for-row.

## REST API (2026-09-29, partial)

`com.hcltech.benchmatch.api` — read-only endpoints over `supply_enriched`/
`demand_enriched`, now that `RefreshService` actually puts real data there:

- `GET /api/supply` (filters: `persona`, `subPersona`, `location`,
  `classificationStatus`, `activeOnly` — default `true`), `GET /api/supply/{employeeId}`
- `GET /api/demand` (same filters), `GET /api/demand/{demandId}`

Both return DTOs (`SupplyDto`/`DemandDto`), never the JPA entities directly
— their `persona`/`subPersona` associations are lazy and would either
serialize as proxies or throw outside a transaction. Filtering is done in
memory, not as JPA Specifications/derived queries — deliberate, given
243/972 rows today; revisit as real DB-level filtering if the dataset
ever grows past a few thousand rows.

**Deliberately NOT built yet: any endpoint over `match_candidates`.**
Nothing in this codebase populates that table. `MatchingService` computes
one pair's signals/tier in memory (only ever called from the verification
harnesses) — there's no service that runs it across every supply×demand
pair in the DB and persists rows. Building a `MatchCandidateController`
against an always-empty table would be pointless, so I stopped rather
than build either that or the missing `MatchingRunService` unprompted —
the latter is a real, separate piece of new work (batch job or on-demand
run, re-run policy, how `match_candidates` gets truncated/rewritten each
time per its own "RE-DERIVABLE" schema comment), not something to fold
silently into "add REST controllers."

## What is NOT done yet

- `RefreshService` IS now verified against a real Postgres database (2026-09-30 — see
  `RefreshServiceDbVerificationTest`, run against a
  manually-started Postgres container; superseded the "not yet verified"
  note that used to be here).
- Vaadin UI views and the multipart upload endpoint now exist — see
  "Upload endpoint + Vaadin grids" below (2026-09-30, task #18).
- `match_candidates` DOES now have a producer — `MatchingRunController`/
  `MatchingRunService` (`POST /api/matching/run`) — superseding the
  "`match_candidates` has no producer" section further down, which is
  kept for history but is no longer current.
- The shortlist workbook export (Generate button) now exists — see
  "Shortlist workbook export" below (2026-09-30, task #19).
- **Not yet built**: no reviewer-workflow UI (accept/reject a match,
  override a classification) — that whole slice (`users`/
  `supply_overrides`/`demand_review_state`/etc.) has no entities yet,
  let alone views.
- Auth and deployment target are still open (see requirements doc).

## Shortlist workbook export (2026-09-30, task #19)

Java port of `write_shortlist_workbook.py` + `build_matches.py`'s `main()`
— **not independently compiled in this sandbox**, same Maven Central
limitation noted elsewhere in this file. Run `mvn clean compile` on a real
machine before trusting this builds.

**Schema fix that had to come first**: four raw source columns the
workbook displays were never ingested —
`demand_enriched.customer`/`project_name`/`new_ageing` (raw 'Customer'/
'Project Name'/'New-Ageing') and `supply_enriched.prime_nv` (raw
'Prime/NV'). All four are pure pass-through display fields, never read by
classification or matching. A fifth, related bug: `supply_enriched` had no
equivalent of `demand_enriched.classification_note` (V4) —
`ClassificationResult.status()` was computed but silently dropped, same
bug demand had before V4, needed here for the DATA-ISSUE block's reason
text. All five added via `V6__shortlist_display_fields.sql`; `RefreshLogic`
and `RefreshService` updated to read/upsert them.

**`com.example.benchmatch.export.ShortlistWorkbookService`** — runs a
fresh matching pass at export time (same candidate scope and per-employee
cap as `MatchingRunService.runMatching()`) rather than reading
`match_candidates`, because `match_candidates` only persists each signal's
quality code and the one-liner — not the full collapsed/expanded text this
workbook's "— summary"/"— raw detail" column pairs show. This does mean
the candidate-scope filter is duplicated in both services (~15 lines,
`candidateDemandsFor`) rather than shared — a deliberate trade-off to keep
each class independently readable; flagged here in case it drifts.

Two ways to trigger it, both calling `ShortlistWorkbookService` directly:

- `com.example.benchmatch.view.ShortlistView` (`/shortlist` route) — a
  "Generate & Download" button (Vaadin `Anchor` + `StreamResource`, so
  nothing is computed until actually clicked).
- `GET /api/shortlist/workbook` (`ShortlistExportController`) — same
  output, for curl/Postman/anything outside the browser.

Formatting (colors, fonts, column widths, freeze panes, employee/DATA-ISSUE
block layout, tier color-coding) mirrors the Python source as closely as
Apache POI allows. **One deliberate divergence**: the "Read Me" sheet keeps
the Python version's structure (scope, tier definitions, capping rule,
classification approach, known limitations) but recomputes every number
live from the current database rather than hard-coding the original
Phase-1 run's snapshot figures (e.g. "16 employees have zero Strong/Good
match", "2,472 rows across 239 employees") — those were true of one
specific historical run, not something worth freezing into every future
export. Worth a look before trusting the wording reads naturally once real
numbers are substituted in.

**Not yet verified against real data** — same caveat as everything else in
this file that says so: needs a real run against the actual
AFD-Supply/Demand exports, opened in Excel, and eyeballed against what
`write_shortlist_workbook.py` itself produces from the same data, before
trusting the port is byte-for-byte faithful.

## Shortlist workbook re-port against the authoritative source (2026-09-30)

**The bug**: task #19 above was ported from `/tmp/deliver-python/write_shortlist_workbook.py`
(a packaged deliverable, README said "matches the last agreed state") without
checking for a newer copy. The user caught it by diffing the generated
workbook's columns against what they expected — three columns were missing:
"MAS Mapping check", "Frontend Anchor check", "Additional Request (manual
review only)".

**Root cause, and why it should not have happened**: this project's sandbox
accumulates multiple divergent copies of the Python reference source across
sessions. `MatchingService.java`'s own class Javadoc *already documented*
this exact trap — a prior session (2026-09-28) hit it for the matching
engine itself, ruled out `/home/claude/build_matches.py` as stale, and named
`cli_pkg/bench-match-cli/build_matches.py` as "the authoritative source."
This session repeated the same mistake for `write_shortlist_workbook.py`
because that Javadoc wasn't read before doing independent research. Lesson
for whoever picks this up next: **before porting or re-verifying against
Python, read `MatchingService.java`'s class Javadoc first** — it names the
authoritative source and the date it was last confirmed current.

**What was actually found on investigation**: good news — the core matching
engine (`Engine.java`, `SupplyClassifier.java`, `DemandClassifier.java`,
`MatchingService.java`) needed **no changes**. All four already match
`cli_pkg/bench-match-cli/engine.py`/`build_matches.py` exactly (confirmed by
diffing `cli_pkg`'s `engine.py` against `/tmp/deliver-python`'s — the only
difference is a removed code comment, no logic). The demand-side and
supply-side band-ladder exclusion (`Sub Band` outside `E1.1`–`E3.2` ⇒ persona
nulled out, `DATA ISSUE` status, excluded from matching entirely — not
scored as an ordinary discount) was also already correctly implemented in
`RefreshLogic.classifySupply()`/`classifyDemand()`. The only real gap was in
`ShortlistWorkbookService` — and even there, all the underlying data (`DemandEnriched.masMappingMismatchFlag`,
`.frontendAnchorFlag`,
`.additionalRequestRaw`, `SupplyEnriched.subCapabilityMismatchFlag`) and note
helpers (`RefreshLogic.masMappingNote()`, `.frontendAnchorNote()`,
`.subCapabilityNote()`) already existed from earlier work — nothing new
needed adding to the schema or classification layer, only to the export.

**Fix applied**:

- `ShortlistRow` — added `masMappingNote`, `frontendAnchorNote`,
  `additionalRequest` fields.
- `ShortlistWorkbookService.buildRows()` — computes the three via
  `RefreshLogic.masMappingNote()`/`.frontendAnchorNote()`/
  `getAdditionalRequestRaw()`, same as `build_matches.py`'s
  `dem.get('mas_mapping_note')` etc.
- `ShortlistWorkbookService.writeEmployeeBlock()` — added the three columns
  to `COLUMNS`/the row-value array (bold amber styling only when actually
  flagged, matching Python's `c in (22, 23) and v` check — an empty note is
  never colored); extended the wrap-text column set to include "Additional
  Request"; added the `⚠ Sub-Capability check: …` suffix to the employee
  header line via `RefreshLogic.subCapabilityNote(emp.getSubCapabilityRaw(),
  persona)`, mirroring Python's `sc_suffix`.

**Still not independently compiled in this sandbox** (same Maven Central
limitation) — run `mvn clean compile` and generate a real workbook to
compare column-for-column against `write_shortlist_workbook.py`'s own output
before trusting this is byte-for-byte faithful.

## Upload endpoint + Vaadin grids (2026-09-30, task #18)

Built on top of the user's own repackaged codebase (`com.example.benchmatch`,
Spring Boot 4.1.1, Vaadin 25.3.0) — **not independently compiled in this
sandbox**, since it has no route to Maven Central (same limitation noted
elsewhere in this file for Apache POI/Testcontainers). Run `mvn clean
compile` (or `mvn spring-boot:run`) on a real machine before trusting this
builds; if it doesn't, the error will point at exactly what needs fixing.

**Two ways to trigger a refresh now, both wired to the same
`RefreshService`, per this app's Vaadin-views-and-REST-controllers-share-
the-service-layer architecture:**

- `com.example.benchmatch.view.UploadView` (`/upload` route) — a Vaadin
  page with two file-drop widgets (Supply, Demand). Calls
  `RefreshService.refreshSupply()`/`refreshDemand()` directly, in-process (not over HTTP), staging the uploaded bytes to
  a temp file first since
  `RefreshService`/`ExcelSheetReader` need a real `Path`. Shows the
  resulting row counts (in/new/changed/flagged) and any error message.
- `RefreshController`'s new `POST /api/refresh/supply/upload` and
  `POST /api/refresh/demand/upload` — multipart endpoints for any
  consumer that isn't the browser (curl/Postman/another service). The
  original `filePath`-based endpoints are unchanged, still useful for
  scripting a refresh against a file already on the server.

**Two read-only grids, one per table:**

- `com.example.benchmatch.view.SupplyView` (`/supply`, also the app's
  root route `/`) and `com.example.benchmatch.view.DemandView`
  (`/demand`) — `Grid` over `SupplyDto`/`DemandDto` with the same filters
  the REST API exposes (persona, sub-persona, location, classification
  status, active-only), applied on a "Search" button rather than per
  keystroke, since each filter re-runs an in-memory scan.
- The filtering logic itself was pulled out of `SupplyController`/
  `DemandController` into two new `@Service` classes,
  `SupplyQueryService`/`DemandQueryService`, so the grids and the REST
  API call the exact same code instead of two copies drifting apart.
  `SupplyController`/`DemandController` now just delegate to them — no
  behavior change, same filters, same DTOs.
- `com.example.benchmatch.view.MainLayout` — the shared `AppLayout` shell (title bar + a `Tabs`/`RouterLink` strip:
  Supply, Demand, Upload).

**Known gaps in this slice, deliberately not addressed:**

- No pagination on the grids — fine at 243/972 rows (Phase 1), same
  caveat as the REST API's in-memory filtering.
- No client-side validation beyond `.xlsx` file-extension filtering on
  the Upload widget — a malformed or wrong-shaped `.xlsx` surfaces
  whatever exception `RefreshService`/`ExcelSheetReader` throws, shown
  as-is in the result panel. Good enough to know something went wrong;
  not a friendly error message yet.
- No auth on any of this — anyone who can reach the app can upload a
  file or browse every row, same as the rest of this handoff.

## RefreshService (2026-09-28)

Excel -> DB ingestion, implementing the hash-diff/upsert/soft-delete
algorithm documented at the bottom of `full_db_design.sql`. Split in two,
matching the existing `matching` package's shape:

- `com.hcltech.benchmatch.refresh.RefreshLogic` — plain Java port of
  `classify_inputs.py`'s filtering + classification (Phase 1 supply scope,
  demand footer-stripping, both band-ladder data-quality overrides,
  Sub-Capability/MAS-Mapping validation flags, `source_row_hash`
  computation). No Spring/JPA dependency, so it's verifiable standalone —
  see `RefreshLogicVerification.java` (same pattern as
  `ParallelRunVerification`/`FullPersonaVerification`), which diffs its
  output against `verification-fixtures/full_supply_classified.csv` /
  `full_demand_classified.csv` (already-confirmed real ground truth from
  `export_full_ground_truth.py`, i.e. the real `classify_inputs.py`
  functions). **Not yet run** — needs real Apache POI on the classpath,
  which this sandbox can't reach (see the file's own header comment for
  the exact `mvn`/`javac`/`java` commands to run it on a real machine).
- `com.hcltech.benchmatch.refresh.ExcelSheetReader` — plain Apache POI
  reader (no Spring dependency either), turns a sheet into
  `List<Map<String,String>>`.
- `com.hcltech.benchmatch.refresh.PersonaCatalog` — get-or-create lookup
  for `personas`/`sub_personas` (neither table is pre-seeded; sub-persona
  names are an open set, e.g. `"React + Angular (dual-acceptable)"`, so
  rows are created lazily).
- `com.hcltech.benchmatch.refresh.RefreshService` — the Spring `@Service`
  that wires `RefreshLogic`'s output to `SupplyEnrichedRepository`/
  `DemandEnrichedRepository`/`RefreshRunRepository`, per-row, inside a
  transaction.
- `com.hcltech.benchmatch.refresh.RefreshController` — two `POST`
  endpoints (`/api/refresh/supply`, `/api/refresh/demand`), each taking a
  server-side `filePath` query param, so a refresh can be triggered without
  waiting on a real upload UI.

Three known, deliberate gaps (documented in `RefreshService`'s own class
Javadoc too):

1. **`needs_reattention` is never set.** Step 4 of the schema's documented
   algorithm ("if an open review/decision exists for this id, flag it")
   needs `demand_review_state`, which has no JPA entity yet — that whole
   reviewer-workflow slice was already out of scope before this work
   started. Reclassification and hashing both work; only this one flag is
   missing.
2. **`sub_capability_note`/`mas_mapping_note` (the human-readable reason
   text) aren't persisted** — the schema only has the boolean flags.
   Nothing is actually lost: both are fully re-derivable on demand from the
   stored raw value + persona (`RefreshLogic.subCapabilityNote()` /
   `masMappingNote()`), so a future controller/view calls those instead of
   storing redundant text.
3. **`cluster_name_raw` is populated from the raw Excel column literally
   named `"cluster"`** — a best-effort mapping based on the schema's own
   comment ("free-text demand ask, as typed by the requesting team", which
   describes `"cluster"` better than `"Final Skill Cluster"` does). Worth
   confirming against one real demand row before trusting it.

Also decided explicitly (2026-09-28): when a row's `source_row_hash` is
unchanged, ALL descriptive columns (location/band/name/etc.) are still
upserted every refresh — the hash only gates whether classification is
recomputed, never whether plain fields get refreshed. This avoids a stale
Location/Band sitting in the DB just because Skill Cluster text didn't
change.

### Schema fix (2026-09-29): `supply_enriched` was missing `sub_band`

Found while wiring up the "Bench Ageing days" column: `supply_enriched`
only ever had the COARSE `band` (E1/E2/E3, raw 'Band' column) — there was
no column for the fine-grained `Sub Band` ('E1.1'..'E3.2'), even though
that's what actually drives the band-ladder DATA ISSUE override and what
`MatchingService.bandSignal()` needs to score band match quality.
`RefreshLogic` was already reading and hashing Sub Band correctly in
memory; it just had nowhere to persist it. Left as-is, this would have
silently broken band scoring the moment matching gets wired to read from
this table instead of a CSV.

Fixed via `V2__supply_sub_band_and_bench_ageing.sql` (additive —
`ALTER TABLE ... ADD COLUMN`, safe to run against an already-populated
database; V1 itself was NOT edited, since it's already applied on real
databases and Flyway checksums it). Also corrected a stale comment in
`full_db_design.sql`: it claimed `source_row_hash` covers
`(skill_cluster_raw, capability)`, but the code has always needed — and
`RefreshLogic` has always computed — `(skill_cluster_raw, sub_band)`, since
Sub Band is what a band-ladder change actually needs to be detected by.

Same pass added `bench_ageing_days` (raw 'Bench Ageing days' column, a
plain day count) — picked over the source file's bucketed 'Duration'
column ('0-2 Weeks', etc.) since the day count is strictly more useful (a bucket is derivable from it, not the reverse).

`demand_enriched` needed no equivalent fix: its existing `band` column
already stores the fine-grained value (raw 'Demand Sub Band Name') — the
demand export has no separate coarse band column worth keeping.

**Not yet re-verified after this fix** — `RefreshLogicVerification` was
updated to also check `sub_band`/`bench_ageing_days` against ground truth,
but hasn't been re-run since. Needs a fresh pass before trusting it, same
as everything else in this file that says "needs a real run."

## `match_candidates` has no producer (found 2026-09-29)

Nothing in this codebase runs `MatchingService`'s per-pair logic across
all supply × demand pairs and writes rows to `match_candidates`.
`MatchingService` is only ever invoked from the standalone verification
harnesses (`ParallelRunVerification`, `FullPersonaVerification`). A REST
endpoint over `match_candidates` today would just be an always-empty
table.

Rather than build a hollow or scoped-down stand-in, the agreed plan is
full parity with `build_matches.py`'s real per-employee ranking/capping
behavior, in four steps:

1. **Schema + ingestion** (IN PROGRESS — see below): add the raw columns
   `assessment_signal()`/`cap_employee_rows()` need that had no home in
   the schema — `rating`/`score` on `supply_enriched`,
   `balance_positions`/`due_category` on `demand_enriched`.
2. **Port the ranking/capping logic**: `RATING_RANK`/`RATING_DISPLAY`/
   `AGEING_RANK`, `assessment_signal()`, and `cap_employee_rows()`
   (all Strong kept uncapped; top-3 Good by `(ageing_rank, -balance_positions)`;
   Weak only as a fallback when an employee has zero Strong and zero Good,
   capped at 10, same sort; top-3 Excluded near-misses by
   `(miss_distance, ageing_rank)`).
3. **`MatchingRunService`**: iterate active supply × candidate demand (same-persona-family pre-filter, matching
   `build_candidate_demands()`),
   compute all four signals via the already-verified `MatchingService`,
   apply the cap, write `match_candidates`, record a `refresh_runs` row
   with `source='matching'`.
4. **Verify** via a standalone harness diffing against a real
   `data/match_rows.csv` (from actually running `build_matches.py`),
   same discipline as `RefreshLogicVerification`, before trusting it
   against the DB.

### Step 1 (2026-09-29): `rating`/`score`/`balance_positions`/`due_category`

Added via `V3__assessment_and_ranking_columns.sql` (additive, same
pattern as V2):

- `supply_enriched.rating` (TEXT) — raw 'Rating' column. One of a small
  fixed set of literal strings (`Expert`/`Proficient`/`Competent`/
  `Advanced Beginner`/`Beginner`/`Entry`/`'0'`/`'0% Score'`, the last two
  both meaning "not yet assessed" — that normalization happens in step 2's
  `RATING_RANK`/`RATING_DISPLAY` port, not at ingestion time; the raw
  literal is stored as-is here).
- `supply_enriched.score` (DOUBLE PRECISION) — raw 'Score' column,
  numeric fraction shown alongside Rating in `assessment_signal()`'s
  one-liner.
- `demand_enriched.balance_positions` (INTEGER) — raw 'Balance Positions'
  column, used as the tie-break (`-balance_positions`) when
  `cap_employee_rows()` ranks Good rows.
- `demand_enriched.due_category` (TEXT) — raw 'Due Category_New' column;
  step 2's `AGEING_RANK` maps its small fixed set of date-bucket strings
  to a numeric urgency rank.

All four are always-upserted (same as every other descriptive column,
regardless of whether `source_row_hash` changed) in `RefreshLogic`/
`RefreshService`. `RefreshLogicVerification` was updated to check all
four against ground truth (`Rating`/`Score` in `full_supply_classified.csv`,
`Balance Positions`/`Due Category_New` in `full_demand_classified.csv`).

**Verified PASS on a real machine (2026-09-29)** — one real mismatch was
found and fixed along the way: `nz()` didn't know about pandas'
default `na_values` list (`NA`, `N/A`, `NULL`, `NaN`, `None`, etc.,
applied to every column `pandas.read_excel()` reads unless overridden,
which `classify_inputs.py` doesn't). Employee 52022094's raw `Rating`
cell literally contains the text `NA`; pandas silently reads that as
missing, `ExcelSheetReader` (plain POI) read it as the literal string.
Fixed by teaching `nz()` the same token list, so any raw field
containing one of these tokens is treated as null the same way the
ground truth treats it — this only affects `nz()`-normalized/stored
output, not classification itself (`SupplyClassifier`/`DemandClassifier`
read raw `row.get(...)` values directly, untouched by this). After the
fix: 243/243 supply, 972/972 demand, hash stability PASS. **Step 1 is
now fully closed.**

### Step 2 (2026-09-29): ported `assessment_signal()`/`cap_employee_rows()`

Added to `MatchingService.java` (same class as the already-verified
`bandSignal()`/`locationSignal()`/`skillSignal()`/`overallTier()`):

- `RATING_RANK`/`UNASSESSED_RATINGS`/`RATING_QUALIFIER` constants and
  `assessmentSignal(rating, score)` — ported in full, including the one
  subtlety worth calling out: a missing supply Rating (`null` in Java,
  after `RefreshLogic.nz()`'s pandas-NA-token normalization) is treated
  as the literal string `"nan"` for lookup purposes, matching Python's
  `str(rating)` on a pandas `NaN` cell (`str(float('nan')) == "nan"`).
  Both land in the same rank-0 "not recognized" fallback either way.
- `AGEING_RANK` constant and `ageingRank(dueCategory)` — ported in full;
  unrecognized/missing due-category values default to rank 9, same as
  Python's `.get(x, 9)`.
- `capEmployeeRows(List<MatchRow>)` — the per-employee output cap,
  ported in full: all Strong kept uncapped; top-3 Good by
  `(ageingRank asc, balancePositions desc)`; Weak shown only as a
  fallback when an employee has zero Strong AND zero Good (uncapped
  counts, before truncation), capped at 10, same sort; top-3 Excluded
  near-misses by `(missDistance, ageingRank)`. One defensive addition
  beyond the strict port: `balancePositions == null` sorts as 0 rather
  than throwing (Python's `-r['balance_positions']` would raise on
  `None` — not expected to occur on real approved-demand rows, but
  Java shouldn't crash if it ever does).
- New small types: `AssessmentResult` (rank/collapsed/expanded, mirrors
  `SignalResult`'s shape) and `MatchRow` (the row shape
  `capEmployeeRows()` needs — employee/demand ids, tier, skill quality,
  ageing rank, balance positions, and the three signal/one-liner display
  fields; display-only fields like customer/project are deliberately
  left out, since the capping algorithm doesn't touch them).

**Verified in the sandbox** with a hand-written smoke test tracing
several `assessment_signal()` calls against a manual Python trace (`Expert`/`Entry`/`0`/`0% Score`/missing-rating cases)
and
`cap_employee_rows()` against constructed row sets (Good-only capping
order, Strong-present suppresses Weak entirely, Weak-only fallback caps
at 10, Excluded near-miss ordering) — all matched expected output. **Not yet run against real match_rows.csv** — that's
step 4, once step 3 (MatchingRunService) exists to actually produce real rows to check.

**Not wired to anything yet** — `assessmentSignal()`/`ageingRank()`/
`capEmployeeRows()` are pure functions with no caller. Step 3 (`MatchingRunService`) is what will actually call them
against real
supply/demand rows and write `match_candidates`.

### Step 3 (2026-09-29): `MatchingRunService` — the first real `match_candidates` producer

`MatchingRunService.runMatching()` (`com.hcltech.benchmatch.matching`),
triggered via `POST /api/matching/run` (`MatchingRunController`, no
request body — matching always runs against whatever is currently
active, there's no file to point at). Ports `build_matches.py`'s
`main()`: iterates active, classified supply x same-persona-family
candidate demand (`build_candidate_demands()`'s restriction — Java-Java,
.NET-.NET, Frontend-Frontend across sub-personas; cross-persona matching
was removed from the design entirely, see `MatchingService`'s own class
Javadoc), computes skill/band/location/assessment signals via the
already-verified `MatchingService`, applies `capEmployeeRows()` per
employee, and rewrites `match_candidates` in full — the table is
RE-DERIVABLE (`V1__init_schema.sql`'s own comment: "Fully truncated and
rewritten every time matching runs"), so each run deletes the previous
run's rows rather than accumulating them under distinct `run_id`s.

**Two more real gaps found and fixed ahead of this** (both via `V4`,
same migration as `completeness` — see that section above and the
migration file's comments for the demand-side one):

- `supply_enriched.completeness` — display-only, covered above.
- `demand_enriched.classification_note` — **correctness-affecting**:
  persists `ClassificationResult.status()` (`DemandClassifier` already
  computes this correctly, e.g. `"Reviewed — weak MERN match (Node.js
only)"`, but it was never saved anywhere). `skillSignal()`'s Frontend
  branch needs this exact text to detect the weak-MERN case; without it,
  a weak-MERN demand would have been wrongly treated as a full match
  against React/MERN candidates (promoted to Strong/Good instead of
  capped at Weak) and would have wrongly hard-excluded a MEAN candidate
  Python still accepts at Weak.

**One deliberate non-fix, checked rather than assumed**:
`location_signal()` takes a `pan_india` argument in the Python source,
but it only ever affects that function's `expanded` text — never
`collapsed`, never `quality`, never the tier decision — and `expanded`
text isn't persisted to `match_candidates` in this schema at all (only
the quality tag and the collapsed-text-built `one_liner` are). So no
`demand_enriched.pan_india` column was added; `locationSignal()` is
always called with `panIndia=null` here, which is provably a no-op for
anything this class actually stores.

`rowsIn`/`rowsNew`/`rowsChanged`/`rowsFlagged` on a matching run's
`refresh_runs` row mean something different than on a supply/demand
refresh, since this isn't an upsert against changed source rows —
documented inline in `finishRun()`: `rowsIn` = classified active supply
employees considered, `rowsNew` = total `match_candidates` rows written (all of them, since the table is fully
rewritten), `rowsChanged` =
deliberately `null` (not meaningful here), `rowsFlagged` = count of
Excluded near-miss rows written (an at-a-glance signal, not a warning).

**Verification status**: confirmed by step 4, below — the full pipeline
this class implements (persona-family filter, all four signals,
`capEmployeeRows()`, `one_liner` assembly) now has an exact-match check
against real `build_matches.py` output, not just a compile check.

### Step 4 (2026-09-29): end-to-end verification against real `data/match_rows.csv`

`MatchingRunVerification.java`
(`src/test/java/com/hcltech/benchmatch/matching`) closes the loop steps
1-3 opened: it re-derives the exact row set `MatchingRunService` would
write to `match_candidates` — persona-family candidate filtering,
`skillSignal()`/`bandSignal()`/`locationSignal()`/`assessmentSignal()`/
`overallTier()`, `capEmployeeRows()`, and the `one_liner` assembly, all
called the same way `MatchingRunService` calls them — directly from the
classified CSVs (no Spring/JPA needed, same discipline as the other
`*Verification` classes), and diffs it row-for-row against
`data/match_rows.csv`, a real `build_matches.py` run against the same
data (`cli_pkg/bench-match-cli/full_supply_classified.csv` +
`full_demand_classified.csv`, confirmed byte-identical to the
`FullPersonaVerification` fixtures and to each other in mtime — a
genuinely paired ground-truth set, not an accidental mismatch of
snapshots).

`FullPersonaVerification` (step 4's predecessor, 2026-09-28) had already
proven the four signals exact across all 86,837 pre-cap candidate pairs;
what it couldn't check is everything layered on top in `MatchingRunService`
specifically — `assessmentSignal()`, `ageingRank()`, `capEmployeeRows()`
at real scale (previously only hand-traced on 4 made-up scenarios), and
the `one_liner` text assembly. This harness checks all of it, on the **final, post-cap row set** — the actual thing that
gets written to
`match_candidates`.

**Result: exact match, confirmed twice** — once in the sandbox against a
stale 2026-09-28 snapshot (2,591/2,591), and **again on a real machine (2026-09-29) against a fresh snapshot regenerated
straight from the
user's own current `input/AFD-Supply.xlsx`/`Demand.xlsx`: 2,586/2,586,
0 extra rows, 0 field mismatches** (tier, skill/band/location quality,
`assessment_rank`, `ageing_rank`, and the full `one_liner` string all
checked per row). 2,586 is exactly the row count the live DB's own
`POST /api/matching/run` produced — the earlier "row-count drift from
the live DB" concern (below) turned out to be nothing once the CLI
snapshot and the webapp's DB state were actually regenerated from the
same data at the same time: two independent implementations (Python,
Java) agree exactly, row for row, field for field. Run:

```
cd bench-match-webapp
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
mkdir -p step4-out
javac -cp "$(cat cp.txt)" -d step4-out \
  src/main/java/com/hcltech/benchmatch/matching/AssessmentResult.java \
  src/main/java/com/hcltech/benchmatch/matching/ClassificationResult.java \
  src/main/java/com/hcltech/benchmatch/matching/DemandClassifier.java \
  src/main/java/com/hcltech/benchmatch/matching/Engine.java \
  src/main/java/com/hcltech/benchmatch/matching/MatchRow.java \
  src/main/java/com/hcltech/benchmatch/matching/MatchingService.java \
  src/main/java/com/hcltech/benchmatch/matching/SignalResult.java \
  src/main/java/com/hcltech/benchmatch/matching/SupplyClassifier.java \
  src/test/java/com/hcltech/benchmatch/matching/MatchingRunVerification.java
java -cp "step4-out:$(cat cp.txt)" \
  com.hcltech.benchmatch.matching.MatchingRunVerification <path-to-cli-repo> <path-to-cli-repo>/data/match_rows.csv
```

Deliberately does **not** compile `MatchingRunService.java`/
`MatchingRunController.java` — this harness duplicates their
`candidateDemandsFor()`/`buildOneLiner()`/`leadIn()` logic internally so
it can run standalone against the classified CSVs without needing the
`entity`/`repository` packages or a live Spring context; including those
two files in the `javac` call pulls in dependencies this harness doesn't
need and isn't equipped to satisfy.

`<path-to-cli-repo>` must contain `full_supply_classified.csv` and
`full_demand_classified.csv` at its root, generated via the CLI's own
`export_full_ground_truth.py` (a small script — not previously in the
user's copy of the CLI repo, since it only ever existed in this
sandbox — added to their repo during this verification). Regenerate all
three ground-truth files together, in this order, without swapping the
input Excel in between, so they stay a matched set:

```
python3 -c "import classify_inputs; classify_inputs.main()"
python3 build_matches.py
python3 export_full_ground_truth.py
```

**This closes the 4-step `match_candidates`-parity plan**: schema/
ingestion (1), signal/capping logic (2), the actual producer (3), and
now end-to-end verification against real Python output (4) are all
done and confirmed exact — on a real machine, against real current
data, not just in this sandbox.

## Frontend-anchor review flag (2026-09-29/30) — a real classification bug, found and fixed

Reported directly from the shortlist output: a demand naming
"Angular,Java,React.js" was matching 100% (Strong) against a plain
Java/SQL employee, and independently flagged by MAS Mapping Check as
disagreeing. Root cause: `classify_demand_row()`'s Java/.NET anchor
branch (`DemandClassifier` here) fires unconditionally whenever
"Java"/".NET" is present, even when a frontend framework (React/Angular/MEAN) is also named and no backend framework
(Spring/ASP.NET) confirms it — unlike the mobile-anchor branch just above
it, which correctly demotes Java/.NET when iOS/Android is present. Affects
312/899 demand rows, 32 distinct skill-cluster patterns.

**First fix attempt (rejected):** gating persona routing itself on
framework confirmation reclassified ~180 legitimately Fullstack Java/.NET
demands to Frontend — including one whose Additional Request text
literally said "no GUI experience needed," and one skill-cluster text ("Devops,Java Full Stack,React.js") tagged Front
End on one real job
requisition and Full Stack on another by different humans. Proof the
ambiguity lives in the source data, not resolvable by any token rule
either way.

**Fix landed:** anchor routing reverted to unconditional (Java/.NET always
wins as anchor, same as before this bug was ever found) — persona
assignment never silently changes. Instead, a new standalone reviewer-only
flag mirrors the existing `mas_mapping_check()`/`masMappingCheck()`
pattern exactly — "checked, not used to match," surfaced for reviewer
judgment, never a routing input:

- Python: `frontend_anchor_check()` in `classify_inputs.py`, producing
  `frontend_anchor_flag`/`frontend_anchor_note` columns.
- Java: `RefreshLogic.frontendAnchorCheck()`/`frontendAnchorNote()`, wired
  into `classifyDemand()` the same way `masMappingCheck()` already was.

Flows through to a new "Frontend Anchor check" column in the Excel
deliverable (`write_shortlist_workbook.py`), persisted on
`demand_enriched.frontend_anchor_flag` (`V5__frontend_anchor_flag.sql`,
additive, same pattern as V2/V3/V4), and wired into
`RefreshService`/`DemandEnriched` the same way `masMappingMismatchFlag`
already was.

**Verification-as-forcing-function**: `RefreshLogicVerification.verifyDemand()`
now asserts Java's `frontendAnchorFlag` matches Python's
`frontend_anchor_flag` from the regenerated ground truth — so if the Java
port is ever missed on a future change, this harness fails loudly instead
of relying on anyone remembering. This was deliberate: an earlier version
of this fix appended a review note to `status`/`classification_note`
instead, which turned out to be invisible in the actual Excel deliverable (`write_shortlist_workbook.py` never reads
that field) — a dedicated
flag/column pair with a verification assertion was chosen specifically so
this can't happen silently again.

**Also fixed along the way (unrelated pre-existing gap, surfaced by
actually running this harness to completion for the first time)**:
`RefreshLogicVerification.verifySupply()`/`verifyDemand()` were comparing _every_ classified row — including
unclassified/data-issue ones — against
`full_supply_classified.csv`/`full_demand_classified.csv`, which
`export_full_ground_truth.py` deliberately filters to `persona.notna()`
rows only. Fixed by skipping unclassified rows in the Java-side
comparison, matching Python's own filter.

**Verified PASS on a real machine (2026-09-30)**, against a fresh
regeneration of both the classified CSVs and the ground-truth fixtures:

```
Supply classification: 239/239 classified rows match Python exactly, row counts MATCH -> PASS
Demand classification: 900/900 classified rows match Python exactly, row counts MATCH -> PASS
Hash stability (same file read twice, supply): PASS
RESULT: PASS — RefreshLogic matches classify_inputs.py exactly.

Java produced 2586 post-cap rows; ground truth has 2586 rows.
RESULT: rows missing from Java=0, extra rows in Java=0, field mismatches on matched rows=0
RESULT: PASS — Java's post-cap match_candidates output matches build_matches.py's data/match_rows.csv exactly.
```

## A real limitation of this handoff, stated plainly

**This sandbox's network egress does not allow Maven Central**
(`repo.maven.apache.org` — confirmed 403 via the environment's proxy
policy, not something to route around), so all Java verification in this
sandbox uses plain `javac` against a local stub for the one Spring
`@Service` annotation `MatchingService` needs — never Maven. **This has
since been confirmed on a real machine:** `mvn test-compile` succeeded
against real Maven Central (2026-09-28), and re-running
`ParallelRunVerification` there (real Spring dependency, not a stub)
reproduced the identical 0-mismatch result. `FullPersonaVerification`
(covering Frontend-Frontend and .NET-.NET, added the same day) has only
been run in this sandbox so far with the stub — worth re-running on a
real machine too, though there's no reason to expect a different result
given the identical toolchain already proved out for the other harness.

## Verification — Fullstack Java only (2026-09-27, superseded in scope by the full-persona result below, kept for history)

`verification-fixtures/` holds CSVs exported by
`export_fullstack_java_ground_truth.py`, which imports and calls the
real, unmodified `classify_inputs.py`/`build_matches.py` functions
against the real `AFD-Supply.xlsx`/`Demand.xlsx` data — it does not
re-derive what Python "should" do, only records what it actually did.

`ParallelRunVerification.java` re-classifies every Fullstack Java supply
and demand row from its raw text using the Java port, and re-computes
every pairing's skill/band/location signal and overall tier, then diffs
both against the exported ground truth.

Result on the real data:

```
Loaded: 118 supply rows, 599 demand rows, 70682 ground-truth pairing rows.
Supply classification: 118/118 match Python exactly.
Demand classification: 599/599 match Python exactly.

Pairing rows checked (skill quality != no_relation on Java side): 70682 / 70682 ground-truth rows
Tier mismatches: 0
Signal quality mismatches (skill/band/loc): 0
Java-computed tier distribution: {Excluded=50717, Good=11096, Strong=989, Weak=7880}

RESULT: PASS — Java port matches Python exactly on Fullstack Java.
```

Every classification and every pairing signal/tier matched Python
exactly — 0 mismatches across all 70,682 real Java-Java pairing rows.

### Reproducing this

```bash
# 1. Regenerate the ground-truth CSVs (only needed if the source Excel
#    or the Python engine has changed since verification-fixtures/ was
#    last exported):
cd /path/to/bench-match-cli
python3 export_fullstack_java_ground_truth.py   # requires input/AFD-Supply.xlsx, input/Demand.xlsx

# 2. Compile the matching package + harness (no Maven needed — this
#    part has zero real external dependencies beyond one annotation):
cd /path/to/bench-match-webapp
javac -d out src/main/java/com/hcltech/benchmatch/matching/*.java \
             src/test/java/com/hcltech/benchmatch/matching/ParallelRunVerification.java
# (MatchingService.java's @Service import needs spring-context on the
#  classpath to compile as-is once Maven Central is reachable; in an
#  environment without it, drop the annotation+import temporarily or
#  compile against a stub, as this handoff did.)

# 3. Run:
java -cp out com.hcltech.benchmatch.matching.ParallelRunVerification verification-fixtures
```

## Verification — all classified persona pairings (2026-09-28)

`export_full_ground_truth.py` extends the same approach to every
classified supply/demand row (not just Fullstack Java), calling the same
real, unmodified Python functions. **Note:** verify against
`cli_pkg/bench-match-cli/engine.py`/`build_matches.py` specifically —
this session briefly consulted a stale duplicate copy at `/home/claude/`
during this work and it produced an incorrect finding (see
`MatchingService`'s class Javadoc and the requirements doc's 2026-09-28
update for the correction). `FullPersonaVerification.java` re-classifies
every row and re-computes every pairing, broken out by (employee-persona x demand-persona) so a regression in one
pairing type
can't hide in an aggregate pass/fail:

```
Loaded: 239 supply rows, 899 demand rows, 86837 ground-truth pairing rows.
Supply classification: 239/239 match Python exactly.
Demand classification: 899/899 match Python exactly.

Frontend x Frontend: 7254/7254 checked, wrongly-no_relation=0, tier mismatches=0, quality mismatches=0 -> PASS
Fullstack .NET x Fullstack .NET: 8901/8901 checked, wrongly-no_relation=0, tier mismatches=0, quality mismatches=0 -> PASS
Fullstack Java x Fullstack Java: 70682/70682 checked, wrongly-no_relation=0, tier mismatches=0, quality mismatches=0 -> PASS

RESULT: PASS — Java port matches Python exactly across all classified persona pairs.
```

Reproduce the same way as `ParallelRunVerification` above, substituting
`export_full_ground_truth.py` and `FullPersonaVerification` for the
Fullstack-Java-only script and class, and `full_*.csv` for `fj_*.csv`.

## Suggested next steps for whoever picks this up

1. ~~Confirm `mvn compile`/`mvn test` succeed in a real environment.~~ Done (2026-09-28).
2. ~~Port `skill_signal`'s Frontend-Frontend branch, verify against Frontend and .NET.~~ Done (2026-09-28) — see above.
3. ~~Write a `RefreshService` that ingests the real Excel files via Apache
   POI, applying the hash-diff/upsert/soft-delete logic.~~ Implemented (2026-09-28) — see the RefreshService section
   above. **Still needs a
   real run**, both of `RefreshLogicVerification` (against real POI) and
   of `RefreshService` itself against a real database (Postgres, or H2 via
   a `@SpringBootTest` — this project already depends on H2 in test
   scope). The remaining reviewer-workflow JPA entities (`users`/`supply_overrides`/`demand_review_state`/
   `demand_candidate_decisions`/`decision_history`) are still not written
   — still out of scope for this slice, and the reason `needs_reattention`
   isn't wired up yet (see gap #1 above).
4. Build the REST controllers and Vaadin views on top of the now-trusted
   service layer. **RefreshController** exists but is a minimal trigger,
   not this.
5. Formalize the parallel-run comparison as an ongoing CI check (not a
   one-off script), covering every persona, for the full duration of
   the parallel-run window described in the requirements doc.
6. Re-run `FullPersonaVerification` on a real machine (with real Maven,
   not this sandbox's stub) — expected to match, but not yet done.
7. ~~Run `RefreshLogicVerification` on a real machine.~~ Done (2026-09-30)
   — see the Frontend-anchor review flag section above (also fixed a
   pre-existing unclassified-row comparison bug in the harness itself,
   found in the process). **Still open**: run `RefreshService` end to end
   against a real (or H2 test) database and spot-check a handful of rows
   by hand — the classification-logic port has a proven track record of
   matching Python exactly, but the persistence half (upsert/soft-delete/
   hashing, and the `PersonaCatalog` get-or-create logic) has never been
   run at all yet, in any environment.
8. Update the reviewer-facing docs/UI (once a UI exists) to surface the
   new "Frontend Anchor check" flag the same way MAS Mapping Check is
   surfaced — it's persisted on `demand_enriched.frontend_anchor_flag` but
   nothing beyond `write_shortlist_workbook.py`'s Excel column displays it
   yet.

## AFD Status case-sensitivity fix ported from Python (2026-09-30)

While web app work is paused (see the requirements doc's "Pause on Web
App — Focus Shifted to Python Engine Precision" section), a real bug fix
made in `classify_inputs.py` on the same day was ported here too, since
letting the two implementations drift apart on a correctness fix — even
during a pause — is exactly the kind of gap the parallel-run plan exists
to prevent.

**The bug:** a real refreshed `AFD-Supply.xlsx` export started writing
`"Blocked/Proposed for opportunity"` (lowercase "o") instead of
`"...Opportunity"`. `RefreshLogic.java`'s Phase 1 scoping filter matched
`AFD Status` against `Engine.PROPOSABLE_STATUSES` with a plain
`List.contains(...)` — an exact, case-sensitive comparison — so every row
carrying the new casing was silently dropped, identical in shape to the
Python bug (see the requirements doc for the full writeup and the
119/268-employees/44% impact figure measured against the Python side;
this Java path was never run against the affected data, so no equivalent
Java-side impact count exists).

**Fix:** `Engine.java` gained a case/whitespace-normalized
`isProposableStatus(String)` helper (mirroring `classify_inputs.py`'s
`.strip().lower()` fix exactly), and `RefreshLogic.java`'s scoping filter
now calls that instead of `PROPOSABLE_STATUSES.contains(...)` directly.
`PROPOSABLE_STATUSES` itself is unchanged and still public, for any
display/reference use — only the comparison is normalized, never the
stored/displayed value.

**Verified:** `Engine.java` + `RefreshLogic.java` compile cleanly
standalone with plain `javac` (no Spring/JPA dependency in this slice, so
no Maven Central needed to check this). A direct unit-style check against
the exact failure case confirms: lowercase `"...for opportunity"` → now
proposable; the original uppercase casing → still proposable (no
regression); a genuinely non-proposable status (`"LWD Finalized"`) → still
correctly excluded; `null` → still correctly excluded. **Not yet run**
against a real database or the real refreshed Excel files in this
environment — same caveat as everything else in this codebase that
doesn't explicitly say "on a real machine." Worth a real `RefreshService`
run against the new `AFD-Supply.xlsx` once web app work resumes, to
confirm the Java-side population count also lands at 243 the way the
Python side did.

## MAS Mapping scope gate added — demand-supply-mapping decision (2026-10-01)

Follow-up to the AFD Status fix above, from the same root cause: a field assumed to be clean
turned out not to be once the real data widened. This time it's `Demand.xlsx`'s "MAS Mapping"
column.

**The decision.** `MAS Mapping` names a broad category — Full Stack, Front End, Tpm (Technical
Project Management), Testing, Polyglot, Devops & Sre, Integration, Mainframe, Architects &
Emerging. Supply was already scoped to Full Stack/Front End only (`filterPhase1Supply()`, via
`Engine.PHASE1_MAS_MAPPING`). Demand had no equivalent gate at all — every MAS Mapping value flowed
straight into skill-cluster-text classification, because `Demand.xlsx` was previously always
pre-filtered upstream to Full Stack/Front End before it ever reached this tool. Once the real
export widened to include the other categories too (intentionally — they'll be needed for Phase
2), that assumption broke: 32 "Java,Microservices,React.js" demand rows tagged `MAS Mapping='Tpm'`
were being classified `Fullstack Java` purely from skill-cluster text, even though a Technical
Project Manager requisition isn't an engineering role at all — a category mismatch, not a
skill-confidence question. Decided: Full Stack and Front End stay active for Phase 1; everything
else is gated out of active matching on **both** sides, via a new `mas_mapping_categories` DB table
(`V7__mas_mapping_categories.sql`) instead of a hardcoded list — so the active set can change by
flipping a row's status, not by a code change.

**Important, and written directly into the migration/table/JSON so it isn't lost later:**
activating a category is necessary but not sufficient. It only stops the gate from excluding that
category's rows — it does not create a persona, matching rules, or an eligible supply pool for it.
Flipping `Tpm` active without building those would just reopen the exact bug this gate exists to
prevent.

**What changed:**

- `Engine.PHASE1_MAS_MAPPING` — was `public static final List.of("Full Stack", "Front End")`; is
  now a settable field (`Engine.setPhase1MasMapping(List<String>)` / `getPhase1MasMapping()`), with
  the same two values kept as the fallback default so `RefreshLogicVerification`'s standalone
  harness (no DB) keeps working unchanged. A `Engine.PHASE1_MAS_MAPPING` static-field compatibility
  shim (a tiny `AbstractList` delegating to the live value) means every existing
  `Engine.PHASE1_MAS_MAPPING.contains(...)` call site needed zero changes.
- New: `entity/MasMappingCategory.java`, `repository/MasMappingCategoryRepository.java`,
  `refresh/MasMappingScope.java` (a thin `@Component`, same shape as the existing `PersonaCatalog`)
  — reads the active categories from the DB and calls `Engine.setPhase1MasMapping()`.
  `RefreshService.refreshSupply()`/`refreshDemand()` both now call `masMappingScope.applyToEngine()`
  first, so a category flipped in the table takes effect on the very next refresh, no restart
  needed.
- `RefreshLogic.classifyDemand()` — new MAS Mapping scope gate, mirroring
  `classify_inputs.py`'s `scope_mask` exactly, including running *after* the existing bad-band check
  so the two can't silently disagree (an out-of-scope row always wins, same as Python). Excluded
  rows are not dropped — same treatment as bad-band rows — they get `persona = null` and an explicit
  `OUT OF PHASE 1 SCOPE` status (`classificationStatus = "out_of_scope"`), staying visible in the
  data.
- `RefreshLogic.masMappingCheck()`/`masMappingNote()` — narrowed. These used to also fire for any
  MAS Mapping outside Full Stack/Front End ("outside Phase 1 values"). That branch is now dead code
  by construction (the new scope gate already clears `persona` for exactly those rows, which these
  methods already treat as "nothing to compare against") — removed to avoid double-reporting the
  same thing two different ways. Mirrors the identical simplification made in `classify_inputs.py`.

**Verified:** standalone `javac` compile of `Engine.java` + `RefreshLogic.java` (still
Spring/JPA-free, same as the AFD Status fix) is clean, plus a direct sanity test (`TestScope.java`) confirming: the Tpm
row on the real flagged phrase gets `persona = null` +
`OUT OF PHASE 1 SCOPE` status; a Full Stack row with the same phrase still classifies
`Fullstack Java`; calling `setPhase1MasMapping(["Full Stack","Front End","Tpm"])` and re-running
correctly lets the Tpm row flow into skill-text classification again (demonstrating the
"activation re-admits, doesn't itself fix" point above); and `setPhase1MasMapping([])` throws
rather than silently excluding everything. All five checks passed.

**Follow-up (same day): the migration itself is now verified against real Postgres, not just
read.** Installed a throwaway local PostgreSQL 16 in this sandbox and ran `V1` through `V7` against
it, in order — all clean. Directly confirmed: seed data is exactly as intended (Full Stack/Front
End active, the rest inactive, Tpm's note present); the `CHECK (status IN ('active','inactive'))`
constraint rejects a bogus value; the `category` primary key rejects a duplicate; and
`UPDATE mas_mapping_categories SET status='active' WHERE category='Tpm'` — the real "revisit later"
action — works and reads back correctly. Instance torn down afterward.

**Still not verified, confirmed to be a hard sandbox limit, not a shortcut:** deliberately tried
`mvn dependency:resolve` in this sandbox to see if the full Spring Boot build could close this gap
— Maven Central returns an explicit `403 Forbidden` through this sandbox's proxy, so there is no
way to compile or run `MasMappingCategory`/`MasMappingCategoryRepository`/`MasMappingScope` here,
full stop. What's left unverified is now narrower: the migration/table itself is proven correct;
only the Spring/JPA wiring on top of it (the `@Entity` mapping under a real Spring context,
`RefreshService` actually invoking `MasMappingScope.applyToEngine()` at the right moment) needs a
real machine with Maven access. Recommend running `mvn spring-boot:run` (or the test suite) there
and a real `refreshDemand()`, confirming the Java-side classified-row count also lands at 900.
