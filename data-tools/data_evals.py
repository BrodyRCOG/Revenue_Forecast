"""
Data-layer evals -- the first of the three eval layers (spec section 6).

These run *offline, before the Java app ever sees the data*. They are Python because the thing
they check is Python's output: the generated dataset. Pipeline- and narrative-layer evals are
JUnit tests on the Java side, because the thing they check is Java code.

``build_dataset.py`` refuses to write ``data.sql`` if any ``error``-severity check fails, so a
broken generator run cannot reach the backend.

Run standalone against already-written CSVs::

    python data-tools/data_evals.py

The load-bearing check here is :func:`capacity_vs_historical_demand_plausibility`. It is the
regression guard for the "hiring recommendations imply 5-6x current headcount" bug: workforce
headcount and the delivery demand implied by revenue must stay within a believable ratio of each
other. If someone edits the generator so workforce is sized independently of demand again, this
check fails and the build stops.
"""

from __future__ import annotations

import json
import sys
from dataclasses import asdict, dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable

import reference_data as ref
from generate_synthetic_data import Dataset, quarter_index

ERROR = "error"
WARNING = "warning"

Tables = dict[str, list[dict]]


@dataclass
class EvalResult:
    name: str
    layer: str
    severity: str
    passed: bool
    message: str
    details: dict[str, Any]

    def to_dict(self) -> dict:
        return asdict(self)


# --------------------------------------------------------------------------------------
# Coercion helpers -- checks must work on both in-memory rows and CSV-loaded strings.
# --------------------------------------------------------------------------------------

def _f(value) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _i(value) -> int | None:
    f = _f(value)
    return None if f is None else int(round(f))


def _b(value) -> bool:
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"true", "1", "yes", "t"}


def _d(value) -> date | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    try:
        return date.fromisoformat(str(value)[:10])
    except ValueError:
        return None


def _ids(rows: Iterable[dict], key: str = "id") -> set[str]:
    return {str(r[key]) for r in rows if r.get(key) is not None}


# --------------------------------------------------------------------------------------
# Individual checks
# --------------------------------------------------------------------------------------

def primary_keys_unique(t: Tables) -> EvalResult:
    offenders = {}
    for table in ("clients", "oem_models", "installed_base", "contracts", "opportunities",
                  "project_billing", "workforce", "targets"):
        rows = t.get(table, [])
        ids = [str(r.get("id")) for r in rows]
        if len(ids) != len(set(ids)):
            offenders[table] = len(ids) - len(set(ids))
    return EvalResult(
        "primary_keys_unique", "data", ERROR, not offenders,
        "All table primary keys are unique." if not offenders
        else f"Duplicate primary keys: {offenders}",
        {"duplicate_counts": offenders})


def referential_integrity(t: Tables) -> EvalResult:
    client_ids = _ids(t["clients"])
    model_ids = _ids(t["oem_models"])
    broken: dict[str, int] = {}

    def check(table: str, column: str, valid: set[str]) -> None:
        bad = sum(1 for r in t.get(table, []) if str(r.get(column)) not in valid)
        if bad:
            broken[f"{table}.{column}"] = bad

    check("installed_base", "client_id", client_ids)
    check("installed_base", "oem_model_id", model_ids)
    check("contracts", "client_id", client_ids)
    check("opportunities", "client_id", client_ids)
    check("project_billing", "client_id", client_ids)
    check("project_billing", "resource_group", set(ref.RESOURCE_GROUPS))
    check("workforce", "resource_group", set(ref.RESOURCE_GROUPS))
    check("targets", "practice", set(ref.PRACTICES))
    check("opportunities", "practice", set(ref.PRACTICES))
    check("opportunities", "opportunity_type", set(ref.OPPORTUNITY_TYPES))

    return EvalResult(
        "referential_integrity", "data", ERROR, not broken,
        "Every foreign key and enum column resolves." if not broken
        else f"Unresolved references: {broken}",
        {"broken": broken})


def source_confidence_domain(t: Tables) -> EvalResult:
    bad = sorted({str(r.get("source_confidence")) for r in t["oem_models"]
                  if str(r.get("source_confidence")) not in ref.VALID_SOURCE_CONFIDENCE})
    return EvalResult(
        "source_confidence_domain", "data", ERROR, not bad,
        f"source_confidence values all within {sorted(ref.VALID_SOURCE_CONFIDENCE)}." if not bad
        else f"Illegal source_confidence values: {bad}",
        {"illegal_values": bad, "allowed": sorted(ref.VALID_SOURCE_CONFIDENCE)})


