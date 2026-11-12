import type { Page } from '@playwright/test'
import { ACTIONS } from '../src/ui/actions'
import { runFromMenu } from './bar'
import { CATALOGS, expect, expectOnScreen, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

interface JournalEntry {
  origin: string
  mission: string | null
  type: string
}

const action = (id: string) => ACTIONS.find((a) => a.id === id)!

/** The card shows this step: its number, its title, the ring on the element it is about. */
async function atStep(page: Page, n: number, id: string): Promise<void> {
  const card = page.locator('.tutorial-card')
  await expect(card.locator('h3')).toHaveText(t[`tutorial.${id}.title` as keyof typeof t])
  await expect(card.locator('.title')).toContainText(`step ${n} of 13`)
  await expectOnScreen(card.locator('h3'), `step ${n}`)
  await shot(page, `4-tutorial-${String(n).padStart(2, '0')}-${id}`)
}

/** Closes a panel with its own × button: the focus may be in the guide's card, where Escape hides the guide. */
async function close(page: Page, panel: string): Promise<void> {
  await page.locator(`${panel} .close`).first().click()
  await expect(page.locator(panel)).toHaveCount(0)
}

async function deskTab(page: Page, tab: string): Promise<void> {
  await page.locator(`.desk-panel .tabs button[data-tab="${tab}"]`).click()
}

/**
 * Journey 4: the guided first mission, offered on the first signed-in page, completed in thirteen steps by the
 * director's own clicks on the real screen. The mission is simulated on Floor42's sample repository: every event of
 * it is SIMULATED, and the costs read 0.
 */
test('the guided first mission goes from the offer to going real', async ({ page }) => {
  test.setTimeout(240_000)
  page.on('dialog', (d) => void d.accept())
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  // This browser never saw the tutorial: the offer comes once.
  await page.evaluate(() => {
    localStorage.removeItem('floor42.tutorial.offered')
    localStorage.removeItem('floor42.tutorial')
  })
  await page.reload()
  const offer = page.locator('.tutorial-offer')
  await expect(offer).toBeVisible()
  await shot(page, '4-tutorial-00-offer')
  await offer.getByRole('button', { name: t['tutorial.offer.start'] }).click()
  const card = page.locator('.tutorial-card')
  const ring = page.locator('.tutorial-ring')
  const next = card.getByRole('button', { name: t['tutorial.next'], exact: true })

  await atStep(page, 1, 'signIn')
  await expect(ring).toBeVisible()
  await next.click()

  await atStep(page, 2, 'openDesk')
  await expect(card.getByRole('button', { name: t['tutorial.next'], exact: true })).toHaveCount(0)
  await page.locator('.bar-nav button.desk').click()

  await atStep(page, 3, 'sample')
  await deskTab(page, 'new')
  const practice = page.locator('.practice-mission')
  await practice.getByRole('button', { name: t['tutorial.practice.prepare'] }).click()
  await expect(practice.locator('code')).toContainText('tutorial')

  await atStep(page, 4, 'objective')
  await practice.getByPlaceholder(t['mission.fieldTitle']).fill('My first mission')
  await practice.getByPlaceholder(t['tutorial.practice.objective']).fill('Inventory the sample and leave notes')
  await practice.getByRole('button', { name: t['tutorial.practice.start'] }).click()

  await atStep(page, 5, 'plan')
  await deskTab(page, 'mission')
  const desk = page.locator('.desk-panel')
  await expect(desk.locator('.plan')).toContainText('Inspect the sample repository')
  const id = ((await desk.locator('.hint b').first().innerText()) || '').trim()
  expect(id).toMatch(/^M-\d+$/)
  await next.click()

  await atStep(page, 6, 'permissions')
  await deskTab(page, 'permissions')
  await expect(desk.locator('table.permissions')).toBeVisible()
  await next.click()

  await atStep(page, 7, 'approve')
  // Without a cap decision the daemon refuses the approval, as it would for a real mission.
  const headers = { Origin: new URL(page.url()).origin, 'X-Floor42-Request': '1' }
  const refused = await page.request.post(`/api/missions/${id}/approve`, { headers })
  expect(refused.status()).toBe(409)
  expect((await refused.json()).code).toBe('CAP_NEEDED')
  await deskTab(page, 'mission')
  const cap = desk.locator('.plan-cap')
  await cap.locator('input[inputmode="decimal"]').fill('1')
  await cap.locator('button.action').click()

  await atStep(page, 8, 'question')
  await deskTab(page, 'decide')
  const question = desk.locator('.question', { hasText: 'README' })
  await question.getByRole('button', { name: 'Yes', exact: true }).click()

  await atStep(page, 9, 'allowWrite')
  await deskTab(page, 'decide')
  await desk.locator('.question').first().getByRole('button', { name: t['option.ALLOW'], exact: true }).click()

  await atStep(page, 10, 'workshop')
  // The card took the focus: Escape there hides the guide, which comes back from the Work menu where it was.
  await expect(card).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(card).toHaveCount(0)
  await expect(desk, 'Escape in the card hides the guide only').toBeVisible()
  await close(page, '.desk-panel')
  await page.keyboard.press('w')
  // The director lets the agents finish their tickets before deciding on the change.
  await expect
    .poll(async () => (await (await page.request.get(`/api/missions/${id}`)).json()).tickets.map((x: { status: string }) => x.status), {
      timeout: 30_000,
    })
    .toEqual(['DONE', 'DONE'])
  const workshop = page.locator('.panel.workshop')
  await expect(workshop.getByText('notes.txt').first()).toBeVisible({ timeout: 30_000 })
  const accept = workshop.locator('.block', { hasText: t['ws.accept'] }).locator('button.action')
  await expect(accept).toBeEnabled()
  await accept.click()
  await expect(accept).toBeDisabled()
  // Decided while the guide was hidden: reopened, it reads the decision from the daemon and goes on.
  await close(page, '.panel.workshop')
  await runFromMenu(page, t, action('tutorial'))

  await atStep(page, 11, 'deliverables')
  await runFromMenu(page, t, action('deliverables'))
  await expect(page.locator('.deliverables-panel')).toBeVisible()
  await close(page, '.deliverables-panel')
  await page.keyboard.press('b')
  await page.locator('.board-panel').getByRole('button', { name: t['board.openReport'] }).first().click()
  await expect(page.locator('.mission-end')).toBeVisible()
  await shot(page, '4-tutorial-11-report')
  await close(page, '.mission-end')
  await next.click()

  await atStep(page, 12, 'costs')
  await runFromMenu(page, t, action('costs'))
  const row = page.locator('.costs-panel tr.simulated', { hasText: id })
  await expect(row).toContainText(t['cost.simulated'])
  await expect(row.locator('td').nth(1)).toHaveText('0')
  await next.click()

  await atStep(page, 13, 'goingReal')
  await expect(card.locator('.checklist li')).toHaveCount(3)
  await close(page, '.costs-panel')
  await card.getByRole('button', { name: t['tutorial.finish'] }).click()
  await expect(card).toHaveCount(0)
  expect(await page.evaluate(() => localStorage.getItem('floor42.tutorial'))).toBe('{"kind":"finished"}')

  // Every event of the rehearsal is simulated.
  const entries: JournalEntry[] = await (await page.request.get('/api/journal?limit=1000')).json()
  const ofMission = entries.filter((e) => e.mission === id)
  expect(ofMission.length).toBeGreaterThan(10)
  expect(ofMission.filter((e) => e.origin !== 'SIMULATED').map((e) => e.type)).toEqual([])
})

test('the tutorial resumes after a reload, and the palette skips or restarts it', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await runFromMenu(page, t, action('tutorial'))
  const card = page.locator('.tutorial-card')
  await card.getByRole('button', { name: t['tutorial.next'], exact: true }).click()
  await expect(card.locator('h3')).toHaveText(t['tutorial.openDesk.title'])
  await page.reload()
  await runFromMenu(page, t, action('tutorial'))
  await expect(card.locator('h3'), 'resumed where it was left').toHaveText(t['tutorial.openDesk.title'])

  const palette = async (label: string) => {
    await page.keyboard.press('Control+k')
    await page.locator('.palette input').fill(label)
    await page.keyboard.press('Enter')
  }
  await palette(t['tutorial.skip'])
  await expect(card).toHaveCount(0)
  await palette(t['tutorial.restart'])
  await expect(card.locator('h3')).toHaveText(t['tutorial.signIn.title'])
  await page.keyboard.press('Escape')
  await expect(card).toHaveCount(0)
})

test('Escape in the guide hides the guide, even over a panel opened after the settings', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await runFromMenu(page, t, action('settings'))
  await runFromMenu(page, t, action('sessions'))
  const sessions = page.locator('.sessions-panel')
  await expect(sessions).toBeVisible()
  await runFromMenu(page, t, action('tutorial'))
  const card = page.locator('.tutorial-card')
  await expect(card).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(card, 'the guide handles the Escape typed in it').toHaveCount(0)
  await expect(sessions, 'the panel opened last stays').toBeVisible()
  // One main panel at a time: the settings made room for the sessions (ui/panelLayout.ts).
  await expect(page.locator('.settings-panel')).toHaveCount(0)
})
