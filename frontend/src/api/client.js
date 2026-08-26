/**
 * The only place this app talks to the network.
 *
 * Everything goes through the relative `/api` prefix, which the Vite dev server proxies to Spring
 * Boot on :8080. The frontend has no database access and no knowledge of the schema -- it renders
 * whatever the controllers hand it.
 */

async function request(path, options = {}) {
  const response = await fetch(`/api${path}`, {
    headers: { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
    ...options,
  })

  if (!response.ok) {
    // Surface the status, and the server's message when there is one worth reading.
    let detail = ''
    try {
      const body = await response.text()
      detail = body.slice(0, 300)
    } catch {
      /* response body already consumed or unreadable; the status is enough */
    }
    throw new Error(`${options.method ?? 'GET'} /api${path} failed: ${response.status}${detail ? ` -- ${detail}` : ''}`)
  }
  return response.json()
}

export const api = {
  revenueIntelligence: () => request('/revenue-intelligence'),
  capacityIntelligence: () => request('/capacity-intelligence'),
  dataSources: () => request('/data-sources'),
  evals: () => request('/evals'),

  executiveSummary: ({ winRateDelta = 0, dealSizeDelta = 0, targetDelta = 0 } = {}) => {
    const query = new URLSearchParams({
      winRateDelta: String(winRateDelta),
      dealSizeDelta: String(dealSizeDelta),
      targetDelta: String(targetDelta),
    })
    return request(`/executive/summary?${query}`)
  },

  whatIf: (assumptions) =>
    request('/what-if', { method: 'POST', body: JSON.stringify(assumptions) }),

  /**
   * Narrate one signal. Only the id is sent: the backend re-resolves the signal so the narration
   * is grounded in numbers it computed itself, not numbers this client posted.
   */
  explainSignal: (signalId) =>
    request('/revenue-intelligence/narrative/signal', {
      method: 'POST',
      body: JSON.stringify({ signalId }),
    }),
}
