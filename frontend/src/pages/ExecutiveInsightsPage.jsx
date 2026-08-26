import React, { useState } from 'react'
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
import {
  Badge,
  DataTable,
  ErrorNotice,
  KpiRow,
  Legend,
  Loading,
  Panel,
} from '../components/primitives.jsx'
import { count, hours, percent, ratio, signedPercent, signedUsd, usd, usdAxis } from '../lib/format.js'

const LEVERS = [
  {
    key: 'winRateDelta',
    name: 'Win rate',
    hint: 'Scales the smoothed historical win rate applied to every deal.',
  },
  {
    key: 'dealSizeDelta',
    name: 'Deal size',
    hint: 'Scales every opportunity and whitespace amount before weighting.',
  },
  {
    key: 'targetDelta',
    name: 'Revenue target',
    hint: 'Scales quarterly targets — the coverage-ratio denominator.',
  },
]

const EVAL_TONE = { all_clear: 'low', warnings: 'medium', failing: 'critical', not_run: 'neutral' }
const EVAL_LABEL = {
  all_clear: 'All clear',
  warnings: 'Warnings',
  failing: 'Failing',
  not_run: 'Not run',
}

export default function ExecutiveInsightsPage() {
  const palette = usePalette()
  const [assumptions, setAssumptions] = useState({ winRateDelta: 0, dealSizeDelta: 0, targetDelta: 0 })

  const summary = useApiData(() => api.executiveSummary(assumptions), [
    assumptions.winRateDelta,
    assumptions.dealSizeDelta,
    assumptions.targetDelta,
  ])
  const evals = useApiData(api.evals, [])

  const chrome = chartChrome(palette)
  const isBaseline =
    assumptions.winRateDelta === 0 && assumptions.dealSizeDelta === 0 && assumptions.targetDelta === 0

  if (summary.loading && !summary.data) return <Loading what="executive summary" />
  if (summary.error) return <ErrorNotice error={summary.error} onRetry={summary.reload} />

  const data = summary.data
  const scenario = data.baselineScenario
  const { baseline, scenario: current, delta } = scenario

  return (
    <div className="stack">
      <KpiRow kpis={data.kpis} />

      <div className="grid grid-2">
        <Panel
          title="What-if"
          note="Each slider re-runs the same forecasting and capacity code with adjusted assumptions. Nothing is re-weighted downstream, so revenue, coverage and delivery demand always describe the same scenario — move win rate and the hiring requirement moves with it."
          actions={
            <button
              type="button"
              className="button"
              disabled={isBaseline}
              onClick={() => setAssumptions({ winRateDelta: 0, dealSizeDelta: 0, targetDelta: 0 })}
            >
              Reset to baseline
            </button>
          }
        >
          <div className="slider-row">
            {LEVERS.map((lever) => (
              <label className="slider" key={lever.key}>
                <span className="slider-head">
                  <span className="slider-name">{lever.name}</span>
                  <span className="slider-value">{signedPercent(assumptions[lever.key])}</span>
                </span>
                <input
                  type="range"
                  min={-0.5}
                  max={0.5}
                  step={0.05}
                  value={assumptions[lever.key]}
                  onChange={(event) =>
                    setAssumptions((previous) => ({
                      ...previous,
                      [lever.key]: Number(event.target.value),
                    }))
                  }
                />
                <span className="slider-hint">{lever.hint}</span>
              </label>
            ))}
          </div>

          <p className="panel-note" style={{ marginTop: 14, marginBottom: 0 }}>
            {scenario.interpretation}
          </p>
          {summary.loading && <p className="footnote">Recomputing…</p>}
        </Panel>

        <Panel
          title="Baseline against scenario"
          note="A dash means the metric is genuinely undefined in that scenario — for instance coverage when every quarter's target is already met. It does not mean zero."
        >
          <DataTable
            columns={[
              { key: 'metric', label: 'Metric' },
              { key: 'baseline', label: 'Baseline', numeric: true },
              { key: 'scenario', label: 'Scenario', numeric: true },
              {
                key: 'delta',
                label: 'Delta',
                numeric: true,
                render: (row) => <span className={row.tone}>{row.delta}</span>,
              },
            ]}
            rows={[
              {
                __key: 'weighted',
                metric: 'Weighted pipeline',
                baseline: usd(baseline.weightedPipelineUsd),
                scenario: usd(current.weightedPipelineUsd),
                delta: signedUsd(delta.weightedPipelineUsd),
                tone: toneFor(delta.weightedPipelineUsd),
              },
              {
                __key: 'projected',
                metric: 'Projected total revenue',
                baseline: usd(baseline.projectedTotalRevenueUsd),
                scenario: usd(current.projectedTotalRevenueUsd),
                delta: signedUsd(delta.projectedTotalRevenueUsd),
                tone: toneFor(delta.projectedTotalRevenueUsd),
              },
              {
                __key: 'target',
                metric: 'Total target',
                baseline: usd(baseline.totalTargetUsd),
                scenario: usd(current.totalTargetUsd),
                delta: signedUsd(delta.totalTargetUsd),
                // A higher target is harder, so the sign reads the other way round here.
                tone: toneFor(delta.totalTargetUsd, true),
              },
              {
                __key: 'coverage',
                metric: 'Blended coverage',
                baseline: ratio(baseline.blendedCoverageRatio),
                scenario: ratio(current.blendedCoverageRatio),
                delta:
                  delta.blendedCoverageRatio == null
                    ? '—'
                    : `${Number(delta.blendedCoverageRatio) > 0 ? '+' : ''}${Number(
                        delta.blendedCoverageRatio,
                      ).toFixed(2)}x`,
                tone: toneFor(delta.blendedCoverageRatio),
              },
              {
                __key: 'demand',
                metric: 'Forecast delivery demand',
                baseline: hours(baseline.forecastDemandHours),
                scenario: hours(current.forecastDemandHours),
                delta:
                  delta.forecastDemandHours == null
                    ? '—'
                    : `${Number(delta.forecastDemandHours) > 0 ? '+' : ''}${hours(delta.forecastDemandHours)}`,
                tone: 'delta-flat',
              },
              {
                __key: 'hires',
                metric: 'Recommended hires',
                baseline: count(baseline.recommendedFteHires),
                scenario: count(current.recommendedFteHires),
                delta:
                  delta.recommendedFteHires == null
                    ? '—'
                    : `${delta.recommendedFteHires > 0 ? '+' : ''}${delta.recommendedFteHires} FTE`,
                tone: 'delta-flat',
              },
              {
                __key: 'gap',
                metric: 'Hiring gap',
                baseline: percent(baseline.hiringGapAsShareOfHeadcount),
                scenario: percent(current.hiringGapAsShareOfHeadcount),
                delta: '—',
                tone: 'delta-flat',
              },
            ]}
          />
        </Panel>
      </div>

      <Panel
        title="Narrative"
        note="Generated from figures that were already computed, then checked: every number in the text must trace back to a number that went into the prompt. If it does not, the generated text is discarded and a template over the same data is served instead."
        actions={
          <span style={{ display: 'flex', gap: 6 }}>
            <Badge tone={data.narrative.source === 'llm' ? 'info' : 'neutral'}>
              {data.narrative.source === 'llm' ? 'LLM' : 'Template'}
            </Badge>
            <Badge tone={data.narrative.grounded ? 'low' : 'medium'}>
              {data.narrative.grounded ? 'Grounded' : 'Not model-generated'}
            </Badge>
          </span>
        }
      >
        <p className="narrative-body" style={{ fontSize: 14 }}>
          {data.narrative.text}
        </p>
        <p className="narrative-reason">{data.narrative.reason}</p>
        {data.narrative.groundTruth?.length > 0 && (
          <p className="footnote">
            Narration was permitted {data.narrative.groundTruth.length} figure(s), all of them
            computed by the deterministic layer.
          </p>
        )}
      </Panel>

      <Panel
        title="Revenue trend under this scenario"
        note={`As of ${data.asOfDate}. Actuals are fixed; the forecast line and the target line both move with the sliders.`}
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
            <Tooltip cursor={chrome.lineCursor} content={<ChartTooltip formatter={(value) => usd(value)} />} />
            <ReferenceLine x={data.currentQuarter} stroke={palette.axis} strokeDasharray="3 3" />
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
              connectNulls={false}
            />
          </ComposedChart>
        </ResponsiveContainer>
      </Panel>

      <div className="grid grid-2">
        <Panel
          title="Coverage by quarter"
          note="Under the current scenario. Quarters whose target is already met carry no bar, because there is no remainder to cover."
        >
          <ResponsiveContainer width="100%" height={230}>
            <BarChart
              data={current.coverageByQuarter.filter((row) => row.phase !== 'actual')}
              margin={{ top: 16, right: 12, bottom: 4, left: 4 }}
            >
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
                label={{ value: '1.0x', fill: palette['text-muted'], fontSize: 11, position: 'insideTopRight' }}
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
        </Panel>

        <Panel
          title="Where capacity binds first"
          note="The resource groups this scenario would need to add people to, largest gap first."
        >
          {data.topHiringNeeds.length === 0 ? (
            <p className="empty">No group needs additional capacity under this scenario.</p>
          ) : (
            <DataTable
              columns={[
                { key: 'resourceGroup', label: 'Resource group', render: (row) => <strong>{row.resourceGroup}</strong> },
                { key: 'currentHeadcount', label: 'Current FTE', numeric: true },
                { key: 'recommendedFteHires', label: 'Hire', numeric: true },
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
                  render: (row) => (
                    <Badge tone={{ High: 'critical', Medium: 'medium', Low: 'info' }[row.urgency] ?? 'low'}>
                      {row.urgency}
                    </Badge>
                  ),
                },
              ]}
              rows={data.topHiringNeeds.map((row) => ({ ...row, __key: row.resourceGroup }))}
            />
          )}
        </Panel>
      </div>

      <Panel
        title="Model health"
        note="The three eval layers. Data-layer checks run in Python before the backend ever sees the dataset; pipeline and narrative checks are JUnit tests over the Java code."
        actions={
          evals.data ? (
            <Badge tone={EVAL_TONE[evals.data.status] ?? 'neutral'}>
              {EVAL_LABEL[evals.data.status] ?? evals.data.status} · {evals.data.passed}/{evals.data.totalChecks}
            </Badge>
          ) : null
        }
      >
        {evals.loading && <p className="empty">Loading eval status…</p>}
        {evals.error && <ErrorNotice error={evals.error} onRetry={evals.reload} />}
        {evals.data && (
          <>
            <DataTable
              columns={[
                { key: 'label', label: 'Layer', render: (row) => <strong>{row.label}</strong> },
                { key: 'language', label: 'Runs in' },
                {
                  key: 'status',
                  label: 'Status',
                  render: (row) => (
                    <Badge tone={EVAL_TONE[row.status] ?? 'neutral'}>{EVAL_LABEL[row.status] ?? row.status}</Badge>
                  ),
                },
                { key: 'passed', label: 'Passed', numeric: true },
                { key: 'failed', label: 'Failed', numeric: true },
                { key: 'total', label: 'Total', numeric: true },
                { key: 'message', label: 'Detail', render: (row) => <span className="muted">{row.message}</span> },
              ]}
              rows={evals.data.layers.map((row) => ({ ...row, __key: row.layer }))}
            />

            {evals.data.layers.some((layer) => layer.failed > 0) && (
              <>
                <h3 style={{ fontSize: 13, margin: '16px 0 6px' }}>Failing checks</h3>
                <DataTable
                  columns={[
                    { key: 'name', label: 'Check' },
                    { key: 'message', label: 'Message' },
                  ]}
                  rows={evals.data.layers
                    .flatMap((layer) => layer.checks ?? [])
                    .filter((check) => !check.passed)
                    .map((check, index) => ({ ...check, __key: `${check.name}-${index}` }))}
                />
              </>
            )}

            <p className="footnote">{evals.data.note}</p>
          </>
        )}
      </Panel>
    </div>
  )
}

function toneFor(value, invert = false) {
  if (value == null) return 'delta-flat'
  const amount = Number(value)
  if (amount === 0) return 'delta-flat'
  const positive = invert ? amount < 0 : amount > 0
  return positive ? 'delta-up' : 'delta-down'
}
