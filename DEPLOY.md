# Deploying bench-match-webapp to Render (free tier)

Goal: get a real, reachable URL so you can click around and feel the actual
Vaadin latency — not a judgment on whether to keep Render long-term. See
`demand-supply-mapping-requirements.md` for the honest trade-offs on free
tiers and cold starts.

## What changed in this repo to make this possible

- `OLDapplication.yml` — datasource URL/username/password and `server.port`
  now read from environment variables (`SPRING_DATASOURCE_URL`/
  `SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD`/`PORT`), falling
  back to the old local-Postgres defaults when those env vars aren't set —
  so nothing changes for local `mvn spring-boot:run`.
- `pom.xml` — added a `production` Maven profile (`-Pproduction`) that turns
  on Vaadin's production frontend build. Deploying without it still works,
  but serves the slower, unminified dev-mode bundle — not what you want to
  judge "does this feel smooth" against.
- `Dockerfile` / `.dockerignore` — a standard two-stage build (Maven+Node to
  build, bare JRE to run). Works on Render, Railway, Fly.io, or any plain
  VM with Docker — you're not locked into one platform by using this.
- `render.yaml` — a Render "Blueprint" that tries to provision the web
  service + a free Postgres together in one step. See the caveat below —
  it may need one manual tweak, the fallback is simple.

## Known fix already applied (2026-10-01)

First real deploy attempt failed at the Docker build step: `mvn: not found`
(exit 127), from the Maven image the Dockerfile originally used (`maven:3.9-eclipse-temurin-21`) not having `mvn` on its
PATH in that build
— never root-caused further, since this repo's dev sandbox has no Docker
daemon to test image tags against directly. Fixed by switching the build
stage to a plain, well-known JDK image (`eclipse-temurin:21-jdk-jammy`)
with Maven installed via `apt-get install maven`, instead of depending on a
third-party Maven image's internal layout. If you still hit a build
failure, the error text itself will say what's missing — this is the kind
of thing that's genuinely faster to fix by reading the actual error than
to guess at from here.

## Known fix already applied (2026-10-01, #2 — OutOfMemoryError on upload)

After the build fix above, a real deploy succeeded, but uploading an actual
`Demand.xlsx` through `/upload` crashed with
`java.lang.OutOfMemoryError: Java heap space`, traced to
`ExcelSheetReader.readFirstSheet` inside Apache POI's DOM-based `XSSFWorkbook`
parsing. Root cause: Render's free tier gives the whole container ~512MB of
RAM, and POI's DOM/xmlbeans model loads an entire workbook as an in-memory
XML tree — often several times the raw file size — while the JVM's default
container-aware heap sizing left an even smaller slice of that 512MB
actually usable as heap.

**Fix applied:** the `Dockerfile`'s `ENTRYPOINT` now passes
`-XX:MaxRAMPercentage=75.0` so the JVM claims a much larger share of the
container's RAM as heap, plus `-XX:+ExitOnOutOfMemoryError` so a future OOM
kills and restarts the process cleanly instead of leaving it in a half-dead
state.

**Honest caveat:** this is a mitigation, not a guaranteed fix. It wasn't
possible to verify against the real `Demand.xlsx` in this sandbox (no
Docker daemon, no access to the actual file size/complexity). If a large
enough file still OOMs after this change, the two real fixes are:

1. **More RAM** — move off Render's free 512MB tier to something like an
   Oracle Cloud Always Free VM (already recommended earlier for avoiding
   cold-starts too — it has no comparable RAM ceiling on the free shape).
2. **Rewrite `ExcelSheetReader.java`** to use Apache POI's streaming/SAX
   reading API (`XSSFReader` + an event-based row handler) instead of
   `XSSFWorkbook`/`XSSFRow`/`XSSFCell`. This reads the workbook row-by-row
   without building the full DOM tree in memory, and is the standard fix
   for POI OOM issues on large files — but it's a real code change to the
   ingestion path, not yet done, and would need the same scrutiny as any
   other logic change in this project (no behavior drift from the current
   row-reading semantics).

If you hit OOM again, the fastest next step is checking the actual size of
the `Demand.xlsx`/`AFD-Supply.xlsx` files being uploaded — if they're small (a few thousand rows), option 1 or 2 should
comfortably fix it; if they're
genuinely large (tens of thousands of rows or more), option 2 is probably
needed regardless of RAM.

## Known fix already applied (2026-10-02, #3 — low-memory Excel reader, switchable)

Option 2 above is now actually done, as a second reader kept *alongside*
the original rather than replacing it — see
`refresh/excel/FastExcelRowReader.java`, `refresh/excel/PoiExcelRowReader.java`,
and `refresh/excel/ExcelRowReaders.java`. The new reader uses
`org.dhatim:fastexcel-reader`, which streams rows instead of building
POI's in-memory XML tree, and was written directly against that library's
real source (fetched from GitHub, not from memory).

