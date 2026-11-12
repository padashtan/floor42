import { beforeEach, describe, expect, it, vi } from 'vitest'
import { panels } from '../state/openPanels'
import { pendingPrefill, prefillTurn, requestPrefill, takePrefill } from '../state/terminalPrefill'
import {
  FAINT,
  isReady,
  OneAtATime,
  planPrefill,
  prefillFor,
  prefillProject,
  prefillRefusal,
  promptState,
  qualifiedName,
  safeToType,
  sameProject,
  typedAfter,
  waitUntilReady,
  type ReadyAnswer,
  type ScreenProbe,
} from './prefill'

const real = { isTrusted: true }

describe('the command prepared from the catalogue', () => {
  it('is /name and a space for a skill, a sentence to finish for an agent, never with Enter', () => {
    expect(prefillFor({ kind: 'SKILL', name: 'lean-delivery' })).toBe('/lean-delivery ')
    expect(prefillFor({ kind: 'AGENT', name: 'code-reviewer' })).toBe('Use the code-reviewer agent to ')
    expect(prefillFor({ kind: 'SKILL', name: 'review', plugin: 'tools' })).toBe('/tools:review ')
    expect(prefillFor({ kind: 'AGENT', name: 'tools:scout' })).toBe('Use the tools:scout agent to ')
    expect(prefillFor({ kind: 'PLUGIN', name: 'tools' })).toBeNull()
    expect(prefillFor({ kind: 'MCP', name: 'github' })).toBeNull()
  })

  // Each of these, typed into a terminal, would do more than prepare: run it, leave bracketed paste, move the cursor.
  it.each([
    ['carriage return', 'deploy\r'],
    ['line feed', 'deploy\nrm -rf x'],
    ['CR LF', 'a\r\nb'],
    ['ESC sequence', 'a\x1b[2J'],
    ['bracketed paste end', 'a\x1b[201~\rb'],
    ['bracketed paste end, ESC lost', 'a[201~'],
    ['NUL', 'a\x00b'],
    ['Ctrl+C', 'a\x03'],
    ['tab', 'a\tb'],
    ['DEL', 'a\x7f'],
    ['C1 CSI', 'a\x9bb'],
    ['Unicode line separator', 'a\u2028b'],
    ['Unicode paragraph separator', 'a\u2029b'],
    ['next line', 'a\u0085b'],
    ['a space', 'a b'],
    ['a slash', '../x'],
    ['two colons', 'a:b:c'],
    ['empty', ''],
    ['too long', 'a'.repeat(65)],
    // A digit could pick an option of a selector Claude Code is showing.
    ['a digit first', '1'],
    ['a digit first, longer', '2-step'],
  ])('refuses a name with %s', (_, name) => {
    expect(qualifiedName({ kind: 'SKILL', name })).toBeNull()
    expect(prefillFor({ kind: 'SKILL', name })).toBeNull()
    expect(prefillFor({ kind: 'AGENT', name })).toBeNull()
    if (name) expect(prefillFor({ kind: 'SKILL', name: 'ok', plugin: name })).toBeNull()
  })

  it('refuses a skill named like a Claude Code command, with its reason; an agent or a plugin skill may be', () => {
    for (const name of ['clear', 'exit', 'logout', 'quit', 'login', 'config', 'resume', 'compact', 'Exit']) {
      expect(prefillRefusal({ kind: 'SKILL', name })).toBe('builtin')
      expect(prefillFor({ kind: 'SKILL', name })).toBeNull()
    }
    expect(prefillFor({ kind: 'AGENT', name: 'review' })).toBe('Use the review agent to ')
    expect(prefillFor({ kind: 'SKILL', name: 'clear', plugin: 'tools' })).toBe('/tools:clear ')
    expect(prefillRefusal({ kind: 'SKILL', name: 'x\r' })).toBe('badName')
    expect(prefillRefusal({ kind: 'SKILL', name: 'lean-delivery' })).toBeNull()
  })

  it('accepts a name up to the cap', () => {
    expect(qualifiedName({ kind: 'SKILL', name: 'a'.repeat(64) })).toBe('a'.repeat(64))
  })

  it('checks the bytes once more before they leave: printable ASCII only', () => {
    expect(safeToType('/lean-delivery ')).toBe(true)
    for (const bad of ['/x\r', '/x\n', '\x1b[201~', '/x\u2028', '/x\x03', '/é', '']) expect(safeToType(bad)).toBe(false)
  })
})

