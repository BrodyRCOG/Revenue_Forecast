"""
How sensitive the coverage ratio is to the what-if levers.

This re-implements the coverage formula **offline, over the generated CSVs**, purely to sanity check
its shape before trusting the app's sliders. It is not the live path -- the live coverage ratio is
computed in Java by ``ForecastingService.pipelineCoverageRatio``, and nothing here runs at request
time. Treating this as a second implementation is the point: it already caught a real bug, where the
Java blended ratio folded closed quarters that had missed their target into a forward-looking
metric.

**Scope, so the two are not read as contradicting each other.** This models the *open CRM pipeline
only*; it does not reproduce signal detection, so the whitespace component of the app's forecast is
absent. Its absolute level therefore sits a few percent below the app's blended ratio -- currently
1.20x here against 1.30x in the app, the difference being whitespace. What is being cross-checked is
the *shape*: direction, sensitivity and the already-met guard. A divergence in shape is a bug; a
few percent in level is this scope difference.

Two things to look for:

1. **The curves move.** In the reference build the sliders changed the headline pipeline number and
   left coverage flat, because coverage was computed from raw CRM probabilities rather than from the
   override-aware forecast rows. A flat win-rate curve here means that bug is back.
2. **Nothing blows up.** Where a target is already met the ratio is undefined and simply absent --
   not an enormous multiple from dividing by a remainder that rounded to nothing.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

import reference_data as ref
from _common import SERIES, apply_style, load, plt, save, usd

DELTAS = np.round(np.arange(-0.40, 0.41, 0.05), 2)


def build() -> tuple[pd.DataFrame, pd.DataFrame, list[str]]:
    opportunities = load("opportunities")
    clients = load("clients")[["id", "segment"]]
    targets = load("targets")

    frame = opportunities.merge(clients, left_on="client_id", right_on="id", suffixes=("", "_client"))
    forecast_quarters = sorted(frame.loc[frame["status"] == "Open", "quarter"].unique())
    return frame, targets, forecast_quarters


def win_rates(frame: pd.DataFrame) -> dict[tuple[str, str], float]:
    """
    Hierarchically smoothed win rates, same shrinkage as the Java side: each (segment, type) cell is
    pulled towards its type rate by WIN_RATE_PRIOR_STRENGTH pseudo-observations.
    """
    closed = frame[frame["status"].isin(["Won", "Lost"])].assign(
        won=lambda f: f["status"].eq("Won").astype(int))
    prior = ref.WIN_RATE_PRIOR_STRENGTH
    global_rate = closed["won"].mean() if len(closed) else 0.0

    by_type = {}
    for opp_type, group in closed.groupby("opportunity_type"):
        by_type[opp_type] = (group["won"].sum() + prior * global_rate) / (len(group) + prior)

    rates = {}
    for (segment, opp_type), group in closed.groupby(["segment", "opportunity_type"]):
        parent = by_type.get(opp_type, global_rate)
        rates[(segment, opp_type)] = (group["won"].sum() + prior * parent) / (len(group) + prior)
    return rates


def coverage(frame: pd.DataFrame, targets: pd.DataFrame, rates: dict, forecast_quarters: list[str],
             win_delta: float = 0.0, size_delta: float = 0.0, target_delta: float = 0.0) -> pd.DataFrame:
    """Per-quarter coverage under one set of assumptions. Undefined quarters get NaN, never a number."""
    open_pipeline = frame[frame["status"] == "Open"].copy()
    open_pipeline["rate"] = [
        min(0.99, max(0.01, rates.get((row.segment, row.opportunity_type), 0.4) * (1 + win_delta)))
        for row in open_pipeline.itertuples()
    ]
    open_pipeline["weighted"] = open_pipeline["amount_usd"] * (1 + size_delta) * open_pipeline["rate"]

    pipeline = open_pipeline.groupby("quarter")["weighted"].sum()
    won = frame[frame["status"] == "Won"].groupby("quarter")["amount_usd"].sum()
    target = targets.groupby("quarter")["target_amount_usd"].sum() * (1 + target_delta)

    rows = []
    for quarter in forecast_quarters:
        remaining = float(target.get(quarter, 0.0)) - float(won.get(quarter, 0.0))
        already_met = remaining <= ref.COVERAGE_TARGET_MET_EPSILON_USD
        rows.append({
            "quarter": quarter,
            "pipeline": float(pipeline.get(quarter, 0.0)),
            "remaining": remaining,
            "target_already_met": already_met,
            "coverage": np.nan if already_met else float(pipeline.get(quarter, 0.0)) / remaining,
        })
    return pd.DataFrame(rows)


def blended(rows: pd.DataFrame) -> float:
    """One headline number: pipeline over remaining target, across quarters not already met."""
    live = rows[~rows["target_already_met"]]
    remaining = live["remaining"].sum()
    if remaining <= ref.COVERAGE_TARGET_MET_EPSILON_USD:
        return np.nan
    return live["pipeline"].sum() / remaining


def main() -> None:
    apply_style()
    frame, targets, forecast_quarters = build()
    rates = win_rates(frame)
    base = coverage(frame, targets, rates, forecast_quarters)

    levers = [
        ("Win rate", "win_delta", SERIES[0]),
        ("Deal size", "size_delta", SERIES[1]),
        ("Revenue target", "target_delta", SERIES[2]),
    ]

    fig, axes = plt.subplots(1, 3, figsize=(15, 4.6))

    # 1. Blended coverage against each lever, one line per lever, one shared y-axis.
    for label, keyword, colour in levers:
        values = [blended(coverage(frame, targets, rates, forecast_quarters, **{keyword: delta}))
                  for delta in DELTAS]
        axes[0].plot(DELTAS, values, marker="o", markersize=3.5, linewidth=2, color=colour, label=label)

    axes[0].axhline(1.0, color="#898781", linestyle="--", linewidth=1.2)
    axes[0].axvline(0.0, color="#e1e0d9", linewidth=1.2)
    axes[0].text(DELTAS[0], 1.0, " covered", fontsize=8, color="#898781", va="bottom")
    axes[0].set_title("Blended coverage against each lever")
    axes[0].set_xlabel("Applied delta")
    axes[0].set_ylabel("Coverage ratio")
    axes[0].xaxis.set_major_formatter(lambda v, _p: f"{v:+.0%}")
    axes[0].yaxis.set_major_formatter(lambda v, _p: f"{v:.2f}x")
    axes[0].legend(loc="upper left")

    # 2. Per-quarter coverage at baseline against a +25% win-rate scenario.
    scenario = coverage(frame, targets, rates, forecast_quarters, win_delta=0.25)
    positions = np.arange(len(forecast_quarters))
    axes[1].bar(positions - 0.2, base["coverage"], width=0.38, color=SERIES[0], label="Baseline")
    axes[1].bar(positions + 0.2, scenario["coverage"], width=0.38, color=SERIES[1],
                label="+25% win rate")
    axes[1].axhline(1.0, color="#898781", linestyle="--", linewidth=1.2)
    axes[1].set_xticks(positions)
    axes[1].set_xticklabels(forecast_quarters, rotation=20)
    axes[1].set_title("Coverage per forecast quarter")
    axes[1].set_ylabel("Coverage ratio")
    axes[1].yaxis.set_major_formatter(lambda v, _p: f"{v:.2f}x")
    axes[1].legend(loc="upper right")
    axes[1].grid(axis="x", visible=False)

    # 3. The guard: how close the remaining target gets to the epsilon that makes the ratio
    #    undefined. Bars near the line are the ones that used to produce five-figure ratios.
    axes[2].bar(positions, base["remaining"], width=0.5, color=SERIES[0])
    axes[2].axhline(ref.COVERAGE_TARGET_MET_EPSILON_USD, color=SERIES[7], linestyle="--",
                    linewidth=1.4, label=f"target-met epsilon ({usd(ref.COVERAGE_TARGET_MET_EPSILON_USD)})")
    axes[2].set_xticks(positions)
    axes[2].set_xticklabels(forecast_quarters, rotation=20)
    axes[2].set_title("Remaining target — the coverage denominator")
    axes[2].set_ylabel("Remaining target (USD)")
    axes[2].yaxis.set_major_formatter(lambda v, _p: usd(v))
    axes[2].legend(loc="upper right")
    axes[2].grid(axis="x", visible=False)

    fig.suptitle("Coverage-ratio sensitivity to the what-if levers", x=0.01, ha="left",
                 fontsize=14, fontweight="600")
    fig.tight_layout(rect=(0, 0.03, 1, 0.94))

    save(fig, "coverage_sensitivity",
         f"Offline re-implementation over {len(forecast_quarters)} forecast quarters, for "
         f"cross-checking the app's sliders. Open CRM pipeline only — whitespace is not modelled "
         f"here, so this sits a few percent below the app. Baseline blended coverage "
         f"{blended(base):.2f}x.")

    print("\n  Blended coverage by lever:")
    for label, keyword, _ in levers:
        row = "  ".join(
            f"{delta:+.0%}:{blended(coverage(frame, targets, rates, forecast_quarters, **{keyword: delta})):.2f}x"
            for delta in (-0.25, -0.10, 0.0, 0.10, 0.25))
        print(f"    {label:<16} {row}")


if __name__ == "__main__":
    main()
