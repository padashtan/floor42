import type { Page } from '@playwright/test'
import { PANEL_ACTIONS, runFromMenu } from './bar'
import { CATALOGS, expect, expectOnScreen, shot, test, useLanguage } from './fixtures'
import { clearAway, seedMeeting, seedReport, seedSandbox, seedSkills } from './seed'

const t = CATALOGS.en
const action = (id: string) => PANEL_ACTIONS.find((a) => a.id === id)!

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
})

/** Reloads so the seeded answers fill the page, then closes the away summary: nothing sits behind the panel. */
async function reloadClean(page: Page) {
  await page.reload()
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await clearAway(page)
}

/** A panel on the Settings model: its frame, its intro line and search field, then each section in turn. */
async function sections(page: Page, selector: string, id: string, ids: string[]) {
  await clearAway(page)
  const settings = await settingsFrame(page)
  await runFromMenu(page, t, action(id))
  const panel = page.locator(selector)
  await expect(panel).toBeVisible()
  await expectOnScreen(panel.locator('.title'), `the title of ${id}`)
  await expect(panel.locator('.sectioned-top .hint'), `${id} says what it is for`).not.toHaveText('')
  await expect(panel.locator('input.sectioned-search')).toBeVisible()
  const frame = await panel.boundingBox()
  expect(Math.abs(frame!.width - settings.width), `${id} has the width of Settings`).toBeLessThan(2)
  expect(Math.abs(frame!.height - settings.height), `${id} has the height of Settings`).toBeLessThan(2)
  expect(Math.abs(frame!.y - settings.y), `${id} sits where Settings sits`).toBeLessThan(2)
  for (const s of ids) {
    const tab = panel.locator(`[data-section-tab="${s}"]`)
    await tab.click()
    await expect(tab).toHaveAttribute('aria-selected', 'true')
    await shot(page, `panels-sidebar-${id}-${s}`)
  }
  return panel
}

let settingsBox: { width: number; height: number; y: number } | null = null
/** The frame of the Settings panel at this window size: the model the others must match. */
async function settingsFrame(page: Page) {
  if (settingsBox) return settingsBox
  await runFromMenu(page, t, action('settings'))
  const panel = page.locator('.settings-panel')
  await expect(panel).toBeVisible()
  const box = (await panel.boundingBox())!
  await clearAway(page)
  await shot(page, 'panels-sidebar-settings')
  await page.keyboard.press('Escape')
  await expect(panel).toHaveCount(0)
  settingsBox = { width: box.width, height: box.height, y: box.y }
  return settingsBox
}

test('the sandbox panel lists its sections, filled', async ({ page }) => {
  await seedSandbox(page)
  await reloadClean(page)
  const panel = await sections(page, '.sandbox-panel', 'sandbox', ['machine', 'tools', 'install', 'environment'])
  // The search reaches the tools and the machine rows.
  await panel.locator('input.sectioned-search').fill('maven')
  await expect(panel.locator('table.sandbox-tools tbody tr').first()).toContainText('maven')
  await shot(page, 'panels-sidebar-sandbox-search')
  await panel.locator('input.sectioned-search').fill('')
  await page.keyboard.press('Escape')
  await expect(panel).toHaveCount(0)
})

test('the skills panel lists its sections, filled', async ({ page }) => {
  await seedSkills(page)
  await reloadClean(page)
  const panel = await sections(page, '.skills-panel', 'skills', ['installed', 'sessions', 'plugins', 'suggestions'])
  await panel.locator('[data-section-tab="installed"]').click()
  await panel.locator('input.sectioned-search').fill('review')
  await expect(panel.locator('.skills-table tbody tr')).toHaveCount(2)
  await shot(page, 'panels-sidebar-skills-search')
  await panel.locator('input.sectioned-search').fill('')
  await page.keyboard.press('Escape')
  await expect(panel).toHaveCount(0)
})

test('the meeting room shows a recorded committee, its debate newest first', async ({ page, daemon }) => {
  test.setTimeout(180_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url, 'Idempotency-Key': 'sidebar-journey-2' }
  const roles: { key: string }[] = await (await page.request.get('/api/committee/roles')).json()
  const created = await page.request.post('/api/missions', {
    headers,
    data: {
      title: 'Sidebar journey',
      objective: 'Choose a storage',
      repository: daemon.gitRepository(),
      committee: ['security', 'developer'].filter((k) => roles.some((r) => r.key === k)),
      plan: false,
      panel: 'architecture',
    },
  })
  expect(created.status(), await created.text()).toBe(201)
  const id: string = (await created.json()).id
  await expect
    .poll(async () => (await (await page.request.get(`/api/missions/${id}`)).json()).committee !== null, { timeout: 60_000 })
    .toBe(true)
  await seedMeeting(page, id)
  await reloadClean(page)
  const panel = await sections(page, '.panel.meeting', 'meeting', ['committee', 'debate', 'history'])
  // Newest first: the decision record, then round 2, then round 1.
  await panel.locator('[data-section-tab="debate"]').click()
  const heads = await panel.locator('.round > .sub').allInnerTexts()
  expect(heads[0].toLowerCase()).toContain('galen')
  const at = (n: number) => heads.findIndex((h) => h.toLowerCase().startsWith(`round ${n}`))
  expect(at(2)).toBeGreaterThan(0)
  expect(at(2)).toBeLessThan(at(1))
  await panel.locator('.contribution').first().click()
  await shot(page, 'panels-sidebar-meeting-debate-read')
})

test('the end-of-mission report, filled', async ({ page, daemon }) => {
  test.setTimeout(180_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url, 'Idempotency-Key': 'sidebar-journey-3' }
  const created = await page.request.post('/api/missions', {
    headers,
    data: { title: 'Report journey', objective: 'Describe it', repository: daemon.gitRepository(), committee: [], plan: false, panel: 'architecture' },
  })
  expect(created.status(), await created.text()).toBe(201)
  const id: string = (await created.json()).id
  await seedReport(page, id)
  await reloadClean(page)
  await page.keyboard.press('b')
  const board = page.locator('.board-panel')
  await expect(board).toBeVisible()
  await board.getByRole('button', { name: t['board.openReport'] }).first().click()
  const panel = page.locator('.mission-end')
  await expect(panel).toBeVisible()
  await clearAway(page)
  await expect(panel.locator('.sectioned-top .hint')).not.toHaveText('')
  for (const s of ['deliverables', 'tickets', 'reviews', 'cost', 'retro', 'next']) {
    await panel.locator(`[data-section-tab="${s}"]`).click()
    await shot(page, `panels-sidebar-report-${s}`)
  }
  // Newest first: the latest check is on top.
  await panel.locator('[data-section-tab="reviews"]').click()
  const commands = await panel.locator('.me-row .main code').allInnerTexts()
  expect(commands[0]).toBe('npx vue-tsc -b --noEmit')
})
