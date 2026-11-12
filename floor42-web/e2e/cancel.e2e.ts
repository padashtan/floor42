import type { Page } from '@playwright/test'
import { ACTIONS } from '../src/ui/actions'
import { runFromMenu } from './bar'
import { CATALOGS, expect, test, useLanguage } from './fixtures'

const t = CATALOGS.en
const action = (id: string) => ACTIONS.find((a) => a.id === id)!

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
})

async function openNewMission(page: Page) {
  await page.keyboard.press('d')
  const desk = page.locator('.desk-panel')
  await expect(desk).toBeVisible()
  await desk.locator('.tabs button', { hasText: t['desk.newMission'] }).click()
  return desk.locator('form.new-mission')
}

/** Journey 4a: what the director typed outlives the panel that another one replaced; "Clear the form" empties it. */
test('a replaced form keeps what was typed, and the form can be cleared', async ({ page }) => {
  let form = await openNewMission(page)
  await form.getByPlaceholder(t['mission.fieldTitle']).fill('Kept title')
  await form.locator('label', { hasText: t['mission.letGeoffreyPlan'] }).locator('input').uncheck()
  await runFromMenu(page, t, action('board'))
  await expect(page.locator('.board-panel')).toBeVisible()
  await expect(page.locator('.desk-panel'), 'the board took the desk\'s place').toHaveCount(0)

  form = await openNewMission(page)
  await expect(form.getByPlaceholder(t['mission.fieldTitle'])).toHaveValue('Kept title')
  const plan = form.locator('label', { hasText: t['mission.letGeoffreyPlan'] }).locator('input')
  await expect(plan).not.toBeChecked()

  await form.getByRole('button', { name: t['ux.clearForm'] }).click()
  await expect(form.getByPlaceholder(t['mission.fieldTitle'])).toHaveValue('')
  await expect(plan, 'the choices go back to their defaults too').toBeChecked()
  await expect(form.getByRole('button', { name: t['ux.clearForm'] })).toHaveCount(0)
})

test('a half typed hire survives another panel taking its place, and Cancel throws it away', async ({ page }) => {
  await runFromMenu(page, t, action('hire'))
  const hire = page.locator('.hire-panel:not(.core-dismissal)')
  await hire.getByPlaceholder(t['hire.namePlaceholder']).fill('Ada')
  await runFromMenu(page, t, action('board'))
  await expect(hire).toHaveCount(0)
  await runFromMenu(page, t, action('hire'))
  await expect(hire.getByPlaceholder(t['hire.namePlaceholder'])).toHaveValue('Ada')
  await hire.getByRole('button', { name: t['common.cancel'] }).click()
  await expect(hire).toHaveCount(0)
  await runFromMenu(page, t, action('hire'))
  await expect(hire.getByPlaceholder(t['hire.namePlaceholder'])).toHaveValue('')
})

/** Journey 4b: a panel that cannot load says so and offers a retry, never an empty form. */
test('Caps shows the failure of its load and Try again brings the form', async ({ page, consoleErrors }) => {
  const settings = (url: URL) => url.pathname === '/api/settings'
  await page.route(settings, (route) => route.fulfill({ status: 500, json: { code: 'BOOM', message: 'the daemon said no' } }))
  await runFromMenu(page, t, action('caps'))
  const caps = page.locator('.caps-panel')
  await expect(caps.locator('.caps-load-error')).toContainText('the daemon said no')
  await expect(caps.locator('.caps-load-error')).toContainText(t['ux.retry'])
  await expect(caps.locator('input[type="number"][max="100"]'), 'no session form without its settings').toHaveCount(0)

  await page.unroute(settings)
  await caps.getByRole('button', { name: t['ux.retry'] }).click()
  await expect(caps.locator('.caps-load-error')).toHaveCount(0)
  await expect(caps.locator('input[type="number"][max="100"]').first()).toBeVisible()
  // The refused call is the one the page logged: nothing else may be.
  consoleErrors.splice(0, consoleErrors.length, ...consoleErrors.filter((e) => !e.includes('500')))
})

