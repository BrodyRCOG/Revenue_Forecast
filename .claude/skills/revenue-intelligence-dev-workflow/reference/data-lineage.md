# Data lineage reference

## Real vs. synthetic vs. LLM-touched, and why all three exist together

Real client files (CMDB sample, partial project history, partial contract data) and a synthetic proof-of-concept dataset are developed in parallel, sharing the same schema. This means pipeline logic never has to change when a synthetic table is replaced by a real one — only the data source underneath it changes.

A third category, **LLM-touched**, covers any data that passed through a model somewhere before reaching the calculation layer — cleaning, schema mapping, extraction from unstructured input, or any other LLM-assisted step in ingestion or orchestration. Since the application is now broadly open to LLM involvement outside the calculation layer, this category is the norm for anything that didn't come from a fixed, testable function — not a rare exception. It is treated as untrusted until it clears the same data-layer evals every other input must pass.

Every record carries a provenance flag (`real`, `synthetic`, or `llm_touched`) that is never dropped as it moves downstream through enrichment, feature engineering, or forecasting. A dashboard value derived from anything other than `real` should be identifiable as such all the way to the UI.

## Guardrail for the LLM-touched category specifically

- Scope: shaping raw data into the existing schema, orchestration, or any other non-calculation task. It never computes a forecast, capacity, or signal value — that boundary does not move regardless of how broadly LLM involvement is otherwise allowed.
- Must pass the same data-layer evals (referential integrity, domain checks, plausibility) as any other newly-ingested data before anything downstream — especially the calculation layer — treats it as usable.
- Rejected output is discarded and reported, not silently patched — the same posture `NarrativeService` already takes when generated narration fails its groundedness check.
- Kept distinguishable from `real` and `synthetic` at every layer, so an eval failure or a client question about a number's origin can always be traced back to whether an LLM-touched step contributed to it.

## OEM lifecycle data specifically

This is the one dataset where real external facts are deliberately used to shape procedural generation, rather than being either the only real content in an arbitrary dataset or diluted into invisibility once scaled up:

1. Real EOL/EOS/EOSL dates are researched per model, each with a source URL and an honest confidence level (vendor-official, aggregator, or synthetic if none could be found).
2. The real anchors' actual lifecycle spans inform a small set of (vendor, category, lifecycle-years, support-tail-years) patterns.
3. Those patterns are used to procedurally generate the rest of the catalog at scale — reflecting real lifecycle shapes, not literal invented dates.
4. Provenance (`is_real_anchor`, `source_url`, `source_confidence`) is carried through untouched, even though downstream risk-severity logic treats real and procedural rows identically.

## Sourcing coverage (no single universal API)

- Cisco EoX API — official, strong coverage for Cisco hardware
- Flexera / Technopedia via ServiceNow — most operationally direct path if a client's real-world CMDB is ServiceNow-based
- endoflife.date — free API, software/OS/runtime lifecycle, weak on physical hardware
- eosl.date — aggregator, ~9 hardware vendors, no API, useful as a free cross-check only
- Human-reviewed pass — periodic manual fill for anything no automated source covers

## Before regenerating synthetic data

Confirm every error-severity data eval currently passes before overwriting the live dataset. If any fail, do not overwrite — report why and stop.
