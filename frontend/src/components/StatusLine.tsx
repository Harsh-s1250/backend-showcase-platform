import './StatusLine.css'

interface StatusLineProps {
  status: string
  isRunning: boolean
}

const TONE_BY_STATUS: Record<string, 'live' | 'stopped' | 'warn'> = {
  RUNNING: 'live',
  BUILT: 'stopped',
  CLONED: 'stopped',
  CREATED: 'stopped',
  STOPPED: 'stopped',
  CLONING: 'warn',
  BUILDING: 'warn',
  CLONE_FAILED: 'warn',
  RUN_UNHEALTHY: 'warn',
}

export function StatusLine({ status, isRunning }: StatusLineProps) {
  const tone = isRunning ? 'live' : TONE_BY_STATUS[status] ?? 'stopped'

  return (
    <div className={`status-line status-line--${tone}`}>
      <span className="status-line__prompt">$ deployment status</span>
      <span className="status-line__value">
        <span className="status-line__dot" aria-hidden="true" />
        {status}
      </span>
    </div>
  )
}
