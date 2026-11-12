import { ACTIONS } from '../src/ui/actions'
import { menuButton, PANEL_ACTIONS, PANEL_OF, runFromMenu, runFromPalette } from './bar'
import { CATALOGS, expect, expectOnScreen, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
})

test('every action of the bar names the panel it opens', () => {
  expect(PANEL_ACTIONS.filter((a) => !PANEL_OF[a.id]).map((a) => a.id), 'actions without a panel in e2e/bar.ts').toEqual(
    [],
  )
})

/**
 * Journeys 3a and 3c: each panel of the three menus opens on screen, makes room for the others (panels opened one
 * after the other replace each other, ui/panelLayout.ts), keeps its close button reachable and closes with Escape
 * (pressed on some panels only, to leave the others open under the next one).
 */
test('each panel opens from its menu, replaces the others and closes with Escape', async ({ page }) => {
  // All the main panels of the bar in one locator (the tutorial card is none).
  const MAIN_PANELS = PANEL_ACTIONS.filter((x) => x.id !== 'tutorial')
    .map((x) => PANEL_OF[x.id])
    .join(', ')
  let left: string | null = null // the panel still open when the next one is opened
  for (const [i, a] of PANEL_ACTIONS.entries()) {
    await test.step(a.id, async () => {
      await runFromMenu(page, t, a)
      const panel = page.locator(PANEL_OF[a.id])
      await expect(panel, `${a.id} opens`).toBeVisible()
      // Its title bar at least is on screen: a panel that opens off screen cannot be read nor moved.
      const title = panel.locator('.title').first()
      if (await title.count()) await expectOnScreen(title, `the title of ${a.id}`)
      if (a.id !== 'tutorial') {
        // The panel left open before is gone and this one is the only main panel: two never share the screen.
        if (left) await expect(page.locator(PANEL_OF[left]), `${left} makes room for ${a.id}`).toHaveCount(0)
        await expect(page.locator(MAIN_PANELS), `${a.id} is the only main panel`).toHaveCount(1)
        // Every main panel has a close button, and it can be reached.
        await expectOnScreen(panel.locator('.title button.close').first(), `the close button of ${a.id}`)
      }
      await shot(page, `3-menu-${String(i + 1).padStart(2, '0')}-${a.id}`)
      // Escape on every second panel, the tutorial card and its neighbours, and the last: the others stay open, so the
      // next opening proves it replaces them.
      const next = PANEL_ACTIONS[i + 1]
      if (i % 2 === 1 || !next || a.id === 'tutorial' || next.id === 'tutorial') {
        await page.keyboard.press('Escape')
        await expect(panel, `${a.id} closes with Escape`).toHaveCount(0)
        left = null
      } else left = a.id
    })
  }
  await expect(page.locator('.hud > .panel.framed')).toHaveCount(0)

  // Docs opens the API documentation in a new tab.
  const docs = ACTIONS.find((a) => a.href)!
  await menuButton(page, docs.group).click()
  const [tab] = await Promise.all([
    page.waitForEvent('popup'),
    page.locator('.bar-dropdown [role="menuitem"]', { hasText: t[docs.label] }).click(),
  ])
  await tab.waitForLoadState()
  expect(new URL(tab.url()).pathname).toMatch(/^\/q\/swagger-ui/)
  // /q/ needs the director (the signed-in cookie): the page and the contract it fetches both load.
  await expect(tab.locator('.swagger-ui .information-container .title')).toContainText('Floor42 API')
  await tab.close()
})

/** Journey 3b: the same panels through the command palette (Ctrl+K, the label, Enter). */
test('each panel opens from the palette and closes with Escape', async ({ page }) => {
  const misranked: string[] = []
  for (const a of PANEL_ACTIONS) {
    await test.step(a.id, async () => {
      const ran = await runFromPalette(page, t, a)
      if (ran !== null) misranked.push(`"${t[a.label]}" → first "${ran}"`)
      await expect(page.locator('.palette')).toHaveCount(0)
      const panel = page.locator(PANEL_OF[a.id])
      await expect(panel, `${a.id} opens from the palette`).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(panel, `${a.id} closes with Escape`).toHaveCount(0)
    })
  }
  if (misranked.length) {
    test.info().annotations.push({ type: 'palette: typed label not first', description: misranked.join(' | ') })
  }
  // The palette closes with Escape too.
  await page.keyboard.press('Control+k')
  await expect(page.locator('.palette')).toBeVisible()
  await shot(page, '3-palette')
  await page.keyboard.press('Escape')
  await expect(page.locator('.palette')).toHaveCount(0)
})
