import React from 'react'
import { api } from '../api/client.js'
import { useApiData } from '../lib/useApiData.js'
import {
  Badge,
  DataTable,
  ErrorNotice,
  KpiRow,
  Loading,
  Panel,
  SeverityBadge,
} from '../components/primitives.jsx'
import { count, usd } from '../lib/format.js'

const TYPE_LABEL = {
  lifecycle_risk: 'Lifecycle refresh',
  contract_renewal_risk: 'Renewal expansion',
  capacity_pressure: 'Capacity pressure',
}

function months(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—'
  return `${Number(value).toFixed(1)} mo`
}

/**
 * The quick-look tab for a business manager: what needs attention now, without reading four
 * dashboards. Two things carry it -- contracts coming up for renewal inside the three-month window,
 * and the largest revenue-increase opportunities the engine has surfaced. Every number here is
 * computed by the backend; this page only lays them out.
 */
export default function ManagerOverviewPage() {
  const { data, error, loading, reload } = useApiData(api.managerOverview, [])

  if (loading) return <Loading what="manager overview" />
  if (error) return <ErrorNotice error={error} onRetry={reload} />

  return (
    <div className="stack">
      <KpiRow kpis={data.kpis} />

      <Panel
        title="Contracts ending within 3 months"
        note="Every managed-services contract whose term ends inside the three-month renewal window, most imminent first. This is the standing renewal rule applied to the data — a contract shows up here whether or not an expansion play is already in motion, so nothing quietly lapses."
        actions={
          <Badge tone={data.renewalWindow.length > 0 ? 'medium' : 'low'}>
            {count(data.renewalWindow.length)} up for renewal
          </Badge>
        }
      >
        {data.renewalWindow.length === 0 ? (
          <p className="empty">No contracts are ending in the next three months.</p>
        ) : (
          <DataTable
            columns={[
              { key: 'clientName', label: 'Client', render: (row) => <strong>{row.clientName}</strong> },
              { key: 'segment', label: 'Segment', render: (row) => <span className="muted">{row.segment ?? '—'}</span> },
              { key: 'serviceType', label: 'Service' },
              { key: 'arrUsd', label: 'ARR', numeric: true, render: (row) => usd(row.arrUsd) },
              { key: 'endDate', label: 'Ends' },
              {
                key: 'monthsToRenewal',
                label: 'In',
                numeric: true,
                render: (row) => months(row.monthsToRenewal),
              },
              {
                key: 'renewalRisk',
                label: 'Renewal risk',
                render: (row) => <SeverityBadge severity={row.renewalRisk} />,
              },
              {
                key: 'autoRenew',
                label: 'Auto-renew',
                render: (row) => (
                  <Badge tone={row.autoRenew ? 'low' : 'medium'}>{row.autoRenew ? 'Yes' : 'No'}</Badge>
                ),
              },
              { key: 'npsScore', label: 'NPS', numeric: true },
            ]}
            rows={data.renewalWindow.map((row) => ({ ...row, __key: row.contractId }))}
          />
        )}
      </Panel>

      <Panel
        title="Top revenue opportunities"
        note="The highest-value opportunities the engine has detected but that are not yet in the CRM pipeline — infrastructure refreshes coming due and at-risk contracts worth expanding — ranked by estimated value. Open the Revenue Intelligence tab for the full list and the reasoning behind each."
        actions={<Badge tone="neutral">{count(data.topOpportunities.length)} shown</Badge>}
      >
        {data.topOpportunities.length === 0 ? (
          <p className="empty">No revenue-bearing opportunities are open right now.</p>
        ) : (
          <DataTable
            columns={[
              { key: 'clientName', label: 'Client', render: (row) => <strong>{row.clientName}</strong> },
              { key: 'title', label: 'Opportunity' },
              {
                key: 'type',
                label: 'Type',
                render: (row) => <Badge tone="neutral">{TYPE_LABEL[row.type] ?? row.type}</Badge>,
              },
              { key: 'recommendedQuarter', label: 'Target' },
              {
                key: 'estimatedValueUsd',
                label: 'Est. value',
                numeric: true,
                render: (row) => <strong>{usd(row.estimatedValueUsd)}</strong>,
              },
            ]}
            rows={data.topOpportunities.map((row) => ({ ...row, __key: row.id }))}
          />
        )}
      </Panel>
    </div>
  )
}
