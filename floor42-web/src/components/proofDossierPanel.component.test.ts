// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProofDossier } from '../api/types'
import { setLang, t } from '../i18n'
import ProofDossierPanel from './ProofDossierPanel.vue'

const api = vi.hoisted(() => ({ proofDossier: vi.fn() }))
vi.mock('../api/client', () => ({
  api,
  ApiError: class ApiError extends Error {
    needsLogin = false
  },
}))

const section = <T>(items: T[] = []) => ({ count: items.length, items })
const dossier: ProofDossier = {
  format: 1,
  generator: 'Floor42',
  version: '1.1.0',
  generatedAt: '2026-11-13T12:00:00Z',
  statement: 'facts from the journal',
  mission: { id: 'M-1', title: 'T', objective: 'O', origin: 'SIMULATED', status: 'DONE', events: 3, real: 0, simulated: 3 },
  iam: false,
  identities: section(),
  delegation: null,
  brokerCalls: section(),
  refusals: section([
    {
      at: 'x',
      agent: 'ada',
      tool: 'Bash',
      reason: 'POLICY',
      detail: 'no push',
      identity: false,
      ref: { source: 'JOURNAL', origin: 'SIMULATED', events: [{ seq: 3, eventId: 'e3', type: 'ActionDenied' }] },
    },
  ]),
  approvals: section(),
  authorized: [],
  costs: { sessions: [{ agent: 'ada', session: 'S1', usd: 0, basis: 'SIMULATED', ref: { source: 'JOURNAL', basis: 'SIMULATED', origin: 'SIMULATED', events: [{ seq: 2, eventId: 'e2', type: 'AgentSessionEnded' }] } }] },
  checks: section(),
  deliverables: section(),
  diff: section(),
  masked: [],
  limits: [],
}

describe('the proof dossier panel', () => {
  beforeEach(() => setLang('en'))
  afterEach(() => vi.restoreAllMocks())

  it('shows the loading line, then the sections', async () => {
    api.proofDossier.mockResolvedValue(dossier)
    const w = mount(ProofDossierPanel, { props: { mission: 'M-1' } })
    expect(w.text()).toContain(t('dossier.loading'))
    await flushPromises()
    expect(w.text()).not.toContain(t('dossier.loading'))
    expect(w.text()).toContain(t('dossier.section.refusals'))
    expect(w.text()).toContain(t('dossier.iamOff'))
  })

  it('shows the failure message on an error', async () => {
    api.proofDossier.mockRejectedValue(new Error('boom'))
    const w = mount(ProofDossierPanel, { props: { mission: 'M-1' } })
    await flushPromises()
    expect(w.get('[role=alert]').text()).toBe(t('dossier.failed', { message: 'boom' }))
  })

  it('names the two downloads floor42-dossier-<id>.md and .json', async () => {
    api.proofDossier.mockResolvedValue(dossier)
    const names: string[] = []
    vi.stubGlobal('URL', { createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      names.push(this.download)
    })
    const w = mount(ProofDossierPanel, { props: { mission: 'M-1' } })
    await flushPromises()
    const buttons = w.findAll('.dossier-downloads button')
    expect(buttons.map((b) => b.text())).toEqual([t('dossier.downloadMd'), t('dossier.downloadJson')])
    await buttons[0]!.trigger('click')
    await buttons[1]!.trigger('click')
    expect(names).toEqual(['floor42-dossier-M-1.md', 'floor42-dossier-M-1.json'])
    vi.unstubAllGlobals()
  })
})
