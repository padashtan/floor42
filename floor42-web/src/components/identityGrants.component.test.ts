// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import type { ResourceCatalogueView } from '../api/identity'
import { setLang } from '../i18n'
import IdentityGrants from './IdentityGrants.vue'

const resource = (name: string) => ({
  name,
  baseUrl: `https://${name}.example`,
  audience: name,
  scopes: [],
  methods: ['GET'],
  pathPrefixes: ['/'],
  maxResponseBytes: 1000,
  local: false,
})

let view: ResourceCatalogueView

beforeAll(() => setLang('en'))
afterEach(() => vi.unstubAllGlobals())

/** The daemon: GET answers the catalogue, PUT grants answers the new one, or says OpenFGA is out. */
function daemon(down = false) {
  const puts: unknown[] = []
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    if (init.method === 'PUT') {
      if (down) return new Response(JSON.stringify({ code: 'FGA_UNAVAILABLE', message: 'down' }), { status: 503 })
      const names = (JSON.parse(String(init.body)) as { names: string[] }).names
      puts.push({ url, names })
      view = { ...view, grants: { ...view.grants, ada: names } }
    }
    return new Response(JSON.stringify(view), { status: 200 })
  })
  return puts
}

it('shows what the agent holds and grants or revokes one resource per click', async () => {
  view = { resources: [resource('crm'), resource('wiki')], grants: { ada: ['crm'] } }
  const puts = daemon()
  const w = mount(IdentityGrants, { props: { agent: 'ada' } })
  await flushPromises()
  expect(w.find('[data-resource="crm"]').text()).toContain('granted')
  expect(w.find('[data-resource="wiki"]').text()).toContain('not granted')

  await w.find('[data-resource="wiki"] button').trigger('click')
  await flushPromises()
  expect(puts).toEqual([{ url: '/api/iam/agents/ada/grants', names: ['crm', 'wiki'] }])
  expect(w.find('[role="status"]').text()).toContain('ada may now use wiki')

  await w.find('[data-resource="crm"] button').trigger('click')
  await flushPromises()
  expect(puts[1]).toEqual({ url: '/api/iam/agents/ada/grants', names: ['wiki'] })
  expect(w.find('[data-resource="crm"]').text()).toContain('not granted')
})

it('says nothing changed when OpenFGA does not answer', async () => {
  view = { resources: [resource('crm')], grants: {} }
  daemon(true)
  const w = mount(IdentityGrants, { props: { agent: 'ada' } })
  await flushPromises()
  await w.find('[data-resource="crm"] button').trigger('click')
  await flushPromises()
  expect(w.find('[role="alert"]').text()).toContain('nothing was changed')
  expect(w.find('[data-resource="crm"]').text()).toContain('not granted')
})

it('says so when the catalogue is empty', async () => {
  view = { resources: [], grants: {} }
  daemon()
  const w = mount(IdentityGrants, { props: { agent: 'ada' } })
  await flushPromises()
  expect(w.text()).toContain('no resource yet')
})

it('follows the step-up when the daemon answers 202 {redirect} and changes nothing on screen', async () => {
  view = { resources: [resource('crm')], grants: {} }
  vi.stubGlobal('fetch', async (_url: string, init: RequestInit) =>
    init.method === 'PUT'
      ? new Response(JSON.stringify({ redirect: '/auth/keycloak/assure?act=A1' }), { status: 202 })
      : new Response(JSON.stringify(view), { status: 200 }),
  )
  const assign = vi.fn()
  vi.stubGlobal('window', Object.assign(window, { location: { assign } }))
  const w = mount(IdentityGrants, { props: { agent: 'ada' } })
  await flushPromises()
  await w.find('[data-resource="crm"] button').trigger('click')
  await flushPromises()
  expect(assign).toHaveBeenCalledWith('/auth/keycloak/assure?act=A1')
  expect(w.find('[data-resource="crm"]').text()).toContain('not granted')
})
