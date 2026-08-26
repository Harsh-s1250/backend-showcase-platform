import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  ApiError,
  analyzeProject,
  cloneProject,
  createProject,
  deleteProject,
  fetchAuthMe,
  fetchProjects,
  logout,
  restartProject,
  runProject,
  stopProject,
  streamBuildLog,
} from '../api/client'
import type { AuthMe, ProjectResponse } from '../api/types'
import { StatusLine } from '../components/StatusLine'
import { TechBadges } from '../components/TechBadges'
import './DashboardPage.css'

type AuthState = { phase: 'loading' } | { phase: 'anonymous' } | { phase: 'authenticated'; user: AuthMe }

type BuildStream = {
  projectId: string
  lines: string[]
  phase: 'streaming' | 'done' | 'error'
  errorMessage: string | null
}

export function DashboardPage() {
  const [auth, setAuth] = useState<AuthState>({ phase: 'loading' })
  const [projects, setProjects] = useState<ProjectResponse[] | null>(null)
  const [listError, setListError] = useState<string | null>(null)
  const [busy, setBusy] = useState<{ id: string; label: string } | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [confirmingDeleteId, setConfirmingDeleteId] = useState<string | null>(null)
  const [deleteInput, setDeleteInput] = useState('')
  const [build, setBuild] = useState<BuildStream | null>(null)
  const navigate = useNavigate()

  useEffect(() => {
    fetchAuthMe()
      .then((me) => setAuth(me.authenticated ? { phase: 'authenticated', user: me } : { phase: 'anonymous' }))
      .catch(() => setAuth({ phase: 'anonymous' }))
  }, [])

  const loadProjects = useCallback(() => {
    fetchProjects()
      .then((list) => {
        setProjects(list)
        setListError(null)
      })
      .catch((err: unknown) => {
        setListError(err instanceof ApiError ? err.message : 'Could not load your projects.')
      })
  }, [])

  useEffect(() => {
    if (auth.phase === 'authenticated') loadProjects()
  }, [auth.phase, loadProjects])

  async function handleLogout() {
    await logout().catch(() => undefined)
    setAuth({ phase: 'anonymous' })
    setProjects(null)
  }

  async function runAction(id: string, label: string, action: () => Promise<unknown>, after?: () => void) {
    setBusy({ id, label })
    setActionError(null)
    try {
      await action()
      loadProjects()
      after?.()
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : `${label} failed.`)
    } finally {
      setBusy(null)
    }
  }

  function handleClone(id: string) {
    runAction(id, 'Clone', () => cloneProject(id))
  }

  function handleAnalyze(id: string) {
    runAction(id, 'Analyze', () => analyzeProject(id))
  }

  function handleBuild(id: string) {
    setActionError(null)
    setBuild({ projectId: id, lines: [], phase: 'streaming', errorMessage: null })
    streamBuildLog(id, {
      onStatus: (message) => setBuild((b) => (b && b.projectId === id ? { ...b, lines: [...b.lines, message] } : b)),
      onLog: (line) => setBuild((b) => (b && b.projectId === id ? { ...b, lines: [...b.lines, line] } : b)),
      onComplete: () => {
        setBuild((b) => (b && b.projectId === id ? { ...b, phase: 'done' } : b))
        loadProjects()
      },
      onError: (message) => {
        setBuild((b) => (b && b.projectId === id ? { ...b, phase: 'error', errorMessage: message } : b))
        loadProjects()
      },
    })
  }

  function handleRun(id: string) {
    runAction(id, 'Run', () => runProject(id), () => navigate(`/showcase?projectId=${id}`))
  }

  function handleStop(id: string) {
    runAction(id, 'Stop', () => stopProject(id))
  }

  function handleRestart(id: string) {
    runAction(id, 'Restart', () => restartProject(id))
  }

  function handleDeleteConfirmed(id: string) {
    runAction(id, 'Delete', () => deleteProject(id, deleteInput), () => {
      setConfirmingDeleteId(null)
      setDeleteInput('')
    })
  }

  if (auth.phase === 'loading') {
    return (
      <Frame>
        <p className="showcase-empty">Checking session…</p>
      </Frame>
    )
  }

  if (auth.phase === 'anonymous') {
    return (
      <Frame>
        <header className="showcase-header">
          <span className="showcase-eyebrow">Owner Dashboard</span>
          <h1 className="showcase-name">Log in to manage your projects</h1>
        </header>
        <p className="dashboard-copy">
          Create, build, and run backends from a GitHub repo — you'll need to sign in with GitHub first.
        </p>
        <div className="showcase-actions">
          <a className="showcase-btn showcase-btn--primary" href="/auth/login">
            Log in with GitHub
          </a>
        </div>
        <a className="showcase-link showcase-link--muted" href="/">
          ← Back to showcase lookup
        </a>
      </Frame>
    )
  }

  return (
    <div className="dashboard-page">
      <div className="dashboard-shell">
        <DashboardHeader user={auth.user} onLogout={handleLogout} />

        <CreateProjectForm
          onCreated={() => loadProjects()}
        />

        {actionError && <p className="dashboard-error">{actionError}</p>}

        {listError && <p className="dashboard-error">{listError}</p>}

        {projects === null && !listError && <p className="showcase-empty">Loading your projects…</p>}

        {projects !== null && projects.length === 0 && (
          <p className="showcase-empty">No projects yet — create one above.</p>
        )}

        {projects !== null && projects.length > 0 && (
          <ul className="dashboard-list">
            {projects.map((project) => (
              <ProjectRow
                key={project.id}
                project={project}
                busy={busy?.id === project.id ? busy.label : null}
                onClone={() => handleClone(project.id)}
                onAnalyze={() => handleAnalyze(project.id)}
                onBuild={() => handleBuild(project.id)}
                onRun={() => handleRun(project.id)}
                onStop={() => handleStop(project.id)}
                onRestart={() => handleRestart(project.id)}
                isConfirmingDelete={confirmingDeleteId === project.id}
                deleteInput={deleteInput}
                onStartDelete={() => {
                  setConfirmingDeleteId(project.id)
                  setDeleteInput('')
                }}
                onCancelDelete={() => setConfirmingDeleteId(null)}
                onDeleteInputChange={setDeleteInput}
                onConfirmDelete={() => handleDeleteConfirmed(project.id)}
                buildStream={build?.projectId === project.id ? build : null}
                onCloseBuild={() => setBuild(null)}
              />
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function Frame({ children }: { children: React.ReactNode }) {
  return (
    <div className="showcase-page">
      <div className="showcase-card">{children}</div>
    </div>
  )
}

function DashboardHeader({ user, onLogout }: { user: AuthMe; onLogout: () => void }) {
  return (
    <header className="dashboard-header">
      <div className="dashboard-header__identity">
        {user.avatarUrl && <img className="dashboard-avatar" src={user.avatarUrl} alt="" />}
        <div>
          <span className="showcase-eyebrow">Owner Dashboard</span>
          <h1 className="dashboard-title">{user.githubUsername}</h1>
        </div>
      </div>
      <button className="showcase-btn showcase-btn--secondary" onClick={onLogout} type="button">
        Log out
      </button>
    </header>
  )
}

function CreateProjectForm({ onCreated }: { onCreated: () => void }) {
  const [name, setName] = useState('')
  const [githubRepoUrl, setGithubRepoUrl] = useState('')
  const [branch, setBranch] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await createProject({ name: name.trim(), githubRepoUrl: githubRepoUrl.trim(), branch: branch.trim() || undefined })
      setName('')
      setGithubRepoUrl('')
      setBranch('')
      onCreated()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create the project.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="dashboard-form" onSubmit={handleSubmit}>
      <span className="showcase-eyebrow">New project</span>
      <div className="dashboard-form__row">
        <input
          className="home-input"
          placeholder="name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          required
        />
        <input
          className="home-input"
          placeholder="https://github.com/owner/repo"
          value={githubRepoUrl}
          onChange={(e) => setGithubRepoUrl(e.target.value)}
          required
        />
        <input
          className="home-input dashboard-form__branch"
          placeholder="branch (main)"
          value={branch}
          onChange={(e) => setBranch(e.target.value)}
        />
        <button className="showcase-btn showcase-btn--primary" type="submit" disabled={submitting}>
          {submitting ? 'Creating…' : 'Create'}
        </button>
      </div>
      {error && <p className="dashboard-error">{error}</p>}
    </form>
  )
}

interface ProjectRowProps {
  project: ProjectResponse
  busy: string | null
  onClone: () => void
  onAnalyze: () => void
  onBuild: () => void
  onRun: () => void
  onStop: () => void
  onRestart: () => void
  isConfirmingDelete: boolean
  deleteInput: string
  onStartDelete: () => void
  onCancelDelete: () => void
  onDeleteInputChange: (value: string) => void
  onConfirmDelete: () => void
  buildStream: BuildStream | null
  onCloseBuild: () => void
}

function ProjectRow({
  project,
  busy,
  onClone,
  onAnalyze,
  onBuild,
  onRun,
  onStop,
  onRestart,
  isConfirmingDelete,
  deleteInput,
  onStartDelete,
  onCancelDelete,
  onDeleteInputChange,
  onConfirmDelete,
  buildStream,
  onCloseBuild,
}: ProjectRowProps) {
  const isRunning = project.status === 'RUNNING'
  const isBusy = busy !== null

  return (
    <li className="dashboard-row">
      <div className="dashboard-row__main">
        <div className="dashboard-row__info">
          <h2 className="dashboard-row__name">{project.name}</h2>
          <a className="showcase-link showcase-link--muted" href={project.githubRepoUrl} target="_blank" rel="noreferrer">
            {project.githubRepoUrl}
          </a>
          <TechBadges
            items={[project.projectType ?? '', project.branch].filter(Boolean)}
          />
        </div>
        <StatusLine status={project.status} isRunning={isRunning} />
      </div>

      <div className="showcase-actions">
        {!project.cloned && (
          <button className="showcase-btn showcase-btn--primary" onClick={onClone} disabled={isBusy}>
            {busy === 'Clone' ? 'Cloning…' : 'Clone'}
          </button>
        )}
        {project.cloned && (
          <button className="showcase-btn showcase-btn--secondary" onClick={onAnalyze} disabled={isBusy}>
            {busy === 'Analyze' ? 'Analyzing…' : project.projectType ? 'Re-analyze' : 'Analyze'}
          </button>
        )}
        {project.cloned && !project.built && (
          <button className="showcase-btn showcase-btn--primary" onClick={onBuild} disabled={isBusy}>
            Build
          </button>
        )}
        {project.built && !isRunning && (
          <button className="showcase-btn showcase-btn--primary" onClick={onRun} disabled={isBusy}>
            {busy === 'Run' ? 'Starting…' : 'Run'}
          </button>
        )}
        {isRunning && (
          <>
            <a
              className="showcase-btn showcase-btn--primary"
              href={`/showcase?projectId=${project.id}`}
            >
              Open Showcase
            </a>
            <button className="showcase-btn showcase-btn--secondary" onClick={onRestart} disabled={isBusy}>
              {busy === 'Restart' ? 'Restarting…' : 'Restart'}
            </button>
            <button className="showcase-btn showcase-btn--secondary" onClick={onStop} disabled={isBusy}>
              {busy === 'Stop' ? 'Stopping…' : 'Stop'}
            </button>
          </>
        )}
        {!isConfirmingDelete && (
          <button className="showcase-btn showcase-btn--danger" onClick={onStartDelete} disabled={isBusy}>
            Delete
          </button>
        )}
      </div>

      {isConfirmingDelete && (
        <div className="dashboard-delete-confirm">
          <p>
            Type <strong>{project.name}</strong> to confirm deletion. This removes the container, database, and
            workspace — it cannot be undone.
          </p>
          <div className="dashboard-form__row">
            <input
              className="home-input"
              value={deleteInput}
              onChange={(e) => onDeleteInputChange(e.target.value)}
              placeholder={project.name}
            />
            <button
              className="showcase-btn showcase-btn--danger"
              onClick={onConfirmDelete}
              disabled={deleteInput !== project.name || isBusy}
            >
              {busy === 'Delete' ? 'Deleting…' : 'Confirm delete'}
            </button>
            <button className="showcase-btn showcase-btn--secondary" onClick={onCancelDelete} disabled={isBusy}>
              Cancel
            </button>
          </div>
        </div>
      )}

      {buildStream && (
        <div className="dashboard-build-log">
          <div className="dashboard-build-log__header">
            <span className="showcase-eyebrow">
              {buildStream.phase === 'streaming' && 'Building…'}
              {buildStream.phase === 'done' && 'Build complete'}
              {buildStream.phase === 'error' && 'Build failed'}
            </span>
            {buildStream.phase !== 'streaming' && (
              <button className="showcase-btn showcase-btn--secondary" onClick={onCloseBuild}>
                Close
              </button>
            )}
          </div>
          <pre className="dashboard-build-log__body">
            {buildStream.lines.join('\n')}
            {buildStream.phase === 'error' && buildStream.errorMessage ? `\n${buildStream.errorMessage}` : ''}
          </pre>
        </div>
      )}
    </li>
  )
}
