// The catalogue's "Prepare in my console" (ADR-0041, amendment of 2026-11-02): the plain rules, apart from Vue and
// xterm.js so they can be tested. The only bytes Floor42 ever puts in the director's terminal are built here.

/**
 * A skill or agent name as Claude Code writes them: a letter, then letters, digits, dots, dashes, underscores. Never a
 * digit first: typed into a selector Claude Code is showing, a digit could pick one of its options.
 */
const NAME = /^[A-Za-z][A-Za-z0-9._-]*$/

/**
 * Claude Code's own slash commands (its commands reference and `/help`, Claude Code 2.1): a skill with one of these
 * names would run the built-in command (`/exit`, `/logout`, `/clear`), so it is not prepared.
 */
export const BUILT_IN_COMMANDS: ReadonlySet<string> = new Set([
  'add-dir', 'agents', 'bashes', 'bug', 'clear', 'compact', 'config', 'context', 'cost', 'doctor', 'exit', 'export',
  'extra-usage', 'feedback', 'help', 'hooks', 'ide', 'init', 'install-github-app', 'keybindings', 'login', 'logout',
  'mcp', 'memory', 'migrate-installer', 'model', 'output-style', 'permissions', 'plugin', 'plugins', 'pr-comments',
  'privacy-settings', 'quit', 'release-notes', 'reset', 'resume', 'review', 'rewind', 'sandbox', 'security-review',
  'settings', 'skills', 'stats', 'status', 'statusline', 'tasks', 'terminal-setup', 'theme', 'todos', 'upgrade',
  'usage', 'vim',
])

/** The longest name accepted, for the plugin and for the item each. */
export const NAME_MAX = 64

/** What may be typed: printable ASCII only. No control character, no ESC, no CR or LF, nothing beyond U+007E. */
const TYPABLE = /^[\x20-\x7e]+$/

/** How long the screen must stay quiet, after it printed something, before Claude Code is taken as ready. */
export const QUIET_MS = 800

/** How long to wait for that before giving up (nothing is typed then). */
export const READY_TIMEOUT_MS = 20_000

export interface PrefillItem {
  kind: string
  name: string
  /** The plugin it comes with, when the catalogue read it from one: Claude Code names it `plugin:name`. */
  plugin?: string | null
}

function validName(part: string): boolean {
  return part.length > 0 && part.length <= NAME_MAX && NAME.test(part)
}

/**
 * The name Claude Code knows the item by, or null when the catalogue's name is not one Floor42 will type. A session
 * name may already carry its plugin (`plugin:name`): one colon at most, each side checked.
 */
export function qualifiedName(item: PrefillItem): string | null {
  const parts = item.plugin ? [item.plugin, item.name] : item.name.split(':')
  if (parts.length > 2 || !parts.every(validName)) return null
  return parts.join(':')
}

/** Why an item is not prepared: a name Floor42 will not type, or a skill named like a built-in command; null if it is. */
export function prefillRefusal(item: PrefillItem): 'badName' | 'builtin' | null {
  const name = qualifiedName(item)
  if (!name) return 'badName'
  if (item.kind.toUpperCase() === 'SKILL' && BUILT_IN_COMMANDS.has(name.toLowerCase())) return 'builtin'
  return null
}

/** The command to prepare, never ending with a carriage return: `/name ` for a skill, a sentence to finish for an agent. */
export function prefillFor(item: PrefillItem): string | null {
  if (prefillRefusal(item)) return null
  const name = qualifiedName(item)!
  const kind = item.kind.toUpperCase()
  if (kind === 'SKILL') return `/${name} `
  if (kind === 'AGENT') return `Use the ${name} agent to `
  return null
}

/** The last check before the bytes leave: whatever built the text, nothing but printable ASCII is sent. */
export function safeToType(text: string): boolean {
  return TYPABLE.test(text)
}

/**
 * Whether the director has typed something on Claude Code's line since his last Enter (or Ctrl+C, which clears it).
 * `before` undefined means unknown (a terminal opened before this page): taken as typed, so nothing is clobbered.
 */
export function typedAfter(before: boolean | undefined, data: string): boolean {
  // The terminal's own answers (cursor position, device attributes, focus) and arrow keys put no text on the line.
  const text = data.replace(/\x1b(\[[0-9;?<>=]*[ -/]*[@-~]|O.|\][^\x07\x1b]*(\x07|\x1b\\)|.)/g, '') // NOSONAR S6324: matching the ESC and BEL control characters is the point
  const end = Math.max(text.lastIndexOf('\r'), text.lastIndexOf('\n'), text.lastIndexOf('\x03'))
  if (end < 0) return text.length > 0 || (before ?? true)
  return text.length > end + 1
}

