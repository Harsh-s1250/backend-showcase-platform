import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import './HomePage.css'

const SUPPORTED_TYPES = [
  {
    title: 'Spring Boot REST APIs',
    body:
      "Simple CRUD APIs get an auto-generated table UI with Add / Edit / Delete. Endpoint shapes " +
      "that don\u2019t fit that pattern (query-param aggregation, async jobs, file downloads) fall back " +
      "to an interactive API Explorer instead.",
  },
  {
    title: 'Java console applications',
    body:
      "Get a live browser terminal wired to the real process \u2014 type into it and get real " +
      "next-prompts back, not canned output. Each session is isolated to one viewer at a time.",
  },
  {
    title: 'Everything else',
    body:
      "Still deploys. If we can\u2019t build a working interface for the project type, we say so " +
      "directly instead of hanging or guessing.",
  },
]

export function HomePage() {
  const [projectId, setProjectId] = useState('')
  const [showLookup, setShowLookup] = useState(false)
  const navigate = useNavigate()

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    const trimmed = projectId.trim()
    if (trimmed) navigate(`/showcase?projectId=${trimmed}`)
  }

  return (
    <div className="home-page">
      <div className="home-hero">
        <span className="showcase-eyebrow">Backend Showcase Platform</span>
        <h1 className="home-title">Deploy it. Share the link.</h1>
        <p className="home-copy">
          Connect a GitHub repo, we detect what kind of project it is, and give it a working page
          to share {'\u2014'} no config, no guessing.
        </p>

        <div className="home-cta-row">
          <a className="showcase-btn showcase-btn--primary" href="/dashboard">
            Go to dashboard {'\u2192'}
          </a>
          <button
            type="button"
            className="home-lookup-toggle"
            onClick={() => setShowLookup((v) => !v)}
            aria-expanded={showLookup}
          >
            Have a showcase link instead?
          </button>
        </div>

        {showLookup && (
          <form className="home-form" onSubmit={handleSubmit}>
            <input
              className="home-input"
              placeholder="project id"
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              aria-label="Project ID"
              autoFocus
            />
            <button className="showcase-btn showcase-btn--secondary" type="submit">
              Open showcase
            </button>
          </form>
        )}
      </div>

      <div className="home-support">
        <span className="showcase-eyebrow">What's supported</span>
        <div className="home-support-grid">
          {SUPPORTED_TYPES.map((item) => (
            <div className="home-support-card" key={item.title}>
              <h3 className="home-support-title">{item.title}</h3>
              <p className="home-support-body">{item.body}</p>
            </div>
          ))}
        </div>

        <p className="home-schema-note">
          <strong>Using a database?</strong> Add a <code>schema.sql</code> at your repo root (or
          in <code>db/</code>, <code>sql/</code>, <code>database/</code>) and it runs
          automatically the first time the project is provisioned {'\u2014'} it won{'\u2019'}t
          re-run retroactively if you add it after the fact.
        </p>
      </div>
    </div>
  )
}
