import tapeUrl from '../../demo/tape.json?url'
import { t, dateLocale } from '../i18n'
import { setClock } from '../ui/clock'

// The online demo (ADR-0031): the real UI, with fetch and the event WebSocket answered from a tape recorded on a fresh
// daemon playing the simulated demo mission. Installed only in the replay build (VITE_DEMO_TAPE); the normal build never
// contains it.

interface Answer {
  status: number
  body: unknown
}

type Step =
  | { t: number; kind: 'answers'; answers: Record<string, Answer> }
  | { t: number; kind: 'event'; event: { type: string } }
  | { t: number; kind: 'wait'; method: string; path: string; answer: string }
  | { t: number; kind: 'response'; path: string; body: unknown }

export interface Tape {
  format: number
  recordedAt: string
  paceMs: number
  steps: Step[]
}

/** Replays a tape: the clock runs from the page's load, and stops at a recorded wait until the visitor acts. */
export class Replay {
  private readonly answers = new Map<string, Answer>()
  private readonly listeners = new Set<(data: string) => void>()
  private next = 0
  /** Tape time already played, and the real time it was reached at. */
  private played = 0
  private since: number
  private waiting: Extract<Step, { kind: 'wait' }> | null = null
  private tape: Tape
  private readonly clock: () => number

  /** @param clock milliseconds, monotonic: the page's clock in a browser, a fake one in tests */
  constructor(tape: Tape, clock: () => number = () => performance.now()) {
    this.tape = tape
    this.clock = clock
    this.since = clock()
    // The page's first reads come before the tape's first step (recorded a moment after the load): they get its answers.
    const first = tape.steps[0]
    if (first?.kind === 'answers') this.tape = { ...tape, steps: [{ ...first, t: 0 }, ...tape.steps.slice(1)] }
    this.advance()
  }

  /** Plays every step due by now: answers become readable, events reach the open sockets, a wait stops the clock. */
  advance(): void {
    if (this.waiting) return
    const at = this.played + (this.clock() - this.since)
    while (this.next < this.tape.steps.length && this.tape.steps[this.next].t <= at) {
      const step = this.tape.steps[this.next++]
      if (step.kind === 'answers') for (const [path, a] of Object.entries(step.answers)) this.answers.set(path, a)
      else if (step.kind === 'event') for (const l of this.listeners) l(JSON.stringify(step.event))
      else if (step.kind === 'wait') {
        this.waiting = step
        this.played = step.t
        return
      }
    }
  }

  /** The recording own wall clock at this point of the tape: what every age on screen is measured against. */
  tapeNow(): number {
    this.advance()
    const at = this.waiting ? this.played : this.played + (this.clock() - this.since)
    return Date.parse(this.tape.recordedAt) + at
  }

  listen(listener: (data: string) => void): void {
    this.listeners.add(listener)
  }

  unlisten(listener: (data: string) => void): void {
    this.listeners.delete(listener)
  }

  /** A GET answers what the tape held for that path at this point; a path it never held answers 404. */
  read(path: string): Response {
    const a = this.answers.get(path) ?? this.answers.get(path.split('?')[0])
    if (!a) return problem(404, 'NOT_RECORDED', t('demo.notRecorded'))
    return new Response(JSON.stringify(a.body), { status: a.status, headers: { 'Content-Type': 'application/json' } })
  }

  /** The visitor's change: only the one the tape waits for goes through, with the recorded answer; the tape goes on. */
  write(method: string, path: string, body: string | null): Response {
    const w = this.waiting
    if (w && w.method === method && w.path === path) {
      const answer = (JSON.parse(body ?? '{}') as { answer?: string }).answer
      if (answer !== w.answer) return problem(409, 'DEMO_RECORDED_ANSWER', t('demo.recordedAnswer', { answer: w.answer }))
      const response = this.tape.steps.find((s) => s.kind === 'response' && s.path === path && s.t >= w.t)
      this.waiting = null
      this.since = this.clock()
      return new Response(JSON.stringify(response && 'body' in response ? response.body : null), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    return problem(403, 'DEMO_READ_ONLY', t('demo.readOnly'))
  }

  /** Answers fetch and the event WebSocket from the tape, from now on, in this page. */
  install(): void {
    setClock(() => this.tapeNow())
    window.setInterval(() => this.advance(), 100)
    const real = window.fetch.bind(window)
    window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, location.href)
      const base = import.meta.env.BASE_URL.replace(/\/$/, '')
      const path = url.pathname.startsWith(base + '/api/') ? url.pathname.slice(base.length) : url.pathname
      if (!path.startsWith('/api/')) return real(input, init)
      const method = (init?.method ?? 'GET').toUpperCase()
      return method === 'GET' ? this.read(path + url.search) : this.write(method, path, (init?.body as string) ?? null)
    }
    const listen = (l: (data: string) => void) => this.listen(l)
    const unlisten = (l: (data: string) => void) => this.unlisten(l)
    // The event stream comes from the tape; the API console's stream stays silent.
    class TapeSocket {
      onopen: (() => void) | null = null
      onmessage: ((m: { data: string }) => void) | null = null
      onclose: (() => void) | null = null
      private readonly listener = (data: string) => this.onmessage?.({ data })

      constructor(url: string) {
        if (url.endsWith('/ws/events')) listen(this.listener)
        window.setTimeout(() => this.onopen?.(), 0)
      }

      close(): void {
        unlisten(this.listener)
      }
    }
    window.WebSocket = TapeSocket as unknown as typeof WebSocket
    this.ribbon()
  }

  /** Says on every screen what this is: a recording of a simulated mission. */
  private ribbon(): void {
    const r = document.createElement('div')
    r.className = 'demo-ribbon'
    r.textContent = t('demo.ribbon', { date: new Date(this.tape.recordedAt).toLocaleDateString(dateLocale()) })
    document.body.appendChild(r)
  }
}

function problem(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({ code, message }), { status, headers: { 'Content-Type': 'application/json' } })
}

/** Loads the tape (an asset of the replay build only) and installs the replay before the app starts. */
export async function installReplay(): Promise<void> {
  const response = await fetch(tapeUrl)
  new Replay((await response.json()) as Tape).install()
}
