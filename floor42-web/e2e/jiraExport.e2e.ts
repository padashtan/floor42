import { readFile } from 'node:fs/promises'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

/** Journey: the director exports the board as a Jira import file (a snapshot, nothing is sent to Jira). */
test('the director exports the board for Jira', { tag: '@tickets' }, async ({ page, daemon }) => {
  test.setTimeout(120_000)
  const consoleErrors: string[] = []
  page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()))
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const created = await page.request.post('/api/missions', {
    headers,
    data: { objective: 'Export me for Jira', repository: daemon.gitRepository() },
  })
  expect(created.status()).toBe(201)
  const id: string = (await created.json()).id
  const read = async () => (await page.request.get(`/api/missions/${id}`)).json()
  await expect.poll(async () => (await read()).status).toBe('AWAITING_APPROVAL')
  const tickets: unknown[] = (await read()).tickets

  await page.evaluate((m) => localStorage.setItem('floor42.board.mission', m), id)
  await page.reload()
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await page.keyboard.press('b')
  const button = page.locator('.board-panel .jira-export')
  await expect(button).toHaveText(t['board.exportJira'])
  const [download] = await Promise.all([page.waitForEvent('download'), button.click()])
  expect(download.suggestedFilename()).toBe(`floor42-${id}-tickets.csv`)
  const lines = (await readFile((await download.path())!, 'utf8')).split('\r\n').filter(Boolean)
  expect(lines[0]).toBe('Issue ID,Summary,Description,Issue Type,Priority,Status,Labels,Created,Resolved')
  expect(lines.length).toBeGreaterThanOrEqual(tickets.length + 1)
  await shot(page, 'jira-01-export')
  expect(consoleErrors).toEqual([])
})
