/**
 * Contract check between the pages and the API.
 *
 * Each page reads a specific set of field paths off its payload. This asserts every one of them
 * exists, is non-empty where the UI needs content, and carries a real number where the UI renders
 * one -- so "the tab renders real numbers from the generated dataset" is verified rather than
 * assumed. Run against the Vite dev server so the proxy is exercised too:
 *
 *   node scripts/check-api-contract.mjs [baseUrl]
 */

const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'

let failures = 0
let checks = 0

function check(label, condition, detail = '') {
  checks += 1
  if (condition) {
    console.log(`  PASS  ${label}${detail ? ` — ${detail}` : ''}`)
  } else {
    failures += 1
    console.log(`  FAIL  ${label}${detail ? ` — ${detail}` : ''}`)
  }
}

const isNumeric = (value) => value !== null && value !== undefined && Number.isFinite(Number(value))
const nonEmpty = (value) => Array.isArray(value) && value.length > 0

async function get(path, options) {
  const response = await fetch(`${BASE}/api${path}`, options)
  if (!response.ok) throw new Error(`${path} -> HTTP ${response.status}`)
  const text = await response.text()
  // A NaN or Infinity in the payload fails here, which is the point.
  return JSON.parse(text)
}

async function main() {
  console.log(`Contract check against ${BASE}\n`)

  // ------------------------------------------------------------------ Data Sources
  console.log('Data Sources tab')
  const dataSources = await get('/data-sources')
  check('datasetSource is generated', dataSources.datasetSource === 'generated', dataSources.datasetSource)
  check('totalRows is a real number', isNumeric(dataSources.totalRows) && dataSources.totalRows > 100, `${dataSources.totalRows} rows`)
  check('tables listed', dataSources.tables.length === 8, `${dataSources.tables.length} tables`)
  check('every table has a positive row count', dataSources.tables.every((t) => t.rowCount > 0))
  check('real anchors present with source URLs',
    dataSources.realAnchors.length === 7 && dataSources.realAnchors.every((a) => a.sourceUrl?.startsWith('http')),
    `${dataSources.realAnchors.length} anchors`)

  // ------------------------------------------------------- Revenue Intelligence
  console.log('\nRevenue Intelligence tab')
  const revenue = await get('/revenue-intelligence')
  check('kpis present with units the formatter knows', nonEmpty(revenue.kpis)
    && revenue.kpis.every((k) => ['usd', 'ratio', 'percent', 'count', 'hours'].includes(k.unit)),
    `${revenue.kpis.length} tiles`)
  check('at least one kpi carries a real value', revenue.kpis.some((k) => isNumeric(k.value)))
  check('trend has points on both sides of now',
    revenue.trend.some((p) => isNumeric(p.actualUsd)) && revenue.trend.some((p) => isNumeric(p.projectedUsd)),
    `${revenue.trend.length} quarters`)
  check('trend targets are all real numbers', revenue.trend.every((p) => isNumeric(p.targetUsd)))
  for (const dimension of ['byQuarter', 'bySegment', 'byPractice', 'byOpportunityType', 'topClients']) {
    check(`${dimension} populated`, nonEmpty(revenue[dimension])
      && revenue[dimension].every((b) => b.label && isNumeric(b.weightedAmountUsd)),
      `${revenue[dimension]?.length} buckets`)
  }
  check('heatmap cells populated', nonEmpty(revenue.heatmap)
    && revenue.heatmap.every((c) => c.segment && c.opportunityType && isNumeric(c.weightedAmountUsd)),
    `${revenue.heatmap.length} cells`)
  check('coverage has a computable forward quarter',
    revenue.coverage.some((r) => r.phase !== 'actual' && isNumeric(r.coverageRatio)))
  check('already-met quarters carry null, not a number',
    revenue.coverage.filter((r) => r.targetAlreadyMet).every((r) => r.coverageRatio === null))
  check('win rates populated', nonEmpty(revenue.winRates)
    && revenue.winRates.every((r) => isNumeric(r.smoothedWinRate)),
    `${revenue.winRates.length} cells`)
  check('signals populated with severity and title', nonEmpty(revenue.signals)
    && revenue.signals.every((s) => s.id && s.title && ['Critical', 'High', 'Medium', 'Low'].includes(s.severity)),
    `${revenue.signals.length} signals`)
  check('totals populated', isNumeric(revenue.totals.weightedPipelineUsd) && isNumeric(revenue.totals.grossPipelineUsd),
    `weighted ${revenue.totals.weightedPipelineUsd}`)

  // Signal narration round trip (the "Explain signal" button).
  const signal = revenue.signals[0]
  const narrative = await get('/revenue-intelligence/narrative/signal', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ signalId: signal.id }),
  })
  check('narration returns text and provenance',
    Boolean(narrative.text) && ['llm', 'template'].includes(narrative.source) && typeof narrative.grounded === 'boolean',
    `source=${narrative.source} grounded=${narrative.grounded}`)

  // ------------------------------------------------------ Capacity Intelligence
  console.log('\nCapacity Intelligence tab')
  const capacity = await get('/capacity-intelligence')
  check('kpis present', nonEmpty(capacity.kpis), `${capacity.kpis.length} tiles`)
  check('forecast quarters listed', nonEmpty(capacity.forecastQuarters), capacity.forecastQuarters.join(', '))
  check('skill demand populated', nonEmpty(capacity.skillDemand)
    && capacity.skillDemand.every((r) => r.resourceGroup && isNumeric(r.demandHours)),
    `${capacity.skillDemand.length} rows`)
  check('demand splits into pipeline and whitespace',
    capacity.skillDemand.every((r) => isNumeric(r.pipelineDemandHours) && isNumeric(r.whitespaceDemandHours)))
  check('capacity rows populated', nonEmpty(capacity.capacity)
    && capacity.capacity.every((r) => isNumeric(r.sustainableHours) && isNumeric(r.currentUtilization)),
    `${capacity.capacity.length} groups`)
  check('utilisation projection carries a known status', nonEmpty(capacity.utilizationProjection)
    && capacity.utilizationProjection.every((r) =>
      ['Under-utilised', 'Healthy', 'Stretched', 'Over capacity'].includes(r.status)),
    `${capacity.utilizationProjection.length} rows`)
  check('every skill-demand row has a matching capacity row',
    capacity.skillDemand.every((d) => capacity.capacity.some((c) => c.resourceGroup === d.resourceGroup)))
  check('hiring recommendations carry a rationale', nonEmpty(capacity.hiringRecommendations)
    && capacity.hiringRecommendations.every((r) => r.rationale && isNumeric(r.gapAsShareOfHeadcount)),
    `${capacity.hiringRecommendations.length} groups`)
  check('first-quarter demand exists for the default chart',
    capacity.skillDemand.some((r) => r.quarter === capacity.forecastQuarters[0]))

  // ------------------------------------------------------- Executive Insights
  console.log('\nExecutive Insights tab')
  const executive = await get('/executive/summary?winRateDelta=0&dealSizeDelta=0&targetDelta=0')
  check('kpis present', nonEmpty(executive.kpis), `${executive.kpis.length} tiles`)
  check('narrative present with provenance',
    Boolean(executive.narrative?.text) && ['llm', 'template'].includes(executive.narrative.source),
    `source=${executive.narrative?.source}`)
  check('trend populated', nonEmpty(executive.trend), `${executive.trend.length} quarters`)
  check('baseline scenario present',
    isNumeric(executive.baselineScenario?.baseline?.weightedPipelineUsd)
    && isNumeric(executive.baselineScenario?.scenario?.weightedPipelineUsd))
  check('coverage-by-quarter present in the scenario',
    nonEmpty(executive.baselineScenario.scenario.coverageByQuarter))
  check('capacity summary present', isNumeric(executive.capacity?.totalForecastDemandHours))

  const scenario = await get('/what-if', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ winRateDelta: 0.25, dealSizeDelta: 0, targetDelta: 0 }),
  })
  check('a win-rate override moves weighted pipeline',
    Number(scenario.scenario.weightedPipelineUsd) > Number(scenario.baseline.weightedPipelineUsd),
    `${scenario.baseline.weightedPipelineUsd} -> ${scenario.scenario.weightedPipelineUsd}`)
  check('the same override moves the coverage ratio',
    Number(scenario.scenario.blendedCoverageRatio) > Number(scenario.baseline.blendedCoverageRatio),
    `${scenario.baseline.blendedCoverageRatio} -> ${scenario.scenario.blendedCoverageRatio}`)
  check('the same override moves delivery demand',
    Number(scenario.scenario.forecastDemandHours) > Number(scenario.baseline.forecastDemandHours))
  check('interpretation is human readable', typeof scenario.interpretation === 'string'
    && scenario.interpretation.length > 20)

  // -------------------------------------------------------------- Model health
  console.log('\nModel health panel')
  const evals = await get('/evals')
  check('evals all clear', evals.status === 'all_clear' && evals.failed === 0,
    `${evals.passed}/${evals.totalChecks} passed`)
  check('all three layers reported', evals.layers.length === 3
    && ['data', 'pipeline', 'narrative'].every((layer) => evals.layers.some((l) => l.layer === layer)),
    evals.layers.map((l) => `${l.layer}:${l.total}`).join(' '))

  console.log(`\n${checks - failures}/${checks} contract checks passed`)
  if (failures > 0) process.exit(1)
}

main().catch((error) => {
  console.error(`\nContract check could not run: ${error.message}`)
  console.error('Is the backend on :8080 and the Vite dev server on :5173?')
  process.exit(2)
})
