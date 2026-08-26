"""
Shared plumbing for the exploratory analysis scripts.

These scripts are a **development aid**, not part of anything. They read the CSVs the generator
wrote and produce charts under ``data-tools/output/analysis/`` so a human can look at the dataset
before it is wired into the app. Nothing in the Spring Boot application imports any of this, and
nothing here runs at request time.

They are kept separate from the other two Python roles on purpose:

* ``generate_synthetic_data.py`` -- generation
* ``data_evals.py``             -- pass/fail validation that gates the build
* ``exploratory_analysis/``     -- looking at the data with your eyes

The palette matches the frontend's so a chart here reads the same way as the one in the app.
"""

from __future__ import annotations

import sys
from pathlib import Path

import matplotlib
import pandas as pd

matplotlib.use("Agg")  # No display in a build environment; write files.
import matplotlib.pyplot as plt  # noqa: E402  (must follow the backend selection)

HERE = Path(__file__).resolve().parent
DATA_TOOLS = HERE.parent
OUTPUT_DIR = DATA_TOOLS / "output"
ANALYSIS_DIR = OUTPUT_DIR / "analysis"

# Make `import reference_data` work when a script is run directly from this directory.
if str(DATA_TOOLS) not in sys.path:
    sys.path.insert(0, str(DATA_TOOLS))

# Same categorical order and status colours as the frontend, light mode.
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948"]
SEQUENTIAL = ["#cde2fb", "#9ec5f4", "#6da7ec", "#3987e5", "#256abf", "#184f95", "#0d366b"]
STATUS = {
    "good": "#0ca30c",
    "warning": "#fab219",
    "serious": "#ec835a",
    "critical": "#d03b3b",
}
SEVERITY_COLOR = {
    "Critical": STATUS["critical"],
    "High": STATUS["serious"],
    "Medium": STATUS["warning"],
    "Low": STATUS["good"],
}

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_MUTED = "#898781"
GRID = "#e1e0d9"


def apply_style() -> None:
    """Recessive chrome, thin marks, no chart junk."""
    plt.rcParams.update({
        "figure.facecolor": SURFACE,
        "axes.facecolor": SURFACE,
        "axes.edgecolor": GRID,
        "axes.labelcolor": INK,
        "axes.titlecolor": INK,
        "axes.titlesize": 12,
        "axes.titleweight": "600",
        "axes.labelsize": 10,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.grid": True,
        "grid.color": GRID,
        "grid.linewidth": 0.8,
        "xtick.color": INK_MUTED,
        "ytick.color": INK_MUTED,
        "xtick.labelsize": 9,
        "ytick.labelsize": 9,
        "text.color": INK,
        "legend.frameon": False,
        "legend.fontsize": 9,
        "font.family": "sans-serif",
        "font.sans-serif": ["Segoe UI", "DejaVu Sans", "sans-serif"],
        "figure.dpi": 130,
    })


def load(table: str) -> pd.DataFrame:
    """Read one generated CSV. Raises with a useful message if the generator has not been run."""
    path = OUTPUT_DIR / f"{table}.csv"
    if not path.exists():
        raise FileNotFoundError(
            f"{path} not found. Run `python data-tools/build_dataset.py` first."
        )
    return pd.read_csv(path)


def save(fig, name: str, caption: str = "") -> Path:
    """Write a figure and return its path."""
    ANALYSIS_DIR.mkdir(parents=True, exist_ok=True)
    path = ANALYSIS_DIR / f"{name}.png"
    if caption:
        fig.text(0.01, 0.005, caption, fontsize=8, color=INK_MUTED, ha="left", va="bottom")
    fig.savefig(path, bbox_inches="tight", facecolor=SURFACE)
    plt.close(fig)
    print(f"  wrote {path.relative_to(DATA_TOOLS)}")
    return path


def usd(value: float) -> str:
    magnitude = abs(value)
    sign = "-" if value < 0 else ""
    if magnitude >= 1_000_000:
        return f"{sign}${magnitude / 1_000_000:.1f}M"
    if magnitude >= 1_000:
        return f"{sign}${magnitude / 1_000:.0f}k"
    return f"{sign}${magnitude:.0f}"


def usd_formatter(value, _position=None) -> str:
    return usd(value)
