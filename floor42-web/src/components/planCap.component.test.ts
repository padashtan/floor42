// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { MissionView } from '../api/types'
import PlanCap from './PlanCap.vue'

const api = vi.hoisted(() => ({ settings: vi.fn() }))
const request = vi.hoisted(() => vi.fn())
vi.mock('../api/client', () => ({ api }))
vi.mock('../api/request', () => ({ request }))
vi.mock('../ui/identities', () => ({ iamOn: async () => true }))

const mission = { id: 'M-1', budgetUsd: 1, capDecided: true } as unknown as MissionView
const catalogue = {
  resources: [{ name: 'demo-resource', baseUrl: 'http://127.0.0.1:7499', audience: 'demo-resource', scopes: [], methods: ['GET'], pathPrefixes: ['/claims'], maxResponseBytes: 65536, local: true }],
  grants: { 'agent-1': ['demo-resource'] },
}

/** IAM mode: the director picks, at approval, the broker resources the mission's agents may reach (none by default). */
describe('plan approval resources', () => {
  it('delegates no resource unless ticked, then sends the ticked one and shows it in the consent', async () => {
    api.settings.mockResolvedValue([])
    request.mockImplementation(async (_m: string, url: string) =>
      url === '/api/iam/resources'
        ? catalogue
        : { text: '', expiresAt: '2026-11-06T00:00:00Z', resources: url.includes('resources=demo-resource') ? ['demo-resource'] : [] },
    )
    const w = mount(PlanCap, { props: { mission, busy: false } })
    await flushPromises()
    const row = w.find('[data-resource="demo-resource"]')
    expect(row.text()).toContain('agent-1')
    await w.find('button.recommended').trigger('click')
    expect(w.emitted('approve')![0][0]).not.toHaveProperty('resources')

    await row.find('input').setValue(true)
    await flushPromises()
    expect(request).toHaveBeenCalledWith('GET', '/api/iam/missions/M-1/consent?resources=demo-resource')
    expect(w.text()).toContain('demo-resource')
    await w.find('button.recommended').trigger('click')
    expect(w.emitted('approve')![1][0]).toMatchObject({ budgetUsd: 1, resources: ['demo-resource'] })
  })
})
