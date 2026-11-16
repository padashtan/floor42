import type {
  ArchiveView,
  DeliverablesView,
  MissionReport,
  ReplayExportPreview,
  ReplayExportRequest,
  PullRequestOutcome,
  PullRequestPreview,
  PullRequestRequest,
  PullRequestView,
} from '../types'
import { ApiError, REQUEST_HEADER, request } from '../request'

export const deliveryApi = {
  archive: (mission: string) => request<ArchiveView>('GET', `/api/missions/${encodeURIComponent(mission)}/archive`),
  /** The end-of-mission report (ADR-0039): read from the journal and git on each call. */
  report: (mission: string) => request<MissionReport>('GET', `/api/missions/${encodeURIComponent(mission)}/report`),
  /** What a replay file of the mission would hold and what it masks: builds nothing. */
  replayPreview: (mission: string) =>
    request<ReplayExportPreview>('GET', `/api/missions/${encodeURIComponent(mission)}/replay-export/preview`),
  /** The replay file, for the content of the preview the director confirmed (its digest). */
  exportReplay: (mission: string, body: ReplayExportRequest) =>
    download(`/api/missions/${encodeURIComponent(mission)}/replay-export`, body),
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
