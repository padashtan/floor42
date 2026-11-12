// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import type { IamStatusView } from '../api/identity'
import { setLang } from '../i18n'
import IamSecretReplace from './IamSecretReplace.vue'

const SECRET = 'S3cr3t-value-xyz'
const status: IamStatusView = {
  enabled: true,
  health: 'REFUSED_TO_START',
  code: 'IAM_API_CREDENTIAL_REFUSED',
  reason: 'refused',
  lastCheck: null,
  failedChecks: 0,
  restartNeeded: false,
  issuer: 'http://kc/realms/floor42',
  maxTokenHours: 8,
  savedEnabled: true,
}

beforeAll(() => setLang('en'))
afterEach(() => vi.unstubAllGlobals())

function stub(res: Response) {
  const calls: { url: string; body: string }[] = []
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    calls.push({ url, body: String(init.body) })
    return res.clone()
  })
  return calls
}

async function fill(w: ReturnType<typeof mount>, kind: 'api' | 'daemon') {
  await w.find('button').trigger('click')
  const input = w.find('input')
  expect(input.attributes('type')).toBe('password')
  expect(input.attributes('autocomplete')).toBe('off')
  expect(input.attributes('spellcheck')).toBe('false')
  expect((input.element as HTMLInputElement).value).toBe('')
  await input.setValue(SECRET)
  await w.find('form').trigger('submit')
  await flushPromises()
  return kind
}

it.each(['api', 'daemon'] as const)('sends only the %s secret, clears it, shows no secret and says what happened', async (kind) => {
  const log = vi.spyOn(console, 'log')
  const err = vi.spyOn(console, 'error')
  const warn = vi.spyOn(console, 'warn')
  const calls = stub(new Response(JSON.stringify({ restartNeeded: true }), { status: 200, headers: { 'content-type': 'application/json' } }))
  const w = mount(IamSecretReplace, { props: { status, kind } })
  await fill(w, kind)
  expect(calls).toHaveLength(1)
  expect(calls[0].url).toBe(`/api/iam/secrets/${kind}`)
  expect(calls[0].url).not.toContain(SECRET)
  expect(JSON.parse(calls[0].body)).toEqual({ secret: SECRET })
  expect(w.emitted('saved')).toHaveLength(1)
  expect(w.html()).not.toContain(SECRET)
  expect(w.text()).toContain('restart is needed')
  expect(w.text().includes('within 60 s')).toBe(kind === 'api')
  for (const spy of [log, err, warn]) expect(JSON.stringify(spy.mock.calls)).not.toContain(SECRET)
})

it('empties the input after an error and keeps the form open', async () => {
  stub(new Response(JSON.stringify({ message: 'nope' }), { status: 400, headers: { 'content-type': 'application/json' } }))
  const w = mount(IamSecretReplace, { props: { status, kind: 'api' } })
  await fill(w, 'api')
  expect((w.find('input').element as HTMLInputElement).value).toBe('')
  expect(w.html()).not.toContain(SECRET)
  expect(w.find('.error').exists()).toBe(true)
  expect(w.emitted('saved')).toBeUndefined()
})
