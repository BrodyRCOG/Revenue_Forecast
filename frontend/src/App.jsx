import React, { useEffect, useState } from 'react'
import RevenueIntelligencePage from './pages/RevenueIntelligencePage.jsx'
import CapacityIntelligencePage from './pages/CapacityIntelligencePage.jsx'
import ExecutiveInsightsPage from './pages/ExecutiveInsightsPage.jsx'
import DataSourcesPage from './pages/DataSourcesPage.jsx'
import { Badge } from './components/primitives.jsx'

const TABS = [
  { id: 'revenue', label: 'Revenue Intelligence', Page: RevenueIntelligencePage },
  { id: 'capacity', label: 'Capacity Intelligence', Page: CapacityIntelligencePage },
  { id: 'executive', label: 'Executive Insights', Page: ExecutiveInsightsPage },
  { id: 'data', label: 'Data Sources', Page: DataSourcesPage },
]

const THEME_KEY = 'revintel.theme'

export default function App() {
  const [active, setActive] = useState('revenue')
  const [theme, setTheme] = useState(() => localStorage.getItem(THEME_KEY) ?? 'system')

  useEffect(() => {
    if (theme === 'system') {
      document.documentElement.removeAttribute('data-theme')
    } else {
      document.documentElement.setAttribute('data-theme', theme)
    }
    localStorage.setItem(THEME_KEY, theme)
  }, [theme])

  const { Page } = TABS.find((tab) => tab.id === active) ?? TABS[0]

  return (
    <div className="app">
      <header className="masthead">
        <div>
          <h1>Revenue Intelligence Engine</h1>
          <p>
            Finds infrastructure refresh, modernization and managed-services opportunities in a bank
            and credit-union client base <em>before</em> they enter the sales cycle. Every number on
            these pages is computed by deterministic code; the language model only turns those
            numbers into prose, and only after a groundedness check.
          </p>
        </div>
        <div className="masthead-meta">
          <Badge tone="medium">POC · synthetic data</Badge>
          <button
            type="button"
            className="button"
            onClick={() => setTheme(nextTheme(theme))}
            aria-label={`Theme: ${theme}. Click to change.`}
          >
            {{ system: 'Theme: system', light: 'Theme: light', dark: 'Theme: dark' }[theme]}
          </button>
        </div>
      </header>

      <nav className="tabs" role="tablist" aria-label="Sections">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            className="tab"
            aria-selected={active === tab.id}
            onClick={() => setActive(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </nav>

      <main role="tabpanel">
        <Page />
      </main>
    </div>
  )
}

function nextTheme(theme) {
  return { system: 'light', light: 'dark', dark: 'system' }[theme] ?? 'system'
}
