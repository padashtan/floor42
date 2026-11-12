import { CATALOGS, expect, expectOnScreen, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

/**
 * What is missing on this machine: the checklist opens by itself once, at the first signed-in page of this browser,
 * in the sandbox panel. The daemon of the journeys points at a distribution that does not exist, so that row is
 * missing, with the command to copy; nothing on the screen runs it.
 */
test('the machine checklist opens once at the first start and names what is missing', async ({ page, consoleErrors }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  // The page decides at its stream's first "live" (App.vue): removed before that, the mark is set again by this page
  // and the reloaded one opens nothing. Seen in a full run: the stream went live 0.2 s after the removal, during the
  // reload (1.4 s on the busy daemon).
  await expect(page.locator('.panel.brand .stream.live')).toHaveCount(1)
  await page.evaluate(() => localStorage.removeItem('floor42.checklist.shown'))
  await page.reload()

  const panel = page.locator('.sandbox-panel')
  await expect(panel).toBeVisible()
  await expect(panel.getByText(t['checklist.title'])).toBeVisible()
  const claude = panel.locator('tr[data-row="host-claude"]')
  await expect(claude).toContainText(t['checklist.item.claude'])
  await expect(claude).toContainText(t['checklist.for.claude'])
  const distro = panel.locator('tr[data-row="host-distro"]')
  if (process.platform === 'win32') {
    await expect(distro).toContainText(t['checklist.state.MISSING'])
    await expect(distro.locator('code')).toHaveText('wsl --install -d floor42-e2e-no-such-distro')
    await expect(panel.getByText(t['checklist.notReady'])).toBeVisible()
  } else {
    await expect(distro).toContainText(t['checklist.state.OPTIONAL'])
  }
  await expect(panel.locator('tr[data-row="sandbox-chromium"]')).toContainText(t['checklist.for.chromium'])
  await expectOnScreen(panel.locator('.sub').first(), 'the checklist title')
  await shot(page, '9-checklist-first-start')

  // Seen once: closed, it does not come back at the next start.
  await panel.locator('.close').first().click()
  await expect(panel).toHaveCount(0)
  await page.reload()
  await expect(page.locator('.bar-nav')).toBeVisible()
  await expect(page.locator('.sandbox-panel')).toHaveCount(0)
  expect(consoleErrors).toEqual([])
})
