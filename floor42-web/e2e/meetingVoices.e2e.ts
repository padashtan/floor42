import { ACTIONS } from '../src/ui/actions'
import { runFromMenu } from './bar'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'
import { CONFERENCE_AT, near, whereIs } from './office'

const t = CATALOGS.en

interface Mission {
  status: string
  sessions: { agent: string; open: boolean }[]
}

/**
 * Journey 5b: turns of speech at the director's meeting (ADR-0049), played by the fake Claude Code. The director calls
 * the developer at work to the conference table: it sits there under the meeting's sign. Reopened, the panel always
 * shows the open meeting and its close button, and a failed read shows its error, never the call form. The director
 * asks for their view within a cap, reads the answer in the conversation, replies and closes: the sign goes out. The
 * scene walks the attendee to the conference room, marks it as the speaker when its turn is heard, and walks it back to
 * its desk when the meeting ends (canvas data `office` and `speaking`).
 */
test('the director asks the attendees for their view at the conference table', async ({ page, daemon, consoleErrors }) => {
  test.setTimeout(240_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const created = await page.request.post('/api/missions', {
    headers,
    data: { title: 'Voices journey', objective: 'Describe it [fake:meeting]', repository: daemon.gitRepository() },
  })
  const id: string = (await created.json()).id
  const mission = async (): Promise<Mission> => (await page.request.get(`/api/missions/${id}`)).json()
  await expect.poll(async () => (await mission()).status).toBe('AWAITING_APPROVAL')
  expect((await page.request.post(`/api/missions/${id}/approve`, { headers, data: { noCap: true } })).status()).toBe(200)
  await expect.poll(async () => (await mission()).sessions.some((s) => s.open), { timeout: 60_000 }).toBe(true)
  // The developer at work: whoever the shared daemon staffed the mission with (another journey may have hired more).
  const workerId = (await mission()).sessions.find((s) => s.open)!.agent
  const staff: { id: string; name: string }[] = await (await page.request.get('/api/agents')).json()
  const workerName = staff.find((a) => a.id === workerId)!.name

  const callMeeting = ACTIONS.find((a) => a.id === 'callMeeting')!
  await runFromMenu(page, t, callMeeting)
  const panel = page.locator('.director-meeting')
  await panel.locator('select.meeting-mission').selectOption(id)
  await panel.locator('select.meeting-place').selectOption('CONFERENCE')
  await expect(panel.locator('label.attendee', { hasText: workerName }).locator('input')).toBeChecked()
  await panel.locator('input.meeting-agenda').fill('Review the release notes')
  await panel.locator('button.meeting-call').click()

  // Seated at the conference table, under its sign.
  await expect(panel.locator('.attendees li.seated', { hasText: workerName })).toBeVisible({ timeout: 30_000 })
  await expect(page.locator('canvas[data-meeting-signs~="CONFERENCE"]')).toHaveCount(1)
  await expect.poll(async () => near((await whereIs(page, workerId)) ?? { x: 0, z: 0 }, CONFERENCE_AT, 3), { timeout: 20_000 }).toBe(true)
  await shot(page, '5b-voices-01-seated')
  // The camera on the attendee, the panel out of the way: the meeting as the director sees it in the room.
  await panel.locator('button.close').click()
  await page.locator('.crew .card', { hasText: workerName }).first().click()
  await page.waitForTimeout(2_500)
  await shot(page, 'lively-02-meeting-in-progress')

  // Reopened, the open meeting shows with its close button; a failed read shows its error, never the call form.
  const meetingsRead = (url: URL) => url.pathname === '/api/meetings'
  await page.route(meetingsRead, (route) =>
    route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":"DOWN","message":"daemon busy"}' }),
  )
  await runFromMenu(page, t, callMeeting)
  await expect(panel.locator('.meeting-read-error')).toBeVisible()
  await expect(panel.locator('button.meeting-call')).toHaveCount(0)
  await page.unroute(meetingsRead)
  await expect(panel.locator('button.meeting-close')).toBeVisible({ timeout: 10_000 })
  await expect(panel.locator('.meeting-read-error')).toHaveCount(0)
  const refused = consoleErrors.filter((e) => /status of 503/.test(e))
  consoleErrors.splice(0, consoleErrors.length, ...consoleErrors.filter((e) => !refused.includes(e)))

  // Ask for their view, within a cap set before it starts: the answer comes into the conversation with its cost.
  await panel.locator('input.meeting-question').fill('Is it ready?')
  await panel.locator('input.meeting-cap').fill('0.5')
  await panel.locator('button.meeting-ask-go').click()
  const answer = panel.locator('.conversation .said.done', { hasText: 'README.md' })
  await expect(answer).toBeVisible({ timeout: 60_000 })
  await expect(answer).toContainText('estimated by Claude Code')
  // The turn heard marks its speaker on the canvas for a few seconds.
  await expect(page.locator(`canvas[data-speaking~="${workerId}"]`)).toHaveCount(1, { timeout: 10_000 })
  await shot(page, '5b-voices-02-answered')

  await panel.locator('select.meeting-to').selectOption(workerId)
  await panel.locator('textarea.meeting-note').fill('Add how to run the tests')
  await panel.locator('button.meeting-say').click()
  await expect(panel.locator('.conversation .said.director', { hasText: 'Add how to run the tests' })).toBeVisible()

  await panel.locator('button.meeting-close').click()
  await expect(panel.locator('button.meeting-call')).toBeVisible()
  await expect(page.locator('canvas[data-meeting-signs~="CONFERENCE"]')).toHaveCount(0, { timeout: 10_000 })
  // Out of the conference room, where its status sends it now (its new session works, or asks the director something).
  await expect
    .poll(async () => {
      const at = await whereIs(page, workerId)
      return !!at && at.visible && !near(at, CONFERENCE_AT, 3)
    }, { timeout: 60_000 })
    .toBe(true)
  await shot(page, '5b-voices-03-closed')
  const cancel = await page.request.post(`/api/missions/${id}/cancel`, { headers, data: { reason: 'journey over' } })
  expect(cancel.status()).toBe(200)
})
