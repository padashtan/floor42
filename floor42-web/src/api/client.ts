import type {
  ArchiveOutcome,
  RetroOffer,
  ImportView,
  WorkflowFileView,
  UncommittedCheck,
  WorkflowsView,
  PracticeRequest,
  PracticeView,
  SampleView,
  MeetingInvitation,
  MeetingNoteUse,
  MeetingPlace,
  MeetingView,
  CoreDismissalPreview,
  CoreDismissalReport,
  ArchiveView,
  DeliverablesView,
  MissionReport,
  CatalogueView,
  MissionAssetsView,
  PluginOutcome,
  PluginPreview,
  PluginRequest,
  PluginsView,
  FolderView,
  InitPreview,
  MergeResult,
  MissionProgress,
  AwayView,
  ProjectState,
  Confirmed,
  Upload,
  RepositoryFacts,
  ReplayExportPreview,
  ReplayExportRequest,
  ProjectView,
  ResumeMode,
  ResumeView,
  PullRequestOutcome,
  PullRequestPreview,
  PullRequestRequest,
  PullRequestView,
  AgentProfileView,
  AgentView,
  CheckRunView,
  ContextReading,
  FileView,
  MissionSummary,
  MissionView,
  ApiCall,
  EventView,
  CallDetail,
  NoteView,
  RetroWait,
  HeldDeveloper,
  LessonView,
  QuestionView,
  CommitteePanel,
  RoleView,
  SandboxReadiness,
  Checklist,
  BackupsView,
  UnreadableEventView,
  JournalAlertsView,
  NewSeatRequest,
  SettingView,
  Status,
  ToolDecision,
  DefaultPermissionView,
  ToolSettingView,
  UsageView,
  WorkshopView,
  TerminalAccess,
  TerminalView,
  TerminalsView,
  CostsOverview,
  MissionCosts,
  PlanApproval,
  DirectorSessionView,
  IssuedScriptToken,
  TokenScope,
} from './types'
import type { QuarantineView } from './identity'
import { ApiError, followStepUp, noContent, REQUEST_HEADER, request } from './request'

export { ApiError, REQUEST_HEADER }

/** The daemon's bounds on a new mission (MissionsResource.TITLE_MAX and OBJECTIVE_MAX). */
export const MISSION_TITLE_MAX = 200
export const MISSION_OBJECTIVE_MAX = 20_000

