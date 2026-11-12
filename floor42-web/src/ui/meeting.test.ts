import { describe, expect, it } from 'vitest'
import type { AgentView, MeetingView, MissionSummary } from '../api/types'
import { addressee, choosable, defaultMission, meetableMissions, seated } from './meeting'

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

const agent = (id: string): AgentView => ({
  id,
  name: id.toUpperCase(),
  role: 'Developer',
  provider: 'anthropic',
  model: 'm',
  effort: null,
  status: 'WORKING',
  suspended: false,
  workRole: 'DEVELOPER',
  atWork: false,
  stays: false,
  core: false,
})

const meeting = (id: string, missionId: string, open: boolean, attendees: MeetingView['attendees'] = []): MeetingView => ({
  id,
  mission: missionId,
  missionTitle: '',
  agenda: null,
  calledAt: '2026-11-01T10:00:00Z',
  closedAt: open ? null : '2026-11-01T10:30:00Z',
  open,
  attendees,
  notes: [],
  place: 'TABLE',
  spentUsdCharged: 0,
  rounds: [],
})

describe('the director’s meeting form', () => {
  it('meets only about missions that are not closed, starting on the board’s', () => {
    const all = [mission('M-1', 'COMPLETED'), mission('M-2', 'IN_PROGRESS'), mission('M-3', 'PLANNING'), mission('M-4', 'CANCELLED')]
    expect(meetableMissions(all).map((m) => m.id)).toEqual(['M-2', 'M-3'])
    expect(defaultMission(all, 'M-3')).toBe('M-3')
    expect(defaultMission(all, 'M-1')).toBe('M-2')
    expect(defaultMission([mission('M-1', 'COMPLETED')], null)).toBeNull()
  })

  it('lists the proposed agents first, the others after, in the roster’s order', () => {
    const roster = [agent('ana'), agent('bob'), agent('chris')]
    expect(choosable(roster, ['chris']).map((a) => a.id)).toEqual(['chris', 'ana', 'bob'])
  })

  it('counts as seated only those whose call under way has finished', () => {
    const m = meeting('MT-1', 'M-1', true, [
      { id: 'ana', name: 'Ana', status: 'IN_MEETING', finishing: false },
      { id: 'bob', name: 'Bob', status: 'EXECUTING', finishing: true },
      { id: 'chris', name: 'Chris', status: 'IN_MEETING', finishing: true },
    ])
    expect(seated(m)).toEqual({ seated: 1, of: 3 })
    expect(addressee(m, 'bob')).toBe('Bob')
    expect(addressee(m, null)).toBeNull()
  })
})
