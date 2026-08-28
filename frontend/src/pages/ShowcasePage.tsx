import { useEffect, useState } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import { fetchExperience, fetchShowcase, fetchUiSchema } from '../api/client'
import type { ExperienceInfo, ShowcaseInfo, UiSchemaResult } from '../api/types'
import { StatusLine } from '../components/StatusLine'
import { TechBadges } from '../components/TechBadges'
import { GeneratedRestUi } from '../components/GeneratedRestUi'
import { BrowserTerminal } from '../components/BrowserTerminal'
import './ShowcasePage.css'

type LoadState =
  | { phase: 'loading' }
  | { phase: 'not-found' }
  | { phase: 'error'; message: string }
  | { phase: 'ready'; showcase: ShowcaseInfo; experience: ExperienceInfo }

const DETECTION_LABEL: Record<string, string> = {
  DETECTED: 'Detected',
  INFERRED: 'Inferred',
  UNKNOWN: 'Unknown',
}

export function ShowcasePage() {
  const [params] = useSearchParams()
  const projectId = params.get('projectId')
  const [state, setState] = useState<LoadState>({ phase: 'loading' })

  // Passed via navigate(..., { state }) from the dashboard's Run button — a one-time value from
  // that specific /run call, not part of this page's normal data-fetch. Dismissible since it's
  // only relevant right after landing here from a fresh Run.
  const location = useLocation()
  const [schemaWarning, setSchemaWarning] = useState<string | null>(
    (location.state as { schemaWarning?: string | null } | null)?.schemaWarning ?? null,
  )

  useEffect(() => {
    if (!projectId) return
    let cancelled = false

    Promise.all([fetchShowcase(projectId), fetchExperience(projectId)])
      .then(([showcase, experience]) => {
        if (!cancelled) setState({ phase: 'ready', showcase, experience })
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const status = (err as { status?: number }).status
        if (status === 404) {
          setState({ phase: 'not-found' })
        } else {
          setState({ phase: 'error', message: 'Could not load this project right now.' })
        }
      })

    return () => {
      cancelled = true
    }
    // Intentionally re-fetch whenever projectId changes; loading state is the
    // initial state and reset happens naturally on remount for a new id.
  }, [projectId])

  const [uiSchema, setUiSchema] = useState<UiSchemaResult | null>(null)

  useEffect(() => {
    if (state.phase !== 'ready') return
    // Only worth checking for a generated UI when the fallback (API Explorer) is what
    // we'd otherwise show for a running REST app — this is the live-schema check that
    // decides whether to upgrade past the guaranteed-safe fallback (PRD §11).
    if (state.experience.interfaceType !== 'API_EXPLORER' || !state.showcase.isRunning) {
      return
    }
    let cancelled = false
    fetchUiSchema(state.showcase.id)
      .then((result) => {
        if (!cancelled) setUiSchema(result)
      })
      .catch(() => {
        if (!cancelled) setUiSchema(null)
      })
    return () => {
      cancelled = true
    }
  }, [state])

  if (!projectId) {
    return (
      <Frame>
        <p className="showcase-empty">
          This page needs a project to show. Append <code>?projectId=&lt;id&gt;</code> to the URL.
        </p>
      </Frame>
    )
  }

  if (state.phase === 'loading') {
    return (
      <Frame>
        <p className="showcase-empty">Loading project…</p>
      </Frame>
    )
  }

  if (state.phase === 'not-found') {
    return (
      <Frame>
        <p className="showcase-empty">No project found for this link.</p>
      </Frame>
    )
  }

  if (state.phase === 'error') {
    return (
      <Frame>
        <p className="showcase-empty">{state.message}</p>
      </Frame>
    )
  }

  const { showcase, experience } = state

  return (
    <Frame>
      <header className="showcase-header">
        <span className="showcase-eyebrow">Project Manifest</span>
        <h1 className="showcase-name">{showcase.name}</h1>
        <TechBadges items={[showcase.buildTool, `Java ${showcase.javaVersion}`]} />
      </header>

      <StatusLine status={showcase.status} isRunning={showcase.isRunning} />

      {schemaWarning && (
        <div className="showcase-panel showcase-panel--muted" style={{ borderColor: '#c9a227' }}>
          <p style={{ margin: 0 }}>
            <strong>No schema found:</strong> {schemaWarning}
          </p>
          <button
            className="showcase-btn showcase-btn--secondary"
            style={{ marginTop: '0.5rem' }}
            onClick={() => setSchemaWarning(null)}
          >
            Dismiss
          </button>
        </div>
      )}

      <section className="showcase-body">
        <InterfaceSection showcase={showcase} experience={experience} uiSchema={uiSchema} />
      </section>

      <footer className="showcase-footer">
        <DetectionLine experience={experience} />
        <a className="showcase-link" href={showcase.githubRepoUrl} target="_blank" rel="noreferrer">
          View source on GitHub →
        </a>
      </footer>
    </Frame>
  )
}

