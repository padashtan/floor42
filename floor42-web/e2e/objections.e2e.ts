import { ACTIONS } from '../src/ui/actions'
import { runFromMenu } from './bar'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

interface Mission {
  status: string
  tickets: { id: string; status: string; directives: string[] }[]
  questions: { id: string; status: string }[]
}

/**
 * Journey 14: objections at acceptance (ADR-0060). A mission is delivered; on the board the director clicks "Object",
 * writes one objection on the producer's done ticket, reads what the round costs in sessions in the confirmation,
 * sends it: the ticket goes back to work in the same mission with the text as a directive, is done again after its
 * review, and the delivery block asks for the acceptance again.
 */
test('an objection at acceptance sends the producer back to work in the same mission', async ({ page, daemon }) => {
  test.setTimeout(180_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const created = await page.request.post('/api/missions', {
    headers,
    data: { title: 'Objection journey', objective: 'Write notes [fake:touch=notes.txt]', repository: daemon.gitRepository() },
  })
  expect(created.status()).toBe(201)
  const id: string = (await created.json()).id
  const mission = async (): Promise<Mission> => (await page.request.get(`/api/missions/${id}`)).json()
  const delivered = async () => {
    const m = await mission()
    for (const q of m.questions.filter((x) => x.status === 'OPEN'))
      await page.request.post(`/api/questions/${q.id}/answer`, { headers, data: { answer: 'ALLOW' } })
    expect(m.tickets.length).toBeGreaterThan(0)
    expect(m.tickets.map((x) => x.status)).toEqual(m.tickets.map(() => 'DONE'))
  }
  await expect.poll(async () => (await mission()).status).toBe('AWAITING_APPROVAL')
  expect((await page.request.post(`/api/missions/${id}/approve`, { headers, data: { noCap: true } })).status()).toBe(200)
  await expect(delivered).toPass({ timeout: 120_000, intervals: [1_000] })

  await page.evaluate((m) => localStorage.setItem('floor42.board.mission', m), id)
  await page.reload()
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await runFromMenu(page, t, ACTIONS.find((a) => a.id === 'board')!)
  const board = page.locator('.board-panel')
  await expect(board.locator('.delivery')).toContainText(t['board.deliveryAccept'])

  // The form: one row, on the producer's latest done ticket; nothing can be sent until the text is filled.
  const ticket = (await mission()).tickets[0].id
  await board.getByRole('button', { name: t['objections.open'], exact: true }).click()
  // It opens as a panel of its own, on the frame of the others, with the first row focused.
  const form = page.locator('.objections-panel')
  await expect(form).toBeVisible()
  await expect(form.locator('select')).toHaveValue(ticket)
  await expect(form.locator('textarea')).toBeFocused()
  await expect(form.getByRole('button', { name: t['objections.send'] })).toBeDisabled()
  await form.locator('textarea').fill('Also write the French notes')
  // Three objections stay inside the frame and the footer stays in view; the extra two are then removed.
  for (const text of ['The notes need a title', 'Add a closing line'] as const) {
    await form.getByRole('button', { name: t['objections.add'] }).click()
    await form.locator('textarea').last().fill(text)
  }
  await expect(form.locator('.objection')).toHaveCount(3)
  const box = (await form.boundingBox())!
  expect(box.y + box.height).toBeLessThanOrEqual(900)
  await expect(form.getByRole('button', { name: t['objections.send'] })).toBeInViewport()
  await shot(page, '14-objections-01-three')
  for (let i = 0; i < 2; i++) await form.getByRole('button', { name: t['objections.remove'] }).last().click()
  await expect(form.locator('.objection')).toHaveCount(1)
  await shot(page, '14-objections-01-form')

  // The confirmation says what the round costs; the objection is sent and the same ticket goes back to work.
  await form.getByRole('button', { name: t['objections.send'] }).click()
  const dialog = page.locator('.confirm-dialog')
  await expect(dialog).toContainText('At least 1 producer session(s) and 1 review session(s)')
  await shot(page, '14-objections-02-confirm')
  await dialog.getByRole('button', { name: t['objections.confirmLabel'], exact: true }).click()
  await expect
    .poll(async () => (await mission()).tickets.find((x) => x.id === ticket)?.directives ?? [])
    .toEqual(['Also write the French notes'])
  await expect.poll(async () => (await mission()).tickets.length).toBe(1)
  await expect(delivered).toPass({ timeout: 120_000, intervals: [1_000] })
  await expect(board.locator('.delivery')).toContainText(t['board.deliveryAccept'])
  await shot(page, '14-objections-03-delivered-again')

  const events: { type: string; causationId: string | null }[] = await (await page.request.get('/api/journal?limit=100')).json()
  expect(events.filter((e) => e.type === 'DirectiveGiven' && e.causationId === 'objections-1')).toHaveLength(1)
})
