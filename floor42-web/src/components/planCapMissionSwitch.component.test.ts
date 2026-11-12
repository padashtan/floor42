// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import type { MissionView } from '../api/types'
import PlanCap from './PlanCap.vue'

const api = vi.hoisted(() => ({ settings: vi.fn(async () => []) }))
const request = vi.hoisted(() => vi.fn())
vi.mock('../api/client', () => ({ api }))
vi.mock('../api/request', () => ({ request }))
vi.mock('../ui/identities', () => ({ iamOn: async () => true }))

const m = (id: string) => ({ id, budgetUsd: 1, capDecided: true }) as unknown as MissionView

it('forgets the resources ticked for the previous mission when the mission changes', async () => {
  request.mockImplementation(async (_m: string, url: string) =>
    url === '/api/iam/resources'
      ? { resources: [{ name: 'r1', methods: ['GET'], pathPrefixes: ['/'] }], grants: {} }
      : { expiresAt: '2026-11-06T00:00:00Z', resources: [] },
  )
  const w = mount(PlanCap, { props: { mission: m('M-1'), busy: false } })
  await flushPromises()
  await w.find('[data-resource="r1"] input').setValue(true)
  await w.setProps({ mission: m('M-2') })
  await flushPromises()
  await w.find('button.recommended').trigger('click')
  expect(w.emitted('approve')!.at(-1)![0]).not.toHaveProperty('resources')
})
