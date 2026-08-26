import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import './HomePage.css'

export function HomePage() {
  const [projectId, setProjectId] = useState('')
  const navigate = useNavigate()

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    const trimmed = projectId.trim()
    if (trimmed) navigate(`/showcase?projectId=${trimmed}`)
  }

  return (
    <div className="home-page">
      <div className="home-card">
        <span className="showcase-eyebrow">Backend Showcase Platform</span>
        <h1 className="home-title">Deploy it. Share the link.</h1>
        <p className="home-copy">
          Paste a project ID to open its showcase page — this is the public entry point people
          land on.
        </p>
        <form className="home-form" onSubmit={handleSubmit}>
          <input
            className="home-input"
            placeholder="project id"
            value={projectId}
            onChange={(e) => setProjectId(e.target.value)}
            aria-label="Project ID"
          />
          <button className="showcase-btn showcase-btn--primary" type="submit">
            Open showcase
          </button>
        </form>
        <a className="showcase-link showcase-link--muted home-dashboard-link" href="/dashboard">
          Own a project? Go to the dashboard →
        </a>
      </div>
    </div>
  )
}
