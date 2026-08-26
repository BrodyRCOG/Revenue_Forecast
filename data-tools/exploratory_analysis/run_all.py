"""
Run every exploratory analysis script and write an index page linking the charts.

    python data-tools/exploratory_analysis/run_all.py

Output lands in ``data-tools/output/analysis/``. Run it after ``build_dataset.py``, before wiring a
freshly generated dataset into the app -- the charts answer "does this data look like a real client
base" in a way pass/fail evals cannot.
"""

from __future__ import annotations

import sys
import traceback
from datetime import datetime, timezone

from _common import ANALYSIS_DIR

import coverage_sensitivity
import eol_severity
import opportunity_amounts
import workforce_vs_demand

SCRIPTS = [
    ("opportunity_amounts", "Opportunity amounts and win rates", opportunity_amounts.main,
     "Deal-size distributions by segment and type, and the closed sample each win rate rests on."),
    ("eol_severity", "Installed-base lifecycle risk", eol_severity.main,
     "How the estate is distributed across the EOL/EOS severity bands, and how much of it sits on "
     "a real sourced anchor rather than a procedural variant."),
    ("workforce_vs_demand", "Workforce sizing against delivery demand", workforce_vs_demand.main,
     "The eyeball version of the capacity plausibility eval: headcount against the demand it was "
     "sized for."),
    ("coverage_sensitivity", "Coverage-ratio sensitivity", coverage_sensitivity.main,
     "Offline cross-check that the what-if levers actually move the coverage ratio, and that the "
     "already-met guard holds."),
]

INDEX_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Revenue Intelligence Engine — dataset analysis</title>
<style>
  :root {{ color-scheme: light dark; }}
  body {{ font: 14px/1.55 system-ui, -apple-system, "Segoe UI", sans-serif;
         max-width: 1180px; margin: 0 auto; padding: 32px 24px 72px;
         background: #f9f9f7; color: #0b0b0b; }}
  h1 {{ font-size: 21px; margin: 0 0 4px; }}
  p.lede {{ color: #52514e; margin: 0 0 28px; max-width: 76ch; }}
  section {{ background: #fcfcfb; border: 1px solid rgba(11,11,11,.10); border-radius: 10px;
             padding: 18px 20px 20px; margin-bottom: 20px; }}
  h2 {{ font-size: 15px; margin: 0 0 4px; }}
  p.note {{ color: #52514e; font-size: 12.5px; margin: 0 0 14px; max-width: 84ch; }}
  img {{ width: 100%; height: auto; border-radius: 6px; }}
  footer {{ color: #898781; font-size: 11.5px; }}
  code {{ background: #f2f2ee; padding: 1px 4px; border-radius: 3px; }}
  @media (prefers-color-scheme: dark) {{
    body {{ background: #0d0d0d; color: #fff; }}
    section {{ background: #1a1a19; border-color: rgba(255,255,255,.10); }}
    p.lede, p.note {{ color: #c3c2b7; }}
    code {{ background: #201f1e; }}
  }}
</style>
</head>
<body>
<h1>Dataset analysis</h1>
<p class="lede">
  Development-time sanity checks on the generated dataset. These are a human aid, not a gate — the
  gate is <code>data-tools/data_evals.py</code>, which blocks the build. Nothing here runs as part
  of the application.
</p>
{sections}
<footer>Generated {timestamp} by <code>data-tools/exploratory_analysis/run_all.py</code>.</footer>
</body>
</html>
"""

SECTION_TEMPLATE = """<section>
  <h2>{title}</h2>
  <p class="note">{note}</p>
  <img src="{name}.png" alt="{title}">
</section>"""


def main() -> int:
    failures = []
    sections = []

    for name, title, entry, note in SCRIPTS:
        print(f"\n=== {title} ===")
        try:
            entry()
            sections.append(SECTION_TEMPLATE.format(name=name, title=title, note=note))
        except Exception as error:  # keep going: one broken chart should not hide the others
            failures.append((title, error))
            print(f"  FAILED: {error}")
            traceback.print_exc(limit=3)

    ANALYSIS_DIR.mkdir(parents=True, exist_ok=True)
    index = ANALYSIS_DIR / "index.html"
    index.write_text(
        INDEX_TEMPLATE.format(
            sections="\n".join(sections),
            timestamp=datetime.now(timezone.utc).replace(microsecond=0).isoformat()),
        encoding="utf-8")

    print(f"\nWrote {index}")
    if failures:
        print(f"\n{len(failures)} script(s) failed:")
        for title, error in failures:
            print(f"  - {title}: {error}")
        return 1
    print(f"{len(sections)} chart(s) written to {ANALYSIS_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
