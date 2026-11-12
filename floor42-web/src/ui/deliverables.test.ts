import { describe, expect, it } from 'vitest'
import type { DeliverablesView, DeliveredBranch, DeliveredFile, DeliveredTicket } from '../api/types'
import {
  byAgent,
  byTicket,
  capOf,
  counts,
  downloadable,
  findFile,
  formatSize,
  notViewable,
  refOf,
  shouldOpenOnEnd,
  viewerOf,
} from './deliverables'

const file = (over: Partial<DeliveredFile>): DeliveredFile => ({
  path: 'a.md',
  from: null,
  status: 'A',
  size: 10,
  commit: 'abc1234',
  committed: true,
  ignored: false,
  link: false,
  kind: 'TEXT',
  ...over,
})

const branch = (agent: string, over: Partial<DeliveredBranch> = {}): DeliveredBranch => ({
  agent,
  branch: `floor42/m-1-${agent}`,
  worktree: `/data/workspaces/M-1-${agent}`,
  worktreePresent: true,
  legacy: false,
  start: 'aaa',
  tip: 'bbb',
  merged: 'NOT_MERGED',
  notInProject: 1,
  commits: [],
  committed: [],
  uncommitted: [],
  ignoredHidden: 0,
  truncated: false,
  tickets: [],
  ...over,
})

const ticket = (id: string, assignee: string | null): DeliveredTicket => ({
  id,
  title: id,
  status: 'DONE',
  assignee,
  deliverable: 'done',
  verdict: 'VALIDATED',
  reviews: [],
})

const view = (branches: DeliveredBranch[], tickets: DeliveredTicket[]): DeliverablesView => ({
  mission: 'M-1',
  title: 'T',
  status: 'COMPLETED',
  origin: 'REAL',
  repository: '/repo',
  available: true,
  projectBranch: 'main',
  projectHead: 'ccc',
  ignoredShown: false,
  tickets,
  branches,
  pullRequests: [],
  maxViewText: 1024,
  maxViewBytes: 4096,
  maxZipBytes: 1 << 20,
})

describe('deliverables', () => {
  it('groups by agent: branches first, an assignee without a branch, unassigned tickets last; every ticket once', () => {
    const v = view([branch('chris'), branch('linus')], [ticket('T-1', 'chris'), ticket('T-2', null), ticket('T-3', 'ada')])
    const groups = byAgent(v)
    expect(groups.map((g) => g.agent)).toEqual(['chris', 'linus', 'ada', null])
    expect(groups[0].tickets.map((t) => t.id)).toEqual(['T-1'])
    expect(groups[1].tickets).toEqual([])
    expect(groups[2].branch).toBeNull()
    expect(groups.flatMap((g) => g.tickets.map((t) => t.id)).sort()).toEqual(['T-1', 'T-2', 'T-3'])
  })

  it('gives each ticket its assignee branch', () => {
    const v = view([branch('chris')], [ticket('T-1', 'chris'), ticket('T-2', null)])
    expect(byTicket(v).map((g) => g.branch?.agent ?? null)).toEqual(['chris', null])
  })

  it('formats sizes and says when one is unknown', () => {
    expect(formatSize(null)).toBe('—')
    expect(formatSize(512)).toBe('512 B')
    expect(formatSize(1536)).toBe('1.5 KB')
    expect(formatSize(3 * 1024 * 1024)).toBe('3.0 MB')
  })

  it('shows only what the daemon would show, within its caps, never a link or a deleted file', () => {
    const v = view([], [])
    expect(viewerOf(file({}), v)).toBe('text')
    expect(viewerOf(file({ size: 2048 }), v)).toBeNull()
    expect(viewerOf(file({ kind: 'IMAGE', size: 2048 }), v)).toBe('image')
    expect(viewerOf(file({ kind: 'PDF', size: 9000 }), v)).toBeNull()
    expect(viewerOf(file({ kind: 'OTHER' }), v)).toBeNull()
    expect(viewerOf(file({ link: true }), v)).toBeNull()
    expect(viewerOf(file({ status: 'D' }), v)).toBeNull()
    expect(downloadable(file({ link: true }))).toBe(false)
    expect(downloadable(file({ status: '!' }))).toBe(true)
  })

  it('names the source the daemon reads a file from', () => {
    expect(refOf('chris', file({})).source).toBe('COMMITTED')
    expect(refOf('chris', file({ committed: false, status: '?' }))).toEqual({
      agent: 'chris',
      path: 'a.md',
      source: 'WORKTREE',
    })
  })

  it('counts committed, not committed and ignored files apart', () => {
    const b = branch('chris', {
      committed: [file({}), file({ path: 'gone', status: 'D' })],
      uncommitted: [file({ committed: false, status: '?' }), file({ committed: false, status: '!', ignored: true })],
    })
    expect(counts(b)).toEqual({ committed: 1, uncommitted: 1, ignored: 1 })
  })

  it('opens once when a mission ends under the director’s eyes, never for one already ended', () => {
    const store = new Map<string, string>()
    const storage = {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
    } as unknown as Storage
    expect(shouldOpenOnEnd(undefined, 'COMPLETED', 'M-1', storage)).toBe(false)
    expect(shouldOpenOnEnd('COMPLETED', 'COMPLETED', 'M-1', storage)).toBe(false)
    expect(shouldOpenOnEnd('IN_PROGRESS', 'IN_PROGRESS', 'M-1', storage)).toBe(false)
    expect(shouldOpenOnEnd('IN_PROGRESS', 'COMPLETED', 'M-1', storage)).toBe(true)
    expect(shouldOpenOnEnd('IN_PROGRESS', 'COMPLETED', 'M-1', storage)).toBe(false)
    expect(shouldOpenOnEnd('IN_PROGRESS', 'CANCELLED', 'M-2', storage)).toBe(true)
  })
})

describe('the viewer panel (ADR-0051)', () => {
  const caps = { maxViewText: 1024, maxViewBytes: 4096 }

  it('says why it cannot show a file, so Download is offered instead', () => {
    expect(notViewable(file({ kind: 'TEXT', size: 10 }), caps)).toBeNull()
    expect(notViewable(file({ kind: 'TEXT', size: 2048 }), caps)).toBe('tooBig')
    expect(notViewable(file({ kind: 'PDF', path: 'report.pdf', size: 5000 }), caps)).toBe('tooBig')
    expect(capOf(file({ kind: 'PDF', path: 'report.pdf' }), caps)).toBe(4096)
    expect(notViewable(file({ kind: 'OTHER', path: 'app.exe' }), caps)).toBe('unknownKind')
    expect(notViewable(file({ link: true }), caps)).toBe('link')
    expect(notViewable(file({ status: 'D' }), caps)).toBe('deleted')
  })

  it('shows an SVG as an image, never as a page', () => {
    expect(viewerOf(file({ kind: 'IMAGE', path: 'diagram.svg', size: 100 }), caps)).toBe('image')
  })

  it('finds the listed entry of a workshop file, and nothing for a file the listing does not name', () => {
    const notes = file({ path: 'notes.md', committed: false, status: '?' })
    const view = { branches: [branch('chris', { uncommitted: [notes] })] } as unknown as DeliverablesView
    expect(findFile(view, 'chris', 'notes.md', false)).toBe(notes)
    expect(findFile(view, 'chris', 'notes.md', true)).toBeNull()
    expect(findFile(view, 'nobody', 'notes.md', false)).toBeNull()
  })
})
