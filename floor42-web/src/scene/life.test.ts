import { describe, expect, it } from 'vitest'
import type { EventView } from '../api/types'
import {
  daylight,
  easeInOutCubic,
  easeOutBack,
  gestureFor,
  handsFor,
  milestoneOf,
  toolRunning,
  turn,
} from './life'

function event(partial: Partial<EventView>): EventView {
  return {
    eventId: 'e1',
    occurredAt: '2026-10-20T10:00:00Z',
    origin: 'REAL',
    actor: 'system:floor42',
    mission: 'M-1',
    type: 'ActionExecuted',
    code: '',
    params: {},
    agent: 'chris',
    ticket: 'T-1',
    question: null,
    action: null,
    tool: null,
    category: null,
    summary: null,
    stage: null,
    ran: false,
    ...partial,
  }
}

const board: Record<string, string> = { 'T-1': 'dehren' }
const assignee = (ticket: string) => board[ticket]

describe('gestures', () => {
  it('makes an agent frustrated only when Floor42 denies one of its actions', () => {
    expect(gestureFor(event({ type: 'ActionDenied' }), assignee)).toEqual({ agent: 'chris', gesture: 'frustrated' })
    expect(gestureFor(event({ type: 'ActionDenied', agent: null }), assignee)).toBeNull()
    expect(gestureFor(event({ type: 'ActionRequested' }), assignee)).toBeNull()
    expect(gestureFor(event({ type: 'ActionFailed', ran: true }), assignee)).toBeNull()
  })

  it('makes the assignee of a completed ticket celebrate, looked up on the board', () => {
    expect(gestureFor(event({ type: 'TicketCompleted', agent: null }), assignee)).toEqual({
      agent: 'dehren',
      gesture: 'celebrate',
    })
    // A ticket the board does not know: nobody celebrates in its name.
    expect(gestureFor(event({ type: 'TicketCompleted', agent: null, ticket: 'T-9' }), assignee)).toBeNull()
    expect(gestureFor(event({ type: 'TicketStarted' }), assignee)).toBeNull()
  })

  it('marks the plan approval and the mission completion as milestones, nothing else', () => {
    expect(milestoneOf(event({ type: 'MissionPlanApproved' }))).toBe('plan-approved')
    expect(milestoneOf(event({ type: 'MissionCompleted' }))).toBe('mission-completed')
    expect(milestoneOf(event({ type: 'MissionCancelled' }))).toBeNull()
    expect(milestoneOf(event({ type: 'TicketCompleted' }))).toBeNull()
  })
})

describe('hands at the desk', () => {
  it('reads for read tools and types for everything else', () => {
    expect(handsFor('READ')).toBe('read')
    expect(handsFor('WRITE')).toBe('type')
    expect(handsFor('EXECUTE')).toBe('type')
    expect(handsFor(null)).toBe('type')
  })

  it('works at full speed only while a tool runs', () => {
    expect(toolRunning('EXECUTING')).toBe(true)
    expect(toolRunning('WORKING')).toBe(false)
    expect(toolRunning('WAITING_FOR_AUTHORIZATION')).toBe(false)
  })
})

describe('daylight', () => {
  it('is neutral in the day, warm in the evening and at night', () => {
    expect(daylight(12)).toEqual({ warmth: 0, level: 1 })
    expect(daylight(20).warmth).toBe(1)
    expect(daylight(18.5).warmth).toBeCloseTo(0.5)
    expect(daylight(3).warmth).toBeCloseTo(0.8)
    expect(daylight(9).warmth).toBe(0)
    expect(daylight(20).level).toBeLessThan(daylight(12).level)
  })

  it('wraps hours outside 0..24', () => {
    expect(daylight(36)).toEqual(daylight(12))
    expect(daylight(-4)).toEqual(daylight(20))
  })
})

describe('easing', () => {
  it('starts at 0, ends at 1, and clamps', () => {
    for (const f of [easeInOutCubic, easeOutBack]) {
      expect(f(0)).toBeCloseTo(0)
      expect(f(1)).toBeCloseTo(1)
      expect(f(2)).toBeCloseTo(1)
      expect(f(-1)).toBeCloseTo(0)
    }
    expect(easeInOutCubic(0.5)).toBeCloseTo(0.5)
    expect(easeOutBack(0.7)).toBeGreaterThan(1)
  })

  it('turns the short way round', () => {
    expect(turn(0, Math.PI / 2)).toBeCloseTo(Math.PI / 2)
    expect(turn(Math.PI * 0.9, -Math.PI * 0.9)).toBeCloseTo(Math.PI * 0.2)
    expect(turn(0, Math.PI * 4)).toBeCloseTo(0)
  })
})
