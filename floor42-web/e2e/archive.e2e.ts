import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

interface Summary {
  id: string
  title: string
}

/**
 * Journey 9: the archives (ADR-0054). Three missions abandoned before anything ran on them; the director archives the
 * one on the board from the desk, finds it under Archives and restores it, then cleans the board with "Archive all
 * closed missions", confirmed with its count and offered only the three missions it made (the daemon is shared with
 * the other journeys). Archived missions leave the board, the Work menu and the mission list.
 */
test('closed missions go to the archives, one at a time or all at once, and come back', async ({ page, daemon }) => {
  test.setTimeout(120_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const repo = daemon.gitRepository()
  const ids: string[] = []
  for (const title of ['Archive journey A', 'Archive journey B', 'Archive journey C']) {
    const created = await page.request.post('/api/missions', {
      headers,
      data: { title, objective: 'Nothing to run', repository: repo },
    })
    expect(created.status()).toBe(201)
    ids.push((await created.json()).id)
  }
  for (const id of ids) {
    await expect.poll(async () => (await (await page.request.get(`/api/missions/${id}`)).json()).status).toBe('AWAITING_APPROVAL')
    expect((await page.request.post(`/api/missions/${id}/cancel`, { headers, data: { reason: 'not needed' } })).status()).toBe(200)
  }
  const listed = async (): Promise<string[]> =>
    ((await (await page.request.get('/api/missions')).json()) as Summary[]).map((m) => m.id)
  const archived = async (): Promise<string[]> =>
    ((await (await page.request.get('/api/missions?archived=true')).json()) as Summary[]).map((m) => m.id)

  // The director puts the newest one on the board (another journey's mission may be running), then archives it from
  // the desk's Mission tab, after the confirmation.
  await page.evaluate((id) => localStorage.setItem('floor42.board.mission', id), ids[2])
  await page.reload()
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await page.keyboard.press('d')
  const desk = page.locator('.desk-panel')
  await expect(desk).toBeVisible()
  await desk.locator('.tabs button[data-tab="mission"]').click()
  await expect(desk.locator('.hint', { hasText: `${ids[2]} · Archive journey C` }).first()).toBeVisible()
  await desk.locator('button.archive-mission').click()
  const dialog = page.locator('.confirm-dialog')
  await expect(dialog).toContainText('Archive journey C')
  await shot(page, '9-archive-01-confirm')
  await dialog.getByRole('button', { name: t['confirm.archive'], exact: true }).click()
  await expect.poll(archived).toContain(ids[2])
  expect(await listed()).not.toContain(ids[2])
  // Then the board asks what it shows next (pilot entry 18): left empty here.
  const next = page.locator('.after-archive')
  await expect(next).toContainText(t['afterArchive.title'])
  await shot(page, '9-archive-01b-what-next')
  await next.locator('button.leave-empty').click()
  await expect(next).toBeHidden()

  // Under Archives, restored: back in the list.
  await desk.locator('.tabs button[data-tab="archives"]').click()
  const row = desk.locator(`li.archived-mission[data-mission="${ids[2]}"]`)
  await expect(row).toBeVisible()
  await shot(page, '9-archive-02-archives')
  await row.locator('button.restore').click()
  await expect(row).toHaveCount(0)
  // Restored: said, and nothing convened by the restore. Nobody worked on it, so no retrospective is offered, and why.
  const restored = desk.locator(`.restored[data-mission="${ids[2]}"]`)
  await expect(restored).toBeVisible()
  await expect(restored.locator('.convene-retro-why')).toBeVisible()
  await expect(restored.locator('button.convene-retro')).toHaveCount(0)
  await expect.poll(listed).toContain(ids[2])

  // Clean the board. The daemon is shared with the other journeys: only the missions this journey made are offered, so
  // the bulk archive, which sends the ids the director confirmed, never touches another journey's mission.
  const archivableRoute = (url: URL) => url.pathname === '/api/missions/archivable'
  await page.route(archivableRoute, async (route) => {
    const all = (await (await route.fetch()).json()) as Summary[]
    await route.fulfill({ json: all.filter((m) => ids.includes(m.id)) })
  })
  const [bulk] = await Promise.all([
    page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/missions/archive'),
    (async () => {
      await desk.locator('button.clean-board').click()
      await expect(dialog).toContainText(t['archives.cleanConfirm'].replace('{count}', '3'))
      await dialog.getByRole('button', { name: t['confirm.archive'], exact: true }).click()
    })(),
  ])
  expect([...(bulk.postDataJSON() as { missions: string[] }).missions].sort()).toEqual([...ids].sort())
  await page.unroute(archivableRoute)
  await expect(desk.locator('.clean-report')).toContainText(t['archives.cleaned'].replace('{count}', '3'))
  for (const id of ids) await expect(desk.locator(`li.archived-mission[data-mission="${id}"]`)).toBeVisible()
  await shot(page, '9-archive-03-cleaned')
  const left = await listed()
  for (const id of ids) expect(left).not.toContain(id)

  // The Work menu offers none of them any more.
  await page.keyboard.press('Escape')
  await page.locator('.panel.brand .bar-nav button[data-menu="work"]').click()
  for (const title of ['Archive journey A', 'Archive journey B', 'Archive journey C']) {
    await expect(page.locator('.bar-dropdown[role="menu"]').getByText(title)).toHaveCount(0)
  }
})
