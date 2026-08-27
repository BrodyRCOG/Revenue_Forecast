reference_data.py is unchanged

I edited nothing. Nothing under data-tools/, backend/ or frontend/ was touched. The anchors have not been updated — a human must apply the block below.

Summary counts

- 7 anchors researched
- 2 dates confirmed unchanged against a vendor page I actually read (microsoft-ws2016, microsoft-sql2016)
- 1 partially confirmed (vmware-esxi67 — general support end confirmed, technical guidance end not on any vendor page)
- 1 corroborated but not vendor-verifiable (cisco-c2960x — cisco.com returns 403 to my fetch tool)
- 3 anchors with changed dates (hpe-dl380-gen9, fortinet-fg60e, netapp-fas2650) — 6 date fields
- 7 release_date values I could not source at all
- 5 source URLs dead/moved/stating no date; 1 unverifiable; only 1 (microsoft-ws2016) fully healthy

That last line is the real headline: six of seven anchor URLs no longer substantiate their date.

Key findings

Fortinet is the big correction. The repo is ~18 months late: EOO 2023-06-30 → 2021-12-29, EOS 2028-06-30 → 2026-12-29. Its URL 404s and pointed at a FortiOS firmware page rather than the 60E appliance.

HPE 2019-07-31 → 2020-07-22 and 2026-12-31 → 2025-07-22. NetApp 2020-05-31 → 2019-05-31 and 2025-11-30 → 2024-05-31.

Both Microsoft anchors are correct as-is, and I want to flag a trap: the vendor tables render 1/12/2022/1/13/2027 and 7/14/2021/7/15/2026 — one day later than the repo, because those are UTC instants of 23:59:59 PT. The repo's values are the right calendar dates. SQL Server 2016 self-confirms: the ESU Year 1 window opens 2026-07-15, so extended support ended 2026-07-14. A future refresher reading the raw table will be tempted to add a day to four fields; they should not. (SQL's URL still needs replacing — the microsoft- prefix slug 404s; correct is .../products/sql-server-2016.)

Three anchors drop to uncertain (HPE, Fortinet, NetApp) plus VMware. That value was previously unused in the repo. I used it rather than dressing third-party aggregator dates up as aggregator-grade. I did not invent a single date: every unsourceable value was left at its existing setting and listed as unresolved, including all five non-Microsoft release_dates and VMware's technical-guidance end.

I also kept the two milestone mappings intact — end_of_sale = mainstream/general support end, end_of_support = extended/technical guidance end — and preserved the HPE and NetApp comments explaining why their dates are representative.

Downstream effects

- Two severity band changes at as_of = 2026-08-27: HPE High → Critical, Fortinet Medium → High. Anchor mix {Crit 3, High 2, Med 2} → {Crit 4, High 2, Med 1}. Both move toward greater severity, so whitespace_signals_exist gets easier; eol_severity_spread should stay green since the 45 procedural rows dominate the distribution — but verify rather than assume.
- Anchor count stays 7, so next_id stays 8 and no OEM-00xx id shifts. Any recorded id remains valid.
- Regeneration still required — three end_of_sale values move, and installed-base rows derive from min(end_of_sale, as_of).
- No new category, so no hand-edits to UNIT_REFRESH_COST_USD / INSTALLED_BASE_CATEGORY_WEIGHTS / QUANTITY_RANGE.
- No OEM_CATALOG.derived_from breaks — no anchor_key renamed or removed; all 7 keys still targeted by the 15 patterns.
- Java side untouched — no band boundary changes, so DeliveryEconomics.eolSeverity stays in parity.

