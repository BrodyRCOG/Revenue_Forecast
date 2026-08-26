"""
Reference data and shared economic constants for the Revenue Intelligence Engine POC.

Two kinds of thing live here:

1. **Real, sourced OEM lifecycle anchors** (``REAL_ANCHORS``) -- seven actual products with
   real end-of-sale / end-of-support dates and a ``source_url``. Every other OEM row in the
   dataset is a procedural variant derived from these anchors' lifecycle *spans*, and is
   labelled ``source_confidence="synthetic"`` so downstream code can always tell the two
   apart. ``source_confidence`` on the anchors themselves is an honest statement of how
   certain the date is: ``vendor_official`` where the vendor publishes it directly,
   ``aggregator`` where the published date varies by SKU/region and the value here is a
   representative one.

   These dates were correct as published at authoring time. They are POC anchors, not a
   lifecycle feed -- see spec section 8, "no production EOL/EOS integration".

2. **Shared economic constants** (``LABOR_CONTENT``, ``DELIVERY_RATE_USD_PER_HOUR``,
   ``RESOURCE_MIX``, ``ANNUAL_CAPACITY_HOURS``, ``TARGET_UTILIZATION``).

   *** THESE MUST STAY IN SYNC WITH THE JAVA SIDE. ***
   Mirror:  backend/src/main/java/com/cognizant/revintel/service/DeliveryEconomics.java

   The generator sizes workforce headcount off the demand these constants imply, and the Java
   CapacityService converts forecast dollars to demand hours with the same constants. If the
   two drift apart, hiring recommendations go implausible (the "hire 5x your bench" bug) --
   ``data_evals.capacity_vs_historical_demand_plausibility`` is the regression guard, and
   ``DeliveryEconomicsParityTest`` on the Java side asserts the numbers still match.
"""

from __future__ import annotations

# --------------------------------------------------------------------------------------
# 1. Real, sourced OEM lifecycle anchors
# --------------------------------------------------------------------------------------

# (anchor_key, oem, model_name, category, release, end_of_sale, end_of_support,
#  source_confidence, source_url)
REAL_ANCHORS: list[dict] = [
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
    {
        "anchor_key": "hpe-dl380-gen9",
        "oem": "HPE",
        "model_name": "ProLiant DL380 Gen9",
        "category": "Server",
        "release_date": "2014-09-01",
        "end_of_sale": "2019-07-31",
        "end_of_support": "2026-12-31",
        # HPE publishes support end per-SKU and per-region; this is a representative date.
        "source_confidence": "aggregator",
        "source_url": "https://support.hpe.com/connect/s/product?kmpmoid=7271241",
    },
    {
        "anchor_key": "fortinet-fg60e",
        "oem": "Fortinet",
        "model_name": "FortiGate-60E",
        "category": "Firewall",
        "release_date": "2016-06-01",
        "end_of_sale": "2023-06-30",
        "end_of_support": "2028-06-30",
        "source_confidence": "vendor_official",
        "source_url": "https://docs.fortinet.com/document/fortigate/7.4.0/fortigate-product-life-cycle",
    },
    {
        "anchor_key": "netapp-fas2650",
        "oem": "NetApp",
        "model_name": "FAS2650",
        "category": "Storage",
        "release_date": "2016-03-01",
        "end_of_sale": "2020-05-31",
        "end_of_support": "2025-11-30",
        # NetApp end-of-support runs off the individual serial's contract; representative date.
        "source_confidence": "aggregator",
        "source_url": "https://mysupport.netapp.com/site/info/version-support",
    },
    {
        "anchor_key": "microsoft-ws2016",
        "oem": "Microsoft",
        "model_name": "Windows Server 2016",
        "category": "Server OS",
        "release_date": "2016-10-15",
        # Mainstream support end / extended support end.
        "end_of_sale": "2022-01-11",
        "end_of_support": "2027-01-12",
        "source_confidence": "vendor_official",
        "source_url": "https://learn.microsoft.com/en-us/lifecycle/products/windows-server-2016",
    },
    {
        "anchor_key": "microsoft-sql2016",
        "oem": "Microsoft",
        "model_name": "SQL Server 2016",
        "category": "Database",
        "release_date": "2016-06-01",
        "end_of_sale": "2021-07-13",
        "end_of_support": "2026-07-14",
        "source_confidence": "vendor_official",
        "source_url": "https://learn.microsoft.com/en-us/lifecycle/products/microsoft-sql-server-2016",
    },
    {
        "anchor_key": "vmware-esxi67",
        "oem": "VMware",
        "model_name": "vSphere ESXi 6.7",
        "category": "Hypervisor",
        # General support end / technical guidance end.
        "release_date": "2018-04-17",
        "end_of_sale": "2022-10-15",
        "end_of_support": "2023-11-15",
        "source_confidence": "vendor_official",
        "source_url": "https://lifecycle.vmware.com/",
    },
]

