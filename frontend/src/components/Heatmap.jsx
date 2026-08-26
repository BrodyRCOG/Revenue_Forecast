import React, { useMemo, useState } from 'react'
import { sequentialStep, usePalette } from '../lib/usePalette.js'
import { usd } from '../lib/format.js'

/**
 * Segment x opportunity-type grid.
 *
 * A heatmap encodes continuous magnitude, so it uses the single-hue sequential ramp light-to-dark
 * -- not the categorical palette, and never a rainbow. Cells carry their value as a visible label,
 * which is also what the light-mode contrast relief rule requires, so the colour is reinforcement
 * rather than the only channel.
 */
export function Heatmap({ cells, rowKey = 'segment', columnKey = 'opportunityType', valueKey = 'weightedAmountUsd' }) {
  const palette = usePalette()
  const [hovered, setHovered] = useState(null)

  const { rows, columns, lookup, max } = useMemo(() => {
    const rowNames = [...new Set(cells.map((cell) => cell[rowKey]))].sort()
    const columnNames = [...new Set(cells.map((cell) => cell[columnKey]))].sort()
    const table = new Map()
    let peak = 0
    for (const cell of cells) {
      const value = Number(cell[valueKey] ?? 0)
      table.set(`${cell[rowKey]}|${cell[columnKey]}`, cell)
      if (value > peak) peak = value
    }
    return { rows: rowNames, columns: columnNames, lookup: table, max: peak }
  }, [cells, rowKey, columnKey, valueKey])

  if (!cells.length) return <p className="empty">No forecast rows to plot.</p>

  return (
    <>
      <div className="table-wrap">
        <div
          className="heatmap"
          style={{ gridTemplateColumns: `minmax(150px, 1.2fr) repeat(${columns.length}, minmax(96px, 1fr))` }}
          role="table"
          aria-label="Weighted forecast by segment and opportunity type"
        >
          <div className="heatmap-corner" role="columnheader" />
          {columns.map((column) => (
            <div className="heatmap-head" key={column} role="columnheader">
              {column}
            </div>
          ))}

          {rows.map((row) => (
            <React.Fragment key={row}>
              <div className="heatmap-row-head" role="rowheader">
                {row}
              </div>
              {columns.map((column) => {
                const cell = lookup.get(`${row}|${column}`)
                const value = Number(cell?.[valueKey] ?? 0)
                const share = max > 0 ? value / max : 0
                const background = value > 0 ? sequentialStep(palette, share) : 'transparent'
                // Ink follows the fill's darkness, not the series colour.
                const ink = share > 0.55 ? '#ffffff' : 'var(--text-primary)'
                const key = `${row}|${column}`

                return (
                  <div
                    className="heatmap-cell"
                    key={key}
                    role="cell"
                    tabIndex={0}
                    onMouseEnter={() => setHovered(key)}
                    onMouseLeave={() => setHovered(null)}
                    onFocus={() => setHovered(key)}
                    onBlur={() => setHovered(null)}
                    title={
                      cell
                        ? `${row} · ${column}\nWeighted ${usd(value)} across ${cell.rowCount} row(s)` +
                          (Number(cell.whitespaceWeightedUsd) > 0
                            ? `\nof which whitespace ${usd(cell.whitespaceWeightedUsd)}`
                            : '')
                        : `${row} · ${column}: no forecast`
                    }
                    style={{
                      background,
                      color: value > 0 ? ink : 'var(--text-muted)',
                      border: value > 0 ? '1px solid transparent' : '1px dashed var(--grid)',
                      outline: hovered === key ? '2px solid var(--text-primary)' : 'none',
                      outlineOffset: '-2px',
                    }}
                  >
                    {value > 0 ? usd(value) : '—'}
                  </div>
                )
              })}
            </React.Fragment>
          ))}
        </div>
      </div>

      <div className="heatmap-scale">
        <span>Lower</span>
        <span className="heatmap-scale-ramp" aria-hidden="true">
          {[0.05, 0.2, 0.35, 0.5, 0.65, 0.8, 0.95].map((share) => (
            <span
              className="heatmap-scale-step"
              key={share}
              style={{ background: sequentialStep(palette, share) }}
            />
          ))}
        </span>
        <span>Higher weighted forecast (peak {usd(max)})</span>
      </div>
    </>
  )
}
