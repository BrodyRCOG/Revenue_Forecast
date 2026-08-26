import React from 'react'
import { DASH } from '../lib/format.js'

/**
 * Shared Recharts tooltip.
 *
 * Every chart in this app ships one -- an HTML chart is interactive, and reading an exact value off
 * a bar height is not a thing anyone should have to do. Series whose value is `null` are dropped
 * rather than rendered as zero.
 *
 * @param formatter (value, entry) => string
 */
export function ChartTooltip({ active, payload, label, formatter, titleSuffix }) {
  if (!active || !payload?.length) return null

  const rows = payload.filter((entry) => entry.value !== null && entry.value !== undefined)
  if (!rows.length) return null

  return (
    <div className="tooltip" role="tooltip">
      <div className="tooltip-title">
        {label}
        {titleSuffix ? <span className="muted"> · {titleSuffix(payload[0]?.payload)}</span> : null}
      </div>
      {rows.map((entry) => (
        <div className="tooltip-row" key={`${entry.dataKey}-${entry.name}`}>
          <span>
            <span
              className="legend-swatch"
              style={{ background: entry.color, display: 'inline-block', marginRight: 6 }}
              aria-hidden="true"
            />
            {entry.name}
          </span>
          <strong>{formatter ? formatter(entry.value, entry) : (entry.value ?? DASH)}</strong>
        </div>
      ))}
    </div>
  )
}

/** Axis and grid props shared by every chart, so chrome stays recessive and consistent. */
export function chartChrome(palette) {
  return {
    grid: { stroke: palette.grid, strokeDasharray: '0', vertical: false },
    axis: {
      stroke: palette.axis,
      tick: { fill: palette['text-muted'], fontSize: 11 },
      tickLine: false,
    },
    cursor: { fill: palette.grid, fillOpacity: 0.35 },
    lineCursor: { stroke: palette.axis, strokeWidth: 1 },
  }
}
