// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { setLang } from '../i18n'
import IamChecks from './IamChecks.vue'

beforeAll(() => setLang('en'))
afterEach(() => vi.unstubAllGlobals())

it('says IAM is blocked and why when Keycloak refuses the floor42-api credential', async () => {
  vi.stubGlobal('fetch', async (url: string) => new Response(String(url).includes('sessions') ? '[]' : '{"sandbox":{}}', { status: 200, headers: { 'content-type': 'application/json' } }))
  const status = {
    enabled: true,
    health: 'REFUSED_TO_START' as const,
    code: 'IAM_API_CREDENTIAL_REFUSED',
    reason: "Keycloak refuses Floor42's floor42-api credential: re-enter its secret in Settings",
    lastCheck: null,
    failedChecks: 2,
    restartNeeded: false,
    issuer: 'http://kc/realms/floor42',
    maxTokenHours: 8,
    savedEnabled: false,
  }
  const w = mount(IamChecks, { props: { status } })
  await flushPromises()
  expect(w.text()).toContain('IAM blocked: Keycloak refuses Floor42')
  expect(w.text()).not.toContain('refused to start')
})
