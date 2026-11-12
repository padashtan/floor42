import { describe, expect, it } from 'vitest'
import type { MissionSummary, ProjectView } from '../api/types'
import { BOARD_EMPTY, BOARD_KEY, boardChoice, boardMission, folderName, keepBoardChoice, recentProjects } from './recent'

function memory(): Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return {
    data,
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
    removeItem: (k) => void data.delete(k),
  }
}

const mission = (id: string, status: string): MissionSummary => ({ agents: [],
  id,
  title: id,
  status,
  origin: 'REAL',
  tickets: 0,
  ticketsDone: 0,
  openQuestions: 0,
  archived: false,
})

const project = (path: string, extra: Partial<ProjectView> = {}): ProjectView => ({
  path,
  missions: ['M-1'],
  lastTitle: 't',
  office: false,
  exists: true,
  git: null,
  lastMission: 'M-1',
  lastActivity: null,
  pinned: false,
  forgotten: false,
  ...extra,
})

describe('the mission on the board', () => {
  const missions = [mission('M-1', 'IN_PROGRESS'), mission('M-2', 'COMPLETED'), mission('M-3', 'SUSPENDED')]

  it('is the most recent active one unless the director chose another', () => {
    expect(boardMission(missions, null)?.id).toBe('M-1')
    expect(boardMission(missions, 'M-3')?.id).toBe('M-3')
    expect(boardMission(missions, 'M-9')?.id).toBe('M-1')
    expect(boardMission([mission('M-1', 'COMPLETED')], null)?.id).toBe('M-1')
    expect(boardMission([], null)).toBeNull()
  })

  it('never brings an archived mission back, chosen or automatic', () => {
    const gone = { ...mission('M-4', 'IN_PROGRESS'), archived: true }
    expect(boardMission([mission('M-1', 'COMPLETED'), gone], null)?.id).toBe('M-1')
    expect(boardMission([mission('M-1', 'COMPLETED'), gone], 'M-4')?.id).toBe('M-1')
    expect(boardMission([gone], null)).toBeNull()
  })

  it('stays empty on the explicit empty choice, which survives a reload', () => {
    expect(boardMission(missions, BOARD_EMPTY)).toBeNull()
    const store = memory()
    keepBoardChoice(BOARD_EMPTY, store)
    expect(boardChoice(store)).toBe(BOARD_EMPTY)
  })

  it('is kept in this browser only, and forgotten on the automatic choice', () => {
    const store = memory()
    keepBoardChoice('M-3', store)
    expect(store.data.get(BOARD_KEY)).toBe('M-3')
    expect(boardChoice(store)).toBe('M-3')
    keepBoardChoice(null, store)
    expect(boardChoice(store)).toBeNull()
  })
})

describe('recent projects', () => {
  it('leaves out forgotten ones and the projects Floor42 made for ideas, keeping the server order', () => {
    const list = [project('C:/b', { pinned: true }), project('C:/a'), project('C:/x', { forgotten: true }), project('C:/i', { office: true })]
    expect(recentProjects(list).map((p) => p.path)).toEqual(['C:/b', 'C:/a'])
  })

  it('names a folder by its last part, on Windows and POSIX', () => {
    expect(folderName('C:\\Users\\me\\shop\\')).toBe('shop')
    expect(folderName('/home/me/shop')).toBe('shop')
  })
})
