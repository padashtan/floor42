import { describe, expect, it } from 'vitest'
import type { ActionView } from '../api/types'
import { TYPED_MAX, proposalsOf, typedFor } from './proposals'

const action = (over: Partial<ActionView>): ActionView =>
  ({
    id: 'M-1:a1',
    ticket: 'T-1',
    agent: 'ada',
    category: 'EXECUTE',
    tool: 'Bash',
    summary: 'mvn test',
    stage: 'DENIED',
    ran: false,
    decisionBasis: 'shadow mode: proposed to the director',
    denialReason: 'POLICY',
    authorizedByOffice: false,
    result: null,
    requestedAt: '2026-11-16T09:00:00Z',
    updatedAt: '2026-11-16T09:00:00Z',
    runningSeconds: null,
    decisionBasisCode: 'trustShadow',
    decisionBasisParams: {},
    ...over,
  }) as ActionView

describe('the shadow proposals', () => {
  it('are the denied calls whose basis is the shadow one, in the order asked', () => {
    const list = [
      action({ id: 'a1' }),
      action({ id: 'a2', decisionBasisCode: 'defaultDeny' }),
      action({ id: 'a3', stage: 'AUTHORIZED', denialReason: null }),
      action({ id: 'a4', tool: 'Write', summary: 'notes.txt' }),
    ]
    expect(proposalsOf(list).map((a) => a.id)).toEqual(['a1', 'a4'])
  })

  it('type a shell command behind the bang, never a return', () => {
    expect(typedFor(action({}))).toBe('! mvn test')
    expect(typedFor(action({ summary: 'mvn test\r\nrm -rf /' }))).toBeNull()
  })

  it('type nothing for a write, a non-ASCII line or an overlong one', () => {
    expect(typedFor(action({ tool: 'Write', summary: 'notes.txt' }))).toBeNull()
    expect(typedFor(action({ summary: 'echo é' }))).toBeNull()
    expect(typedFor(action({ summary: 'x'.repeat(TYPED_MAX) }))).toBeNull()
    expect(typedFor(action({ summary: '   ' }))).toBeNull()
  })
})
