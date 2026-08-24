// Talks to a project's own API through the platform's reverse proxy (/p/{projectId}/...),
// not the platform's management API. This is what the generated CRUD UI actually reads
// and writes — the same path a real client of the deployed app would use.

function proxyUrl(projectId: string, path: string): string {
  return `/p/${projectId}${path}`
}

async function parseJsonSafe(res: Response): Promise<unknown> {
  const text = await res.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

export async function listRows(projectId: string, basePath: string): Promise<Record<string, unknown>[]> {
  const res = await fetch(proxyUrl(projectId, basePath))
  if (!res.ok) throw new Error(`GET ${basePath} failed with ${res.status}`)
  const body = await parseJsonSafe(res)
  return Array.isArray(body) ? (body as Record<string, unknown>[]) : []
}

export async function createRow(
  projectId: string,
  basePath: string,
  values: Record<string, unknown>,
): Promise<void> {
  const res = await fetch(proxyUrl(projectId, basePath), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(values),
  })
  if (!res.ok) throw new Error(`POST ${basePath} failed with ${res.status}`)
}

export async function updateRow(
  projectId: string,
  itemPath: string,
  values: Record<string, unknown>,
): Promise<void> {
  const res = await fetch(proxyUrl(projectId, itemPath), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(values),
  })
  if (!res.ok) throw new Error(`PUT ${itemPath} failed with ${res.status}`)
}

export async function deleteRow(projectId: string, itemPath: string): Promise<void> {
  const res = await fetch(proxyUrl(projectId, itemPath), { method: 'DELETE' })
  if (!res.ok) throw new Error(`DELETE ${itemPath} failed with ${res.status}`)
}

export function buildItemPath(itemPathTemplate: string, id: unknown): string {
  return itemPathTemplate.replace(/\{[^}]+}/, encodeURIComponent(String(id)))
}
