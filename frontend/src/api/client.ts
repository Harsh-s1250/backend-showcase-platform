import type {
  ShowcaseInfo,
  ExperienceInfo,
  UiSchemaResult,
  AuthMe,
  ProjectResponse,
  CreateProjectInput,
  RunResult,
} from './types'

class ApiError extends Error {
  status: number

  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

// Pulls a usable message out of whatever shape the backend returned. GlobalExceptionHandler
// returns {error, message}; Spring's default handler for unmapped exceptions (e.g. RunController's
// IllegalStateException) returns {status, error, message, path, timestamp}. Either way "message"
// is the field we want; fall back to "error", then to a generic string if the body isn't JSON.
async function extractErrorMessage(res: Response): Promise<string> {
  try {
    const body = await res.json()
    return body.message || body.error || `Request failed with status ${res.status}`
  } catch {
    return `Request failed with status ${res.status}`
  }
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) {
    throw new ApiError(await extractErrorMessage(res), res.status)
  }
  return res.json() as Promise<T>
}

async function sendJson<T>(url: string, method: 'POST' | 'DELETE', body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    throw new ApiError(await extractErrorMessage(res), res.status)
  }
  // Some endpoints (e.g. /run, /stop) always return a body; DELETE does too here. Guard anyway
  // in case a future no-content endpoint is added.
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export function fetchShowcase(projectId: string): Promise<ShowcaseInfo> {
  return getJson<ShowcaseInfo>(`/api/projects/${projectId}/showcase`)
}

export function fetchExperience(projectId: string): Promise<ExperienceInfo> {
  return getJson<ExperienceInfo>(`/api/projects/${projectId}/experience`)
}

export function fetchUiSchema(projectId: string): Promise<UiSchemaResult> {
  return getJson<UiSchemaResult>(`/api/projects/${projectId}/ui-schema`)
}

// --- Owner dashboard ---

export function fetchAuthMe(): Promise<AuthMe> {
  return getJson<AuthMe>('/auth/me')
}

export function logout(): Promise<AuthMe> {
  return sendJson<AuthMe>('/auth/logout', 'POST')
}

export function fetchProjects(): Promise<ProjectResponse[]> {
  return getJson<ProjectResponse[]>('/api/projects')
}

export function createProject(input: CreateProjectInput): Promise<ProjectResponse> {
  return sendJson<ProjectResponse>('/api/projects', 'POST', input)
}

export function cloneProject(id: string): Promise<ProjectResponse> {
  return sendJson<ProjectResponse>(`/api/projects/${id}/clone`, 'POST')
}

export function analyzeProject(id: string): Promise<unknown> {
  return sendJson(`/api/projects/${id}/analyze`, 'POST')
}

export function runProject(id: string): Promise<RunResult> {
  return sendJson<RunResult>(`/api/projects/${id}/run`, 'POST')
}

export function stopProject(id: string): Promise<{ status: string }> {
  return sendJson<{ status: string }>(`/api/projects/${id}/stop`, 'POST')
}

export function restartProject(id: string): Promise<{ healthy: boolean; url: string }> {
  return sendJson<{ healthy: boolean; url: string }>(`/api/projects/${id}/restart`, 'POST')
}

export function deleteProject(id: string, confirmProjectName: string): Promise<{ deleted: boolean }> {
  return sendJson<{ deleted: boolean }>(`/api/projects/${id}`, 'DELETE', { confirmProjectName })
}

// Streams the build SSE endpoint (GET /api/projects/{id}/build). Returns a cleanup function to
// close the connection. EventSource can't set custom headers, but it sends same-origin cookies
// automatically, which is all the session-based auth here needs.
export function streamBuildLog(
  projectId: string,
  handlers: {
    onStatus?: (message: string) => void
    onLog?: (line: string) => void
    onComplete?: (imageId: string) => void
    onError?: (message: string) => void
  },
): () => void {
  const source = new EventSource(`/api/projects/${projectId}/build`)

  source.addEventListener('status', (e) => handlers.onStatus?.((e as MessageEvent).data))
  source.addEventListener('log', (e) => handlers.onLog?.((e as MessageEvent).data))
  source.addEventListener('complete', (e) => {
    handlers.onComplete?.((e as MessageEvent).data)
    source.close()
  })
  source.addEventListener('error', (e) => {
    // SSE fires a plain (non-message) "error" event both for the server-sent "error" event
    // above AND for a dropped connection — MessageEvent.data is only present in the former case.
    const data = (e as MessageEvent).data
    handlers.onError?.(data ?? 'Build stream disconnected.')
    source.close()
  })

  return () => source.close()
}

export { ApiError }
