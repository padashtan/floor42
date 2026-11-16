// The human oversight report (ADR-0075). To be checked against openapi.json by contract.ts at INT.
export interface OversightEventRef {
  /** The number among the mission's events from 1; absent for a company-wide event. */
  seq?: number | null
  eventId: string
  type: string
}

export interface OversightLine {
  at: string
  kind: string
  actor: string
  scope?: string | null
  text?: string | null
  code?: string | null
  basis?: string | null
  origin?: string | null
  events: OversightEventRef[]
}

export interface OversightSection {
  count: number
  items: OversightLine[]
}

export interface OversightMission {
  id: string
  title: string
  origin: string
  status: string
  events: number
  real: number
  simulated: number
}

export interface OversightReport {
  format: number
  generator: string
  version: string
  generatedAt: string
  statement: string
  mission: OversightMission
  supervision: OversightSection
  capabilities: OversightSection
  actions: OversightSection
  overrides: OversightSection
  masked: { kind: string; count: number }[]
  limits: string[]
}
