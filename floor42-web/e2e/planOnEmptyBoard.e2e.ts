import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

/**
 * Pilot, M-17: the director left the board empty after an archive, then proposed a mission in graph mode. The plan
 * waiting for approval must be reachable: the To do row leads to the desk's Mission tab with the plan and its cap.
 */
test('a plan proposed after the board was left empty is shown for approval', async ({ page, daemon }) => {
  test.setTimeout(120_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await page.evaluate(() => localStorage.setItem('floor42.board.mission', '@empty'))
  await page.reload()
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const created = await page.request.post('/api/missions', {
    headers,
    data: { title: 'Plan on an empty board', objective: 'Write notes', repository: daemon.gitRepository(), plan: true, mode: 'graph' },
  })
  expect(created.status()).toBe(201)
  const id = (await created.json()).id as string
  await expect.poll(async () => (await (await page.request.get(`/api/missions/${id}`)).json()).status, { timeout: 60_000 }).toBe('AWAITING_APPROVAL')

  // The banner's row for this plan opens the desk on the Mission tab, with the plan and its cap.
  const row = page.locator('[data-testid="waiting-banner"] .waiting-row', { hasText: id })
  await expect(row).toBeVisible({ timeout: 30_000 })
  await shot(page, 'plan-empty-board-01-banner')
  await row.locator('button').click()
  const desk = page.locator('.desk-panel')
  await expect(desk).toBeVisible()
  await expect(desk.locator('.hint', { hasText: `${id} · Plan on an empty board` }).first()).toBeVisible()
  await expect(desk.locator('.plan-cap')).toBeVisible()
  await shot(page, 'plan-empty-board-02-desk-plan')
})