function Frame({ children }: { children: React.ReactNode }) {
  return (
    <div className="showcase-page">
      <div className="showcase-card">{children}</div>
    </div>
  )
}

function DetectionLine({ experience }: { experience: ExperienceInfo }) {
  if (!experience.projectType) return null
  return (
    <p className="showcase-detection">
      <span className="showcase-detection__badge">
        {DETECTION_LABEL[experience.projectTypeStatus ?? 'UNKNOWN']}
      </span>
      {experience.projectTypeReason}
    </p>
  )
}

function InterfaceSection({
  showcase,
  experience,
  uiSchema,
}: {
  showcase: ShowcaseInfo
  experience: ExperienceInfo
  uiSchema: UiSchemaResult | null
}) {
  if (!showcase.isRunning) {
    return (
      <div className="showcase-panel showcase-panel--muted">
        <p>This project isn't running right now, so there's nothing to open yet.</p>
      </div>
    )
  }

  switch (experience.interfaceType) {
    case 'GENERATED_REST_UI':
      // Only reachable once the backend actually implements dynamic UI generation
      // (PRD Phase D/E) — a generated UI is meant to be opened directly, so both
      // actions make sense here.
      return (
        <div className="showcase-actions">
          <a className="showcase-btn showcase-btn--primary" href={`/p/${showcase.id}/`} target="_blank" rel="noreferrer">
            Open Application
          </a>
          <a
            className="showcase-btn showcase-btn--secondary"
            href={`/explorer.html?projectId=${showcase.id}`}
            target="_blank"
            rel="noreferrer"
          >
            API Explorer
          </a>
        </div>
      )

    case 'API_EXPLORER':
      // If the live OpenAPI schema turned out to have simple, CRUD-able resources,
      // render the generated table/form UI (PRD §10-12) instead of just linking out to
      // Swagger — but only ever as an upgrade on top of the guaranteed-safe fallback,
      // per PRD §11: "fall back to the API Explorer instead of generating an unreliable
      // interface." A null/unsupported result just means we stay on that fallback.
      if (uiSchema?.supported) {
        return (
          <div className="showcase-generated">
            <GeneratedRestUi projectId={showcase.id} schema={uiSchema} />
            <a
              className="showcase-link showcase-link--muted"
              href={`/explorer.html?projectId=${showcase.id}`}
              target="_blank"
              rel="noreferrer"
            >
              Prefer the raw API? Open API Explorer →
            </a>
          </div>
        )
      }
      return (
        <div className="showcase-actions">
          <a
            className="showcase-btn showcase-btn--primary"
            href={`/explorer.html?projectId=${showcase.id}`}
            target="_blank"
            rel="noreferrer"
          >
            API Explorer
          </a>
        </div>
      )

    case 'BROWSER_TERMINAL':
      if (!experience.interfaceAvailable) {
        // Unreachable in this build — ExperienceService only reports BROWSER_TERMINAL
        // with interfaceAvailable:false if the backend feature were ever pulled out from
        // under a still-detected console project. Kept as a safe, honest fallback rather
        // than assumed impossible.
        return (
          <div className="showcase-panel showcase-panel--terminal">
            <div className="showcase-panel__title-bar">
              <span />
              <span />
              <span />
            </div>
            <p className="showcase-panel__body">
              This project is a console application, but its terminal isn't available right now.
            </p>
          </div>
        )
      }
      return <BrowserTerminal projectId={showcase.id} />

    case 'NONE':
    default:
      return (
        <div className="showcase-panel showcase-panel--muted">
          <p className="showcase-panel__heading">Project deployed successfully.</p>
          <p>
            Automatic interactive interface: <strong>not available</strong> for this project type.
          </p>
          <p className="showcase-panel__note">Available: project information, logs, and the GitHub repository.</p>
        </div>
      )
  }
}
