import { useEffect, useRef, useState } from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import './BrowserTerminal.css'

interface BrowserTerminalProps {
  projectId: string
}

type ConnStatus = 'connecting' | 'connected' | 'busy' | 'exited' | 'disconnected' | 'error'

interface ServerMessage {
  type: 'output' | 'exit' | 'busy' | 'error' | 'info'
  data?: string
}

export function BrowserTerminal({ projectId }: BrowserTerminalProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const termRef = useRef<Terminal | null>(null)
  const wsRef = useRef<WebSocket | null>(null)
  const lineBufferRef = useRef('')
  const escapeRef = useRef(false)
  const acceptingInputRef = useRef(false)

  const [status, setStatus] = useState<ConnStatus>('connecting')
  const [restarting, setRestarting] = useState(false)
  // Bump this to force the connect-effect to re-run and open a fresh socket.
  const [connectionAttempt, setConnectionAttempt] = useState(0)

  // Create the xterm.js instance once.
  useEffect(() => {
    if (!containerRef.current) return
    const term = new Terminal({
      convertEol: false,
      fontFamily: 'IBM Plex Mono, ui-monospace, monospace',
      fontSize: 13,
      theme: { background: '#16202a', foreground: '#d7dee5' },
      cursorBlink: true,
    })
    const fitAddon = new FitAddon()
    term.loadAddon(fitAddon)
    term.open(containerRef.current)
    fitAddon.fit()
    term.writeln('Java Application Terminal')
    term.writeln('')
    termRef.current = term

    term.onData((data) => {
      if (!acceptingInputRef.current) return
      handleTypedData(data, term, lineBufferRef, escapeRef, (line) => {
        if (wsRef.current?.readyState === WebSocket.OPEN) {
          wsRef.current.send(JSON.stringify({ type: 'input', data: line }))
        }
      })
    })

    const resizeObserver = new ResizeObserver(() => fitAddon.fit())
    resizeObserver.observe(containerRef.current)

    return () => {
      resizeObserver.disconnect()
      term.dispose()
      termRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Open (and, on demand, re-open) the WebSocket connection.
  useEffect(() => {
    const term = termRef.current
    if (!term) return

    setStatus('connecting')
    acceptingInputRef.current = false

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const ws = new WebSocket(`${protocol}//${window.location.host}/api/projects/${projectId}/terminal`)
    wsRef.current = ws

    ws.onopen = () => {
      // Actual readiness is confirmed by the server's "info: Connected." message, not
      // just the socket opening — the server may still reject with busy/error right after.
    }

    ws.onmessage = (event) => {
      let msg: ServerMessage
      try {
        msg = JSON.parse(event.data)
      } catch {
        return
      }

      switch (msg.type) {
        case 'output':
          term.write((msg.data ?? '').replace(/\n/g, '\r\n'))
          break
        case 'info':
          if (msg.data === 'Connected.') {
            setStatus('connected')
            acceptingInputRef.current = true
          } else {
            term.write(`\r\n\x1b[2m[${msg.data}]\x1b[0m\r\n`)
          }
          break
        case 'busy':
          setStatus('busy')
          term.writeln(msg.data ?? 'This application is currently being used. Please try again later.')
          break
        case 'exit':
          setStatus('exited')
          acceptingInputRef.current = false
          term.write(`\r\n\x1b[33m[${msg.data ?? 'Application exited.'}]\x1b[0m\r\n`)
          break
        case 'error':
          setStatus('error')
          term.write(`\r\n\x1b[31m[${msg.data ?? 'Terminal error.'}]\x1b[0m\r\n`)
          break
      }
    }

    ws.onclose = () => {
      acceptingInputRef.current = false
      setStatus((current) => (current === 'exited' || current === 'busy' || current === 'error' ? current : 'disconnected'))
    }

    ws.onerror = () => {
      // onclose fires right after in every browser — no separate handling needed here.
    }

    return () => {
      ws.close()
    }
  }, [projectId, connectionAttempt])

  async function handleRestart() {
    setRestarting(true)
    try {
      const res = await fetch(`/api/projects/${projectId}/restart`, { method: 'POST', credentials: 'include' })
      if (!res.ok) {
        termRef.current?.writeln(
          res.status === 403
            ? '\r\n\x1b[31m[Only the project owner can restart this application.]\x1b[0m'
            : '\r\n\x1b[31m[Restart failed.]\x1b[0m',
        )
        return
      }
      termRef.current?.clear()
      termRef.current?.writeln('Java Application Terminal')
      termRef.current?.writeln('')
      setConnectionAttempt((n) => n + 1)
    } catch {
      termRef.current?.writeln('\r\n\x1b[31m[Restart failed.]\x1b[0m')
    } finally {
      setRestarting(false)
    }
  }

  return (
    <div className="terminal">
      <div className="terminal__toolbar">
        <span className={`terminal__status terminal__status--${status}`}>{statusLabel(status)}</span>
        <div className="terminal__actions">
          <button className="terminal__btn" onClick={() => termRef.current?.clear()}>
            Clear
          </button>
          {(status === 'disconnected' || status === 'error') && (
            <button className="terminal__btn" onClick={() => setConnectionAttempt((n) => n + 1)}>
              Reconnect
            </button>
          )}
          {status === 'exited' && (
            <button className="terminal__btn terminal__btn--primary" onClick={handleRestart} disabled={restarting}>
              {restarting ? 'Restarting…' : 'Restart & Reconnect'}
            </button>
          )}
        </div>
      </div>
      <div className="terminal__surface" ref={containerRef} />
    </div>
  )
}

function statusLabel(status: ConnStatus): string {
  switch (status) {
    case 'connecting': return 'Connecting…'
    case 'connected': return 'Connected'
    case 'busy': return 'In use by someone else'
    case 'exited': return 'Application exited'
    case 'disconnected': return 'Disconnected'
    case 'error': return 'Error'
  }
}

function handleTypedData(
  data: string,
  term: Terminal,
  lineBufferRef: React.MutableRefObject<string>,
  escapeRef: React.MutableRefObject<boolean>,
  onSubmitLine: (line: string) => void,
) {
  for (const ch of data) {
    const code = ch.charCodeAt(0)

    if (escapeRef.current) {
      // Mid-escape-sequence (arrow keys, Insert/Home/End, etc: ESC [ ... <final byte>).
      // Swallow every byte until the CSI final byte (0x40-0x7E) closes the sequence —
      // otherwise its trailing characters (e.g. the "2~" in Insert's "\x1b[2~") get typed
      // as if they were literal text, corrupting the line.
      if (code >= 0x40 && code <= 0x7e) escapeRef.current = false
      continue
    }
    if (ch === '\x1b') {
      escapeRef.current = true
      continue
    }

    if (ch === '\r' || ch === '\n') {
      term.write('\r\n')
      onSubmitLine(lineBufferRef.current + '\n')
      lineBufferRef.current = ''
    } else if (ch === '\x7f' || ch === '\b') {
      if (lineBufferRef.current.length > 0) {
        lineBufferRef.current = lineBufferRef.current.slice(0, -1)
        term.write('\b \b')
      }
    } else if (ch >= ' ') {
      // Printable characters only — other control characters (non-escape-sequence ones)
      // are simply dropped, which this line-based MVP terminal doesn't need to support.
      lineBufferRef.current += ch
      term.write(ch)
    }
  }
}
