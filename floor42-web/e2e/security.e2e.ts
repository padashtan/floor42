import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { ACTIONS } from '../src/ui/actions'
import { runFromMenu } from './bar'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
})

/** A page on another local port, as a server an agent started would serve; it tries the daemon with the cookie. */
function attackerPage(daemon: string): Promise<{ server: Server; url: string }> {
  const html = `<!doctype html><title>pending</title><script>
    const results = []
    fetch(${JSON.stringify(daemon)} + '/api/notes', {
      method: 'POST', mode: 'no-cors', credentials: 'include',
      headers: { 'Content-Type': 'text/plain' }, body: JSON.stringify({ text: 'planted by another port' }),
    }).then(() => results.push('post sent'), () => results.push('post failed'))
    .finally(() => {
      const ws = new WebSocket(${JSON.stringify(daemon.replace('http', 'ws'))} + '/ws/events')
      ws.onopen = () => { document.title = 'ws opened' }
      ws.onerror = () => { document.title = 'ws refused' }
    })
  </script>`
  const server = createServer((_, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html' })
    res.end(html)
  })
  return new Promise((resolve) =>
    server.listen(0, '127.0.0.1', () =>
      resolve({ server, url: `http://127.0.0.1:${(server.address() as AddressInfo).port}/` }),
    ),
  )
}

test('a page on another local port cannot drive the daemon with the director’s cookie', async ({ page, daemon }) => {
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  const { server, url } = await attackerPage(daemon.url)
  try {
    // Another tab of the same signed-in browser: SameSite=Strict sends the cookie, ports being the same site.
    const other = await page.context().newPage()
    await other.goto(url)
    await expect(other).toHaveTitle('ws refused', { timeout: 10_000 })
    await other.close()
  } finally {
    server.close()
  }
  const notes: { text: string }[] = await (await page.request.get('/api/notes')).json()
  expect(notes.map((n) => n.text)).not.toContain('planted by another port')
})

test('the page runs under its strict policy, the terminal included', async ({ page, daemon }) => {
  const response = await page.goto('/')
  const csp = response?.headers()['content-security-policy'] ?? ''
  expect(csp).toContain("script-src 'self';")
  expect(csp).toContain("object-src 'none'")
  expect(csp).toContain("frame-ancestors 'none'")
  expect(response?.headers()['x-frame-options']).toBe('DENY')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  // xterm.js renders in the page: its own <style> elements are the policy's one exception, and nothing else is refused
  // (the consoleErrors fixture fails on any CSP violation).
  const repo = daemon.gitRepository()
  await runFromMenu(page, t, ACTIONS.find((a) => a.id === 'terminal')!)
  const panel = page.locator('.director-terminal')
  await panel.locator('input[list="terminal-projects"]').fill(repo)
  await panel.locator('button.action').click()
  await expect(panel.locator('.xterm-rows')).toContainText('FAKE-TTY ready', { timeout: 20_000 })
  await shot(page, '4-terminal-under-csp')
  await panel.getByRole('button', { name: t['terminal.stop'] }).first().click()
  // The page's own confirmation, not the browser's: nothing is closed before the director confirms it.
  await page.locator('.confirm-dialog').getByRole('button', { name: t['terminal.stop'] }).click()
  await page.keyboard.press('Escape')
})

test('a script token is shown once, works within its scope, and stops when revoked', async ({ page }) => {
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await runFromMenu(page, t, ACTIONS.find((a) => a.id === 'sessions')!)
  const panel = page.locator('.sessions-panel')
  await expect(panel).toBeVisible()
  await expect(panel.locator('.session-row', { hasText: t['sessions.current'] })).toHaveCount(1)

  await panel.locator('select').selectOption('read')
  await panel.locator('input[type="number"]').fill('2')
  await panel.getByRole('button', { name: t['sessions.issueButton'] }).click()
  const token = (await panel.locator('.issued-token code').textContent())?.trim() ?? ''
  expect(token.length).toBeGreaterThanOrEqual(40)
  await shot(page, '4-sessions-token-issued')

  const bearer = { Authorization: `Bearer ${token}` }
  expect((await page.request.get('/api/missions', { headers: bearer })).status()).toBe(200)
  const post = await page.request.post('/api/notes', { headers: bearer, data: { text: 'from a read token' } })
  expect(post.status()).toBe(403)

  const row = panel.locator('.session-row', { hasText: t['sessions.kind.SCRIPT'] }).last()
  await row.getByRole('button', { name: t['sessions.revoke'] }).click()
  await page.locator('.confirm-dialog').getByRole('button', { name: t['sessions.revoke'] }).click()
  await expect.poll(async () => (await page.request.get('/api/missions', { headers: bearer })).status()).toBe(401)
  await page.keyboard.press('Escape')
  await expect(panel).toHaveCount(0)
})
