import { itemId, SECTIONS } from '../src/components/settingsSections'
import { PANEL_ACTIONS, runFromMenu } from './bar'
import type { Page } from '@playwright/test'
import { CATALOGS, expect, expectNarrowLayout, expectOnScreen, shot, test, useLanguage } from './fixtures'

// settings-redesign: the settings panel, section by section, at the narrowest supported width and a wide one.
const t = CATALOGS.en
const SETTINGS = PANEL_ACTIONS.find((a) => a.id === 'settings')!

for (const width of [1280, 1600]) {
  test(`each settings section opens and shows its settings at ${width} px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await useLanguage(page, 'en')
    await page.goto('/')
    await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
    await runFromMenu(page, t, SETTINGS)
    const panel = page.locator('.settings-panel')
    await expect(panel).toBeVisible()
    await expectOnScreen(panel.locator('.title'), 'the settings title')

    for (const [i, s] of SECTIONS.entries()) {
      await test.step(s.id, async () => {
        const tab = panel.locator(`[data-section-tab="${s.id}"]`)
        await tab.click()
        await expect(tab).toHaveAttribute('aria-selected', 'true')
        const section = panel.locator(`.settings-section[data-section="${s.id}"]`)
        await expect(section.locator('.section-name')).toHaveText(t[`sr.s.${s.id}`])
        for (const item of s.items) {
          await expect(section.locator(`[data-setting="${itemId(item)}"]`), `${itemId(item)} in ${s.id}`).toBeVisible()
        }
        // Nothing wider than the panel: no sideways scrolling, the panel inside the window.
        const content = panel.locator('.settings-content')
        const overflow = await content.evaluate((e) => e.scrollWidth - e.clientWidth)
        expect(overflow, `${s.id} overflows sideways`).toBeLessThanOrEqual(0)
        const box = (await panel.boundingBox())!
        expect(box.x >= 0 && box.x + box.width <= width, `the panel inside ${width} px`).toBe(true)
        await shot(page, `settings-after-${width}-${i + 1}-${s.id}`)
      })
    }
  })
}

async function openSettings(page: Page, lang: 'en' | 'fr' = 'en') {
  const c = CATALOGS[lang]
  await page.setViewportSize({ width: 1280, height: 900 })
  await useLanguage(page, lang)
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(c['top.liveReady'])
  await runFromMenu(page, c, SETTINGS)
  const panel = page.locator('.settings-panel')
  await expect(panel).toBeVisible()
  return panel
}

test('the settings answer the keyboard and the search', async ({ page }) => {
  const panel = await openSettings(page)
  const tab = (id: string) => panel.locator(`[data-section-tab="${id}"]`)

  // The panel opens with the focus on its section; the arrows, Home and End move through them, the focus follows.
  await expect(tab('general')).toBeFocused()
  await page.keyboard.press('ArrowDown')
  await expect(tab('missions')).toBeFocused()
  await expect(tab('missions')).toHaveAttribute('aria-selected', 'true')
  await page.keyboard.press('ArrowUp')
  await expect(tab('general')).toBeFocused()
  await page.keyboard.press('ArrowUp')
  await expect(tab('advanced')).toHaveAttribute('aria-selected', 'true')
  await page.keyboard.press('Home')
  await expect(tab('general')).toBeFocused()
  await expect(tab('general')).toHaveAttribute('aria-selected', 'true')
  await page.keyboard.press('End')
  await expect(tab('advanced')).toHaveAttribute('aria-selected', 'true')

  // A search shows the matches of every section; Escape empties it, then closes the panel.
  const search = panel.getByRole('searchbox', { name: t['sr.search'] })
  await search.fill('network')
  await expect(panel.locator('[data-setting="SANDBOX_NETWORK"]')).toBeVisible()
  await expect(panel.locator('[data-setting="PARALLEL_SESSIONS"]')).toHaveCount(0)
  await shot(page, 'settings-after-search')
  await search.press('Escape')
  await expect(search).toHaveValue('')
  await expect(panel).toBeVisible()

  // The session cap is edited in Caps, opened from its line: one main panel at a time, so Caps takes the settings'
  // place, and Escape closes it.
  await tab('missions').click()
  await panel.locator('[data-setting="SESSION_BUDGET_USD"] button', { hasText: t['sr.openCaps'] }).click()
  await expect(page.locator('.caps-panel')).toBeVisible()
  await expect(panel).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(page.locator('.caps-panel')).toHaveCount(0)
  // Closing Caps leads back to the settings it came from; one more Escape closes them.
  await expect(panel).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(panel).toHaveCount(0)
  // The focus goes back to the bar's menu the panel came from.
  await expect(page.locator('.bar-nav button[data-menu="system"]')).toBeFocused()
})

test('a draft survives a change of section, and a saved value shows its default', async ({ page }) => {
  const panel = await openSettings(page)
  const row = panel.locator('[data-setting="STALL_MINUTES"]')
  await panel.locator('[data-section-tab="missions"]').click()
  await row.locator('select').selectOption('10')
  await expect(row.locator('.tag.unsaved')).toHaveText(t['sr.unsaved'])
  await expect(panel.locator('[data-section-tab="missions"] .tab-count.unsaved')).toBeVisible()

  // Away and back: the draft is still there, and found again through a search.
  await panel.locator('[data-section-tab="safety"]').click()
  await panel.locator('[data-section-tab="missions"]').click()
  await expect(row.locator('select')).toHaveValue('10')
  await panel.getByRole('searchbox', { name: t['sr.search'] }).fill('stuck')
  await expect(row.locator('select')).toHaveValue('10')
  await panel.getByRole('searchbox', { name: t['sr.search'] }).fill('')

  // Closed and opened again: the draft is still there.
  await panel.locator('button.close').click()
  await expect(panel).toHaveCount(0)
  await runFromMenu(page, t, SETTINGS)
  await panel.locator('[data-section-tab="missions"]').click()
  await expect(row.locator('select')).toHaveValue('10')
  await expect(row.locator('.tag.unsaved')).toBeVisible()

  // Saved: changed by you, beside its default; then back to the default.
  await row.getByRole('button', { name: t['settings.save'] }).click()
  await expect(row).toHaveClass(/\bchanged\b/)
  await expect(row.locator('.tag.changed')).toHaveText(t['settings.changed'])
  await expect(row.locator('.facts')).toHaveText(t['settings.default'].replace('{value}', '5'))
  await expect(panel.locator('[data-section-tab="missions"] .tab-count.changed')).toHaveText('1')
  await shot(page, 'settings-after-changed')
  await row.getByRole('button', { name: t['settings.reset'] }).click()
  await expect(row).not.toHaveClass(/\bchanged\b/)
  await expect(row.locator('select')).toHaveValue('5')
})

test('a value the daemon refuses stays typed, with the refusal beside it', async ({ page, consoleErrors }) => {
  const panel = await openSettings(page)
  await panel.locator('[data-section-tab="missions"]').click()
  const row = panel.locator('[data-setting="MISSION_BUDGET_USD"]')
  const input = row.locator('input')
  const before = await input.inputValue()
  await input.fill('abc')
  await row.getByRole('button', { name: t['settings.save'] }).click()
  await expect(row.getByRole('alert')).toHaveText(t['sr.refused.MISSION_BUDGET_USD'])
  await expect(input, 'the typed value is kept').toHaveValue('abc')
  await expect(row).toHaveClass(/\brefused\b/)
  await expect(row).not.toHaveClass(/\bchanged\b/)
  await shot(page, 'settings-after-refused')
  // Typing again clears the refusal; the value in force is untouched.
  await input.fill(before)
  await expect(row.getByRole('alert')).toHaveCount(0)
  await expect(row.locator('.tag.unsaved')).toHaveCount(0)
  // The browser logs the refused request (400) itself: that one is expected, nothing else.
  const expected = consoleErrors.filter((e) => /status of 400/.test(e))
  expect(expected.length, 'the refused save').toBeLessThanOrEqual(1)
  consoleErrors.splice(0, consoleErrors.length, ...consoleErrors.filter((e) => !expected.includes(e)))
})

test('the tutorial is restarted and skipped from General', async ({ page }) => {
  const panel = await openSettings(page)
  const block = panel.locator('[data-setting="tutorial"]')
  await expect(block.locator('.name')).toHaveText(t['tutorial.title'])
  await block.getByRole('button', { name: t['tutorial.restart'] }).click()
  await expect(page.locator('.tutorial-card')).toBeVisible()
  await expect(block.locator('.line')).toHaveText(t['tutorial.state.running'])
  await block.getByRole('button', { name: t['tutorial.skip'] }).click()
  await expect(page.locator('.tutorial-card')).toHaveCount(0)
  await expect(block.locator('.line')).toHaveText(t['tutorial.state.skipped'])
  await expect(block.getByRole('button', { name: t['tutorial.skip'] })).toBeDisabled()
})

test('the settings sections become a row of tabs on a narrow screen', async ({ page }) => {
  const panel = await openSettings(page)
  await page.setViewportSize({ width: 600, height: 900 })
  await expectNarrowLayout(panel, 'settings')
  await shot(page, 'settings-narrow-600')
})

test('a French search finds a setting by an accented word', async ({ page }) => {
  const panel = await openSettings(page, 'fr')
  const search = panel.getByRole('searchbox', { name: CATALOGS.fr['sr.search'] })
  for (const word of ['réseau', 'RESEAU']) {
    await search.fill(word)
    await expect(panel.locator('[data-setting="SANDBOX_NETWORK"]'), word).toBeVisible()
    await expect(panel.locator('[data-setting="PARALLEL_SESSIONS"]'), word).toHaveCount(0)
  }
})
