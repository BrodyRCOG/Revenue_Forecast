"""
EOL/EOS severity distribution across the installed base.

What to look for: all four severity bands should be populated. A degenerate distribution -- almost
everything Critical, or almost nothing -- means the variant release-date offsets in the generator
have drifted relative to the as-of date, and the signal cards on the revenue page will either be
empty or a wall of red. ``data_evals.eol_severity_spread`` is the pass/fail version of this chart.

The right-hand panel is the one worth staring at: it separates the seven real, sourced anchors from
the procedural variants, so you can see how much of the lifecycle signal rests on real data.
"""

from __future__ import annotations

from datetime import date

import pandas as pd

import reference_data as ref
from _common import SEQUENTIAL, SERIES, SEVERITY_COLOR, apply_style, load, plt, save

BANDS = ["Critical", "High", "Medium", "Low"]


def months_between(start: date, end: date) -> float:
    return (end.year - start.year) * 12 + (end.month - start.month) + (end.day - start.day) / 30.44


def main() -> None:
    apply_style()
    as_of = date.today()

    assets = load("installed_base")
    models = load("oem_models")
    frame = assets.merge(models, left_on="oem_model_id", right_on="id", suffixes=("", "_model"))
    frame["end_of_support"] = pd.to_datetime(frame["end_of_support"]).dt.date
    frame["months_to_eos"] = frame["end_of_support"].map(lambda d: months_between(as_of, d))
    frame["severity"] = frame["months_to_eos"].map(ref.eol_severity)
    frame["is_real_anchor"] = frame["is_real_anchor"].astype(str).str.lower().isin(["true", "1"])

    fig, axes = plt.subplots(1, 3, figsize=(15, 4.6))

    # 1. Months to end of support -- the underlying continuous variable.
    axes[0].hist(frame["months_to_eos"], bins=42, color=SEQUENTIAL[3], edgecolor="none")
    axes[0].axvline(0, color=SEVERITY_COLOR["Critical"], linewidth=1.6, linestyle="--")
    axes[0].axvline(12, color=SEVERITY_COLOR["High"], linewidth=1.4, linestyle="--")
    axes[0].axvline(24, color=SEVERITY_COLOR["Medium"], linewidth=1.4, linestyle="--")
    axes[0].text(0, axes[0].get_ylim()[1] * 0.97, " out of support", fontsize=8,
                 color=SEVERITY_COLOR["Critical"], va="top")
    axes[0].set_title("Months until end of support")
    axes[0].set_xlabel("Months (negative = already lapsed)")
    axes[0].set_ylabel("Asset records")

    # 2. Severity by unit count, not record count -- 1 record of 60 switches is not 1 switch.
    units = (frame.groupby("severity")["quantity"].sum().reindex(BANDS).fillna(0))
    records = (frame.groupby("severity").size().reindex(BANDS).fillna(0))
    positions = range(len(BANDS))
    axes[1].bar([p - 0.2 for p in positions], records, width=0.38,
                color=[SEVERITY_COLOR[b] for b in BANDS], label="Asset records")
    axes[1].bar([p + 0.2 for p in positions], units / 10, width=0.38,
                color=[SEVERITY_COLOR[b] for b in BANDS], alpha=0.45, label="Units (÷10)")
    axes[1].set_xticks(list(positions))
    axes[1].set_xticklabels(BANDS)
    axes[1].set_title("Severity band, records and units")
    axes[1].set_ylabel("Count")
    axes[1].legend(loc="upper right")
    axes[1].grid(axis="x", visible=False)
    for position, band in zip(positions, BANDS):
        axes[1].text(position - 0.2, records[band], f"{int(records[band]):,}",
                     ha="center", va="bottom", fontsize=8, color="#52514e")

    # 3. Real anchors against procedural variants -- how much of the estate is anchored to a
    #    genuinely sourced lifecycle date.
    provenance = (frame.assign(kind=frame["is_real_anchor"].map({True: "Real anchor", False: "Procedural variant"}))
                  .groupby(["kind", "severity"]).size().unstack(fill_value=0).reindex(columns=BANDS, fill_value=0))
    bottom = [0.0] * len(provenance.index)
    for band in BANDS:
        axes[2].barh(provenance.index, provenance[band], left=bottom,
                     color=SEVERITY_COLOR[band], label=band, height=0.5,
                     edgecolor="#fcfcfb", linewidth=2)
        bottom = [b + v for b, v in zip(bottom, provenance[band])]
    axes[2].set_title("Provenance against severity")
    axes[2].set_xlabel("Asset records")
    axes[2].legend(loc="lower right", ncols=2)
    axes[2].grid(axis="y", visible=False)

    fig.suptitle("Installed-base lifecycle risk", x=0.01, ha="left", fontsize=14, fontweight="600")
    fig.tight_layout(rect=(0, 0.03, 1, 0.94))

    real_share = frame["is_real_anchor"].mean()
    save(fig, "eol_severity",
         f"{len(frame):,} asset records, {int(frame['quantity'].sum()):,} units, as of {as_of}. "
         f"{real_share:.1%} of records sit on one of the {int(models['is_real_anchor'].astype(str).str.lower().isin(['true','1']).sum())} "
         f"real, sourced OEM lifecycle anchors.")

    print("\n  Severity shares:")
    shares = (frame.groupby("severity").size() / len(frame)).reindex(BANDS).fillna(0)
    for band, share in shares.items():
        print(f"    {band:<9} {share:6.1%}  ({int(records[band]):,} records, {int(units[band]):,} units)")


if __name__ == "__main__":
    main()
