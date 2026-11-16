import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

interface Mission {
  id: string
  title: string
  status: string
  tickets: { id: string; status: string }[]
}

/**
 * The pilot frictions of 2026-11, proven in the browser (they shipped in v1.0.0 with unit tests only): a title offered
 * from the objective, a mission abandoned from its board, and a part of the pending files accepted.
 */
test('an empty title offers the objective first line and one click takes it', { tag: '@tickets' }, async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await page.keyboard.press('d')
  const desk = page.locator('.desk-panel')
  await desk.locator('.tabs button', { hasText: t['desk.newMission'] }).click()
  const form = desk.locator('form.new-mission')
  const title = form.getByPlaceholder(t['mission.fieldTitle'])
  await form.getByPlaceholder(t['mission.fieldObjective']).fill('Port the billing module\nthen its tests')
  const suggestion = form.locator('.suggestion')
  await expect(suggestion.locator('button.link')).toHaveText('Port the billing module')
  await shot(page, 'frictions/title')
  await suggestion.locator('button.link').click()
  await expect(title).toHaveValue('Port the billing module')
  await expect(suggestion).toHaveCount(0)
  // A title typed first: nothing is suggested.
  await title.fill('My own title')
  await expect(suggestion).toHaveCount(0)
})

test('the director abandons a mission from its board', { tag: '@tickets' }, async ({ page, daemon }) => {
  test.setTimeout(120_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  // No title sent: the server takes the objective's first line (one rule, MissionTitles).
  const created = await page.request.post('/api/missions', {
    headers,
    data: { objective: 'Abandon me from the board', repository: daemon.gitRepository() },
  })
  expect(created.status()).toBe(201)
  const id: string = (await created.json()).id
  const read = async (): Promise<Mission> => (await page.request.get(`/api/missions/${id}`)).json()
  await expect.poll(async () => (await read()).status).toBe('AWAITING_APPROVAL')
  expect((await read()).title).toBe('Abandon me from the board')

  await page.evaluate((m) => localStorage.setItem('floor42.board.mission', m), id)
  await page.reload()
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await page.keyboard.press('b')
  const board = page.locator('.board-panel')
  const abandon = board.locator('button.abandon-mission')
  await expect(abandon).toBeVisible()

  // A no: nothing changes.
  await abandon.click()
  const dialog = page.locator('.confirm-dialog')
  await expect(dialog).toContainText('Abandon me from the board')
  await dialog.getByRole('button', { name: t['confirm.cancel'], exact: true }).click()
  await expect(dialog).toHaveCount(0)
  expect((await read()).status).toBe('AWAITING_APPROVAL')

  // A yes: the banner shows, the button is gone, the mission is cancelled.
  await abandon.click()
  await dialog.getByRole('button', { name: t['confirm.abandon'], exact: true }).click()
  await expect.poll(async () => (await read()).status).toBe('CANCELLED')
  await expect(board.locator('.delivery.abandoned')).toBeVisible()
  await expect(board.locator('button.abandon-mission')).toHaveCount(0)
  await shot(page, 'frictions/abandon')
})

test('the director accepts two files of three and discards the third', { tag: '@tickets' }, async ({ page, daemon }) => {
  test.setTimeout(240_000)
  page.on('dialog', (d) => void d.accept())
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  // [fake:touch] leaves notes.txt in the developer's worktree; two more files are written beside it below.
  const created = await page.request.post('/api/missions', {
    headers,
    data: { title: 'Partial acceptance', objective: 'Write the notes [fake:touch=notes.txt]', repository: daemon.gitRepository() },
  })
  expect(created.status()).toBe(201)
  const id: string = (await created.json()).id
  const mission = async (): Promise<Mission> => (await page.request.get(`/api/missions/${id}`)).json()
  await expect.poll(async () => (await mission()).status).toBe('AWAITING_APPROVAL')

  await page.evaluate((m) => localStorage.setItem('floor42.board.mission', m), id)
  await page.reload()
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await page.keyboard.press('d')
  const desk = page.locator('.desk-panel')
  await desk.locator('.tabs button', { hasText: t['desk.mission'] }).first().click()
  const cap = desk.locator('.plan-cap')
  await cap.locator('input[inputmode="decimal"]').fill('1')
  await cap.locator('button.action').click()
  await expect.poll(async () => (await mission()).status).toBe('IN_PROGRESS')

  await desk.locator('.tabs button', { hasText: t['desk.toDecide'] }).click()
  await expect(async () => {
    const card = desk.locator('.question').first()
    if (await card.count()) await card.getByRole('button', { name: t['option.ALLOW'], exact: true }).click()
    const m = await mission()
    expect(m.tickets.length).toBeGreaterThan(0)
    expect(m.tickets.map((x) => x.status)).toEqual(m.tickets.map(() => 'DONE'))
  }).toPass({ timeout: 120_000, intervals: [250] })
  await page.keyboard.press('Escape')

  // Two more changes in the same worktree: three pending files.
  const view = await (await page.request.get(`/api/missions/${id}/workshop`)).json()
  const worktree: string = view.workspaces.find((w: { changes: unknown[] }) => w.changes.length > 0).path
  writeFileSync(join(worktree, 'second.txt'), 'second\n')
  writeFileSync(join(worktree, 'third.txt'), 'third\n')

  await page.keyboard.press('w')
  const workshop = page.locator('.panel.workshop')
  await expect(workshop.locator('.change-line')).toHaveCount(3)
  await workshop.locator('.change-line', { hasText: 'third.txt' }).locator('input[type="checkbox"]').uncheck()
  await expect(workshop.locator('.picked-count')).toContainText('2')
  await workshop.getByPlaceholder(t['ws.commitMessage']).fill('Two of three')
  const accept = workshop.locator('.block', { hasText: t['ws.accept'] }).locator('button.action.recommended')
  await shot(page, 'frictions/partial')
  const [accepted] = await Promise.all([page.waitForResponse((r) => /\/workshop\/[^/]+\/accept$/.test(r.url())), accept.click()])
  expect(accepted.status(), await accepted.text()).toBe(200)

  // The two ticked files left the pending list; the unticked one stays, then is discarded after a confirmation.
  await expect(workshop.locator('.change-line')).toHaveCount(1)
  await expect(workshop.locator('.change-line')).toContainText('third.txt')
  const after = await (await page.request.get(`/api/missions/${id}/workshop`)).json()
  const commit = after.interventions.find((i: { kind: string }) => i.kind === 'PATCH_ACCEPTED')
  expect([...commit.files].sort()).toEqual(['notes.txt', 'second.txt'])
  await workshop.locator('.change-line button.discard').click()
  await page.locator('.confirm-dialog').getByRole('button', { name: t['ws.discard'], exact: true }).click()
  await expect(workshop.locator('.change-line')).toHaveCount(0)
})