def real_anchors_have_source_url(t: Tables) -> EvalResult:
    anchors = [r for r in t["oem_models"] if _b(r.get("is_real_anchor"))]
    synthetic = [r for r in t["oem_models"] if not _b(r.get("is_real_anchor"))]

    missing_url = [r["id"] for r in anchors if not str(r.get("source_url") or "").startswith("http")]
    anchor_marked_synthetic = [r["id"] for r in anchors if r.get("source_confidence") == "synthetic"]
    synthetic_not_marked = [r["id"] for r in synthetic if r.get("source_confidence") != "synthetic"]
    synthetic_with_url = [r["id"] for r in synthetic if str(r.get("source_url") or "").strip()]

    problems = {
        "anchors_missing_source_url": missing_url,
        "anchors_labelled_synthetic": anchor_marked_synthetic,
        "synthetic_rows_not_labelled_synthetic": synthetic_not_marked,
        "synthetic_rows_claiming_a_source_url": synthetic_with_url,
    }
    problems = {k: v for k, v in problems.items() if v}
    expected_anchors = len(ref.REAL_ANCHORS)
    if len(anchors) != expected_anchors:
        problems["anchor_count"] = f"expected {expected_anchors}, found {len(anchors)}"

    return EvalResult(
        "real_anchors_have_source_url", "data", ERROR, not problems,
        f"All {len(anchors)} real anchors carry a source URL; "
        f"all {len(synthetic)} derived rows are labelled synthetic." if not problems
        else f"Provenance problems: {problems}",
        {"real_anchor_count": len(anchors), "synthetic_count": len(synthetic), "problems": problems})


def lifecycle_dates_ordered(t: Tables) -> EvalResult:
    bad = []
    for r in t["oem_models"]:
        release, eos, eosupport = _d(r.get("release_date")), _d(r.get("end_of_sale")), _d(r.get("end_of_support"))
        if None in (release, eos, eosupport) or not (release <= eos <= eosupport):
            bad.append(r["id"])
    return EvalResult(
        "lifecycle_dates_ordered", "data", ERROR, not bad,
        "release_date <= end_of_sale <= end_of_support holds for every model." if not bad
        else f"{len(bad)} model(s) have out-of-order lifecycle dates: {bad[:8]}",
        {"offending_ids": bad[:20], "offending_count": len(bad)})


def monetary_and_probability_ranges(t: Tables) -> EvalResult:
    problems: dict[str, int] = {}

    def flag(label: str, count: int) -> None:
        if count:
            problems[label] = count

    flag("opportunity_amount_not_positive",
         sum(1 for r in t["opportunities"] if (_f(r.get("amount_usd")) or 0) <= 0))
    flag("opportunity_probability_out_of_range",
         sum(1 for r in t["opportunities"]
             if not (0.0 <= (_f(r.get("probability")) if _f(r.get("probability")) is not None else -1) <= 1.0)))
    flag("opportunity_status_illegal",
         sum(1 for r in t["opportunities"] if r.get("status") not in ("Open", "Won", "Lost")))
    flag("contract_arr_not_positive",
         sum(1 for r in t["contracts"] if (_f(r.get("arr_usd")) or 0) <= 0))
    flag("contract_renewal_risk_illegal",
         sum(1 for r in t["contracts"]
             if r.get("renewal_risk") not in ("Low", "Medium", "High", "Critical")))
    flag("target_not_positive",
         sum(1 for r in t["targets"] if (_f(r.get("target_amount_usd")) or 0) <= 0))
    flag("billed_hours_negative",
         sum(1 for r in t["project_billing"] if (_f(r.get("billed_hours")) or 0) < 0))
    flag("utilization_out_of_range",
         sum(1 for r in t["workforce"]
             if not (0.2 <= (_f(r.get("current_utilization")) or 0) <= 1.2)))
    flag("installed_base_quantity_not_positive",
         sum(1 for r in t["installed_base"] if (_i(r.get("quantity")) or 0) <= 0))

    return EvalResult(
        "monetary_and_probability_ranges", "data", ERROR, not problems,
        "Amounts positive, probabilities in [0,1], statuses and risk grades legal." if not problems
        else f"Out-of-range values: {problems}",
        {"problems": problems})


