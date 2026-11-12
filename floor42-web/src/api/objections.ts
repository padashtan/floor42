// The director's objections at acceptance (ADR-0060): the request of POST /api/missions/{id}/objections and the rounds
// the report groups. Kept apart from types.ts and client.ts, which are at their size ceiling; contract.ts checks these
// types against openapi.json all the same.
import type { MissionView } from './types'
import { request } from './request'

/** One objection: about a done work ticket, or `ticket` null for new work. */
export interface ObjectionRequest {
  ticket: string | null
  text: string
}

export interface ObjectionsRequest {
  objections: ObjectionRequest[]
}

/** One round of objections, grouped by the cause its events share: the tickets reopened and the tickets created. */
export interface ReportObjectionRound {
  number: number
  targeted: string[]
  created: string[]
}

export const objectionsApi = {
  /** Reopens the tickets named with the text as a directive, and creates the new work: one command. */
  object: (mission: string, body: ObjectionsRequest) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/objections`, body),
}
