# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`README.md` is thorough (395 lines) and is the reference for architecture prose, the API table, and
the frontend conventions. This file covers what it does not: environment quirks, the exact commands,
and the invariants stated as rules.

## Environment quirks — read before running anything

- **`JAVA_HOME` must point at JDK 21.** Spring Boot 3.4 does not support Java 25, and a newer JDK
  may well be first on `PATH` — check before assuming `mvn` picked the right one
  (`README.md:50-55`). PowerShell: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"` · bash:
  `export JAVA_HOME=/path/to/jdk-21`
- **If `git` fails with *"detected dubious ownership in repository"*, run it through the PowerShell
  tool instead of the Bash tool.** Happens when the working tree's owner differs from the session
  user (e.g. a tree owned by `BUILTIN\Administrators`). Don't "fix" it by adding a `safe.directory`
  entry to someone's global git config — just switch shells.
- **There is no pytest.** `data-tools/requirements.txt` is `pandas`, `numpy`, `Faker`,
  `matplotlib`. `data_evals.py` *is* the Python test suite. Never propose a pytest invocation.
- **The frontend has no test or lint script.** `frontend/package.json` has only `dev`, `build`,
  `preview`. Its only guard is `scripts/check-api-contract.mjs`, which needs a running stack.

## Commands

```bash
# Data pipeline (offline). Generate -> validate -> write data.sql.
python data-tools/build_dataset.py                       # full run; writes data.sql if evals pass
python data-tools/build_dataset.py --skip-sql            # generate + validate, leave data.sql alone
python data-tools/build_dataset.py --seed 7 --as-of 2026-01-15
python data-tools/data_evals.py                          # re-run evals over CSVs already written

# Backend (JDK 21)
cd backend && mvn test                                   # 61 tests: boot + pipeline + narrative
cd backend && mvn -Dtest=PlausibilityTest test           # one class
cd backend && mvn -Dtest=WhatIfConsistencyTest#whatIfOverridesMoveCoverageRatio test
cd backend && mvn -Dtest='com.cognizant.revintel.pipeline.*Test' test    # one eval layer
cd backend && mvn spring-boot:run                        # :8080

# Frontend
cd frontend && npm install && npm run dev                # :5173, proxies /api to 127.0.0.1:8080
node frontend/scripts/check-api-contract.mjs http://localhost:5173   # 42 assertions, needs both up

# Optional: charts for a human to look at
python data-tools/exploratory_analysis/run_all.py
```

`POC_SEED` (default 42) and `POC_AS_OF_DATE` (default today) are honoured as environment variables.
Both `build_dataset.py` and `data_evals.py` exit 1 when any error-severity check fails.

## Hard rules

- **Never hand-edit `backend/src/main/resources/db/generated/data.sql`.** It is generated, and it is
  checked in deliberately (see the comment in `.gitignore`) so a fresh clone boots without Python.
  Regenerate it with `build_dataset.py`.
- **Never pass `--force` to `build_dataset.py`.** It writes `data.sql` even when error-severity evals
  fail; its own help text says "Don't." The refusal is the design — a stale-but-valid dataset beats a
  fresh nonsensical one.
- **`DeliveryEconomics.java` and `data-tools/reference_data.py` move together or not at all.**
  `DeliveryEconomicsParityTest` parses the Python and compares value by value, so editing one side
  alone fails the build. That is intentional.
- **No class other than `NarrativeService` may touch the Anthropic SDK.** Legitimate `com.anthropic`
  references exist in exactly four places: `NarrativeService`, `pom.xml`, `application.yml` /
  `NarrativeProperties` (config plumbing only), and a string assertion in `NarrativeFallbackTest`.
- **`AsOfProvider` is the single source of "now".** `AsOfProvider.java:32` holds the only
  `LocalDate.now()` in the Java tree. A fresh one in a service makes tests flaky in the first week of
  a quarter.
- **`null` is never `0`.** An undefined ratio returns `null` all the way to the UI, which renders an
  em dash. `DeliveryEconomics.money`/`rate` collapse NaN/Infinity to zero at the boundary; genuinely
  undefined values stay null. Coercing them turns "we cannot say" into "it is zero".

## Architecture: the parts that need several files to see

**Two rules the design serves.** All computation is deterministic Java in
`backend/.../service/`; the LLM only narrates already-computed numbers. Python runs offline only —
never a subprocess, sidecar, or embedded interpreter in the request path.

**The narration gate.** `NarrativeService` is the one LLM call site. Every generation flows through
`narrate()`: flatten the prompt data to ground truth → call the model → `GroundednessChecker.check()`
→ on failure discard the *whole* output and render a template over the same data. Three paths reach
the template (no API key, transport error, failed check) and each sets a distinct `reason` the UI
surfaces. With no `ANTHROPIC_API_KEY` the client is never constructed — that is the default and
supported configuration, not a degraded one.

`GroundednessChecker` accepts a numeric token only if it traces back to a value that went into the
prompt, under any plausible rendering. Its regex and tolerance constants carry the specific
near-misses that shaped them in their Javadoc — read those before touching either.

**Compute once, pass the rows in.** `CapacityService` is deliberately **not** a Spring bean and
injects nothing; its constructor takes `(List<ForecastRow>, List<Workforce>)`. Callers compute the
forecast once and hand the same list to both revenue and capacity, so a what-if override cannot reach
one and miss the other. `WhatIfService.metrics()` is the canonical sequence; the same shape appears in
`RevenueIntelligenceOrchestrator.capacityIntelligence()` and `executiveSummary()`. Overrides are
applied in exactly one place — `ForecastingService.forecastRows(Assumptions)` — and weighting uses the
smoothed historical win rate, **not** `opportunity.probability`. Guarded by `CapacityFixtureTest`
(which stops compiling if the class starts fetching its own data) and
`WhatIfConsistencyTest.overridesReachCapacityDemandAsWellAsRevenue`.

