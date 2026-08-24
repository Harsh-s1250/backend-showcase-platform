// Mirrors backend/platform-backend ShowcaseController's response Map.
// Kept as a hand-written interface (not generated) since the backend endpoint
// returns a Map<String, Object>, not a typed record.
export interface ShowcaseInfo {
  id: string
  name: string
  githubRepoUrl: string
  branch: string
  status: string
  isRunning: boolean
  buildTool: string
  javaVersion: string
}

// Mirrors backend/platform-backend dto/ExperienceResponse.java exactly.
export type ProjectType = 'REST_APPLICATION' | 'CONSOLE_APPLICATION' | 'UNSUPPORTED' | 'UNKNOWN'
export type DetectionStatusValue = 'DETECTED' | 'INFERRED' | 'UNKNOWN'
export type InterfaceType = 'GENERATED_REST_UI' | 'API_EXPLORER' | 'BROWSER_TERMINAL' | 'NONE'

export interface ExperienceInfo {
  projectType: ProjectType | null
  projectTypeStatus: DetectionStatusValue | null
  projectTypeReason: string | null
  interfaceType: InterfaceType
  interfaceAvailable: boolean
  status: 'NOT_ANALYZED' | 'NOT_DEPLOYED' | 'READY' | 'DEPLOYED_NO_INTERFACE'
  deploymentStatus: string
  isRunning: boolean
}

// Mirrors backend/platform-backend service/UiSchema.java exactly.
export type FieldType = 'string' | 'integer' | 'number' | 'boolean'

export interface UiField {
  name: string
  type: FieldType
  required: boolean
  readOnly: boolean
}

export interface UiResource {
  name: string
  displayName: string
  basePath: string
  itemPathTemplate: string | null
  idField: string
  fields: UiField[]
  supportsCreate: boolean
  supportsUpdate: boolean
  supportsDelete: boolean
}

export interface UiSchemaResult {
  supported: boolean
  reason: string | null
  resources: UiResource[]
}
