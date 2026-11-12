import { menuButton, PANEL_ACTIONS, PANEL_OF, runFromMenu, runFromPalette } from './bar'
import { blocker, CATALOGS, expect, expectOnScreen, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en
const ACTIONS = PANEL_ACTIONS.filter((a) => a.id !== 'tutorial')

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
})

/** The boxes of the visible main panels never intersect, whatever opened them. */
test('no two visible main panels overlap', async ({ page }) => {
  for (const a of ACTIONS.filter((x) => ['board', 'desk', 'costs', 'settings'].includes(x.id))) {
    await runFromMenu(page, t, a)
  }
  const boxes = await page.locator('.hud > .panel.framed').evaluateAll((els) =>
    els.map((e) => {
      const r = e.getBoundingClientRect()
      return { name: e.className, l: r.left, t: r.top, r: r.right, b: r.bottom }
    }),
  )
  expect(boxes.map((b) => b.name), 'exactly one main panel is open').toHaveLength(1)
  // Whatever stays on screen with it (the log, the selection, the crew) never covers its close button.
  const close = page.locator('.hud > .panel.framed > .title button.close').first()
  await expectOnScreen(close, 'the close button of the open panel')
  const [panel] = boxes
  for (const dock of await page.locator('.hud > .panel:not(.framed):not(.brand):not(.toast):not(.help)').all()) {
    const d = await dock.boundingBox()
    if (!d) continue
    const apart = panel.r <= d.x || d.x + d.width <= panel.l || panel.b <= d.y || d.y + d.height <= panel.t
    if (!apart) expect(await blocker(close), 'a docked panel covers the close button').toBeNull()
  }
})

/** The Workflows panel keeps its own place over the frame's defaults (style.css): below the top bar, stacked at 24. */
test('the Workflows panel sits below the top bar, at its own stacking level', async ({ page }) => {
  await runFromMenu(page, t, ACTIONS.find((a) => a.id === 'workflows')!)
  const panel = page.locator(PANEL_OF.workflows)
  await expect(panel).toBeVisible()
  // The stylesheet's own level: a raised panel carries an inline one (ui/panels.ts), set aside here.
  const style = await panel.evaluate((e) => {
    ;(e as HTMLElement).style.zIndex = ''
    return { z: getComputedStyle(e).zIndex, top: getComputedStyle(e).top }
  })
  expect(style, 'its rule wins over .panel.framed').toEqual({ z: '24', top: '90px' })
})

/**
 * At 600 px (F1, F2, F3, F16 of the UI audit): every menu of the bar on screen, every panel inside the window with its
 * close button reachable, the keyboard help out of the way, the settings sections all on screen.
 */
test('at 600 px every menu is reachable and every panel fits the window', async ({ page }) => {
  await page.setViewportSize({ width: 600, height: 900 })
  for (const group of new Set(ACTIONS.map((a) => a.group))) await expectOnScreen(menuButton(page, group), `the ${group} menu`)
  await expect(page.locator('.hud > .panel.help'), 'the keyboard help gives way').toBeHidden()
  for (const a of ACTIONS) {
    await test.step(a.id, async () => {
      await runFromMenu(page, t, a)
      const panel = page.locator(PANEL_OF[a.id]).first()
      await expect(panel, `${a.id} opens`).toBeVisible()
      const box = (await panel.boundingBox())!
      expect(box.x >= 0 && box.x + box.width <= 600.5, `${a.id} at ${JSON.stringify(box)} fits the 600 px window`).toBe(true)
      await expectOnScreen(panel.locator('.title button.close').first(), `the close button of ${a.id}`)
      if (a.id === 'settings') {
        await expectOnScreen(panel.locator('.sectioned-nav button').last(), 'the last settings section')
        await shot(page, 'layout-600-settings')
      }
    })
  }
})

/** A panel opened from the keyboard takes the focus on its frame, and the frame shows it (F6): a visible ring. */
test('a panel opened from the keyboard shows a focus ring on its frame', async ({ page }) => {
  expect(await runFromPalette(page, t, ACTIONS.find((a) => a.id === 'costs')!), 'the palette ran Costs').toBeNull()
  const panel = page.locator(PANEL_OF.costs)
  await expect(panel).toBeVisible()
  await expect(panel).toBeFocused()
  const ring = await panel.evaluate((e) => {
    const s = getComputedStyle(e)
    return { style: s.outlineStyle, width: parseFloat(s.outlineWidth), visible: e.matches(':focus-visible') }
  })
  expect(ring, 'a solid ring of 2 px on the frame').toEqual({ style: 'solid', width: 2, visible: true })
})

/** A size saved on a wide screen never makes a panel wider than a narrow window (ui/panels.ts, sizedStyle). */
test('a panel resized on a wide screen still fits a 600 px window', async ({ page }) => {
  await page.evaluate(() => localStorage.setItem('floor42.panel-sizes', JSON.stringify({ 'board-panel': { width: 1400, height: 700 } })))
  await page.reload()
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await runFromMenu(page, t, ACTIONS.find((a) => a.id === 'board')!)
  const panel = page.locator(PANEL_OF.board)
  await expect(panel).toHaveClass(/\bsized\b/)
  await page.setViewportSize({ width: 600, height: 900 })
  const box = (await panel.boundingBox())!
  expect(box.width, `the saved size, ${JSON.stringify(box)}`).toBeLessThanOrEqual(600)
})
