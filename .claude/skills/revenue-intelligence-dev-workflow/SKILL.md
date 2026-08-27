---
name: revenue-intelligence-dev-workflow
description: Use when building, reviewing, testing, or extending the FIS Revenue & Capacity Intelligence pipeline — any change touching the Java/Spring services layer, the Python analytics/data-generation layer, or the eval suite. Also use when asked to add a new computed metric, refresh synthetic or OEM lifecycle data, or diagnose a failing eval.
---

# Revenue Intelligence dev workflow

This skill governs how work gets done on the deterministic core of the platform. It is process only — for background on *why* the architecture is shaped this way, see the reference files linked at each step.

## The one rule that overrides everything else — scoped to calculation only

**Only the calculation layer must stay deterministic.** That means: any code whose job is to turn inputs into a quantity shown on the dashboard — a forecast dollar amount, a demand/capacity hour figure, an FTE or contractor count, a coverage ratio, a utilization percentage, or a severity/threshold classification derived from those numbers (e.g. `ForecastingService`'s totals, `CapacityService`'s hours/headcount math, `WhatIfService`'s diffing, `DeliveryEconomics`'s conversions) — must remain plain, deterministic code. No model call anywhere inside these functions. This is the one boundary that does not move.

**Everything else is open to LLM involvement**, including but not limited to:

- Data ingestion, cleaning, and schema mapping
- Orchestration decisions — which service to call, in what order, how to assemble a response payload
- Narrative and explanation text (already the case via `NarrativeService`)
- Embedded Q&A / chat responses about the data or the dashboard
- Recommendation phrasing and rationale text (the *wording* of a hiring or renewal recommendation — not the FTE count or dollar figure behind it)
- Eval-failure triage and plain-language explanation
- What-if scenario framing and interpretation text

**The test to apply:** does this code produce a *quantity* that lands on the dashboard, or does it produce *everything else* (data shape, routing decisions, explanation, phrasing)? Quantities stay deterministic, full stop. Everything else may involve a model.

**One carry-over guardrail, even though this area is open:** if an LLM-touched step (ingestion, mapping, orchestration) produces something that feeds *into* the calculation layer, that output is still treated as untrusted input and should clear the same data-layer evals as any other newly-ingested data before the deterministic calculation functions consume it. The openness applies to what LLMs are allowed to *do* in this app, not to whether their output gets checked before it reaches the one part that has to stay provably correct.

## Before adding or changing a computed metric

1. Confirm which layer it belongs in: routes have no logic, services have all the logic, repositories are the only place data is queried. See `reference/architecture.md` for the layer boundaries and the specific patterns (`CapacityService`, `WhatIfService`) that must not regress.
2. If this metric is a *quantity* (dollar amount, hours, headcount, ratio, percentage, threshold classification), it must be computed by deterministic code — no model call inside the function that produces it, regardless of how the code around it is built.
3. Confirm the metric doesn't duplicate a computation another service already owns. If it needs a forecast, take forecast rows as an input — don't recompute them.
4. Add eval coverage in the same change, not after. See `reference/eval-catalog.md` for which layer your check belongs in and how `EvalsService` discovers it.
5. If the metric touches hiring, capacity, or headcount math, re-read the sizing-bug history in `reference/architecture.md` before writing it — this exact class of bug has happened once already.

## Before touching data (real, synthetic, or LLM-assisted)

1. Check `reference/data-lineage.md` for what's real, what's synthetic, what's LLM-assisted, and how provenance is tracked for each.
2. Never regenerate the synthetic dataset without confirming existing error-severity evals pass first.
3. If adding real OEM lifecycle data, every entry needs a source URL and an honest confidence level — no bare dates.
4. If using the on-request LLM data-handling assist, confirm its output ran through data-layer evals before it's treated as usable input anywhere downstream — see "On-request LLM-assisted data handling" above.

## When an eval or test fails

1. Don't just re-run it. Read `reference/eval-catalog.md` to find where the actual failure output lives (Surefire XML for Java, the data eval JSON for Python).
2. Root-cause it, then add a permanent regression check for that root cause — see the bug-fix precedent in `reference/architecture.md`.

## Where to look for more

- `reference/architecture.md` — layer boundaries, service responsibilities, known bug history, the patterns that must not regress
- `reference/eval-catalog.md` — the three eval layers, what each validates, where results are read from
- `reference/data-lineage.md` — real vs. synthetic data, provenance tagging, OEM anchor sourcing