**Workforce sizing is derived, not chosen.** `gen_workforce` sizes headcount off
`historical_quarterly_demand_hours()` — billed hours per resource group over completed quarters — with
a deliberate 0.82–1.15 staffing jitter around 0.85 target utilisation. It runs *after*
`gen_projects_and_billing` for that reason. Java's `CapacityService.hiringRecommendations()` closes the
loop: permanent hires close the gap against **average** demand, contractors absorb only the peak above
that. Sizing FTE off the peak is the "hire 5–6× your bench" bug.

**Three eval layers, split by where the checked thing lives.**

| Layer | Lives in | A new check goes |
|---|---|---|
| Data | `data-tools/data_evals.py` | A new function **appended to the module-level `CHECKS` list** — a function not in `CHECKS` never runs. `ERROR` severity gates `data.sql`; `WARNING` does not. |
| Pipeline | `backend/src/test/java/.../pipeline/` | A test there |
| Narrative | `backend/src/test/java/.../narrative/` | A test there |

`GET /api/evals` reads **recorded** runs, not live ones: `EvalsService` parses
`data-tools/output/data_eval_results.json` and the Surefire XML from the last `mvn test`. It routes
Surefire files to a layer by the `.pipeline.` / `.narrative.` segment of the FQCN and **skips
anything else**, so a test placed in another package passes `mvn test` and is invisible to the
endpoint. A layer with no artefact reports `not_run` rather than passing vacuously.

**Bug fixes are paired with permanent guards, documented on both sides.** The fixed production
class's Javadoc states the bug in past tense with the concrete wrong number and names its guard; the
guard's Javadoc or docstring states root cause → fix → what a future failure means. Thresholds are
*derived from stated inputs*, and the derivation lives in the constant's Javadoc — so relaxing a
threshold is almost never the correct fix. `README.md` keeps a *Bug | Root cause | Fix, and the test
that holds it* table for the four documented bugs.

## Agentic tooling in `.claude/`

Five subagents and three slash commands. Most of them are read-only with respect to application code
and generated data — `architecture-reviewer`, `pipeline-auditor` and `oem-eol-researcher` write
nothing outside `.claude/proposals/`, and `data-refresher` only writes what `build_dataset.py` itself
writes. The one deliberate exception is `data-processing-designer` (see below), which is empowered to
rewrite the `data-tools/` pipeline.

- `architecture-reviewer` — reviews service-layer diffs against the invariants above
- `pipeline-auditor` — root-causes eval failures from the real artefact paths
- `oem-eol-researcher` — researches OEM lifecycle dates and writes a proposal to
  `.claude/proposals/`. Carries `EnterWorktree` so that write actually lands; the hard boundary in
  its instructions still forbids touching `reference_data.py` or anything under `data-tools/`,
  `backend/`, `frontend/`
- `data-refresher` — regenerates the dataset without bypassing the eval gate
- `data-processing-designer` — inspects the data, decides how it should be processed for revenue
  forecasting, and **creates/replaces the Python pipeline under `data-tools/`** (and, only in
  lockstep with `reference_data.py`'s economics block, `DeliveryEconomics.java`). It authors
  deterministic Python and lets the eval gate decide correctness — it never computes a value itself.
  It is the only agent that writes into `data-tools/`; it still never passes `--force`, never
  hand-edits `data.sql`, and touches nothing else in `backend/` or `frontend/`
- `/run-evals`, `/regenerate-data`, `/design-data-processing`
- `.claude/skills/oem-lifecycle-refresh/` — the OEM anchor refresh procedure

`.claude/proposals/` is committed, not ignored — a proposal is a reviewable artefact a human applies
by hand. One already exists (`oem-anchors-2026-08-27.md`).

## Known gotchas

- **`revintel.as-of-date` is not present in `application.yml`.** It exists only as the `:` default in
  `AsOfProvider`'s `@Value`. If a dataset is generated with a non-default `--as-of`, that key must be
  added and set to match, or Java and the data disagree about which quarters are history — silently.
- **`data-tools/output/` is gitignored** and may not exist in a fresh clone, so `/api/evals` reports
  the data layer as `not_run` until `build_dataset.py` has run. Same for the pipeline and narrative
  layers until `mvn test` has run.
- **`DeliveryEconomicsParityTest` skips, rather than fails, when `data-tools/reference_data.py` is
  absent** (`@EnabledIf`). Skips count as *warnings* in `EvalsService`, so a pipeline layer reporting
  `warnings` often means "Python source not found", not "the constants drifted".
- **Several pipeline evals assert on dataset *shape*, not just arithmetic** — e.g. that a closed
  quarter which missed target exists, and that baseline coverage is computable. A new `--seed` can
  legitimately break them; that means the seed produced a dataset the app's guarantees do not hold on.
- **`README.md` line 360 is stale.** It lists "No subagent / slash-command / hook tooling layer" as
  explicitly out of scope; `.claude/` now exists.
- **`.env` is gitignored** (as of `db6348c`). `application.yml` reads `${ANTHROPIC_API_KEY:}` from
  the environment, so Spring itself does not load `.env` — whatever populates your shell or IDE run
  configuration is what matters.
- **Two data-layer checks use `date.today()` instead of the run's `as_of`** —
  `eol_severity_spread` and `whitespace_signals_exist`. `run_data_evals` accepts `as_of`, records it
  in the report, and never forwards it, so those two do not mean what they say on a non-default
  `--as-of` build. Both are warning-severity, so nothing blocks.
