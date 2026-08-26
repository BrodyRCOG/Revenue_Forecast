import React, { useMemo, useState } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { api } from '../api/client.js'
import { useApiData } from '../lib/useApiData.js'
import { usePalette } from '../lib/usePalette.js'
import { ChartTooltip, chartChrome } from '../components/ChartTooltip.jsx'
import { Heatmap } from '../components/Heatmap.jsx'
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
import { count, percent, ratio, shortLabel, usd, usdAxis } from '../lib/format.js'

const BREAKDOWNS = [
  { value: 'byQuarter', label: 'Quarter' },
  { value: 'bySegment', label: 'Segment' },
  { value: 'byPractice', label: 'Practice' },
  { value: 'byOpportunityType', label: 'Opportunity type' },
  { value: 'topClients', label: 'Top clients' },
]

const SIGNAL_FILTERS = [
  { value: 'all', label: 'All' },
  { value: 'Critical', label: 'Critical' },
  { value: 'High', label: 'High' },
  { value: 'lifecycle_risk', label: 'Lifecycle' },
  { value: 'contract_renewal_risk', label: 'Renewal' },
]

const SIGNALS_SHOWN = 9

export default function RevenueIntelligencePage() {
  const { data, error, loading, reload } = useApiData(api.revenueIntelligence, [])
  const palette = usePalette()
  const [breakdown, setBreakdown] = useState('byQuarter')
  const [signalFilter, setSignalFilter] = useState('Critical')

  const chrome = chartChrome(palette)

  const filteredSignals = useMemo(() => {
    if (!data) return []
    if (signalFilter === 'all') return data.signals
    return data.signals.filter(
      (signal) => signal.severity === signalFilter || signal.type === signalFilter,
    )
  }, [data, signalFilter])

  if (loading) return <Loading what="revenue intelligence" />
  if (error) return <ErrorNotice error={error} onRetry={reload} />

  const breakdownRows = data[breakdown] ?? []
  // Forward-looking quarters only: a closed quarter has no pipeline left to cover it, so its
  // coverage ratio is a true but useless zero.
  const forwardCoverage = data.coverage.filter((row) => row.phase !== 'actual')

  return (
    <div className="stack">
      <KpiRow kpis={data.kpis} />

      <Panel
        title="Actual, projected and target revenue"
        note="Closed-won revenue runs to the current quarter; the weighted forecast runs from it. This POC recomputes forecasts live and keeps no snapshots, so there is no retrospective projection for a quarter that has already closed — those points are absent rather than invented."
      >
        <Legend
          items={[
            { label: 'Closed won (actual)', color: palette['series-1'], variant: 'line' },
            { label: 'Weighted forecast', color: palette['series-2'], variant: 'dashed' },
            { label: 'Target', color: palette['text-muted'], variant: 'dashed' },
          ]}
        />
        <ResponsiveContainer width="100%" height={280}>
          <ComposedChart data={data.trend} margin={{ top: 8, right: 12, bottom: 4, left: 4 }}>
            <CartesianGrid {...chrome.grid} />
            <XAxis dataKey="quarter" {...chrome.axis} />
            <YAxis {...chrome.axis} tickFormatter={usdAxis} width={62} />
            <Tooltip
              cursor={chrome.lineCursor}
              content={
                <ChartTooltip
                  formatter={(value) => usd(value)}
                  titleSuffix={(point) => point?.phase}
                />
              }
            />
            <ReferenceLine
              x={data.currentQuarter}
              stroke={palette.axis}
              strokeDasharray="3 3"
              label={{ value: 'now', fill: palette['text-muted'], fontSize: 11, position: 'top' }}
            />
            <Line
              type="monotone"
              dataKey="targetUsd"
              name="Target"
              stroke={palette['text-muted']}
              strokeWidth={2}
              strokeDasharray="4 4"
              dot={false}
            />
            <Line
              type="monotone"
              dataKey="actualUsd"
              name="Closed won"
              stroke={palette['series-1']}
              strokeWidth={2}
              dot={{ r: 4, strokeWidth: 2, fill: palette['surface-1'] }}
              activeDot={{ r: 5 }}
              connectNulls={false}
            />
            <Line
              type="monotone"
              dataKey="projectedUsd"
              name="Weighted forecast"
              stroke={palette['series-2']}
              strokeWidth={2}
              strokeDasharray="5 4"
              dot={{ r: 4, strokeWidth: 2, fill: palette['surface-1'] }}
              activeDot={{ r: 5 }}
              connectNulls={false}
            />
          </ComposedChart>
        </ResponsiveContainer>
      </Panel>

      <div className="grid grid-2">
        <Panel
          title="Forecast breakdown"
          note="Weighted forecast, split by the dimension you pick. Whitespace is shown as a share of each bar's label so you can see how much of a bucket is not yet in the CRM."
        >
          <ChipGroup label="Group by" options={BREAKDOWNS} value={breakdown} onChange={setBreakdown} />
          <ResponsiveContainer width="100%" height={Math.max(260, breakdownRows.length * 30)}>
            <BarChart
              data={breakdownRows}
              layout="vertical"
              margin={{ top: 4, right: 60, bottom: 4, left: 4 }}
            >
              <CartesianGrid {...chrome.grid} vertical horizontal={false} />
              <XAxis type="number" {...chrome.axis} tickFormatter={usdAxis} />
              <YAxis
                type="category"
                dataKey="label"
                {...chrome.axis}
                width={150}
                tickFormatter={(value) => shortLabel(value, 20)}
              />
              <Tooltip
                cursor={chrome.cursor}
                content={
                  <ChartTooltip
                    formatter={(value) => usd(value)}
                    titleSuffix={(row) =>
                      `${row?.rowCount} row(s)${
                        Number(row?.whitespaceWeightedUsd) > 0
                          ? `, ${usd(row.whitespaceWeightedUsd)} whitespace`
                          : ''
                      }`
                    }
                  />
                }
              />
              <Bar
                dataKey="weightedAmountUsd"
                name="Weighted forecast"
                fill={palette['series-1']}
                radius={[0, 4, 4, 0]}
                barSize={16}
                label={{
                  position: 'right',
                  formatter: (value) => usd(value),
                  fill: palette['text-secondary'],
                  fontSize: 11,
                }}
              />
            </BarChart>
          </ResponsiveContainer>
        </Panel>

        <Panel
          title="Pipeline coverage"
          note="Weighted pipeline divided by remaining target. A quarter whose target is already met shows no ratio at all — dividing by a remainder that has rounded to nothing is how coverage ratios end up in the tens of thousands."
        >
          <Legend items={[{ label: 'Coverage ratio', color: palette['series-1'] }]} />
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={forwardCoverage} margin={{ top: 16, right: 12, bottom: 4, left: 4 }}>
              <CartesianGrid {...chrome.grid} />
              <XAxis dataKey="quarter" {...chrome.axis} />
              <YAxis {...chrome.axis} tickFormatter={(value) => `${value}x`} width={44} />
              <Tooltip
                cursor={chrome.cursor}
                content={
                  <ChartTooltip
                    formatter={(value) => ratio(value)}
                    titleSuffix={(row) =>
                      row?.targetAlreadyMet
                        ? 'target already met'
                        : `${usd(row?.weightedPipelineUsd)} over ${usd(row?.remainingTargetUsd)}`
                    }
                  />
                }
              />
              <ReferenceLine
                y={1}
                stroke={palette['text-muted']}
                strokeDasharray="4 4"
                label={{ value: '1.0x = covered', fill: palette['text-muted'], fontSize: 11, position: 'insideTopRight' }}
              />
              <Bar
                dataKey="coverageRatio"
                name="Coverage"
                fill={palette['series-1']}
                radius={[4, 4, 0, 0]}
                maxBarSize={54}
                label={{
                  position: 'top',
                  formatter: (value) => (value == null ? '' : `${Number(value).toFixed(2)}x`),
                  fill: palette['text-secondary'],
                  fontSize: 11,
                }}
              />
            </BarChart>
          </ResponsiveContainer>

          <DataTable
            columns={[
              { key: 'quarter', label: 'Quarter' },
              { key: 'targetUsd', label: 'Target', numeric: true, render: (row) => usd(row.targetUsd) },
              { key: 'closedWonUsd', label: 'Won', numeric: true, render: (row) => usd(row.closedWonUsd) },
              {
                key: 'remainingTargetUsd',
                label: 'Remaining',
                numeric: true,
                render: (row) => usd(row.remainingTargetUsd),
              },
              {
                key: 'weightedPipelineUsd',
                label: 'Pipeline',
                numeric: true,
                render: (row) => usd(row.weightedPipelineUsd),
              },
              {
                key: 'coverageRatio',
                label: 'Coverage',
                numeric: true,
                render: (row) =>
                  row.targetAlreadyMet ? <span className="muted">target met</span> : ratio(row.coverageRatio),
              },
            ]}
            rows={forwardCoverage.map((row) => ({ ...row, __key: row.quarter }))}
          />
        </Panel>
      </div>

      <Panel
        title="Opportunity heatmap"
        note="Where the weighted forecast concentrates, by client segment and opportunity type. Empty cells are shown as empty rather than as zero."
      >
        <Heatmap cells={data.heatmap} />
      </Panel>

      <Panel
        title="Signals"
        note="Detected by rule and join logic over the asset, contract and workforce data — not by a model. These are opportunities and risks that are not in the CRM pipeline yet. Press “Explain signal” for a narrative; the badge tells you whether it was model-generated and groundedness-checked, or rendered from a template."
        actions={
          <Badge tone="neutral">
            {count(data.signals.length)} detected · {count(filteredSignals.length)} shown
          </Badge>
        }
      >
        <ChipGroup
          label="Filter"
          options={SIGNAL_FILTERS}
          value={signalFilter}
          onChange={setSignalFilter}
        />
        {filteredSignals.length === 0 ? (
          <p className="empty">No signals match this filter.</p>
        ) : (
          <>
            <div className="signal-list">
              {filteredSignals.slice(0, SIGNALS_SHOWN).map((signal) => (
                <SignalCard key={signal.id} signal={signal} />
              ))}
            </div>
            {filteredSignals.length > SIGNALS_SHOWN && (
              <p className="footnote">
                Showing the {SIGNALS_SHOWN} highest-value of {filteredSignals.length} matching
                signals. Signals are ranked by severity, then by estimated value.
              </p>
            )}
          </>
        )}
      </Panel>

      <div className="grid grid-2">
        <Panel
          title="Smoothed win rates"
          note="Fitted on closed history, with each (segment, opportunity type) cell shrunk towards its type's rate. The raw column shows why: a cell with two closed deals and one win reads as 50% before smoothing."
        >
          <DataTable
            columns={[
              { key: 'opportunityType', label: 'Opportunity type' },
              { key: 'segment', label: 'Segment' },
              {
                key: 'smoothedWinRate',
                label: 'Smoothed',
                numeric: true,
                render: (row) => <strong>{percent(row.smoothedWinRate, { digits: 1 })}</strong>,
              },
              {
                key: 'rawWinRate',
                label: 'Raw',
                numeric: true,
                render: (row) => <span className="muted">{percent(row.rawWinRate, { digits: 1 })}</span>,
              },
              { key: 'closedCount', label: 'Closed', numeric: true },
              { key: 'wonCount', label: 'Won', numeric: true },
            ]}
            rows={data.winRates.map((row, index) => ({
              ...row,
              __key: `${row.segment}-${row.opportunityType}-${index}`,
            }))}
            maxRows={14}
          />
        </Panel>

        <Panel
          title="Forecast composition"
          note="How much of the weighted forecast is live CRM pipeline versus whitespace the sales cycle has not seen. Whitespace is discounted twice — once for conversion, once by the same historical win rate a real opportunity gets."
        >
          <ResponsiveContainer width="100%" height={200}>
            <BarChart
              data={[
                {
                  name: 'Weighted forecast',
                  pipeline: Number(data.totals.pipelineOnlyWeightedUsd),
                  whitespace: Number(data.totals.whitespaceWeightedUsd),
                },
              ]}
              layout="vertical"
              margin={{ top: 4, right: 16, bottom: 4, left: 4 }}
            >
              <CartesianGrid {...chrome.grid} vertical horizontal={false} />
              <XAxis type="number" {...chrome.axis} tickFormatter={usdAxis} />
              <YAxis type="category" dataKey="name" {...chrome.axis} width={130} />
              <Tooltip cursor={chrome.cursor} content={<ChartTooltip formatter={(value) => usd(value)} />} />
              {/* 2px surface gap between stacked segments, per the mark spec. */}
              <Bar
                dataKey="pipeline"
                name="CRM pipeline"
                stackId="a"
                fill={palette['series-1']}
                barSize={40}
                stroke={palette['surface-1']}
                strokeWidth={2}
              />
              <Bar
                dataKey="whitespace"
                name="Whitespace"
                stackId="a"
                fill={palette['series-3']}
                barSize={40}
                radius={[0, 4, 4, 0]}
                stroke={palette['surface-1']}
                strokeWidth={2}
              />
            </BarChart>
          </ResponsiveContainer>
          <Legend
            items={[
              { label: `CRM pipeline · ${usd(data.totals.pipelineOnlyWeightedUsd)}`, color: palette['series-1'] },
              { label: `Whitespace · ${usd(data.totals.whitespaceWeightedUsd)}`, color: palette['series-3'] },
            ]}
          />
          <DataTable
            columns={[
              { key: 'metric', label: 'Metric' },
              { key: 'value', label: 'Value', numeric: true },
            ]}
            rows={[
              { __key: 'gross', metric: 'Gross pipeline', value: usd(data.totals.grossPipelineUsd) },
              { __key: 'weighted', metric: 'Weighted forecast', value: usd(data.totals.weightedPipelineUsd) },
              { __key: 'blended', metric: 'Blended win rate', value: percent(data.totals.blendedWinRate, { digits: 1 }) },
              { __key: 'open', metric: 'Open opportunities', value: count(data.totals.openOpportunityCount) },
              { __key: 'ws', metric: 'Whitespace signals priced', value: count(data.totals.whitespaceSignalCount) },
              { __key: 'won', metric: 'Closed won to date', value: usd(data.totals.closedWonToDateUsd) },
            ]}
          />
        </Panel>
      </div>

      <Panel title="Actual against projected" note={`As of ${data.asOfDate}. Variance is only reported where both an actual and a projection exist — the in-progress quarter.`}>
        <DataTable
          columns={[
            { key: 'quarter', label: 'Quarter' },
            {
              key: 'phase',
              label: 'Phase',
              render: (row) => (
                <Badge tone={row.phase === 'forecast' ? 'info' : row.phase === 'in-progress' ? 'medium' : 'neutral'}>
                  {row.phase}
                </Badge>
              ),
            },
            { key: 'actualUsd', label: 'Actual', numeric: true, render: (row) => usd(row.actualUsd) },
            { key: 'projectedUsd', label: 'Projected', numeric: true, render: (row) => usd(row.projectedUsd) },
            {
              key: 'varianceUsd',
              label: 'Variance',
              numeric: true,
              render: (row) =>
                row.varianceUsd == null ? (
                  <span className="muted">—</span>
                ) : (
                  <span className={Number(row.varianceUsd) >= 0 ? 'delta-up' : 'delta-down'}>
                    {usd(row.varianceUsd)}
                  </span>
                ),
            },
            {
              key: 'variancePct',
              label: 'Variance %',
              numeric: true,
              render: (row) => (row.variancePct == null ? <span className="muted">—</span> : percent(row.variancePct, { digits: 1 })),
            },
          ]}
          rows={data.actualVsProjected.map((row) => ({ ...row, __key: row.quarter }))}
        />
      </Panel>
    </div>
  )
}
