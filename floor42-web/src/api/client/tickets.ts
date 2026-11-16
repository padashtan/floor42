import type {
  CheckRunView,
  FileView,
  MissionView,
  WorkshopView,
} from '../types'
import { request } from '../request'

export const ticketsApi = {
  /** `risks` are the director's risks for graph nodes (ADR-0062), by ticket id: final once approved. */
  approveTickets: (mission: string, tickets: string[], risks?: Record<string, 'low' | 'high'>) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/tickets/approve`, {
      tickets,
      ...(risks && Object.keys(risks).length ? { risks } : {}),
    }),
  /** Extend the graph (ADR-0062): one paid planner session proposes new nodes. Scope all. */
  extendGraph: (mission: string, goal: string) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/graph/extend`, { goal }),
  editTicket: (ticket: string, title: string, description: string, acceptanceCriteria: string[]) =>
    request<MissionView>('PUT', `/api/tickets/${encodeURIComponent(ticket)}`, { title, description, acceptanceCriteria }),
  dropTicket: (ticket: string, reason: string) =>
    request<MissionView>('POST', `/api/tickets/${encodeURIComponent(ticket)}/drop`, { reason }),
  /** The tickets as a Jira import file: a plain download link, the session cookie rides along. */
  ticketsCsvUrl: (mission: string) => `/api/missions/${encodeURIComponent(mission)}/tickets.csv`,
  workshop: (mission: string) => request<WorkshopView>('GET', `${workshopPath(mission)}`),
  workshopFiles: (mission: string, agent: string) =>
    request<string[]>('GET', `${workshopPath(mission)}/${encodeURIComponent(agent)}/files`),
  workshopFile: (mission: string, agent: string, path: string) =>
    request<FileView>('GET', `${workshopPath(mission)}/${encodeURIComponent(agent)}/file?path=${encodeURIComponent(path)}`),
  workshopDiff: (mission: string, agent: string, path: string) =>
    request<{ path: string; diff: string }>(
      'GET',
      `${workshopPath(mission)}/${encodeURIComponent(agent)}/diff?path=${encodeURIComponent(path)}`,
    ),
  editFile: (mission: string, agent: string, path: string, content: string, note: string) =>
    request<WorkshopView>(
      'PUT',
      `${workshopPath(mission)}/${encodeURIComponent(agent)}/file?path=${encodeURIComponent(path)}`,
      { content, note },
    ),
  acceptPatch: (mission: string, agent: string, message: string, paths?: string[]) =>
    request<WorkshopView>('POST', `${workshopPath(mission)}/${encodeURIComponent(agent)}/accept`, { message, paths }),
  rejectPatch: (mission: string, agent: string, reason: string) =>
    request<WorkshopView>('POST', `${workshopPath(mission)}/${encodeURIComponent(agent)}/reject`, { message: reason }),
  directive: (mission: string, ticket: string, directive: string) =>
    request<WorkshopView>('POST', `${workshopPath(mission)}/directive`, { ticket, directive }),
  configureChecks: (mission: string, command: string, automatic: boolean) =>
    request<MissionView>('PUT', `/api/missions/${encodeURIComponent(mission)}/checks`, { command, automatic }),
  runChecks: (mission: string, agent: string) =>
    request<CheckRunView>('POST', `${workshopPath(mission)}/${encodeURIComponent(agent)}/checks`),
}

function workshopPath(mission: string): string {
  return `/api/missions/${encodeURIComponent(mission)}/workshop`
}
