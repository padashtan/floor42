import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { Locator, Page } from '@playwright/test'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

/** A 1x1 PNG, a small SVG, a one-page PDF and a Markdown file holding a script: neutral samples. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)
const SVG = '<?xml version="1.0"?>\n<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="40" height="20" fill="#3a7"/><script>window.top.hit = 1</script></svg>\n'
const MARKDOWN = '# Release notes\n\nThe gateway is ready.\n\n<script>window.hit = 2</script>\n'
function pdf(): string {
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    '<< /Length 44 >>\nstream\nBT /F1 18 Tf 20 50 Td (Sample report) Tj ET\nendstream',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ]
  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  objects.forEach((o, i) => {
    offsets.push(out.length)
    out += `${i + 1} 0 obj\n${o}\nendobj\n`
  })
  const xref = out.length
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  for (const o of offsets) out += `${String(o).padStart(10, '0')} 00000 n \n`
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return out
}

interface Mission {
  id: string
  title: string
  status: string
  tickets: { status: string }[]
  questions: { id: string; status: string }[]
}

/** Clicks the View button of a file, then checks the viewer took the list's place. */
async function view(page: Page, list: Locator, key: string): Promise<Locator> {
  await list.locator(`[data-view-key="${key}"]`).click()
  const viewer = page.locator('.file-viewer')
  await expect(viewer).toBeVisible()
  await expect(list).toHaveCount(0)
  return viewer
}

/** Closes the viewer by `how`; the list comes back and the focus is on the button that opened the viewer. */
async function closeBack(page: Page, viewer: Locator, list: Locator, key: string, how: 'escape' | 'button'): Promise<void> {
  if (how === 'escape') await page.keyboard.press('Escape')
  else await viewer.getByRole('button', { name: t['viewer.close'] }).click()
  await expect(viewer).toHaveCount(0)
  await expect(list).toBeVisible()
  await expect(page.locator(':focus')).toHaveAttribute('data-view-key', key)
}

const naturalWidth = (img: Locator) => img.evaluate((e) => (e as HTMLImageElement).naturalWidth)

/**
 * Where deliverables land, and the file viewer (ADR-0051): a mission created with its deliverables folder, played by
 * the fake Claude Code; its Markdown, PNG, SVG and PDF opened in the viewer from the deliverables, a pending change
 * opened from the workshop, the focus back on the opener each time; then the delivery accepted and its documents found
 * in `<project>/deliverables/<mission>/`.
 */
