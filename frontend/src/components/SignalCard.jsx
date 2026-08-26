import React, { useState } from 'react'
import { api } from '../api/client.js'
import { Badge, SeverityBadge } from './primitives.jsx'
import { usd } from '../lib/format.js'

const TYPE_LABEL = {
  lifecycle_risk: 'Lifecycle risk',
  contract_renewal_risk: 'Renewal risk',
  capacity_pressure: 'Capacity pressure',
}

/**
 * One detected signal, with the "Explain signal" round trip.
 *
 * The narrative response always reports whether it came from the model or from a template, and
 * whether it passed the groundedness check. Both are shown as a badge -- a reader should never have
 * to guess which they are looking at.
 */
export function SignalCard({ signal }) {
  const [narrative, setNarrative] = useState(null)
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState(null)

  const severityClass = `sev-${(signal.severity ?? '').toLowerCase()}`

  async function explain() {
    setPending(true)
    setFailure(null)
    try {
      setNarrative(await api.explainSignal(signal.id))
    } catch (cause) {
      setFailure(cause)
    } finally {
      setPending(false)
    }
  }

  return (
    <article className={`signal ${severityClass}`}>
      <div className="signal-head">
        <h3 className="signal-title">{signal.title}</h3>
        <SeverityBadge severity={signal.severity} />
      </div>

      <p className="signal-detail">{signal.detail}</p>

      <div className="signal-facts">
        <span>
          <Badge tone="neutral">{TYPE_LABEL[signal.type] ?? signal.type}</Badge>
        </span>
        {signal.estimatedValueUsd != null && (
          <span>
            Estimated value <b>{usd(signal.estimatedValueUsd)}</b>
          </span>
        )}
        {signal.recommendedQuarter && (
          <span>
            Target quarter <b>{signal.recommendedQuarter}</b>
          </span>
        )}
        {signal.segment && <span className="muted">{signal.segment}</span>}
      </div>

      <button type="button" className="button" onClick={explain} disabled={pending}>
        {pending ? 'Explaining…' : narrative ? 'Re-explain signal' : 'Explain signal'}
      </button>

      {failure && (
        <p className="narrative-reason" style={{ color: 'var(--status-critical)' }}>
          Narration request failed: {String(failure.message ?? failure)}
        </p>
      )}

      {narrative && (
        <div className="narrative">
          <div className="narrative-head">
            <Badge tone={narrative.source === 'llm' ? 'info' : 'neutral'}>
              {narrative.source === 'llm' ? 'LLM' : 'Template'}
            </Badge>
            <Badge tone={narrative.grounded ? 'low' : 'medium'}>
              {narrative.grounded ? 'Grounded' : 'Not model-generated'}
            </Badge>
            {narrative.rejectedNumbers?.length > 0 && (
              <Badge tone="critical">{narrative.rejectedNumbers.length} number(s) rejected</Badge>
            )}
          </div>
          <p className="narrative-body">{narrative.text}</p>
          <p className="narrative-reason">{narrative.reason}</p>
          {narrative.rejectedNumbers?.length > 0 && (
            <p className="narrative-reason">
              Discarded because these figures were not in the computed data:{' '}
              <code>{narrative.rejectedNumbers.join(', ')}</code>
            </p>
          )}
        </div>
      )}
    </article>
  )
}