VALID_SOURCE_CONFIDENCE = {"vendor_official", "aggregator", "uncertain", "synthetic"}

# --------------------------------------------------------------------------------------
# OEM_CATALOG -- 15 (oem, category, lifecycle_years, support_tail_years) patterns whose
# spans are *derived from* the real anchors above. `derived_from` records which anchor's
# lifecycle shape each pattern borrows, so provenance is auditable.
# --------------------------------------------------------------------------------------

OEM_CATALOG: list[dict] = [
    {"oem": "Cisco", "family": "Catalyst", "category": "Switching",
     "lifecycle_years": 9, "support_tail_years": 5, "derived_from": "cisco-c2960x"},
    {"oem": "Cisco", "family": "ISR", "category": "Router",
     "lifecycle_years": 8, "support_tail_years": 5, "derived_from": "cisco-c2960x"},
    {"oem": "Cisco", "family": "Aironet", "category": "Wireless",
     "lifecycle_years": 7, "support_tail_years": 5, "derived_from": "cisco-c2960x"},
    {"oem": "Cisco", "family": "Unified CM", "category": "Voice & Collaboration",
     "lifecycle_years": 8, "support_tail_years": 5, "derived_from": "cisco-c2960x"},
    {"oem": "HPE", "family": "ProLiant DL", "category": "Server",
     "lifecycle_years": 5, "support_tail_years": 7, "derived_from": "hpe-dl380-gen9"},
    {"oem": "Dell EMC", "family": "PowerEdge R", "category": "Server",
     "lifecycle_years": 5, "support_tail_years": 7, "derived_from": "hpe-dl380-gen9"},
    {"oem": "Fortinet", "family": "FortiGate", "category": "Firewall",
     "lifecycle_years": 7, "support_tail_years": 5, "derived_from": "fortinet-fg60e"},
    {"oem": "Palo Alto Networks", "family": "PA-Series", "category": "Firewall",
     "lifecycle_years": 7, "support_tail_years": 5, "derived_from": "fortinet-fg60e"},
    {"oem": "NetApp", "family": "FAS", "category": "Storage",
     "lifecycle_years": 4, "support_tail_years": 5, "derived_from": "netapp-fas2650"},
    {"oem": "Dell EMC", "family": "Unity XT", "category": "Storage",
     "lifecycle_years": 4, "support_tail_years": 5, "derived_from": "netapp-fas2650"},
    {"oem": "Microsoft", "family": "Windows Server", "category": "Server OS",
     "lifecycle_years": 5, "support_tail_years": 5, "derived_from": "microsoft-ws2016"},
    {"oem": "Microsoft", "family": "SQL Server", "category": "Database",
     "lifecycle_years": 5, "support_tail_years": 5, "derived_from": "microsoft-sql2016"},
    {"oem": "VMware", "family": "vSphere ESXi", "category": "Hypervisor",
     "lifecycle_years": 4, "support_tail_years": 1, "derived_from": "vmware-esxi67"},
    {"oem": "Nutanix", "family": "NX-Series", "category": "Hyperconverged",
     "lifecycle_years": 5, "support_tail_years": 3, "derived_from": "vmware-esxi67"},
    {"oem": "F5", "family": "BIG-IP i-Series", "category": "Load Balancer",
     "lifecycle_years": 6, "support_tail_years": 4, "derived_from": "cisco-c2960x"},
]

# Three procedural variants per pattern -> 45 synthetic rows.
VARIANTS_PER_PATTERN = 3

# Variant naming suffixes, and the release-date offset (in months, relative to the date that
# would put end-of-support exactly at as-of) used to spread severities deterministically:
# one variant already past support, one inside the 12-24 month window, one comfortably clear.
VARIANT_SUFFIXES = ["Gen1", "Gen2", "Gen3"]
VARIANT_RELEASE_OFFSET_MONTHS = [-30, 18, 54]

