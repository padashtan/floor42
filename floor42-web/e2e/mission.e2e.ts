import { CATALOGS, expect, expectNarrowLayout, expectOnScreen, opaque, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

interface Mission {
  id: string
  title: string
  status: string
  tickets: { id: string; status: string }[]
}

/**
 * Journey 2: a mission from the desk to its end, on a throwaway git repository, played by the fake Claude Code.
 * The director proposes it, approves the plan with a cap, allows what the agent asks, accepts the change in the
 * workshop, finds the file among the deliverables and reads it, reads the end-of-mission report, then accepts the
 * delivery and sees the mission closed.
 */
test('a mission goes from the desk to its end', async ({ page, daemon }) => {
  test.setTimeout(240_000)
  page.on('dialog', (d) => void d.accept())
  const repo = daemon.gitRepository()
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  // The desk, "New mission": no planner, no committee, so Floor42 proposes one ticket.
  await page.keyboard.press('d')
  const desk = page.locator('.desk-panel')
  await expect(desk).toBeVisible()
  await desk.locator('.tabs button', { hasText: t['desk.newMission'] }).click()
  const form = desk.locator('form.new-mission')
  await form.getByPlaceholder(t['mission.fieldTitle']).fill('Browser journey')
  // [fake:touch] makes the developer's session leave a file in its worktree, so there is a change to accept.
  await form.getByPlaceholder(t['mission.fieldObjective']).fill('Write the notes [fake:touch=notes.txt]')
  await form.getByPlaceholder(t['mission.fieldRepository']).fill(repo)
  await form.locator('label', { hasText: t['mission.letGeoffreyPlan'] }).locator('input').uncheck()
  await shot(page, '2-mission-01-new')
  await form.getByRole('button', { name: t['mission.propose'], exact: true }).click()

  let id = ''
  const mission = async (): Promise<Mission> => (await page.request.get(`/api/missions/${id}`)).json()
  await expect
    .poll(async () => {
      const all: Mission[] = await (await page.request.get('/api/missions')).json()
      id = all.find((x) => x.title === 'Browser journey')?.id ?? ''
      return id && (await mission()).status
    })
    .toBe('AWAITING_APPROVAL')

  // The plan, then its approval: a real mission is approved with a cap (PlanCap), here 1 USD.
  await desk.locator('.tabs button', { hasText: t['desk.mission'] }).first().click()
  await expect(desk.getByText(t['mission.officePlan'])).toBeVisible()
  const cap = desk.locator('.plan-cap')
  await cap.locator('input[inputmode="decimal"]').fill('1')
  await shot(page, '2-mission-02-plan-with-cap')
  await cap.locator('button.action').click()
  await expect.poll(async () => (await mission()).status).toBe('IN_PROGRESS')
  const budget = await (await page.request.get(`/api/missions/${id}`)).json()
  expect(budget.budgetUsd, 'the cap the director set').toBe(1)

  // The agent asks before its shell command: the director allows from the desk until the ticket is done.
  await desk.locator('.tabs button', { hasText: t['desk.toDecide'] }).click()
  let asked = 0
  await expect(async () => {
    const card = desk.locator('.question').first()
    if (await card.count()) {
      const question = (await card.locator('.head b').first().innerText()).trim()
      if (asked === 0) await shot(page, '2-mission-03-question')
      await card.getByRole('button', { name: t['option.ALLOW'], exact: true }).click()
      asked++
      // Answered: its toast ("Answer") leaves at once, not 12 s later.
      await expect(page.locator('.toasts .toast', { hasText: `${question} ` })).toHaveCount(0, { timeout: 3_000 })
    }
    const m = await mission()
    expect(m.tickets.length).toBeGreaterThan(0)
    expect(m.tickets.map((x) => x.status)).toEqual(m.tickets.map(() => 'DONE'))
  }).toPass({ timeout: 120_000, intervals: [1_000] })
  test.info().annotations.push({ type: 'questions allowed', description: String(asked) })
  await page.keyboard.press('Escape')
  await expect(desk).toHaveCount(0)

  // The workshop: the developer's change, accepted as a commit on its branch.
  await page.keyboard.press('w')
  const workshop = page.locator('.panel.workshop')
  await expect(workshop).toBeVisible()
  await expect(workshop.getByText('notes.txt').first()).toBeVisible()
  await shot(page, '2-mission-04-workshop')
  // At 1600 by 900, then at 600 px: the file list and its filter are not under the actions, a file opens (F5, F17).
  for (const size of [{ width: 1600, height: 900 }, { width: 600, height: 900 }]) {
    await page.setViewportSize(size)
    const pending = workshop.locator('button.file-row', { hasText: 'notes.txt' }).first()
    const filter = workshop.locator('input.filter')
    // Under 760 px the file pane scrolls on its own: what is in it is reached by scrolling it, never covered. On a
    // desktop window everything is on screen as the workshop opens, without scrolling.
    for (const [control, what] of [[pending, 'the pending change'], [filter, 'the file filter']] as const) {
      if (size.width < 760) await control.scrollIntoViewIfNeeded()
      await expectOnScreen(control, `${what} at ${size.width}`)
    }
    await filter.fill('no-such-file-here')
    await expect(workshop.locator('.file-list button.file-row')).toHaveCount(0)
    await filter.fill('')
    await expectOnScreen(workshop.locator('.title button.close').first(), `the workshop's close button at ${size.width}`)
    expect(await opaque(workshop), `the workshop's body at ${size.width} is opaque`).toBe(true)
    await pending.click()
    await expect(pending).toHaveClass(/\bon\b/)
    await shot(page, `2-mission-04-workshop-${size.width}`)
  }
  await page.setViewportSize({ width: 1600, height: 900 })
  const accept = workshop.locator('.block', { hasText: t['ws.accept'] }).locator('button.action')
  await expect(accept).toBeEnabled()
  const [accepted] = await Promise.all([
    page.waitForResponse((r) => /\/workshop\/[^/]+\/accept$/.test(r.url())),
    accept.click(),
  ])
  expect(accepted.status(), await accepted.text()).toBe(200)
  await expect(accept).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(workshop).toHaveCount(0)

  // The board shows the delivery; the deliverables list the committed file, and it can be read.
  await page.keyboard.press('b')
  const board = page.locator('.board-panel')
  await expect(board.locator('.delivery')).toBeVisible()
  await shot(page, '2-mission-05-delivery')
  await board.getByRole('button', { name: t['deliverables.open'], exact: true }).click()
  const deliverables = page.locator('.deliverables-panel')
  await expect(deliverables).toBeVisible()
  const files = deliverables.locator('details.files-of').first()
  await files.locator('summary').click()
  const file = files.locator('.file', { hasText: 'notes.txt' }).first()
  await expect(file).toBeVisible()
  await file.getByRole('button', { name: t['deliverables.view'] }).click()
  // The file viewer is a main panel of its own (ADR-0051): it takes the list's place, Escape brings the list back.
  const viewer = page.locator('.file-viewer')
  await expect(viewer.locator('code', { hasText: 'notes.txt' })).toBeVisible()
  await expect(viewer).toContainText('written by session')
  await expect(viewer.getByRole('link', { name: t['viewer.download'] })).toBeVisible()
  await expect(deliverables).toHaveCount(0)
  await shot(page, '2-mission-06-deliverables')
  await page.keyboard.press('Escape')
  await expect(viewer).toHaveCount(0)
  await expect(deliverables).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(deliverables).toHaveCount(0)

  // The end-of-mission report, before closing. The deliverables had replaced the board: it comes back with B.
  await page.keyboard.press('b')
  await expect(board).toBeVisible()
  await board.getByRole('button', { name: t['board.openReport'] }).first().click()
  const report = page.locator('.mission-end')
  await expect(report).toBeVisible()
  await expect(report.locator('.title')).toContainText(id)
  await shot(page, '2-mission-07-report')
  await page.keyboard.press('Escape')
  await expect(report).toHaveCount(0)

  // The delivery, accepted from the board: the mission closes.
  await page.keyboard.press('b')
  await expect(board).toBeVisible()
  await board.getByRole('button', { name: t['board.deliveryAccept'] }).click()
  // The producer left no PROVE.md (ADR-0058): said before the acceptance, accepted unproven.
  const unproven = page.locator('.confirm-dialog')
  await expect(unproven).toContainText('left no PROVE.md')
  await unproven.getByRole('button', { name: t['prove.acceptAnyway'] }).click()
  await expect.poll(async () => (await mission()).status).toBe('COMPLETED')

  // The end of the mission opens its deliverables, in the board's place (one main panel at a time); B brings the board back.
  await expect(page.locator('.deliverables-panel')).toBeVisible()
  await page.keyboard.press('Escape')
  await page.keyboard.press('b')
  await expect(board).toBeVisible()
  // The end of the mission as the director sees it: closed on the board, its report, no mission badge on the bar.
  await expect(board.locator('.delivery.done')).toContainText(t['board.closedTitle'])
  await expect(page.locator('.panel.brand .badge.real, .panel.brand .badge.sim')).toHaveCount(0)
  await shot(page, '2-mission-08-closed')
  await board.getByRole('button', { name: t['board.openReport'] }).click()
  await expect(report).toBeVisible()
  await expect(report.locator('.title')).toContainText(t['mstatus.COMPLETED'])
  // A closed mission is not offered to be closed or reopened, and no raw server refusal reaches the director.
  await expect(report.getByRole('button', { name: t['report.do.CLOSE'] })).toHaveCount(0)
  await expect(report.getByRole('button', { name: t['report.do.REOPEN'] })).toHaveCount(0)
  expect(await report.innerText()).not.toMatch(/\b[A-Z][A-Z_]{4,}: /)
  await shot(page, '2-mission-09-report-completed')

  // Under 760 px the section list of the report and of the deliverables is a row of tabs above the content.
  await page.setViewportSize({ width: 600, height: 900 })
  await expectNarrowLayout(report, 'the report')
  await shot(page, '2-mission-10-report-narrow')
  await report.getByRole('button', { name: t['report.deliverables'] }).first().click()
  await expectNarrowLayout(page.locator('.deliverables-panel'), 'the deliverables')
  await shot(page, '2-mission-11-deliverables-narrow')
})
