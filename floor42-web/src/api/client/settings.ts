import type {
  AwayView,
  BackupsView,
  UnreadableEventView,
  JournalAlertsView,
  SettingView,
  ToolDecision,
  DefaultPermissionView,
  ToolSettingView,
  TerminalAccess,
  TerminalView,
  TerminalsView,
  DirectorSessionView,
  IssuedScriptToken,
  TokenScope,
} from '../types'
import { noContent, request } from '../request'

export const settingsApi = {
  // The director's sessions and script tokens (ADR-0044): in the daemon's memory only.
  sessions: () => request<DirectorSessionView[]>('GET', '/api/auth/sessions'),
  endSession: (id: string) => noContent('DELETE', `/api/auth/sessions/${encodeURIComponent(id)}`),
  issueToken: (scope: TokenScope, days: number, label: string | null) =>
    request<IssuedScriptToken>('POST', '/api/auth/tokens', { scope, days, label }),
  signOut: () => noContent('POST', '/api/auth/sign-out'),
  signOutEverywhere: () => noContent('POST', '/api/auth/sign-out-everywhere'),
  // The director's own terminal (ADR-0041): opened on the director's click only.
  terminals: () => request<TerminalsView>('GET', '/api/terminals'),
  openTerminal: (project: string, columns: number, rows: number) =>
    request<TerminalAccess>('POST', '/api/terminals', { project, columns, rows }),
  attachTerminal: (id: string) =>
    request<TerminalAccess>('POST', `/api/terminals/${encodeURIComponent(id)}/attach`),
  closeTerminal: (id: string) => request<TerminalView>('DELETE', `/api/terminals/${encodeURIComponent(id)}`),
  /** The journal rows replay left out at startup (ADR-0042): empty when the state is complete. */
  unreadableEvents: () => request<UnreadableEventView[]>('GET', '/api/journal/unreadable'),
  journalAlerts: () => request<JournalAlertsView>('GET', '/api/journal/alerts'),
  acknowledgeContinuity: () => request<JournalAlertsView>('POST', '/api/journal/alerts/continuity/acknowledge'),
  /** The journal's copies, newest first, and a restore waiting for the next start. */
  backups: () => request<BackupsView>('GET', '/api/backups'),
  backUpNow: () => request<BackupsView>('POST', '/api/backups'),
  /** The next start restores this copy; nothing changes before. */
  prepareRestore: (name: string) => request<BackupsView>('PUT', '/api/backups/restore', { name }),
  cancelRestore: () => request<BackupsView>('DELETE', '/api/backups/restore'),
  /** What happened after this journal sequence; without one, only the latest sequence to acknowledge. */
  awaySummary: (since: number | null) =>
    request<AwayView>('GET', since === null ? '/api/journal/summary' : `/api/journal/summary?since=${since}`),
  settings: () => request<SettingView[]>('GET', '/api/settings'),
  /** `value` null goes back to the daemon's default. */
  changeSetting: (key: SettingView['key'], value: string | null) =>
    request<SettingView[]>('PUT', `/api/settings/${encodeURIComponent(key)}`, { value }),
  toolSettings: (mission: string) =>
    request<ToolSettingView[]>('GET', `/api/missions/${encodeURIComponent(mission)}/tools`),
  defaultPermissions: () => request<DefaultPermissionView[]>('GET', '/api/permissions/defaults'),
  setDefaultPermission: (tool: string, decision: ToolDecision | null) =>
    request<DefaultPermissionView>('PUT', `/api/permissions/defaults/${encodeURIComponent(tool)}`, { decision }),
  setToolSetting: (mission: string, tool: string, decision: ToolDecision) =>
    request<ToolSettingView>('PUT', `/api/missions/${encodeURIComponent(mission)}/tools/${encodeURIComponent(tool)}`, {
      decision,
    }),
}
