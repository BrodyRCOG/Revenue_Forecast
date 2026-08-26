"""
Distribution of opportunity amounts by client segment and opportunity type.

What to look for: bank asset sizes are heavily right-skewed, so deal amounts should be too. If a
segment's distribution looks symmetric, or if Regional Bank deals are not visibly larger than
Credit Union deals, the tier multiplier in the generator has stopped biting.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from _common import SERIES, apply_style, load, plt, save, usd, usd_formatter


def main() -> None:
    apply_style()
    opportunities = load("opportunities")
    clients = load("clients")[["id", "segment", "revenue_tier"]]
    frame = opportunities.merge(clients, left_on="client_id", right_on="id", suffixes=("", "_client"))

    fig, axes = plt.subplots(1, 3, figsize=(15, 4.6))

    # 1. Amount distribution by segment -- box plot, ordered by median so it reads at a glance.
    segments = (frame.groupby("segment")["amount_usd"].median().sort_values(ascending=False).index.tolist())
    grouped = [frame.loc[frame["segment"] == segment, "amount_usd"].to_numpy() for segment in segments]

    box = axes[0].boxplot(grouped, vert=False, patch_artist=True, widths=0.55,
                          medianprops={"color": "#0b0b0b", "linewidth": 1.6},
                          flierprops={"marker": ".", "markersize": 3, "alpha": 0.4,
                                      "markerfacecolor": "#898781", "markeredgecolor": "none"})
    for patch, colour in zip(box["boxes"], SERIES):
        patch.set_facecolor(colour)
        patch.set_alpha(0.75)
        patch.set_edgecolor("none")
    axes[0].set_yticklabels(segments)
    axes[0].set_title("Deal amount by client segment")
    axes[0].set_xlabel("Amount (USD)")
    axes[0].xaxis.set_major_formatter(usd_formatter)
    axes[0].grid(axis="y", visible=False)

    # 2. Amount distribution by opportunity type -- same idea, different cut.
    types = (frame.groupby("opportunity_type")["amount_usd"].median().sort_values(ascending=False).index.tolist())
    by_type = [frame.loc[frame["opportunity_type"] == t, "amount_usd"].to_numpy() for t in types]
    box = axes[1].boxplot(by_type, vert=False, patch_artist=True, widths=0.55,
                          medianprops={"color": "#0b0b0b", "linewidth": 1.6},
                          flierprops={"marker": ".", "markersize": 3, "alpha": 0.4,
                                      "markerfacecolor": "#898781", "markeredgecolor": "none"})
    for patch, colour in zip(box["boxes"], SERIES):
        patch.set_facecolor(colour)
        patch.set_alpha(0.75)
        patch.set_edgecolor("none")
    axes[1].set_yticklabels(types)
    axes[1].set_title("Deal amount by opportunity type")
    axes[1].set_xlabel("Amount (USD)")
    axes[1].xaxis.set_major_formatter(usd_formatter)
    axes[1].grid(axis="y", visible=False)

    # 3. Realised win rate per type against the closed sample size behind it. The point of the
    #    chart is the sample sizes: thin cells are why the Java side smooths towards a parent rate.
    closed = frame[frame["status"].isin(["Won", "Lost"])]
    stats = (closed.assign(won=closed["status"].eq("Won").astype(int))
             .groupby("opportunity_type")
             .agg(win_rate=("won", "mean"), closed=("won", "size"))
             .sort_values("win_rate", ascending=True))

    bars = axes[2].barh(stats.index, stats["win_rate"], color=SERIES[0], height=0.55)
    for bar, (_, row) in zip(bars, stats.iterrows()):
        axes[2].text(bar.get_width() + 0.012, bar.get_y() + bar.get_height() / 2,
                     f"{row['win_rate']:.0%}  (n={int(row['closed'])})",
                     va="center", fontsize=9, color="#52514e")
    axes[2].set_xlim(0, max(0.8, stats["win_rate"].max() * 1.45))
    axes[2].set_title("Realised win rate, and the sample behind it")
    axes[2].set_xlabel("Won share of closed deals")
    axes[2].xaxis.set_major_formatter(lambda v, _p: f"{v:.0%}")
    axes[2].grid(axis="y", visible=False)

    fig.suptitle("Opportunity amounts and win rates", x=0.01, ha="left", fontsize=14, fontweight="600")
    fig.tight_layout(rect=(0, 0.03, 1, 0.94))

    save(fig, "opportunity_amounts",
         f"{len(frame):,} opportunities across {frame['client_id'].nunique()} clients. "
         f"Median deal {usd(frame['amount_usd'].median())}, "
         f"p95 {usd(np.percentile(frame['amount_usd'], 95))}.")

    print("\n  Amount summary by segment (USD):")
    summary = (frame.groupby("segment")["amount_usd"]
               .agg(["count", "median", "mean", "max"])
               .sort_values("median", ascending=False))
    with pd.option_context("display.float_format", lambda v: f"{v:,.0f}"):
        print(summary.to_string())


if __name__ == "__main__":
    main()
