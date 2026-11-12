import type { Page } from '@playwright/test'
import { CATALOGS, expect, RESULTS, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

/** Where the scene says the robot, the cat, the dog and the library's bookcase are on screen (canvas data, four times a second). */
async function marks(page: Page): Promise<Record<string, { x: number; y: number }>> {
  const raw = await page.locator('canvas.view').getAttribute('data-mascots')
  const out: Record<string, { x: number; y: number }> = {}
  for (const part of (raw ?? '').split(';').filter(Boolean)) {
    const [name, xy] = part.split(':')
    const [x, y] = xy.split(',').map(Number)
    out[name] = { x, y }
  }
  return out
}

/** Journey 11: the robot opens the director's terminal; the pets only show their name. Reduced motion freezes them. */
test('a click on the robot opens the terminal; the pets show their names', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await expect(page.locator('canvas.view')).toHaveAttribute('data-mascots', /robot:/)
  // The default zoom, the whole page as the director sees it first.
  await page.waitForTimeout(500)
  await page.screenshot({ path: `${RESULTS}/11-default-zoom.png` })
  // Zoom out one notch at a time until all three stand in view (they stand where the scene put them: no wandering).
  let at = await marks(page)
  const inView = () => Object.values(at).every((p) => p.x > 60 && p.x < 1540 && p.y > 140 && p.y < 760)
  for (let i = 0; i < 8 && !inView(); i++) {
    await page.locator('canvas.view').evaluate((canvas) => canvas.dispatchEvent(new WheelEvent('wheel', { deltaY: 100 })))
    await page.waitForTimeout(700)
    at = await marks(page)
  }
  // The zoom eases in: read the marks once two reads in a row agree, so a loaded machine does not hover a stale spot.
  let previous = ''
  for (let i = 0; i < 20; i++) {
    await page.waitForTimeout(500)
    at = await marks(page)
    const now = JSON.stringify(at)
    if (now === previous) break
    previous = now
  }
  expect(Object.keys(at).filter((k) => k !== 'library').sort()).toEqual(['cat', 'dog', 'robot'])
  const xs = Object.values(at).map((p) => p.x)
  const ys = Object.values(at).map((p) => p.y)
  const x = Math.max(0, Math.min(...xs) - 160)
  const y = Math.max(0, Math.min(...ys) - 160)
  const clip = { x, y, width: Math.min(1600 - x, Math.max(...xs) + 160 - x), height: Math.min(900 - y, Math.max(...ys) + 160 - y) }
  await page.screenshot({ path: `${RESULTS}/11-robot-and-pets.png`, clip })
  // A close-up of each: a small crop around it, on the same frame.
  for (const [name, p] of Object.entries(at)) {
    const cx = Math.max(0, Math.min(1600 - 240, p.x - 120))
    const cy = Math.max(0, Math.min(900 - 200, p.y - 130))
    await page.screenshot({ path: `${RESULTS}/11-closeup-${name}.png`, clip: { x: cx, y: cy, width: 240, height: 200 } })
  }

  await page.mouse.move(at.cat.x, at.cat.y)
  await expect(page.locator('.tooltip')).toHaveText(t['hover.cat'])
  await page.mouse.move(at.dog.x, at.dog.y)
  await expect(page.locator('.tooltip')).toHaveText(t['hover.dog'])
  await expect(page.locator('.director-terminal')).toHaveCount(0)

  await page.mouse.move(at.robot.x, at.robot.y)
  await expect(page.locator('.tooltip')).toHaveText(t['hover.robot'])
  expect(await page.locator('canvas.view').evaluate((c) => (c as HTMLElement).style.cursor)).toBe('pointer')
  await page.mouse.click(at.robot.x, at.robot.y)
  await expect(page.locator('.director-terminal')).toBeVisible()
  await shot(page, '11-robot-terminal')
  // The robot is a mouse shortcut: closing the panel gives the focus back to the bar, like the menu's entry does.
  await page.keyboard.press('Escape')
  await expect(page.locator('.director-terminal')).toHaveCount(0)
  await expect(page.locator('.bar-nav button[data-menu="system"]')).toBeFocused()
})

/** What the scene says each pet is doing ('cat:sleep;dog:walk'), as canvas data. */
async function states(page: Page): Promise<Record<string, string>> {
  const raw = (await page.locator('canvas.view').getAttribute('data-pets')) ?? ''
  return Object.fromEntries(raw.split(';').filter(Boolean).map((part) => part.split(':') as [string, string]))
}

/** Journey 11b: without reduced motion the pets walk and run, speak, and sleep curled up in their baskets. */
test('the pets walk, speak and sleep in their baskets', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await expect(page.locator('canvas.view')).toHaveAttribute('data-pets', /cat:/)
  const frame = async (name: 'cat' | 'dog', file: string) => {
    const wheel = async (deltaY: number) => {
      await page.locator('canvas.view').evaluate((canvas, d) => canvas.dispatchEvent(new WheelEvent('wheel', { deltaY: d })), deltaY)
      await page.waitForTimeout(600)
    }
    const centred = async () => {
      const at = (await marks(page))[name]
      return !!at && at.x > 300 && at.x < 1300 && at.y > 260 && at.y < 640
    }
    // Zoom out one notch at a time until the pet is on screen, then in again while it stays in the middle of it.
    for (let i = 0; i < 10 && !(await centred()); i++) await wheel(100)
    for (let i = 0; i < 5; i++) {
      await wheel(-100)
      if (!(await centred())) {
        await wheel(100)
        break
      }
    }
    await page.waitForTimeout(300)
    const at = (await marks(page))[name]
    const x = Math.max(0, Math.min(1600 - 640, at.x - 320))
    const y = Math.max(0, Math.min(900 - 420, at.y - 250))
    await page.screenshot({ path: `${RESULTS}/${file}`, clip: { x, y, width: 640, height: 420 } })
  }
  await expect.poll(async () => Object.values(await states(page)).some((s) => s === 'walk' || s === 'run'), { timeout: 60_000 }).toBe(true)
  const mover = Object.entries(await states(page)).find(([, s]) => s === 'walk' || s === 'run')?.[0] as 'cat' | 'dog'
  await frame(mover, '11b-pets-walking.png')
  // Their clock is pushed ahead (canvas data, read once by the scene): a nap would take minutes otherwise. Fifteen seconds a step, not more: a nap lasts 30 to 60 s of their clock (mascots.ts petPlan), and a step longer than that could run the whole nap between two reads.
  const nap = async (name: 'cat' | 'dog') =>
    expect
      .poll(
        async () => {
          await page.locator('canvas.view').evaluate((c) => ((c as HTMLElement).dataset.petAdvance = '15'))
          return (await states(page))[name]
        },
        { timeout: 60_000 },
      )
      .toBe('sleep')
  await nap('cat')
  await frame('cat', '11b-cat-asleep.png')
  await expect(page.locator('.pet-bubble[data-pet="cat"]')).toHaveText('z')
  await nap('dog')
  await frame('dog', '11b-dog-asleep.png')
})
