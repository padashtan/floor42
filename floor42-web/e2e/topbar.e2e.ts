import type { APIRequestContext } from '@playwright/test'
import { GROUPS, grouped } from '../src/ui/actions'
import { boardMission } from '../src/ui/recent'
import { menuButton, menuItem, openMenuPanel } from './bar'
import { blocker, CATALOGS, expect, expectOnScreen, type Lang, shot, test, useLanguage } from './fixtures'

/**
 * The spend indicator reads the real mission on the board against its cap. Run after the mission journey, that mission
 * is on the board; run alone, the journey makes one of its own (planned by the fake Claude Code, capped at 1 USD), so
 * it never depends on the order of the files.
 */
async function realCappedMissionOnBoard(request: APIRequestContext, daemonUrl: string, repository: string) {
  const headers = { 'X-Floor42-Request': '1', Origin: daemonUrl }
  const shown = boardMission(await (await request.get('/api/missions')).json(), null)
  if (shown) {
    const m = await (await request.get(`/api/missions/${shown.id}`)).json()
    if (m.origin === 'REAL' && m.budgetUsd !== null) return
  }
  const created = await request.post('/api/missions', {
    headers,
    data: { title: 'Top bar journey', objective: 'Describe it', repository },
  })
  expect(created.status()).toBe(201)
  const id: string = (await created.json()).id
  await expect.poll(async () => (await (await request.get(`/api/missions/${id}`)).json()).status).toBe('AWAITING_APPROVAL')
  const capped = await request.put(`/api/missions/${id}/budget`, { headers, data: { usd: 1 } })
  expect(capped.status()).toBe(200)
}

/**
 * Journey 1: the top bar's essentials are on screen, in both languages, on a laptop and on a large screen: the name,
 * the build, the quota gauges, the direct buttons, the three menus and every entry they hold.
 */
for (const width of [1280, 1600, 1920]) {
  for (const lang of ['en', 'fr'] as Lang[]) {
    test(`the top bar and its menus are on screen at ${width} px in ${lang}`, async ({ page, daemon }) => {
      const t = CATALOGS[lang]
      await realCappedMissionOnBoard(page.request, daemon.url, daemon.gitRepository())
      await page.setViewportSize({ width, height: Math.round((width * 9) / 16) })
      await useLanguage(page, lang)
      await page.goto('/')
      await expect(page.locator('html')).toHaveAttribute('lang', lang)
      await expect(page.locator('.panel.login')).toHaveCount(0)

      // "While you were away" tells what happened since this browser last looked (the mission journey ran before):
      // below the crew's cards, and once only.
      const away = page.locator('.away-panel')
      if (await away.isVisible().catch(() => false)) {
        const panel = (await away.boundingBox())!
        for (const card of await page.locator('.crew .card').all()) {
          const c = (await card.boundingBox())!
          const overlaps =
            c.x < panel.x + panel.width && panel.x < c.x + c.width && c.y < panel.y + panel.height && panel.y < c.y + c.height
          expect(overlaps, `the away panel covers the crew card "${(await card.innerText()).trim()}"`).toBe(false)
        }
        await shot(page, `1-away-${width}-${lang}`)
        await page.reload()
        await expect(page.locator('.panel.brand')).toBeVisible()
        await page.waitForTimeout(1_500)
        await expect(away, 'the away panel opened again on a reload with nothing new').toHaveCount(0)
      }

      const brand = page.locator('.panel.brand')
      await expectOnScreen(brand.locator('b', { hasText: 'FLOOR42' }), 'FLOOR42')
      const build = brand.locator('.badge.build')
      await expect(build).toHaveText(/^v\d+\.\d+\.\d+(-[\w.]+)? · [0-9a-f]{7,}\*?$/)
      await expectOnScreen(build, 'the version · commit badge')
      const gauges = brand.locator('.gauges .gauge')
      await expect(gauges.first()).toBeVisible()
      for (let i = 0; i < (await gauges.count()); i++) await expectOnScreen(gauges.nth(i), `quota gauge ${i + 1}`)
      // The fake Claude Code answered the daemon's probe: live agents are offered.
      await expect(brand.locator('.badge.ready')).toHaveText(t['top.liveReady'])
      // The measured numbers (quota gauges, spend) are never cut: whole, on screen, uncovered.
      const spend = brand.locator('.spend')
      await expectOnScreen(spend, 'the spend indicator')
      await expect(spend).toHaveText(/^≈ \d+\.\d+\/\d+\.\d+ \$$/)
      const measuredCut = await brand
        .locator('.measure, .measure .gauge')
        .evaluateAll((els) => els.filter((e) => e.scrollWidth > e.clientWidth + 1).map((e) => e.textContent?.trim()))
      expect(measuredCut, 'measured numbers cut on the bar').toEqual([])
      // Another badge that gives way is cut with an ellipsis: reported, not failed on (its tooltip keeps the text).
      const cut = await brand
        .locator('.shrink')
        .evaluateAll((els) => els.filter((e) => e.scrollWidth > e.clientWidth + 1).map((e) => e.textContent?.trim()))
      if (cut.length) test.info().annotations.push({ type: 'cut with an ellipsis', description: cut.join(' | ') })

      // The direct buttons, the menus and the palette's button: all in view and clickable.
      const nav = brand.locator('.bar-nav > button')
      for (let i = 0; i < (await nav.count()); i++) {
        await expectOnScreen(nav.nth(i), `bar button "${(await nav.nth(i).innerText()).trim()}"`)
      }
      expect(await nav.count()).toBe(grouped().direct.length + GROUPS.length + 1)
      await shot(page, `1-topbar-${width}-${lang}`)

      // The crew's Hire and core cards hold their labels; the help strip does not run under the selection panel.
      const overflowing = await page
        .locator('.crew .card.hire')
        .evaluateAll((els) => els.filter((e) => e.scrollWidth > e.clientWidth + 1).map((e) => e.textContent?.trim()))
      expect(overflowing, 'crew cards whose label runs out').toEqual([])
      const selection = (await page.locator('.panel.selection').boundingBox())!
      const help = (await page.locator('.panel.help').boundingBox())!
      const overlap = selection.x + selection.width > help.x && selection.y + selection.height > help.y
      expect(overlap, `the selection panel ${JSON.stringify(selection)} covers the help ${JSON.stringify(help)}`).toBe(
        false,
      )

      // Each menu opens on screen with every entry it holds clickable, and closes with Escape.
      for (const m of grouped().menus) {
        await menuButton(page, m.group).click()
        await expect(openMenuPanel(page)).toBeVisible()
        const covered: string[] = []
        for (const a of m.items) {
          const item = menuItem(page, t, a)
          await item.scrollIntoViewIfNeeded()
          await expect(item, `${m.group} › ${a.id}`).toBeVisible()
          const by = await blocker(item)
          if (by !== null) covered.push(`${a.id} (${by})`)
        }
        expect(covered, `entries of the ${m.group} menu that cannot be clicked`).toEqual([])
        await shot(page, `1-menu-${m.group}-${width}-${lang}`)
        await page.keyboard.press('Escape')
        await expect(openMenuPanel(page)).toHaveCount(0)
      }
    })
  }
}
