---
name: oem-lifecycle-refresh
description: The procedure for refreshing the seven real OEM lifecycle anchors in data-tools/reference_data.py — vendor coverage, the provenance rules, what regenerating changes downstream, and how to verify. Use when OEM EOL/EOS/EOSL dates need rechecking or a new anchor is being considered.
---

# OEM lifecycle refresh

The dataset is synthetic with one exception. `README.md:7-9`:

> The only real data in the system is seven sourced OEM lifecycle records, each carrying the URL it
> came from — the Data Sources tab lists them.

Those seven live in `REAL_ANCHORS` at `data-tools/reference_data.py:39-124`. This is the procedure
for rechecking them. It is documentation of what the repo already does — every rule below is
enforced by code or stated in a comment that is cited.

**This is a manual, human-approved procedure.** Nothing in `.claude/` edits `reference_data.py`. The
`oem-eol-researcher` agent does the research and writes a proposal to `.claude/proposals/`; a human
applies it.

---

## What the anchors are for, and what they are not

From the `reference_data.py` module docstring (`:6-16`):

> Seven actual products with real end-of-sale / end-of-support dates and a `source_url`. Every other
> OEM row in the dataset is a procedural variant derived from these anchors' lifecycle *spans*, and is
> labelled `source_confidence="synthetic"` so downstream code can always tell the two apart.
>
> These dates were correct as published at authoring time. They are POC anchors, not a lifecycle
> feed — see spec section 8, "no production EOL/EOS integration".

So: 7 real rows + 15 `OEM_CATALOG` patterns × 3 variants = 52 rows in `oem_models`. The seven carry
`is_real_anchor=True` and a URL; the 45 carry `is_real_anchor=False`, `source_url=None`,
`source_confidence="synthetic"` (`generate_synthetic_data.py:340-392`).

**Out of scope, explicitly.** `README.md:358-359`:

> **No production OEM EOL/EOS integration** (Cisco EoX, Flexera/Technopedia, endoflife.date). The
> seven hardcoded anchors are sufficient here.

A refresh is a manual recheck of seven dates. It is not the first step toward a feed. Those three
named sources are legitimate as *human-readable cross-checks* — that is precisely what
`source_confidence: aggregator` records — but not as an integration.

---

## Vendor coverage

The seven anchors and the source of record each was taken from:

| `anchor_key` | Product | Category | Source domain | `source_confidence` |
|---|---|---|---|---|
| `cisco-c2960x` | Cisco Catalyst 2960-X Series | Switching | `cisco.com` EoS/EoL notice collateral | `vendor_official` |
| `hpe-dl380-gen9` | HPE ProLiant DL380 Gen9 | Server | `support.hpe.com` | `aggregator` |
| `fortinet-fg60e` | Fortinet FortiGate-60E | Firewall | `docs.fortinet.com` product life cycle | `vendor_official` |
| `netapp-fas2650` | NetApp FAS2650 | Storage | `mysupport.netapp.com` | `aggregator` |
| `microsoft-ws2016` | Windows Server 2016 | Server OS | `learn.microsoft.com/lifecycle/products/` | `vendor_official` |
| `microsoft-sql2016` | SQL Server 2016 | Database | `learn.microsoft.com/lifecycle/products/` | `vendor_official` |
| `vmware-esxi67` | VMware vSphere ESXi 6.7 | Hypervisor | `lifecycle.vmware.com` | `vendor_official` |

Two vendors are `aggregator` for reasons recorded inline, and those reasons are the definition of the
grade:

- HPE (`:62`) — "HPE publishes support end per-SKU and per-region; this is a representative date."
- NetApp (`:85`) — "NetApp end-of-support runs off the individual serial's contract; representative date."

Two anchors record **which of two published milestones** was mapped onto which field. Preserve this
mapping; switching it silently moves assets between severity bands:

- Windows Server 2016 (`:95`) — "Mainstream support end / extended support end", i.e. `end_of_sale`
  = mainstream end, `end_of_support` = extended end.
- VMware ESXi 6.7 (`:116-117`) — "General support end / technical guidance end", same ordering.

