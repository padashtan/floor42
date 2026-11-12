import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { Page } from '@playwright/test'
import { THEME_IDS, type ThemeId } from '../src/scene/palette'
import { CATALOGS, expect, RESULTS, test, useLanguage } from './fixtures'
import { HeapProbe, type HeapSummary, kindsDiff, typesLine } from './heap'
import { decodePng, differingPixels } from './png'

const t = CATALOGS.en

/**
 * Journey 6: the office of each decade, and what a change of decade leaves behind. The page counts, per WebGL
 * context, the buffers and textures created and deleted (a geometry is one or more buffers, a texture one texture):
 * the scene a new decade replaces must give back what it uploaded, not rely on the lost context alone.
 *
 * Screenshots of the canvas go to e2e-results/scene/<FLOOR42_SCENE_LABEL or "current">. When a folder named by
 * FLOOR42_SCENE_BASELINE holds the same decade, the two are compared pixel by pixel and the count is annotated: the
 * time and Math.random are fixed, so two builds of the same scene draw the same picture, within a frame of traffic.
 */

interface GlCount {
  buffers: number
  textures: number
  lost: boolean
}

/** Counts live WebGL buffers and textures per context; Math.random is fixed so the scene draws the same each time. */
function instrument(): void {
  const stats: GlCount[] = []
  ;(window as unknown as { __glStats: GlCount[] }).__glStats = stats
  Math.random = () => 0.5
  const original = HTMLCanvasElement.prototype.getContext
  const seen = new WeakMap<object, GlCount>()
  HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, ...args: unknown[]) {
    const gl = (original as (...a: unknown[]) => unknown).apply(this, args) as WebGL2RenderingContext | null
    if (!gl || !String(args[0]).startsWith('webgl') || seen.has(gl)) return gl
    const count: GlCount = { buffers: 0, textures: 0, lost: false }
    seen.set(gl, count)
    stats.push(count)
    const wrap = (name: 'createBuffer' | 'deleteBuffer' | 'createTexture' | 'deleteTexture', key: 'buffers' | 'textures', d: number) => {
      const fn = gl[name].bind(gl) as (...a: unknown[]) => unknown
      ;(gl as unknown as Record<string, unknown>)[name] = (...a: unknown[]) => {
        if (d < 0 && !a[0]) return fn(...a)
        count[key] += d
        return fn(...a)
      }
    }
    wrap('createBuffer', 'buffers', 1)
    wrap('deleteBuffer', 'buffers', -1)
    wrap('createTexture', 'textures', 1)
    wrap('deleteTexture', 'textures', -1)
    this.addEventListener('webglcontextlost', () => (count.lost = true))
    return gl
  } as typeof HTMLCanvasElement.prototype.getContext
}

/** Changes the decade in the settings, and lets the page's own clock run in small steps until it rebuilt the office. */
async function setTheme(page: Page, theme: ThemeId): Promise<void> {
  const r = await page.evaluate(async (value) => {
    const res = await fetch('/api/settings/OFFICE_THEME', {
      method: 'PUT',
      headers: { 'X-Floor42-Request': '1', 'Content-Type': 'application/json' },
      body: JSON.stringify({ value }),
    })
    return res.status
  }, theme)
  expect(r, `PUT OFFICE_THEME ${theme}`).toBe(200)
  for (let i = 0; i < 600; i++) {
    if ((await page.locator('html').getAttribute('data-theme')) === theme) return
    await page.clock.runFor(16)
  }
  throw new Error(`the office never turned to ${theme}`)
}

/** The empty default textures three.js's renderer makes on a context and never deletes; its loss frees them. */
const RENDERER_OWN_TEXTURES = 4

/**
 * The most the objects scripts made may grow per change of decade, in MB, from the end of the second round to the end
 * of the third, the same decade, both drawn without a zoom out (heap.ts). 0.096 to 0.101 MB in every run without a
 * leak, nearly all three.js's per-geometry binding tables, which grow with every
 * renderer until V8 turns them into dictionaries about the 47th change. So a leak
 * of script objects is seen from about 20 KB a change; a tenth of each replaced scene's geometries kept gives 0.551.
 * Raised from 0.12 to 0.14 on 2026-11-05 (the director's decision): the identity server and the glass partitions add
 * geometries, so binding tables, and measured 0.1200 to 0.1209 without a leak. A leak still shows from about 20 KB.
 */
const HEAP_PER_CHANGE = 0.14

/**
 * The most the bytes of ArrayBuffers (geometries' vertices, textures' pixels: native memory, outside the JS heap's
 * used size and outside the script objects) may grow per change of decade, in MB, measured the same way: 16 KB.
 * Under 1 KB in every run without a leak; one geometry of each replaced scene kept (18.5 KB a change), a tenth of its
 * geometries (448 KB) or its largest one (2.97 MB) fail it. Its canvases kept
 * fail the canvas count (3,062 then 4,422 instead of 342).
 */
const BUFFERS_PER_CHANGE = 16 / 1024

const CANVAS_ONLY = 'body * { visibility: hidden !important } canvas.view { visibility: visible !important }'

/**
 * Zooms out almost all the way (1), never against the limit, or back in (-1), then lets the camera settle for a fixed
 * time. The wheel is sent to the canvas itself: a panel left open by an earlier journey may lie over its middle.
 */
async function zoom(page: Page, direction: 1 | -1): Promise<void> {
  await page.locator('canvas.view').evaluate((canvas, d) => {
    for (let i = 0; i < 14; i++) canvas.dispatchEvent(new WheelEvent('wheel', { deltaY: 100 * d }))
  }, direction)
  await page.clock.runFor(1_500)
}

