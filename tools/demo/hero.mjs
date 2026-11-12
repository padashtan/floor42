// Records docs/media/hero.gif: the online demo (floor42-web/demo/tape.json, ADR-0031) played in headless Chrome.
//
//   cd floor42-web && npm run build:demo && npx vite preview --outDir dist-demo --port 4173
//   node tools/demo/hero.mjs [http://localhost:4173/] [docs/media/hero.gif]
//
// Needs Chrome (FLOOR42_CHROME names its executable) and ffmpeg on the PATH. The page answers the demo's question
// (Answer, then Allow) as a visitor would; everything shown is the recorded simulated mission, marked SIM.
import { spawn, execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const url = process.argv[2] ?? 'http://localhost:4173/'
const out = process.argv[3] ?? 'docs/media/hero.gif'
const chrome =
  process.env.FLOOR42_CHROME ??
  (process.platform === 'win32'
    ? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
    : process.platform === 'darwin'
      ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
      : 'google-chrome')
const SECONDS = 34
const EVERY_MS = 125
const click = (label) =>
  `[...document.querySelectorAll('button')].find(b => b.offsetParent && b.textContent.trim() === '${label}')?.click()`
const actions = [
  { at: 14500, js: click('Answer') },
  { at: 17500, js: click('Allow') },
  { at: 19500, js: `document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))` },
]

const work = mkdtempSync(join(tmpdir(), 'floor42-hero-'))
const port = 9333
const proc = spawn(chrome, [
  '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${join(work, 'profile')}`,
  '--window-size=1280,720', '--no-first-run', '--hide-scrollbars', '--lang=en-US', 'about:blank',
], { stdio: 'ignore' })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let targets
for (let i = 0; i < 150 && !targets; i++) {
  try {
    targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
  } catch {
    await sleep(200)
  }
}
const ws = new WebSocket(targets.find((t) => t.type === 'page').webSocketDebuggerUrl)
await new Promise((r) => ws.addEventListener('open', r))
let id = 0
const pending = new Map()
ws.addEventListener('message', (m) => {
  const msg = JSON.parse(m.data)
  const answered = pending.get(msg.id)
  if (typeof answered === 'function') answered(msg)
  pending.delete(msg.id)
})
const send = (method, params = {}) =>
  new Promise((r) => {
    pending.set(++id, r)
    ws.send(JSON.stringify({ id, method, params }))
  })

await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 720, deviceScaleFactor: 1, mobile: false })
await send('Page.enable')
await send('Emulation.setLocaleOverride', { locale: 'en-US' })
await send('Page.addScriptToEvaluateOnNewDocument', {
  source: "Object.defineProperty(navigator, 'language', { get: () => 'en-US' });" +
    "Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] })",
})
await send('Page.navigate', { url })
await sleep(1500)
const start = Date.now()
let n = 0
while (Date.now() - start < SECONDS * 1000) {
  while (actions.length && actions[0].at <= Date.now() - start) await send('Runtime.evaluate', { expression: actions.shift().js })
  const shot = await send('Page.captureScreenshot', { format: 'jpeg', quality: 90 })
  if (!shot.result) continue
  writeFileSync(join(work, `f${String(n++).padStart(4, '0')}.jpg`), Buffer.from(shot.result.data, 'base64'))
  const next = start + n * EVERY_MS
  if (next > Date.now()) await sleep(next - Date.now())
}
ws.close()
proc.kill()
execFileSync('ffmpeg', [
  '-loglevel', 'error', '-y', '-framerate', String(1000 / EVERY_MS), '-i', join(work, 'f%04d.jpg'), '-vf',
  'fps=6,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];' +
    '[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle',
  '-loop', '0', out,
], { stdio: 'inherit' })
try {
  rmSync(work, { recursive: true, force: true, maxRetries: 5 })
} catch {
  console.warn(`Could not remove ${work}`)
}
console.log(`${n} frames -> ${out}`)
