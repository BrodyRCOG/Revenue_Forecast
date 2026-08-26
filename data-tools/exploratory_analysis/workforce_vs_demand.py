"""
Workforce size against the delivery demand it was sized for.

This is the eyeball version of ``data_evals.capacity_vs_historical_demand_plausibility`` -- the
regression guard for the "hiring recommendations imply 5-6x current headcount" bug. That bug had a
simple shape on this chart: points scattered far off the diagonal, because headcount and revenue
scale were generated independently of each other.

What to look for: every resource group should sit close to the diagonal, and implied utilisation
should cluster around the 0.85 target with the generator's deliberate 0.82-1.15 staffing jitter
spread either side of it.
"""

from __future__ import annotations

import pandas as pd

import reference_data as ref
from _common import SERIES, STATUS, apply_style, load, plt, save


def main() -> None:
    apply_style()
    billing = load("project_billing")
    workforce = load("workforce")

    quarters = sorted(billing["quarter"].unique())
    # Drop the newest quarter: it is in progress, so its hours are only partial.
    complete = quarters[:-1] if len(quarters) > 1 else quarters
    history = billing[billing["quarter"].isin(complete)]

    demand = (history.groupby("resource_group")["billed_hours"].sum() / len(complete)).rename("avg_demand_hours")
    frame = workforce.merge(demand, left_on="resource_group", right_index=True, how="left").fillna({"avg_demand_hours": 0.0})

    frame["quarterly_capacity_per_fte"] = frame["annual_capacity_hours_per_fte"] / 4.0
    frame["required_headcount"] = frame["avg_demand_hours"] / (
        frame["quarterly_capacity_per_fte"] * ref.TARGET_UTILIZATION)
    frame["implied_utilization"] = frame["avg_demand_hours"] / (
        frame["headcount"] * frame["quarterly_capacity_per_fte"])
    frame = frame.sort_values("headcount", ascending=False)

    fig, axes = plt.subplots(1, 3, figsize=(15, 4.6))

    # 1. Actual headcount against demand-implied headcount. The diagonal is the whole point.
    limit = max(frame["headcount"].max(), frame["required_headcount"].max()) * 1.18
    axes[0].plot([0, limit], [0, limit], color="#898781", linewidth=1.2, linestyle="--",
                 label="perfectly sized")
    # +/-20% band: inside this, the two models agree.
    axes[0].fill_between([0, limit], [0, limit * 0.8], [0, limit * 1.2],
                         color=SERIES[0], alpha=0.08, linewidth=0)
    axes[0].scatter(frame["required_headcount"], frame["headcount"], s=90,
                    color=SERIES[0], edgecolor="#fcfcfb", linewidth=2, zorder=3)
    for _, row in frame.iterrows():
        axes[0].annotate(row["resource_group"].replace(" & ", " &\n"),
                         (row["required_headcount"], row["headcount"]),
                         textcoords="offset points", xytext=(8, -3), fontsize=8, color="#52514e")
    axes[0].set_xlim(0, limit)
    axes[0].set_ylim(0, limit)
    axes[0].set_title("Headcount against demand-implied headcount")
    axes[0].set_xlabel("Required FTE (historical demand at target utilisation)")
    axes[0].set_ylabel("Generated FTE")
    axes[0].legend(loc="lower right")

    # 2. Implied utilisation per group against the target and the jitter band.
    low, high = ref.STAFFING_FACTOR_RANGE
    axes[1].axvspan(ref.TARGET_UTILIZATION / high, ref.TARGET_UTILIZATION / low,
                    color=STATUS["good"], alpha=0.10, linewidth=0,
                    label=f"staffing jitter band ({low}-{high})")
    axes[1].axvline(ref.TARGET_UTILIZATION, color="#898781", linestyle="--", linewidth=1.2,
                    label=f"target {ref.TARGET_UTILIZATION:.0%}")
    axes[1].axvline(ref.OVER_UTILIZATION_THRESHOLD, color=STATUS["critical"], linestyle=":",
                    linewidth=1.4, label=f"signal threshold {ref.OVER_UTILIZATION_THRESHOLD:.0%}")
    axes[1].barh(frame["resource_group"], frame["implied_utilization"], height=0.5,
                 color=SERIES[0])
    for _, row in frame.iterrows():
        axes[1].text(row["implied_utilization"] + 0.012, row["resource_group"],
                     f"{row['implied_utilization']:.0%}", va="center", fontsize=8, color="#52514e")
    axes[1].set_xlim(0, max(1.15, frame["implied_utilization"].max() * 1.25))
    axes[1].set_title("Implied utilisation from historical demand")
    axes[1].set_xlabel("Demand hours / raw capacity")
    axes[1].xaxis.set_major_formatter(lambda v, _p: f"{v:.0%}")
    axes[1].legend(loc="lower right")
    axes[1].grid(axis="y", visible=False)

    # 3. Demand hours per quarter per group -- how steady the historical load actually is.
    per_quarter = (billing.groupby(["quarter", "resource_group"])["billed_hours"].sum()
                   .unstack(fill_value=0).reindex(quarters))
    for slot, group in enumerate(per_quarter.columns):
        axes[2].plot(per_quarter.index, per_quarter[group], marker="o", markersize=4,
                     linewidth=2, color=SERIES[slot % len(SERIES)], label=group)
    axes[2].set_title("Delivered hours per quarter")
    axes[2].set_ylabel("Billed hours")
    axes[2].tick_params(axis="x", rotation=45)
    axes[2].legend(loc="upper left", ncols=2, fontsize=7.5)

    fig.suptitle("Workforce sizing against delivery demand", x=0.01, ha="left",
                 fontsize=14, fontweight="600")
    fig.tight_layout(rect=(0, 0.03, 1, 0.94))

    fleet_ratio = frame["headcount"].sum() / max(1e-9, frame["required_headcount"].sum())
    save(fig, "workforce_vs_demand",
         f"Fleet headcount is {fleet_ratio:.2f}x demand-implied, averaged over "
         f"{len(complete)} complete quarters. Points far off the diagonal are the signature of the "
         f"bug this chart exists to catch.")

    print("\n  Per-group sizing:")
    table = frame[["resource_group", "headcount", "required_headcount", "avg_demand_hours",
                   "implied_utilization"]]
    with pd.option_context("display.float_format", lambda v: f"{v:,.2f}"):
        print(table.to_string(index=False))
    print(f"\n  Fleet ratio: {fleet_ratio:.3f}x (0.70-1.40 is the eval's pass band)")


if __name__ == "__main__":
    main()
