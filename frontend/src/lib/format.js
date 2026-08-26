/**
 * Formatting helpers.
 *
 * One rule runs through all of them: **null is not zero.** The backend returns null for a metric
 * that is genuinely undefined -- a coverage ratio against a target that is already met, a variance
 * with no actual to compare against -- and these render that as a dash. Coercing it to 0 would
 * turn "we cannot say" into "it is zero", which is the kind of quiet lie a dashboard should never
 * tell.
 */

export const DASH = '—'

const isMissing = (value) => value === null || value === undefined || Number.isNaN(Number(value))

export function usd(value, { compact = true } = {}) {
  if (isMissing(value)) return DASH
  const amount = Number(value)
  const sign = amount < 0 ? '-' : ''
  const magnitude = Math.abs(amount)

  if (!compact) {
    return `${sign}$${magnitude.toLocaleString('en-US', { maximumFractionDigits: 0 })}`
  }
  if (magnitude >= 1_000_000_000) return `${sign}$${(magnitude / 1_000_000_000).toFixed(2)}B`
  if (magnitude >= 1_000_000) return `${sign}$${(magnitude / 1_000_000).toFixed(1)}M`
  if (magnitude >= 1_000) return `${sign}$${Math.round(magnitude / 1_000)}k`
  return `${sign}$${magnitude.toFixed(0)}`
}

export function signedUsd(value) {
  if (isMissing(value)) return DASH
  const amount = Number(value)
  if (amount === 0) return usd(0)
  return `${amount > 0 ? '+' : ''}${usd(amount)}`
}

export function ratio(value, { digits = 2 } = {}) {
  if (isMissing(value)) return DASH
  return `${Number(value).toFixed(digits)}x`
}

export function percent(value, { digits = 0 } = {}) {
  if (isMissing(value)) return DASH
  return `${(Number(value) * 100).toFixed(digits)}%`
}

export function signedPercent(value, { digits = 0 } = {}) {
  if (isMissing(value)) return DASH
  const pct = Number(value) * 100
  return `${pct > 0 ? '+' : ''}${pct.toFixed(digits)}%`
}

export function count(value) {
  if (isMissing(value)) return DASH
  return Number(value).toLocaleString('en-US')
}

export function hours(value) {
  if (isMissing(value)) return DASH
  const amount = Number(value)
  if (Math.abs(amount) >= 10_000) return `${Math.round(amount / 1_000)}k h`
  return `${Math.round(amount).toLocaleString('en-US')} h`
}

/** Format by the unit tag the backend attaches to each KPI. */
export function byUnit(value, unit) {
  switch (unit) {
    case 'usd':
      return usd(value)
    case 'ratio':
      return ratio(value)
    case 'percent':
      return percent(value)
    case 'hours':
      return hours(value)
    case 'count':
      return count(value)
    default:
      return isMissing(value) ? DASH : String(value)
  }
}

/** Axis ticks: short, tabular, no decimals unless they earn their place. */
export function usdAxis(value) {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return ''
  if (amount === 0) return '0'
  const magnitude = Math.abs(amount)
  if (magnitude >= 1_000_000) return `$${(amount / 1_000_000).toFixed(magnitude >= 10_000_000 ? 0 : 1)}M`
  if (magnitude >= 1_000) return `$${Math.round(amount / 1_000)}k`
  return `$${amount}`
}

export function hoursAxis(value) {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return ''
  if (amount === 0) return '0'
  if (Math.abs(amount) >= 1_000) return `${Math.round(amount / 1_000)}k`
  return String(Math.round(amount))
}

export function percentAxis(value) {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return ''
  return `${Math.round(amount * 100)}%`
}

/** Truncate a long label for an axis without hiding that it was truncated. */
export function shortLabel(text, max = 18) {
  if (!text) return ''
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`
}