/** The live stats of every context the page made so far, oldest first. */
const glStats = (page: Page) => page.evaluate(() => (window as unknown as { __glStats: GlCount[] }).__glStats)

test('each decade draws its office, and a change of decade frees the scene it replaces', async ({ page }) => {
  test.setTimeout(360_000)
  const label = process.env.FLOOR42_SCENE_LABEL ?? 'current'
  const folder = join(RESULTS, 'scene', label)
  const baseline = process.env.FLOOR42_SCENE_BASELINE ? join(RESULTS, 'scene', process.env.FLOOR42_SCENE_BASELINE) : null
  mkdirSync(folder, { recursive: true })
  await page.setViewportSize({ width: 1280, height: 720 })
  await useLanguage(page, 'en')
  await page.addInitScript(instrument)
  await page.clock.install({ time: new Date(2026, 10, 1, 15, 0) })
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  const report: string[] = []
  const shoot = async (name: string) => {
    // The canvas alone: the HUD over it shows clock times.
    const png = await page.locator('canvas.view').screenshot({ path: join(folder, `${name}.png`), style: CANVAS_ONLY })
    const before = baseline && join(baseline, `${name}.png`)
    if (before && existsSync(before)) {
      const n = differingPixels(decodePng(readFileSync(before)), decodePng(png))
      report.push(`${name}: ${n} pixels of ${1280 * 720} differ from ${process.env.FLOOR42_SCENE_BASELINE}`)
    }
  }
  const probe = await HeapProbe.open(page)
  const heap0 = await probe.usedMb()
  const original = (await page.locator('html').getAttribute('data-theme')) as ThemeId
  /** The heap at the end of the second and the third round, in the same decade, both drawn without a zoom out. */
  const ends: HeapSummary[] = []

  for (const [i, theme] of [...THEME_IDS, ...THEME_IDS, ...THEME_IDS].entries()) {
    // Time stands still while the decade changes, then the same three seconds of scene play for every decade.
    const now = await page.evaluate(() => Date.now())
    await page.clock.pauseAt(now + 500)
    await setTheme(page, theme)
    await page.clock.runFor(3_000)
    const stats = await glStats(page)
    const live = (s: GlCount | undefined) => (s ? `${s.buffers} buffers, ${s.textures} textures${s.lost ? ' (lost)' : ''}` : '-')
    report.push(`${theme}: replaced context keeps ${live(stats.at(-2))}; new context ${live(stats.at(-1))}; JS heap ${await probe.usedMb()} MB`)
    if (i < THEME_IDS.length) {
      await shoot(`${theme}-office`)
      // Zoomed out: the city below and the sky. The view is carried over to the next decade: zoomed back in after.
      await zoom(page, 1)
      await shoot(`${theme}-city`)
      await zoom(page, -1)
    }
    if (i % THEME_IDS.length === THEME_IDS.length - 1 && i >= THEME_IDS.length) ends.push(await probe.snapshot())
    await page.clock.resume()
  }
  // The same decade drawn again, one round later: what each change of the third round added to the heap.
  const [before, last] = ends
  const mb = (bytes: number) => bytes / 1048576
  const perChange = (f: (s: HeapSummary) => number) => (mb(f(last)) - mb(f(before))) / THEME_IDS.length
  const growth = { script: perChange((s) => s.scriptBytes), buffers: perChange((s) => s.bufferBytes) }
  report.push(`heap after the second round: ${typesLine(before)}`, `heap after the third round: ${typesLine(last)}`)
  report.push(`per change of decade, third round: script objects ${growth.script.toFixed(3)} MB, buffers' data ${(growth.buffers * 1024).toFixed(1)} KB`)
  report.push('kinds that changed most from the second round to the third:', ...kindsDiff(before, last, 15))
  const contexts = last.natives.get('WebGL2RenderingContext') ?? 0
  report.push(`after GC: ${contexts} WebGL context(s) still reachable`)
  const heap1 = await probe.usedMb()
  await probe.close()
  report.push(`JS heap after GC: ${heap0} MB at start, ${heap1} MB after ${THEME_IDS.length * 3} changes of decade`)
  await setTheme(page, original)
  writeFileSync(join(folder, 'report.txt'), report.join('\n') + '\n')
  test.info().annotations.push({ type: 'scene', description: report.join('\n') })
  console.log(report.join('\n'))

  // Every replaced context gives back every buffer and every texture but three.js's own, which only the lost context
  // frees: the renderer's four empty default textures (the same 4 on the fake WebGL context of
  // officeScene.gl.test.ts). One more on any replaced context fails.
  const stats = await glStats(page)
  const kept = stats.slice(0, -1).filter((s) => s.buffers > 0 || s.textures > RENDERER_OWN_TEXTURES)
  // Nothing keeps a replaced scene alive: after a full GC the page holds one WebGL context, the scene's in use, and a
  // change of decade adds next to nothing to the heap: neither objects, nor the buffers' bytes, nor canvases.
  expect(contexts, 'WebGL contexts still reachable after the changes of decade').toBe(1)
  expect(growth.script, 'MB of script objects added per change of decade, third round').toBeLessThan(HEAP_PER_CHANGE)
  expect(growth.buffers, 'MB of ArrayBuffer data added per change of decade, third round').toBeLessThan(BUFFERS_PER_CHANGE)
  expect(last.canvases, 'canvases reachable, third round against second').toBeLessThanOrEqual(before.canvases)
  expect(kept.map((s) => `b${s.buffers} t${s.textures}`), 'GPU objects left on replaced contexts').toEqual([])
})
