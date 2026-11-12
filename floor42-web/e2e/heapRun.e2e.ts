import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { Page } from '@playwright/test'
import { THEME_IDS, type ThemeId } from '../src/scene/palette'
import { CATALOGS, expect, RESULTS, test, useLanguage } from './fixtures'
import { HeapProbe, type HeapSummary, kindsDiff, typesLine } from './heap'

/**
 * A measurement, not a gate: run only with FLOOR42_HEAP_RUN=<changes> (56 for the D18 evidence). The decade changes
 * that many times, in turn; after each change the JS heap's used size after a full GC, and at a few changes a heap
 * snapshot summed by node type, with the kinds of objects that changed most between them. The report goes to
 * e2e-results/heap-run/report.txt.
 */
const CHANGES = Number(process.env.FLOOR42_HEAP_RUN ?? 0)

async function putTheme(page: Page, theme: ThemeId): Promise<void> {
  const status = await page.evaluate(async (value) => {
    const res = await fetch('/api/settings/OFFICE_THEME', {
      method: 'PUT',
      headers: { 'X-Floor42-Request': '1', 'Content-Type': 'application/json' },
      body: JSON.stringify({ value }),
    })
    return res.status
  }, theme)
  expect(status).toBe(200)
}

async function setTheme(page: Page, theme: ThemeId): Promise<void> {
  await putTheme(page, theme)
  for (let i = 0; i < 600; i++) {
    if ((await page.locator('html').getAttribute('data-theme')) === theme) return
    await page.clock.runFor(16)
  }
  throw new Error(`the office never turned to ${theme}`)
}

test('the heap over many changes of decade (a measurement)', async ({ page }) => {
  test.skip(CHANGES === 0, 'a measurement: FLOOR42_HEAP_RUN=<changes> runs it')
  test.setTimeout(CHANGES * 15_000 + 60_000)
  await page.setViewportSize({ width: 1280, height: 720 })
  await useLanguage(page, 'en')
  await page.addInitScript(() => (Math.random = () => 0.5))
  await page.clock.install({ time: new Date(2026, 10, 1, 15, 0) })
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(CATALOGS.en['top.liveReady'])
  const probe = await HeapProbe.open(page)
  // Snapshots in the same decade (the last of a round), early, just before and just after the fall, and at the end.
  const at = new Set([8, 40, 44, 48, 52, CHANGES].filter((n) => n % THEME_IDS.length === 0 && n <= CHANGES))
  const used: string[] = []
  const snaps: [number, HeapSummary][] = []
  for (let n = 1; n <= CHANGES; n++) {
    const now = await page.evaluate(() => Date.now())
    await page.clock.pauseAt(now + 500)
    await setTheme(page, THEME_IDS[(n - 1) % THEME_IDS.length])
    await page.clock.runFor(3_000)
    await page.clock.resume()
    used.push(`${n} ${THEME_IDS[(n - 1) % THEME_IDS.length]} ${await probe.usedMb()} MB`)
    if (at.has(n)) snaps.push([n, await probe.snapshot()])
  }
  await probe.close()
  const report = ['JS heap used after a full GC, after each change of decade:', ...used, '']
  for (const [n, s] of snaps) report.push(`snapshot after change ${n} (MB by node type): ${typesLine(s)}`)
  for (let i = 1; i < snaps.length; i++) {
    report.push('', `kinds that changed most from change ${snaps[i - 1][0]} to change ${snaps[i][0]}:`, ...kindsDiff(snaps[i - 1][1], snaps[i][1], 20))
  }
  const folder = join(RESULTS, 'heap-run')
  mkdirSync(folder, { recursive: true })
  writeFileSync(join(folder, 'report.txt'), report.join('\n') + '\n')
})

/**
 * D23: the same changes of decade with no DevTools session attached, measured by the page itself through
 * performance.measureUserAgentSpecificMemory() (FLOOR42_HEAP_ISOLATED=<changes>, a sample every 10 changes).
 * That API needs a cross-origin isolated page and the daemon sends neither header, so for this run only the
 * document response gets COOP same-origin and COEP credentialless from a Playwright route (every sub-resource is
 * same-origin). The report goes to e2e-results/heap-run/isolated.txt.
 */
