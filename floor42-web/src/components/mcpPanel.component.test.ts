// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { setLang } from '../i18n'
import McpPanel from './McpPanel.vue'

beforeAll(() => setLang('en'))
afterEach(() => vi.unstubAllGlobals())

const tool = (name: string, over: object = {}) => ({ name, description: `does ${name}`, inputSchema: { type: 'object', properties: {} }, sha256: name, ticked: false, changed: false, ...over })
const servers = (tools: object[]) => ({ servers: [{ resource: 'docs', path: '/mcp', baseUrl: 'https://docs.example.com', audience: 'docs-api', pinnedAt: '2026-11-16T10:00:00Z', tools }] })

function serve(iam: boolean, tools: object[], extra: Record<string, () => Response> = {}) {
  const answers: Record<string, unknown> = {
    '/api/iam/status': { enabled: iam },
    '/api/iam/mcp-servers': servers(tools),
    '/api/iam/resources': { resources: [{ name: 'docs', baseUrl: 'https://docs.example.com', audience: 'docs-api', scopes: [], methods: ['POST'], pathPrefixes: ['/mcp'], maxResponseBytes: 1000, local: false }], grants: {} },
    '/api/agents': [{ id: 'ada', name: 'Ada' }],
    '/api/missions': [{ id: 'M-1', title: 'First' }],
  }
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${url}`
    if (extra[key]) return extra[key]()
    return new Response(JSON.stringify(answers[url] ?? {}), { status: 200 })
  })
}

describe('the MCP panel', () => {
  it('IAM off disables refresh and try with the reason', async () => {
    serve(false, [tool('search')])
    const w = mount(McpPanel)
    await flushPromises()
    expect(w.find('.iam-off').text()).toContain('Needs IAM mode')
    expect(w.find('.refresh-form button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(w.find('button.try').attributes('disabled')).toBeDefined()
    expect(w.find('.server-item').text()).toContain('0 of 1 tools allowed')
  })

  it('a refusal shows its code in red', async () => {
    serve(true, [tool('search')], {
      'POST /api/iam/mcp-servers/docs/tools/refresh': () => new Response(JSON.stringify({ code: 'HOST_NOT_ALLOWED', message: 'no' }), { status: 409 }),
    })
    const w = mount(McpPanel)
    await flushPromises()
    await w.find('.refresh-form').trigger('submit')
    await flushPromises()
    expect(w.find('.refused.bad').text()).toContain('HOST_NOT_ALLOWED')
  })

  it('shows the address and audience of the server in its row', async () => {
    serve(true, [tool('search')])
    const w = mount(McpPanel)
    await flushPromises()
    expect(w.find('.server-address').text()).toBe('https://docs.example.com/mcp · docs-api')
  })

  it('a tick sends the hash it saw; a stale one says so and reads the list again', async () => {
    let puts: unknown[] = []
    let lists = 0
    serve(true, [tool('search')], {
      'PUT /api/iam/mcp-servers/docs/tools': () => (puts.push(1), new Response(JSON.stringify({ code: 'MCP_TOOL_CHANGED', message: 'x' }), { status: 400 })),
      'GET /api/iam/mcp-servers': () => (lists++, new Response(JSON.stringify(servers([tool('search')])), { status: 200 })),
    })
    const w = mount(McpPanel)
    await flushPromises()
    await w.find('tr.tool input[type="checkbox"]').setValue(true)
    await flushPromises()
    expect(puts).toHaveLength(1)
    expect(w.find('.refused.bad').text()).toContain('changed since you looked')
    expect(lists).toBe(2)
    puts = []
  })

  it('a moved endpoint asks for a refresh to review it', async () => {
    serve(true, [tool('search')], {
      'PUT /api/iam/mcp-servers/docs/tools': () => new Response(JSON.stringify({ code: 'MCP_ENDPOINT_CHANGED', message: 'x' }), { status: 409 }),
    })
    const w = mount(McpPanel)
    await flushPromises()
    await w.find('tr.tool input[type="checkbox"]').setValue(true)
    await flushPromises()
    expect(w.find('.refused.bad').text()).toContain('Refresh to review the new endpoint')
  })

  it('a changed tool is unticked and amber', async () => {
    serve(true, [tool('search', { ticked: true, changed: true })])
    const w = mount(McpPanel)
    await flushPromises()
    expect(w.find('tr.tool').classes()).toContain('changed')
    expect((w.find('tr.tool input[type="checkbox"]').element as HTMLInputElement).checked).toBe(false)
    expect(w.find('.changed-note').text()).toContain('Definition changed')
    expect(w.find('.changed-mark').exists()).toBe(true)
  })

  it('text from the server is rendered as text', async () => {
    serve(true, [tool('search', { description: '<img src=x onerror=alert(1)>' })])
    const w = mount(McpPanel)
    await flushPromises()
    expect(w.find('.desc').element.querySelectorAll('*')).toHaveLength(0)
    expect(w.find('.desc').text()).toContain('<img')
  })
})
