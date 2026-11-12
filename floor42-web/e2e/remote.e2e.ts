import { whereIs } from './office'
import { CATALOGS, expect, expectOnScreen, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

interface Agent {
  id: string
  status: string
}

/**
 * Journey: "remote today". Two agents of a mission in progress are drawn at their home desk (the browser-test hook
 * floor42.remote.force replaces the daily draw; the reads are the daemon's own with two statuses and one mission set,
 * data only). The strip shows a webcam tile for each with the recorded state, their chairs in the office stay empty and
 * signed, a click on a tile selects the agent, the director's switch turns the decor off.
 */
test('agents drawn remote today show in the webcam strip and not at their desks', async ({ page, daemon }) => {
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  const created = await page.request.post('/api/missions', { headers, data: { title: 'Remote journey', objective: 'Nothing to run', repository: daemon.gitRepository() } })
  expect(created.status()).toBe(201)
  const mission = (await created.json()).id as string
  const real = (await (await page.request.get('/api/agents')).json()) as Agent[]
  const [a, b] = real.slice(0, 2).map((x) => x.id)
  await page.addInitScript((ids) => localStorage.setItem('floor42.remote.force', ids), `${a},${b}`)
  await page.route(/\/api\/agents$/, async (route) => {
    const all = (await (await route.fetch()).json()) as Agent[]
    await route.fulfill({ json: all.map((x) => (x.id === a ? { ...x, status: 'EXECUTING' } : x.id === b ? { ...x, status: 'REVIEWING' } : x)) })
  })
  await page.route(/\/api\/missions$/, async (route) => {
    const all = (await (await route.fetch()).json()) as Record<string, unknown>[]
    await route.fulfill({ json: all.map((m) => (m.id === mission ? { ...m, status: 'IN_PROGRESS', agents: [a, b] } : m)) })
  })
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const strip = page.locator('.remote-strip')
  await expect(strip).toBeVisible()
  await expect(strip.locator('.tile')).toHaveCount(2)
  await expect(strip.locator(`.tile[data-agent="${a}"]`)).toHaveAttribute('data-state', 'typing')
  await expect(strip.locator(`.tile[data-agent="${b}"]`)).toHaveAttribute('data-state', 'reading')
  await expect(strip.locator('.tile').first().locator('.tag')).toContainText('Remote today')
  // The office: both are out of it (their chairs signed), nobody else is.
  await expect(page.locator('canvas.view')).toHaveAttribute('data-remote', new RegExp(`${a}`))
  for (const id of [a, b]) expect((await whereIs(page, id))?.visible, `${id} is not at its desk`).toBe(false)
  await expect(page.locator('.name-tag').filter({ hasText: 'Remote' }).first()).toBeVisible()
  await page.waitForTimeout(700)
  await shot(page, 'remote-strip')
  await expectOnScreen(strip, 'the remote strip')

  // A click on a tile selects the agent, as a click in the office does.
  await strip.locator(`.tile[data-agent="${a}"]`).click()
  await expect(page.locator('.selection')).toBeVisible()

  // 600 px: the strip stays inside the window.
  await page.setViewportSize({ width: 600, height: 900 })
  await page.keyboard.press('Escape')
  const box = await strip.boundingBox()
  if (box) expect(box.x >= 0 && box.x + box.width <= 600.5, `strip ${JSON.stringify(box)}`).toBe(true)
  await page.setViewportSize({ width: 1600, height: 900 })

  // The director's switch: no remote days, the strip and the signs are gone.
  await page.locator('.help button.remote-days').click()
  await expect(strip).toHaveCount(0)
  await expect(page.locator('canvas.view')).toHaveAttribute('data-remote', '')
  await page.request.post(`/api/missions/${mission}/cancel`, { headers, data: { reason: 'journey over' } })
})
