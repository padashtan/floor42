import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

/**
 * Journey: session risk (ADR-0066). The fake Claude Code reads three files outside its workspace ([fake:probe]); the
 * hook refuses each one, the risk reaches the strict level, and the To do panel shows the row with its reason. The
 * director opens the confirmation, reads what a quarantine does, confirms, and sees the mission suspended and the
 * journal line. Detection only: nothing is applied before the click.
 */
test('a probing session shows in To do and the director quarantines it', async ({ page, daemon }) => {
  test.setTimeout(240_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  // [fake:unverifiable] keeps the mission in progress once the probing session has ended (the review escalates).
  const created = await page.request.post('/api/missions', {
    headers,
    data: { title: 'Probing journey', objective: 'Write notes [fake:probe] [fake:unverifiable]', repository: daemon.gitRepository() },
  })
  expect(created.status()).toBe(201)
  const id: string = (await created.json()).id
  const status = async (): Promise<string> => (await (await page.request.get(`/api/missions/${id}`)).json()).status
  await expect.poll(status, { timeout: 90_000 }).toBe('AWAITING_APPROVAL')
  expect((await page.request.post(`/api/missions/${id}/approve`, { headers, data: { noCap: true } })).status()).toBe(200)

  // Nothing is stopped by itself: the risk is a row, never an action.
  const risks = async () => {
    const all: { mission: string; stuck: { kind: string; status: string | null }[] }[] = await (await page.request.get('/api/progress')).json()
    return all.find((m) => m.mission === id)?.stuck.filter((i) => i.kind === 'SESSION_RISK') ?? []
  }
  await expect.poll(async () => (await risks()).map((r) => r.status), { timeout: 120_000 }).toContain('QUARANTINE')

  // The row, in the To do panel's alerts: its reason in words, its button proposes the quarantine.
  const todo = page.locator('.hud > .panel.todo')
  const row = todo.locator('[data-category="alerts"] .row', { hasText: id }).filter({ hasText: 'three refusals in five minutes' })
  await expect(row).toHaveCount(1, { timeout: 30_000 })
  await expect(row.locator('button.action')).toHaveText(t['todo.go.quarantine'])
  // The waiting banner keeps the same row on screen (unfolded when something else waits for the director too).
  const banner = page.locator('[data-testid="waiting-banner"]')
  await expect(banner).toBeVisible()
  const more = banner.locator('.waiting-more')
  if (await more.count()) await more.click()
  await expect(banner.locator('.waiting-row', { hasText: id }).filter({ hasText: 'three refusals in five minutes' })).toHaveCount(1)
  await shot(page, '17-session-risk-01-todo-row')

  // The confirmation says what a quarantine does; nothing is sent before the click on "Quarantine".
  await row.locator('button.action').click()
  const dialog = page.locator('.confirm-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('suspends the mission and stops the session')
  await expect(dialog).toContainText('resumes only with your approval')
  expect(await status(), 'nothing applied before the confirm').toBe('IN_PROGRESS')
  await shot(page, '17-session-risk-02-confirm')
  await dialog.getByRole('button', { name: t['confirm.quarantine'], exact: true }).click()

  // Done: the mission is suspended, the row is gone, the journal says what was done in words.
  await expect.poll(status, { timeout: 30_000 }).toBe('SUSPENDED')
  await expect(row).toHaveCount(0, { timeout: 30_000 })
  const log = page.locator('.hud > .panel.log')
  await expect(log).toContainText('was quarantined at risk level quarantine', { timeout: 30_000 })
  await expect(log).toContainText('mission suspended')
  await shot(page, '17-session-risk-03-quarantined')
})
