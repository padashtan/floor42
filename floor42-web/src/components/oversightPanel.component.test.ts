// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { OversightReport } from '../api/types'
import { setLang, t } from '../i18n'
import OversightPanel from './OversightPanel.vue'

const api = vi.hoisted(() => ({ oversightReport: vi.fn() }))
vi.mock('../api/client', () => ({
  api,
  ApiError: class ApiError extends Error {
    needsLogin = false
  },
}))

const empty = { count: 0, items: [] }
const report: OversightReport = {
  format: 1,
  generator: 'Floor42',
  version: '1.3.0',
  generatedAt: '2026-11-13T12:00:00Z',
  statement: 'facts',
  mission: { id: 'M-1', title: 'T', origin: 'REAL', status: 'DONE', events: 2, real: 2, simulated: 0 },
  supervision: {
    count: 1,
    items: [{ at: 'x', kind: 'PLAN_APPROVED', actor: 'director', origin: 'REAL', events: [{ seq: 2, eventId: 'e2', type: 'MissionPlanApproved' }] }],
  },
  capabilities: empty,
  actions: empty,
  overrides: empty,
  masked: [],
  limits: ['signIns.notJournaled'],
}

describe('the oversight panel', () => {
  beforeEach(() => setLang('en'))

  it('reads the report of the open mission and shows the first question with its event', async () => {
    api.oversightReport.mockResolvedValue(report)
    const w = mount(OversightPanel, { props: { mission: 'M-1' } })
    expect(w.text()).toContain(t('oversight.loading'))
    await flushPromises()
    expect(api.oversightReport).toHaveBeenCalledWith('M-1')
    expect(w.text()).toContain(t('oversight.kind.PLAN_APPROVED'))
    expect(w.text()).toContain('#2 MissionPlanApproved')
  })

  it('links the two files of the daemon, named by the mission', async () => {
    api.oversightReport.mockResolvedValue(report)
    const w = mount(OversightPanel, { props: { mission: 'M-1' } })
    await flushPromises()
    const hrefs = w.findAll('.oversight-downloads a').map((a) => a.attributes('href'))
    expect(hrefs).toEqual([
      '/api/missions/M-1/oversight-report?format=md&download=true',
      '/api/missions/M-1/oversight-report?format=json&download=true',
    ])
  })

  it('says so when the report fails and when no mission is open', async () => {
    api.oversightReport.mockRejectedValue(new Error('boom'))
    const w = mount(OversightPanel, { props: { mission: 'M-1' } })
    await flushPromises()
    expect(w.get('[role=alert]').text()).toBe(t('oversight.failed', { message: 'boom' }))
    expect(mount(OversightPanel, { props: { mission: null } }).text()).toContain(t('oversight.noMission'))
  })
})