describe('the request from the catalogue', () => {
  beforeEach(() => {
    const left = takePrefill()
    if (left) prefillTurn.release(left.turn)
    panels.terminal = false
  })

  it('opens the terminal panel with the command, on the director’s click', () => {
    expect(requestPrefill({ kind: 'SKILL', name: 'lean-delivery' }, 'C:/work/app', real)).toBe('ok')
    expect(panels.terminal).toBe(true)
    const r = takePrefill()!
    expect(r).toMatchObject({ text: '/lean-delivery ', project: 'C:/work/app' })
    expect(pendingPrefill.value).toBeNull()
    prefillTurn.release(r.turn)
  })

  it('refuses an event a script dispatched', () => {
    expect(requestPrefill({ kind: 'SKILL', name: 'lean-delivery' }, null, { isTrusted: false })).toBe('untrusted')
    expect(pendingPrefill.value).toBeNull()
    expect(panels.terminal).toBe(false)
  })

  it('refuses a name with a carriage return, and opens nothing', () => {
    expect(requestPrefill({ kind: 'SKILL', name: 'x\r' }, null, real)).toBe('badName')
    expect(pendingPrefill.value).toBeNull()
    expect(panels.terminal).toBe(false)
  })

  it('refuses a second click while the first is being prepared, until it is done', () => {
    expect(requestPrefill({ kind: 'SKILL', name: 'a' }, null, real)).toBe('ok')
    expect(requestPrefill({ kind: 'SKILL', name: 'b' }, null, real)).toBe('busy')
    const a = takePrefill()!
    expect(a).toMatchObject({ text: '/a ', project: null })
    prefillTurn.release(a.turn)
    expect(requestPrefill({ kind: 'SKILL', name: 'b' }, null, real)).toBe('ok')
  })
})

describe('the turn', () => {
  it('has an owner: A holds, B is refused, A’s late release does not free B', () => {
    let now = 1_000
    const turn = new OneAtATime(() => now)
    const a = turn.take()!
    expect(a).not.toBeNull()
    expect(turn.take()).toBeNull()
    // A's panel never gave it back: after a minute, B takes it.
    now += 61_000
    const b = turn.take()!
    expect(b).not.toBeNull()
    expect(turn.holds(a)).toBe(false)
    turn.release(a)
    expect(turn.holds(b)).toBe(true)
    expect(turn.take()).toBeNull()
    turn.release(b)
    expect(turn.take()).not.toBeNull()
  })

  it('is not taken over before the minute', () => {
    let now = 1_000
    const turn = new OneAtATime(() => now)
    const a = turn.take()!
    now += 59_000
    expect(turn.take()).toBeNull()
    expect(turn.holds(a)).toBe(true)
  })
})

describe('the line the director typed on', () => {
  it('is empty after Enter or Ctrl+C, typed after anything else, unknown taken as typed', () => {
    expect(typedAfter(false, 'a')).toBe(true)
    expect(typedAfter(true, '\r')).toBe(false)
    expect(typedAfter(true, 'abc\rde')).toBe(true)
    expect(typedAfter(true, '\x03')).toBe(false)
    expect(typedAfter(false, '')).toBe(false)
    expect(typedAfter(undefined, '')).toBe(true)
  })

  it('is not changed by the terminal’s own answers or arrow keys', () => {
    // xterm.js answers ConPTY's cursor position query through the same channel as keystrokes.
    expect(typedAfter(false, '\x1b[1;1R')).toBe(false)
    expect(typedAfter(false, '\x1b[?1;2c')).toBe(false)
    expect(typedAfter(false, '\x1b[I\x1b[O')).toBe(false)
    expect(typedAfter(false, '\x1bOA')).toBe(false)
    expect(typedAfter(false, '\x1b]11;rgb:0b0b/0f0f/1414\x1b\\')).toBe(false)
    expect(typedAfter(false, '\x1b[1;1Rx')).toBe(true)
    expect(typedAfter(true, '\x1b[A')).toBe(true)
  })
})

