# Revenue Intelligence Engine

A predictive tool that finds **infrastructure refresh, modernization and managed-services
opportunities** in a bank / credit-union client base **before they enter the sales cycle**, and
tells you whether you have the delivery capacity to serve them.

This is a POC on **synthetic data**. Every client, opportunity, contract, project and workforce
record is generated. The only real data in the system is seven sourced OEM lifecycle records, each
carrying the URL it came from — the Data Sources tab lists them.

```
┌─ frontend/ ──────────┐   ┌─ backend/ ────────────────┐   ┌─ data-tools/ ─────────┐
│ React + Vite  :5173  │──▶│ Spring Boot + H2   :8080  │◀──│ Python, offline only  │
│ Recharts             │   │ Java 21, all computation  │   │ generate → validate   │
└──────────────────────┘   └───────────────────────────┘   │ → data.sql            │
      /api proxied              only LLM call site:        └───────────────────────┘
                               NarrativeService
```

---

## Two rules the whole design serves

**1. The deterministic core computes; the LLM only narrates.**

Every number you see — forecast dollars, a win rate, a coverage ratio, a headcount figure — is
produced by plain, auditable Java in `backend/.../service/`. **No LLM call ever computes a number.**

The one sanctioned LLM call site is `NarrativeService`. It takes numbers that were already computed,
asks for two or three sentences of prose, then runs the result through `GroundednessChecker`: every
numeric token in the generated text must trace back to a number that went into the prompt. If one
does not, the whole generation is discarded and a template rendered from the same data is served
instead. Every narration response carries `grounded: boolean` and `source: "llm" | "template"`, and
the UI shows both as a badge.

With no `ANTHROPIC_API_KEY` set the model is never called at all — that is a supported
configuration, not a degraded one, and it is how the app runs out of the box.

**2. Python generates and validates data. Java does everything at request time.**

Python is not in the request path in any form — no subprocess, no embedded interpreter, no sidecar.
It runs once, offline, and produces the dataset the backend loads at startup.

---

## Running it

Prerequisites: **JDK 21**, **Maven 3.9+**, **Node 18+**, **Python 3.11+**.

> On this machine JDK 25 is also installed and is first on `PATH`. Point `JAVA_HOME` at JDK 21
> before building — Spring Boot 3.4 does not support Java 25:
> ```
> $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"     # PowerShell
> export JAVA_HOME=/path/to/jdk-21                     # bash
> ```

### 1. Generate the dataset

```bash
cd data-tools
pip install -r requirements.txt
python build_dataset.py
```

Writes CSVs plus an eval report to `data-tools/output/`, and — only if every error-severity data
eval passes — `backend/src/main/resources/db/generated/data.sql`.

Deterministic in two environment variables:

```bash
POC_SEED=42 POC_AS_OF_DATE=2026-08-26 python build_dataset.py
# or: python build_dataset.py --seed 42 --as-of 2026-08-26
```

If you skip this step the backend still boots: it falls back to `db/data-fallback.sql`, a handful of
hand-written rows, and logs a warning telling you to run the generator. The Data Sources tab says
which dataset is loaded.

### 2. Backend

```bash
cd backend
mvn test                # 61 tests: boot, pipeline evals, narrative evals
mvn spring-boot:run     # http://localhost:8080
```

