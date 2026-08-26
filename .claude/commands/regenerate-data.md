---
description: Regenerate the synthetic dataset via the data-refresher agent, gated on error-severity data evals passing
---

Invoke the **`data-refresher`** agent to regenerate the synthetic dataset. Pass `$ARGUMENTS` through
as the request.

Recognised arguments, matching `data-tools/build_dataset.py`'s own flags (`:36-55`):

| Argument | Effect |
|---|---|
| `--seed N` | RNG seed. Env equivalent `POC_SEED`, default 42 |
| `--as-of YYYY-MM-DD` | As-of date. Env equivalent `POC_AS_OF_DATE`, default today |
| `--clients N` | Client count, default 50 (`ref.N_CLIENTS`) |
| `--installed-base N` | Installed-base rows, default 6000 (`ref.N_INSTALLED_BASE`) |
| `--skip-sql` | Generate and validate only; leave `data.sql` untouched |
| `dry-run` / `validate only` | Same as `--skip-sql` |

With no arguments, the agent should default to a validating dry run (`--skip-sql`) and report what a
live write would replace, rather than overwriting the dataset unasked.

## Constraints the agent must hold

- **`--force` is off the table.** It exists (`build_dataset.py:51-52`, help text "Write data.sql even
  if error-severity evals fail. Don't.") and the agent never passes it. If the user insists, the
  agent explains what breaks and hands them the command to run themselves.
- **`data.sql` is only written when every error-severity data eval passes**
  (`build_dataset.py:91-97`). The refusal is the design: "a stale-but-valid dataset beats a fresh
  nonsensical one" (`data-tools/README.md:47-50`). Warning-severity failures are reported but do not
  gate — `all_clear` counts only `ERROR` (`data_evals.py:478-490`).
- **No source edits.** The agent runs the generator; it does not modify
  `generate_synthetic_data.py`, `reference_data.py`, `data_evals.py`, or anything in `backend/` or
  `frontend/`. If the request requires a generator change, it reports that and stops.
- **`reference_data.py` and `DeliveryEconomics.java` move together or not at all** —
  `DeliveryEconomicsParityTest` fails otherwise, by design (`data-tools/README.md:60-64`).
- **The workforce-sizing mechanism is preserved.** Headcount is derived from delivered demand:
  `gen_workforce` (`generate_synthetic_data.py:665-691`) sizes off
  `historical_quarterly_demand_hours()` with the 0.82–1.15 `STAFFING_FACTOR_RANGE` jitter around
  0.85 target utilisation, and it runs *after* `gen_projects_and_billing` for that reason. Sizing
  workforce independently of revenue scale is the "hire 5–6× your bench" bug
  (`README.md:304-306`). The agent's own instructions cover this in full.

## After a successful write

The agent should run `cd backend && mvn test` (`JAVA_HOME` on JDK 21 — `README.md:50-55`), because
several pipeline evals assert against dataset *shape* and a new seed can legitimately move them —
`AggregationConsistencyTest.blendedCoverageIsTheForwardQuarterFold` needs a closed quarter that
missed target (`:130-134`), `WhatIfConsistencyTest` needs baseline coverage to be computable
(`:39-42`). Then remind the user to restart the backend: H2 is in-memory and only reads `data.sql` at
startup (`build_dataset.py:106`, `README.md:260-263`).

If a non-default `--as-of` was used, the agent must flag that `revintel.as-of-date` needs to be set
to the same value in `backend/src/main/resources/application.yml` — the key is not currently present
there — or Java and the dataset will disagree about which quarters are history
(`data-tools/README.md:32-34`, `AsOfProvider.java:17-20`). The agent reports this; it does not edit
`application.yml`.