def closed_history_is_sufficient(t: Tables) -> EvalResult:
    """Bayesian smoothing needs a real closed sample, and the win rate must be believable."""
    closed = [r for r in t["opportunities"] if r.get("status") in ("Won", "Lost")]
    won = [r for r in closed if r.get("status") == "Won"]
    open_rows = [r for r in t["opportunities"] if r.get("status") == "Open"]
    win_rate = len(won) / len(closed) if closed else 0.0

    ok = len(closed) >= 60 and len(open_rows) >= 30 and 0.20 <= win_rate <= 0.70
    return EvalResult(
        "closed_history_is_sufficient", "data", ERROR, ok,
        f"{len(closed)} closed opportunities ({win_rate:.1%} won), {len(open_rows)} open."
        if ok else
        f"Insufficient or implausible history: {len(closed)} closed, {len(open_rows)} open, "
        f"win rate {win_rate:.1%} (need >=60 closed, >=30 open, win rate 20-70%).",
        {"closed": len(closed), "won": len(won), "open": len(open_rows),
         "overall_win_rate": round(win_rate, 4)})


def capacity_vs_historical_demand_plausibility(t: Tables) -> EvalResult:
    """
    THE regression guard for the "hire 5-6x your bench" bug.

    Recomputes average quarterly demand hours per resource group straight from
    ``project_billing`` -- the same demand model the generator sized workforce against and the
    same transform the Java capacity service applies to forecast dollars -- and asserts that the
    generated headcount is a believable fit for it. If workforce is ever sized independently of
    revenue scale again, the implied utilisation drifts far from target and this fails.
    """
    billing = t["project_billing"]
    workforce = {r["resource_group"]: r for r in t["workforce"]}
    if not billing or not workforce:
        return EvalResult("capacity_vs_historical_demand_plausibility", "data", ERROR, False,
                          "No billing or workforce rows to compare.", {})

    quarters = sorted({str(r["quarter"]) for r in billing}, key=quarter_index)
    # Drop the newest quarter: it is in progress, so its hours are only partial.
    complete = set(quarters[:-1]) if len(quarters) > 1 else set(quarters)

    demand: dict[str, float] = {g: 0.0 for g in ref.RESOURCE_GROUPS}
    for r in billing:
        if str(r["quarter"]) in complete:
            demand[str(r["resource_group"])] += _f(r.get("billed_hours")) or 0.0
    n_quarters = max(1, len(complete))

    per_group: dict[str, dict[str, float]] = {}
    offenders: list[str] = []
    total_required = 0.0
    total_headcount = 0

    for group, total_hours in demand.items():
        avg_hours = total_hours / n_quarters
        row = workforce.get(group)
        if row is None:
            offenders.append(f"{group}: no workforce row")
            continue
        headcount = _i(row.get("headcount")) or 0
        q_capacity = ref.quarterly_capacity_hours(group)
        required = avg_hours / (q_capacity * ref.TARGET_UTILIZATION) if avg_hours > 0 else 0.0
        implied_utilization = avg_hours / (headcount * q_capacity) if headcount else 0.0

        total_required += required
        total_headcount += headcount
        per_group[group] = {
            "avg_quarterly_demand_hours": round(avg_hours, 1),
            "headcount": headcount,
            "demand_implied_headcount": round(required, 2),
            "implied_utilization": round(implied_utilization, 4),
        }
        # Target utilisation is 0.85 with a 0.82-1.15 staffing jitter, so 0.72-1.05 is the
        # legitimate band. Anything outside means demand and headcount are out of scale.
        if not (0.50 <= implied_utilization <= 1.10):
            offenders.append(
                f"{group}: implied utilisation {implied_utilization:.2f} outside 0.50-1.10 "
                f"(demand {avg_hours:.0f}h/qtr vs {headcount} FTE)")

    fleet_ratio = total_headcount / total_required if total_required > 0 else 0.0
    if not (0.70 <= fleet_ratio <= 1.40):
        offenders.append(
            f"fleet-wide headcount is {fleet_ratio:.2f}x demand-implied "
            f"({total_headcount} FTE vs {total_required:.1f} required); expected 0.70-1.40x")

    return EvalResult(
        "capacity_vs_historical_demand_plausibility", "data", ERROR, not offenders,
        f"Workforce is {fleet_ratio:.2f}x demand-implied headcount across "
        f"{len(per_group)} resource groups; every group's implied utilisation is in band."
        if not offenders else "Capacity/demand mismatch -- " + "; ".join(offenders),
        {"quarters_used": sorted(complete, key=quarter_index),
         "fleet_headcount": total_headcount,
         "fleet_demand_implied_headcount": round(total_required, 2),
         "fleet_ratio": round(fleet_ratio, 4),
         "per_group": per_group,
         "offenders": offenders})


