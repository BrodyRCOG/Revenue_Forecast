---
description: Run all three eval layers — data (Python), pipeline and narrative (JUnit) — and report the combined result the way GET /api/evals would
---

Run this repo's three eval layers and report the result. Read-only with respect to application
source: nothing here edits Java, Python, or frontend files.

`$ARGUMENTS` may narrow the run — `data`, `java`, `pipeline`, `narrative`, or a test class name. With
no arguments, run everything.

## 1. Data layer — Python

The data layer is `data-tools/data_evals.py`: 12 checks in the module-level `CHECKS` list
(`:428-441`), 10 at `ERROR` severity and 2 at `WARNING`.

If `data-tools/output/*.csv` already exist, re-run over them without regenerating:

```bash
python data-tools/data_evals.py
```

If they do not exist, it raises `FileNotFoundError` telling you to run the generator
(`data_evals.py:469-470`). In that case generate and validate **without touching the live dataset**:

```bash
python data-tools/build_dataset.py --skip-sql
```

`--skip-sql` leaves `backend/src/main/resources/db/generated/data.sql` alone
(`build_dataset.py:87-89`). **Never pass `--force`.** Both commands exit 1 when any error-severity
check fails; warning-severity failures do not affect the exit code
(`data_evals.py:478-490` — `all_clear` counts only `ERROR`).

Either command writes `data-tools/output/data_eval_results.json`, which is the exact artefact
`EvalsService` reads. For any failure, read that file's `details` payload — it carries the numbers
behind the verdict and is not surfaced by the API:

```bash
python -c "import json;r=json.load(open('data-tools/output/data_eval_results.json'));[print('---',c['name'],c['severity'],'\n',c['message'],'\n',json.dumps(c['details'],indent=2)[:1500]) for c in r['checks'] if not c['passed']]"
```

There is **no pytest in this project** — `data-tools/requirements.txt` is `pandas`, `numpy`, `Faker`,
`matplotlib`. `data_evals.py` is the Python test suite.

## 2. Pipeline and narrative layers — JUnit

Both live in the same Maven module and run in one command:

```bash
cd backend && mvn test
```

`JAVA_HOME` must point at JDK 21. JDK 25 is first on `PATH` on this machine and Spring Boot 3.4 does
not support it (`README.md:50-55`):

- PowerShell — `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"`
- bash — `export JAVA_HOME=/path/to/jdk-21`

Narrowing:

```bash
cd backend && mvn -Dtest='com.cognizant.revintel.pipeline.*Test' test
cd backend && mvn -Dtest='com.cognizant.revintel.narrative.*Test' test
cd backend && mvn -Dtest=PlausibilityTest test
cd backend && mvn -Dtest=WhatIfConsistencyTest#whatIfOverridesMoveCoverageRatio test
```

Test classes as they exist:

- `pipeline/` — `AggregationConsistencyTest`, `CapacityFixtureTest`, `DeliveryEconomicsParityTest`,
  `JsonPayloadSafetyTest`, `PlausibilityTest`, `WhatIfConsistencyTest`
- `narrative/` — `GroundednessCheckerTest`, `NarrativeFallbackTest`
- `ApplicationBootTest` at the root — a boot smoke test, **not** an eval layer

`mvn test` writes `backend/target/surefire-reports/TEST-*.xml`, the artefact `EvalsService` reads.

## 3. Report it the way EvalsService would

Aggregate the three layers using the same rules as `service/EvalsService.java`, so your report and
`GET /api/evals` agree:

- **Layer routing by FQCN** (`:176-184`): a Surefire file whose class name contains `.narrative.`
  is the narrative layer; `.pipeline.` is the pipeline layer; **anything else is skipped entirely**
  so `ApplicationBootTest` does not inflate the counts. Exclude it from your totals.
- **Severity mapping** (`:217-226`): a `<failure>` or `<error>` is severity `error` and counts as
  failed; a `<skipped>` is severity `warning` and is **not** a pass —
  `passed = total - failed - skipped` (`:274-275`).
- **Layer status** — `failing` if any failure, else `warnings` if any skip, else `all_clear`; and
  `not_run` for a layer with no artefact at all, which never passes vacuously (`:42, 246-250`).
- **Overall status** (`:86-91`) — `not_run` if nothing ran, else `failing`, else `warnings`, else
  `all_clear`.

Read the XML directly for any failure. `EvalsService.firstMessage` (`:230-239`) reports only the
**first line** of the assertion message, so the API payload is a truncation of what the file holds.

One thing to interpret rather than report flatly: `DeliveryEconomicsParityTest` is
`@EnabledIf("pythonSourceAvailable")` and **skips** wholesale when `data-tools/reference_data.py` is
not on disk (`:29-33, 48-50`). A pipeline layer showing `warnings` from those skips means "Python
source not found", not "the Java and Python constants drifted".

## 4. Output

Report per layer: name, language, artefact path used, status, and `total / passed / failed /
warnings` — the same fields the API exposes. Then the combined status. Then, for every failure: the
class and method, the **full** message from the XML or the `details` block from the JSON, and which
of the four documented bugs it corresponds to if any (`README.md:299-314` has the *Bug | Root cause
| Fix, and the test that holds it* table).

Do not propose relaxing a threshold. The bounds in `PlausibilityTest` and `GroundednessChecker` are
derived from stated inputs and their derivations are in their Javadoc — `MAX_GROUP_HIRING_SHARE =
0.55` from `1/0.82 × 1.10` plus headroom (`PlausibilityTest.java:41-49`), `MAGNITUDE_TOLERANCE =
0.005` because 5% wrongly accepted "215 units" against a ground truth of 212
(`GroundednessChecker.java:61-68`). For a genuine root-cause diagnosis, hand off to the
`pipeline-auditor` agent.
