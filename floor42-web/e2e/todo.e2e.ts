import { ACTIONS } from '../src/ui/actions'
import { runFromMenu } from './bar'
import { CATALOGS, expect, expectOnScreen, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en
const action = (id: string) => ACTIONS.find((a) => a.id === id)!

/**
 * Journey: the to-do panel under the event log shows a plan that waits for approval and its button opens the board;
 * then two panels are pinned side by side, stay open when another panel opens, and Escape leaves them alone.
 */
test('the to-do panel shows what waits and panels can be pinned', async ({ page, daemon }) => {
  test.setTimeout(120_000)
  page.on('dialog', (d) => void d.accept())
  const repo = daemon.gitRepository()
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const todo = page.locator('.hud > .panel.todo')
  await expect(todo).toBeVisible()
  // The journeys share a daemon: one that ran before may leave a mission waiting (prove sends a delivery back), so
  // this journey finds its own row by its mission, and checks each count against the rows shown.
  const total = todo.locator('[data-testid="todo-total"]')
  const approvals = todo.locator('[data-category="approvals"]')
  await expect(todo.locator('.empty').or(total)).toBeVisible()
  // Docked under the event log, in the same column.
  const [log, box] = [await page.locator('.hud > .panel.log').boundingBox(), await todo.boundingBox()]
  expect(box!.y, 'under the log').toBeGreaterThanOrEqual(log!.y + log!.height - 1)
  expect(Math.abs(box!.x + box!.width - (log!.x + log!.width)), 'same column').toBeLessThan(2)
  await expectOnScreen(todo.locator('.title'), 'the to-do title')

  // A mission whose plan waits for the director: no planner, so Floor42 proposes one ticket.
  await page.keyboard.press('d')
  const desk = page.locator('.desk-panel')
  await desk.locator('.tabs button', { hasText: t['desk.newMission'] }).click()
  const form = desk.locator('form.new-mission')
  await form.getByPlaceholder(t['mission.fieldTitle']).fill('Waiting plan')
  await form.getByPlaceholder(t['mission.fieldObjective']).fill('Wait for the director')
  await form.getByPlaceholder(t['mission.fieldRepository']).fill(repo)
  await form.locator('label', { hasText: t['mission.letGeoffreyPlan'] }).locator('input').uncheck()
  await form.getByRole('button', { name: t['mission.propose'], exact: true }).click()

  await expect.poll(async () => {
    const missions: { id: string; title: string }[] = await (await page.request.get('/api/missions')).json()
    return missions.some((m) => m.title === 'Waiting plan')
  }).toBe(true)
  const missions: { id: string; title: string }[] = await (await page.request.get('/api/missions')).json()
  const waiting = missions.filter((m) => m.title === 'Waiting plan').at(-1)!.id
  const row = approvals.locator('.row', { hasText: t['progress.item.APPROVE_PLAN'] }).filter({ hasText: waiting })
  await expect(row).toHaveCount(1, { timeout: 30_000 })
  await expect(approvals.locator('.count')).toHaveText(String(await approvals.locator('.row').count()))
  await expect(total).toHaveText(String(await todo.locator('.row').count()))
  await page.keyboard.press('Escape')
  await shot(page, '8-todo-01-waiting-plan')

  // A waiting plan is approved on the desk's Mission tab, with its cap: the row puts its mission on the board and opens it.
  await row.locator('button.action').click()
  const planDesk = page.locator('.desk-panel')
  await expect(planDesk).toBeVisible()
  await expect(planDesk.locator('.tabs button[data-tab="mission"]')).toHaveClass(/\bon\b/)
  await expect(planDesk.locator('.plan-cap')).toBeVisible()
  await shot(page, '8-todo-02-plan-on-desk')
  await page.keyboard.press('Escape')
  await expect(planDesk).toHaveCount(0)

  // Pin the costs panel, then open the usage panel: both stay.
  await runFromMenu(page, t, action('costs'))
  const costs = page.locator('.costs-panel')
  await expect(costs).toBeVisible()
  const pin = costs.locator('.title > button.pin')
  await expect(pin).toBeVisible()
  await pin.click()
  await expect(pin).toHaveAttribute('aria-pressed', 'true')
  await runFromMenu(page, t, action('usage'))
  const usage = page.locator('.panel.usage')
  await expect(usage).toBeVisible()
  await expect(costs, 'a pinned panel stays when another opens').toBeVisible()
  // Side by side: the usage panel is moved by its title bar to the left of the costs panel.
  const title = await usage.locator('.title').first().boundingBox()
  await page.mouse.move(title!.x + 20, title!.y + 8)
  await page.mouse.down()
  await page.mouse.move(60, 300, { steps: 6 })
  await page.mouse.up()
  await usage.locator('.title > button.pin').click()
  await expect(usage.locator('.title > button.pin')).toHaveAttribute('aria-pressed', 'true')
  await shot(page, '8-todo-03-two-pinned')

  // A third panel opens beside them, and Escape closes it but neither pinned panel.
  await runFromMenu(page, t, action('sessions'))
  await expect(page.locator('.sessions-panel')).toBeVisible()
  await expect(costs).toBeVisible()
  await expect(usage).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.locator('.sessions-panel')).toHaveCount(0)
  await expect(costs).toBeVisible()
  await expect(usage).toBeVisible()

  // Unpinned, a panel is a normal one again: the next panel replaces it.
  await costs.locator('.title > button.pin').click()
  await expect(costs.locator('.title > button.pin')).toHaveAttribute('aria-pressed', 'false')
  await runFromMenu(page, t, action('sessions'))
  await expect(costs).toHaveCount(0)
  await expect(usage).toBeVisible()
})