test('a mission delivers into its folder, and its files open in the viewer', async ({ page, daemon }) => {
  test.setTimeout(240_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  const repo = daemon.gitRepository()
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  // The desk, "New mission": the deliverables folder is filled with the project's deliverables/ by default.
  await page.keyboard.press('d')
  const desk = page.locator('.desk-panel')
  await desk.locator('.tabs button', { hasText: t['desk.newMission'] }).click()
  const form = desk.locator('form.new-mission')
  await form.getByPlaceholder(t['mission.fieldTitle']).fill('Release notes')
  await form.getByPlaceholder(t['mission.fieldObjective']).fill('Write the release notes [fake:touch=notes.txt]')
  await form.getByPlaceholder(t['mission.fieldRepository']).fill(repo)
  await expect(form.locator('input[name="deliverTo"]')).toHaveValue('deliverables')
  await form.locator('label', { hasText: t['mission.letGalenPlan'] }).locator('input').uncheck()
  await shot(page, '12-home-01-new')
  await form.getByRole('button', { name: t['mission.propose'], exact: true }).click()

  let id = ''
  const mission = async (): Promise<Mission> => (await page.request.get(`/api/missions/${id}`)).json()
  await expect
    .poll(async () => {
      const all: Mission[] = await (await page.request.get('/api/missions')).json()
      id = all.find((x) => x.title === 'Release notes')?.id ?? ''
      return id && (await mission()).status
    })
    .toBe('AWAITING_APPROVAL')
  const created = await (await page.request.get(`/api/missions/${id}`)).json()
  expect(created.delivery.destination, 'the folder journaled at creation').toBe(join(repo, 'deliverables'))
  await page.keyboard.press('Escape')

  // Approved with no cap and played to the end, through the API: the desk was shown by the main journey.
  const approved = await page.request.post(`/api/missions/${id}/approve`, { headers, data: { noCap: true } })
  expect(approved.status(), await approved.text()).toBe(200)
  await expect(async () => {
    const m = await mission()
    for (const q of m.questions.filter((x) => x.status === 'OPEN')) {
      await page.request.post(`/api/questions/${q.id}/answer`, { headers, data: { answer: 'ALLOW' } })
    }
    expect(m.tickets.map((x) => x.status)).toEqual(m.tickets.map(() => 'DONE'))
  }).toPass({ timeout: 120_000, intervals: [1_000] })

  // What the agent left in its worktree, beside the fake's notes.txt.
  const listing = await (await page.request.get(`/api/missions/${id}/deliverables`)).json()
  const agent: string = listing.branches[0].agent
  const worktree: string = listing.branches[0].worktree
  writeFileSync(join(worktree, 'notes.md'), MARKDOWN)
  writeFileSync(join(worktree, 'diagram.png'), PNG)
  writeFileSync(join(worktree, 'diagram.svg'), SVG)
  writeFileSync(join(worktree, 'report.pdf'), pdf())
  writeFileSync(join(worktree, 'build.sh'), 'echo build\n')
  const key = (path: string) => `${agent}:w:${path}`

  // The deliverables, from the board: each file opens in the viewer, which takes the list's place.
  await page.keyboard.press('b')
  const board = page.locator('.board-panel')
  await expect(board.locator('.delivery')).toContainText(join(repo, 'deliverables'))
  await board.getByRole('button', { name: t['deliverables.open'], exact: true }).click()
  const deliverables = page.locator('.deliverables-panel')
  await deliverables.locator('details.files-of summary').first().click()

  let viewer = await view(page, deliverables, key('notes.md'))
  await expect(viewer.getByRole('heading', { name: 'Release notes' })).toBeVisible()
  await expect(viewer.locator('script')).toHaveCount(0)
  await expect(viewer.getByRole('link', { name: t['viewer.download'] })).toHaveAttribute('href', /download=true/)
  await shot(page, '12-home-02-markdown')
  await closeBack(page, viewer, deliverables, key('notes.md'), 'escape')

  viewer = await view(page, deliverables, key('diagram.png'))
  await expect.poll(() => naturalWidth(viewer.locator('img.image'))).toBe(1)
  await closeBack(page, viewer, deliverables, key('diagram.png'), 'button')

  viewer = await view(page, deliverables, key('diagram.svg'))
  await expect.poll(() => naturalWidth(viewer.locator('img.image'))).toBe(40)
  expect(await page.evaluate(() => (window as unknown as { hit?: number }).hit), 'no script of the SVG ran').toBeUndefined()
  await shot(page, '12-home-03-svg')
  await closeBack(page, viewer, deliverables, key('diagram.svg'), 'escape')

  viewer = await view(page, deliverables, key('report.pdf'))
  const frame = viewer.locator('iframe.pdf')
  await expect(frame).toHaveAttribute('src', /path=report.pdf/)
  const sandbox = await frame.getAttribute('sandbox')
  test.info().annotations.push({ type: 'pdf iframe sandbox', description: String(sandbox) })
  // Chromium shows a PDF through its own viewer, a frame of its PDF extension: never an error page.
  const PDF_VIEWER = 'chrome-extension://mhjfbmdgcfjbbpaeojofohoefgiehjai/'
  await expect.poll(() => page.frames().some((f) => f.url().startsWith(PDF_VIEWER)), { timeout: 15_000 }).toBe(true)
  // Measured on 2026-11-02 (ADR-0051): with any sandbox attribute ("", allow-scripts, allow-same-origin, both),
  // Chromium refuses the PDF and shows an error page, so the frame has none and the daemon's policy guards the bytes.
  expect(sandbox, 'Chromium does not show a sandboxed PDF').toBeNull()
  const probe = await page.context().newPage()
  await probe.goto('/')
  await probe.evaluate((src) => {
    const f = document.createElement('iframe')
    f.setAttribute('sandbox', '')
    f.src = src
    document.body.appendChild(f)
  }, (await frame.getAttribute('src'))!)
  await probe.waitForTimeout(3_000)
  expect(probe.frames().some((f) => f.url().startsWith(PDF_VIEWER)), 'a sandboxed frame shows no PDF').toBe(false)
  await probe.close()
  await shot(page, '12-home-04-pdf')
  await closeBack(page, viewer, deliverables, key('report.pdf'), 'escape')
  await page.keyboard.press('Escape')
  await expect(deliverables).toHaveCount(0)

  // The workshop: a pending change opens in the viewer too, and the focus comes back to its View button.
  await page.keyboard.press('w')
  const workshop = page.locator('.panel.workshop')
  await expect(workshop).toBeVisible()
  await workshop.locator('.file-row', { hasText: 'notes.md' }).first().click()
  viewer = await view(page, workshop, key('notes.md'))
  await expect(viewer).toContainText('The gateway is ready.')
  await closeBack(page, viewer, workshop, key('notes.md'), 'escape')
  await page.keyboard.press('Escape')
  await expect(workshop).toHaveCount(0)

  // Accepted from the board: the documents are in <project>/deliverables/<mission>/, the script is not.
  await page.keyboard.press('b')
  await expect(board).toBeVisible()
  await board.getByRole('button', { name: t['board.deliveryAccept'] }).click()
  // No PROVE.md either (ADR-0058): said first, accepted unproven.
  const unproven = page.locator('.confirm-dialog')
  await expect(unproven).toContainText('left no PROVE.md')
  await unproven.getByRole('button', { name: t['prove.acceptAnyway'] }).click()
  // Chris's worktree holds files in no commit (ADR-0055): said before the acceptance, accepted as it is.
  const uncommitted = page.locator('.confirm-dialog')
  await expect(uncommitted).toContainText('build.sh')
  await uncommitted.getByRole('button', { name: t['uncommitted.acceptAnyway'] }).click()
  await expect.poll(async () => (await mission()).status).toBe('COMPLETED')
  const folder = join(repo, 'deliverables', id)
  expect(readFileSync(join(folder, 'notes.md'), 'utf8')).toBe(MARKDOWN)
  expect(readFileSync(join(folder, 'report.pdf'), 'latin1').startsWith('%PDF-')).toBe(true)
  for (const doc of ['notes.txt', 'diagram.png']) expect(existsSync(join(folder, doc)), doc).toBe(true)
  expect(existsSync(join(folder, 'diagram.svg')), 'an SVG can carry a script: never copied').toBe(false)
  expect(existsSync(join(folder, 'build.sh')), 'a script is never copied').toBe(false)
  const closed = await mission()
  expect((closed as unknown as { delivery: { copy: { folder: string } } }).delivery.copy.folder).toBe(folder)
  await expect(page.locator('.deliverables-panel .delivery-home')).toContainText(folder)
  await shot(page, '12-home-05-copied')
})
