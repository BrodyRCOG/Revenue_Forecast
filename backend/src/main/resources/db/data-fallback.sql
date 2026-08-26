-- Minimal hand-written seed so the app boots and the UI renders something even when
-- data-tools/build_dataset.py has never been run. DataBootstrap loads this ONLY when the
-- generated dataset is absent (clients table empty after data.sql init).
--
-- Do not grow this file. If you need realistic volumes, run:
--   python data-tools/build_dataset.py

INSERT INTO clients (id, name, segment, region, state, revenue_tier, asset_size_usd, branch_count, core_banking_platform, account_owner, relationship_start) VALUES
  ('CLI-0001', 'Cedar Valley Community Bank', 'Community Bank', 'Midwest', 'IA', 'Mid-Market', 1450000000.00, 14, 'Fiserv Premier', 'A. Whitfield', DATE '2019-04-01'),
  ('CLI-0002', 'Harbor Point Credit Union', 'Credit Union', 'Northeast', 'MA', 'SMB', 620000000.00, 6, 'Symitar Episys', 'R. Okafor', DATE '2021-09-15');

INSERT INTO oem_models (id, oem, model_name, category, lifecycle_years, support_tail_years, release_date, end_of_sale, end_of_support, is_real_anchor, source_url, source_confidence) VALUES
  ('OEM-0001', 'Cisco', 'Catalyst 2960-X Series', 'Switching', 7, 5, DATE '2013-06-01', DATE '2022-10-31', DATE '2027-10-31', TRUE, 'https://www.cisco.com/c/en/us/products/collateral/switches/catalyst-2960-x-series-switches/eos-eol-notice-c51-744295.html', 'vendor_official'),
  ('OEM-0002', 'HPE', 'ProLiant DL380 Gen9', 'Server', 6, 5, DATE '2014-09-01', DATE '2019-07-31', DATE '2026-12-31', TRUE, 'https://support.hpe.com/hpesc/public/docDisplay?docId=emr_na-a00092201en_us', 'vendor_official');

INSERT INTO installed_base (id, client_id, oem_model_id, asset_tag, site, quantity, install_date, criticality, annual_support_cost_usd) VALUES
  ('IB-000001', 'CLI-0001', 'OEM-0001', 'CV-SW-0001', 'Cedar Valley - Main Branch', 22, DATE '2017-03-14', 'Mission Critical', 41800.00),
  ('IB-000002', 'CLI-0001', 'OEM-0002', 'CV-SR-0002', 'Cedar Valley - Data Center', 6, DATE '2016-11-02', 'Business Critical', 27600.00),
  ('IB-000003', 'CLI-0002', 'OEM-0001', 'HP-SW-0001', 'Harbor Point - Operations Center', 9, DATE '2018-05-20', 'Business Critical', 17100.00);

INSERT INTO contracts (id, client_id, service_type, arr_usd, start_date, end_date, renewal_risk, renewal_probability, auto_renew, nps_score) VALUES
  ('CTR-0001', 'CLI-0001', 'Managed Network', 480000.00, DATE '2023-01-01', DATE '2026-12-31', 'High', 0.5800, FALSE, 21),
  ('CTR-0002', 'CLI-0002', 'Managed Security', 265000.00, DATE '2024-07-01', DATE '2027-06-30', 'Low', 0.9100, TRUE, 54);

INSERT INTO opportunities (id, client_id, name, opportunity_type, practice, resource_group, amount_usd, probability, stage, status, lead_source, created_date, close_date, quarter) VALUES
  ('OPP-0001', 'CLI-0001', 'POP - New Branch - Cedar Valley', 'Infrastructure Refresh', 'Infrastructure', 'Network Engineering', 620000.00, 0.6000, 'Proposal', 'Open', 'CRM', DATE '2026-05-04', DATE '2026-11-20', '2026-Q4'),
  ('OPP-0002', 'CLI-0002', 'Core Platform Modernization - Harbor Point', 'Modernization', 'Applications', 'Cloud & Platform Engineering', 410000.00, 0.4500, 'Qualification', 'Open', 'Referral', DATE '2026-06-11', DATE '2027-02-15', '2027-Q1'),
  ('OPP-0003', 'CLI-0001', 'Managed Network Expansion - Cedar Valley', 'Managed Services', 'Managed Services', 'Service Desk & Operations', 285000.00, 0.5500, 'Negotiation', 'Open', 'Renewal', DATE '2026-07-02', DATE '2026-12-05', '2026-Q4'),
  ('OPP-0004', 'CLI-0001', 'Branch Switching Refresh - Cedar Valley', 'Infrastructure Refresh', 'Infrastructure', 'Network Engineering', 540000.00, 1.0000, 'Closed Won', 'Won', 'CRM', DATE '2025-08-01', DATE '2026-02-28', '2026-Q1'),
  ('OPP-0005', 'CLI-0002', 'Endpoint Security Uplift - Harbor Point', 'Security', 'Security', 'Security Engineering', 190000.00, 0.0000, 'Closed Lost', 'Lost', 'Partner', DATE '2025-09-12', DATE '2026-03-31', '2026-Q1'),
  ('OPP-0006', 'CLI-0002', 'Data Center Exit - Harbor Point', 'Cloud Migration', 'Cloud', 'Cloud & Platform Engineering', 330000.00, 1.0000, 'Closed Won', 'Won', 'CRM', DATE '2025-11-05', DATE '2026-06-30', '2026-Q2');

INSERT INTO project_billing (id, client_id, project_name, practice, resource_group, quarter, billed_hours, billed_amount_usd, delivery_utilization) VALUES
  ('PB-000001', 'CLI-0001', 'Branch Switching Refresh - Cedar Valley', 'Infrastructure', 'Network Engineering', '2026-Q1', 1180.00, 218300.00, 0.8400),
  ('PB-000002', 'CLI-0002', 'Data Center Exit - Harbor Point', 'Cloud', 'Cloud & Platform Engineering', '2026-Q2', 1460.00, 291000.00, 0.8700);

INSERT INTO workforce (id, resource_group, practice, region, headcount, contractor_headcount, avg_bill_rate_usd, target_utilization, current_utilization, annual_capacity_hours_per_fte) VALUES
  ('WF-0001', 'Network Engineering', 'Infrastructure', 'National', 12, 2, 185.00, 0.8500, 0.8800, 1720.00),
  ('WF-0002', 'Cloud & Platform Engineering', 'Cloud', 'National', 15, 3, 205.00, 0.8500, 0.8100, 1720.00),
  ('WF-0003', 'Service Desk & Operations', 'Managed Services', 'National', 18, 4, 128.00, 0.8500, 0.7900, 1760.00),
  ('WF-0004', 'Security Engineering', 'Security', 'National', 8, 1, 215.00, 0.8500, 0.9200, 1720.00);

INSERT INTO targets (id, practice, quarter, target_amount_usd) VALUES
  ('TGT-0001', 'Infrastructure', '2026-Q4', 900000.00),
  ('TGT-0002', 'Managed Services', '2026-Q4', 400000.00),
  ('TGT-0003', 'Applications', '2027-Q1', 600000.00),
  ('TGT-0004', 'Cloud', '2027-Q1', 500000.00);
