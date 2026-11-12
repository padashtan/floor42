import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { Locator, Page } from '@playwright/test'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

/** A 1x1 PNG, a one-page PDF, bytes of no known kind, an SVG and an HTML page that try to run: neutral samples. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)
const EVIL_SVG =
  '<svg xmlns="http://www.w3.org/2000/svg" width="60" height="30" onload="window.top.pwned = 1">' +
  '<rect width="60" height="30" fill="#a53"/><script>window.top.pwned = 2; top.document.title = "pwned"</script>' +
  '<image href="https://example.invalid/beacon.png" width="1" height="1"/></svg>\n'
const TRAP = '<html><body onload="window.top.pwned = 3"><script>window.top.pwned = 4</script></body></html>\n'
/**
 * A PDF of `pages` pages, each saying its number: the browser's viewer pages through it with its own controls. With
 * `script`, the document runs it on open (/OpenAction JavaScript), as a hostile PDF would try to.
 */
function pdf(pages: number, script?: string): string {
  const action = script ? ` /OpenAction << /S /JavaScript /JS (${script}) >>` : ''
  const objects = [`<< /Type /Catalog /Pages 2 0 R${action} >>`, '']
  const kids: string[] = []
  for (let p = 0; p < pages; p++) {
    const page = objects.length + 1
    const text = `BT /F1 18 Tf 20 50 Td (Page ${p + 1}) Tj ET`
    kids.push(`${page} 0 R`)
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents ${page + 1} 0 R /Resources << /Font << /F1 ${2 * pages + 3} 0 R >> >> >>`)
    objects.push(`<< /Length ${text.length} >>\nstream\n${text}\nendstream`)
  }
  objects[1] = `<< /Type /Pages /Kids [${kids.join(' ')}] /Count ${pages} >>`
  objects.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  objects.forEach((o, i) => {
    offsets.push(out.length)
    out += `${i + 1} 0 obj\n${o}\nendobj\n`
  })
  const xref = out.length
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  for (const o of offsets) out += `${String(o).padStart(10, '0')} 00000 n \n`
  return out + `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
}

/** Tries to leave the viewer: open a site, post to the daemon, fetch from it. */
// Balanced parentheses need no escape in a PDF string.
const HOSTILE = 'app.launchURL("https://example.invalid/launch", true); this.submitForm({cURL: "/api/missions"}); this.getURL("/api/status");'

const PDF_VIEWER = 'chrome-extension://mhjfbmdgcfjbbpaeojofohoefgiehjai/'
const pwned = (page: Page) => page.evaluate(() => (window as unknown as { pwned?: number }).pwned)
const naturalWidth = (img: Locator) => img.evaluate((e) => (e as HTMLImageElement).naturalWidth)

/**
 * Media in the file viewer (ADR-0051), from the director's own pending files on the desk: a photo fitted then zoomed,
 * a PDF in the browser's own viewer, a file of no known kind as a "no preview, download" card, and an SVG whose script
 * and onload never run. A file named .png holding HTML is refused by the daemon, never sent as HTML nor as an image.
 */
test("the viewer previews an image, a PDF and an SVG, and offers Download for the rest", async ({ page, daemon }) => {
  test.setTimeout(120_000)
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  const repo = daemon.gitRepository()
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  const created = await page.request.post('/api/missions', {
    headers,
    data: { title: 'Photo album', objective: 'Sort the photos', repository: repo },
  })
  expect(created.status(), await created.text()).toBe(201)
  const id: string = (await created.json()).id
  mkdirSync(join(repo, 'in'))
  writeFileSync(join(repo, 'in', 'photo.png'), PNG)
  writeFileSync(join(repo, 'in', 'report.pdf'), pdf(3))
  writeFileSync(join(repo, 'in', 'data.bin'), Buffer.from([0, 1, 2, 3]))
  writeFileSync(join(repo, 'in', 'evil.svg'), EVIL_SVG)
  writeFileSync(join(repo, 'in', 'trap.png'), TRAP)
  writeFileSync(join(repo, 'in', 'action.pdf'), pdf(1, HOSTILE))
  writeFileSync(join(repo, 'in', 'fake.pdf'), TRAP)

  // HTML named .png: the daemon's detection reads the bytes, so it is neither an image nor a page.
  const trap = await page.request.get(`/api/missions/${id}/files/file?path=in/trap.png`)
  expect(trap.status()).toBe(415)
  expect(trap.headers()['content-type']).not.toContain('html')
  const svg = await page.request.get(`/api/missions/${id}/files/file?path=in/evil.svg`)
  expect(svg.headers()['content-type']).toBe('image/svg+xml')
  expect(svg.headers()['content-disposition']).toMatch(/^attachment;/)
  expect(svg.headers()['x-content-type-options']).toBe('nosniff')
  expect(svg.headers()['content-security-policy']).toBe(
    "sandbox; default-src 'none'; img-src 'self' blob: data:; style-src 'unsafe-inline'",
  )

  // Positive control: the same SVG put in a page as a document, with no policy, runs its script and fetches its
  // <image href>. So the listener below would see such a request, and its absence in the viewer means something.
  const control = await page.context().newPage()
  const fetched: string[] = []
  control.on('request', (r) => fetched.push(r.url()))
  await control.route('https://example.invalid/**', (r) => r.abort())
  await control.setContent(`<!DOCTYPE html><html><body>${EVIL_SVG}</body></html>`)
  await expect.poll(() => fetched.filter((u) => u.includes('example.invalid')).length).toBeGreaterThan(0)
  await expect.poll(() => control.evaluate(() => (window as unknown as { pwned?: number }).pwned)).toBeDefined()
  await control.close()
  const outside = await page.request.get(`/api/missions/${id}/files/file?path=../outside.txt`)
  expect(outside.status()).toBe(400)

  // Every request the page and its frames make, and every window they open: none may leave for another site.
  const requests: { url: string; main: boolean }[] = []
  page.on('request', (r) => requests.push({ url: r.url(), main: r.frame() === page.mainFrame() }))
  const popups: string[] = []
  page.context().on('page', (p) => popups.push(p.url()))
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await page.keyboard.press('d')
  const desk = page.locator('.desk-panel')
  await desk.locator('.tabs [data-tab="mission"]').click()
  await expect(desk.locator('.pending-path', { hasText: 'in/photo.png' })).toBeVisible({ timeout: 15_000 })
  const viewer = page.locator('.file-viewer')
  const open = async (path: string) => {
    await desk.locator(`[data-view-key=":w:${path}"]`).click()
    await expect(viewer).toBeVisible()
    await expect(desk).toHaveCount(0)
  }
  const back = async (path: string) => {
    await page.keyboard.press('Escape')
    await expect(viewer).toHaveCount(0)
    await expect(desk).toBeVisible()
    await expect(page.locator(':focus')).toHaveAttribute('data-view-key', `:w:${path}`)
  }

  // A photo: fitted to the panel first, then at actual size and zoomed by steps.
  await open('in/photo.png')
  await expect.poll(() => naturalWidth(viewer.locator('img.image'))).toBe(1)
  await expect(viewer).toContainText(t['viewer.own'])
  await viewer.getByRole('button', { name: t['viewer.zoomIn'] }).click()
  await viewer.getByRole('button', { name: t['viewer.zoomIn'] }).click()
  await expect(viewer.locator('[data-zoom]')).toHaveText('150 %')
  await shot(page, '13-media-01-image')
  await back('in/photo.png')

  // A PDF: the browser's own viewer, paged with its own controls.
  await open('in/report.pdf')
  await expect(viewer.locator('iframe.pdf')).toHaveAttribute('src', /files\/file\?path=in%2Freport.pdf/)
  await expect.poll(() => page.frames().some((f) => f.url().startsWith(PDF_VIEWER)), { timeout: 15_000 }).toBe(true)
  // The extension draws its pages a moment after its frame exists.
  await page.waitForTimeout(2_000)
  await shot(page, '13-media-02-pdf')
  await back('in/report.pdf')

  // No known kind: a card that says so, with Download.
  await open('in/data.bin')
  const card = viewer.locator('[data-no-preview]')
  await expect(card).toContainText(t['viewer.noPreview'])
  await expect(card.getByRole('link', { name: t['viewer.download'] })).toHaveAttribute('href', /download=true/)
  await expect(viewer.locator('img, iframe')).toHaveCount(0)
  await shot(page, '13-media-03-unknown')
  await back('in/data.bin')

  // An SVG with a script and an onload: drawn as an image only, nothing of it runs.
  await open('in/evil.svg')
  await expect.poll(() => naturalWidth(viewer.locator('img.image'))).toBe(60)
  await page.waitForTimeout(500)
  expect(await pwned(page), 'no script of the SVG ran').toBeUndefined()
  expect(await page.title()).not.toBe('pwned')
  await expect(viewer.locator('svg, iframe, object, embed')).toHaveCount(0)
  await shot(page, '13-media-04-svg')
  await back('in/evil.svg')
  expect(await pwned(page)).toBeUndefined()

  // A PDF whose /OpenAction tries to open a site, post a form and fetch from the daemon: nothing leaves the viewer.
  const before = requests.length
  const at = page.url()
  await open('in/action.pdf')
  await expect.poll(() => page.frames().some((f) => f.url().startsWith(PDF_VIEWER)), { timeout: 15_000 }).toBe(true)
  await page.waitForTimeout(3_000)
  const fromFrames = requests
    .slice(before)
    .filter((r) => !r.main && r.url.includes('/api/') && !r.url.includes('/files/file?path=in%2Faction.pdf'))
  expect(fromFrames, 'no call to the daemon from the PDF').toEqual([])
  expect(page.url(), 'the page did not navigate').toBe(at)
  expect(popups, 'no window opened').toEqual([])
  await shot(page, '13-media-05-pdf-action')
  await back('in/action.pdf')

  // A .pdf whose bytes are HTML: the daemon refuses it, and the panel says so instead of showing its JSON answer.
  await open('in/fake.pdf')
  await expect(viewer.locator('[data-no-preview]')).toContainText(t['viewer.pdfFailed'])
  await expect(viewer.locator('iframe')).toHaveCount(0)
  expect(await pwned(page)).toBeUndefined()
  await shot(page, '13-media-06-pdf-refused')
  await back('in/fake.pdf')

  // HTML named .png, from the desk too: the card, no <img>, nothing of it runs.
  await open('in/trap.png')
  await expect(viewer.locator('[data-no-preview]')).toContainText(t['viewer.imageFailed'])
  await expect(viewer.locator('img, iframe')).toHaveCount(0)
  expect(await pwned(page)).toBeUndefined()
  await back('in/trap.png')

  expect(requests.filter((r) => r.url.includes('example.invalid')), 'no request to another site').toEqual([])
})
