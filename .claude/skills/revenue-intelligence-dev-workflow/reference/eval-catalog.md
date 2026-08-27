# Eval catalog reference

Three independent layers. Every computed metric needs coverage in the layer it belongs to — this is not optional, and `architecture-reviewer` checks for it on every diff.

## Data layer (Python)

Validates the synthetic generator's output: referential integrity, domain checks, that real OEM anchors carry a source URL, and plausibility checks like workforce sizing vs. historical demand (the regression guard for the 5-6x headcount bug). Results are written by the Python build script to a JSON report.

## Pipeline layer (Java / JUnit)

Validates the deterministic forecasting/capacity math: internal consistency, no extreme or divide-by-near-zero values, and that what-if overrides actually move every metric they should (the regression test for the coverage-ratio bug lives here). Results come from Surefire XML after `mvn test`.

## Narrative layer (Java / JUnit)

Exercises `GroundednessChecker` and `NarrativeService`'s template fallback directly — the anti-hallucination gate has its own tests, so a change to the checker's tolerance or regex can't silently weaken it.

## Where results actually come from

`EvalsService` does not run tests live — it reads the last recorded run:
- Data layer: a JSON report path (configurable, with fallbacks for repo-root vs. `backend/`-relative launches)
- Pipeline / narrative layers: Surefire XML in the last `mvn test` output, split into layers by test package name

This means the `/api/evals` endpoint can go stale if code changes without a rebuild — the payload always reports when each layer was last generated so this is visible rather than silent. A layer with no recorded run reports `not_run`, never a false pass.

## Adding a new check

- New computed metric in a service → add a plausibility/consistency check to the pipeline-layer JUnit suite.
- New or changed synthetic data field → add a data-layer check to the Python eval script.
- Change to narration or the groundedness checker itself → add or update a narrative-layer test.

Every eval added in response to a real bug should be named or commented so the bug it guards against is traceable later — see the coverage-ratio and headcount-sizing tests as the model for this.
