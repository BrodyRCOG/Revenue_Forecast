"""
Synthetic dataset generator for the Revenue Intelligence Engine POC.

Offline only. This module never runs inside the Spring Boot application -- it produces CSVs and a
``data.sql`` that the backend loads at startup. See spec section 5.1.

Generation runs in strict dependency order::

    gen_clients -> gen_oem_models -> gen_installed_base -> gen_contracts
      -> gen_projects_and_billing -> gen_opportunities -> gen_workforce -> gen_targets

Two ordering choices matter:

* ``gen_projects_and_billing`` runs *before* ``gen_opportunities``. Delivered projects are the
  ground truth; won opportunities are the sales record of those same projects. Generating
  billing from opportunities instead would make the two reconcile only by luck.
* ``gen_workforce`` runs *after* billing exists, because headcount is sized off
  :func:`historical_quarterly_demand_hours` -- the same demand model the Java capacity service
  reads. Sizing workforce independently of revenue scale is what produced the
  "hire 5-6x your current bench" bug in the reference build.

Determinism: everything is driven by ``POC_SEED`` (default 42) and ``POC_AS_OF_DATE``
(default: today). Same inputs, byte-identical output.
"""

from __future__ import annotations

import csv
import math
import os
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path

import numpy as np
from faker import Faker

import reference_data as ref

# --------------------------------------------------------------------------------------
# Volume / shape knobs
# --------------------------------------------------------------------------------------

PROJECTS_PER_QUARTER = 12
PROJECTS_PER_QUARTER_JITTER = 3
# Fraction of a normal quarter's projects that have landed in the in-progress current quarter.
CURRENT_QUARTER_PROGRESS = 0.40

# Weighted open pipeline per forecast quarter, as a multiple of average historical won revenue.
#
# This is the growth assumption baked into the dataset, and it interacts with
# STAFFING_FACTOR_RANGE. A group whose staffing factor came out at the low end is already
# carrying 1/0.82 = 1.22x its sustainable load before any growth; multiply that by this figure
# and you have the largest hiring gap the generator can legitimately produce. Raising this much
# above 1.1 starts producing hiring recommendations that look like the bug the plausibility
# evals exist to catch.
OPEN_PIPELINE_COVERAGE = 1.10

BASE_DEAL_AMOUNT_USD: dict[str, float] = {
    "Infrastructure Refresh": 480_000,
    "Modernization": 620_000,
    "Managed Services": 300_000,
    "Security": 250_000,
    "Cloud Migration": 420_000,
}
TIER_DEAL_MULTIPLIER: dict[str, float] = {"Enterprise": 1.8, "Mid-Market": 1.0, "SMB": 0.55}
DEAL_AMOUNT_SIGMA = 0.45
MIN_DEAL_AMOUNT_USD = 45_000

OPPORTUNITY_TYPE_WEIGHTS = [0.30, 0.18, 0.24, 0.14, 0.14]  # aligned with ref.OPPORTUNITY_TYPES

# "True" win rates the closed history is generated to reproduce. ForecastingService has to
# rediscover these from the data via Bayesian smoothing -- it never sees this table.
WIN_RATE_BY_TYPE: dict[str, float] = {
    "Infrastructure Refresh": 0.46,
    "Modernization": 0.34,
    "Managed Services": 0.52,
    "Security": 0.38,
    "Cloud Migration": 0.40,
}
WIN_RATE_SEGMENT_DELTA: dict[str, float] = {
    "Community Bank": 0.02,
    "Credit Union": 0.04,
    "Regional Bank": -0.05,
    "De Novo/Digital Bank": -0.02,
    "Trust & Wealth Bank": 0.00,
}

INSTALLED_BASE_CATEGORY_WEIGHTS: dict[str, float] = {
    "Switching": 0.18,
    "Server": 0.16,
    "Server OS": 0.10,
    "Wireless": 0.10,
    "Firewall": 0.09,
    "Storage": 0.08,
    "Database": 0.07,
    "Hypervisor": 0.07,
    "Router": 0.06,
    "Voice & Collaboration": 0.05,
    "Hyperconverged": 0.02,
    "Load Balancer": 0.02,
}

# Typical unit counts per asset record, by category.
QUANTITY_RANGE: dict[str, tuple[int, int]] = {
    "Switching": (4, 34),
    "Router": (1, 8),
    "Wireless": (6, 60),
    "Voice & Collaboration": (2, 20),
    "Server": (1, 12),
    "Firewall": (1, 6),
    "Storage": (1, 4),
    "Server OS": (2, 40),
    "Database": (1, 10),
    "Hypervisor": (2, 24),
    "Hyperconverged": (1, 4),
    "Load Balancer": (1, 4),
}

