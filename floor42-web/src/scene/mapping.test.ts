import { describe, expect, it } from 'vitest'
import type { EventView, TicketView } from '../api/types'
import { activityOf, boardColumns, glyphFor, machineForExecution, machineReaction, presentInOffice, stationFor } from './mapping'

function event(partial: Partial<EventView>): EventView {
  return {
    eventId: 'e1',
    occurredAt: '2026-10-20T10:00:00Z',
    origin: 'SIMULATED',
    actor: 'system:floor42',
    mission: 'M-1',
    type: 'ActionExecuted',
    code: 'ActionExecuted.authorized',
    params: {},
    agent: 'chris',
    ticket: 'T-1',
    question: null,
    action: 'M-1:toolu_1',
    tool: 'Bash',
    category: 'EXECUTE',
    summary: 'git status',
    stage: 'EXECUTED',
    ran: true,
    ...partial,
  }
}

describe('machine reactions', () => {
  it('lights a machine only for an execution, never for a request or an authorization', () => {
    expect(machineReaction(event({}))).toEqual({ machine: 'terminal-1', state: 'ACTIVE' })
    expect(machineReaction(event({ type: 'ActionAuthorized', ran: false, stage: 'AUTHORIZED' }))).toBeNull()
    expect(machineReaction(event({ type: 'ActionRequested', ran: false, stage: 'REQUESTED' }))).toBeNull()
  })

  it('shows a failed execution as an error on the same machine', () => {
    expect(machineReaction(event({ type: 'ActionFailed', stage: 'FAILED' }))).toEqual({
      machine: 'terminal-1',
      state: 'ERROR',
    })
  })

  it('sends builds and tests to the mainframe and reads to the archive', () => {
    expect(machineForExecution('EXECUTE', './mvnw -q test')).toBe('mainframe-1')
    expect(machineForExecution('EXECUTE', 'npm run build')).toBe('mainframe-1')
    expect(machineForExecution('EXECUTE', 'git status')).toBe('terminal-1')
    expect(machineForExecution('EXECUTE', 'gotest.sh')).toBe('terminal-1')
    expect(machineForExecution('READ', 'README.md')).toBe('archive-1')
    expect(machineForExecution('NETWORK', 'https://example.org')).toBeNull()
  })

  it('runs the printer when a deliverable is submitted', () => {
    expect(machineReaction(event({ type: 'TicketSubmittedForReview', ran: false, action: null }))).toEqual({
      machine: 'printer-1',
      state: 'ACTIVE',
    })
  })
})

describe('agent placement', () => {
  it('sends an agent waiting for the director to the director desk', () => {
    expect(stationFor('WAITING_FOR_DIRECTOR')).toBe('director-front')
    expect(glyphFor('WAITING_FOR_DIRECTOR')).toBe('wait')
  })

  it('keeps a working agent at its desk and an available one in the lounge', () => {
    expect(stationFor('EXECUTING')).toBe('desk')
    expect(stationFor('REVIEWING')).toBe('desk')
    expect(stationFor('AVAILABLE')).toBe('lounge')
    // A committee in session sits at the meeting table, whatever the member's own desk.
    expect(stationFor('IN_COMMITTEE')).toBe('conference-seat')
    // So does a retrospective: those who worked meet at the table once the mission has ended.
    expect(stationFor('IN_RETRO')).toBe('table-seat')
    expect(stationFor('IN_MEETING')).toBe('table-seat')
    expect(glyphFor('IN_MEETING')).toBe('work')
    expect(glyphFor('AVAILABLE')).toBe('idle')
  })
})

describe('agents between two sessions', () => {
  it('keeps a resident of the mission in progress at its desk, idle, and sends any other available agent to the lounge', () => {
    expect(presentInOffice('AVAILABLE')).toBe(true)
    expect(stationFor('AVAILABLE', true)).toBe('desk')
    expect(stationFor('AVAILABLE', false)).toBe('lounge')
    expect(stationFor('AVAILABLE')).toBe('lounge')
    // Work calls it back to its desk, whatever it was doing.
    expect(stationFor('WORKING', false)).toBe('desk')
    // The emblem stays the idle one: nothing says it works.
    expect(glyphFor('AVAILABLE')).toBe('idle')
  })

  it('never keeps an agent of a suspended mission, and changes nothing for the others', () => {
    expect(presentInOffice('AVAILABLE', true)).toBe(false) // suspended by the director: out
    expect(presentInOffice('PAUSED')).toBe(false)
    expect(presentInOffice('WORKING', false)).toBe(true)
    expect(stationFor('IN_COMMITTEE', true)).toBe('conference-seat')
    expect(stationFor('IN_MEETING', true)).toBe('table-seat')
  })
})

