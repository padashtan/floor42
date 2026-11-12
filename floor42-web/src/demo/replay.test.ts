import { describe, expect, it } from 'vitest'
import { Replay, type Tape } from './replay'

const tape: Tape = {
  format: 1,
  recordedAt: '2026-10-30T13:57:00Z',
  paceMs: 1500,
  steps: [
    { t: 0, kind: 'answers', answers: { '/api/questions': { status: 200, body: [] } } },
    { t: 100, kind: 'event', event: { type: 'MissionCreated' } },
    { t: 200, kind: 'answers', answers: { '/api/questions': { status: 200, body: [{ id: 'Q-1' }] } } },
    { t: 250, kind: 'event', event: { type: 'QuestionAsked' } },
    { t: 300, kind: 'wait', method: 'POST', path: '/api/questions/Q-1/answer', answer: 'ALLOW' },
    { t: 400, kind: 'response', path: '/api/questions/Q-1/answer', body: { id: 'Q-1', status: 'ANSWERED' } },
    { t: 500, kind: 'event', event: { type: 'TicketCompleted' } },
  ],
}

function playing() {
  let now = 0
  const replay = new Replay(tape, () => now)
  const events: string[] = []
  replay.listen((data) => events.push(JSON.parse(data).type))
  const at = (ms: number) => {
    now = ms
    replay.advance()
  }
  return { replay, events, at }
}

describe('the online demo replay', () => {
  it('answers a read with what the tape held at that point', async () => {
    const { replay, at } = playing()
    expect(await replay.read('/api/questions').json()).toEqual([])
    at(210)
    expect(await replay.read('/api/questions').json()).toEqual([{ id: 'Q-1' }])
  })

  it('gives the recording wall clock, stopped at a wait', () => {
    const { replay, at } = playing()
    const start = Date.parse(tape.recordedAt)
    at(120)
    expect(replay.tapeNow()).toBe(start + 120)
    at(1000)
    expect(replay.tapeNow()).toBe(start + 300)
  })

  it('answers the page first reads with the first recorded answers, even before their recorded time', async () => {
    const late: Tape = { ...tape, steps: [{ ...tape.steps[0], t: 244 } as Tape['steps'][number], ...tape.steps.slice(1)] }
    expect((await new Replay(late, () => 0).read('/api/questions')).status).toBe(200)
  })

  it('answers 404 for a path the tape never held', () => {
    expect(playing().replay.read('/api/usage').status).toBe(404)
  })

  it('plays events at their recorded time, and stops at the recorded wait', () => {
    const { events, at } = playing()
    at(150)
    expect(events).toEqual(['MissionCreated'])
    at(10_000)
    expect(events).toEqual(['MissionCreated', 'QuestionAsked'])
  })

  it('goes on once the visitor gives the recorded answer, with the recorded response', async () => {
    const { replay, events, at } = playing()
    at(10_000)
    const answered = replay.write('POST', '/api/questions/Q-1/answer', JSON.stringify({ answer: 'ALLOW' }))
    expect(answered.status).toBe(200)
    expect(await answered.json()).toEqual({ id: 'Q-1', status: 'ANSWERED' })
    // The clock resumes from the wait: the next event is 200 ms of tape later.
    at(10_100)
    expect(events).not.toContain('TicketCompleted')
    at(10_250)
    expect(events).toContain('TicketCompleted')
  })

  it('refuses another answer than the recorded one, and keeps waiting', () => {
    const { replay, events, at } = playing()
    at(10_000)
    const refused = replay.write('POST', '/api/questions/Q-1/answer', JSON.stringify({ answer: 'DENY' }))
    expect(refused.status).toBe(409)
    at(20_000)
    expect(events).not.toContain('TicketCompleted')
  })

  it('refuses any other change: the online demo is a recording', async () => {
    const refused = playing().replay.write('POST', '/api/agents', '{}')
    expect(refused.status).toBe(403)
    expect((await refused.json()).code).toBe('DEMO_READ_ONLY')
  })
})
