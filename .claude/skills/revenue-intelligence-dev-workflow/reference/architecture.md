# Architecture reference

## Layers

- **Routes** — HTTP in/out only. Parse request, call one service (usually via the orchestrator), return the result. No business logic.
- **Services** — all business logic: forecasting, capacity math, signal detection, what-if, orchestration. Call repositories, never raw SQL.
- **Data / repositories** — the only place SQL or file access is written.

## Patterns that must not regress

**Compute once, pass the rows in.** `CapacityService` is deliberately not a Spring bean — it's constructed with a plain `List<ForecastRow>` and a workforce list, rather than injecting `ForecastingService` and recomputing internally. This guarantees a what-if override reaches both revenue and capacity numbers, since both are derived from the same row list in one pass. `WhatIfService.metrics()` follows the same shape: build the forecast rows once, hand them to both the revenue totals and a fresh `CapacityService`.

If you're adding a new service that depends on a forecast, follow this pattern — take rows as a constructor argument, don't reach for `ForecastingService` yourself.

## Known bug history (read before touching capacity/hiring math)

- **5–6x headcount bug** — workforce size and forecast demand were generated independently, so hiring recommendations implied absurd headcount. Fixed by sizing workforce off the same historical demand model the forecast reads, plus a plausibility eval guard.
- **Hiring sized off peak demand instead of average** — this is the specific mechanism of the bug above. Permanent hires must close the *average* demand gap; contractors absorb the *peak* above that. Sizing FTEs to the peak quarter reproduces the bug.
- **82,427x coverage ratio bug** — a remaining-target value floored near zero, and the ratio was computed from raw CRM data instead of the override-aware forecast rows, so it also didn't move with what-if sliders. Fixed with a "target already met" flag (returns null instead of dividing near-zero) and reusing the override-aware forecast rows everywhere coverage is computed.

Every fix here shipped with a permanent regression eval, not just a patch. New fixes should follow the same shape.

## Where the LLM boundary actually sits

The application is open to LLM involvement almost everywhere. The single exception is the calculation layer — the functions that turn inputs into a quantity shown on the dashboard: `ForecastingService`'s totals and aggregations, `CapacityService`'s hours/headcount/utilization math, `WhatIfService`'s diffing, `DeliveryEconomics`'s dollars-to-hours conversions, and any threshold-derived classification built from those numbers (e.g. severity bands, utilization status labels). Nothing in this specific set of functions may call a model, regardless of what's built around them.

Everywhere else — orchestration, data ingestion/cleaning, narrative, embedded Q&A, recommendation phrasing, eval-failure explanation — is open. `NarrativeService` (below) was the first and, for a while, only place this was allowed; it's now one example of the broader pattern rather than a special case.

**The one carry-over guardrail:** if an LLM-touched step anywhere in the open areas produces something that feeds into the calculation layer, that output is untrusted input until it clears data-layer evals — the same way any newly-arrived file would be. Openness applies to what LLMs may *do*; it doesn't exempt their output from being checked before the deterministic layer consumes it.

## Narration (`NarrativeService`) as a worked example

`NarrativeService` is given data that has already been computed and asked only for prose — never to compute, estimate, rank, or infer a number. Its output is checked by `GroundednessChecker` before anyone sees it: every numeric token in the generated text must trace back to a value in the data that was actually handed to the model (matched literally, or by a tightly-tolerant magnitude comparison for scaled renderings like "$1.2M"). If any number fails to match, the entire generated text is discarded and a template built directly from the same data is returned instead. No API key configured takes the same template path with no model call attempted. New LLM-touched features outside the calculation layer should follow this same generate-then-verify shape where the output could plausibly contain a fabricated number, even though they're not required to gate as strictly as this one does.