**Why both stay:** the real `Demand.xlsx`/`AFD-Supply.xlsx`/`AAFD-Supply.xlsx`
were checked with a quick Python/openpyxl script before writing this —
zero formula cells and zero genuine date-typed cells in any of them, which
is good news (it means the two readers' one real documented behavioral
difference, how a *formula* cell's number formatting is handled, can't
currently be exercised by this project's actual data). But "currently"
is the operative word, and this wasn't verified by an actual `mvn compile`
in this sandbox (Maven Central is blocked here, same as the two fixes
above) — so the original POI reader stays the default, and the new one is
opt-in.

**To switch:** set the `EXCEL_READER_IMPL` environment variable to
`fastexcel` (on Render: the web service's Environment tab, no code change
or redeploy-from-source needed) or `poi` to go back — no value or an
unrecognized one falls back to `poi`. Nothing else in the app needs to
change; `ExcelSheetReader.readFirstSheet(Path)` (what `RefreshService` and
everything else already calls) delegates to whichever one is active.

**Before switching in production:** run the comparison tool against your
real files first —
`refresh/excel/ExcelReaderComparison.java` (in `src/test`) runs both
readers against the same file and reports every cell where they disagree.
Its own header has the exact `javac`/`java` commands (needs a real machine
with Maven Central reachable, same as `RefreshLogicVerification`):

```
cd bench-match-webapp
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
javac -d out -cp "$(cat cp.txt)" \
  src/main/java/com/example/benchmatch/refresh/excel/*.java \
  src/test/java/com/example/benchmatch/refresh/excel/ExcelReaderComparison.java
java -cp "out:$(cat cp.txt)" com.example.benchmatch.refresh.excel.ExcelReaderComparison \
  AFD-Supply.xlsx Demand.xlsx AAFD-Supply.xlsx
```

If it reports `IDENTICAL` for your real files, switching is low-risk. If
it reports differences, read them before switching — they're almost
certainly in a formula or error cell, which is exactly where
`FastExcelRowReader`'s own javadoc says its fidelity to the original is
weakest.

## Steps

1. **Push this repo to GitHub** (a new repo is fine, private is fine —
   Render just needs to be able to clone it).

2. **Sign up at [render.com](https://render.com)** (free, no card needed for
   the free tier as of this writing — Render has changed this before, so if
   it asks for one, that's current Render policy, not something wrong on
   your end).

3. **Try the Blueprint first:** New → Blueprint → connect the repo → Render
   reads `render.yaml` and proposes a web service + a Postgres database →
   Apply.
    - **If the database connection doesn't come up clean** (the web service
      fails to start because `SPRING_DATASOURCE_URL` isn't a valid JDBC
      URL): Render's "connection string" field doesn't always include the
      `jdbc:` prefix Spring Boot needs. Fix: open the Postgres instance's
      "Info" page, copy the **Host**, **Port**, and **Database** values, then
      on the web service's **Environment** tab set
      `SPRING_DATASOURCE_URL` by hand to:
      ```
      jdbc:postgresql://<host>:<port>/<database>
      jdbc:postgresql://dpg-dav7ahfpn0mc73agsdr0-a:5432/bench_match_db_osa9
      postgresql://bench_match_db_osa9_user:lX6phRf8WtPoEwTYWvODXhwAdnkTJuoK@dpg-dav7ahfpn0mc73agsdr0-a/bench_match_db_osa9
      postgresql://bench_match_db_osa9_user:lX6phRf8WtPoEwTYWvODXhwAdnkTJuoK@dpg-dav7ahfpn0mc73agsdr0-a.oregon-postgres.render.com/bench_match_db_osa9
      ```
      and set `SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD` from
      the same Info page. Save → the service redeploys automatically.

4. **If you'd rather skip the Blueprint and click through manually** (also
   fine, arguably clearer the first time):
    - New → PostgreSQL → free plan → create. Note its Host/Port/Database/
      User/Password from the Info page.
    - New → Web Service → connect the repo → Render should auto-detect the
      `Dockerfile`; if it offers a build-command/start-command form instead,
      switch the "Runtime" to Docker.
    - On the web service's Environment tab, add `SPRING_DATASOURCE_URL`
      (`jdbc:postgresql://<host>:<port>/<database>`),
      `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
    - Deploy.

5. **First build will be slow** (Maven downloading dependencies + Vaadin
   downloading its own Node.js + npm install — easily 5–10 minutes on a
   free-tier build machine). That's normal; it's a one-time cost per
   deploy, not something that recurs on every request.

6. **Open the app's URL once it's live.** The schema (`V1`–`V8`) applies
   itself automatically on first boot via Flyway — no manual migration
   step. There's no data yet, so `/upload` is where you'd load a real
   `AFD-Supply.xlsx`/`Demand.xlsx` to have something to click through, or
   just browse the empty Supply/Demand grids and the Upload page itself to
   get a feel for navigation responsiveness.

7. **On judging the UX**, per the earlier discussion: ignore how the very
   first page load feels (free-tier cold start after idle — can be
   30–50+ seconds and has nothing to do with Vaadin itself). Click around
   for a minute or two first, *then* form an opinion on responsiveness.

## What this doesn't cover

- Auth — there isn't any; anyone with the URL can use it. Fine for a
  private trial, not for leaving it up long-term.
- The free Postgres instance **expires after 90 days** on Render's free
  tier — don't put anything you want to keep in it.
- This is still the same scaffold documented in `bench-match-webapp-readme.md`
  — reviewer-workflow screens (propose/reject/override) don't exist yet, so
  what you're judging here is grid/form/upload responsiveness, not the full
  intended app.
