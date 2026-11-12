// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { setLang } from '../i18n'
import IdentitiesPanel from './IdentitiesPanel.vue'

beforeAll(() => setLang('en'))
afterEach(() => vi.unstubAllGlobals())

const claims = (iss: string) => ({
  iss,
  azp: 'floor42-agent-ada',
  // A hostile or buggy value: the screen must still never show something shaped like a token.
  note: 'eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl',
  floor42: { launch: 'L1', mission: 'M-3', ticket: 'T-7' },
})

const token = (iss: string, origin: string) => ({
  agent: 'ada',
  session: 'L1',
  launch: 'L1',
  jti: 'jti-1',
  header: { alg: 'RS256', typ: 'JWT', kid: 'k1' },
  claims: claims(iss),
  signaturePrefix: 'c2lnbmF0dXJl-more-than-eight',
  expiresAt: '2026-11-05T18:00:00Z',
  origin,
})

function serve(iss: string, origin: string) {
  const answers: Record<string, unknown> = {
    '/api/iam/status': { enabled: true },
    '/api/iam/agents': [{ agent: 'ada', clientId: 'floor42-agent-ada', serviceAccountId: 's', enabled: true, revoked: false }],
    '/api/iam/tokens': [token(iss, origin)],
    '/api/iam/refusals': [{ at: '2026-11-05T10:00:03Z', code: 'WRONG_PROCESS', jti: 'jti-1', launch: 'L1' }],
    '/api/sessions/L1/trust-chain': {
      links: [
        { kind: 'AGENT_TOKEN', label: 'floor42-agent-ada', detail: 'jti jti-1', at: null, ok: true, code: null },
        { kind: 'REFUSAL', label: 'WRONG_PROCESS', detail: '', at: null, ok: false, code: 'WRONG_PROCESS' },
      ],
    },
  }
  vi.stubGlobal('fetch', async (url: string) => new Response(JSON.stringify(answers[url] ?? {}), { status: 200 }))
}

describe('the identities panel', () => {
  it('renders no string shaped like a token', async () => {
    serve('http://kc/realms/floor42', 'REAL')
    const w = mount(IdentitiesPanel)
    await flushPromises()
    await w.find('details').trigger('toggle')
    const everything = w.text() + w.html()
    expect(everything).not.toMatch(/eyJ[\w-]+\.[\w-]+\.[\w-]+/)
    expect(w.find('.signature').text()).toBe('c2lnbmF0...')
    expect(w.text()).toContain('ada acts for the director on M-3/T-7')
  })

  it('shows a refusal in red with its code, and the SIMULATED badge of a simulated issuer', async () => {
    serve('urn:floor42:simulated', 'SIMULATED')
    const w = mount(IdentitiesPanel)
    await flushPromises()
    expect(w.find('.badge.simulated').text()).toBe('SIMULATED')
    expect(w.find('.trust-chain li.refused .code').text()).toBe('WRONG_PROCESS')
    expect(w.find('.refusal-row').attributes('data-code')).toBe('WRONG_PROCESS')
  })

  it('offers the audit export of each mission', async () => {
    serve('http://kc/realms/floor42', 'REAL')
    const w = mount(IdentitiesPanel)
    await flushPromises()
    expect(w.find('a.export-link').attributes('href')).toBe('/api/missions/M-3/identity-audit')
  })

  it('says IAM mode is off instead of listing anything', async () => {
    vi.stubGlobal('fetch', async () => new Response(JSON.stringify({ enabled: false }), { status: 200 }))
    const w = mount(IdentitiesPanel)
    await flushPromises()
    expect(w.find('.iam-off').exists()).toBe(true)
  })

  it('keeps the known agents and says why while Keycloak is unreachable', async () => {
    const answers: Record<string, unknown> = {
      '/api/iam/status': { enabled: true, health: 'UNREACHABLE' },
      '/api/agents': [{ id: 'ada', name: 'Ada' }],
      '/api/iam/tokens': [],
      '/api/iam/refusals': [],
    }
    vi.stubGlobal('fetch', async (url: string) =>
      url === '/api/iam/agents' ? new Response('{}', { status: 503 }) : new Response(JSON.stringify(answers[url] ?? {}), { status: 200 }),
    )
    const w = mount(IdentitiesPanel)
    await flushPromises()
    expect(w.find('.keycloak-down').text()).toContain('Keycloak is unreachable: the agents’ Keycloak clients cannot be read now.')
    expect(w.find('[data-agent="ada"]').exists()).toBe(true)
    expect(w.find('.error').exists()).toBe(false)
  })

  it('says the tokens and refusals could not be read instead of showing empty lists', async () => {
    const answers: Record<string, unknown> = {
      '/api/iam/status': { enabled: true, health: 'UNREACHABLE' },
      '/api/agents': [{ id: 'ada', name: 'Ada' }],
    }
    vi.stubGlobal('fetch', async (url: string) =>
      ['/api/iam/agents', '/api/iam/tokens', '/api/iam/refusals'].includes(url)
        ? new Response('{}', { status: 503 })
        : new Response(JSON.stringify(answers[url] ?? {}), { status: 200 }),
    )
    const w = mount(IdentitiesPanel)
    await flushPromises()
    expect(w.find('.tokens-unread').text()).toContain('could not be read')
  })
})