/** Journey 4c: Caps opened from the settings leads back to them. */
test('Escape in Caps opened from the settings goes back to the settings, then closes them', async ({ page }) => {
  await runFromMenu(page, t, action('settings'))
  const settings = page.locator('.settings-panel')
  await expect(settings).toBeVisible()
  await settings.locator('[data-section-tab="missions"]').click()
  await settings.locator('[data-setting="SESSION_BUDGET_USD"] button', { hasText: t['sr.openCaps'] }).click()
  await expect(page.locator('.caps-panel')).toBeVisible()
  await expect(settings, 'Caps takes the settings\' place').toHaveCount(0)

  await page.keyboard.press('Escape')
  await expect(page.locator('.caps-panel')).toHaveCount(0)
  await expect(settings, 'closing Caps brings the settings back').toBeVisible()
  // The focus is on the reopened panel, not lost on the page.
  await expect(page.locator('.hud > .panel.framed > .title button.close').first()).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(settings).toHaveCount(0)
  await expect(page.locator('.bar-nav button[data-menu="system"]'), 'the focus goes back to the menu').toBeFocused()
})

/** The agents a journey of this file hired: let go after it, so the shared daemon's crew is as the next file expects. */
const hired: string[] = []

test.afterEach(async ({ page, daemon }) => {
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  const staff: { id: string; name: string }[] = await (await page.request.get('/api/agents')).json()
  for (const name of hired.splice(0)) {
    for (const a of staff.filter((x) => x.name === name)) {
      const left = await page.request.post(`/api/agents/${a.id}/dismiss`, { headers, data: { reason: 'journey over' } })
      expect(left.status(), `${name} let go after the journey`).toBe(200)
    }
  }
})

/** An agent on the crew, hired from the bar: the target of the profile and of the right click menu. */
async function hireOne(page: Page, name: string) {
  hired.push(name)
  await runFromMenu(page, t, action('hire'))
  const hire = page.locator('.hire-panel:not(.core-dismissal)')
  await hire.getByPlaceholder(t['hire.namePlaceholder']).fill(name)
  await hire.locator('button[type="submit"]').click()
  await expect(hire).toHaveCount(0)
  const card = page.locator('.crew .card', { hasText: name }).first()
  await expect(card).toBeVisible()
  return card
}

/** Journey 4d: the context menu of a crew card closes on a press anywhere outside it, and not on a press inside. */
test('the context menu closes on a press outside it', async ({ page }) => {
  const card = await hireOne(page, 'Menu Tester')
  const menu = page.locator('.panel.menu')
  await card.click({ button: 'right' })
  await expect(menu).toBeVisible()
  // A real press (pointerdown, then up) on the menu's own edge, away from its items: inside, so it stays open.
  await menu.click({ position: { x: 2, y: 2 } })
  await expect(menu, 'a press inside the menu does not close it').toBeVisible()
  await page.locator('.panel.brand b', { hasText: 'FLOOR42' }).click()
  await expect(menu).toHaveCount(0)
})

/** Journey 4e: an agent's settings, opened from its profile, lead back to the profile. */
test('Escape in the agent settings goes back to the profile', async ({ page }) => {
  const card = await hireOne(page, 'Back Tester')
  await card.dblclick()
  const profile = page.locator('.profile-panel')
  await expect(profile).toBeVisible()
  await profile.getByRole('button', { name: t['profile.settings'] }).click()
  const settings = page.locator('.agent-settings')
  await expect(settings).toBeVisible()
  await expect(profile, "the agent settings take the profile's place").toHaveCount(0)

  await page.keyboard.press('Escape')
  await expect(settings).toHaveCount(0)
  await expect(profile, 'closing them brings the profile back').toBeVisible()
  await expect(profile.locator('.profile-name'), "the same agent's profile").toContainText('Back Tester')
  await page.keyboard.press('Escape')
  await expect(profile).toHaveCount(0)
})
