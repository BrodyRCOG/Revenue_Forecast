import { useCallback, useEffect, useState } from 'react'

/**
 * Resolves the CSS palette variables into concrete values for Recharts.
 *
 * Recharts writes colours as SVG attributes, and a `var(--x)` reference is not reliably resolved
 * there, so the values are read out of the cascade once and re-read whenever the theme changes.
 * The stylesheet stays the single source of truth for the palette -- this hook only reads it.
 */

const ROLES = [
  'surface-1',
  'surface-raised',
  'text-primary',
  'text-secondary',
  'text-muted',
  'grid',
  'axis',
  'series-1',
  'series-2',
  'series-3',
  'series-4',
  'series-5',
  'series-6',
  'series-7',
  'series-8',
  'seq-100',
  'seq-200',
  'seq-300',
  'seq-400',
  'seq-500',
  'seq-600',
  'seq-700',
  'status-good',
  'status-warning',
  'status-serious',
  'status-critical',
]

function read() {
  if (typeof window === 'undefined') return {}
  const styles = getComputedStyle(document.documentElement)
  const palette = {}
  for (const role of ROLES) {
    palette[role] = styles.getPropertyValue(`--${role}`).trim()
  }
  return palette
}

export function usePalette() {
  const [palette, setPalette] = useState(read)
  const refresh = useCallback(() => setPalette(read()), [])

  useEffect(() => {
    // The viewer's toggle stamps data-theme on <html>; the OS setting comes through the media query.
    const observer = new MutationObserver(refresh)
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })

    const media = window.matchMedia('(prefers-color-scheme: dark)')
    media.addEventListener('change', refresh)

    return () => {
      observer.disconnect()
      media.removeEventListener('change', refresh)
    }
  }, [refresh])

  return palette
}

/** Fixed categorical order. Index into this -- never cycle it, never reassign by rank. */
export function seriesColor(palette, slot) {
  return palette[`series-${Math.min(8, slot + 1)}`]
}

/**
 * Sequential blue ramp for continuous magnitude, light to dark.
 *
 * @param share 0..1 position within the range being encoded
 */
export function sequentialStep(palette, share) {
  const steps = ['seq-100', 'seq-200', 'seq-300', 'seq-400', 'seq-500', 'seq-600', 'seq-700']
  if (!Number.isFinite(share) || share <= 0) return palette['seq-100']
  const index = Math.min(steps.length - 1, Math.floor(share * steps.length))
  return palette[steps[index]]
}

/** Reserved status colours. Severity and utilisation state only -- never a series. */
export function severityColor(palette, severity) {
  switch (severity) {
    case 'Critical':
      return palette['status-critical']
    case 'High':
      return palette['status-serious']
    case 'Medium':
      return palette['status-warning']
    case 'Low':
      return palette['status-good']
    default:
      return palette['text-muted']
  }
}

export function utilizationColor(palette, status) {
  switch (status) {
    case 'Over capacity':
      return palette['status-critical']
    case 'Stretched':
      return palette['status-serious']
    case 'Under-utilised':
      return palette['status-warning']
    case 'Healthy':
      return palette['status-good']
    default:
      return palette['text-muted']
  }
}
