import type {
  FolderView,
  InitPreview,
  MissionProgress,
  RepositoryFacts,
  ProjectView,
  ResumeMode,
  PreflightView,
  ResumeView,
  SandboxReadiness,
  Checklist,
} from '../types'
import { request } from '../request'

export const projectsApi = {
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
  /** What stands in the way of a real mission on this folder: reads files, git and the journal, measures nothing. */
  preflight: (project: string, check: string | null) =>
    request<PreflightView>('GET', `/api/preflight${sandboxQuery(project, check)}`),
  /** What is missing on this machine: the sandbox's part from its last measure. Installs nothing. */
  machineChecklist: () => request<Checklist>('GET', '/api/sandbox/checklist'),
  /** Measures the sandbox again (one run in it, 30 s at most). Installs nothing. */
  probeSandbox: (project: string | null, check: string | null) =>
    request<SandboxReadiness>('POST', `/api/sandbox/readiness/probe${sandboxQuery(project, check)}`),
}

function sandboxQuery(project: string | null, check: string | null): string {
  const q = new URLSearchParams()
  if (project) q.set('project', project)
  if (check) q.set('check', check)
  const s = q.toString()
  return s ? `?${s}` : ''
}
