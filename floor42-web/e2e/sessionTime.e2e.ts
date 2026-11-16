import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en
const T0 = new Date(2026, 10, 5, 10, 0)
const iso = (minutes: number) => new Date(T0.getTime() + minutes * 60_000).toISOString()
const session = (idleUntil: string) => ({ current: true, breakGlass: false, idleUntil, expires: null, keycloakUntil: null })

/**
 * Journey: the director's session end shows in the bar, turns amber and toasts 5 minutes before, and goes when the end
 * moves away. The page clock is installed, so the test does not depend on the date.
 */
test('the bar shows when the session ends and warns five minutes before', { tag: '@ui' }, async ({ page }) => {
  // The hint gives way to the labels from 1505 to 1700 px (base.css); 1400 px shows it.
  await page.setViewportSize({ width: 1400, height: 900 })
  await useLanguage(page, 'en')
  await page.clock.install({ time: T0 })
  let idle = iso(6)
  await page.route('**/api/auth/sessions', (route) => route.fulfill({ json: [session(idle)] }))
  await page.goto('/')
  const note = page.locator('.session-time')
  await expect(note).toBeVisible()
  await expect(page.locator('.session-toast')).toHaveCount(0)

  await page.clock.fastForward(2 * 60_000)
  await expect(note).toHaveClass(/ending/)
  await expect(page.locator('.session-toast')).toContainText(t['session.endingToast'].split('{time}')[0])
  await shot(page, 'sessionTime/session')

  idle = iso(8 * 60)
  await page.clock.fastForward(60_000)
  await expect(note).not.toHaveClass(/ending/)
  await expect(page.locator('.session-toast')).toHaveCount(0)
})

/**
 * Journey (IAM answers stood in, as identity.e2e.ts does; no Keycloak): a delegation ending in 20 minutes and a token
 * ending in 5 raise one toast each, and a delegation granted for 30 days shows its date on the chip.
 */
test('in IAM mode the delegation and a token announce their end', { tag: '@iam' }, async ({ page, daemon }) => {
  // A real mission on the daemon: the board reads its details there, so a stubbed list alone fails the reload.
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url, 'Idempotency-Key': 'session-time-iam' }
  const created = await page.request.post('/api/missions', {
    headers,
    data: { title: 'Pilot mission', objective: 'Describe it', repository: daemon.gitRepository(), committee: [], plan: false, panel: 'architecture' },
  })
  expect(created.status(), await created.text()).toBe(201)
  const id: string = (await created.json()).id
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.clock.install({ time: T0 })
  await page.route('**/api/auth/sessions', (route) => route.fulfill({ json: [session(iso(8 * 60))] }))
  await page.route('**/api/iam/status', (route) => route.fulfill({ json: { enabled: true } }))
  await page.route('**/api/iam/tokens', (route) =>
    route.fulfill({
      json: [{ agent: 'ada', session: 'L1', launch: 'L1', jti: 'j1', header: {}, claims: {}, signaturePrefix: 'c2lnbmF0', expiresAt: iso(5), origin: 'SIMULATED' }],
    }),
  )
  await page.route('**/api/iam/missions/*/delegation', (route) =>
    route.fulfill({ json: { mission: id, grantedAt: iso(0), expiresAt: iso(20), resources: [], revoked: false } }),
  )
  // The ending checks read the mission list: wait for the board to have it, then let the minute pass.
  const board = page.waitForResponse((r) => r.url().endsWith(`/api/missions/${id}`))
  await page.goto('/')
  await board
  await page.clock.fastForward(60_000)
  await expect(page.locator('.iam-delegation')).toContainText('Pilot mission')
  await expect(page.locator('.iam-token')).toContainText('ada')
  await shot(page, 'sessionTime/iam')
  await page.locator('.iam-token button').click()
  await expect(page.locator('.iam-token')).toHaveCount(0)
})
