// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import type { IamStatusView } from '../api/identity'
import { setLang } from '../i18n'
import IamSettings from './IamSettings.vue'

beforeAll(() => setLang('en'))
afterEach(() => vi.unstubAllGlobals())

it('shows the saved switch, not the running one, so a save keeps a pending change', async () => {
  const status: IamStatusView = {
    enabled: false,
    savedEnabled: true,
    health: 'OFF',
    code: null,
    reason: null,
    lastCheck: null,
    failedChecks: 0,
    restartNeeded: true,
    issuer: 'http://kc/realms/floor42',
    maxTokenHours: 8,
  }
  const puts: string[] = []
  vi.stubGlobal('fetch', async (_url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') puts.push(String(init.body))
    const body = init?.method === 'PUT' ? { restartNeeded: true } : status
    return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })
  })
  // The children fetch their own routes (sandbox, agents, sessions): stubbed here, tested in their own files.
  const w = mount(IamSettings, { global: { stubs: { IamChecks: true, IamSecretReplace: true } } })
  await flushPromises()
  const box = w.find('input[name="iam-enabled"]').element as HTMLInputElement
  expect(box.checked).toBe(true)
  await w.find('form').trigger('submit')
  await flushPromises()
  expect(puts).toHaveLength(1)
  expect(JSON.parse(puts[0]).enabled).toBe(true)
})
