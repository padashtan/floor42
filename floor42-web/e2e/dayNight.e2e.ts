import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { CATALOGS, expect, RESULTS, test, useLanguage } from './fixtures'
import { decodePng, differingPixels } from './png'

const t = CATALOGS.en
const CANVAS_ONLY = 'body * { visibility: hidden !important } canvas.view { visibility: visible !important }'
const FLOOR = 20_000

/** Journey: the director draws the scene by night and the choice is kept in this browser (decor only). */
test('the director switches the scene to night and the choice survives a reload', { tag: '@scene' }, async ({ page }) => {
  test.setTimeout(120_000)
  const folder = join(RESULTS, 'dayNight')
  mkdirSync(folder, { recursive: true })
  await page.setViewportSize({ width: 1280, height: 720 })
  await useLanguage(page, 'en')
  await page.clock.install({ time: new Date(2026, 10, 1, 15, 0) })
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  const canvas = page.locator('canvas.view')
  const button = page.locator('.help .day-night')
  const shoot = async (name: string) => decodePng(await canvas.screenshot({ path: join(folder, `${name}.png`), style: CANVAS_ONLY }))
  await expect(button).toHaveText(t['scene.light.auto'])
  await page.clock.runFor(1_000)
  const noon = await shoot('auto-15h')

  await button.click()
  await expect(button).toHaveText(t['scene.light.day'])
  await page.clock.runFor(2_000)
  const day = await shoot('day')
  await button.click()
  await expect(button).toHaveText(t['scene.light.night'])
  await page.clock.runFor(2_000) // past the 1.5 s glide
  const night = await shoot('night')
  expect(differingPixels(noon, night), 'night differs from the 15:00 scene').toBeGreaterThan(FLOOR)
  expect(differingPixels(day, night)).toBeGreaterThan(FLOOR)

  await page.reload()
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await expect(button).toHaveText(t['scene.light.night'])
  expect(await page.evaluate(() => localStorage.getItem('floor42.dayNight'))).toBe('night')

  // Reduced motion: no glide, the pixels already differ one frame after the click.
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.reload()
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await button.click() // night to auto (15:00)
  await page.clock.runFor(50)
  const jumped = await shoot('reduced-auto')
  expect.soft(differingPixels(jumped, night), 'one frame after the click it is already back to the 15:00 light').toBeGreaterThan(FLOOR)
})
