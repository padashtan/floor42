// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeAll, expect, it, vi } from 'vitest'
import { setLang } from '../i18n'
import WorkshopPanel from './WorkshopPanel.vue'

const api = vi.hoisted(() => ({
  workshop: vi.fn(),
  workshopFiles: vi.fn(async () => []),
  acceptPatch: vi.fn(),
}))
const state = vi.hoisted(() => ({ office: null as unknown }))
vi.mock('../api/client', () => ({ api }))
vi.mock('../state/office', async () => {
  const { reactive } = await import('vue')
  state.office = reactive({ mission: null })
  return { office: state.office, onLiveEvent: () => () => {}, workshopCall: async (run: () => unknown) => run() }
})

beforeAll(() => setLang('en'))

const view = { workspaces: [{ agent: 'ada', branch: 'b', baseCommit: 'abcdefgh1', head: 'abcdefgh1', changes: [] }], interventions: [] }
const mission = (id: string) => ({ id, title: `T${id}`, tickets: [], sessions: [] })

it('refuses to accept once the mission on screen changed, and never commits the other mission', async () => {
  const office = state.office as { mission: unknown }
  office.mission = mission('M-1')
  api.workshop.mockImplementation(async (id: string) => (id === 'M-1' ? view : new Promise(() => {})))
  const w = mount(WorkshopPanel, {
    global: {
      stubs: {
        WorkshopActions: { template: '<button class="acc" @click="$emit(\'accept\')" />' },
        WorkshopChecks: true,
        WorkshopFiles: true,
        WorkshopTrace: true,
        WorkshopViewer: true,
      },
    },
  })
  await flushPromises()
  office.mission = mission('M-2')
  await flushPromises()
  // The view of M-2 never loaded: the Accept button may be gone, or it must do nothing.
  if (w.find('.acc').exists()) await w.find('.acc').trigger('click')
  expect(api.acceptPatch).not.toHaveBeenCalled()
  w.unmount()
})
