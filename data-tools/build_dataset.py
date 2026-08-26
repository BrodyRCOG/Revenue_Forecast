"""
CLI entry point for the offline data pipeline: generate -> validate -> write.

    python data-tools/build_dataset.py
    python data-tools/build_dataset.py --seed 7 --as-of 2026-01-15
    POC_SEED=99 python data-tools/build_dataset.py

Order of operations, and why it matters:

1. **Generate** the whole relational dataset in memory (deterministic in ``POC_SEED`` /
   ``POC_AS_OF_DATE``).
2. **Validate** it with the data-layer evals. CSVs and the eval report are written either way,
   so a failing run is still debuggable.
3. **Write ``data.sql``** -- but *only* if no error-severity eval failed. A broken generator run
   must never reach the Spring Boot app; a stale-but-valid ``data.sql`` is strictly better than a
   fresh nonsensical one. ``--force`` overrides this, deliberately awkwardly.
"""

from __future__ import annotations

import argparse
import os
import sys
from datetime import date
from pathlib import Path

import data_evals
from generate_synthetic_data import SyntheticDataGenerator, write_csvs, write_data_sql
import reference_data as ref

HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT_DIR = HERE / "output"
DEFAULT_SQL_PATH = HERE.parent / "backend" / "src" / "main" / "resources" / "db" / "generated" / "data.sql"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate, validate and emit the Revenue Intelligence POC dataset.")
    parser.add_argument("--seed", type=int, default=int(os.environ.get("POC_SEED", 42)),
                        help="RNG seed (env: POC_SEED, default 42).")
    parser.add_argument("--as-of", type=str, default=os.environ.get("POC_AS_OF_DATE"),
                        help="As-of date, YYYY-MM-DD (env: POC_AS_OF_DATE, default today).")
    parser.add_argument("--clients", type=int, default=ref.N_CLIENTS,
                        help=f"Number of clients (default {ref.N_CLIENTS}).")
    parser.add_argument("--installed-base", type=int, default=ref.N_INSTALLED_BASE,
                        help=f"Number of installed-base rows (default {ref.N_INSTALLED_BASE}).")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_DIR,
                        help="Directory for CSVs and the eval report.")
    parser.add_argument("--sql-out", type=Path, default=DEFAULT_SQL_PATH,
                        help="Path of the generated data.sql the backend loads.")
    parser.add_argument("--force", action="store_true",
                        help="Write data.sql even if error-severity evals fail. Don't.")
    parser.add_argument("--skip-sql", action="store_true",
                        help="Generate and validate only; leave data.sql untouched.")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    as_of = date.fromisoformat(args.as_of) if args.as_of else date.today()

    print("Revenue Intelligence Engine -- synthetic dataset build")
    print(f"  seed          : {args.seed}")
    print(f"  as-of         : {as_of.isoformat()}")
    print(f"  clients       : {args.clients}")
    print(f"  installed base: {args.installed_base}")

    # 1. Generate ---------------------------------------------------------------------
    generator = SyntheticDataGenerator(seed=args.seed, as_of=as_of,
                                       n_clients=args.clients,
                                       n_installed_base=args.installed_base)
    data = generator.generate()
    print(f"\n  historical quarters: {', '.join(data.historical_quarters)}")
    print(f"  forecast quarters  : {', '.join(data.forecast_quarters)}")

    # 2. Validate ---------------------------------------------------------------------
    report = data_evals.run_data_evals(data_evals.tables_from_dataset(data),
                                       seed=args.seed, as_of=as_of)

    csv_paths = write_csvs(data, args.output)
    print(f"\n  wrote {len(csv_paths)} CSV file(s) to {args.output}")
    report_path = data_evals.write_report(report, args.output / "data_eval_results.json")
    data_evals.print_report(report)
    print(f"Report written to {report_path}")

    # 3. Emit data.sql ----------------------------------------------------------------
    if args.skip_sql:
        print("\n--skip-sql set: data.sql left untouched.")
        return 0 if report["all_clear"] else 1

    if not report["all_clear"] and not args.force:
        print("\n" + "=" * 78)
        print("REFUSING to write data.sql: error-severity data evals failed.")
        print("The backend keeps whatever dataset it already had. Fix the generator, or")
        print("re-run with --force if you genuinely want a known-broken dataset loaded.")
        print("=" * 78)
        return 1

    if not report["all_clear"]:
        print("\n--force set: writing data.sql despite failing evals.")

    sql_path = write_data_sql(data, args.sql_out)
    total_rows = sum(report["row_counts"].values())
    print(f"\n  wrote {total_rows} rows across {len(report['row_counts'])} tables to")
    print(f"    {sql_path}")
    print("\nRestart the backend to pick up the new dataset.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