/** Claude Code is taken as ready once the screen is open, has printed, and has been quiet for {@link QUIET_MS}. */
export function isReady(open: boolean, lastOutputAt: number, now: number, quietMs = QUIET_MS): boolean {
  return open && lastOutputAt > 0 && now - lastOutputAt >= quietMs
}

/** Two spellings of one folder: slashes either way, no trailing slash, the case ignored on a Windows drive. */
export function sameProject(a: string | null | undefined, b: string | null | undefined): boolean {
  if (!a || !b) return false
  const norm = (p: string) => {
    const s = p.trim().replaceAll('\\', '/').replace(/\/+$/, '')
    return /^[A-Za-z]:/.test(s) ? s.toLowerCase() : s
  }
  return norm(a) === norm(b)
}

/** The item's own project first, then the terminal on screen, then the last one used, then the panel's default. */
export function prefillProject(...candidates: (string | null | undefined)[]): string | null {
  return candidates.find((c) => !!c && c.trim().length > 0)?.trim() ?? null
}

/*
 * Default deny (the director's choice, 2026-11-02): Floor42 types only on a screen it recognises as Claude Code's empty
 * input box, and nothing on any other screen. The signature, as Claude Code 2.1.316 draws it (read from a real
 * terminal by the director's coordinator, 2026-11-02; the 1.x box is kept):
 *
 *   ──────────────────────────          or   ╭──────────────────────────╮
 *   ❯ Try "how do I log an error?"            │ > (what he typed)        │
 *   ──────────────────────────               ╰──────────────────────────╯
 *     (status line: any lines)                  ? for shortcuts
 *     ⏵⏵ auto mode on (shift+tab to cycle)
 *
 * The box's top and bottom are full rules, its one line is the prompt character then text, and somewhere under it
 * comes one of the footers Claude Code draws (the status line, the director's own, may come first, any length). The
 * dim (or italic) text after the prompt is Claude Code's placeholder suggestion, never the director's text. Below the
 * box nothing may look like a dialog: no cursor or option line (`❯` or `>` at the start), no "Esc to cancel", no
 * "Enter to confirm", no numbered option, no other box. Lines above the box are history.
 */

/** A rule of the 2.x input box, or the top or bottom of the boxed (1.x) one. */
const TOP = /^\s*(─{8,}|╭─{6,}╮)\s*$/
const BOTTOM = /^\s*(─{8,}|╰─{6,}╯)\s*$/

/** The box's line: in a rule-bounded box the prompt alone; in a bordered box the prompt between its two sides. */
const RULED_LINE = /^\s*[>❯] ?(.*?)\s*$/
const BOXED_LINE = /^\s*│ *[>❯] ?(.*?)\s*│\s*$/

/** The footers Claude Code draws under its input box (the empty box's hint and the permission-mode lines). */
const FOOTERS = [
  /\? for shortcuts/,
  /shift\+tab to cycle/,
  /accept edits on/,
  /plan mode on/,
  /auto mode on/,
  /bypass permissions on/,
]

/** What makes a screen a dialog: a cursor or option line, a field's "Esc to cancel", "Enter to confirm", a number. */
const DIALOG = [
  /^\s*(│\s*)*[>❯]\s/,
  /^\s*(│\s*)*[>❯]\s*(│\s*)*$/,
  /esc to cancel/i,
  /enter to confirm/i,
  /^\s*(│\s*)*[>❯]?\s*\d+[.)]\s/,
]

/** Below the input box, any other box is a dialog too. */
const DIALOG_BELOW = [...DIALOG, /[╭╰]/]

/** The mark of a dim (or italic) cell in a line's style mask: Claude Code's placeholder, not the director's text. */
export const FAINT = 'd'

/**
 * Claude Code's empty input box with what the director typed on it (`ready`), a screen that is plainly a dialog
 * (`asking`), or anything else (`none`): only `ready` lets Floor42 type. `faint[i]`, when given, marks each dim or
 * italic character of `lines[i]` with {@link FAINT} at the same index (any other character elsewhere).
 */
export type PromptState = { state: 'ready'; line: string } | { state: 'asking' } | { state: 'none' }

