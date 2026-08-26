import React, { useMemo, useState } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { api } from '../api/client.js'
import { useApiData } from '../lib/useApiData.js'
import { usePalette, utilizationColor } from '../lib/usePalette.js'
import { ChartTooltip, chartChrome } from '../components/ChartTooltip.jsx'
import { SignalCard } from '../components/SignalCard.jsx'
import {
  Badge,
  ChipGroup,
  DataTable,
  ErrorNotice,
  KpiRow,
  Legend,
  Loading,
  Panel,
} from '../components/primitives.jsx'
import { count, hours, hoursAxis, percent, percentAxis, shortLabel, usd } from '../lib/format.js'

const URGENCY_TONE = { High: 'critical', Medium: 'medium', Low: 'info', None: 'low' }

export default function CapacityIntelligencePage() {
  const { data, error, loading, reload } = useApiData(api.capacityIntelligence, [])
  const palette = usePalette()
  const [quarter, setQuarter] = useState(null)

  const chrome = chartChrome(palette)

  const activeQuarter = quarter ?? data?.forecastQuarters?.[0] ?? null

  const demandForQuarter = useMemo(() => {
    if (!data || !activeQuarter) return []
    return data.skillDemand
      .filter((row) => row.quarter === activeQuarter)
      .map((row) => {
        const capacity = data.capacity.find((c) => c.resourceGroup === row.resourceGroup)
        return {
          resourceGroup: row.resourceGroup,
          demandHours: Number(row.demandHours),
          pipelineDemandHours: Number(row.pipelineDemandHours),
          whitespaceDemandHours: Number(row.whitespaceDemandHours),
          sustainableHours: capacity ? Number(capacity.sustainableHours) : null,
        }
      })
      .sort((a, b) => b.demandHours - a.demandHours)
  }, [data, activeQuarter])

  const utilizationForQuarter = useMemo(() => {
    if (!data || !activeQuarter) return []
    return data.utilizationProjection
      .filter((row) => row.quarter === activeQuarter)
      .map((row) => ({ ...row, projectedUtilization: Number(row.projectedUtilization) }))
      .sort((a, b) => b.projectedUtilization - a.projectedUtilization)
  }, [data, activeQuarter])

  if (loading) return <Loading what="capacity intelligence" />
  if (error) return <ErrorNotice error={error} onRetry={reload} />

  const quarterOptions = data.forecastQuarters.map((q) => ({ value: q, label: q }))

  return (
    <div className="stack">
      <KpiRow kpis={data.kpis} />

      <Panel
        title="Skill demand against sustainable capacity"
        note="Demand hours are a deterministic transform of the same weighted forecast the revenue page shows: each deal's labour content divided by its delivery rate, split across resource groups by a fixed mix. Sustainable capacity is headcount at target utilisation, not the theoretical maximum."
        actions={<Badge tone="neutral">{count(data.summary.totalHeadcount)} FTE · {count(data.summary.totalContractors)} contractors</Badge>}
      >
        <ChipGroup label="Quarter" options={quarterOptions} value={activeQuarter} onChange={setQuarter} />
        <Legend
          items={[
            { label: 'CRM pipeline demand', color: palette['series-1'] },
            { label: 'Whitespace demand', color: palette['series-3'] },
            { label: 'Sustainable capacity', color: palette['series-2'], variant: 'dashed' },
          ]}
        />
        <ResponsiveContainer width="100%" height={Math.max(280, demandForQuarter.length * 44)}>
          <BarChart
            data={demandForQuarter}
            layout="vertical"
            margin={{ top: 4, right: 70, bottom: 4, left: 4 }}
          >
            <CartesianGrid {...chrome.grid} vertical horizontal={false} />
            <XAxis type="number" {...chrome.axis} tickFormatter={hoursAxis} />
            <YAxis
              type="category"
              dataKey="resourceGroup"
              {...chrome.axis}
              width={180}
              tickFormatter={(value) => shortLabel(value, 24)}
            />
            <Tooltip cursor={chrome.cursor} content={<ChartTooltip formatter={(value) => hours(value)} />} />
            <Bar
              dataKey="pipelineDemandHours"
              name="CRM pipeline demand"
              stackId="demand"
              fill={palette['series-1']}
              barSize={18}
              stroke={palette['surface-1']}
              strokeWidth={2}
            />
            <Bar
              dataKey="whitespaceDemandHours"
              name="Whitespace demand"
              stackId="demand"
              fill={palette['series-3']}
              barSize={18}
              radius={[0, 4, 4, 0]}
              stroke={palette['surface-1']}
              strokeWidth={2}
            />
            <Bar
              dataKey="sustainableHours"
              name="Sustainable capacity"
              fill={palette['series-2']}
              fillOpacity={0.28}
              stroke={palette['series-2']}
              strokeWidth={2}
              barSize={18}
              radius={[0, 4, 4, 0]}
              label={{
                position: 'right',
                formatter: (value) => hours(value),
                fill: palette['text-secondary'],
                fontSize: 11,
              }}
            />
          </BarChart>
        </ResponsiveContainer>
        <p className="footnote">
          For each group, the upper bar is forecast demand for {activeQuarter} (split by whether it
          comes from the CRM or from whitespace) and the lower bar is what that group can sustainably
          deliver. A demand bar longer than its capacity bar needs either capacity or a re-sequenced
          delivery plan.
        </p>
      </Panel>

      <div className="grid grid-2">
        <Panel
          title={`Projected utilisation · ${activeQuarter}`}
          note="Forecast demand over raw quarterly capacity. The reference line is the target utilisation the workforce was sized against; a bar past 100% means the group is committed beyond its available hours, not merely busy."
        >
          <Legend
            items={[
              { label: 'Healthy', color: palette['status-good'] },
              { label: 'Stretched', color: palette['status-serious'] },
              { label: 'Over capacity', color: palette['status-critical'] },
              { label: 'Under-utilised', color: palette['status-warning'] },
            ]}
          />
          <ResponsiveContainer width="100%" height={Math.max(240, utilizationForQuarter.length * 38)}>
            <BarChart
              data={utilizationForQuarter}
              layout="vertical"
              margin={{ top: 4, right: 58, bottom: 4, left: 4 }}
            >
              <CartesianGrid {...chrome.grid} vertical horizontal={false} />
              <XAxis type="number" {...chrome.axis} tickFormatter={percentAxis} domain={[0, 'dataMax']} />
              <YAxis
                type="category"
                dataKey="resourceGroup"
                {...chrome.axis}
                width={180}
                tickFormatter={(value) => shortLabel(value, 24)}
              />
              <Tooltip
                cursor={chrome.cursor}
                content={
                  <ChartTooltip
                    formatter={(value) => percent(value, { digits: 1 })}
                    titleSuffix={(row) => row?.status}
                  />
                }
              />
              <ReferenceLine
                x={0.85}
                stroke={palette['text-muted']}
                strokeDasharray="4 4"
                label={{ value: 'target 85%', fill: palette['text-muted'], fontSize: 11, position: 'top' }}
              />
              <ReferenceLine x={1} stroke={palette['status-critical']} strokeDasharray="2 3" />
              <Bar
                dataKey="projectedUtilization"
                name="Projected utilisation"
                barSize={16}
                radius={[0, 4, 4, 0]}
                label={{
                  position: 'right',
                  formatter: (value) => percent(value),
                  fill: palette['text-secondary'],
                  fontSize: 11,
                }}
              >
                {/* Status colour, and the status word beside it in the table below. */}
                {utilizationForQuarter.map((row) => (
                  <Cell key={row.resourceGroup} fill={utilizationColor(palette, row.status)} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </Panel>

        <Panel
          title="Capacity available"
          note="What each resource group can deliver in a quarter, and what it is running at today. Sustainable hours are raw capacity multiplied by target utilisation."
        >
          <DataTable
            columns={[
              { key: 'resourceGroup', label: 'Resource group', render: (row) => <strong>{row.resourceGroup}</strong> },
              { key: 'headcount', label: 'FTE', numeric: true },
              { key: 'contractorHeadcount', label: 'Contract', numeric: true },
              {
                key: 'quarterlyCapacityHours',
                label: 'Capacity',
                numeric: true,
                render: (row) => hours(row.quarterlyCapacityHours),
              },
              {
                key: 'sustainableHours',
                label: 'Sustainable',
                numeric: true,
                render: (row) => hours(row.sustainableHours),
              },
              {
                key: 'currentUtilization',
                label: 'Current util.',
                numeric: true,
                render: (row) => percent(row.currentUtilization, { digits: 1 }),
              },
              {
                key: 'sustainableRevenueUsd',
                label: 'Revenue ceiling',
                numeric: true,
                render: (row) => usd(row.sustainableRevenueUsd),
              },
            ]}
            rows={data.capacity.map((row) => ({ ...row, __key: row.resourceGroup }))}
          />
        </Panel>
      </div>

      <Panel
        title="Hiring and contractor recommendations"
        note="Permanent hires close the gap against average demand across the forecast horizon; contractors absorb the peak above that. Sizing permanent headcount off the peak quarter is how a capacity model ends up recommending several times the current bench for one busy quarter."
        actions={
          <Badge tone={data.summary.recommendedFteHires > 0 ? 'medium' : 'low'}>
            {count(data.summary.recommendedFteHires)} FTE · {count(data.summary.recommendedContractors)} contractors
          </Badge>
        }
      >
        <DataTable
          columns={[
            { key: 'resourceGroup', label: 'Resource group', render: (row) => <strong>{row.resourceGroup}</strong> },
            { key: 'currentHeadcount', label: 'Current FTE', numeric: true },
            {
              key: 'avgQuarterlyDemandHours',
              label: 'Avg demand',
              numeric: true,
              render: (row) => hours(row.avgQuarterlyDemandHours),
            },
            {
              key: 'peakQuarterlyDemandHours',
              label: 'Peak demand',
              numeric: true,
              render: (row) => hours(row.peakQuarterlyDemandHours),
            },
            {
              key: 'sustainableHours',
              label: 'Sustainable',
              numeric: true,
              render: (row) => hours(row.sustainableHours),
            },
            { key: 'recommendedFteHires', label: 'Hire', numeric: true, render: (row) => <strong>{row.recommendedFteHires}</strong> },
            { key: 'recommendedContractors', label: 'Contract', numeric: true },
            {
              key: 'gapAsShareOfHeadcount',
              label: 'Gap',
              numeric: true,
              render: (row) => percent(row.gapAsShareOfHeadcount),
            },
            {
              key: 'urgency',
              label: 'Urgency',
              render: (row) => <Badge tone={URGENCY_TONE[row.urgency] ?? 'neutral'}>{row.urgency}</Badge>,
            },
          ]}
          rows={data.hiringRecommendations.map((row) => ({ ...row, __key: row.resourceGroup }))}
        />
        <ul className="muted" style={{ fontSize: 12.5, marginTop: 12, paddingLeft: 18 }}>
          {data.hiringRecommendations
            .filter((row) => row.recommendedFteHires > 0 || row.recommendedContractors > 0)
            .map((row) => (
              <li key={row.resourceGroup} style={{ marginBottom: 4 }}>
                <strong style={{ color: 'var(--text-primary)' }}>{row.resourceGroup}:</strong>{' '}
                {row.rationale}
              </li>
            ))}
        </ul>
      </Panel>

      <Panel
        title="Demand across the forecast horizon"
        note="Every resource group, every forecast quarter. This is the table behind the charts above."
      >
        <DataTable
          columns={[
            { key: 'resourceGroup', label: 'Resource group' },
            { key: 'practice', label: 'Practice' },
            { key: 'quarter', label: 'Quarter' },
            { key: 'demandHours', label: 'Demand', numeric: true, render: (row) => hours(row.demandHours) },
            {
              key: 'pipelineDemandHours',
              label: 'From pipeline',
              numeric: true,
              render: (row) => hours(row.pipelineDemandHours),
            },
            {
              key: 'whitespaceDemandHours',
              label: 'From whitespace',
              numeric: true,
              render: (row) => hours(row.whitespaceDemandHours),
            },
            { key: 'demandFte', label: 'Implied FTE', numeric: true, render: (row) => Number(row.demandFte).toFixed(1) },
          ]}
          rows={data.skillDemand.map((row) => ({ ...row, __key: `${row.resourceGroup}-${row.quarter}` }))}
          maxRows={16}
        />
      </Panel>

      {data.capacitySignals.length > 0 && (
        <Panel
          title="Capacity signals"
          note="Resource groups already running above the over-utilisation threshold today, before any of the forecast lands. These carry no revenue — they are constraints, so they never become forecast rows."
        >
          <div className="signal-list">
            {data.capacitySignals.map((signal) => (
              <SignalCard key={signal.id} signal={signal} />
            ))}
          </div>
        </Panel>
      )}
    </div>
  )
}
