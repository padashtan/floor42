import type {
  ArchiveOutcome,
  RetroOffer,
  UncommittedCheck,
  PracticeRequest,
  PracticeView,
  SampleView,
  MergeResult,
  MissionProgress,
  ProjectState,
  Confirmed,
  Upload,
  MissionSummary,
  MissionView,
  RetroWait,
  HeldDeveloper,
  QuestionView,
  CommitteePanel,
  PlanApproval,
} from '../types'
import { followStepUp, request } from '../request'

/** The daemon's bounds on a new mission (MissionsResource.TITLE_MAX and OBJECTIVE_MAX). */
export const MISSION_TITLE_MAX = 200
export const MISSION_OBJECTIVE_MAX = 20_000

/** A key for one mission the director proposes: the same request sent twice with it creates one mission. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

export const missionsApi = {
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
  reviewStaffing: (mission: string) => request<MissionView>('POST', `/api/missions/${mission}/staffing`),
  held: (mission: string) => request<HeldDeveloper[]>('GET', `/api/missions/${mission}/held`),
  retroWait: (mission: string) => request<RetroWait>('GET', `/api/missions/${mission}/retro/wait`),
  closeRetro: (mission: string) => request<MissionView>('POST', `/api/missions/${mission}/retro/close`),
  openQuestions: () => request<QuestionView[]>('GET', '/api/questions'),
  answer: (id: string, answer: string, askAgain: string | null = null) =>
    request<QuestionView>('POST', `/api/questions/${encodeURIComponent(id)}/answer`, askAgain ? { answer, askAgain } : { answer }).then(
      followStepUp,
    ),
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
  resumeMission: (mission: string) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/resume`),
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
  reject: (mission: string, reason: string) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/reject`, { reason }),
  /** `acknowledged`, `proofAcknowledged`: the tokens of the uncommitted work (ADR-0055) and unproven delivery (ADR-0058) seen. */
  complete: (mission: string, summary: string, withIgnored = false, acknowledged: string | null = null, proofAcknowledged: string | null = null) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/complete`, { reason: summary, withIgnored, acknowledged, proofAcknowledged }),
  uncommitted: (mission: string) =>
    request<UncommittedCheck>('GET', `/api/missions/${encodeURIComponent(mission)}/uncommitted`),
  cancel: (mission: string, reason: string) =>
    request<MissionView>('POST', `/api/missions/${encodeURIComponent(mission)}/cancel`, { reason }),
  setBudget: (mission: string, usd: number | null) =>
    request<MissionView>('PUT', `/api/missions/${encodeURIComponent(mission)}/budget`, { usd }),
  startLiveDemo: (paceMs = 1500) => request<{ mission: string }>('POST', `/api/demo/live?paceMs=${paceMs}`),
  /** Floor42's own throwaway repository for the guided first mission (ADR-0045), created the first time. */
  prepareSample: () => request<SampleView>('POST', '/api/demo/sample'),
  /** The guided first mission: simulated, on the sample repository; its plan awaits approval on return. */
  /** Where a rehearsal stands: PLAYING, DONE, or STOPPED (nothing resumes it). */
  practice: (mission: string) => request<PracticeView>('GET', `/api/demo/practice/${encodeURIComponent(mission)}`),
  startPractice: (body: PracticeRequest) => request<{ mission: string }>('POST', '/api/demo/practice', body),
}