Paste-ready diff (the three changed anchors)

    {
        "anchor_key": "hpe-dl380-gen9",
        "oem": "HPE",
        "model_name": "ProLiant DL380 Gen9",
        "category": "Server",
        "release_date": "2014-09-01",
        "end_of_sale": "2020-07-22",
        "end_of_support": "2025-07-22",
        # HPE publishes support end per-SKU and per-region, and does not publish per-product EOL
        # documents publicly at all -- announcements sit behind the support portal. Two independent
        # aggregators give 2020-07-22 / 2025-07-22; a third gives EOSL 2025-10-31. Representative
        # date from a third party, not a vendor page: uncertain, not aggregator.
        "source_confidence": "uncertain",
        "source_url": (
            "https://serviceexpress.com/eol-eosl-database/products/"
            "hpe-proliant-dl380g9-hp-proliant-servers/"
        ),
    },
    {
        "anchor_key": "fortinet-fg60e",
        "oem": "Fortinet",
        "model_name": "FortiGate-60E",
        "category": "Firewall",
        "release_date": "2016-06-01",
        # End of Order (EOO) / End of Support (EOS), per Fortinet's milestone definitions.
        "end_of_sale": "2021-12-29",
        "end_of_support": "2026-12-29",
        # Fortinet's system of record (support.fortinet.com/welcome/#/lifecycle) is a client-side JS
        # app this could not read; the previous docs.fortinet.com URL now 404s and covered FortiOS
        # firmware, not the 60E appliance. Two aggregators agree, and the 60-month EOO->EOS gap
        # matches Fortinet's published hardware policy. Promote to vendor_official once someone
        # reads the 60E row in the portal directly (no login reportedly required).
        "source_confidence": "uncertain",
        "source_url": "https://www.eol.network/fortinet/fortigate/FortiGate-60E",
    },
    {
        "anchor_key": "netapp-fas2650",
        "oem": "NetApp",
        "model_name": "FAS2650",
        "category": "Storage",
        "release_date": "2016-03-01",
        "end_of_sale": "2019-05-31",
        "end_of_support": "2024-05-31",
        # NetApp end-of-support runs off the individual serial's contract; representative date.
        # Three aggregators agree on EOSL 2024-05-31; end-of-sale varies (2019-05-31 vs 2019-05-12).
        # The vendor source (hwu.netapp.com Hardware Universe) is a JS app that could not be read,
        # and the previous mysupport.netapp.com URL states no hardware dates.
        "source_confidence": "uncertain",
        "source_url": (
            "https://serviceexpress.com/eol-eosl-database/products/"
            "netapp-fas2650-netapp-filer-disk-storage/"
        ),
    },

Plus two URL-only changes (dates untouched): SQL Server 2016 → https://learn.microsoft.com/en-us/lifecycle/products/sql-server-2016, and VMware → the Broadcom KB 326984 page, with VMware's confidence dropping to uncertain because only one of its two milestones is substantiated.

Cisco I left byte-identical — dates, URL and vendor_official all unchanged. I couldn't read any cisco.com page, so I won't swap a URL while keeping a date I couldn't check. Worth noting the bulletin ID in the repo (c51-744295) appears in no search result, while the 2960-X family bulletin is c51-744432; someone should open both in a browser.

Verification after applying

python data-tools/build_dataset.py --skip-sql → confirm real_anchors_have_source_url, lifecycle_dates_ordered (all three changed anchors order correctly) and source_confidence_domain pass — note this is uncertain's first use in the repo, so confirm it genuinely passes. Check the eol_severity_spread warning delta. Then full python data-tools/build_dataset.py, then cd backend && mvn test.

Highest-value follow-up

The Fortinet lifecycle portal reportedly needs no login. A human with a browser can read the 60E row and promote that anchor from uncertain to vendor_official in about two minutes — and it's the anchor whose dates were most wrong.

Sources: Windows Server 2016 lifecycle (https://learn.microsoft.com/en-us/lifecycle/products/windows-server-2016) · SQL Server 2016 lifecycle (https://learn.microsoft.com/en-us/lifecycle/products/sql-server-2016) · SQL end-of-support overview (https://learn.microsoft.com/en-us/sql/sql-server/end-of-support/sql-server-end-of-support-overview?view=sql-server-ver17) · Broadcom KB 326984 (https://knowledge.broadcom.com/external/article/326984/end-of-general-support-for-vsphere-65677.html) · Fortinet community — lifecycle portal & milestone definitions (https://community.fortinet.com/customer-service-42/customer-service-tip-product-life-cycle-information-on-fortinet-products-96482) · eol.network FortiGate-60E (https://www.eol.network/fortinet/fortigate/FortiGate-60E) · DataCenter360 Fortinet EOL (https://datacenter360.ca/faqs/fortinet-faqs/end-of-life-product-life-cycle/) · Service Express DL380 G9 (https://serviceexpress.com/eol-eosl-database/products/hpe-proliant-dl380g9-hp-proliant-servers/) · Evernex DL380 G9 (https://evernex.com/eosl/hpe/server/proliant-dl380-g9/) · Service Express FAS2650 (https://serviceexpress.com/eol-eosl-database/products/netapp-fas2650-netapp-filer-disk-storage/) · ReluTech FAS2650 (https://relutech.com/eol-eosl/netapp/fas-2650) · Park Place FAS2650 (https://www.parkplacetechnologies.com/eosl/netapp/fas-2650/)