/** A key for one mission the director proposes: the same request sent twice with it creates one mission. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

export const api = {
  status: () => request<Status>('GET', '/api/status'),
  agents: () => request<AgentView[]>('GET', '/api/agents'),
  missions: () => request<MissionSummary[]>('GET', '/api/missions'),
  /** The missions the director archived (ADR-0054), restorable. */
  archivedMissions: () => request<MissionSummary[]>('GET', '/api/missions?archived=true'),
  /** The closed missions that can be archived now: what "Archive all closed missions" counts. */
  archivableMissions: () => request<MissionSummary[]>('GET', '/api/missions/archivable'),
  archiveMissions: (missions: string[]) => request<ArchiveOutcome[]>('POST', '/api/missions/archive', { missions }),
  archiveMission: (mission: string) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/archived`),
  retroOffer: (mission: string) => request<RetroOffer>('GET', `/api/missions/${encodeURIComponent(mission)}/retro/offer`),
  /** Convenes the retrospective of a closed mission that has none: its paid sessions start now (ADR-0054). */
  conveneRetro: (mission: string) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/retro/convene`),
  unarchiveMission: (mission: string) =>
    request<MissionView>('DELETE', `/api/missions/${encodeURIComponent(mission)}/archived`),
  mission: (id: string) => request<MissionView>('GET', `/api/missions/${encodeURIComponent(id)}`),
  forgetApproval: (question: string) =>
    request<QuestionView>('POST', `/api/questions/${encodeURIComponent(question)}/forget`),
  notes: () => request<NoteView[]>('GET', '/api/notes'),
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
  projects: () => request<ProjectView[]>('GET', '/api/projects'),
  /** Pins a project at the top of the recent list, or unpins it (journaled). */
  pinProject: (path: string, pinned: boolean) =>
    request<ProjectView[]>('POST', '/api/projects/pin', { path, pinned }),
  /** Takes a project off the recent list; its missions are untouched (journaled). */
  forgetProject: (path: string) => request<ProjectView[]>('POST', '/api/projects/forget', { path }),
  /** Missions not closed and sessions that ended before their report: reading it starts nothing. */
  resumable: () => request<ResumeView>('GET', '/api/resume'),
  /** Answers the session's "Retry"; CONTINUE first asks that the next session continue it with --resume. */
  resumeSession: (session: string, mode: ResumeMode) =>
    request<ResumeView>('POST', '/api/resume/session', { session, mode }),
  archive: (mission: string) => request<ArchiveView>('GET', `/api/missions/${encodeURIComponent(mission)}/archive`),
  /** The end-of-mission report (ADR-0039): read from the journal and git on each call. */
  report: (mission: string) => request<MissionReport>('GET', `/api/missions/${encodeURIComponent(mission)}/report`),
  /** `path` null lists the starting points: the home folder and the drives. */
  folders: (path: string | null) =>
    request<FolderView>(
      'GET',
      path ? `/api/projects/folders?path=${encodeURIComponent(path)}` : '/api/projects/folders',
    ),
  /** Creates one empty folder `name` in `parent` and returns it listed (codes NEW_FOLDER_*). */
  newFolder: (parent: string, name: string) => request<FolderView>('POST', '/api/projects/folders', { parent, name }),
  previewInit: (path: string) =>
    request<InitPreview>('GET', `/api/projects/init?path=${encodeURIComponent(path)}`),
  /** Makes the folder a git repository with a first commit, so a mission can work on it. */
  initRepository: (path: string) => request<RepositoryFacts>('POST', '/api/projects/init', { path }),
  progress: () => request<MissionProgress[]>('GET', '/api/progress'),
  /** What the project needs and what the sandbox has, from the last measure: runs nothing. */
  sandboxReadiness: (project: string | null, check: string | null) =>
    request<SandboxReadiness>('GET', `/api/sandbox/readiness${sandboxQuery(project, check)}`),
  /** What is missing on this machine: the sandbox's part from its last measure. Installs nothing. */
  machineChecklist: () => request<Checklist>('GET', '/api/sandbox/checklist'),
  /** Measures the sandbox again (one run in it, 30 s at most). Installs nothing. */
  probeSandbox: (project: string | null, check: string | null) =>
    request<SandboxReadiness>('POST', `/api/sandbox/readiness/probe${sandboxQuery(project, check)}`),
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
  /** Skills, agents and plugins (W3): read from disk now, nothing journaled, nothing installed. */
  catalogue: (mission: string | null) =>
    request<CatalogueView>('GET', mission ? `/api/catalogue?mission=${encodeURIComponent(mission)}` : '/api/catalogue'),
  /** Marketplaces and installed plugins, read from disk now (ADR-0040): no network, nothing installed. */
  plugins: () => request<PluginsView>('GET', '/api/plugins'),
  /** Reads a marketplace's listing from GitHub: a network call, on the director's click only. */
  refreshMarketplace: (name: string) =>
    request<PluginsView>('POST', `/api/plugins/marketplaces/${encodeURIComponent(name)}/refresh`),
  /** What an install or uninstall would run: runs nothing. */
  previewPlugin: (r: PluginRequest) => request<PluginPreview>('POST', '/api/plugins/preview', r),
  /** Runs what the director confirmed (the preview's digest), then reads the installed state again. */
  runPlugin: (r: PluginRequest) => request<PluginOutcome>('POST', '/api/plugins/run', r),
  missionAssets: (mission: string) =>
    request<MissionAssetsView>('GET', `/api/missions/${encodeURIComponent(mission)}/assets`),
  /** The skills and agents the mission's sessions receive from now on; journaled. */
  selectMissionAssets: (mission: string, items: string[]) =>
    request<MissionAssetsView>('PUT', `/api/missions/${encodeURIComponent(mission)}/assets`, { items }),
  /** Asks Floor42 to look at the mission again: what can run starts. */
  nudge: (mission: string) =>
    request<MissionProgress[]>('POST', `/api/missions/${encodeURIComponent(mission)}/nudge`),
  missionFiles: (mission: string) =>
    request<ProjectState>('GET', `/api/missions/${encodeURIComponent(mission)}/files`),
  /** Writes the files into `folder` of the project and commits them on the director's branch. */
  giveFiles: (mission: string, folder: string, files: Upload[]) =>
    request<ProjectState>('POST', `/api/missions/${encodeURIComponent(mission)}/files`, { folder, files }),
  /** Commits only the pending paths the director ticked: nothing else in the folder goes into the commit. */
  commitMissionFiles: (mission: string, paths: string[]) =>
    request<ProjectState>('POST', `/api/missions/${encodeURIComponent(mission)}/files/commit`, {
      paths,
    } satisfies Confirmed),
  updateAgents: (mission: string) =>
    request<MergeResult[]>('POST', `/api/missions/${encodeURIComponent(mission)}/files/update-agents`),
  lessons: () => request<LessonView[]>('GET', '/api/lessons'),
  settings: () => request<SettingView[]>('GET', '/api/settings'),
  /** `value` null goes back to the daemon's default. */
  changeSetting: (key: SettingView['key'], value: string | null) =>
    request<SettingView[]>('PUT', `/api/settings/${encodeURIComponent(key)}`, { value }),
  acceptLesson: (id: string, text: string) =>
    request<LessonView[]>('POST', `/api/lessons/${encodeURIComponent(id)}/accept`, { text }),
  rejectLesson: (id: string, reason: string) =>
    request<LessonView[]>('POST', `/api/lessons/${encodeURIComponent(id)}/reject`, { reason }),
  retireLesson: (id: string, reason: string) =>
    request<LessonView[]>('POST', `/api/lessons/${encodeURIComponent(id)}/retire`, { reason }),
  addNote: (text: string) => request<NoteView[]>('POST', '/api/notes', { text }),
  checkNote: (id: string, done: boolean) =>
    request<NoteView[]>('PUT', `/api/notes/${encodeURIComponent(id)}`, { done }),
  reviewStaffing: (mission: string) => request<MissionView>('POST', `/api/missions/${mission}/staffing`),
  held: (mission: string) => request<HeldDeveloper[]>('GET', `/api/missions/${mission}/held`),
  retroWait: (mission: string) => request<RetroWait>('GET', `/api/missions/${mission}/retro/wait`),
  closeRetro: (mission: string) => request<MissionView>('POST', `/api/missions/${mission}/retro/close`),
  closeNote: (id: string, commit: string | null) =>
    request<NoteView[]>('POST', `/api/notes/${encodeURIComponent(id)}/close`, { commit }),
  noteToTicket: (id: string, mission: string) =>
    request<NoteView[]>('POST', `/api/notes/${encodeURIComponent(id)}/ticket`, { mission }),
  removeNote: (id: string) => request<NoteView[]>('DELETE', `/api/notes/${encodeURIComponent(id)}`),
  usage: () => request<UsageView>('GET', '/api/usage'),
  // director-meeting (ADR-0047)
  /** The open meetings and the latest closed, ten in all (ADR-0049): read every few seconds. */
  meetings: () => request<MeetingView[]>('GET', '/api/meetings?limit=10'),
  meetingInvitation: (mission: string) =>
    request<MeetingInvitation>('GET', `/api/meetings/invitation?mission=${encodeURIComponent(mission)}`),
  callMeeting: (mission: string, attendees: string[], agenda: string | null, place: MeetingPlace = 'TABLE') =>
    request<MeetingView>('POST', '/api/meetings', { mission, attendees, agenda, place }),
  askForViews: (meeting: string, speakers: string[], capUsd: number, question: string | null) =>
    request<MeetingView>('POST', `/api/meetings/${encodeURIComponent(meeting)}/views`, { speakers, capUsd, question }),
  meetingNote: (meeting: string, attendee: string | null, text: string, use: MeetingNoteUse) =>
    request<MeetingView>('POST', `/api/meetings/${encodeURIComponent(meeting)}/notes`, { attendee, text, use }),
  closeMeeting: (meeting: string) =>
    request<MeetingView>('POST', `/api/meetings/${encodeURIComponent(meeting)}/close`),
  calls: (limit = 200) => request<ApiCall[]>('GET', `/api/calls?limit=${limit}`),
  call: (sequence: number) => request<CallDetail>('GET', `/api/calls/${sequence}`),
  events: (limit = 40) => request<EventView[]>('GET', `/api/events?limit=${limit}`),
  openQuestions: () => request<QuestionView[]>('GET', '/api/questions'),
  answer: (id: string, answer: string, askAgain: string | null = null) =>
    request<QuestionView>('POST', `/api/questions/${encodeURIComponent(id)}/answer`, askAgain ? { answer, askAgain } : { answer }),
  toolSettings: (mission: string) =>
    request<ToolSettingView[]>('GET', `/api/missions/${encodeURIComponent(mission)}/tools`),
  defaultPermissions: () => request<DefaultPermissionView[]>('GET', '/api/permissions/defaults'),
  setDefaultPermission: (tool: string, decision: ToolDecision | null) =>
    request<DefaultPermissionView>('PUT', `/api/permissions/defaults/${encodeURIComponent(tool)}`, { decision }),
  setToolSetting: (mission: string, tool: string, decision: ToolDecision) =>
    request<ToolSettingView>('PUT', `/api/missions/${encodeURIComponent(mission)}/tools/${encodeURIComponent(tool)}`, {
      decision,
    }),
  createMission: (
    title: string,
    objective: string,
    repository: string,
    committee: string[] = [],
    plan = false,
    panel: CommitteePanel = 'architecture',
    workflow: string | null = null,
    deliverTo: string | null = null,
    key: string = newIdempotencyKey(), mode: 'loop' | 'graph' = 'loop',
  ) =>
    request<MissionView>(
      'POST',
      '/api/missions',
      {
        title,
        objective,
        repository,
        committee,
        plan,
        panel,
        workflow, mode,
        deliverTo: deliverTo?.trim() ? deliverTo.trim() : null,
      },
      { 'Idempotency-Key': key },
    ),
  workflows: () => request<WorkflowsView>('GET', '/api/workflows'),
  saveWorkflow: (name: string, steps: readonly object[], replace: boolean, proposal: number | null = null) =>
    request<WorkflowsView>('POST', '/api/workflows', { name, steps, replace, proposal }),
  removeWorkflow: (name: string) => request<WorkflowsView>('POST', '/api/workflows/remove', { name }),
  exportWorkflow: (name: string) =>
    request<WorkflowFileView>('GET', `/api/workflows/export?name=${encodeURIComponent(name)}`),
  /** Checks a file's text in full; saves nothing. */
  importWorkflow: (text: string) => request<ImportView>('POST', '/api/workflows/import', { text }),
  dismissWorkflowProposal: (proposal: number) =>
    request<WorkflowsView>('POST', `/api/workflows/proposals/${proposal}/dismiss`),
  resumeMission: (mission: string) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/resume`),
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
  committeeRoles: () => request<RoleView[]>('GET', '/api/committee/roles'),
  /** Returns every seat, the new one among them. */
  defineSeat: (seat: NewSeatRequest) => request<RoleView[]>('POST', '/api/committee/roles', seat),
  /** The director speaks in person in their double's seat on an idea committee. */
  contributeInPerson: (
    mission: string,
    committee: number,
    text: string,
    proposal: string | null,
    position: string | null,
    criticalObjection: string | null,
  ) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/committees/${committee}/contribute`, {
      text,
      proposal,
      position,
      criticalObjection,
    }),
  /** A committee is named by its number in the mission (ADR-0046). */
  sendBackCommittee: (mission: string, committee: number, constraint: string) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/committees/${committee}/send-back`, {
      constraint,
    }),
  /** The cap is decided with the approval: a budget, or `noCap` (both journaled); a real plan without one is refused. */
  approve: (mission: string, cap: PlanApproval) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/approve`, cap).then(followStepUp),
  // cost-overview
  costs: (zone: string) => request<CostsOverview>('GET', `/api/costs?zone=${encodeURIComponent(zone)}`),
  missionCosts: (mission: string) =>
    request<MissionCosts>('GET', `/api/missions/${encodeURIComponent(mission)}/costs`),
  reject: (mission: string, reason: string) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/reject`, { reason }),
  /** `acknowledged`, `proofAcknowledged`: the tokens of the uncommitted work (ADR-0055) and unproven delivery (ADR-0058) seen. */
  complete: (mission: string, summary: string, withIgnored = false, acknowledged: string | null = null, proofAcknowledged: string | null = null) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/complete`, { reason: summary, withIgnored, acknowledged, proofAcknowledged }),
  uncommitted: (mission: string) =>
    request<UncommittedCheck>('GET', `/api/missions/${encodeURIComponent(mission)}/uncommitted`),
  cancel: (mission: string, reason: string) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/cancel`, { reason }),
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
  setBudget: (mission: string, usd: number | null) =>
    request<MissionView>('PUT', `/api/missions/${encodeURIComponent(mission)}/budget`, { usd }),
  runChecks: (mission: string, agent: string) =>
    request<CheckRunView>('POST', `${workshopPath(mission)}/${encodeURIComponent(agent)}/checks`),
  /** What a replay file of the mission would hold and what it masks: builds nothing. */
  replayPreview: (mission: string) =>
    request<ReplayExportPreview>('GET', `/api/missions/${encodeURIComponent(mission)}/replay-export/preview`),
  /** The replay file, for the content of the preview the director confirmed (its digest). */
  exportReplay: (mission: string, body: ReplayExportRequest) =>
    download(`/api/missions/${encodeURIComponent(mission)}/replay-export`, body),
  startLiveDemo: (paceMs = 1500) => request<{ mission: string }>('POST', `/api/demo/live?paceMs=${paceMs}`),
  /** Floor42's own throwaway repository for the guided first mission (ADR-0045), created the first time. */
  prepareSample: () => request<SampleView>('POST', '/api/demo/sample'),
  /** The guided first mission: simulated, on the sample repository; its plan awaits approval on return. */
  /** Where a rehearsal stands: PLAYING, DONE, or STOPPED (nothing resumes it). */
  practice: (mission: string) => request<PracticeView>('GET', `/api/demo/practice/${encodeURIComponent(mission)}`),
  startPractice: (body: PracticeRequest) => request<{ mission: string }>('POST', '/api/demo/practice', body),
  /** Pull requests (ADR-0036): those recorded for the mission, read from the journal. */
  pullRequests: (mission: string) => request<PullRequestView[]>('GET', pullRequestsPath(mission)),
  /** What a pull request of this branch would be. Pushes nothing. */
  pullRequestPreview: (mission: string, workspace: string | null) =>
    request<PullRequestPreview>(
      'GET',
      `${pullRequestsPath(mission)}/preview${workspace ? `?workspace=${encodeURIComponent(workspace)}` : ''}`,
    ),
  /** Pushes the branch (never forced) and opens the pull request the director confirmed. */
  openPullRequest: (mission: string, confirmed: PullRequestRequest) =>
    request<PullRequestOutcome>('POST', pullRequestsPath(mission), confirmed),
  /** Reads once the checks GitHub reports on the pull request. */
  refreshPullRequestChecks: (mission: string, number: number) =>
    request<PullRequestView>('POST', `${pullRequestsPath(mission)}/${number}/checks`),
  /** What the mission delivered (ADR-0038); ignored files only when asked. Read only, never journaled. */
  deliverables: (mission: string, ignored: boolean) =>
    request<DeliverablesView>('GET', `${deliverablesPath(mission)}${ignored ? '?ignored=true' : ''}`),
  /** Opens the agent's worktree in the file explorer of the director's machine. */
  openFolder: async (mission: string, agent: string): Promise<void> => {
    const response = await fetch(`${deliverablesPath(mission)}/open-folder`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { ...REQUEST_HEADER, 'Content-Type': 'application/json' },
      body: JSON.stringify({ agent }),
    })
    if (!response.ok) {
      const problem: { code?: string; message?: string } = await response.json().catch(() => ({}))
      throw new ApiError(response.status, problem.code ?? `HTTP_${response.status}`, problem.message ?? response.statusText)
    }
  },
}

/** One delivered file, as the daemon names it: whose branch, which path, committed or only in the worktree. */
export interface DeliverableRef {
  agent: string
  path: string
  source: 'COMMITTED' | 'WORKTREE'
}

function deliverablesPath(mission: string): string {
  return `/api/missions/${encodeURIComponent(mission)}/deliverables`
}

/** The address of one delivered file: shown (sandboxed by the daemon) or downloaded as an attachment. */
export function deliverableUrl(mission: string, file: DeliverableRef, download: boolean): string {
  const q = new URLSearchParams({ agent: file.agent, path: file.path, source: file.source })
  if (download) q.set('download', 'true')
  return `${deliverablesPath(mission)}/file?${q.toString()}`
}

/** The whole mission as one ZIP. */
export function deliverablesZipUrl(mission: string, ignored: boolean): string {
  return `${deliverablesPath(mission)}/zip${ignored ? '?ignored=true' : ''}`
}

function pullRequestsPath(mission: string): string {
  return `/api/missions/${encodeURIComponent(mission)}/pull-requests`
}

/** A POST that answers a file: the body as a Blob, or the daemon's refusal as an ApiError. */
async function download(path: string, body: unknown): Promise<Blob> {
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { ...REQUEST_HEADER, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    const problem: { code?: string; message?: string } = await response.json().catch(() => ({}))
    throw new ApiError(response.status, problem.code ?? `HTTP_${response.status}`, problem.message ?? response.statusText)
  }
  return response.blob()
}

function sandboxQuery(project: string | null, check: string | null): string {
  const q = new URLSearchParams()
  if (project) q.set('project', project)
  if (check) q.set('check', check)
  const s = q.toString()
  return s ? `?${s}` : ''
}

function workshopPath(mission: string): string {
  return `/api/missions/${encodeURIComponent(mission)}/workshop`
}