# Replacement cost per unit when an asset in this category is refreshed (hardware + licence,
# before services). Used to size the whitespace opportunity a lifecycle signal implies.
UNIT_REFRESH_COST_USD: dict[str, float] = {
    "Switching": 2_600,
    "Router": 7_500,
    "Wireless": 1_100,
    "Voice & Collaboration": 2_200,
    "Server": 9_500,
    "Firewall": 4_200,
    "Storage": 18_000,
    "Server OS": 1_200,
    "Database": 6_500,
    "Hypervisor": 1_800,
    "Hyperconverged": 22_000,
    "Load Balancer": 14_000,
}

# --------------------------------------------------------------------------------------
# 2. Client base -- bank / credit-union flavour
# --------------------------------------------------------------------------------------

SEGMENTS: list[dict] = [
    {"name": "Community Bank", "weight": 0.32, "asset_range": (2.5e8, 3.0e9), "branch_range": (4, 28)},
    {"name": "Credit Union", "weight": 0.26, "asset_range": (1.2e8, 2.2e9), "branch_range": (3, 22)},
    {"name": "Regional Bank", "weight": 0.20, "asset_range": (3.0e9, 4.5e10), "branch_range": (25, 180)},
    {"name": "De Novo/Digital Bank", "weight": 0.12, "asset_range": (4.0e7, 8.0e8), "branch_range": (0, 3)},
    {"name": "Trust & Wealth Bank", "weight": 0.10, "asset_range": (6.0e8, 9.0e9), "branch_range": (2, 18)},
]

# Revenue tier drives installed-base sampling weight (spec 5.1).
REVENUE_TIER_WEIGHT: dict[str, float] = {"Enterprise": 3.0, "Mid-Market": 1.5, "SMB": 0.6}

# Asset-size thresholds (USD) that put a client in a revenue tier.
TIER_ASSET_THRESHOLDS: list[tuple[float, str]] = [
    (5.0e9, "Enterprise"),
    (8.0e8, "Mid-Market"),
    (0.0, "SMB"),
]

BANK_NAME_PREFIXES = [
    "Cedar Valley", "Harbor Point", "Ironwood", "Lakeshore", "Prairie State", "Summit Ridge",
    "Blue Heron", "Riverbend", "Copper Creek", "Fairmount", "Granite Hills", "Willow Park",
    "Northgate", "Sandhill", "Stonebridge", "Auburn Falls", "Clearwater", "Elmwood",
    "Foxglove", "Great Basin", "Hollow Brook", "Juniper Flats", "Kingsford", "Larkspur",
    "Maple Grove", "Nine Mile", "Osprey Bay", "Pine Bluff", "Quarry Hill", "Red Cedar",
    "Silver Lake", "Tamarack", "Union Springs", "Vantage", "Westfield", "Yellow Creek",
    "Abbott Mills", "Bridgewater", "Chestnut Hill", "Dunmore", "Eastport", "Fenwick",
    "Glenmore", "Hartwell", "Inglewood", "Jessup", "Kenmare", "Loganville", "Merrick",
    "Norwood",
]

BANK_NAME_SUFFIX_BY_SEGMENT = {
    "Community Bank": ["Community Bank", "Bank & Trust", "State Bank", "Savings Bank"],
    "Credit Union": ["Credit Union", "Federal Credit Union", "Community Credit Union"],
    "Regional Bank": ["Regional Bank", "National Bank", "Bancorp", "Financial Group"],
    "De Novo/Digital Bank": ["Digital Bank", "Bank (N.A.)", "Neobank", "Financial"],
    "Trust & Wealth Bank": ["Trust Company", "Private Bank", "Wealth & Trust"],
}

BRANCH_TOWNS = [
    "Ashland", "Bellview", "Carrolton", "Delmar", "Eaton Rapids", "Franklin Park",
    "Georgetown", "Hastings", "Ionia", "Jamesport", "Kirkwood", "Lyndon", "Marshall",
    "Newberry", "Oakdale", "Pittsford", "Quincy", "Ridgeway", "Sheldon", "Talbot",
    "Underhill", "Vernon", "Waterloo", "Yorkville", "Zeeland",
]

CORE_BANKING_PLATFORMS = [
    "Fiserv Premier", "Fiserv DNA", "FIS Horizon", "FIS IBS", "Jack Henry SilverLake",
    "Jack Henry CIF 20/20", "Symitar Episys", "Corelation KeyStone", "Finastra Phoenix",
    "Temenos Transact",
]

REGIONS_BY_STATE = {
    "Northeast": ["MA", "CT", "NY", "NJ", "PA", "ME", "NH", "VT", "RI"],
    "Southeast": ["FL", "GA", "NC", "SC", "TN", "VA", "AL", "KY"],
    "Midwest": ["IL", "IN", "IA", "MI", "MN", "MO", "OH", "WI", "KS", "NE"],
    "Southwest": ["TX", "OK", "NM", "AZ"],
    "West": ["CA", "OR", "WA", "CO", "UT", "NV", "ID", "MT"],
}

