import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import { api } from '../api/client'
import type { PracticeView } from '../api/types'
import { onLiveEvent } from './office'
import { send, tutorial } from './tutorial'

/** How often the guide reads the rehearsal again while it is shown. */
export const POLL_MS = 3000

/**
 * The guide's view of its rehearsal, read from the daemon rather than remembered by the page (ADR-0045): whether its
 * script still runs, and whether the director decided on its change in the workshop. So a guide hidden, reloaded or
 * reopened after a restart of the daemon picks up where things really are.
 */
export function useRehearsal(mission: () => string | null): { state: Ref<PracticeView['state'] | null> } {
  const state = ref<PracticeView['state'] | null>(null)

  async function refresh(): Promise<void> {
    const id = mission()
    if (!id) return void (state.value = null)
    try {
      const [view, workshop] = await Promise.all([api.practice(id), api.workshop(id)])
      if (mission() !== id) return
      state.value = view.state
      tutorial.decided = workshop.interventions.some((i) => i.kind === 'PATCH_ACCEPTED' || i.kind === 'PATCH_REJECTED')
    } catch {
      state.value = null
    }
  }

  let timer: number | undefined
  const off = onLiveEvent((e) => {
    if (e.mission && e.mission === mission()) void refresh()
  })
  onMounted(() => {
    void refresh()
    timer = window.setInterval(() => void refresh(), POLL_MS)
  })
  onBeforeUnmount(() => {
    off()
    window.clearInterval(timer)
  })
  return { state }
}

/** The director abandons a stopped rehearsal (their click): it is cancelled if still open, and the guide starts over. */
export async function abandonRehearsal(id: string): Promise<void> {
  const { status } = await api.mission(id)
  if (status !== 'COMPLETED' && status !== 'CANCELLED') {
    await api.cancel(id, 'Abandoned by the director: the practice mission had stopped')
  }
  tutorial.decided = false
  send({ kind: 'practiceLost' })
}
