import { reactive } from 'vue'
import { api } from '../api/client'
import { EventStream } from '../api/eventStream'
import type { ContextReading } from '../api/types'
import { office } from './office'

/**
 * Each agent's latest context reading (W18), pushed by /ws/context. A measurement of the moment kept in memory by the
 * daemon, never journaled: a copy of what it pushed, nothing derived.
 */
export const contexts = reactive<Record<string, ContextReading>>({})

let stream: EventStream<ContextReading> | null = null

export function startContext(): void {
  if (stream) return
  stream = new EventStream<ContextReading>(receive, () => {}, () => void refill(), '/ws/context')
  stream.open()
}

export function receive(reading: ContextReading): void {
  const before = contexts[reading.agent]
  // A late message of an older reading never replaces a newer one of the same session.
  if (before && before.session === reading.session && before.at > reading.at) return
  contexts[reading.agent] = reading
}

/** After a (re)connection: what was pushed meanwhile, for the sessions running now on the mission on the board. */
async function refill(): Promise<void> {
  const mission = office.mission
  if (!mission || mission.origin !== 'REAL') return
  for (const s of mission.sessions.filter((x) => x.open)) {
    try {
      const reading = await api.sessionContext(s.id)
      if (reading) receive(reading)
    } catch {
      // The session ended meanwhile (404): nothing to show.
    }
  }
}
