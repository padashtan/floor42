// Records the simulated demo mission played by a fresh daemon, as a tape the online demo replays (ADR-0031).
//
//   FLOOR42_TOKEN_FILE=path/to/session.token node tools/demo/record.mjs [out.json]
//
// Use a daemon on an empty data directory (a new Docker volume): everything it answers is written to the tape, which is
// published. The token is read from FLOOR42_TOKEN_FILE (or FLOOR42_TOKEN) and never printed. The default output is
// floor42-web/demo/tape.json.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

const BASE = process.env.FLOOR42_URL ?? 'http://127.0.0.1:7420'
const OUT = process.argv[2] ?? 'floor42-web/demo/tape.json'
const PACE_MS = 1500
/** The tape ends once nothing has happened for this long after the demo's last question. */
const QUIET_MS = 8000
const token = (process.env.FLOOR42_TOKEN ?? readFileSync(process.env.FLOOR42_TOKEN_FILE, 'utf8')).trim()
const auth = { Authorization: `Bearer ${token}` }

const start = Date.now()
const now = () => Date.now() - start
const steps = []
/** The last answer recorded for each path: a snapshot stores only what changed. */
const last = new Map()

async function get(path) {
  const r = await fetch(BASE + path, { headers: auth })
  return { status: r.status, body: r.status === 204 ? null : await r.json().catch(() => null) }
}

async function send(method, path, body) {
  const r = await fetch(BASE + path, {
    method,
    headers: { ...auth, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await r.text()
  if (!r.ok) throw new Error(`${method} ${path}: ${r.status} ${text}`)
  return text ? JSON.parse(text) : null
}

/** Every GET the UI may make, with the arguments it uses (floor42-web/src/api/client.ts). */
async function paths() {
  const fixed = [
    '/api/status', '/api/agents', '/api/missions', '/api/notes', '/api/projects', '/api/lessons', '/api/settings',
    // Not /api/calls: the API console would only show this recorder's own requests.
    '/api/usage', '/api/events?limit=40', '/api/questions', '/api/permissions/defaults',
    '/api/committee/roles',
  ]
  const missions = (await get('/api/missions')).body ?? []
  const agents = (await get('/api/agents')).body ?? []
  const perMission = missions.flatMap(({ id }) => {
    const m = `/api/missions/${encodeURIComponent(id)}`
    return [m, `${m}/archive`, `${m}/held`, `${m}/retro/wait`, `${m}/tools`, `${m}/workshop`]
  })
  const perAgent = agents.map(({ id }) => `/api/agents/${encodeURIComponent(id)}/profile`)
  return [...fixed, ...perMission, ...perAgent]
}

let snapshotting = Promise.resolve()
function snapshot() {
  snapshotting = snapshotting.then(async () => {
    const answers = {}
    for (const p of await paths()) {
      const a = await get(p)
      const json = JSON.stringify(a)
      if (last.get(p) !== json) {
        last.set(p, json)
        answers[p] = a
      }
    }
    if (Object.keys(answers).length) steps.push({ t: now(), kind: 'answers', answers })
  })
  return snapshotting
}

let lastEvent = Date.now()
const answered = new Set()
const seen = new Set()
/**
 * New events, oldest first. Polled rather than read from /ws/events: Node's WebSocket cannot send the bearer header,
 * and the replay only needs each event once, in order.
 */
async function poll() {
  const recent = ((await get('/api/events?limit=200')).body ?? []).filter((e) => !seen.has(e.eventId)).reverse()
  for (const e of recent) {
    seen.add(e.eventId)
    lastEvent = Date.now()
    // The answers first, then the event: the UI reloads what the event changed and must find it on the tape.
    await snapshot()
    steps.push({ t: now(), kind: 'event', event: e })
    if (e.type === 'QuestionAsked') await approve()
  }
}

/** Answers the demo's approval request as a visitor would, after a pause; the replay waits for the visitor there. */
async function approve() {
  const open = ((await get('/api/questions')).body ?? []).filter(
    (q) => q.kind === 'ACTION_APPROVAL' && q.status === 'OPEN' && !answered.has(q.id),
  )
  for (const q of open) {
    answered.add(q.id)
    const answer = q.recommendation ?? q.options[0]
    const path = `/api/questions/${encodeURIComponent(q.id)}/answer`
    await new Promise((r) => setTimeout(r, 3000))
    steps.push({ t: now(), kind: 'wait', method: 'POST', path, answer })
    const response = await send('POST', path, { answer })
    steps.push({ t: now(), kind: 'response', path, body: response })
  }
}

for (const e of (await get('/api/events?limit=200')).body ?? []) seen.add(e.eventId)
await snapshot()
await send('POST', `/api/demo/live?paceMs=${PACE_MS}`)
while (Date.now() - lastEvent < QUIET_MS || steps.length < 5) {
  await poll()
  await new Promise((r) => setTimeout(r, 250))
}
await snapshot()

const tape = { format: 1, recordedAt: new Date(start).toISOString(), paceMs: PACE_MS, steps }
mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(OUT, JSON.stringify(tape))
console.log(`${OUT}: ${steps.length} steps, ${steps.filter((s) => s.kind === 'event').length} events, ${Math.round(now() / 1000)} s`)
process.exit(0)
