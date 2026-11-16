import { readFile } from 'node:fs/promises'
import { PANEL_ACTIONS, runFromMenu } from './bar'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'
import { clearAway } from './seed'

const t = CATALOGS.en
const dossierAction = PANEL_ACTIONS.find((a) => a.id === 'dossier')!

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
})

test(
  'the proof dossier lists a refusal with its event, labels every cost and downloads masked',
  { tag: '@reports' },
  async ({ page, daemon }) => {
    test.setTimeout(180_000)
    const headers = { 'X-Floor42-Request': '1', Origin: daemon.url, 'Idempotency-Key': 'dossier-journey-1' }
    const created = await page.request.post('/api/missions', {
      headers,
      data: { title: 'Dossier journey', objective: 'Describe it', repository: daemon.gitRepository(), committee: [], plan: false, panel: 'architecture' },
    })
    expect(created.status(), await created.text()).toBe(201)
    const id: string = (await created.json()).id
    // The daemon's own dossier, with one refusal added to what the journal holds: the browser's rendering is what is
    // under test here, the server's facts are covered by ProofDossiersTest.
    await page.route(new RegExp(`/api/missions/${id}/proof-dossier$`), async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      body.refusals = {
        count: 1,
        items: [
          {
            at: '2026-11-13T10:00:03Z',
            agent: 'ada',
            tool: 'Bash',
            reason: 'POLICY',
            detail: 'git push is refused',
            identity: false,
            ref: { source: 'JOURNAL', origin: 'REAL', events: [{ seq: 3, eventId: 'e3', type: 'ActionDenied' }] },
          },
        ],
      }
      await route.fulfill({ response, json: body })
    })
    await page.reload()
    await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
    await clearAway(page)
    await runFromMenu(page, t, dossierAction)
    const panel = page.locator('.dossier-panel')
    await expect(panel).toBeVisible()
    await expect(panel.getByText(t['dossier.statement']).first()).toBeVisible()
    await expect.soft(panel.getByText(t['dossier.iamOff'])).toBeVisible()
    await panel.locator('[data-section-tab="refusals"]').click()
    await expect.soft(panel.locator('.dossier-row')).toHaveCount(1)
    await expect.soft(panel.locator('.dossier-row').first()).toContainText('#3 ActionDenied')
    await panel.locator('[data-section-tab="costs"]').click()
    for (const row of await panel.locator('.dossier-row').all()) {
      // A figure always carries its basis word; a session without cost says so.
      await expect.soft(row).toContainText(/measured|estimated|simulated|upper bound|derived|no cost reported|journal/)
    }
    await shot(page, 'dossier-panel')
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      panel.getByRole('button', { name: t['dossier.downloadMd'] }).click(),
    ])
    expect(download.suggestedFilename()).toBe(`floor42-dossier-${id}.md`)
    const text = await readFile((await download.path())!, 'utf8')
    expect(text.startsWith(`# ${t['dossier.title'].replace('{mission}', id)}`)).toBe(true)
    expect(text).toContain(t['dossier.statement'])
    // The daemon's own token lives in memory only (nothing to read here; ProofDossierApiTest holds it against the
    // dossier). What the browser holds is checked: no cookie of this session is in the file.
    for (const cookie of await page.context().cookies()) expect(text).not.toContain(cookie.value)
  },
)
