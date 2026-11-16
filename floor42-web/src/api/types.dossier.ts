// The mission proof dossier (ADR-0071). Checked against openapi.json by contract.ts.
export type DossierSource = 'JOURNAL' | 'GIT' | 'STATE' | 'DERIVED'

export interface DossierEventRef {
  seq: number
  eventId: string
  type: string
}

export interface DossierRef {
  source: DossierSource
  basis?: string | null
  origin?: string | null
  events: DossierEventRef[]
}

export interface DossierSection<T> {
  count: number
  items: T[]
}

export interface DossierMission {
  id: string
  title: string
  objective: string
  origin: string
  status: string
  outcome?: string | null
  events: number
  real: number
  simulated: number
}

export interface DossierIdentity {
  agent: string
  client: string
  onBehalfOf: string
  tokenAudience: string
  session: string
  ticket?: string | null
  issuer: string
  expiresAt: string
  jti: string
  /** The claims the journal recorded (ADR-0074); absent for a session older than v1.2. `subject` is shortened. */
  subject?: string | null
  authorizedParty?: string | null
  actor?: string | null
  scopes?: string[] | null
  derived: string[]
  ref: DossierRef
}

export interface DossierDelegation {
  resources: string[]
  grantedAt?: string | null
  expiresAt?: string | null
  revoked: boolean
  ref: DossierRef
}

export interface DossierBroker {
  agent: string
  resource: string
  method: string
  path: string
  status: string
  exchangedJti: string
  audience: string
  ref: DossierRef
}

export interface DossierRefusal {
  at: string
  agent: string
  ticket?: string | null
  tool: string
  category?: string | null
  summary?: string | null
  reason: string
  detail?: string | null
  identity: boolean
  ref: DossierRef
}

export interface DossierApproval {
  at: string
  kind: string
  actor: string
  text?: string | null
  ref: DossierRef
}

export interface DossierBasisCount {
  basis: string
  count: number
}

export interface DossierSessionCost {
  agent?: string | null
  session: string
  usd?: number | null
  basis?: string | null
  ref: DossierRef
}

export interface DossierCosts {
  budgetUsd?: number | null
  budgetRef?: DossierRef | null
  sessions: DossierSessionCost[]
  total?: { usd: number; basis: string } | null
  bound?: { usd: number; sessions: number; basis: string } | null
}

export interface DossierCheck {
  at: string
  workspace: string
  command: string
  exitCode?: number | null
  timedOut: boolean
  durationMs: number
  trigger?: string | null
  head?: string | null
  kind: 'CHECK' | 'PROOF'
  ref: DossierRef
}

/** What git said of an accepted commit's signature when the dossier was built (ADR-0077). */
export interface DossierSignature {
  status: 'VERIFIED' | 'NOT_SIGNED' | 'BAD_SIGNATURE' | 'UNKNOWN_KEY' | 'UNCHECKABLE'
  /** The agent whose key signed, when VERIFIED. */
  agent?: string | null
  /** The agent's key was retired since (revoked or dismissed); the signature still verifies. */
  retired: boolean
}

export interface DossierDelivery {
  at: string
  kind: string
  text?: string | null
  commit?: string | null
  files?: number | null
  signature?: DossierSignature | null
  ref: DossierRef
}

export interface DossierDiff {
  agent: string
  branch?: string | null
  readable: boolean
  commits: number
  uncommitted: number
  ref: DossierRef
}

export interface ProofDossier {
  format: number
  generator: string
  version: string
  generatedAt: string
  statement: string
  mission: DossierMission
  iam: boolean
  identities: DossierSection<DossierIdentity>
  delegation?: DossierDelegation | null
  brokerCalls: DossierSection<DossierBroker>
  refusals: DossierSection<DossierRefusal>
  approvals: DossierSection<DossierApproval>
  authorized: DossierBasisCount[]
  costs: DossierCosts
  checks: DossierSection<DossierCheck>
  deliverables: DossierSection<DossierDelivery>
  diff: DossierSection<DossierDiff>
  gitReadAt?: string | null
  masked: { kind: string; count: number }[]
  limits: string[]
}
