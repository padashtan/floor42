import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'
import { atADesk, atBreak, near, whereIs } from './office'

const t = CATALOGS.en

interface Mission {
  status: string
  questions: { id: string; status: string }[]
  tickets: { status: string; assignee: string | null }[]
  sessions: { open: boolean }[]
}

/**
 * Journey 12: a lively office. Once its session has ended, the developer of a mission still in progress does not leave:
 * its status is available (nothing is being done), yet the scene keeps it at its desk, idle, as the canvas data says.
 */
test('an agent stays at its desk after its session ends, while the mission is in progress', async ({ page, daemon }) => {
  test.setTimeout(240_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const created = await page.request.post('/api/missions', {
    headers,
    data: { title: 'Lively journey', objective: 'Describe it', repository: daemon.gitRepository() },
  })
  const id: string = (await created.json()).id
  const mission = async (): Promise<Mission> => (await page.request.get(`/api/missions/${id}`)).json()
  await expect.poll(async () => (await mission()).status).toBe('AWAITING_APPROVAL')
  expect((await page.request.post(`/api/missions/${id}/approve`, { headers, data: { noCap: true } })).status()).toBe(200)

  // Whatever the agent asks is allowed; then its ticket is done and no session is open: the mission is still in
  // progress, waiting for the director to close it.
  await expect(async () => {
    const m = await mission()
    for (const q of m.questions.filter((x) => x.status === 'OPEN')) {
      await page.request.post(`/api/questions/${q.id}/answer`, { headers, data: { answer: 'ALLOW' } })
    }
    expect(m.tickets.length).toBeGreaterThan(0)
    expect(m.tickets.every((x) => x.status === 'DONE') && m.sessions.every((s) => !s.open)).toBe(true)
  }).toPass({ timeout: 120_000, intervals: [1_000] })
  const worker = (await mission()).tickets[0].assignee!
  expect((await mission()).status).toBe('IN_PROGRESS')
  const agents: { id: string; status: string }[] = await (await page.request.get('/api/agents')).json()
  expect(agents.find((a) => a.id === worker)?.status).toBe('AVAILABLE')

  // Sitting at a desk seat of the open space (not at the elevator), and still there, unmoved, 8 seconds later.
  const here = async () => (await whereIs(page, worker)) ?? { visible: false, x: 0, z: 0 }
  await expect
    .poll(async () => {
      const first = await here() // seated, not walking past a desk
      await page.waitForTimeout(700)
      const second = await here()
      return second.visible && atADesk(second) && near(first, second, 0.02)
    }, { timeout: 30_000 })
    .toBe(true)
  const seat = (await whereIs(page, worker))!
  for (let i = 0; i < 8; i++) {
    await page.waitForTimeout(1_000)
    const at = (await whereIs(page, worker))!
    expect(at.visible).toBe(true)
    expect(near(at, seat, 0.1), JSON.stringify({ at, seat, i })).toBe(true)
  }
  await shot(page, 'lively-01-idle-office')

  // The mission over, nobody is resident: the agent goes to the lounge (a seat of the sofa or the ping-pong table, or
  // of the chat table beside them), and sits there, unmoved.
  const cancel = await page.request.post(`/api/missions/${id}/cancel`, { headers, data: { reason: 'journey over' } })
  expect(cancel.status()).toBe(200)
  await expect
    .poll(async () => {
      const first = await here()
      await page.waitForTimeout(700)
      const second = await here()
      return second.visible && atBreak(second) && near(first, second, 0.02)
    }, { timeout: 60_000 })
    .toBe(true)
  await shot(page, 'lively-03-lounge')
})
