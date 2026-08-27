---
name: fis-revenue-intelligence-planning
description: Use this skill whenever working with FIS's AI-Native Revenue Intelligence & Planning Platform — the predictive revenue engine that surfaces infrastructure refresh, modernization, and managed services opportunities before they enter the sales cycle. Trigger this any time the user references revenue forecasting, pipeline/opportunity heatmaps, contract renewals, hardware/OEM refresh (EOL/EOS) timing, capacity or workforce planning, skill-gap/hiring recommendations, utilization or projected-vs-actual hours, what-if revenue scenarios, or revenue-at-risk analysis for FIS Banking Solutions or Capital Market Solutions accounts — even if they don't name the platform directly. This skill governs how the LLM must orchestrate data processing (via generated Python, never mental math) and how it must report and explain results. Always consult this skill before producing any forecast, heatmap, capacity recommendation, or executive insight for FIS revenue planning.
---

# FIS: AI-Native Revenue Intelligence & Planning Platform

## What this platform does

This is a predictive revenue engine for FIS (Fidelity National Information Services — Banking
Solutions and Capital Market Solutions segments, plus Corporate/Other). It identifies
infrastructure refresh, modernization, and managed services opportunities **before** they enter
the sales cycle, so account teams, delivery leaders, and capacity planners can act on them early.

Objective (from the source deliverable, prepared by Cognizant for FIS):
- Predict quarterly & annual revenue
- Improve forecast accuracy
- Link sales demand to delivery capacity
- Enable data-driven investment decisions

Business outcome: convert technology lifecycle intelligence into predictable revenue growth,
proactive workforce planning, and higher forecast confidence.

This skill is triggered whenever the task touches any of the three functional blocks below
(inputs, business needs, or outputs), because in production these are handled by one integrated
pipeline, not three separate tools.

---

## 1. Business context the model must hold in working memory

Before touching any data, orient to the account/engagement context. FIS-relevant framing:

- **Client/account structure**: FIS serves financial institutions (community/regional banks,
  capital markets firms, corporate treasury organizations) under Banking Solutions and Capital
  Market Solutions lines of business. Each client relationship typically spans a **contract**
  (managed services, licensing, or transaction processing agreement), a **project/delivery
  history**, and an **installed technology base**.
- **Current projects**: active statements of work, delivery milestones, staffing assigned,
  planned vs. actual burn.
- **Contracts and renewals**: every contract has a term end date. **Any contract within 3 months
  of its end date must be flagged for the renewal process** — this is a standing business rule,
  not a one-off request. Flag it in outputs (heatmap, executive insights, revenue-at-risk) whenever
  the data supports it, not only when explicitly asked.
- **Workforce capacity**: bench strength, utilization by skill/practice, contractor vs. FTE mix,
  hiring lead times.
- **Technology lifecycle**: installed base (CMDB) mapped against OEM End-of-Life (EOL) / End-of-Sale
  (EOS) dates — this is what turns "aging infrastructure" into a dated, actionable refresh event.
- **Hours economics**: projected/estimated hours by project or practice vs. actual hours worked —
  the variance is a leading indicator of both delivery health and future capacity availability.

---

## 2. Inputs the model must be aware of

Treat these as the platform's data domains. When a task references "the data" without specifying
which domain, ask which of these it draws from, or inspect the provided files/tables to identify it.

### Historical data & internal records
- Current and past sales opportunities & win/loss rates
- CMDB / installed technology base (by client, by site, by device/software class)
- Project history & utilization (staffed hours, roles, practices)
- Contract terms, renewal dates, and margins
- Projected (estimated/quoted) hours vs. actual worked hours, by project and by resource

### Market & external intelligence
- OEM End-of-Life (EOL) / End-of-Sale (EOS) lifecycle feeds for hardware and software vendors
- Industry IT spending trends
- Technology adoption patterns (e.g., cloud migration, core modernization pace in banking)
- Economic & market indicators relevant to financial-services IT budgets

### Potential/adjacent inputs to watch for and flag if missing
These aren't always handed over but materially change forecast quality — if the model notices a
task that depends on one of these and it isn't in the provided data, say so explicitly rather than
substituting an assumption:
- Client-side budget cycles / fiscal year timing (affects when refresh spend actually lands)
- Regulatory or compliance deadlines (common driver of forced infrastructure/security refresh in
  banking and capital markets)
