// Mirrors of the daemon's API views, part 5 (see types.ts).

import type { RepositoryFacts } from './types.settings'

/** A project real missions already worked on. */
export interface ProjectView {
  path: string
  /** Its missions not archived, newest first. */
  missions: string[]
  lastTitle: string | null
  office: boolean
  exists: boolean
  git: RepositoryFacts | null
  /** The newest real mission on it not archived; `lastTitle` is its title. Null when all are archived (ADR-0054). */
  lastMission: string | null
  /** When anything last happened to one of its missions (from the journal). */
  lastActivity: string | null
  pinned: boolean
  /** Taken off the recent list by the director, and no mission created on it since. */
  forgotten: boolean
}

export interface FolderEntry {
  name: string
  path: string
  repository: boolean
}

/** One folder of the browser: its sub-folders (names only) and, for a repository, what git says. */
export interface FolderView {
  path: string | null
  parent: string | null
  git: RepositoryFacts | null
  folders: FolderEntry[]
  truncated: boolean
}

/** What making a folder a repository would do; `refused` says why Floor42 will not. */
export interface InitPreview {
  path: string
  repository: boolean
  hasCommit: boolean
  files: number
  refused: string | null
}

/** Where the director's files stand for a mission's project. */
export interface ProjectState {
  path: string
  branch: string
  head: string | null
  pending: string[]
  pendingCount: number
  agents: AgentCopy[]
}

/** The pending paths the director confirmed for a commit: nothing else in the folder is committed. */
export interface Confirmed {
  paths: string[]
}

/** The body of every error the daemon returns. */
export interface Problem {
  code: string
  message: string
}

/** An agent's worktree: whether it holds the director's last commit, whether its session runs. */
export interface AgentCopy {
  agent: string
  upToDate: boolean
  atWork: boolean
}

/** What merging the director's branch into one worktree gave. */
export interface MergeResult {
  agent: string
  clean: boolean
  skipped: boolean
  detail: string | null
}

/** A file from the browser, its content in base64. */
export interface Upload {
  name: string
  content: string
}

/** One commit as git records it (W6). */
export interface CommitLine {
  hash: string
  subject: string
  author: string
  at: string
}

/** One agent's worktree branch: the commits it holds beyond the repository's current branch (-1: not counted). */
export interface BranchLine {
  agent: string
  branch: string
  ahead: number
  commits: CommitLine[]
}

/** The archive wall (W6), read from git when asked. */
export interface ArchiveView {
  mission: string
  repository: string | null
  branch: string | null
  available: boolean
  trunk: CommitLine[]
  branches: BranchLine[]
  graph: GraphView
}

/** One commit of the git graph: full hashes, first parent first, author date ISO 8601. */
export interface GraphCommit {
  hash: string
  parents: string[]
  subject: string
  author: string
  at: string
}

/** A branch head: the director's (agent null) or an agent's worktree; uncommitted files are not in the graph. */
export interface GraphHead {
  agent: string | null
  branch: string
  commit: string
  uncommitted: number
}

/** The git graph of a mission: the director's branch first, children before parents, at most 200 commits. */
export interface GraphView {
  heads: GraphHead[]
  commits: GraphCommit[]
  truncated: boolean
}

/** A tool as sandbox readiness sees it: MISSING only when a measure said so (ADR-0033). */
export type ToolState = 'PRESENT' | 'OLDER' | 'MISSING' | 'UNKNOWN'

/** One tool: what the project asks for and what the sandbox's last measure found. */
export interface ToolLine {
  tool: string
  wanted: string | null
  /** The project's files that need it; empty for a tool the sandbox has but the project does not need. */
  because: string[]
  state: ToolState
  path: string | null
  version: string | null
  /** docker.unreachable, docker.daemon, docker.reachable, nvm or mvnw: explained on screen. */
  note: string | null
}

/** Which tools a project needs, which ones the WSL sandbox has, and the commands to install the rest (copy only). */
export interface SandboxReadiness {
  project: string | null
  enabled: boolean
  network: boolean
  distro: string
  os: string | null
  measuredAt: string | null
  measured: boolean
  reason: string | null
  recipes: boolean
  tools: ToolLine[]
  commands: string[]
  manual: string[]
}

/** A checklist row: MISSING is required and absent, OPTIONAL absent but not required. */
export type ChecklistState = 'PRESENT' | 'MISSING' | 'OPTIONAL' | 'UNKNOWN'

/** One thing the machine has or lacks for Floor42, and the command to copy (Floor42 never runs it). */
export interface ChecklistRow {
  /** claude, git, wsl, distro, isolation, or an essential tool's name: explained on screen. */
  id: string
  sandbox: boolean
  state: ChecklistState
  required: boolean
  version: string | null
  /** old, notFound, wsl1, notWindows, notMeasured or probeFailed: explained on screen. */
  note: string | null
  /** The isolation probe's own words when it failed, for floor42 doctor (English): never shown on a screen. */
  detail: string | null
  command: string | null
  /** When a row measured apart from the tools was measured (the Docker daemon, in memory since the start). */
  measuredAt: string | null
}

/** What is missing on this machine (the same list as floor42 doctor). */
export interface Checklist {
  windows: boolean
  distro: string
  measuredAt: string | null
  ready: boolean
  rows: ChecklistRow[]
}

/** Whether an item reaches Floor42's sessions (W3): AVAILABLE or NOT_AVAILABLE only from a real session's init line. */
export type Reach = 'AVAILABLE' | 'NOT_AVAILABLE' | 'UNKNOWN'

/** How Floor42 launches its sessions, and the session the verdicts are read from (null fields: no session yet). */
export interface LaunchView {
  flags: string[]
  sessionAt: string | null
  sessionVersion: string | null
  sessionCwd: string | null
  sessionOfMission: boolean
  sessionTools: string[]
  skillTool: boolean | null
  agentTool: boolean | null
  /** isolated, notIsolated or unknown. */
  isolation: string
}

/** A skill, custom agent or plugin found on disk; only its frontmatter or manifest was read. */
export interface InstalledItem {
  kind: 'SKILL' | 'AGENT' | 'PLUGIN'
  level: 'USER' | 'PROJECT' | 'PLUGIN'
  plugin: string | null
  name: string
  description: string | null
  tools: string[]
  model: string | null
  version: string | null
  path: string
  /** linkOut, outsidePlugins, tooLarge, malformed, noFrontmatter, unreadable; null when read. */
  problem: string | null
  state: Reach
  reason: string
}

/** A name the session's init lists that matches no file Floor42 read (built into Claude Code, or elsewhere). */
export interface SessionItem {
  kind: 'SKILL' | 'AGENT' | 'PLUGIN'
  name: string
  state: Reach
  reason: string
}

/** An entry of the curated catalogue shipped with Floor42. Floor42 never installs it: the command is to copy. */
export interface SuggestionItem {
  id: string
  kind: 'skill' | 'agent' | 'plugin' | 'mcp'
  name: string
  source: string
  brings: Record<string, string>
  risks: Record<string, string>
  install: string[]
  license: string | null
  installed: boolean
  state: Reach
  reason: string
}

/** GET /api/catalogue: a reading from disk at `readAt`, nothing journaled. */
export interface CatalogueView {
  readAt: string
  mission: string | null
  repository: string | null
  launch: LaunchView
  installed: InstalledItem[]
  session: SessionItem[]
  suggestions: SuggestionItem[]
  checked: string | null
}
