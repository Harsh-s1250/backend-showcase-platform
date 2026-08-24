import type { ShowcaseInfo, ExperienceInfo, UiSchemaResult } from './types'

class ApiError extends Error {
  status: number

  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) {
    throw new ApiError(`Request to ${url} failed with status ${res.status}`, res.status)
  }
  return res.json() as Promise<T>
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

export { ApiError }
