import React from 'react'
import { byUnit, DASH } from '../lib/format.js'

/** A titled card. `note` is where the "what am I looking at" sentence goes. */
export function Panel({ title, note, actions, children }) {
  return (
    <section className="panel">
      {(title || actions) && (
        <div className="panel-head">
          {title && <h2>{title}</h2>}
          {actions}
        </div>
      )}
      {note && <p className="panel-note">{note}</p>}
      {children}
    </section>
  )
}

/**
 * A status pill. The dot carries the colour, the text carries the meaning -- a status colour never
 * appears without its label.
 */
export function Badge({ tone = 'neutral', children }) {
  return (
    <span className={`badge badge-${tone}`}>
      <span className="badge-dot" aria-hidden="true" />
      {children}
    </span>
  )
}

export function SeverityBadge({ severity }) {
  const tone = { Critical: 'critical', High: 'high', Medium: 'medium', Low: 'low' }[severity] ?? 'neutral'
  return <Badge tone={tone}>{severity}</Badge>
}

export function KpiTile({ label, value, unit, caption }) {
  const rendered = byUnit(value, unit)
  return (
    <div className="kpi">
      <div className="kpi-label">{label}</div>
      <div className={`kpi-value${rendered === DASH ? ' undefined-value' : ''}`}>{rendered}</div>
      {caption && <div className="kpi-caption">{caption}</div>}
    </div>
  )
}

export function KpiRow({ kpis }) {
  return (
    <div className="kpi-row">
      {kpis.map((kpi) => (
        <KpiTile key={kpi.key} label={kpi.label} value={kpi.value} unit={kpi.unit} caption={kpi.caption} />
      ))}
    </div>
  )
}

/**
 * Legend. Always rendered for two or more series, so identity is never carried by colour alone.
 * `variant` picks the swatch shape so a dashed line in the chart reads as dashed here too.
 */
export function Legend({ items }) {
  return (
    <ul className="legend">
      {items.map((item) => (
        <li key={item.label}>
          <span
            className={`legend-swatch${item.variant ? ` ${item.variant}` : ''}`}
            style={item.variant === 'dashed' ? { color: item.color } : { background: item.color }}
            aria-hidden="true"
          />
          {item.label}
        </li>
      ))}
    </ul>
  )
}

/** Chip group used for the single row of filters above a chart. */
export function ChipGroup({ label, options, value, onChange }) {
  return (
    <div className="control-row">
      {label && <span className="control-label">{label}</span>}
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          className="chip"
          aria-pressed={value === option.value}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

/**
 * A plain table. Present on every page: it is the table view the colour-contrast relief rule
 * requires, and it is how anyone checks a chart's exact numbers.
 */
export function DataTable({ columns, rows, empty = 'Nothing to show.', maxRows }) {
  const visible = maxRows ? rows.slice(0, maxRows) : rows
  if (!rows.length) return <p className="empty">{empty}</p>

  return (
    <>
      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column.key} className={column.numeric ? 'num' : undefined} scope="col">
                  {column.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {visible.map((row, index) => (
              <tr key={row.__key ?? index}>
                {columns.map((column) => (
                  <td key={column.key} className={column.numeric ? 'num' : undefined}>
                    {column.render ? column.render(row) : (row[column.key] ?? DASH)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {maxRows && rows.length > maxRows && (
        <p className="footnote">
          Showing {maxRows} of {rows.length} rows.
        </p>
      )}
    </>
  )
}

export function Loading({ what = 'data' }) {
  return <p className="loading">Loading {what}…</p>
}

export function ErrorNotice({ error, onRetry }) {
  return (
    <div className="notice error">
      <strong>Could not reach the backend.</strong> {String(error?.message ?? error)}
      <div style={{ marginTop: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
        {onRetry && (
          <button type="button" className="button" onClick={onRetry}>
            Retry
          </button>
        )}
        <span className="muted">
          Start it with <code>mvn spring-boot:run</code> in <code>backend/</code>.
        </span>
      </div>
    </div>
  )
}