# Engagement / project name templates. `{town}` is filled from BRANCH_TOWNS, `{client}` from
# the client's short name.
ENGAGEMENT_TEMPLATES_BY_TYPE = {
    "Infrastructure Refresh": [
        "POP - New Branch - {town}",
        "Branch Switching Refresh - {client}",
        "Data Center Compute Refresh - {client}",
        "Wireless Modernization - {town}",
        "Core Network Refresh - {client}",
    ],
    "Modernization": [
        "Core Platform Modernization - {client}",
        "Digital Banking Channel Uplift - {client}",
        "Loan Origination Re-platform - {client}",
        "Teller Application Modernization - {town}",
    ],
    "Managed Services": [
        "Managed Network Expansion - {client}",
        "Managed Endpoint Rollout - {client}",
        "24x7 NOC Onboarding - {client}",
        "Managed Backup & Recovery - {client}",
    ],
    "Security": [
        "Endpoint Security Uplift - {client}",
        "Zero Trust Segmentation - {client}",
        "FFIEC Controls Remediation - {client}",
        "SOC Onboarding - {town}",
    ],
    "Cloud Migration": [
        "Data Center Exit - {client}",
        "Disaster Recovery to Cloud - {client}",
        "Branch Workload Migration - {town}",
        "Cloud Landing Zone Build - {client}",
    ],
}

SALES_STAGES_OPEN = ["Qualification", "Discovery", "Proposal", "Negotiation"]
LEAD_SOURCES = ["CRM", "Referral", "Partner", "Renewal"]

CONTRACT_SERVICE_TYPES = [
    "Managed Network", "Managed Security", "Managed Cloud", "Managed Endpoint", "Hosting",
]

CRITICALITY_LEVELS = ["Mission Critical", "Business Critical", "Standard"]
CRITICALITY_WEIGHTS = [0.22, 0.38, 0.40]

# --------------------------------------------------------------------------------------
# 3. Shared economic constants -- MIRROR OF DeliveryEconomics.java
# --------------------------------------------------------------------------------------

PRACTICES = ["Infrastructure", "Applications", "Managed Services", "Security", "Cloud"]

OPPORTUNITY_TYPES = [
    "Infrastructure Refresh", "Modernization", "Managed Services", "Security", "Cloud Migration",
]

PRACTICE_BY_OPPORTUNITY_TYPE = {
    "Infrastructure Refresh": "Infrastructure",
    "Modernization": "Applications",
    "Managed Services": "Managed Services",
    "Security": "Security",
    "Cloud Migration": "Cloud",
}

RESOURCE_GROUPS = [
    "Network Engineering",
    "Data Center & Compute",
    "Cloud & Platform Engineering",
    "Application Modernization",
    "Security Engineering",
    "Service Desk & Operations",
    "Project & Program Management",
]

PRACTICE_BY_RESOURCE_GROUP = {
    "Network Engineering": "Infrastructure",
    "Data Center & Compute": "Infrastructure",
    "Cloud & Platform Engineering": "Cloud",
    "Application Modernization": "Applications",
    "Security Engineering": "Security",
    "Service Desk & Operations": "Managed Services",
    "Project & Program Management": "Managed Services",
}

# Fraction of deal value that is delivery labour. The remainder is hardware / licence
# pass-through, which consumes no delivery hours -- this is why an Infrastructure Refresh
# deal generates far fewer hours per dollar than a Modernization deal.
LABOR_CONTENT: dict[str, float] = {
    "Infrastructure Refresh": 0.35,
    "Modernization": 0.80,
    "Managed Services": 0.60,
    "Security": 0.55,
    "Cloud Migration": 0.70,
}

# Realised revenue per delivery hour, by opportunity type.
DELIVERY_RATE_USD_PER_HOUR: dict[str, float] = {
    "Infrastructure Refresh": 175.0,
    "Modernization": 195.0,
    "Managed Services": 135.0,
    "Security": 205.0,
    "Cloud Migration": 200.0,
}

