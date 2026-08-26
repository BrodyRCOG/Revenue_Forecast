import React from 'react'
import { api } from '../api/client.js'
import { useApiData } from '../lib/useApiData.js'
import { Badge, DataTable, ErrorNotice, KpiRow, Loading, Panel } from '../components/primitives.jsx'
import { count } from '../lib/format.js'

/**
 * What data exists, how much of it, and -- the part that matters for a POC on synthetic data --
 * which of it is real.
 */
export default function DataSourcesPage() {
  const { data, error, loading, reload } = useApiData(api.dataSources, [])

  if (loading) return <Loading what="data catalog" />
  if (error) return <ErrorNotice error={error} onRetry={reload} />

  const kpis = [
    { key: 'rows', label: 'Total rows', value: data.totalRows, unit: 'count', caption: `Across ${data.tableCount} tables` },
    { key: 'tables', label: 'Tables', value: data.tableCount, unit: 'count', caption: 'One JPA entity each' },
    {
      key: 'oem',
      label: 'OEM models',
      value: data.oemModelCount,
      unit: 'count',
      caption: `${data.realAnchorCount} anchored to real vendor data`,
    },
    {
      key: 'anchors',
      label: 'Real lifecycle anchors',
      value: data.realAnchorCount,
      unit: 'count',
      caption: 'Each carries a vendor or aggregator source URL',
    },
  ]

  return (
    <div className="stack">
      {data.datasetSource === 'fallback' && (
        <div className="notice">
          <strong>Running on the fallback seed.</strong> The generated dataset was not found, so the
          backend loaded a handful of hand-written rows to stay usable. Run{' '}
          <code>python data-tools/build_dataset.py</code> and restart the backend for the full
          dataset.
        </div>
      )}

      <KpiRow kpis={kpis} />

      <Panel
        title="Tables"
        note="Every table is generated offline by the Python data tooling and loaded into an in-memory H2 database at startup. Nothing is written back at request time."
        actions={<Badge tone={data.datasetSource === 'generated' ? 'info' : 'medium'}>
          {data.datasetSource === 'generated' ? 'Generated dataset' : 'Fallback seed'}
        </Badge>}
      >
        <DataTable
          columns={[
            { key: 'label', label: 'Dataset', render: (row) => <strong>{row.label}</strong> },
            { key: 'tableName', label: 'Table', render: (row) => <code>{row.tableName}</code> },
            { key: 'dataType', label: 'Type' },
            { key: 'rowCount', label: 'Rows', numeric: true, render: (row) => count(row.rowCount) },
            { key: 'provenance', label: 'Provenance', render: (row) => <span className="muted">{row.provenance}</span> },
            { key: 'description', label: 'Contents', render: (row) => <span className="muted">{row.description}</span> },
          ]}
          rows={data.tables.map((row) => ({ ...row, __key: row.tableName }))}
        />
      </Panel>

      <Panel
        title="Real OEM lifecycle anchors"
        note={data.provenanceNote}
      >
        <DataTable
          columns={[
            { key: 'oem', label: 'OEM' },
            { key: 'modelName', label: 'Model', render: (row) => <strong>{row.modelName}</strong> },
            { key: 'category', label: 'Category' },
            { key: 'endOfSale', label: 'End of sale', numeric: true },
            { key: 'endOfSupport', label: 'End of support', numeric: true },
            {
              key: 'sourceConfidence',
              label: 'Confidence',
              render: (row) => (
                <Badge tone={row.sourceConfidence === 'vendor_official' ? 'low' : 'medium'}>
                  {row.sourceConfidence.replace('_', ' ')}
                </Badge>
              ),
            },
            {
              key: 'sourceUrl',
              label: 'Source',
              render: (row) =>
                row.sourceUrl ? (
                  <a href={row.sourceUrl} target="_blank" rel="noreferrer noopener">
                    vendor page
                  </a>
                ) : (
                  <span className="muted">—</span>
                ),
            },
          ]}
          rows={data.realAnchors.map((row) => ({ ...row, __key: row.id }))}
        />
        <p className="footnote">
          <strong>vendor official</strong> means the vendor publishes the date directly.{' '}
          <strong>aggregator</strong> means the published date varies by SKU, region or support
          contract and the value shown is a representative one. There is no production EOL/EOS feed
          behind this -- deliberately out of scope for the POC.
        </p>
      </Panel>

      <Panel title="How the data is produced" note={`As of ${data.asOfDate}.`}>
        <ol className="muted" style={{ margin: 0, paddingLeft: 20, fontSize: 12.5, lineHeight: 1.7 }}>
          <li>
            <code>data-tools/build_dataset.py</code> generates the full relational dataset in
            dependency order, seeded by <code>POC_SEED</code> so runs are reproducible.
          </li>
          <li>
            The data-layer evals check referential integrity, value domains, provenance labelling
            and cross-dataset plausibility.
          </li>
          <li>
            Only if every error-severity eval passes does it write{' '}
            <code>backend/src/main/resources/db/generated/data.sql</code> — a broken generator run
            cannot reach the backend.
          </li>
          <li>
            Spring Boot creates the schema from the JPA entities, then runs <code>data.sql</code>.
            H2 is in-memory, so each restart is a clean load and nothing persists between runs.
          </li>
        </ol>
      </Panel>
    </div>
  )
}
