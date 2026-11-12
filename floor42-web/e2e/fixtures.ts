import { test as base, expect, type Locator, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { en } from '../src/i18n/en'
import { fr } from '../src/i18n/fr'
import { identityEn } from '../src/i18n/identity.en'
import { unsafeEn } from '../src/i18n/unsafe.en'
import { unsafeFr } from '../src/i18n/unsafe.fr'
import { identityFr } from '../src/i18n/identity.fr'
import type { Key } from '../src/i18n'
import { type Daemon, startDaemon } from './daemon'

export type Lang = 'en' | 'fr'
export const CATALOGS = {
  en: { ...en, ...identityEn, ...unsafeEn },
  fr: { ...fr, ...identityFr, ...unsafeFr },
} as const
/** One language's texts: the English keys, any language's words. */
export type Catalog = Record<Key, string>

/** Where the journeys leave their screenshots (gitignored): kept as artifacts, compared with nothing. */
export const RESULTS = resolve(import.meta.dirname, '..', 'e2e-results')

export async function shot(page: Page, name: string): Promise<void> {
  mkdirSync(RESULTS, { recursive: true })
  await page.screenshot({ path: join(RESULTS, `${name}.png`) })
}

/** Shows the page in one language: the choice the language switch stores in this browser. */
export async function useLanguage(page: Page, lang: Lang): Promise<void> {
  // Init scripts run in every frame: a PDF shown in the file viewer has no storage of its own (null there).
  await page.addInitScript((l) => window.localStorage?.setItem('floor42.lang', l), lang)
}

/**
 * The element is on screen: its box lies inside the viewport, and the browser hits the element itself (or one of its
 * children) at its centre, so nothing covers it. Every test passed while the quota gauges sat outside the window.
 */
export async function expectOnScreen(locator: Locator, what: string): Promise<void> {
  await expect(locator, what).toBeVisible()
  const page = locator.page()
  const viewport = page.viewportSize()!
  const box = (await locator.boundingBox())!
  expect(box, `${what}: no box`).not.toBeNull()
  const inside =
    box.x >= 0 && box.y >= 0 && box.x + box.width <= viewport.width + 0.5 && box.y + box.height <= viewport.height + 0.5
  expect(inside, `${what} at ${JSON.stringify(box)} outside the ${viewport.width}x${viewport.height} viewport`).toBe(
    true,
  )
  const hit = await blocker(locator)
  expect(hit, `${what} is covered by ${hit}`).toBeNull()
}

/**
 * What the browser hits at the element's centre instead of the element: null when it hits the element (or a child),
 * else a short description of what covers it ("clipped" when the point is outside the window, or a scrolling parent
 * hides it).
 */
export async function blocker(locator: Locator): Promise<string | null> {
  return locator.evaluate((el) => {
    const r = el.getBoundingClientRect()
    const x = r.left + r.width / 2
    const y = r.top + r.height / 2
    if (x < 0 || y < 0 || x > innerWidth || y > innerHeight) return 'clipped (outside the window)'
    const top = document.elementFromPoint(x, y)
    if (top === el || (top && el.contains(top))) return null
    if (!top) return 'nothing'
    const panel = top.closest('.panel')
    const name = (e: Element) => `${e.tagName.toLowerCase()}.${[...e.classList].join('.')}`
    // Hidden by its own scrolling strip: the point falls on an ancestor of the element.
    if (top.contains(el)) return `clipped by ${name(top)}`
    return panel ? name(panel) : name(top)
  })
}

interface WorkerFixtures {
  daemon: Daemon
  /** A storage state signed in through the single-use link the daemon prints (ADR-0034). */
  signedIn: string
}

interface TestFixtures {
  /** Console errors and uncaught page errors seen during the test: none is allowed. */
  consoleErrors: string[]
  /** Cancels what a journey left under way, once it ended. */
  tidy: void
}

export const test = base.extend<TestFixtures, WorkerFixtures>({
  daemon: [
    async ({}, use) => {
      const daemon = await startDaemon()
      try {
        await use(daemon)
      } finally {
        await daemon.stop()
      }
    },
    { scope: 'worker', timeout: 120_000 },
  ],

  signedIn: [
    async ({ daemon, browser }, use) => {
      const context = await browser.newContext({ baseURL: daemon.url })
      const page = await context.newPage()
      await useLanguage(page, 'en')
      await page.goto('/')
      // The page asks the daemon for a link; the daemon prints it, never hands it back to the page.
      const since = daemon.output().length
      await page.getByRole('button', { name: en['d12.login.open'] }).click()
      await expect(page.getByText(en['d12.login.opened'])).toBeVisible()
      await page.goto(await daemon.signInLink(since))
      await expect(page.locator('.panel.login')).toHaveCount(0)
      // The guided first mission is offered once per browser: answered here, so the other journeys see the bare
      // screen. The tutorial journey takes the offer back.
      await page.evaluate(() => localStorage.setItem('floor42.tutorial.offered', '1'))
      // The machine checklist opens once per browser too: seen here; the settings journey opens it on purpose.
      await page.evaluate(() => localStorage.setItem('floor42.checklist.shown', '1'))
      // No acknowledged journal sequence is kept: each journey opens as a first visit, where "While you were away"
      // starts from now. Kept, it held the sequence of this sign-in, and a journey opened over the summary of every
      // journey that ran before it on the shared daemon (the panels loop then ended with that summary on screen).
      await page.evaluate(() => localStorage.removeItem('floor42.away.acknowledged'))
      const file = join(daemon.dataDir, '..', 'signed-in.json')
      await context.storageState({ path: file })
      await context.close()
      await use(file)
    },
    { scope: 'worker' },
  ],

  baseURL: async ({ daemon }, use) => use(daemon.url),
  storageState: async ({ signedIn }, use) => use(signedIn),

  // A journey leaves no mission under way on the daemon its worker shares with the next files: a leftover keeps an agent
  // assigned or waiting on a question (askBack, graphMode), and a later journey then sees its developer busy or never
  // staffed (lively, meeting). Done missions stay: the archive journeys read them.
  tidy: [
    async ({ daemon, request }, use) => {
      await use()
      const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
      const missions: { id: string; status: string }[] = await (await request.get('/api/missions')).json()
      for (const m of missions.filter((x) => x.status !== 'COMPLETED' && x.status !== 'CANCELLED')) {
        await request.post(`/api/missions/${m.id}/cancel`, { headers, data: { reason: 'journey over' } })
      }
    },
    { auto: true },
  ],

  consoleErrors: [
    async ({ page }, use) => {
      const errors: string[] = []
      page.on('console', (m) => {
        if (m.type() === 'error') errors.push(`console: ${m.text()} (${m.location().url})`)
      })
      page.on('pageerror', (e) => errors.push(`page: ${e.message}`))
      // The page's Content-Security-Policy (ADR-0044): every refusal fails the journey, named by its directive.
      await page.exposeFunction('__floor42CspViolation', (v: string) => errors.push(`CSP: ${v}`))
      await page.addInitScript(() => {
        document.addEventListener('securitypolicyviolation', (e) => {
          const report = (window as unknown as { __floor42CspViolation: (v: string) => void }).__floor42CspViolation
          report(`${e.effectiveDirective} blocked ${e.blockedURI || 'inline'} (${e.sourceFile}:${e.lineNumber})`)
        })
      })
      // The browser logs a refused call without its reason: keep the daemon's answer next to it.
      page.on('response', async (r) => {
        if (r.status() < 400) return
        const body = await r.text().catch(() => '')
        test.info().annotations.push({ type: `HTTP ${r.status()}`, description: `${r.url()} ${body.slice(0, 300)}` })
      })
      await use(errors)
      expect(errors, 'console errors').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }

/** Whether the element paints a solid background: nothing behind it (the log, the scene) shows through. */
export async function opaque(locator: Locator): Promise<boolean> {
  return locator.evaluate((e) => {
    const m = /rgba?\(([^)]+)\)/.exec(getComputedStyle(e).backgroundColor)
    const alpha = m ? Number(m[1].split(/[,/\s]+/).filter(Boolean)[3] ?? 1) : 0
    return alpha === 1
  })
}

/** In a panel built on SectionedPanel, under 760 px: one grid column, the section list a row of tabs. */
export async function expectNarrowLayout(panel: Locator, name: string): Promise<void> {
  const columns = await panel.locator('.sectioned-body').evaluate((e) => getComputedStyle(e).gridTemplateColumns.trim().split(/\s+/).length)
  expect(columns, `${name}: one column under 760 px`).toBe(1)
  const direction = await panel.locator('.sectioned-nav').evaluate((e) => getComputedStyle(e).flexDirection)
  expect(direction, `${name}: the tabs in a row`).toBe('row')
}