`mvn test` is worth running before the app: `GET /api/evals` reports the pipeline and narrative eval
layers from the last test run (see [Eval suite](#eval-suite-the-anti-hallucination-backbone)).

### 3. Frontend

```bash
cd frontend
npm install
npm run dev             # http://localhost:5173
```

The dev server proxies `/api` to `127.0.0.1:8080`, so the browser only ever talks to one origin and
there is no CORS configuration and no API base URL in the bundle.

### 4. Confirm it works

```bash
cd frontend
node scripts/check-api-contract.mjs http://localhost:5173
```

46 assertions over every field path the five pages read — that each exists, is populated, and carries
a real number. Run it against the dev server so the proxy is exercised too.

### Optional: enable the LLM narration path

```bash
export ANTHROPIC_API_KEY=sk-ant-...      # then restart the backend
```

Model and effort are configurable under `revintel.narrative` in `application.yml`; the default is
`claude-opus-5` at low effort, which is ample for two sentences over pre-computed numbers.

### Optional: look at the data

```bash
python data-tools/exploratory_analysis/run_all.py
# open data-tools/output/analysis/index.html
```

---

## The five tabs

| Tab | What it answers |
|---|---|
| **Revenue Intelligence** | Where is the money, and what is not in the CRM yet? KPI tiles, actual-vs-projected trend, forecast breakdown by five dimensions, segment × type heatmap, pipeline coverage, smoothed win rates, and signal cards with "Explain signal". |
| **Capacity Intelligence** | Can we actually deliver it? Skill demand against sustainable capacity, projected utilisation, and hiring / contractor recommendations. |
| **Executive Insights** | What if? Three what-if sliders re-running the same forecasting and capacity code, baseline-vs-scenario diffs, a groundedness-checked narrative, and a model-health panel reading `GET /api/evals`. |
| **Manager Overview** | The quick look. For a business manager who just needs to know what to act on now: which client contracts are ending inside the three-month renewal window, and the highest-value revenue-increase opportunities the engine has surfaced. Reuses figures the other tabs compute; adds no new arithmetic beyond the renewal-window join. |
| **Data Sources** | What is this built on? Row counts per table, and an honest separation of the seven real lifecycle anchors from the procedural variants derived from them. |

---

## Architecture

### Three layers, each calling only the one below

```
Controllers (controller/)    HTTP in/out only. No business logic.
      │
Services (service/)          All business logic. Forecasting, capacity, signals,
      │                      what-if, orchestration, narration.
Repositories (repository/)   All database access, via Spring Data JPA.
                             Never used directly by a controller.
```

Repositories are the only place JPQL or native SQL appears. Services never touch `JdbcTemplate`.
Controllers parse the request, call exactly one service method, and return the DTO.

### Request flow

```
Browser (:5173) ── fetch("/api/revenue-intelligence") ──▶ RevenueController
                                                                │
                                              RevenueIntelligenceOrchestrator
                                              (assembles; computes nothing)
                                                    │
             ┌──────────────────────────────────────┼───────────────────────────┐
             ▼                                      ▼                           ▼
     ForecastingService                    SignalDetectionService        CapacityService
   forecastRows() once ────────────────────────────────────────────────▶ (same rows in)
   forecastBy / heatmap / coverage / trend                              demand → hiring
             │
             ▼
        DTO records ──▶ JSON (nulls serialise cleanly, never NaN) ──▶ React
```

Narration is a separate, optional round trip: the "Explain signal" button posts a signal id, the
backend re-resolves that signal server-side, and `NarrativeService` runs the groundedness-checked
flow.

### Service map

| Service | Responsibility | Depends on |
|---|---|---|
| `ForecastingService` | Hierarchically smoothed historical win rates; pipeline and whitespace forecast rows; all revenue aggregations | Opportunity / Client / Target repositories, `SignalDetectionService` |
| `SignalDetectionService` | Pure rule and join logic — lifecycle risk with no open refresh, at-risk contracts with no expansion play, over-utilised resource groups | Repositories only |
| `CapacityService` | Skill demand, sustainable capacity, utilisation projection, hiring recommendations | **A `List<ForecastRow>` passed into its constructor** — not a Spring bean |
| `WhatIfService` | Re-runs the same forecasting and capacity code with adjusted assumptions, diffs against baseline | `ForecastingService`, `CapacityService` |
| `RevenueIntelligenceOrchestrator` | Assembles each page's payload. The only place a new service gets wired in | All of the above |
| `NarrativeService` | The only LLM call site: narrate → groundedness-check → template fallback | `GroundednessChecker` |
| `EvalsService` | Reads the three eval layers' recorded results for `GET /api/evals` | Filesystem artefacts |

### Why `CapacityService` takes forecast rows in its constructor

It is deliberately **not** a Spring bean and does **not** inject `ForecastingService`. Callers
compute the forecast rows once and hand the same list to both. That buys:

- **No duplicate computation.** A request needing revenue *and* capacity numbers forecasts once.
- **Independent testability.** `CapacityFixtureTest` exercises it from a literal list — no database,
  no Spring context. If someone changes it to fetch its own data, that test stops compiling.
- **Guaranteed consistency.** Capacity demand is a deterministic hours-per-dollar transform of *the
  exact same* forecast the revenue numbers came from, so a what-if override physically cannot reach
  one and miss the other. `WhatIfConsistencyTest.overridesReachCapacityDemandAsWellAsRevenue` guards
  it.

Workforce rows are passed in for the same reason — the class stays free of Spring and easy to
fixture. (The spec's constructor signature is about not depending on `ForecastingService`; this
keeps that property and extends it.)

### The shared economics model — and the parity test

`DeliveryEconomics.java` and `data-tools/reference_data.py` hold the **same** labour-content,
delivery-rate, resource-mix and capacity constants. The generator sizes workforce headcount off the
demand those numbers imply; Java converts forecast dollars into demand hours the same way. Nothing
at runtime notices if the two drift — the numbers stay plausible-looking and quietly stop meaning
the same thing.

So `DeliveryEconomicsParityTest` parses `reference_data.py` and compares it to `DeliveryEconomics`
value by value. Edit one side without the other and the build fails.

---

## Data layer

### Generation (`data-tools/`, offline)

`build_dataset.py` → generate → validate → write. Generation runs in strict dependency order:

```
gen_clients → gen_oem_models → gen_installed_base → gen_contracts
  → gen_projects_and_billing → gen_opportunities → gen_workforce → gen_targets
```

Two ordering choices carry weight:

- **Billing before opportunities.** Delivered projects are the ground truth; won opportunities are
  the sales record of those same projects. Generating billing *from* opportunities would make the
  two reconcile only by luck.
- **Workforce last.** Headcount is sized off `historical_quarterly_demand_hours()` — the same demand
  model the Java capacity service reads — with a deliberate 0.82–1.15 staffing jitter around the
  0.85 target utilisation. Sizing workforce independently of revenue scale is what produced the
  "hire 5–6× your current bench" bug in the reference build.

**Real-anchored OEM lifecycle data.** Seven real products with real end-of-sale / end-of-support
dates and a `source_url` (Cisco Catalyst 2960-X, HPE ProLiant DL380 Gen9, Fortinet FortiGate-60E,
NetApp FAS2650, Windows Server 2016, SQL Server 2016, VMware vSphere ESXi 6.7). `source_confidence`
is an honest statement of certainty: `vendor_official` where the vendor publishes the date directly,
`aggregator` where it varies by SKU or support contract and the value is representative.

From those anchors' lifecycle *spans*, `OEM_CATALOG` derives 15 `(oem, category, lifecycle_years,
support_tail_years)` patterns, each expanded into 3 procedural variants — 45 rows carrying
`is_real_anchor=False, source_confidence="synthetic"`. 6,000 `installed_base` rows are then sampled
across 50 clients, weighted by revenue tier (Enterprise 3.0× / Mid-Market 1.5× / SMB 0.6×). Those
three provenance fields are carried through untouched so downstream code and the evals can always
tell real from patterned.

### Loading (Spring Boot side)

- Hibernate creates the schema from the entities (`ddl-auto: create-drop`), then Spring runs
  `db/generated/data.sql`. **`spring.jpa.defer-datasource-initialization: true` is load-bearing** —
  without it `data.sql` runs before the schema exists and every insert fails.
- The generated file is referenced as `optional:`, so a missing dataset is not a startup failure.
  `DataBootstrap` then checks whether any rows landed and, if not, loads `db/data-fallback.sql` with
  a loud warning.
- **H2 is in-memory, so every restart re-loads `data.sql` fresh.** No stale state between runs, and
  no persistence between them either. That is intentional for a POC.
- The H2 console is on at `/h2-console` (JDBC URL `jdbc:h2:mem:revintel`, user `sa`, no password) and
  hard-disabled under the `prod` profile.

### Python's three separate roles

| Directory / file | Role |
|---|---|
| `generate_synthetic_data.py`, `reference_data.py` | Generation |
| `data_evals.py` | Pass/fail validation that **gates the build** |
| `exploratory_analysis/` | Charts for a human to look at. A dev aid; nothing depends on it |

---

## Eval suite — the anti-hallucination backbone

Three layers, split by language according to where the thing being checked lives.

| Layer | Runs in | Where | Checks |
|---|---|---|---|
| **Data** | Python, offline | `data-tools/data_evals.py` | 12 checks: referential integrity, value domains, provenance labelling, `real_anchors_have_source_url`, `capacity_vs_historical_demand_plausibility` |
| **Pipeline** | Java, JUnit | `backend/src/test/java/.../pipeline/` | aggregation consistency, plausibility, JSON payload safety, what-if propagation, Java↔Python constant parity, manager-overview renewal-window consistency |
| **Narrative** | Java, JUnit | `backend/src/test/java/.../narrative/` | 24 checks: `GroundednessChecker` against adversarial cases, template-fallback behaviour |

`GET /api/evals` returns the combined status, and the Executive Insights model-health panel renders
it.

**How `/api/evals` gets its numbers, and the tradeoff.** The spec allowed either running the
JUnit-backed checks live through an in-process runner, or pre-computing them and exposing a summary.
This takes the second option: `EvalsService` reads `data-tools/output/data_eval_results.json` and the
Surefire XML from the last `mvn test` run. **The cost is staleness** — change the code, reload the
page without re-running the build, and the numbers are from the previous run, which is why every
layer reports when it was produced and a layer that has never run reports `not_run` rather than
passing vacuously. The benefit is no test-harness machinery in the running app and no chance of an
eval endpoint executing tests against the live datasource.

### The three bugs these guard against

These are regression tests for real bugs found building the reference version. None of them was a
crash — all three produced confident, well-formatted, wrong numbers.

| Bug | Root cause | Fix, and the test that holds it |
|---|---|---|
| Hiring recommendations implying **5–6× current headcount** | Workforce size and opportunity revenue scale generated independently of each other | Generator sizes headcount off the same demand model the forecast reads. `data_evals.capacity_vs_historical_demand_plausibility` + `PlausibilityTest.hiringGapPlausibility`, which compares forecast demand against what each group actually delivered, so it separates "the models disagree" from "this group is understaffed and growing" |
| **Serialization errors** on numeric edge cases | Nulls and NaN-equivalents reaching the JSON boundary | Explicit null-safe DTO mapping; undefined ratios are `null`, never a sentinel. `JsonPayloadSafetyTest` serialises every payload and walks it for non-finite numbers |
| Coverage ratio showing **82,427×** and **not responding to the what-if sliders** | Dividing by a near-zero remaining target; and computing weighted pipeline from raw CRM probability instead of override-aware forecast rows | `targetAlreadyMet` returns `null` instead of dividing; weighting uses the derived win rate, and `WhatIfService` reuses the same forecast rows. `PlausibilityTest.coverageRatioNoExtremeValues` + `WhatIfConsistencyTest.whatIfOverridesMoveCoverageRatio` |

A fourth, found while building **this** version: the blended coverage ratio was folding closed
quarters that had missed their target into a forward-looking metric, dragging it from 1.30× to
1.13×. The offline `coverage_sensitivity.py` cross-check is what surfaced the discrepancy — that is
the value of a second implementation. Fixed in `ForecastingService.blendedCoverageRatio`, guarded by
`AggregationConsistencyTest.blendedCoverageIsTheForwardQuarterFold`.

---

## API

| Method | Path | Returns |
|---|---|---|
| `GET` | `/api/revenue-intelligence` | KPIs, trend, breakdowns, heatmap, coverage, win rates, signals |
| `POST` | `/api/revenue-intelligence/narrative/signal` | Narration for one signal — body `{"signalId":"..."}`; 404 on an unknown id |
| `GET` | `/api/capacity-intelligence` | Skill demand, capacity, utilisation projection, hiring recommendations |
| `GET` | `/api/manager-overview` | Quick-look KPIs, contracts ending within three months, top revenue opportunities |
| `GET` | `/api/executive/summary` | Executive KPIs, trend, scenario, narrative. Optional `winRateDelta`, `dealSizeDelta`, `targetDelta` |
| `POST` | `/api/what-if` | Baseline / scenario / delta — body `{"winRateDelta":0.15,...}` |
| `GET` | `/api/data-sources` | Row counts per table, real lifecycle anchors |
| `GET` | `/api/evals` | Combined status of all three eval layers |

Only the signal **id** is accepted for narration, never the posted numbers: the signal is re-resolved
server-side so narration is always grounded in numbers this backend computed.

---

## Frontend notes

Five tabs in `src/pages/`, one API client in `src/api/client.js`. The frontend never talks to the
database and holds no schema knowledge — it renders whatever the controllers hand it.

- **`null` is never rendered as `0`.** An undefined ratio shows an em dash. Coercing it would turn
  "we cannot say" into "it is zero".
- **Fixed categorical colour order, never cycled**, and **status colours (red / amber / green) are
  reserved** for signal severity and utilisation state — never reused as a series colour, and never
  shown without a text label beside them.
- Light and dark are both chosen: the dark steps are the same hues re-stepped for the dark surface
  and validated against it, not an automatic inversion. The theme button cycles system / light /
  dark.
- Every chart has a hover tooltip and, below it, the table of exact numbers.

---

## Explicitly out of scope

- **No forecast calibration or learning loop.** Forecasts are recomputed live on every request;
  there is no `forecast_snapshots` table and no calibration service. This is why
  `actualVsProjected` reports `null` for a closed quarter's projection instead of inventing a
  retrospective one.
- **No production OEM EOL/EOS integration** (Cisco EoX, Flexera/Technopedia, endoflife.date). The
  seven hardcoded anchors are sufficient here.
- **No subagent / slash-command / hook tooling layer.**

---

## Layout

```
backend/
  pom.xml
  src/main/java/com/cognizant/revintel/
    config/       DataBootstrap, NarrativeProperties
    controller/   Revenue, Capacity, Executive, WhatIf, DataSources, Evals
    dto/          Records serialised to JSON
    entity/       8 JPA entities
    model/        ForecastRow, Signal, Assumptions, WinRateModel
    repository/   Spring Data JPA interfaces + DataCatalogRepository
    service/      All computation, plus the single LLM call site
  src/main/resources/
    application.yml, application-prod.yml
    db/data-fallback.sql
    db/generated/data.sql        (generated; not hand-edited)
  src/test/java/com/cognizant/revintel/
    ApplicationBootTest, pipeline/, narrative/

data-tools/
  reference_data.py              Real anchors + shared economics (mirror of DeliveryEconomics.java)
  generate_synthetic_data.py     Generation
  data_evals.py                  Validation that gates the build
  build_dataset.py               CLI: generate → validate → write
  exploratory_analysis/          Dev-time charts
  output/                        CSVs, eval report, analysis charts

frontend/
  src/api/, src/components/, src/lib/, src/pages/, styles.css
  scripts/check-api-contract.mjs
```
