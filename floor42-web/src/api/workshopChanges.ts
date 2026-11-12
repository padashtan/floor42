// Per-file decisions of the workshop, kept apart from client.ts, which is at its size ceiling; contract.ts checks the
// types against openapi.json all the same.
import { request } from './request'
import type { WorkshopView } from './types'

export const workshopChangesApi = {
  /** Gives up one pending change: the file goes back to the last commit, a new file is deleted. */
  discard: (mission: string, agent: string, path: string) =>
    request<WorkshopView>(
      'POST',
      `/api/missions/${encodeURIComponent(mission)}/workshop/${encodeURIComponent(agent)}/discard?path=${encodeURIComponent(path)}`,
    ),
}