# How each opportunity type's delivery hours split across resource groups. Rows sum to 1.0.
RESOURCE_MIX: dict[str, dict[str, float]] = {
    "Infrastructure Refresh": {
        "Network Engineering": 0.45,
        "Data Center & Compute": 0.35,
        "Project & Program Management": 0.12,
        "Service Desk & Operations": 0.08,
    },
    "Modernization": {
        "Application Modernization": 0.55,
        "Cloud & Platform Engineering": 0.25,
        "Project & Program Management": 0.12,
        "Data Center & Compute": 0.08,
    },
    "Managed Services": {
        "Service Desk & Operations": 0.55,
        "Network Engineering": 0.15,
        "Cloud & Platform Engineering": 0.15,
        "Security Engineering": 0.08,
        "Project & Program Management": 0.07,
    },
    "Security": {
        "Security Engineering": 0.65,
        "Network Engineering": 0.15,
        "Cloud & Platform Engineering": 0.10,
        "Project & Program Management": 0.10,
    },
    "Cloud Migration": {
        "Cloud & Platform Engineering": 0.55,
        "Application Modernization": 0.20,
        "Data Center & Compute": 0.13,
        "Project & Program Management": 0.12,
    },
}

# Billable-capable hours per FTE per year, before utilisation.
ANNUAL_CAPACITY_HOURS: dict[str, float] = {
    "Network Engineering": 1720.0,
    "Data Center & Compute": 1720.0,
    "Cloud & Platform Engineering": 1720.0,
    "Application Modernization": 1720.0,
    "Security Engineering": 1720.0,
    "Service Desk & Operations": 1760.0,
    "Project & Program Management": 1680.0,
}

AVG_BILL_RATE_USD: dict[str, float] = {
    "Network Engineering": 185.0,
    "Data Center & Compute": 178.0,
    "Cloud & Platform Engineering": 205.0,
    "Application Modernization": 196.0,
    "Security Engineering": 215.0,
    "Service Desk & Operations": 128.0,
    "Project & Program Management": 165.0,
}

TARGET_UTILIZATION = 0.85

# Jitter applied to demand-implied headcount so utilisation varies by group instead of
# every group sitting exactly on target. <1.0 means understaffed (hot), >1.0 means bench.
STAFFING_FACTOR_RANGE = (0.82, 1.15)

# Fraction of a whitespace signal's estimated value that is assumed to convert into real
# pipeline. Applied *before* the historical win rate.
WHITESPACE_CONVERSION = 0.25

# Bayesian smoothing strength for historical win rates: the group rate is pulled this many
# pseudo-observations towards the global rate.
WIN_RATE_PRIOR_STRENGTH = 12.0

# A remaining target at or below this many dollars counts as "already met" -- the coverage
# ratio returns null rather than dividing by near-zero. Mirrors ForecastingService.
COVERAGE_TARGET_MET_EPSILON_USD = 25_000.0

# Utilisation above which a resource group raises an over-utilisation signal.
OVER_UTILIZATION_THRESHOLD = 0.92

HISTORICAL_QUARTERS = 8
FORECAST_QUARTERS = 4

N_CLIENTS = 50
N_INSTALLED_BASE = 6_000


def revenue_tier_for_assets(asset_size_usd: float) -> str:
    """Map a client's asset size onto a revenue tier."""
    for threshold, tier in TIER_ASSET_THRESHOLDS:
        if asset_size_usd >= threshold:
            return tier
    return "SMB"


def quarterly_capacity_hours(resource_group: str) -> float:
    """Billable-capable hours one FTE in this group can offer in a quarter, before utilisation."""
    return ANNUAL_CAPACITY_HOURS[resource_group] / 4.0


def demand_hours(opportunity_type: str, amount_usd: float) -> float:
    """Total delivery hours a deal of this type and size implies. Mirrors DeliveryEconomics."""
    return amount_usd * LABOR_CONTENT[opportunity_type] / DELIVERY_RATE_USD_PER_HOUR[opportunity_type]


def demand_hours_by_group(opportunity_type: str, amount_usd: float) -> dict[str, float]:
    """Split :func:`demand_hours` across resource groups using RESOURCE_MIX."""
    total = demand_hours(opportunity_type, amount_usd)
    return {group: total * share for group, share in RESOURCE_MIX[opportunity_type].items()}


def primary_resource_group(opportunity_type: str) -> str:
    """The resource group carrying the largest share of this opportunity type's hours."""
    return max(RESOURCE_MIX[opportunity_type].items(), key=lambda kv: kv[1])[0]


def eol_severity(months_to_end_of_support: float) -> str:
    """Lifecycle severity band. Mirrors SignalDetectionService."""
    if months_to_end_of_support < 0:
        return "Critical"
    if months_to_end_of_support <= 12:
        return "High"
    if months_to_end_of_support <= 24:
        return "Medium"
    return "Low"
