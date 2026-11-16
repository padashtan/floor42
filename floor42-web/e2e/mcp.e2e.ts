import { ACTIONS } from '../src/ui/actions'
import { runFromMenu } from './bar'
import { CATALOGS, expect, expectOnScreen, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en
const action = (id: string) => ACTIONS.find((a) => a.id === id)!
const fill = (key: 'mcp.allowed' | 'mcp.refused', v: Record<string, string>) => Object.entries(v).reduce<string>((s, [k, x]) => s.replace(`{${k}}`, x), t[key])

const resource = (name: string, baseUrl: string) => ({ name, baseUrl, audience: `${name}-api`, scopes: [], methods: ['POST'], pathPrefixes: ['/mcp'], maxResponseBytes: 65536, local: false })
const tool = (name: string, ticked: boolean) => ({
  name,
  description: `${name} the docs`,
  inputSchema: { type: 'object', required: ['query'], properties: { query: { type: 'string' }, limit: { type: 'integer' } } },
  sha256: `sha-${name}`,
  ticked,
  changed: false,
})

/**
 * Journey: the director declares an MCP server, reads its tools as an agent, allows one and tries it; a second server
 * answers HOST_NOT_ALLOWED, shown in red. The daemon runs with IAM off, so the /api/iam answers are stood in for
 * (sheet 1's shapes), and the refusal comes in a 200 answer of "try" (a real 4xx would log a console error).
 * Not run until integration (INT); the real call is proven by the server tests of the governed MCP node.
 */
test('the director declares an MCP server, allows a tool and tries it', { tag: '@iam' }, async ({ page }) => {
  test.setTimeout(120_000)
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const catalogue = [resource('docs', 'https://docs.example.com'), resource('internal', 'https://10.0.0.5')]
  const servers: { resource: string; path: string; baseUrl: string; audience: string; pinnedAt: string | null; tools: ReturnType<typeof tool>[] }[] = []
  const view = (name: string) => servers.find((s) => s.resource === name)!
  await page.route(/\/api\/(iam\/(status|resources|mcp-servers.*)|agents|missions(\/M-1(\/tools)?)?)$/, (route) => {
    const req = route.request()
    const path = new URL(req.url()).pathname
    const json = (body: unknown) => route.fulfill({ json: body })
    if (path === '/api/iam/status') return json({ enabled: true })
    if (path === '/api/iam/resources') return json({ resources: catalogue, grants: {} })
    if (path === '/api/agents') return json([{ id: 'ada', name: 'Ada', role: 'PRODUCER', status: 'IDLE', suspended: false }])
    if (path === '/api/missions') return json([{ id: 'M-1', title: 'Read the docs' }])
    // The board loads the first mission it is given: this one is stood in for too, or the daemon would answer 404.
    if (path === '/api/missions/M-1') return json(null)
    if (path === '/api/missions/M-1/tools') return json([])
    if (path === '/api/iam/mcp-servers' && req.method() === 'GET') return json({ servers })
    if (path === '/api/iam/mcp-servers' && req.method() === 'PUT') {
      const { resource: name, path: at } = req.postDataJSON()
      servers.push({ resource: name, path: at, baseUrl: catalogue.find((c) => c.name === name)!.baseUrl, audience: `${name}-api`, pinnedAt: null, tools: name === 'internal' ? [tool('read_file', false)] : [] })
      return json(view(name))
    }
    const name = decodeURIComponent(path.split('/')[4])
    if (path.endsWith('/tools/refresh')) {
      view(name).pinnedAt = '2026-11-16T10:00:00Z'
      view(name).tools = [tool('search', false), tool('fetch', false)]
      return json(view(name))
    }
    if (path.endsWith('/tools')) {
      const names: string[] = req.postDataJSON().ticks.map((x: { name: string }) => x.name)
      view(name).tools.forEach((x) => (x.ticked = names.includes(x.name)))
      return json(view(name))
    }
    if (path.endsWith('/try')) {
      const refused = name === 'internal'
      return json({
        ok: !refused,
        firstLine: refused ? 'the host is not allowed' : '3 pages found',
        body: refused ? '' : 'page one\npage two\npage three',
        truncated: false,
        refusal: refused ? 'HOST_NOT_ALLOWED' : null,
      })
    }
    return route.fallback()
  })

  await runFromMenu(page, t, action('mcp'))
  const panel = page.locator('.mcp-panel')
  await expect(panel.locator('.none')).toHaveText(t['mcp.none'])
  await expect(panel.locator('.live')).toHaveText(t['mcp.live'])

  // Declare docs from the catalogue, then read its tools as Ada: nothing is allowed yet.
  await panel.locator('.add-form select').selectOption('docs')
  await panel.locator('.add-form button[type="submit"]').click()
  const detail = panel.locator('.mcp-detail')
  await expect(detail).toHaveAttribute('data-resource', 'docs')
  await expect(detail.locator('.as-note')).toContainText('Ada')
  await detail.locator('.refresh-form button[type="submit"]').click()
  await expect(detail.locator('tr.tool')).toHaveCount(2)

  // Tick one tool: the ticked set goes to the server, and the row counts it.
  const put = page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith('/tools'))
  await detail.locator('tr[data-tool="search"] input[type="checkbox"]').check()
  expect((await put).postDataJSON()).toEqual({ ticks: [{ name: 'search', sha256: 'sha-search' }] })
  await expect(panel.locator('.server-item[data-resource="docs"]')).toContainText(fill('mcp.allowed', { n: '1', m: '2' }))
  await expectOnScreen(detail, 'the server detail')
  await shot(page, 'mcp-01-servers')

  // Try it: the first line, then the body under the untrusted label.
  await detail.locator('tr[data-tool="search"] button.try').click()
  const form = detail.locator('.mcp-try')
  await form.locator('input[type="text"]').fill('governed')
  await form.locator('button[type="submit"]').click()
  await expect(form.locator('.first-line')).toHaveText('3 pages found')
  await expect(form.locator('.try-result')).toContainText(t['mcp.untrusted'])
  await expect(form.locator('pre')).toContainText('page two')
  await shot(page, 'mcp-02-try')

  // A second server, on an internal address: its try is refused, in red, with the code.
  await panel.locator('.add-form select').selectOption('internal')
  await panel.locator('.add-form button[type="submit"]').click()
  await expect(detail).toHaveAttribute('data-resource', 'internal')
  await detail.locator('tr[data-tool="read_file"] button.try').click()
  await detail.locator('.mcp-try input[type="text"]').fill('x')
  await detail.locator('.mcp-try button[type="submit"]').click()
  await expect(detail.locator('.mcp-try .refused.bad')).toHaveText(fill('mcp.refused', { code: 'HOST_NOT_ALLOWED', reason: 'the host is not allowed' }))
  await expectOnScreen(detail, 'the refused try')
  await shot(page, 'mcp-03-refused')
})