Generalising: **`end_of_sale` takes the earlier milestone, `end_of_support` the later.**

---

## The provenance rules, and the code that enforces them

`data-tools/README.md:66-69` states the constraint:

> **Keep provenance fields untouched.** `is_real_anchor`, `source_url` and `source_confidence` are
> how anything downstream tells a real, sourced lifecycle date from a procedural variant.
> `real_anchors_have_source_url` enforces it in both directions: an anchor without a URL fails, and
> so does a synthetic row claiming one.

`data_evals.real_anchors_have_source_url` (`:151-176`, `ERROR` severity) checks four conditions plus
a count:

| Condition | Fails when |
|---|---|
| `anchors_missing_source_url` | An anchor's `source_url` does not start with `http` (`:155`) |
| `anchors_labelled_synthetic` | An anchor's `source_confidence == "synthetic"` (`:156`) |
| `synthetic_rows_not_labelled_synthetic` | A derived row is labelled anything else (`:157`) |
| `synthetic_rows_claiming_a_source_url` | A derived row carries a non-blank `source_url` (`:158`) |
| `anchor_count` | Row count with `is_real_anchor` ≠ `len(ref.REAL_ANCHORS)` (`:167-169`) |

Two further error-severity checks apply:

- `source_confidence_domain` (`:141-148`) — every value must be in `VALID_SOURCE_CONFIDENCE =
  {vendor_official, aggregator, uncertain, synthetic}` (`reference_data.py:126`).
- `lifecycle_dates_ordered` (`:179-189`) — `release_date <= end_of_sale <= end_of_support`, per model.

`uncertain` is in the domain and currently unused. It is the correct grade for a date sourced only via
a third party, or where sources disagree, or where the vendor page has gone — **prefer it over
promoting a shaky date to `aggregator`.**

---

## The anchor shape

Nine keys, in order, dates as ISO strings. Do **not** author `lifecycle_years` or
`support_tail_years` — `gen_oem_models` derives them from the dates
(`generate_synthetic_data.py:351-352`).

```python
{
    "anchor_key": "cisco-c2960x",
    "oem": "Cisco",
    "model_name": "Catalyst 2960-X Series",
    "category": "Switching",
    "release_date": "2013-06-01",
    "end_of_sale": "2022-10-31",
    "end_of_support": "2027-10-31",
    "source_confidence": "vendor_official",
    "source_url": (
        "https://www.cisco.com/c/en/us/products/collateral/switches/"
        "catalyst-2960-x-series-switches/eos-eol-notice-c51-744295.html"
    ),
},
```

`anchor_key` is referenced by every `OEM_CATALOG` entry's `derived_from`
(`reference_data.py:134-165`), and that value is written onto all 45 derived rows
(`generate_synthetic_data.py:390`) so provenance stays auditable. **Renaming or removing an
`anchor_key` orphans the patterns pointing at it** — grep `derived_from` before doing either.

---

## Procedure

### 1. Research

Delegate to the `oem-eol-researcher` agent, or do it by hand under the same rules: fetch the vendor
page, do not cite a search snippet, record the milestone name as the vendor words it, and report a
date as not found rather than inferring one. Output is a proposal at
`.claude/proposals/oem-anchors-<YYYY-MM-DD>.md`.

Expect dead or moved URLs. Two of the seven point at vendor portals that require a product selector
or have been reorganised — `support.hpe.com/connect/s/product?kmpmoid=7271241` and
`lifecycle.vmware.com`. Report a dead source explicitly; never swap a URL while keeping the old
date, or keep a URL while changing the date.

### 2. Apply, by hand

A human edits `reference_data.py`. Keep the existing formatting: nine keys in order, long URLs as a
parenthesised implicit-concatenation string, a `#` comment above any `aggregator` or `uncertain`
grade explaining why — matching `:62`, `:85`, `:95`, `:116-117`.

### 3. Validate without touching the live dataset

```bash
python data-tools/build_dataset.py --skip-sql
```