const ISOLATED = Number(process.env.FLOOR42_HEAP_ISOLATED ?? 0)
const SNAP = Number(process.env.FLOOR42_HEAP_SNAP ?? 0)

test('the heap over many changes of decade, no DevTools (a measurement)', async ({ page }) => {
  test.skip(ISOLATED === 0, 'a measurement: FLOOR42_HEAP_ISOLATED=<changes> runs it')
  test.setTimeout(ISOLATED * 15_000 + 300_000)
  await page.route('**/*', async (route) => {
    if (route.request().resourceType() !== 'document') return route.continue()
    const res = await route.fetch()
    await route.fulfill({
      response: res,
      headers: { ...res.headers(), 'cross-origin-opener-policy': 'same-origin', 'cross-origin-embedder-policy': 'credentialless' },
    })
  })
  await page.setViewportSize({ width: 1280, height: 720 })
  await useLanguage(page, 'en')
  await page.addInitScript(() => (Math.random = () => 0.5))
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(CATALOGS.en['top.liveReady'])
  expect(await page.evaluate(() => crossOriginIsolated), 'the page is cross-origin isolated').toBe(true)
  const lines = ['performance.measureUserAgentSpecificMemory(), no DevTools session; bytes = result.bytes, js = entries attributed to the JS of the page, sample at change 0 and every 20']
  const sample = async (n: number) => {
    const r = await page.evaluate(async () => {
      const g = (window as unknown as { gc(): void }).gc
      g()
      g()
      const used = (performance as unknown as { memory: { usedJSHeapSize: number } }).memory.usedJSHeapSize
      const m = await Promise.race([new Promise<undefined>((_, no) => setTimeout(() => no(new Error('measure took over 120 s')), 120_000)), (performance as unknown as { measureUserAgentSpecificMemory(): Promise<{ bytes: number; breakdown: { bytes: number; types: string[] }[] }> }).measureUserAgentSpecificMemory()])
      if (!m) throw new Error('measureUserAgentSpecificMemory gave no result')
      const js = m.breakdown.filter((b) => b.types.includes('JavaScript')).reduce((a, b) => a + b.bytes, 0)
      return { total: m.bytes, js, used }
    })
    const line = `${n} total ${(r.total / 1048576).toFixed(2)} MB, js ${(r.js / 1048576).toFixed(2)} MB, gc'd used ${(r.used / 1048576).toFixed(2)} MB`
    lines.push(line)
    mkdirSync(join(RESULTS, 'heap-run'), { recursive: true })
    appendFileSync(join(RESULTS, 'heap-run', 'isolated.progress.txt'), line + '\n')
  }
  const snaps: [number, HeapSummary][] = []
  await sample(0)
  for (let n = 1; n <= ISOLATED; n++) {
    const theme = THEME_IDS[(n - 1) % THEME_IDS.length]
    await putTheme(page, theme)
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    await page.waitForTimeout(500)
    if (n % 20 === 0) await sample(n)
    // A DevTools session only for the two snapshots (change SNAP and the last), closed again at once.
    if (SNAP > 0 && (n === SNAP || n === ISOLATED)) {
      const probe = await HeapProbe.open(page)
      snaps.push([n, await probe.snapshot()])
      await probe.close()
    }
  }
  const folder = join(RESULTS, 'heap-run')
  mkdirSync(folder, { recursive: true })
  if (snaps.length === 2) {
    lines.push('', `snapshot ${snaps[0][0]}: ${typesLine(snaps[0][1])}`, `snapshot ${snaps[1][0]}: ${typesLine(snaps[1][1])}`)
    lines.push(`kinds that changed most from change ${snaps[0][0]} to ${snaps[1][0]}:`, ...kindsDiff(snaps[0][1], snaps[1][1], 25))
  }
  writeFileSync(join(folder, 'isolated.txt'), lines.join('\n') + '\n')
})

// Cross-origin isolation makes this Chrome refuse the page own loopback WebSocket (LocalNetworkAccess): off for these runs.
test.use({ launchOptions: { executablePath: process.env.FLOOR42_E2E_EXECUTABLE, args: ["--js-flags=--expose-gc","--disable-features=LocalNetworkAccessChecks,LocalNetworkAccessChecksWebSockets,BlockInsecurePrivateNetworkRequests"] } })