describe('when Claude Code is ready', () => {
  it('needs the screen open, some output, then quiet', () => {
    expect(isReady(true, 0, 10_000)).toBe(false)
    expect(isReady(false, 1_000, 10_000)).toBe(false)
    expect(isReady(true, 9_500, 10_000)).toBe(false)
    expect(isReady(true, 9_000, 10_000)).toBe(true)
  })
})

describe('the project', () => {
  it('is the item’s, else the one on screen, else the last one, else the panel’s default', () => {
    expect(prefillProject('C:/a', 'C:/b', 'C:/c', 'C:/d')).toBe('C:/a')
    expect(prefillProject(null, 'C:/b', 'C:/c', 'C:/d')).toBe('C:/b')
    expect(prefillProject(null, undefined, 'C:/c', 'C:/d')).toBe('C:/c')
    expect(prefillProject(null, null, null, ' ')).toBeNull()
  })

  it('matches two spellings of one folder', () => {
    expect(sameProject('C:\\Work\\App\\', 'c:/work/app')).toBe(true)
    expect(sameProject('/home/a', '/home/A')).toBe(false)
    expect(sameProject(null, 'x')).toBe(false)
  })
})

// Claude Code's screens as it draws them (2.x): its empty input box, typed on, the boxed 1.x form; then the screens
// that must get nothing: the trust prompt, a permission prompt, the MCP server selector, a text field.
const RULE = '─'.repeat(60)
const INPUT = ['╭───────────────────────────╮', '│ ✻ Welcome to Claude Code! │', '╰───────────────────────────╯', '', RULE, '> ', RULE, '  ? for shortcuts']
const TYPED = ['> earlier question', '● An answer.', RULE, '> fix the build', RULE, '  ? for shortcuts']
const BOXED = ['╭' + '─'.repeat(40) + '╮', '│ > /review                                │', '╰' + '─'.repeat(40) + '╯', '  ? for shortcuts']
const MODE = [RULE, '> ', RULE, '  ⏵⏵ accept edits on (shift+tab to cycle)']
const TRUST = [
  '╭' + '─'.repeat(50) + '╮',
  '│ Do you trust the files in this folder?            │',
  '│                                                   │',
  '│ ❯ 1. Yes, proceed                                 │',
  '│   2. No, exit                                     │',
  '╰' + '─'.repeat(50) + '╯',
  '   Enter to confirm · Esc to exit',
]
const PERMISSION = [
  '> run the tests',
  '╭' + '─'.repeat(50) + '╮',
  '│ Bash command                                      │',
  '│   npm test                                        │',
  '│ Do you want to proceed?                           │',
  '│ ❯ 1. Yes                                          │',
  "│   2. Yes, and don't ask again for npm test        │",
  '│   3. No, and tell Claude what to do differently   │',
  '╰' + '─'.repeat(50) + '╯',
]
const MCP_SELECTOR = [
  '╭' + '─'.repeat(60) + '╮',
  '│ New MCP server found in .mcp.json: github                    │',
  '│                                                              │',
  '│ ❯ Use this and all future MCP servers in this project        │',
  '│   Use this MCP server                                        │',
  '│   Continue without using this MCP server                     │',
  '╰' + '─'.repeat(60) + '╯',
  '   Enter to confirm · Esc to reject',
]
const TEXT_FIELD = ['╭' + '─'.repeat(30) + '╮', '│ >                            │', '╰' + '─'.repeat(30) + '╯', '  Esc to cancel']
// The input box drawn, and a selector shown under it.
const BOX_THEN_SELECTOR = [RULE, '> ', RULE, '  ? for shortcuts', '  ❯ Use this and all future MCP servers']