Generates and validates, writes CSVs and the eval report to `data-tools/output/`, and leaves
`data.sql` alone (`build_dataset.py:87-89`). Exits 1 if any error-severity check failed. Confirm
`real_anchors_have_source_url`, `source_confidence_domain` and `lifecycle_dates_ordered` all pass.

### 4. Regenerate and run the Java suite

```bash
python data-tools/build_dataset.py
cd backend && mvn test          # JAVA_HOME must be JDK 21 — see README.md:50-55
```

Then restart the backend: H2 is in-memory and only reads `data.sql` at startup
(`README.md:260-263`).

### 5. Check the Data Sources tab

The anchors surface through `RevenueIntelligenceOrchestrator.dataSources()` (`:283-312`), which reads
`oemModels.findByRealAnchorTrue()` and maps each to a `DataSourcesPayload.RealAnchor` carrying id,
oem, model name, category, `end_of_sale`, `end_of_support`, `source_confidence` and `source_url`.
`GET /api/data-sources` should show the updated dates and the count.

---

## What changes downstream when an anchor date moves

Anchor dates are not inert. Anticipate these:

**Severity bands.** `reference_data.eol_severity` (`:473-481`) bands months-to-end-of-support:
`< 0 → Critical`, `<= 12 → High`, `<= 24 → Medium`, else `Low`. The 45 derived rows are anchored to
`as_of` by `VARIANT_RELEASE_OFFSET_MONTHS = [-30, 18, 54]` (`:174`) so they spread across bands by
construction — but **the seven anchors sit wherever their real dates put them.** Pushing an
`end_of_support` outward can empty a band. That feeds two warning-severity checks:

- `eol_severity_spread` (`:369-393`, `WARNING`) — flags any band under 5% of the installed base,
  because "the signal cards and the heatmap are degenerate and nobody notices a broken threshold".
- `whitespace_signals_exist` (`:396-425`, `WARNING`) — needs critical/high lifecycle assets to exist
  at all, so `SignalDetectionService` has something to find.

Neither blocks the write (`all_clear` counts only `ERROR` failures, `data_evals.py:478-490`), so read
the report rather than relying on the exit code.

**Model ids shift if the anchor count changes.** Anchors are numbered `OEM-0001…N` and the derived
rows continue from `next_id = len(oem_models) + 1` (`generate_synthetic_data.py:362`). Adding an
eighth anchor renumbers **every synthetic model**. `real_anchors_have_source_url` compares against
`len(ref.REAL_ANCHORS)` so the count check self-adjusts, but any `OEM-00xx` id recorded anywhere
becomes stale, and `installed_base.oem_model_id` is regenerated wholesale.

**A new `category` needs three map entries.** Categories index into `UNIT_REFRESH_COST_USD`
(`reference_data.py:178-191`), `INSTALLED_BASE_CATEGORY_WEIGHTS`
(`generate_synthetic_data.py:89-102`) and `QUANTITY_RANGE` (`:105-118`). Introducing one without
adding all three will fail or produce nonsense. Prefer reusing an existing category.

**A moved severity boundary is a two-sided change.** `DeliveryEconomics.eolSeverity`
(`:209-223`) mirrors the Python banding — and note the asymmetry: the Java version has a
`NaN → "Low"` branch the Python has no equivalent of. Both files move together or the mirror claim
in their docstrings stops being true.

---

## Things this procedure does not do

- **It does not integrate a feed.** Cisco EoX, Flexera/Technopedia and endoflife.date are named
  out-of-scope at `README.md:358-359`.
- **It does not touch the 45 derived rows.** They are procedural by design and labelled
  `synthetic`; the evals fail if that changes.
- **It does not change the economics constants.** `LABOR_CONTENT`, `RESOURCE_MIX`,
  `ANNUAL_CAPACITY_HOURS` and friends are mirrored in `DeliveryEconomics.java` and guarded by
  `DeliveryEconomicsParityTest`. Unrelated to lifecycle dates, and a different procedure.
- **It does not automate the edit.** The anchors are the one piece of real data in the system; the
  point of a human applying them is that someone has read the source page.