SITE_KINDS = ["Main Branch", "Data Center", "Operations Center", "Disaster Recovery Site"]

ARR_PER_DOLLAR_OF_ASSETS = 0.00012
ARR_BOUNDS_USD = (60_000, 2_500_000)

RENEWAL_RISK_PROBABILITY: dict[str, float] = {
    "Low": 0.93, "Medium": 0.78, "High": 0.58, "Critical": 0.36,
}


# --------------------------------------------------------------------------------------
# Quarter helpers
# --------------------------------------------------------------------------------------

def quarter_label(day: date) -> str:
    """``2026-08-26`` -> ``2026-Q3``. Lexicographically sortable."""
    return f"{day.year}-Q{(day.month - 1) // 3 + 1}"


def quarter_index(label: str) -> int:
    """Absolute quarter number, for arithmetic on labels."""
    year, q = label.split("-Q")
    return int(year) * 4 + (int(q) - 1)


def label_from_index(index: int) -> str:
    return f"{index // 4}-Q{index % 4 + 1}"


def shift_quarter(label: str, delta: int) -> str:
    return label_from_index(quarter_index(label) + delta)


def quarter_bounds(label: str) -> tuple[date, date]:
    year, q = label.split("-Q")
    year, q = int(year), int(q)
    start = date(year, 3 * (q - 1) + 1, 1)
    end = date(year + (1 if q == 4 else 0), 1 if q == 4 else 3 * q + 1, 1) - timedelta(days=1)
    return start, end


