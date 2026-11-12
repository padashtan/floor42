import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

interface Question {
  id: string
  kind: string
  status: string
  subject: string
  askBack: { rounds: number; possible: boolean; text: string | null }
}

/**
 * Journey 15: ask back before answering (ADR-0059). The fake Claude Code asks the director a question
 * ([fake:askback=...]) and asks the next one each time the director asks back. The desk offers "Ask back" with its
 * cost (one more paid turn of the agent's session), the director sends a remark, the first question is withdrawn with
 * the director's words, the agent asks again, and the desk shows how many times it was asked back.
 */
test('the director asks an agent back before answering it', async ({ page, daemon }) => {
  test.setTimeout(180_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  const created = await page.request.post('/api/missions', {
    headers,
    data: {
      title: 'Ask back journey',
      objective: 'Ask first [fake:askback=Which colour for the banner?||Blue or red then?]',
      repository: daemon.gitRepository(),
    },
  })
  expect(created.status()).toBe(201)
  const id: string = (await created.json()).id
  await expect
    .poll(async () => (await (await page.request.get(`/api/missions/${id}`)).json()).status, { timeout: 90_000 })
    .toBe('AWAITING_APPROVAL')
  expect((await page.request.post(`/api/missions/${id}/approve`, { headers, data: { noCap: true } })).status()).toBe(200)
  const open = async (): Promise<Question[]> =>
    ((await (await page.request.get('/api/questions')).json()) as Question[]).filter((q) => q.kind === 'OPEN')
  await expect.poll(async () => (await open()).length, { timeout: 90_000 }).toBeGreaterThan(0)
  const first = (await open())[0]
  expect(first.askBack.possible).toBe(true)

  // The question stays on screen under the bar, above the crew, until it is settled; "Open" goes to it (pilot entry 15).
  const banner = page.locator('[data-testid="waiting-banner"]')
  await expect(banner).toContainText(first.subject)
  const [b, crew] = [await banner.boundingBox(), await page.locator('.crew').boundingBox()]
  expect(crew!.y).toBeGreaterThanOrEqual(b!.y + b!.height)
  await shot(page, '15-ask-back-00-waiting-banner')
  await banner.getByRole('button', { name: t['ux.toast.open'] }).first().click()
  await expect(page.locator('.desk-panel')).toBeVisible()
  const card = page.locator('.desk-panel .question', { hasText: first.id }).first()
  await expect(card).toBeVisible()
  await card.locator('[data-ask-back-open]').click()
  const dialog = card.locator('[data-ask-back]')
  await expect(dialog).toContainText('one more paid turn')
  await dialog.locator('textarea').fill('Which brand guide do you follow?')
  await shot(page, '15-ask-back-01-dialog')
  await dialog.locator('button[type="submit"]').click()

  // The first question is withdrawn with the director's words; the agent asks the next one, a round further.
  await expect(async () => {
    const q = (await (await page.request.get(`/api/missions/${id}`)).json()).questions as Question[]
    expect(q.find((x) => x.id === first.id)?.status).toBe('WITHDRAWN')
    expect(q.find((x) => x.id === first.id)?.askBack.text).toBe('Which brand guide do you follow?')
  }).toPass({ timeout: 60_000, intervals: [1_000] })
  await expect.poll(async () => (await open()).find((q) => q.subject === 'Blue or red then?')?.askBack.rounds, {
    timeout: 60_000,
  }).toBe(1)
  const next = page.locator('.desk-panel .question', { hasText: 'Blue or red then?' }).first()
  await expect(next.locator('[data-ask-back-rounds]')).toContainText(t['askBack.rounds'].replace('{n}', '1'))
  await shot(page, '15-ask-back-02-asked-again')
})
