import { ACTIONS } from '../src/ui/actions'
import { runFromMenu } from './bar'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

interface Mission {
  status: string
  tickets: { id: string; status: string }[]
  questions: { id: string; status: string }[]
}

/**
 * Journey 13: prove the delivery (ADR-0058). The fake developer leaves a PROVE.md with a step Floor42 could run and one
 * for the director. This daemon's sandbox does not work (no WSL distribution by design), so Floor42 runs nothing and
 * every step is the director's: the recipe shows each command with its copy button, the run says why it cannot run,
 * the director records that one step works and the other does not, records the verdict "not proven", and "Send back
 * to the producer" puts the ticket back to work with the director's words. Each is journaled.
 */
test('a delivery is proven step by step, and a failure goes back to its producer', async ({ page, daemon }) => {
  // On Linux the sandbox runs natively and works (ADR-0028): Floor42 then runs the steps itself, which this journey, built
  // on a machine with no WSL distribution, does not cover. The server tests cover the working sandbox.
  test.skip(process.platform === 'linux', 'the sandbox works on Linux: there is no "unavailable" recipe to journey through')
  test.setTimeout(180_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const created = await page.request.post('/api/missions', {
    headers,
    data: { title: 'Proof journey', objective: 'Write notes [fake:touch=notes.txt] [fake:prove]', repository: daemon.gitRepository() },
  })
  expect(created.status()).toBe(201)
  const id: string = (await created.json()).id
  const mission = async (): Promise<Mission> => (await page.request.get(`/api/missions/${id}`)).json()
  await expect.poll(async () => (await mission()).status).toBe('AWAITING_APPROVAL')
  expect((await page.request.post(`/api/missions/${id}/approve`, { headers, data: { noCap: true } })).status()).toBe(200)
  await expect(async () => {
    const m = await mission()
    for (const q of m.questions.filter((x) => x.status === 'OPEN'))
      await page.request.post(`/api/questions/${q.id}/answer`, { headers, data: { answer: 'ALLOW' } })
    expect(m.tickets.map((x) => x.status)).toEqual(m.tickets.map(() => 'DONE'))
  }).toPass({ timeout: 120_000, intervals: [1_000] })

  await page.evaluate((m) => localStorage.setItem('floor42.board.mission', m), id)
  await page.reload()
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  // Every ticket is done and nothing is proven: the waiting banner says so and its button opens the panel (log entry 19).
  const proveLine = page.locator('[data-testid="proof-line"]')
  await expect(proveLine).toContainText(t['waiting.prove'].replace('{mission}', id))
  await shot(page, '13-prove-00-banner')
  await runFromMenu(page, t, ACTIONS.find((a) => a.id === 'prove')!)
  const panel = page.locator('.prove-panel')
  await expect(panel).toBeVisible()

  // The recipe: the producer's two steps, both the director's here, each with its command and a copy button.
  await expect(panel.locator('.prove-step')).toHaveCount(2)
  await expect(panel.locator('.prove-step pre').first()).toHaveText('echo proof-ok')
  await expect(panel.locator('.prove-step').first()).toContainText(t['prove.reason.SANDBOX_UNAVAILABLE'])
  await expect(panel.getByRole('button', { name: t['prove.copy'] })).toHaveCount(2)
  await shot(page, '13-prove-01-recipe')

  // Floor42's run: nothing runs without the sandbox, and the page says so.
  await panel.locator('[data-section-tab="run"]').click()
  await expect(panel.getByRole('button', { name: t['prove.run'] })).toBeDisabled()
  await expect(panel).toContainText(t['prove.noSandbox'])
  await shot(page, '13-prove-02-run')

  // Your run: the first step works, the second does not, with what the director saw; each is recorded.
  await panel.locator('[data-section-tab="yours"]').click()
  const steps = panel.locator('.prove-step')
  await steps.nth(0).getByLabel(t['prove.works']).check()
  await steps.nth(0).getByRole('button', { name: t['prove.say'] }).click()
  await expect(steps.nth(0).locator('.prove-said')).toContainText(t['prove.works'])
  await steps.nth(1).getByLabel(t['prove.notWorks']).check()
  await steps.nth(1).locator('textarea').fill('curl: (7) Failed to connect to localhost port 8080')
  await steps.nth(1).getByRole('button', { name: t['prove.say'] }).click()
  await expect(steps.nth(1).locator('.prove-said')).toContainText('Failed to connect')
  await shot(page, '13-prove-03-yours')

  // The verdict, recorded, then the failure goes back to its producer as a directive on its own ticket.
  await panel.locator('[data-section-tab="verdict"]').click()
  await panel.getByLabel(t['prove.verdictNotProven']).check()
  await panel.getByRole('button', { name: t['prove.verdictRecord'] }).click()
  await expect(panel.locator('.prove-verdict')).toContainText(t['prove.verdictNotProven'])
  await panel.getByRole('button', { name: t['prove.sendBack'] }).first().click()
  const ticket = (await mission()).tickets[0].id
  await expect(panel.locator('[role="status"]')).toContainText(`${ticket} went back to`)
  await shot(page, '13-prove-04-sent-back')
  await expect.poll(async () => (await mission()).tickets.map((x) => x.id)).toEqual([ticket])
  const events: { type: string }[] = await (await page.request.get('/api/journal?limit=50')).json()
  expect(events.map((e) => e.type)).toEqual(expect.arrayContaining(['ProofStepWitnessed', 'ProofVerdictGiven', 'DirectiveGiven']))
})