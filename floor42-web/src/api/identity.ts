// Agent identity (IAM mode): the views of /api/iam. Kept apart from types.ts, which is at its size ceiling;
// contract.ts checks these types against openapi.json all the same.

export type IamHealth = 'OFF' | 'UP' | 'UNREACHABLE' | 'REFUSED_TO_START'

/** GET /api/iam/status. `code` names a refusal to start (IAM_NEEDS_SANDBOX, IAM_NEEDS_PROCESS_BINDING, IAM_VAULT_KEY_INVALID). */
export interface IamStatusView {
  enabled: boolean
  health: IamHealth
  code: string | null
  reason: string | null
  lastCheck: string | null
  failedChecks: number
  restartNeeded: boolean
  issuer: string
  maxTokenHours: number
  /** The switch as saved, which the next start runs; `enabled` is the running one. */
  savedEnabled: boolean
}

/** PUT /api/iam/secrets/{api|daemon}: one client secret, written to the vault only; never returned. */
export interface IamSecretRequest {
  secret: string
}

/** PUT /api/iam/settings. The two secrets are write-only: a blank one keeps the stored secret; none is ever returned. */
export interface IamSettingsRequest {
  enabled: boolean
  issuer: string
  maxTokenHours: number
  daemonSecret?: string
  apiSecret?: string
}

export interface IamSettingsAnswer {
  restartNeeded: boolean
}

export interface AgentIdentityView {
  agent: string
  clientId: string | null
  serviceAccountId: string | null
  enabled: boolean
  revoked: boolean
}

/** GET /api/iam/missions/{id}/delegation: metadata only, never a token. */
export interface DelegationView {
  mission: string
  grantedAt: string
  expiresAt: string
  resources: string[]
  revoked: boolean
}

/** POST /api/missions/{id}/quarantine: the session to quarantine, by its agent. */
export interface QuarantineRequest {
  agent: string
}

/** What a quarantine did, step by step; a second click answers all false. */
export interface QuarantineView {
  mission: string
  session: string
  revoked: boolean
  suspended: boolean
  stopped: boolean
}

/** GET /api/iam/missions/{id}/consent: what approving a plan delegates (the screen shows its own translation). */
export interface ConsentView {
  text: string
  expiresAt: string
  resources: string[]
}

/** 202 of POST /api/missions/{id}/approve in IAM mode: the path of the password step-up. */
export interface StepUp {
  redirect: string
}

/** GET /api/iam/tokens: a session's token as decoded header and claims plus 8 characters of the signature, never the token. */
export interface TokenView {
  agent: string
  session: string
  launch: string
  jti: string
  header: Record<string, unknown>
  claims: Record<string, unknown>
  signaturePrefix: string
  expiresAt: string | null
  /** REAL, or SIMULATED when the issuer is urn:floor42:simulated. */
  origin: string
}

/** GET /api/iam/refusals: the recent refusals of agent tokens (in memory, lost at a restart). */
export interface RefusalView {
  at: string
  code: string
  jti: string | null
  launch: string | null
}

export type TrustLinkKind = 'DIRECTOR_SIGN_IN' | 'DELEGATION' | 'AGENT_TOKEN' | 'PROCESS_BINDING' | 'BROKER_TOKEN' | 'REFUSAL'

/** One link of a session's trust chain; `ok` false (with its `code`, e.g. WRONG_PROCESS) is a refusal or a revocation. */
export interface Link {
  kind: TrustLinkKind
  label: string
  detail: string
  at: string | null
  ok: boolean
  code: string | null
}

/** GET /api/sessions/{launch}/trust-chain. */
export interface TrustChainView {
  links: Link[]
}

/** An external resource of the broker's catalogue (GET/PUT /api/iam/resources). */
export interface Resource {
  name: string
  baseUrl: string
  audience: string
  scopes: string[]
  methods: string[]
  pathPrefixes: string[]
  maxResponseBytes: number
  local: boolean
}

/** GET /api/iam/resources: the catalogue and the grants by agent id. */
export interface ResourceCatalogueView {
  resources: Resource[]
  grants: Record<string, string[]>
}

/** PUT /api/iam/agents/{id}/grants. */
export interface GrantsRequest {
  names: string[]
}

/** GET /api/iam/exchanges: one exchange with Keycloak, newest first. Secret values show as "•••", tokens never. */
export interface IdpExchangeView {
  at: string
  op: string
  method: string
  path: string
  /** 0 when no answer came. */
  status: number
  millis: number
  request: Record<string, string>
  answer: Record<string, string>
  error: string | null
}