def add_months(day: date, months: int) -> date:
    total = (day.year * 12 + day.month - 1) + months
    year, month = divmod(total, 12)
    month += 1
    last_day = [31, 29 if (year % 4 == 0 and (year % 100 != 0 or year % 400 == 0)) else 28,
                31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
    return date(year, month, min(day.day, last_day))


def months_between(start: date, end: date) -> float:
    return (end.year - start.year) * 12 + (end.month - start.month) + (end.day - start.day) / 30.44


# --------------------------------------------------------------------------------------
# Dataset container
# --------------------------------------------------------------------------------------

@dataclass
class Dataset:
    as_of: date
    seed: int
    historical_quarters: list[str] = field(default_factory=list)
    forecast_quarters: list[str] = field(default_factory=list)
    clients: list[dict] = field(default_factory=list)
    oem_models: list[dict] = field(default_factory=list)
    installed_base: list[dict] = field(default_factory=list)
    contracts: list[dict] = field(default_factory=list)
    projects: list[dict] = field(default_factory=list)
    project_billing: list[dict] = field(default_factory=list)
    project_utilization: list[dict] = field(default_factory=list)
    opportunities: list[dict] = field(default_factory=list)
    workforce: list[dict] = field(default_factory=list)
    targets: list[dict] = field(default_factory=list)

    def table(self, name: str) -> list[dict]:
        return getattr(self, name)


# Tables loaded into H2 (order matters -- parents before children).
SQL_TABLES: list[tuple[str, list[str]]] = [
    ("clients", ["id", "name", "segment", "region", "state", "revenue_tier", "asset_size_usd",
                 "branch_count", "core_banking_platform", "account_owner", "relationship_start"]),
    ("oem_models", ["id", "oem", "model_name", "category", "lifecycle_years", "support_tail_years",
                    "release_date", "end_of_sale", "end_of_support", "is_real_anchor", "source_url",
                    "source_confidence"]),
    ("installed_base", ["id", "client_id", "oem_model_id", "asset_tag", "site", "quantity",
                        "install_date", "criticality", "annual_support_cost_usd"]),
    ("contracts", ["id", "client_id", "service_type", "arr_usd", "start_date", "end_date",
                   "renewal_risk", "renewal_probability", "auto_renew", "nps_score"]),
    ("opportunities", ["id", "client_id", "name", "opportunity_type", "practice", "resource_group",
                       "amount_usd", "probability", "stage", "status", "lead_source",
                       "created_date", "close_date", "quarter"]),
    ("project_billing", ["id", "client_id", "project_name", "practice", "resource_group", "quarter",
                         "billed_hours", "billed_amount_usd", "delivery_utilization"]),
    ("workforce", ["id", "resource_group", "practice", "region", "headcount",
                   "contractor_headcount", "avg_bill_rate_usd", "target_utilization",
                   "current_utilization", "annual_capacity_hours_per_fte"]),
    ("targets", ["id", "practice", "quarter", "target_amount_usd"]),
]

# CSV-only tables (analysis inputs, never loaded into H2).
CSV_ONLY_TABLES: list[tuple[str, list[str]]] = [
    ("projects", ["id", "client_id", "name", "opportunity_type", "practice", "quarter",
                  "amount_usd", "total_hours"]),
    ("project_utilization", ["resource_group", "practice", "quarter", "demand_hours",
                             "capacity_hours", "utilization"]),
]


# --------------------------------------------------------------------------------------
# Generator
# --------------------------------------------------------------------------------------

class SyntheticDataGenerator:
    """Builds the whole relational dataset. One instance == one deterministic run."""

    def __init__(self, seed: int = 42, as_of: date | None = None,
                 n_clients: int = ref.N_CLIENTS, n_installed_base: int = ref.N_INSTALLED_BASE):
        self.seed = seed
        self.as_of = as_of or date.today()
        self.n_clients = n_clients
        self.n_installed_base = n_installed_base
        self.rng = np.random.default_rng(seed)
        self.faker = Faker("en_US")
        Faker.seed(seed)

        current = quarter_label(self.as_of)
        self.current_quarter = current
        self.data = Dataset(
            as_of=self.as_of,
            seed=seed,
            historical_quarters=[shift_quarter(current, -n) for n in range(ref.HISTORICAL_QUARTERS, 0, -1)],
            forecast_quarters=[shift_quarter(current, n) for n in range(ref.FORECAST_QUARTERS)],
        )

    # -- entry point ---------------------------------------------------------------

    def generate(self) -> Dataset:
        self.gen_clients()
        self.gen_oem_models()
        self.gen_installed_base()
        self.gen_contracts()
        self.gen_projects_and_billing()
        self.gen_opportunities()
        self.gen_workforce()
        self.gen_targets()
        self.derive_project_utilization()
        return self.data

    # -- helpers -------------------------------------------------------------------

    def _choice(self, items, weights=None):
        if weights is None:
            return items[int(self.rng.integers(0, len(items)))]
        p = np.asarray(weights, dtype=float)
        p = p / p.sum()
        return items[int(self.rng.choice(len(items), p=p))]

    def _date_between(self, start: date, end: date) -> date:
        span = (end - start).days
        if span <= 0:
            return start
        return start + timedelta(days=int(self.rng.integers(0, span + 1)))

    def _deal_amount(self, opportunity_type: str, revenue_tier: str) -> float:
        base = BASE_DEAL_AMOUNT_USD[opportunity_type] * TIER_DEAL_MULTIPLIER[revenue_tier]
        amount = base * float(self.rng.lognormal(0.0, DEAL_AMOUNT_SIGMA))
        amount = max(MIN_DEAL_AMOUNT_USD, round(amount / 5_000) * 5_000)
        return float(amount)

    def true_win_rate(self, segment: str, opportunity_type: str) -> float:
        rate = WIN_RATE_BY_TYPE[opportunity_type] + WIN_RATE_SEGMENT_DELTA[segment]
        return float(min(0.75, max(0.15, rate)))

    def _client_sampling_weights(self) -> np.ndarray:
        w = np.array([ref.REVENUE_TIER_WEIGHT[c["revenue_tier"]] for c in self.data.clients], dtype=float)
        return w / w.sum()

    # -- 1. clients ----------------------------------------------------------------

    def gen_clients(self) -> None:
        prefixes = list(ref.BANK_NAME_PREFIXES)
        self.rng.shuffle(prefixes)
        segment_names = [s["name"] for s in ref.SEGMENTS]
        segment_weights = [s["weight"] for s in ref.SEGMENTS]
        by_name = {s["name"]: s for s in ref.SEGMENTS}
        regions = list(ref.REGIONS_BY_STATE)

        for i in range(self.n_clients):
            segment = self._choice(segment_names, segment_weights)
            spec = by_name[segment]
            low, high = spec["asset_range"]
            # Log-uniform: bank asset sizes are heavily right-skewed.
            assets = float(math.exp(self.rng.uniform(math.log(low), math.log(high))))
            tier = ref.revenue_tier_for_assets(assets)
            region = self._choice(regions)
            b_low, b_high = spec["branch_range"]
            prefix = prefixes[i % len(prefixes)]
            suffix = self._choice(ref.BANK_NAME_SUFFIX_BY_SEGMENT[segment])

            self.data.clients.append({
                "id": f"CLI-{i + 1:04d}",
                "name": f"{prefix} {suffix}",
                "short_name": prefix,
                "segment": segment,
                "region": region,
                "state": self._choice(ref.REGIONS_BY_STATE[region]),
                "revenue_tier": tier,
                "asset_size_usd": round(assets, 2),
                "branch_count": int(self.rng.integers(b_low, b_high + 1)),
                "core_banking_platform": self._choice(ref.CORE_BANKING_PLATFORMS),
                "account_owner": self.faker.name(),
                "relationship_start": self._date_between(
                    add_months(self.as_of, -12 * 14), add_months(self.as_of, -9)),
                "home_town": self._choice(ref.BRANCH_TOWNS),
            })

    # -- 2. oem models -------------------------------------------------------------

    def gen_oem_models(self) -> None:
        """Seven real anchors, then three procedural variants per OEM_CATALOG pattern (45 rows)."""
        for i, anchor in enumerate(ref.REAL_ANCHORS):
            release = date.fromisoformat(anchor["release_date"])
            eos = date.fromisoformat(anchor["end_of_sale"])
            eosupport = date.fromisoformat(anchor["end_of_support"])
            self.data.oem_models.append({
                "id": f"OEM-{i + 1:04d}",
                "oem": anchor["oem"],
                "model_name": anchor["model_name"],
                "category": anchor["category"],
                "lifecycle_years": max(1, round(months_between(release, eos) / 12)),
                "support_tail_years": max(0, round(months_between(eos, eosupport) / 12)),
                "release_date": release,
                "end_of_sale": eos,
                "end_of_support": eosupport,
                "is_real_anchor": True,
                "source_url": anchor["source_url"],
                "source_confidence": anchor["source_confidence"],
                "derived_from": anchor["anchor_key"],
            })

        next_id = len(self.data.oem_models) + 1
        software_categories = {"Server OS", "Database", "Hypervisor"}

        for pattern in ref.OEM_CATALOG:
            span_months = 12 * (pattern["lifecycle_years"] + pattern["support_tail_years"])
            for v in range(ref.VARIANTS_PER_PATTERN):
                # Anchor the release date so end-of-support lands at as_of + offset, which
                # spreads variants deterministically across the severity bands.
                offset = ref.VARIANT_RELEASE_OFFSET_MONTHS[v] + int(self.rng.integers(-3, 4))
                release = add_months(self.as_of, -span_months + offset)
                eos = add_months(release, 12 * pattern["lifecycle_years"])
                eosupport = add_months(eos, 12 * pattern["support_tail_years"])
                suffix = (["R1", "R2", "R3"] if pattern["category"] in software_categories
                          else ref.VARIANT_SUFFIXES)[v]

                self.data.oem_models.append({
                    "id": f"OEM-{next_id:04d}",
                    "oem": pattern["oem"],
                    "model_name": f"{pattern['family']} {suffix}",
                    "category": pattern["category"],
                    "lifecycle_years": pattern["lifecycle_years"],
                    "support_tail_years": pattern["support_tail_years"],
                    "release_date": release,
                    "end_of_sale": eos,
                    "end_of_support": eosupport,
                    "is_real_anchor": False,
                    "source_url": None,
                    "source_confidence": "synthetic",
                    "derived_from": pattern["derived_from"],
                })
                next_id += 1

    # -- 3. installed base ---------------------------------------------------------

    def gen_installed_base(self) -> None:
        client_p = self._client_sampling_weights()
        models = self.data.oem_models
        model_weights = np.array(
            [INSTALLED_BASE_CATEGORY_WEIGHTS.get(m["category"], 0.01) for m in models], dtype=float)
        model_weights /= model_weights.sum()

        for i in range(self.n_installed_base):
            client = self.data.clients[int(self.rng.choice(len(self.data.clients), p=client_p))]
            model = models[int(self.rng.choice(len(models), p=model_weights))]
            q_low, q_high = QUANTITY_RANGE.get(model["category"], (1, 6))
            # Bigger banks deploy more units of the same thing.
            tier_scale = {"Enterprise": 1.6, "Mid-Market": 1.0, "SMB": 0.6}[client["revenue_tier"]]
            quantity = max(1, int(round(self.rng.integers(q_low, q_high + 1) * tier_scale)))

            install_start = model["release_date"]
            install_end = min(model["end_of_sale"], self.as_of)
            site_kind = self._choice(SITE_KINDS + [f"{self._choice(ref.BRANCH_TOWNS)} Branch"])
            unit_cost = ref.UNIT_REFRESH_COST_USD.get(model["category"], 3_000)

            self.data.installed_base.append({
                "id": f"IB-{i + 1:06d}",
                "client_id": client["id"],
                "oem_model_id": model["id"],
                "asset_tag": f"{client['id'][-4:]}-{model['category'][:3].upper()}-{i + 1:06d}",
                "site": f"{client['short_name']} - {site_kind}",
                "quantity": quantity,
                "install_date": self._date_between(install_start, max(install_start, install_end)),
                "criticality": self._choice(ref.CRITICALITY_LEVELS, ref.CRITICALITY_WEIGHTS),
                # Annual support runs ~18% of replacement value.
                "annual_support_cost_usd": round(quantity * unit_cost * 0.18, 2),
            })

    # -- 4. contracts --------------------------------------------------------------

    def gen_contracts(self) -> None:
        n = 0
        for client in self.data.clients:
            # ~70% of the base has managed services; larger banks may have two contracts.
            if self.rng.random() > 0.70:
                continue
            count = 2 if (client["revenue_tier"] == "Enterprise" and self.rng.random() < 0.5) else 1
            service_types = list(ref.CONTRACT_SERVICE_TYPES)
            self.rng.shuffle(service_types)

            for k in range(count):
                n += 1
                arr = client["asset_size_usd"] * ARR_PER_DOLLAR_OF_ASSETS * float(
                    self.rng.lognormal(0.0, 0.35))
                arr = float(min(ARR_BOUNDS_USD[1], max(ARR_BOUNDS_USD[0], round(arr / 1_000) * 1_000)))

                start = self._date_between(add_months(self.as_of, -48), add_months(self.as_of, -3))
                term_years = int(self.rng.choice([1, 2, 3, 3, 5]))
                end = add_months(start, 12 * term_years)
                nps = int(self.rng.integers(-20, 81))
                months_to_renewal = months_between(self.as_of, end)

                # Risk rises as renewal approaches and as satisfaction falls.
                score = 0.0
                if months_to_renewal <= 6:
                    score += 2
                elif months_to_renewal <= 12:
                    score += 1
                if nps < 10:
                    score += 2
                elif nps < 35:
                    score += 1
                score += float(self.rng.choice([0, 0, 1], p=[0.5, 0.3, 0.2]))
                risk = "Low" if score <= 1 else "Medium" if score <= 2 else "High" if score <= 3.5 else "Critical"

                self.data.contracts.append({
                    "id": f"CTR-{n:04d}",
                    "client_id": client["id"],
                    "service_type": service_types[k % len(service_types)],
                    "arr_usd": arr,
                    "start_date": start,
                    "end_date": end,
                    "renewal_risk": risk,
                    "renewal_probability": round(
                        RENEWAL_RISK_PROBABILITY[risk] * float(self.rng.uniform(0.94, 1.06)), 4),
                    "auto_renew": bool(risk in ("Low", "Medium") and self.rng.random() < 0.6),
                    "nps_score": nps,
                })

    # -- 5. projects and billing ---------------------------------------------------

    def gen_projects_and_billing(self) -> None:
        """
        Delivered work, quarter by quarter. Each project's hours are split across resource groups
        with :data:`reference_data.RESOURCE_MIX` and billed at that opportunity type's delivery
        rate, so ``sum(billed_amount) == amount * labor_content`` exactly. Those hours are the
        historical demand the workforce is later sized against.
        """
        client_p = self._client_sampling_weights()
        project_no = 0
        billing_no = 0

        # Full historical quarters, plus a partial in-progress current quarter.
        plan = [(q, 1.0) for q in self.data.historical_quarters]
        plan.append((self.current_quarter, CURRENT_QUARTER_PROGRESS))

        for quarter, progress in plan:
            base = PROJECTS_PER_QUARTER + int(
                self.rng.integers(-PROJECTS_PER_QUARTER_JITTER, PROJECTS_PER_QUARTER_JITTER + 1))
            count = max(1, int(round(base * progress)))

            for _ in range(count):
                project_no += 1
                client = self.data.clients[int(self.rng.choice(len(self.data.clients), p=client_p))]
                opp_type = self._choice(ref.OPPORTUNITY_TYPES, OPPORTUNITY_TYPE_WEIGHTS)
                amount = self._deal_amount(opp_type, client["revenue_tier"])
                practice = ref.PRACTICE_BY_OPPORTUNITY_TYPE[opp_type]
                name = self._choice(ref.ENGAGEMENT_TEMPLATES_BY_TYPE[opp_type]).format(
                    town=self._choice(ref.BRANCH_TOWNS), client=client["short_name"])
                by_group = ref.demand_hours_by_group(opp_type, amount)

                self.data.projects.append({
                    "id": f"PRJ-{project_no:05d}",
                    "client_id": client["id"],
                    "name": name,
                    "opportunity_type": opp_type,
                    "practice": practice,
                    "quarter": quarter,
                    "amount_usd": amount,
                    "total_hours": round(sum(by_group.values()), 2),
                })

                for group, hours in by_group.items():
                    billing_no += 1
                    self.data.project_billing.append({
                        "id": f"PB-{billing_no:06d}",
                        "client_id": client["id"],
                        "project_name": name,
                        "practice": practice,
                        "resource_group": group,
                        "quarter": quarter,
                        "billed_hours": round(hours, 2),
                        "billed_amount_usd": round(
                            hours * ref.DELIVERY_RATE_USD_PER_HOUR[opp_type], 2),
                        "delivery_utilization": round(float(self.rng.uniform(0.72, 0.98)), 4),
                    })

    # -- 6. opportunities ----------------------------------------------------------

    def gen_opportunities(self) -> None:
        """
        Won rows mirror delivered projects one-for-one. Lost rows are then added per
        (segment, opportunity type) so the realised win rate reproduces
        :func:`true_win_rate` -- that's the signal ForecastingService has to recover.
        Finally an open pipeline is generated for the forecast quarters.
        """
        opp_no = 0
        clients_by_id = {c["id"]: c for c in self.data.clients}

        def new_id() -> str:
            nonlocal opp_no
            opp_no += 1
            return f"OPP-{opp_no:05d}"

        def append(client, opp_type, amount, quarter, status, name=None):
            q_start, q_end = quarter_bounds(quarter)
            if status == "Open":
                # Open deals close in the future, but stay inside their own quarter.
                close = self._date_between(max(q_start, self.as_of + timedelta(days=1)), q_end)
            else:
                # Closed deals cannot close after the as-of date.
                close = self._date_between(q_start, min(q_end, self.as_of))
            created = add_months(close, -int(self.rng.integers(3, 11)))
            if name is None:
                name = self._choice(ref.ENGAGEMENT_TEMPLATES_BY_TYPE[opp_type]).format(
                    town=self._choice(ref.BRANCH_TOWNS), client=client["short_name"])
            stage = ("Closed Won" if status == "Won"
                     else "Closed Lost" if status == "Lost"
                     else self._choice(ref.SALES_STAGES_OPEN))
            probability = (1.0 if status == "Won" else 0.0 if status == "Lost"
                           else round(float(np.clip(
                               self.true_win_rate(client["segment"], opp_type)
                               + self.rng.normal(0, 0.10), 0.05, 0.95)), 4))

            self.data.opportunities.append({
                "id": new_id(),
                "client_id": client["id"],
                "name": name,
                "opportunity_type": opp_type,
                "practice": ref.PRACTICE_BY_OPPORTUNITY_TYPE[opp_type],
                "resource_group": ref.primary_resource_group(opp_type),
                "amount_usd": amount,
                "probability": probability,
                "stage": stage,
                "status": status,
                "lead_source": self._choice(ref.LEAD_SOURCES),
                "created_date": created,
                "close_date": close,
                "quarter": quarter,
            })

        # 6a. Won -- one per delivered project.
        won_counts: dict[tuple[str, str], int] = {}
        for project in self.data.projects:
            client = clients_by_id[project["client_id"]]
            append(client, project["opportunity_type"], project["amount_usd"],
                   project["quarter"], "Won", name=project["name"])
            key = (client["segment"], project["opportunity_type"])
            won_counts[key] = won_counts.get(key, 0) + 1

        # 6b. Lost -- enough of them to reproduce the intended win rate per group.
        closed_quarters = self.data.historical_quarters + [self.current_quarter]
        clients_by_segment: dict[str, list[dict]] = {}
        for c in self.data.clients:
            clients_by_segment.setdefault(c["segment"], []).append(c)

        for (segment, opp_type), n_won in sorted(won_counts.items()):
            rate = self.true_win_rate(segment, opp_type)
            n_lost = int(round(n_won * (1.0 - rate) / rate))
            pool = clients_by_segment[segment]
            for _ in range(n_lost):
                client = pool[int(self.rng.integers(0, len(pool)))]
                append(client, opp_type, self._deal_amount(opp_type, client["revenue_tier"]),
                       self._choice(closed_quarters), "Lost")

        # 6c. Open pipeline -- sized so weighted pipeline per forecast quarter is
        #     OPEN_PIPELINE_COVERAGE x average historical won revenue per quarter.
        won_by_quarter: dict[str, float] = {}
        for opp in self.data.opportunities:
            if opp["status"] == "Won":
                won_by_quarter[opp["quarter"]] = won_by_quarter.get(opp["quarter"], 0.0) + opp["amount_usd"]
        full_quarters = [q for q in self.data.historical_quarters if q in won_by_quarter]
        avg_won_per_quarter = (sum(won_by_quarter[q] for q in full_quarters) / len(full_quarters)
                              if full_quarters else 4_000_000.0)
        weighted_goal = avg_won_per_quarter * OPEN_PIPELINE_COVERAGE

        client_p = self._client_sampling_weights()
        for quarter in self.data.forecast_quarters:
            weighted = 0.0
            for _ in range(500):
                if weighted >= weighted_goal:
                    break
                client = self.data.clients[int(self.rng.choice(len(self.data.clients), p=client_p))]
                opp_type = self._choice(ref.OPPORTUNITY_TYPES, OPPORTUNITY_TYPE_WEIGHTS)
                amount = self._deal_amount(opp_type, client["revenue_tier"])
                weight = amount * self.true_win_rate(client["segment"], opp_type)

                # Nearest fit rather than fill-until-exceeded. Stopping short of the goal by less
                # than this deal would overshoot it keeps each quarter's pipeline on target instead
                # of drifting a few percent high, quarter after quarter.
                overshoot = weighted + weight - weighted_goal
                if overshoot > 0 and overshoot > (weighted_goal - weighted):
                    break

                append(client, opp_type, amount, quarter, "Open")
                weighted += weight

    # -- 7. workforce --------------------------------------------------------------

    def historical_quarterly_demand_hours(self) -> dict[str, float]:
        """
        Average delivery hours per quarter, per resource group, over the completed historical
        quarters. This is *the* demand model: the workforce is sized against it here, and the
        Java capacity service applies the same dollars-to-hours transform to forecast rows.
        Keep the two consistent or hiring recommendations go silly.
        """
        totals: dict[str, float] = {g: 0.0 for g in ref.RESOURCE_GROUPS}
        complete = set(self.data.historical_quarters)
        for row in self.data.project_billing:
            if row["quarter"] in complete:
                totals[row["resource_group"]] += row["billed_hours"]
        n = max(1, len(complete))
        return {group: hours / n for group, hours in totals.items()}

    def gen_workforce(self) -> None:
        demand = self.historical_quarterly_demand_hours()
        low, high = ref.STAFFING_FACTOR_RANGE

        for i, group in enumerate(ref.RESOURCE_GROUPS):
            hours = demand[group]
            q_capacity = ref.quarterly_capacity_hours(group)
            required = hours / (q_capacity * ref.TARGET_UTILIZATION) if hours > 0 else 0.0
            staffing_factor = float(self.rng.uniform(low, high))
            headcount = max(4, int(round(required * staffing_factor)))
            utilization = hours / (headcount * q_capacity) if headcount else 0.0

            self.data.workforce.append({
                "id": f"WF-{i + 1:04d}",
                "resource_group": group,
                "practice": ref.PRACTICE_BY_RESOURCE_GROUP[group],
                "region": "National",
                "headcount": headcount,
                "contractor_headcount": int(round(headcount * float(self.rng.uniform(0.08, 0.28)))),
                "avg_bill_rate_usd": ref.AVG_BILL_RATE_USD[group],
                "target_utilization": ref.TARGET_UTILIZATION,
                "current_utilization": round(float(np.clip(utilization, 0.45, 1.05)), 4),
                "annual_capacity_hours_per_fte": ref.ANNUAL_CAPACITY_HOURS[group],
                # Diagnostics: CSV-only, dropped before data.sql.
                "_demand_hours": round(hours, 2),
                "_staffing_factor": round(staffing_factor, 4),
            })

    # -- 8. targets ----------------------------------------------------------------

    def gen_targets(self) -> None:
        """
        Historical targets sit near what was actually won, so most historical quarters come out
        already met -- that is deliberate, it exercises the ``targetAlreadyMet`` path instead of
        letting the coverage ratio divide by a near-zero remaining target.

        Forecast targets are set slightly *below* weighted pipeline, putting coverage in a
        believable 1.05-1.35 band.
        """
        won: dict[tuple[str, str], float] = {}
        weighted_open: dict[tuple[str, str], float] = {}
        clients_by_id = {c["id"]: c for c in self.data.clients}

        for opp in self.data.opportunities:
            key = (opp["practice"], opp["quarter"])
            if opp["status"] == "Won":
                won[key] = won.get(key, 0.0) + opp["amount_usd"]
            elif opp["status"] == "Open":
                rate = self.true_win_rate(clients_by_id[opp["client_id"]]["segment"],
                                          opp["opportunity_type"])
                weighted_open[key] = weighted_open.get(key, 0.0) + opp["amount_usd"] * rate

        practice_avg_won: dict[str, float] = {}
        for practice in ref.PRACTICES:
            values = [won.get((practice, q), 0.0) for q in self.data.historical_quarters]
            practice_avg_won[practice] = sum(values) / max(1, len(values))

        n = 0
        quarters = self.data.historical_quarters + self.data.forecast_quarters
        for practice in ref.PRACTICES:
            for quarter in quarters:
                key = (practice, quarter)
                if quarter in self.data.forecast_quarters:
                    pipeline = weighted_open.get(key, 0.0) + won.get(key, 0.0)
                    basis = pipeline if pipeline > 0 else practice_avg_won[practice]
                    amount = basis / float(self.rng.uniform(1.05, 1.35))
                else:
                    basis = won.get(key, 0.0) or practice_avg_won[practice]
                    amount = basis * float(self.rng.uniform(0.85, 1.15))
                n += 1
                self.data.targets.append({
                    "id": f"TGT-{n:04d}",
                    "practice": practice,
                    "quarter": quarter,
                    "target_amount_usd": round(max(50_000.0, amount), 2),
                })

    # -- derived, CSV-only ---------------------------------------------------------

    def derive_project_utilization(self) -> None:
        """Per resource group and quarter: demand hours vs capacity. Analysis input only."""
        headcount = {w["resource_group"]: w["headcount"] for w in self.data.workforce}
        agg: dict[tuple[str, str], float] = {}
        for row in self.data.project_billing:
            key = (row["resource_group"], row["quarter"])
            agg[key] = agg.get(key, 0.0) + row["billed_hours"]

        for (group, quarter), hours in sorted(agg.items()):
            capacity = headcount.get(group, 0) * ref.quarterly_capacity_hours(group)
            self.data.project_utilization.append({
                "resource_group": group,
                "practice": ref.PRACTICE_BY_RESOURCE_GROUP[group],
                "quarter": quarter,
                "demand_hours": round(hours, 2),
                "capacity_hours": round(capacity, 2),
                "utilization": round(hours / capacity, 4) if capacity else None,
            })


# --------------------------------------------------------------------------------------
# Output writers
# --------------------------------------------------------------------------------------

def write_csvs(data: Dataset, output_dir: Path) -> list[Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for table, columns in SQL_TABLES + CSV_ONLY_TABLES:
        rows = data.table(table)
        path = output_dir / f"{table}.csv"
        # CSVs keep the diagnostic/underscore columns; data.sql does not.
        extra = [k for k in (rows[0].keys() if rows else []) if k not in columns]
        header = columns + extra
        with path.open("w", newline="", encoding="utf-8") as fh:
            writer = csv.DictWriter(fh, fieldnames=header, extrasaction="ignore")
            writer.writeheader()
            for row in rows:
                writer.writerow({k: row.get(k) for k in header})
        written.append(path)
    return written


def sql_literal(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, date):
        return f"DATE '{value.isoformat()}'"
    if isinstance(value, (int, np.integer)):
        return str(int(value))
    if isinstance(value, (float, np.floating)):
        if math.isnan(value) or math.isinf(value):
            return "NULL"
        return f"{float(value):.4f}".rstrip("0").rstrip(".") or "0"
    text = str(value).replace("'", "''")
    return f"'{text}'"


def write_data_sql(data: Dataset, path: Path, batch_size: int = 200) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "-- GENERATED FILE -- do not edit by hand.",
        f"-- data-tools/build_dataset.py  seed={data.seed}  as_of={data.as_of.isoformat()}",
        "-- Regenerate with: python data-tools/build_dataset.py",
        "",
    ]

    for table, columns in SQL_TABLES:
        rows = data.table(table)
        lines.append(f"-- {table}: {len(rows)} rows")
        if not rows:
            lines.append("")
            continue
        column_list = ", ".join(columns)
        for start in range(0, len(rows), batch_size):
            chunk = rows[start:start + batch_size]
            values = ",\n  ".join(
                "(" + ", ".join(sql_literal(row.get(c)) for c in columns) + ")" for row in chunk)
            lines.append(f"INSERT INTO {table} ({column_list}) VALUES\n  {values};")
        lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")
    return path