describe('mission board', () => {
  const t = (id: string, status: TicketView['status']): TicketView => ({
    id,
    title: id,
    description: '',
    priority: 'NORMAL',
    status,
    assignee: null,
    dependsOn: [],
    acceptanceCriteria: [],
    deliverable: null,
    reviews: [],
    directives: [],
    consultations: [],
    createdAt: '2026-10-21T10:00:00Z',
    startedAt: null,
    endedAt: null,
    kind: 'WORK',
    waitingFor: null,
    environmentBlocker: null,
    node: null,
    toRecheck: false,
  })

  it('reflects persisted ticket states and drops cancelled tickets', () => {
    const cols = boardColumns([t('T-1', 'DONE'), t('T-2', 'IN_REVIEW'), t('T-3', 'TODO'), t('T-4', 'CANCELLED')])
    expect(cols).toEqual({
      proposed: [],
      todo: ['T-3'],
      doing: ['T-2'],
      done: ['T-1'],
      inReview: ['T-2'],
      current: null,
      assignees: {},
    })
  })

  it('remembers who each ticket is assigned to, except dropped tickets', () => {
    const cols = boardColumns([
      { ...t('T-1', 'DONE'), assignee: 'chris' },
      { ...t('T-2', 'TODO'), assignee: null },
      { ...t('T-3', 'CANCELLED'), assignee: 'dehren' },
    ])
    expect(cols.assignees).toEqual({ 'T-1': 'chris' })
  })

  it('shows proposals apart and marks the ticket in progress as current', () => {
    const cols = boardColumns([t('T-1', 'PROPOSED'), t('T-2', 'IN_PROGRESS'), t('T-3', 'PROPOSED')])
    expect(cols.proposed).toEqual(['T-1', 'T-3'])
    expect(cols.todo).toEqual([])
    expect(cols.current).toBe('T-2')
  })
})

describe('activity line over an agent', () => {
  const ev = (type: string, agent: string | null = 'chris'): EventView => ({
    eventId: 'e',
    occurredAt: '2026-10-20T00:00:00Z',
    origin: 'REAL',
    actor: 'agent:chris',
    mission: 'M-1',
    type,
    code: type,
    params: {},
    agent,
    ticket: null,
    question: null,
    action: null,
    tool: null,
    category: null,
    summary: null,
    stage: null,
    ran: false,
  })

  it('uses the journal words without the agent name', () => {
    expect(activityOf(ev('ActionRequested'), 'chris requests Read: README.md')).toEqual({
      text: 'requests Read: README.md',
      tone: 'wait',
    })
    expect(activityOf(ev('ActionDenied'), 'Floor42 denies chris Write notes.txt')?.tone).toBe('alert')
    expect(activityOf(ev('QuestionAsked'), 'Blocking question Q-1 for the director: Allow chris to use Bash?')).toEqual({
      text: 'waits for you: Allow chris to use Bash?',
      tone: 'wait',
    })
  })

  it('shows the line the screen hands in, in its language', () => {
    expect(
      activityOf(
        ev('QuestionAsked'),
        'Question bloquante Q-1 pour le directeur : Autoriser Bash ?',
        (subject) => 'vous attend : ' + subject,
      )?.text,
    ).toBe('vous attend : Autoriser Bash ?')
    expect(activityOf(ev('TicketStarted'), 'chris commence T-1')?.text).toBe('commence T-1')
  })

  it('shortens long lines and ignores events about nobody', () => {
    expect(activityOf(ev('ActionExecuted'), 'chris ' + 'x'.repeat(80))?.text.length).toBe(46)
    expect(activityOf(ev('MissionCreated', null), 'New mission')).toBeNull()
  })
})

describe('presence in the office', () => {
  it('keeps every agent with recorded work in the office', () => {
    for (const status of [
      'ASSIGNED',
      'WORKING',
      'REVIEWING',
      'IN_COMMITTEE',
      'IN_RETRO',
      'IN_MEETING',
      'WAITING_FOR_AUTHORIZATION',
      'EXECUTING',
      'WAITING_FOR_DIRECTOR',
    ] as const) {
      expect(presentInOffice(status), status).toBe(true)
    }
  })

  it('sends home an agent with work only on a suspended mission, and keeps an idle one in the office', () => {
    expect(presentInOffice('AVAILABLE')).toBe(true)
    expect(presentInOffice('PAUSED')).toBe(false)
  })
})
