// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { proofApi, type ProofView } from '../api/proof'
import { setLang } from '../i18n'
import ProvePanel from './ProvePanel.vue'
import SectionedPanel from './SectionedPanel.vue'

beforeAll(() => setLang('en'))
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

const view = (running: boolean) =>
  ({ mission: 'M-1', running, status: 'PASSED', worktrees: [], directorSteps: 0, targets: '', verdict: null, sandboxWorks: true, runError: null }) as unknown as ProofView

describe('the Prove panel', () => {
  it('does not poll again when the panel is gone before a run answers', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const proof = vi.spyOn(proofApi, 'proof').mockResolvedValue(view(false))
    let answer: (v: ProofView) => void = () => {}
    const run = vi.spyOn(proofApi, 'run').mockReturnValue(new Promise<ProofView>((resolve) => (answer = resolve)))
    const w = mount(ProvePanel, { props: { mission: 'M-1' } })
    await flushPromises()
    w.findComponent(SectionedPanel).vm.$emit('choose', 'run')
    await flushPromises()
    await w.find('button.action.primary').trigger('click')
    expect(run).toHaveBeenCalledTimes(1)
    w.unmount()
    // The run answers as a running one: a live panel would read again after a second.
    proof.mockResolvedValue(view(true))
    answer(view(true))
    await flushPromises()
    const calls = proof.mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    expect(proof.mock.calls.length).toBe(calls)
  })
})