// Claude Code 2.1.316 as it was read from a real terminal (2026-11-02): the placeholder after ❯ is dim.
const REAL_RULE = '─'.repeat(120)
const REAL_PROMPT = '❯ Try "how do I log an error?"'
const REAL = [
  ' ▐▛███▛█   Claude Code v2.1.316',
  '▝▜██████▀  Opus 5.5 · Claude Pro',
  ' ▝▝   ▝▝   C:\\f42demo\\d-ARLbrP\\p-0B8zFV',
  '⚠ 4 MCP servers need authentication · run /mcp',
  '                                                                    ◐ medium · /effort',
  REAL_RULE,
  REAL_PROMPT,
  REAL_RULE,
  '  ⚠ Transcript saving is off · restart with a clean environment',
  '  [Opus 5.5] │ p-0B8zFV git:(main)',
  '  Context ░░░░░░░░░░ 0%',
  '  ⏵⏵ auto mode on (shift+tab to cycle)',
]
/** The style mask of REAL: the placeholder (everything after "❯ ") is dim. */
const REAL_FAINT = REAL.map((l) => (l === REAL_PROMPT ? '  ' + FAINT.repeat(l.length - 2) : ''))
const REAL_TRUST = [
  ' Accessing workspace:',
  ' C:\\f42demo\\d-ARLbrP\\p-0B8zFV',
  ' Quick safety check: Is this a project you created or one you trust?',
  ' ❯ No, exit',
  '   Yes, I trust this folder',
  ' Enter to confirm · Esc to cancel',
]

describe('the real Claude Code 2.1.316', () => {
  it('is ready on its input screen: the dim placeholder is no text of the director’s, the footer far below counts', () => {
    expect(promptState(REAL, REAL_FAINT)).toEqual({ state: 'ready', line: '' })
  })

  it('reads text that is not dim as the director’s', () => {
    expect(promptState(REAL)).toEqual({ state: 'ready', line: 'Try "how do I log an error?"' })
  })

  it('is not ready on its folder trust screen', () => {
    expect(promptState(REAL_TRUST).state).toBe('asking')
    expect(promptState(REAL_TRUST.slice(0, 5)).state).toBe('asking')
  })

  it('is not ready when an option line or "Enter to confirm" shows under the box', () => {
    expect(promptState([...REAL, '  Enter to confirm'], REAL_FAINT).state).toBe('asking')
    expect(promptState([...REAL, '  ❯ Yes'], REAL_FAINT).state).toBe('asking')
  })
})

describe('what the screen shows', () => {
  it('types only on Claude Code’s input box with its footer, and reads what is on it', () => {
    expect(promptState(INPUT)).toEqual({ state: 'ready', line: '' })
    expect(promptState(TYPED)).toEqual({ state: 'ready', line: 'fix the build' })
    expect(promptState(BOXED)).toEqual({ state: 'ready', line: '/review' })
    expect(promptState(MODE)).toEqual({ state: 'ready', line: '' })
  })

  it.each([
    ['the trust prompt', TRUST],
    ['a permission prompt', PERMISSION],
    ['the MCP server selector', MCP_SELECTOR],
    ['a text field', TEXT_FIELD],
    ['a selector under the input box', BOX_THEN_SELECTOR],
  ])('types nothing on %s: a dialog', (_, screen) => {
    expect(promptState(screen).state).toBe('asking')
  })

  it.each([
    ['a prompt-like line without the box', ['> ', '  ? for shortcuts']],
    ['the box without its footer', [RULE, '> ', RULE]],
    ['the box with an unknown footer', [RULE, '> ', RULE, '  something else']],
    ['a box of two lines', [RULE, '> a', '  b', RULE, '  ? for shortcuts']],
    ['a fake program', ['FAKE-TTY ready', '$ ']],
    ['nothing', []],
  ])('types nothing on an unrecognised screen: %s', (_, screen) => {
    expect(promptState(screen).state).not.toBe('ready')
  })

  it('reads what is above the input box as history: a numbered list of an answer is no question', () => {
    const answer = ['Options:', '1. Keep the old one', '❯ quoted', '╭─x─╮', RULE, '> ', RULE, '  ? for shortcuts']
    expect(promptState(answer)).toEqual({ state: 'ready', line: '' })
  })
})

