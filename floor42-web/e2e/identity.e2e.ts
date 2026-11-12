import { ACTIONS } from '../src/ui/actions'
import { runFromMenu } from './bar'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en
const action = (id: string) => ACTIONS.find((a) => a.id === id)!

const claims = {
  iss: 'urn:floor42:simulated',
  azp: 'simulated-ada',
  act: { client_id: 'simulated-ada' },
  floor42: { launch: 'L1', mission: 'M-3', ticket: 'T-7' },
}

/**
 * Journey: the IAM section of the settings says a restart is needed; the Identities panel says IAM mode is off while it
 * is, then (the daemon runs with IAM off, so its /api/iam answers are stood in for) shows a token as decoded claims, a
 * simulated badge, and a refusal in red with its code on the trust chain, and never a token string.
 */
test('the identity screens show decoded claims, the trust chain and a refusal in red', async ({ page }) => {
  test.setTimeout(120_000)
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  // Settings: the Identity section, saved for the next start.
  await runFromMenu(page, t, action('settings'))
  const settings = page.locator('.settings-panel')
  await settings.locator('[data-section-tab="identity"]').click()
  await expect(settings.locator('.iam-settings')).toBeVisible()
  await settings.locator('.iam-settings button[type="submit"]').click()
  // Nothing changed: the save is confirmed and no restart is asked for (restartNeeded is the real answer).
  await expect(settings.locator('.saved-ok')).toContainText(t['idn.set.savedNoRestart'])
  await shot(page, 'identity-01-settings')
  await page.keyboard.press('Escape')

  // The panel while IAM mode is off.
  await runFromMenu(page, t, action('identities'))
  const panel = page.locator('.identities-panel')
  await expect(panel.locator('.iam-off')).toHaveText(t['idn.off'])
  await page.keyboard.press('Escape')

  // The panel with answers: decoded claims, a badge, a refusal on the chain.
  const answers: Record<string, unknown> = {
    '/api/iam/status': { enabled: true },
    '/api/iam/agents': [
      { agent: 'ada', clientId: 'simulated-ada', serviceAccountId: 'sa-1', enabled: true, revoked: false },
      { agent: 'basile', clientId: null, serviceAccountId: null, enabled: false, revoked: false },
      { agent: 'chris', clientId: 'simulated-chris', serviceAccountId: 'sa-3', enabled: false, revoked: true },
    ],
    '/api/iam/exchanges': [
      {
        at: '2026-11-05T10:00:02.120Z',
        op: 'token exchange',
        method: 'POST',
        path: '/realms/floor42/protocol/openid-connect/token',
        status: 200,
        millis: 41,
        request: { grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange', client_id: 'floor42-agent-ada', client_secret: '•••', subject_token: '•••', audience: 'floor42-api' },
        answer: { access_token: '•••', expires_in: '300', 'access_token.azp': 'floor42-agent-ada', 'access_token.act': '{"sub":"director"}' },
        error: null,
      },
      { at: '2026-11-05T10:00:01.010Z', op: 'signing keys', method: 'GET', path: '/realms/floor42/protocol/openid-connect/certs', status: 200, millis: 6, request: {}, answer: { keys: '2' }, error: null },
      { at: '2026-11-05T10:00:00.500Z', op: 'introspect', method: 'POST', path: '/realms/floor42/protocol/openid-connect/token/introspect', status: 0, millis: 5003, request: { token: '•••' }, answer: {}, error: 'HttpTimeoutException' },
    ],
    '/api/iam/tokens': [
      {
        agent: 'ada',
        session: 'L1',
        launch: 'L1',
        jti: 'jti-1',
        header: { alg: 'RS256', typ: 'JWT', kid: 'k1' },
        claims,
        signaturePrefix: 'c2lnbmF0...',
        expiresAt: '2030-01-01T00:00:00Z',
        origin: 'SIMULATED',
      },
    ],
    '/api/iam/refusals': [{ at: '2026-11-05T10:00:03Z', code: 'WRONG_PROCESS', jti: 'jti-1', launch: 'L1' }],
    '/api/sessions/L1/trust-chain': {
      links: [
        { kind: 'DIRECTOR_SIGN_IN', label: 'director', detail: 'PKCE', at: null, ok: true, code: null },
        { kind: 'AGENT_TOKEN', label: 'floor42-agent-ada', detail: 'jti jti-1', at: null, ok: true, code: null },
        { kind: 'REFUSAL', label: 'WRONG_PROCESS', detail: '', at: '2026-11-05T10:00:03Z', ok: false, code: 'WRONG_PROCESS' },
      ],
    },
  }
  await page.route(/\/api\/(iam\/(status|agents|tokens|refusals|exchanges)|sessions\/L1\/trust-chain)$/, (route) => {
    const path = new URL(route.request().url()).pathname
    return route.fulfill({ json: answers[path] })
  })
  await runFromMenu(page, t, action('identities'))
  await expect(panel.locator('.badge.simulated')).toHaveText(t['idn.simulated'])
  await expect(panel.locator('.chain-line')).toHaveText('ada acts for the director on M-3/T-7')
  await expect(panel.locator('.trust-chain li.refused .code')).toHaveText('WRONG_PROCESS')
  expect(await panel.innerHTML()).not.toMatch(/eyJ[\w-]+\.[\w-]+\.[\w-]+/)
  await shot(page, 'identity-02-trust-chain')

  // Master-detail: the agents on the left, one line each; ada (a token in force) is shown first on the right, with its
  // buttons apart from its text; a click on basile shows basile's detail instead.
  await expect(panel.locator('.agent-item .state')).toHaveText([t['idn.row.ready'], t['idn.row.missing'], t['idn.row.revoked']])
  const detail = panel.locator('.identity-detail')
  await expect(detail).toHaveAttribute('data-agent', 'ada')
  const facts = await detail.locator('.facts').boundingBox()
  const buttons = await detail.locator('.row-actions').boundingBox()
  expect(buttons!.x - (facts!.x + facts!.width), 'a gap between the text and the buttons').toBeGreaterThanOrEqual(8)
  await panel.locator('.agent-item[data-agent="basile"]').click()
  await expect(detail).toHaveAttribute('data-agent', 'basile')
  await expect(detail.locator('.facts')).toContainText(t['idn.row.noClient'])
  await panel.locator('.agent-item[data-agent="ada"]').click()
  await shot(page, 'identity-03-agents')

  // The exchanges with Keycloak: one line each, opened on a click; masked values only.
  await panel.locator('.identities-top').getByRole('button', { name: t['idx.open'] }).click()
  const exchanges = page.locator('.idp-exchanges-panel')
  await expect(exchanges.locator('.exchange')).toHaveCount(3)
  await exchanges.locator('.exchange .line').first().click()
  await expect(exchanges.locator('.detail')).toContainText('floor42-agent-ada')
  await expect(exchanges.locator('.exchange.bad')).toHaveCount(1)
  await shot(page, 'identity-04-exchanges')
})
