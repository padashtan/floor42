import { ACTIONS } from '../src/ui/actions'
import { runFromMenu } from './bar'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

interface Mission {
  id: string
  status: string
  sessions: { agent: string; open: boolean }[]
}

/**
 * Journey 5: the director's meeting (ADR-0047), played by the fake Claude Code ([fake:meeting]: a developer reading
 * files until the hook refuses a call for a meeting). The director calls the developer at work to the table from the
 * Work menu, sees it seated, tells it something, closes the meeting, and the developer goes back to work.
 */
test('the director calls a developer at work to the table, then sends it back', async ({ page, daemon }) => {
  test.setTimeout(180_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const created = await page.request.post('/api/missions', {
    headers,
    data: { title: 'Meeting journey', objective: 'Describe it [fake:meeting]', repository: daemon.gitRepository() },
  })
  expect(created.status()).toBe(201)
  const id: string = (await created.json()).id
  const mission = async (): Promise<Mission> => (await page.request.get(`/api/missions/${id}`)).json()
  await expect.poll(async () => (await mission()).status).toBe('AWAITING_APPROVAL')
  expect((await page.request.post(`/api/missions/${id}/approve`, { headers, data: { noCap: true } })).status()).toBe(200)
  await expect.poll(async () => (await mission()).sessions.some((s) => s.open), { timeout: 60_000 }).toBe(true)
  // The developer at work: whoever the shared daemon staffed the mission with (another journey may have hired more).
  const workerId = (await mission()).sessions.find((s) => s.open)!.agent
  const staff: { id: string; name: string; status: string }[] = await (await page.request.get('/api/agents')).json()
  const workerName = staff.find((a) => a.id === workerId)!.name

  // Work → Call a meeting: the mission, the developer at work ticked, an agenda.
  await runFromMenu(page, t, ACTIONS.find((a) => a.id === 'callMeeting')!)
  const panel = page.locator('.director-meeting')
  await expect(panel).toBeVisible()
  await panel.locator('select.meeting-mission').selectOption(id)
  const worker = panel.locator('label.attendee', { hasText: workerName }).locator('input')
  await expect(worker).toBeChecked()
  await panel.locator('input.meeting-agenda').fill('The next step')
  await shot(page, '5-meeting-01-call')
  await panel.locator('button.meeting-call').click()

  // The developer's next tool call is refused for the meeting: it ends its turn and sits at the table.
  await expect(panel.locator('.attendees li.seated', { hasText: workerName })).toBeVisible({ timeout: 30_000 })
  await expect.poll(async () => (await mission()).sessions.some((s) => s.open)).toBe(false)
  const agents: { id: string; status: string }[] = await (await page.request.get('/api/agents')).json()
  expect(agents.find((a) => a.id === workerId)?.status).toBe('IN_MEETING')
  await shot(page, '5-meeting-02-seated')

  await panel.locator('select.meeting-to').selectOption(workerId)
  await panel.locator('textarea.meeting-note').fill('Start with the README')
  await panel.locator('button.meeting-say').click()
  await expect(panel.locator('.conversation .said.director', { hasText: 'Start with the README' })).toBeVisible()
  await shot(page, '5-meeting-03-note')

  await panel.locator('button.meeting-close').click()
  await expect(panel.locator('button.meeting-call')).toBeVisible()
  // Back to work: a new session of the developer, briefed with what was said.
  await expect.poll(async () => (await mission()).sessions.length, { timeout: 60_000 }).toBeGreaterThanOrEqual(2)
  await shot(page, '5-meeting-04-back-to-work')
  // Leave nothing waiting on the shared daemon: the other journeys answer every question they see.
  const cancel = await page.request.post(`/api/missions/${id}/cancel`, { headers, data: { reason: 'journey over' } })
  expect(cancel.status()).toBe(200)
})
