// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { beforeAll, describe, expect, it } from 'vitest'
import type { Link } from '../api/identity'
import { setLang } from '../i18n'
import TrustChain from './TrustChain.vue'

beforeAll(() => setLang('en'))

const link = (kind: Link['kind'], label: string, extra: Partial<Link> = {}): Link => ({
  kind,
  label,
  detail: '',
  at: '2026-11-05T10:00:00Z',
  ok: true,
  code: null,
  ...extra,
})

const links: Link[] = [
  link('DIRECTOR_SIGN_IN', 'director', { detail: 'PKCE' }),
  link('DELEGATION', 'M-3'),
  link('AGENT_TOKEN', 'floor42-agent-ada', { detail: 'jti jti-1, issuer http://kc, until 2026-11-05T18:00:00Z' }),
  link('PROCESS_BINDING', 'pid 4242'),
  link('BROKER_TOKEN', 'github', { detail: 'audience github' }),
  link('REFUSAL', 'WRONG_PROCESS', { ok: false, code: 'WRONG_PROCESS' }),
]

describe('the trust chain of a session', () => {
  const w = mount(TrustChain, { props: { launch: 'L1', links } })

  it('keeps the links in order, left to right', () => {
    expect(w.findAll('li').map((n) => n.attributes('data-kind'))).toEqual(links.map((l) => l.kind))
  })

  it('shows a refusal in red with its code', () => {
    const refused = w.findAll('li.refused')
    expect(refused).toHaveLength(1)
    expect(refused[0]!.find('.code').text()).toBe('WRONG_PROCESS')
  })

  it('never shows a token', () => {
    expect(w.text()).not.toMatch(/eyJ[\w-]+\.[\w-]+\.[\w-]+/)
    expect(w.html()).not.toMatch(/eyJ/)
  })

  it('says so when the session has no chain', () => {
    expect(mount(TrustChain, { props: { launch: 'L2', links: [] } }).text()).toContain('No chain recorded')
  })
})
