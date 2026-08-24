import { useEffect, useState } from 'react'
import type { UiResource, UiSchemaResult } from '../api/types'
import { buildItemPath, createRow, deleteRow, listRows, updateRow } from '../api/proxyClient'
import './GeneratedRestUi.css'

interface GeneratedRestUiProps {
  projectId: string
  schema: UiSchemaResult
}

export function GeneratedRestUi({ projectId, schema }: GeneratedRestUiProps) {
  const [activeIndex, setActiveIndex] = useState(0)
  const resource = schema.resources[activeIndex]

  if (!resource) return null

  return (
    <div className="generated-ui">
      {schema.resources.length > 1 && (
        <div className="generated-ui__tabs" role="tablist">
          {schema.resources.map((r, i) => (
            <button
              key={r.name}
              role="tab"
              aria-selected={i === activeIndex}
              className={`generated-ui__tab ${i === activeIndex ? 'generated-ui__tab--active' : ''}`}
              onClick={() => setActiveIndex(i)}
            >
              {r.displayName}
            </button>
          ))}
        </div>
      )}
      <ResourcePanel key={resource.name} projectId={projectId} resource={resource} />
    </div>
  )
}

function ResourcePanel({ projectId, resource }: { projectId: string; resource: UiResource }) {
  const [rows, setRows] = useState<Record<string, unknown>[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [editingRow, setEditingRow] = useState<Record<string, unknown> | null>(null)

  function refresh() {
    listRows(projectId, resource.basePath)
      .then((data) => {
        setRows(data)
        setError(null)
      })
      .catch(() => setError(`Couldn't load ${resource.displayName.toLowerCase()}.`))
  }

  useEffect(() => {
    refresh()
    // resource is a fresh object per parent render, but only projectId/resource.basePath
    // identity actually needs to trigger a re-fetch here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, resource.basePath, resource.displayName])

  function openCreate() {
    setEditingRow(null)
    setFormOpen(true)
  }

  function openEdit(row: Record<string, unknown>) {
    setEditingRow(row)
    setFormOpen(true)
  }

  async function handleDelete(row: Record<string, unknown>) {
    if (!resource.itemPathTemplate) return
    if (!confirm(`Delete this ${resource.displayName.toLowerCase().replace(/s$/, '')}?`)) return
    try {
      await deleteRow(projectId, buildItemPath(resource.itemPathTemplate, row[resource.idField]))
      refresh()
    } catch {
      setError('Delete failed.')
    }
  }

  async function handleSubmit(values: Record<string, unknown>) {
    try {
      if (editingRow && resource.itemPathTemplate) {
        await updateRow(projectId, buildItemPath(resource.itemPathTemplate, editingRow[resource.idField]), values)
      } else {
        await createRow(projectId, resource.basePath, values)
      }
      setFormOpen(false)
      refresh()
    } catch {
      setError(editingRow ? 'Update failed.' : 'Create failed.')
    }
  }

  return (
    <div className="generated-ui__panel">
      <div className="generated-ui__toolbar">
        <button className="showcase-btn showcase-btn--secondary" onClick={refresh}>
          Refresh {resource.displayName}
        </button>
        {resource.supportsCreate && (
          <button className="showcase-btn showcase-btn--primary" onClick={openCreate}>
            + Add {resource.displayName.replace(/s$/, '')}
          </button>
        )}
      </div>

      {error && <p className="generated-ui__error">{error}</p>}

      {rows === null && !error && <p className="generated-ui__empty">Loading…</p>}
      {rows && rows.length === 0 && <p className="generated-ui__empty">No {resource.displayName.toLowerCase()} yet.</p>}

      {rows && rows.length > 0 && (
        <table className="generated-ui__table">
          <thead>
            <tr>
              {resource.fields.map((f) => (
                <th key={f.name}>{f.name}</th>
              ))}
              {(resource.supportsUpdate || resource.supportsDelete) && <th />}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={String(row[resource.idField] ?? i)}>
                {resource.fields.map((f) => (
                  <td key={f.name}>{formatCell(row[f.name])}</td>
                ))}
                {(resource.supportsUpdate || resource.supportsDelete) && (
                  <td className="generated-ui__row-actions">
                    {resource.supportsUpdate && (
                      <button className="generated-ui__link-btn" onClick={() => openEdit(row)}>
                        Edit
                      </button>
                    )}
                    {resource.supportsDelete && (
                      <button className="generated-ui__link-btn generated-ui__link-btn--danger" onClick={() => handleDelete(row)}>
                        Delete
                      </button>
                    )}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {formOpen && (
        <ResourceForm
          resource={resource}
          initialValues={editingRow}
          onCancel={() => setFormOpen(false)}
          onSubmit={handleSubmit}
        />
      )}
    </div>
  )
}

function formatCell(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  return String(value)
}

function ResourceForm({
  resource,
  initialValues,
  onCancel,
  onSubmit,
}: {
  resource: UiResource
  initialValues: Record<string, unknown> | null
  onCancel: () => void
  onSubmit: (values: Record<string, unknown>) => void
}) {
  const editableFields = resource.fields.filter((f) => !f.readOnly)
  const [values, setValues] = useState<Record<string, unknown>>(() => {
    const initial: Record<string, unknown> = {}
    for (const f of editableFields) {
      initial[f.name] = initialValues ? initialValues[f.name] : f.type === 'boolean' ? false : ''
    }
    return initial
  })

  function setField(name: string, value: unknown) {
    setValues((prev) => ({ ...prev, [name]: value }))
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    onSubmit(values)
  }

  return (
    <form className="generated-ui__form" onSubmit={handleSubmit}>
      <h3 className="generated-ui__form-title">
        {initialValues ? `Edit ${resource.displayName.replace(/s$/, '')}` : `New ${resource.displayName.replace(/s$/, '')}`}
      </h3>
      {editableFields.map((f) => (
        <label key={f.name} className="generated-ui__field">
          <span>
            {f.name}
            {f.required ? ' *' : ''}
          </span>
          {f.type === 'boolean' ? (
            <input
              type="checkbox"
              checked={Boolean(values[f.name])}
              onChange={(e) => setField(f.name, e.target.checked)}
            />
          ) : f.type === 'integer' || f.type === 'number' ? (
            <input
              type="number"
              required={f.required}
              value={values[f.name] as number | string}
              onChange={(e) => setField(f.name, e.target.valueAsNumber)}
            />
          ) : (
            <input
              type="text"
              required={f.required}
              value={values[f.name] as string}
              onChange={(e) => setField(f.name, e.target.value)}
            />
          )}
        </label>
      ))}
      <div className="generated-ui__form-actions">
        <button type="button" className="showcase-btn showcase-btn--secondary" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="showcase-btn showcase-btn--primary">
          Save
        </button>
      </div>
    </form>
  )
}
