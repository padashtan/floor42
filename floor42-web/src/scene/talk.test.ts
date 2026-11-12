import { describe, expect, it } from 'vitest'
import type { AgentPresence } from './labels'
import { Talk } from './talk'

const agent = (id: string, status: AgentPresence['status']): AgentPresence => ({ id, name: id, status })

/**
 * Characterisation of the chat table, step by step: who thinks, who joins as listener, who leaves when called away,
 * with one table (one pair) or two. Each line: the time, the conversations after the step and who had to walk.
 */
describe('the chat table', () => {
  it('starts, joins and ends conversations as agents think, idle and get called away', () => {
    const lines: string[] = []
    for (const pairs of [1, 2]) {
      let night = false
      const talk = new Talk({ night: () => night, idle: (a) => a.status === 'AVAILABLE' })
      const steps: [number, AgentPresence[]][] = [
        [1, [agent('a', 'WORKING'), agent('b', 'WORKING'), agent('c', 'AVAILABLE')]],
        [8, [agent('a', 'WORKING'), agent('b', 'WORKING'), agent('c', 'AVAILABLE')]],
        [9, [agent('a', 'WORKING'), agent('b', 'WORKING'), agent('c', 'AVAILABLE'), agent('d', 'AVAILABLE')]],
        [10, [agent('a', 'WORKING'), agent('b', 'WORKING'), agent('c', 'WORKING'), agent('d', 'AVAILABLE')]],
        [11, [agent('a', 'PAUSED'), agent('b', 'WORKING'), agent('c', 'WORKING'), agent('d', 'AVAILABLE')]],
        [20, [agent('a', 'AVAILABLE'), agent('b', 'WORKING'), agent('c', 'WORKING'), agent('d', 'AVAILABLE')]],
        [21, [agent('a', 'AVAILABLE'), agent('b', 'WORKING'), agent('c', 'WORKING'), agent('d', 'AVAILABLE')]],
      ]
      for (const [t, agents] of steps) {
        if (t === 21) night = true
        if (t === 20) talk.lastSign.set('c', 19)
        const moved = talk.converse(t, agents, pairs)
        const chats = [...talk.chats].map(([p, c]) => `${p}:${c.thinker}+${c.listener ?? '-'}`).join(' ')
        lines.push(`pairs ${pairs} t=${t}: ${chats || 'none'} | moved ${[...moved].join(',') || 'nobody'}`)
      }
    }
    expect(lines.join('\n')).toMatchSnapshot()
  })
})
