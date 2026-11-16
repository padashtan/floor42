import type {
  CoreDismissalPreview,
  CoreDismissalReport,
  AgentProfileView,
  AgentView,
  ContextReading,
  NoteView,
  LessonView,
  RoleView,
  NewSeatRequest,
  Standup,
  Status,
} from '../types'
import type { QuarantineView } from '../identity'
import { request } from '../request'

export const officeApi = {
  status: () => request<Status>('GET', '/api/status'),
  agents: () => request<AgentView[]>('GET', '/api/agents'),
  /** The 9 o'clock standup (ADR-0078), the days counted in the browser's time zone. */
  standup: (zone: string) => request<Standup>('GET', `/api/standup?zone=${encodeURIComponent(zone)}`),
  notes: () => request<NoteView[]>('GET', '/api/notes'),
  lessons: () => request<LessonView[]>('GET', '/api/lessons'),
  acceptLesson: (id: string, text: string) =>
    request<LessonView[]>('POST', `/api/lessons/${encodeURIComponent(id)}/accept`, { text }),
  rejectLesson: (id: string, reason: string) =>
    request<LessonView[]>('POST', `/api/lessons/${encodeURIComponent(id)}/reject`, { reason }),
  retireLesson: (id: string, reason: string) =>
    request<LessonView[]>('POST', `/api/lessons/${encodeURIComponent(id)}/retire`, { reason }),
  addNote: (text: string) => request<NoteView[]>('POST', '/api/notes', { text }),
  checkNote: (id: string, done: boolean) =>
    request<NoteView[]>('PUT', `/api/notes/${encodeURIComponent(id)}`, { done }),
  closeNote: (id: string, commit: string | null) =>
    request<NoteView[]>('POST', `/api/notes/${encodeURIComponent(id)}/close`, { commit }),
  noteToTicket: (id: string, mission: string) =>
    request<NoteView[]>('POST', `/api/notes/${encodeURIComponent(id)}/ticket`, { mission }),
  removeNote: (id: string) => request<NoteView[]>('DELETE', `/api/notes/${encodeURIComponent(id)}`),
  suspendAgent: (agent: string, reason: string) =>
    request<AgentView>('POST', `/api/agents/${encodeURIComponent(agent)}/suspend`, { reason }),
  /** The director's confirmed quarantine of an agent's risky session: revoke, suspend, stop. */
  quarantine: (mission: string, agent: string) =>
    request<QuarantineView>('POST', `/api/missions/${encodeURIComponent(mission)}/quarantine`, { agent }),
  resumeAgent: (agent: string) => request<AgentView>('POST', `/api/agents/${encodeURIComponent(agent)}/resume`),
  hireAgent: (name: string, role: string, workRole: string, model: string, effort: string) =>
    request<AgentView>('POST', '/api/agents', { name, role, workRole, model, effort }),
  dismissAgent: (agent: string, reason: string) =>
    request<AgentView>('POST', `/api/agents/${encodeURIComponent(agent)}/dismiss`, { reason }),
  markCore: (agent: string, core: boolean) =>
    request<AgentView>('PUT', `/api/agents/${encodeURIComponent(agent)}/core`, { core }),
  coreDismissalPreview: () => request<CoreDismissalPreview>('GET', '/api/agents/dismiss-all-but-core'),
  dismissAllButCore: (agents: string[], reason: string) =>
    request<CoreDismissalReport>('POST', '/api/agents/dismiss-all-but-core', { agents, reason }),
  agentProfile: (agent: string) => request<AgentProfileView>('GET', `/api/agents/${encodeURIComponent(agent)}/profile`),
  sessionContext: (session: string) =>
    request<ContextReading | null>('GET', `/api/sessions/${encodeURIComponent(session)}/context`),
  reconfigureAgent: (agent: string, model: string, effort: string | null) =>
    request<AgentView>('PUT', `/api/agents/${encodeURIComponent(agent)}`, { model, effort }),
  committeeRoles: () => request<RoleView[]>('GET', '/api/committee/roles'),
  /** Returns every seat, the new one among them. */
  defineSeat: (seat: NewSeatRequest) => request<RoleView[]>('POST', '/api/committee/roles', seat),
}