describe('waiting for Claude Code', () => {
  function clock() {
    let now = 10_000
    return { now: () => now, sleep: async (ms: number) => void (now += ms) }
  }

  it('waits for output, then quiet, then the input line', async () => {
    const c = clock()
    const screen: ScreenProbe = { open: true, lastOutputAt: 0, lines: [] }
    let calls = 0
    const probe = () => {
      calls++
      if (calls === 3) Object.assign(screen, { lastOutputAt: c.now(), lines: ['booting'] })
      if (calls === 20) Object.assign(screen, { lastOutputAt: c.now(), lines: INPUT })
      return screen
    }
    expect(await waitUntilReady(probe, c)).toEqual({ state: 'ready', line: '' })
    // Not before the input line showed and the screen stayed quiet after it.
    expect(calls).toBeGreaterThan(20)
  })

  it('never answers ready on a quiet question: the trust screen stays static', async () => {
    const c = clock()
    expect(await waitUntilReady(() => ({ open: true, lastOutputAt: 5_000, lines: TRUST }), c)).toEqual({ state: 'asking' })
  })

  it('gives up on a quiet screen without the input line, and when the screen closes', async () => {
    const c = clock()
    expect(await waitUntilReady(() => ({ open: true, lastOutputAt: 5_000, lines: ['$ '] }), c, 2_000)).toEqual({ state: 'timeout' })
    expect(await waitUntilReady(() => ({ open: false, lastOutputAt: 5_000, lines: INPUT }), c)).toEqual({ state: 'closed' })
  })

  it('types on an empty input line without asking', async () => {
    const confirm = vi.fn(async () => true)
    const plan = await planPrefill('/a ', { ready: async () => ({ state: 'ready', line: '' }), typed: () => false, confirm, stillMine: () => true })
    expect(plan).toEqual({ type: '/a ' })
    expect(confirm).not.toHaveBeenCalled()
  })

  it('asks when the screen’s input line holds text, even if nothing was typed from this page', async () => {
    const confirm = vi.fn(async () => true)
    const plan = await planPrefill('/a ', { ready: async () => ({ state: 'ready', line: 'fix' }), typed: () => false, confirm, stillMine: () => true })
    expect(confirm).toHaveBeenCalledOnce()
    expect(plan).toEqual({ type: ' /a ' })
  })

  it('checks the screen again after the director answered: a dialog that came meanwhile gets nothing', async () => {
    const answers: ReadyAnswer[] = [{ state: 'ready', line: '' }, { state: 'asking' }]
    const plan = await planPrefill('/a ', { ready: async () => answers.shift()!, typed: () => true, confirm: async () => true, stillMine: () => true })
    expect(plan).toEqual({ handOver: 'asking' })
  })

  it('types nothing when he says no, or when the screen asks first', async () => {
    expect(await planPrefill('/a ', { ready: async () => ({ state: 'ready', line: 'x' }), typed: () => false, confirm: async () => false, stillMine: () => true })).toEqual({ cancelled: true })
    expect(await planPrefill('/a ', { ready: async () => ({ state: 'asking' }), typed: () => false, confirm: async () => true, stillMine: () => true })).toEqual({ handOver: 'asking' })
    expect(await planPrefill('/a\r', { ready: async () => ({ state: 'ready', line: '' }), typed: () => false, confirm: async () => true, stillMine: () => true })).toEqual({ cancelled: true })
  })

  it('drops a preparation whose turn a newer click took while the question was open', async () => {
    let mine = true
    const confirm = async () => {
      mine = false
      return true
    }
    const plan = await planPrefill('/a ', { ready: async () => ({ state: 'ready', line: 'x' }), typed: () => true, confirm, stillMine: () => mine })
    expect(plan).toEqual({ cancelled: true })
  })

  it('does not answer while the screen still prints', async () => {
    const c = clock()
    const r = await waitUntilReady(() => ({ open: true, lastOutputAt: c.now(), lines: INPUT }), c, 2_000)
    expect(r).toEqual({ state: 'timeout' })
  })
})
