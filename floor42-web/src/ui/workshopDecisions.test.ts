import { computed, nextTick, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import type { ChangeView, WorkshopView } from '../api/types'
import { useWorkshopDecisions } from './workshopDecisions'

vi.mock('../state/confirm', () => ({ confirmed: async () => true }))
vi.mock('../state/office', () => ({ workshopCall: (run: () => unknown) => run() }))
vi.mock('../api/workshopChanges', () => ({ workshopChangesApi: { discard: async () => ({}) } }))

const change = (path: string): ChangeView => ({ path, status: 'MODIFIED', added: 1, removed: 0 })

function setup() {
  const agent = ref<string | null>('a')
  const path = ref<string | null>('README.md')
  const cleared = vi.fn()
  const d = useWorkshopDecisions({
    mission: () => 'M-1',
    agent,
    path,
    changes: computed(() => [change('README.md'), change('b.txt')]),
    message: ref('m'),
    reason: ref(''),
    act: async (run) => (await run()) as WorkshopView | null,
    clearViewer: cleared,
  })
  return { agent, path, cleared, d }
}

describe('the workshop decisions', () => {
  it('forgets the unticked files when the director switches workspace', async () => {
    const { agent, d } = setup()
    d.unticked.value = ['README.md']
    expect(d.picked.value).toEqual(['b.txt'])
    agent.value = 'b'
    await nextTick()
    expect(d.unticked.value).toEqual([])
    expect(d.picked.value).toEqual(['README.md', 'b.txt'])
  })

  it('clears the viewer when the discarded file is the one it shows, not for another', async () => {
    const { cleared, d } = setup()
    await d.discard('b.txt')
    expect(cleared).not.toHaveBeenCalled()
    await d.discard('README.md')
    expect(cleared).toHaveBeenCalledTimes(1)
  })
})