def pipeline_vs_history_scale(t: Tables) -> EvalResult:
    """Open pipeline must be in the same league as historical wins, or coverage ratios go silly."""
    opps = t["opportunities"]
    won_by_quarter: dict[str, float] = {}
    weighted_open_by_quarter: dict[str, float] = {}
    for r in opps:
        amount = _f(r.get("amount_usd")) or 0.0
        quarter = str(r.get("quarter"))
        if r.get("status") == "Won":
            won_by_quarter[quarter] = won_by_quarter.get(quarter, 0.0) + amount
        elif r.get("status") == "Open":
            # Rough weighting; the point is order of magnitude, not precision.
            weighted_open_by_quarter[quarter] = (
                weighted_open_by_quarter.get(quarter, 0.0) + amount * 0.42)

    if not won_by_quarter or not weighted_open_by_quarter:
        return EvalResult("pipeline_vs_history_scale", "data", ERROR, False,
                          "Missing won history or open pipeline.", {})

    avg_won = sum(won_by_quarter.values()) / len(won_by_quarter)
    avg_open = sum(weighted_open_by_quarter.values()) / len(weighted_open_by_quarter)
    ratio = avg_open / avg_won if avg_won else 0.0
    ok = 0.5 <= ratio <= 3.0
    return EvalResult(
        "pipeline_vs_history_scale", "data", ERROR, ok,
        f"Weighted open pipeline averages {ratio:.2f}x historical won revenue per quarter."
        if ok else
        f"Weighted open pipeline is {ratio:.2f}x historical won revenue per quarter "
        f"(expected 0.5-3.0x) -- coverage ratios will be implausible.",
        {"avg_won_per_quarter": round(avg_won, 2),
         "avg_weighted_open_per_quarter": round(avg_open, 2),
         "ratio": round(ratio, 4)})


def targets_cover_every_practice_quarter(t: Tables) -> EvalResult:
    seen = {(str(r["practice"]), str(r["quarter"])) for r in t["targets"]}
    duplicates = len(t["targets"]) - len(seen)
    quarters = sorted({q for _, q in seen}, key=quarter_index)
    missing = [(p, q) for p in ref.PRACTICES for q in quarters if (p, q) not in seen]
    ok = not missing and duplicates == 0
    return EvalResult(
        "targets_cover_every_practice_quarter", "data", ERROR, ok,
        f"{len(seen)} unique (practice, quarter) targets across {len(quarters)} quarters."
        if ok else f"{len(missing)} missing and {duplicates} duplicate target rows.",
        {"missing": missing[:20], "duplicate_rows": duplicates, "quarters": quarters})


def eol_severity_spread(t: Tables) -> EvalResult:
    """
    Warning-only. Every severity band should have some assets in it, otherwise the signal cards
    and the heatmap are degenerate and nobody notices a broken threshold.
    """
    from generate_synthetic_data import months_between  # local: avoids a cycle at import time

    models = {str(r["id"]): r for r in t["oem_models"]}
    as_of = date.today()
    counts = {"Critical": 0, "High": 0, "Medium": 0, "Low": 0}
    for r in t["installed_base"]:
        model = models.get(str(r.get("oem_model_id")))
        eos = _d(model.get("end_of_support")) if model else None
        if eos is None:
            continue
        counts[ref.eol_severity(months_between(as_of, eos))] += 1

    total = sum(counts.values())
    thin = [band for band, n in counts.items() if total and n / total < 0.05]
    return EvalResult(
        "eol_severity_spread", "data", WARNING, not thin,
        f"Lifecycle severity spread across {total} assets: {counts}." if not thin
        else f"Severity bands under 5% of the installed base: {thin} (counts {counts}).",
        {"counts": counts, "total_assets": total,
         "share": {k: round(v / total, 4) if total else 0.0 for k, v in counts.items()}})


