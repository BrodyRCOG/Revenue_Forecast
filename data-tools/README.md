# data-tools

Python's entire role in this project. It runs **offline**, never as part of the Spring Boot
application — no subprocess, no embedded interpreter, no sidecar service. It produces the dataset the
backend loads at startup, and it refuses to hand over a dataset that fails validation.

Three separate jobs, deliberately kept in separate files:

| File / directory | Job |
|---|---|
| `reference_data.py` | Real OEM lifecycle anchors, the client-base taxonomy, and the shared economic constants. **Mirror of `DeliveryEconomics.java`** |
| `generate_synthetic_data.py` | Generation |
| `data_evals.py` | Pass/fail validation that **gates the build** |
| `build_dataset.py` | CLI: generate → validate → write |
| `exploratory_analysis/` | Charts for a human to look at. A dev aid; nothing depends on it |

## Usage

```bash
pip install -r requirements.txt

python build_dataset.py                              # generate, validate, write data.sql
python build_dataset.py --seed 7 --as-of 2026-01-15  # a different world
python build_dataset.py --skip-sql                   # validate only; leave data.sql alone
python data_evals.py                                 # re-run evals over the CSVs already written
python exploratory_analysis/run_all.py               # charts -> output/analysis/index.html
```

`POC_SEED` (default 42) and `POC_AS_OF_DATE` (default: today) are honoured as environment variables.
Same inputs, identical output.

> If you generate with a non-default `--as-of`, set `revintel.as-of-date` to the same value in the
> backend's `application.yml`. Otherwise Java thinks "now" is today while the data was built around a
> different date, and the two disagree about which quarters are history.

## Outputs

| Path | What |
|---|---|
| `output/*.csv` | One per table, plus `projects.csv` and `project_utilization.csv` (analysis inputs, not loaded into H2) |
| `output/data_eval_results.json` | The data-layer eval report. `GET /api/evals` reads this |
| `output/analysis/` | Charts and an `index.html` from the exploratory scripts |
| `../backend/src/main/resources/db/generated/data.sql` | What Spring Boot loads |

## The build gate

`build_dataset.py` **refuses to write `data.sql` if any error-severity data eval fails.** CSVs and the
eval report are still written, so a failing run is debuggable, but the backend keeps whatever dataset
it already had — a stale-but-valid dataset beats a fresh nonsensical one. `--force` overrides this,
deliberately awkwardly.

The load-bearing check is `capacity_vs_historical_demand_plausibility`. It recomputes average
quarterly demand hours per resource group straight from `project_billing` and asserts the generated
headcount is a believable fit. It is the regression guard for the "hiring recommendations imply 5–6×
current headcount" bug: if workforce is ever sized independently of revenue scale again, implied
utilisation drifts out of band and the build stops.

## Two constraints worth not breaking

**Keep the economics constants in sync with Java.** `LABOR_CONTENT`, `DELIVERY_RATE_USD_PER_HOUR`,
`RESOURCE_MIX`, `ANNUAL_CAPACITY_HOURS`, `TARGET_UTILIZATION` and friends are mirrored in
`backend/.../service/DeliveryEconomics.java`. `DeliveryEconomicsParityTest` parses this file and
compares them value by value, so editing one side alone fails the Java build. That is intentional —
the failure mode when they drift silently is capacity numbers that look fine and mean nothing.

**Keep provenance fields untouched.** `is_real_anchor`, `source_url` and `source_confidence` are how
anything downstream tells a real, sourced lifecycle date from a procedural variant.
`real_anchors_have_source_url` enforces it in both directions: an anchor without a URL fails, and so
does a synthetic row claiming one.
