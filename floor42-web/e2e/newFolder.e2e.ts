import { existsSync, mkdtempSync, realpathSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

/**
 * A new folder from the mission form's folder browser: the director browses a folder, names a new one, and the browser
 * opens it, ready to be chosen. A name Floor42 refuses says why, in the director's language, and creates nothing.
 */
test('the folder browser creates a folder and opens it', async ({ page, consoleErrors }) => {
  const parent = realpathSync(mkdtempSync(join(tmpdir(), 'floor42-e2e-folders-')))
  try {
    await page.setViewportSize({ width: 1600, height: 900 })
    await useLanguage(page, 'en')
    await page.goto('/')
    await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

    await page.keyboard.press('d')
    const desk = page.locator('.desk-panel')
    await desk.locator('.tabs button', { hasText: t['desk.newMission'] }).click()
    const form = desk.locator('form.new-mission')
    await form.getByPlaceholder(t['mission.fieldRepository']).fill(parent)
    await form.getByRole('button', { name: t['project.browse'] }).click()
    const browser = form.locator('.browser')
    await expect(browser.locator('.here b')).toHaveText(parent)

    // A name Windows reserves is refused by the daemon, said in words, and nothing is made.
    await browser.getByRole('button', { name: t['project.newFolder'] }).click()
    const name = browser.getByPlaceholder(t['project.newFolder.name'])
    await name.fill('CON')
    await name.press('Enter')
    await expect(browser.locator('.warn')).toContainText('is not a plain folder name')
    expect(existsSync(join(parent, 'CON'))).toBe(false)

    await name.fill('garden-planner')
    await browser.getByRole('button', { name: t['project.newFolder.create'] }).click()
    await expect(browser.locator('.here b')).toHaveText(join(parent, 'garden-planner'))
    await expect(browser.getByRole('button', { name: t['project.chooseWithoutGit'] })).toBeVisible()
    expect(existsSync(join(parent, 'garden-planner'))).toBe(true)
    await shot(page, 'new-folder-created')

    await browser.getByRole('button', { name: t['project.chooseWithoutGit'] }).click()
    await expect(form.getByPlaceholder(t['mission.fieldRepository'])).toHaveValue(join(parent, 'garden-planner'))
    await page.keyboard.press('Escape')
    // The browser logs the refused request (400) itself: that one is expected, nothing else.
    const refused = consoleErrors.filter((e) => /status of 400 .*\/api\/projects\/folders/.test(e))
    expect(refused.length, 'the refused name').toBe(1)
    consoleErrors.splice(0, consoleErrors.length, ...consoleErrors.filter((e) => !refused.includes(e)))
  } finally {
    rmSync(parent, { recursive: true, force: true })
  }
})