def whitespace_signals_exist(t: Tables) -> EvalResult:
    """
    Warning-only sanity check that the *inputs* to signal detection are present: at-risk
    contracts, critical/high lifecycle assets, and hot resource groups. Java owns the actual
    rule logic; this just makes sure it will have something to find.
    """
    from generate_synthetic_data import months_between

    at_risk_contracts = sum(1 for r in t["contracts"]
                            if r.get("renewal_risk") in ("High", "Critical"))
    models = {str(r["id"]): r for r in t["oem_models"]}
    as_of = date.today()
    urgent_assets = 0
    for r in t["installed_base"]:
        model = models.get(str(r.get("oem_model_id")))
        eos = _d(model.get("end_of_support")) if model else None
        if eos is not None and ref.eol_severity(months_between(as_of, eos)) in ("Critical", "High"):
            urgent_assets += 1
    hot_groups = sum(1 for r in t["workforce"]
                     if (_f(r.get("current_utilization")) or 0) > ref.OVER_UTILIZATION_THRESHOLD)

    ok = at_risk_contracts > 0 and urgent_assets > 0
    return EvalResult(
        "whitespace_signals_exist", "data", WARNING, ok,
        f"Signal inputs present: {urgent_assets} critical/high lifecycle assets, "
        f"{at_risk_contracts} at-risk contracts, {hot_groups} over-utilised resource groups."
        if ok else
        f"Thin signal inputs: {urgent_assets} urgent assets, {at_risk_contracts} at-risk contracts.",
        {"urgent_assets": urgent_assets, "at_risk_contracts": at_risk_contracts,
         "over_utilised_groups": hot_groups})


CHECKS: list[Callable[[Tables], EvalResult]] = [
    primary_keys_unique,
    referential_integrity,
    source_confidence_domain,
    real_anchors_have_source_url,
    lifecycle_dates_ordered,
    monetary_and_probability_ranges,
    closed_history_is_sufficient,
    capacity_vs_historical_demand_plausibility,
    pipeline_vs_history_scale,
    targets_cover_every_practice_quarter,
    eol_severity_spread,
    whitespace_signals_exist,
]


# --------------------------------------------------------------------------------------
# Runner
# --------------------------------------------------------------------------------------

def tables_from_dataset(data: Dataset) -> Tables:
    return {
        "clients": data.clients,
        "oem_models": data.oem_models,
        "installed_base": data.installed_base,
        "contracts": data.contracts,
        "opportunities": data.opportunities,
        "project_billing": data.project_billing,
        "workforce": data.workforce,
        "targets": data.targets,
    }


def tables_from_csv(output_dir: Path) -> Tables:
    import pandas as pd

    tables: Tables = {}
    for name in ("clients", "oem_models", "installed_base", "contracts", "opportunities",
                 "project_billing", "workforce", "targets"):
        path = output_dir / f"{name}.csv"
        if not path.exists():
            raise FileNotFoundError(
                f"{path} not found. Run `python data-tools/build_dataset.py` first.")
        frame = pd.read_csv(path, dtype=str, keep_default_na=False, na_values=[""])
        tables[name] = frame.where(frame.notna(), None).to_dict(orient="records")
    return tables


def run_data_evals(tables: Tables, seed: int | None = None, as_of: date | None = None) -> dict:
    results = [check(tables) for check in CHECKS]
    errors = [r for r in results if not r.passed and r.severity == ERROR]
    warnings = [r for r in results if not r.passed and r.severity == WARNING]

    return {
        "layer": "data",
        "generated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "seed": seed,
        "as_of": as_of.isoformat() if as_of else None,
        "total": len(results),
        "passed": len(results) - len(errors) - len(warnings),
        "errors": len(errors),
        "warnings": len(warnings),
        "all_clear": not errors,
        "row_counts": {name: len(rows) for name, rows in sorted(tables.items())},
        "checks": [r.to_dict() for r in results],
    }


def write_report(report: dict, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    return path


def print_report(report: dict) -> None:
    print(f"\nData-layer evals -- {report['passed']}/{report['total']} passed, "
          f"{report['errors']} error(s), {report['warnings']} warning(s)")
    print("-" * 78)
    for check in report["checks"]:
        mark = "PASS" if check["passed"] else ("FAIL" if check["severity"] == ERROR else "WARN")
        print(f"  [{mark:4}] {check['name']}")
        print(f"          {check['message']}")
    print("-" * 78)


def main() -> int:
    output_dir = Path(__file__).resolve().parent / "output"
    report = run_data_evals(tables_from_csv(output_dir))
    write_report(report, output_dir / "data_eval_results.json")
    print_report(report)
    print(f"Report written to {output_dir / 'data_eval_results.json'}")
    return 0 if report["all_clear"] else 1


if __name__ == "__main__":
    sys.exit(main())
