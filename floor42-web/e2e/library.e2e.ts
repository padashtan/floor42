import type { Page } from '@playwright/test'
import type { ProjectView } from '../src/api/types'
import { recentProjects } from '../src/ui/recent'
import { PANEL_ACTIONS, runFromMenu } from './bar'
import { CATALOGS, expect, RESULTS, shot, test, useLanguage } from './fixtures'
import { clearAway } from './seed'

const t = CATALOGS.en
const action = PANEL_ACTIONS.find((a) => a.id === 'library')!
const CANVAS_ONLY = 'body * { visibility: hidden !important } canvas.view { visibility: visible !important }'

/** Eight projects with a name each: a shelf worth looking at. */
const FIXTURE_PROJECTS = ['atlas', 'harbour-api', 'notes-app', 'paperplane', 'orchard', 'signal-desk', 'kiln', 'lantern'].map((name, i) => ({
  path: `C:\\Users\\director\\work\\${name}`,
  missions: Array.from({ length: i + 1 }, (_, k) => `M-${100 + k}`),
  lastTitle: `Work on ${name}`,
  office: false,
  exists: i !== 6,
  git: null,
  lastMission: `M-${100 + i}`,
  lastActivity: `2026-11-0${(i % 3) + 1}T09:00:00Z`,
  pinned: i === 1,
  forgotten: false,
}))

/** The bookcase's place on the canvas, as the scene reports it. */
async function bookcase(page: Page): Promise<{ x: number; y: number }> {
  await expect(page.locator('canvas.view')).toHaveAttribute('data-mascots', /library:\d+,\d+/)
  const raw = (await page.locator('canvas.view').getAttribute('data-mascots')) ?? ''
  const [x, y] = /library:(\d+),(\d+)/.exec(raw)!.slice(1).map(Number)
  return { x, y }
}

/**
 * Shows the office in another decade in this page only: the settings read says so. The daemon's own setting is shared by
 * every journey running at once and is left alone.
 */
let decade: string | null = null
async function showDecade(page: Page, theme: string) {
  decade = theme
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme, { timeout: 15_000 })
}

/** The bookcase and what is around it, on its own: panels and bars hidden. */
async function shelf(page: Page, name: string) {
  await clearAway(page)
  await page.keyboard.press('Escape')
  const at = await bookcase(page)
  const style = await page.addStyleTag({ content: CANVAS_ONLY })
  const clip = { x: Math.max(0, at.x - 200), y: Math.max(0, at.y - 220), width: 400, height: 340 }
  await page.screenshot({ path: `${RESULTS}/library-bookcase-${name}.png`, clip })
  await style.evaluate((n) => (n as Element).remove())
}

test('the library opens from the bar and from the bookcase, and a pick fills the new mission form', async ({ page, daemon }) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  // The journey makes no mission of its own: a mission left in the shared daemon changes what other journeys see.
  // A real folder (a throwaway repository, no mission) is the one picked: the form reads what git says of it.
  const repo = daemon.gitRepository()
  const mine = { ...FIXTURE_PROJECTS[1], path: repo, lastTitle: 'Library journey' }
  // Fixtures make the shelf, as a director's own recent list would. The daemon's own projects stay on it: the journeys
  // share a daemon, and one that ran before (the archives, a mission) leaves its repositories there.
  let served: ProjectView[] = []
  await page.route(/\/api\/settings$/, async (route) => {
    const response = await route.fetch()
    const list = await response.json()
    if (decade) for (const x of list) if (x.key === 'OFFICE_THEME') x.value = decade
    await route.fulfill({ response, json: list })
  })
  await page.route(/\/api\/projects$/, async (route) => {
    const response = await route.fetch()
    const list = await response.json()
    served = [mine, ...list, ...FIXTURE_PROJECTS]
    await route.fulfill({ response, json: served })
  })
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await clearAway(page)

  // From the bar: the panel has the frame, the intro line and the search of Settings; the books are drawn.
  await runFromMenu(page, t, action)
  const panel = page.locator('.library-panel')
  await expect(panel).toBeVisible()
  await expect(panel.locator('.sectioned-top .hint')).not.toHaveText('')
  await expect(panel.locator('input.sectioned-search')).toBeVisible()
  await expect(panel.locator('.book[data-project]')).toHaveCount(recentProjects(served).length)
  expect(recentProjects(served).length).toBeGreaterThanOrEqual(1 + FIXTURE_PROJECTS.length)
  await expect(panel.locator('.book .cover svg').first()).toBeVisible()
  await shot(page, 'library-gallery')
  await panel.locator('input.sectioned-search').fill('Library journey')
  await expect(panel.locator('.book[data-project]')).toHaveCount(1)
  await shot(page, 'library-gallery-search')
  await panel.locator('input.sectioned-search').fill('')

  // A pick opens the new mission form on that project.
  await panel.locator(`.book[data-project="${repo.replace(/\\/g, '\\\\')}"]`).click()
  await expect(panel).toHaveCount(0)
  const desk = page.locator('.desk-panel')
  await expect(desk).toBeVisible()
  const form = desk.locator('form.new-mission')
  await expect(form.getByPlaceholder(t['mission.fieldRepository'])).toHaveValue(repo)
  await shot(page, 'library-new-mission')
  await page.keyboard.press('Escape')

  // "Another folder" opens the folder browser of the form.
  await runFromMenu(page, t, action)
  await page.locator('.library-panel .book.other').click()
  await expect(page.locator('.desk-panel form.new-mission .browser')).toBeVisible()
  await page.keyboard.press('Escape')

  // From the bookcase: hover names it, a click opens the library.
  const at = await bookcase(page)
  await page.mouse.move(at.x, at.y)
  await expect(page.locator('.tooltip')).toHaveText(t['hover.library'])
  await page.mouse.click(at.x, at.y)
  await expect(page.locator('.library-panel')).toBeVisible()
  await shot(page, 'library-from-bookcase')
  await page.keyboard.press('Escape')

  // The bookcase in two decades, one spine per project.
  for (const theme of ['1980s', '2020s']) {
    await showDecade(page, theme)
    await shelf(page, theme)
  }
})