export function promptState(lines: string[], faint: string[] = []): PromptState {
  const kept = lines.map((text, i) => ({ text, faint: faint[i] ?? '' })).filter((l) => l.text.trim().length > 0)
  const shown = kept.map((l) => l.text)
  for (let i = shown.length - 2; i >= 1; i--) {
    if (!TOP.test(shown[i - 1]) || !BOTTOM.test(shown[i + 1])) continue
    const boxed = shown[i - 1].includes('╭')
    if (boxed !== shown[i + 1].includes('╰')) continue
    if (!(boxed ? BOXED_LINE : RULED_LINE).test(shown[i])) continue
    const below = shown.slice(i + 2)
    if (below.some((l) => DIALOG_BELOW.some((r) => r.test(l)))) return { state: 'asking' }
    const footer = below.some((l) => FOOTERS.some((r) => r.test(l)))
    return footer ? { state: 'ready', line: typedOn(kept[i].text, kept[i].faint, boxed) } : { state: 'none' }
  }
  return shown.some((l) => DIALOG.some((r) => r.test(l))) ? { state: 'asking' } : { state: 'none' }
}

/** The director's text on the input line: what follows the prompt, empty when any of it is faint (the placeholder). */
function typedOn(line: string, faint: string, boxed: boolean): string {
  const at = line.search(/[>❯]/)
  const end = boxed ? line.lastIndexOf('│') : line.length
  if (faint.slice(at + 1, end).includes(FAINT)) return ''
  return line.slice(at + 1, end).trim()
}

/** What the panel can tell about its screen at a moment. */
export interface ScreenProbe {
  open: boolean
  lastOutputAt: number
  lines: string[]
  /** Per line, {@link FAINT} where a cell is dim or italic (see {@link promptState}). */
  faint?: string[]
}

/**
 * Waits until Claude Code shows its input line on a screen that printed and went quiet. `asking` as soon as a quiet
 * screen shows a question (nothing is to be typed into it), `closed` when the screen went away, `timeout` otherwise.
 */
export async function waitUntilReady(
  probe: () => ScreenProbe,
  clock: { now(): number; sleep(ms: number): Promise<void> },
  timeoutMs = READY_TIMEOUT_MS,
): Promise<{ state: 'ready'; line: string } | { state: 'asking' | 'closed' | 'timeout' }> {
  const until = clock.now() + timeoutMs
  for (;;) {
    const p = probe()
    if (!p.open) return { state: 'closed' }
    if (isReady(true, p.lastOutputAt, clock.now())) {
      const s = promptState(p.lines, p.faint)
      if (s.state === 'ready') return s
      if (s.state === 'asking') return s
    }
    if (clock.now() > until) return { state: 'timeout' }
    await clock.sleep(100)
  }
}

/**
 * Lets one preparation run at a time; a second click while one runs is refused. The turn has an owner: `take` gives a
 * token, and only that token releases it or still holds it. A turn left for a minute (a panel that never took its
 * request) goes to the next click; the old holder then no longer holds it, and types nothing.
 */
export class OneAtATime {
  private holder = 0
  private since = 0
  private last = 0
  private readonly now: () => number
  private readonly staleMs: number
  constructor(now: () => number = Date.now, staleMs = 60_000) {
    this.now = now
    this.staleMs = staleMs
  }
  take(): number | null {
    if (this.holder && this.now() - this.since < this.staleMs) return null
    this.holder = ++this.last
    this.since = this.now()
    return this.holder
  }
  holds(token: number): boolean {
    return token === this.holder && this.now() - this.since < this.staleMs
  }
  release(token: number): void {
    if (token === this.holder) this.holder = 0
  }
}

export type ReadyAnswer = { state: 'ready'; line: string } | { state: 'asking' | 'closed' | 'timeout' }

/** What to do with a prepared command: type these bytes, hand it over (nothing typed), or nothing (he said no). */
export type PrefillPlan = { type: string } | { handOver: 'asking' | 'closed' | 'timeout' } | { cancelled: true }

/**
 * The steps of one preparation, apart from the screen: wait for the input line; when the line already holds something
 * (typed here, or shown on screen), ask, then wait for the input line again, since the screen may have changed while
 * the director read the question; then the bytes, checked once more.
 */
export async function planPrefill(
  text: string,
  steps: {
    ready(timeoutMs?: number): Promise<ReadyAnswer>
    typed(): boolean
    confirm(): Promise<boolean>
    /** Whether this preparation still holds the turn ({@link OneAtATime}): a newer click may have taken it. */
    stillMine(): boolean
  },
): Promise<PrefillPlan> {
  let screen = await steps.ready()
  if (!steps.stillMine()) return { cancelled: true }
  if (screen.state !== 'ready') return { handOver: screen.state }
  let out = text
  if (steps.typed() || screen.line.length > 0) {
    // The question may stay open longer than the turn: a stale preparation is dropped when it closes.
    if (!(await steps.confirm()) || !steps.stillMine()) return { cancelled: true }
    out = ` ${text}`
    screen = await steps.ready(2_000)
    if (screen.state !== 'ready') return { handOver: screen.state }
  }
  return safeToType(out) && steps.stillMine() ? { type: out } : { cancelled: true }
}
