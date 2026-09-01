---
description: Have Claude inspect the data, decide how it should be processed for revenue forecasting, and write/replace the Python pipeline in data-tools/ — proven against the eval gate, never computing values itself
---

Invoke the **`data-processing-designer`** agent. Pass `$ARGUMENTS` through as the description of the
processing goal (e.g. "add a contract-margin field and forecast off it", "score refresh urgency by
mission-criticality", "size workforce more conservatively"). With no arguments, the agent should
first triage the current dataset and propose candidate processing changes, then ask which to pursue
rather than editing anything unprompted.

This command is how the AI gets a say in **how** data is processed — not just in narrating results.
The agent authors deterministic Python and lets the eval gate decide whether it is correct; it never
asserts a number it did not get from a script run.

## What the agent may and may not do

- **May create/replace** any Python file under `data-tools/`
  (`generate_synthetic_data.py`, `reference_data.py`, `data_evals.py`, `build_dataset.py`,
  `exploratory_analysis/`), and — only in lockstep with `reference_data.py`'s economics block —
  `backend/.../service/DeliveryEconomics.java`.
- **May not** touch anything else in `backend/` or `frontend/`, and never hand-edits the generated
  `backend/src/main/resources/db/generated/data.sql`.

## Constraints the agent must hold

- **The eval gate decides.** After writing, it runs `python data-tools/build_dataset.py --skip-sql`
  then `python data-tools/data_evals.py`, and iterates until every error-severity check passes before
  writing the live `data.sql`. **`--force` is never used** — its help text says "Don't." A failing
  gate is reported as the design working, not relaxed away.
- **Parity moves together.** If it changes an economics constant it changes `reference_data.py` and
  `DeliveryEconomics.java` identically in the same pass, or `DeliveryEconomicsParityTest` fails by
  design.
- **Workforce sizing stays demand-driven** — headcount off `historical_quarterly_demand_hours()`,
  `gen_workforce` last, the 0.82–1.15 jitter intact. Sizing off the peak or off revenue scale is the
  "hire 5–6× your bench" bug.
- **New data-layer checks go in the `CHECKS` list**, or they never run.
- **A non-default `--as-of`** requires `revintel.as-of-date` in `application.yml` to match — the
  agent reports this; it does not edit `application.yml`.

## After a successful write

The agent runs `cd backend && mvn test` (`JAVA_HOME` on JDK 21) — a processing change can legitimately
move the shape-based pipeline evals — and reminds the user to restart the backend, since H2 is
in-memory and only reads `data.sql` at startup.