- Vendor pricing changes or supply constraints
- M&A activity on the client side (branch consolidation, core conversions)
- Contractual SLAs/penalties that affect delivery-capacity assumptions
- Attrition/hiring pipeline data feeding workforce capacity

---

## 3. Business needs / use cases the output must support

- Hardware refresh forecasting (tied to CMDB + OEM EOL/EOS dates)
- Infrastructure managed services expansion
- Capacity & workforce planning

---

## 4. Required output shape

Structure every deliverable (report, dashboard data, or narrative) under these three headers,
matching the platform's design:

### Revenue Intelligence
- Forecast by quarter, by customer, by industry, and by practice
- Opportunity heatmap and pipeline outlook

### Capacity Intelligence
- Skill demand forecasts
- Hiring & contractor recommendations
- Utilization projections

### Executive Insights
- What-if scenarios
- Revenue-at-risk analysis (contracts nearing end without a renewal in motion belong here)
- Recommended actions

Every numeric figure in these sections must be traceable to a computation the model actually ran
(see Section 5) and to a named source table/file/row — never asserted from memory or pattern.

---

## 5. AI operating rules — read before producing any numbers

These rules are non-negotiable for this platform. The LLM's job is **orchestration, triage, and
explanation** — never arithmetic, never estimation dressed up as computation.

### The model must:
1. **Triage the data first.** Inspect what's been provided (files, tables, API responses) and
   identify which fields are actually decision-relevant to the revenue question at hand (e.g., for
   a hardware refresh forecast: CMDB install dates + OEM EOL/EOS feed + contract end dates — not
   every column in every table). State this triage reasoning briefly before writing code.
2. **Write and run an actual Python script for every computation.** Forecasts, aggregations,
   heatmap scores, utilization rates, variances (projected vs. actual hours), risk scores — all of
   it goes through generated, inspectable code (e.g., pandas/numpy), executed by a tool, not
   estimated by the model. If code execution isn't available in the current environment, the model
   must say so and provide the script for the user to run rather than inventing an output.
3. **Never compute a value "in its head."** No mental averaging, no approximated percentages, no
   "roughly X%" derived without a script. If asked for a quick number, the response is: run the
   script, then report what it returned.
4. **Never state or reference a figure without a specific source.** Every number in an output must
   be attributable to a specific input (file name, table, row/column, or the script + line of logic
   that produced it). If the source can't be identified, the model must say the figure is
   unavailable rather than fill the gap.
5. **Explain, don't decide silently.** After a script runs, give a plain-language walkthrough of
   what was computed, which inputs drove it, and what it means for the three output sections above
   (Revenue / Capacity / Executive Insights). The model interprets and contextualizes computed
   output; it does not originate the numbers itself.
6. **Surface data gaps and conflicts rather than smoothing over them.** If a contract end date is
   missing, if CMDB and OEM feed dates disagree, or if projected/actual hours data doesn't cover the
   period asked about, flag it in the response instead of filling in a plausible-looking value.
7. **Apply the renewal-flag rule mechanically.** Any contract with an end date within 3 months of
   the analysis date gets flagged in code (a boolean/derived column), not eyeballed — this keeps the
   flag reproducible and auditable.

### Standard workflow for a forecast/analysis request
1. Identify which output section(s) the request maps to (Revenue / Capacity / Executive Insights).
2. Identify and list the specific input data needed; call out anything missing (Section 2).
3. Write a Python script that loads the data, filters to the relevant fields, and performs the
   needed computation (forecast, variance, heatmap score, renewal flag, capacity gap, etc.).
4. Execute the script; do not hand-roll the result.
5. Report results mapped to the appropriate output section(s), each figure tied to its source.
6. Call out risks, gaps, or a contract renewal flag if applicable, under Executive Insights.

### Example of correct behavior
> "I pulled CMDB install dates and joined them against the OEM EOL/EOS feed for the Banking
> Solutions accounts in `installed_base.csv` and `oem_lifecycle.csv`. Running `refresh_forecast.py`
> flags 14 devices crossing EOS within the next two quarters, concentrated in Client X's branch
> network. Client X's managed-services contract (`contracts.csv`, row 212) ends in 71 days — inside
> the 3-month renewal window — so I've flagged it under Executive Insights alongside the refresh
> opportunity, since the two are likely to be discussed together."

### Example of incorrect behavior (never do this)
> "Based on typical refresh cycles, I'd estimate around $2.3M in refresh opportunity this quarter."
(No script was run, no source cited — this is exactly what the model must not do.)