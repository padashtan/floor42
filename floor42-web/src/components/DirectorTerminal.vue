<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { api, ApiError } from '../api/client'
import type { TerminalView } from '../api/types'
import { t } from '../i18n'
import { confirmed } from '../state/confirm'
import { lastTerminalProject, pendingPrefill, prefillTurn, takePrefill, typedLine, type PrefillRequest } from '../state/terminalPrefill'
import { FAINT, planPrefill, prefillProject, sameProject, typedAfter, waitUntilReady } from '../ui/prefill'
import { why } from '../ui/why'
import { clipboardKey, costText, inputMessage, parseMessage, projectChoices, resizeMessage, terminalUrl } from '../ui/terminal'

/**
 * The director's own Claude Code in a terminal (ADR-0041). Not an agent and not governed by Floor42: it runs with the
 * director's rights and settings; Floor42 starts it on this panel's button only and types nothing into it but what the
 * director types here. The cost shown is what Claude Code reports (its own estimate). Closing the panel leaves the
 * terminal running (attach again from here); "Close the terminal" stops it with every process it started.
 */
const props = withDefaults(defineProps<{ project?: string | null }>(), { project: null })
const emit = defineEmits<{ close: [] }>()

const choices = ref<string[]>([])
const chosen = ref(props.project ?? '')
const open = ref<TerminalView[]>([])
const available = ref(true)
const reason = ref<string | null>(null)
const error = ref('')
const busy = ref(false)
const current = ref<TerminalView | null>(null)
const ended = ref(false)
/** The socket closed without the director asking: the screen is dead until reattached. */
const lost = ref(false)
const screen = ref<HTMLDivElement | null>(null)

let term: Terminal | null = null
let fit: FitAddon | null = null
let socket: WebSocket | null = null
let observer: ResizeObserver | null = null
/** When the screen last printed something (0: not yet): Claude Code is taken as ready once it has been quiet a while. */
let lastOutputAt = 0
/** Whether the text in `error` is a load failure: a later successful load clears that one only, never a hint or another failure (D26). */
let loadFailed = false

const cost = computed(() => costText(current.value))

function message(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

async function load() {
  try {
    const [view, projects] = await Promise.all([api.terminals(), api.projects().catch(() => [])])
    available.value = view.available
    reason.value = view.reason
    open.value = view.terminals
    choices.value = projectChoices(props.project, projects)
    if (!chosen.value && choices.value.length) chosen.value = choices.value[0]
    if (loadFailed) error.value = ''
    loadFailed = false
  } catch (e) {
    error.value = message(e)
    loadFailed = true
  }
}

function send(text: string) {
  if (!socket) return
  if (socket.readyState === WebSocket.OPEN) socket.send(text)
  // Closing or closed: said, never a silent drop of what the director types.
  else if (socket.readyState > WebSocket.OPEN && !ended.value) lost.value = true
}

async function copySelection() {
  const s = term?.getSelection() ?? ''
  if (s) await navigator.clipboard.writeText(s).catch(() => undefined)
}

async function paste() {
  const s = await navigator.clipboard.readText().catch(() => '')
  if (s && term) term.paste(s)
  term?.focus()
}

function fitNow() {
  if (!term || !fit) return
  try {
    fit.fit()
  } catch {
    // not laid out yet
  }
}

async function mount(id: string, ticket: string) {
  disconnect()
  ended.value = false
  lost.value = false
  lastOutputAt = 0
  if (current.value) lastTerminalProject.value = current.value.project
  await nextTick()
  if (!screen.value) return
  term = new Terminal({
    fontFamily: "'Cascadia Mono', Consolas, 'DejaVu Sans Mono', monospace",
    fontSize: 14,
    cursorBlink: true,
    scrollback: 5000,
    allowProposedApi: false,
    theme: { background: '#0b0f14' },
  })
  fit = new FitAddon()
  term.loadAddon(fit)
  term.open(screen.value)
  fitNow()
  term.attachCustomKeyEventHandler((e) => {
    const action = clipboardKey(e)
    if (action === 'copy') void copySelection()
    if (action === 'paste') void paste()
    return action === null
  })
  term.onData((data) => {
    typedLine.set(id, typedAfter(typedLine.get(id), data))
    send(inputMessage(data))
  })
  term.onResize(({ cols, rows }) => send(resizeMessage(cols, rows)))
  observer = new ResizeObserver(() => fitNow())
  observer.observe(screen.value)

  const ws = new WebSocket(terminalUrl(location, id, ticket))
  socket = ws
  socket.binaryType = 'arraybuffer'
  socket.onopen = () => {
    send(resizeMessage(term?.cols ?? 100, term?.rows ?? 30))
    term?.focus()
  }
  socket.onmessage = (ev) => {
    if (typeof ev.data === 'string') {
      const m = parseMessage(ev.data)
      if (!m) return
      current.value = m.terminal
      if (m.type === 'ended') {
        ended.value = true
        void load()
      }
    } else {
      lastOutputAt = Date.now()
      term?.write(new Uint8Array(ev.data as ArrayBuffer))
    }
  }
  socket.onclose = (ev) => {
    if (ev.code === 4403) error.value = t('terminal.refused')
    else if (socket === ws && !ended.value) lost.value = true
  }
}

async function start() {
  if (!chosen.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    const size = { columns: term?.cols ?? 120, rows: term?.rows ?? 32 }
    const access = await api.openTerminal(chosen.value.trim(), size.columns, size.rows)
    // A terminal started here: its line is empty.
    typedLine.set(access.terminal.id, false)
    current.value = access.terminal
    await mount(access.terminal.id, access.ticket)
    void load()
  } catch (e) {
    error.value = message(e)
  } finally {
    busy.value = false
  }
}

async function attach(view: TerminalView) {
  try {
    const access = await api.attachTerminal(view.id)
    current.value = access.terminal
    await mount(access.terminal.id, access.ticket)
  } catch (e) {
    error.value = message(e)
  }
}

async function reattach() {
  if (current.value) await attach(current.value)
}

async function stop(view: TerminalView | null) {
  if (!view) return
  if (!(await confirmed({ message: t('terminal.stopConfirm'), confirmLabel: t('terminal.stop'), danger: true }))) return
  try {
    await api.closeTerminal(view.id)
  } catch (e) {
    error.value = message(e)
  }
  void load()
}

function disconnect() {
  observer?.disconnect()
  observer = null
  socket?.close()
  socket = null
  term?.dispose()
  term = null
  fit = null
}

function detach() {
  disconnect()
  current.value = null
  ended.value = false
  lost.value = false
  void load()
}

/** The lines on screen now, as text, and beside each its mask of dim or italic characters (Claude Code's placeholder). */
function visibleScreen(): { lines: string[]; faint: string[] } {
  const lines: string[] = []
  const faint: string[] = []
  if (!term) return { lines, faint }
  const b = term.buffer.active
  const cell = b.getNullCell()
  for (let y = b.viewportY; y < b.viewportY + term.rows; y++) {
    const line = b.getLine(y)
    let text = ''
    let mask = ''
    for (let x = 0; line && x < term.cols; x++) {
      line.getCell(x, cell)
      // The second half of a wide character has no text of its own.
      const chars = cell.getChars() || (cell.getWidth() === 0 ? '' : ' ')
      text += chars
      mask += (cell.isDim() || cell.isItalic() ? FAINT : ' ').repeat(chars.length)
    }
    const end = text.trimEnd().length
    lines.push(text.slice(0, end))
    faint.push(mask.slice(0, end))
  }
  return { lines, faint }
}

/** Waits for Claude Code's input line on this screen (ui/prefill.ts, waitUntilReady). */
function ready(timeoutMs?: number) {
  return waitUntilReady(
    // Still connecting counts as open: the screen has just been attached.
    () => ({ open: !!socket && socket.readyState <= WebSocket.OPEN && !ended.value, lastOutputAt, ...visibleScreen() }),
    { now: Date.now, sleep: (ms) => new Promise((r) => window.setTimeout(r, ms)) },
    timeoutMs,
  )
}

/** Nothing typed: the command goes to the clipboard when the browser lets it, and the director is told which. */
async function handOver(text: string, state: 'asking' | 'closed' | 'timeout') {
  const command = text.trim()
  if (state === 'closed') {
    error.value = t('prefill.notReady')
    return
  }
  const copied = await navigator.clipboard.writeText(command).then(() => true, () => false)
  // A screen Floor42 does not recognise as Claude Code's empty input box gets nothing typed either (default deny).
  if (state === 'timeout') error.value = t(copied ? 'prefill.noPromptCopied' : 'prefill.noPrompt', { command })
  else error.value = t(copied ? 'prefill.askingCopied' : 'prefill.asking', { command })
}

/** Puts the screen on the project's terminal: the one shown, else an open one, else a new one (on the director's click). */
async function reach(project: string): Promise<boolean> {
  if (current.value && sameProject(current.value.project, project) && socket && !ended.value) return true
  await load()
  const running = open.value.find((v) => v.running && sameProject(v.project, project))
  if (running) await attach(running)
  else {
    chosen.value = project
    await start()
  }
  return !!current.value && sameProject(current.value.project, project)
}

/**
 * The catalogue's request (ADR-0041, amendment of 2026-11-02): types the prepared command, never Enter. When the
 * director already typed on the line, he is asked first, and the command goes after a space.
 */
async function prefill(r: PrefillRequest) {
  try {
    await prefillNow(r)
  } finally {
    prefillTurn.release(r.turn)
  }
}

async function prefillNow(r: PrefillRequest) {
  const project = prefillProject(r.project, current.value?.project, lastTerminalProject.value, chosen.value)
  if (!project) {
    error.value = t('prefill.noProject')
    return
  }
  if (!(await reach(project))) return
  const id = current.value!.id
  const plan = await planPrefill(r.text, {
    ready,
    // What he typed here since his last Enter; the screen's own input line is checked by planPrefill.
    typed: () => typedLine.get(id) !== false,
    confirm: () => confirmed({ message: t('prefill.typed', { command: r.text.trim() }), confirmLabel: t('prefill.append') }),
    stillMine: () => prefillTurn.holds(r.turn),
  })
  if ('handOver' in plan) return handOver(r.text, plan.handOver)
  if (!('type' in plan) || current.value?.id !== id) return
  send(inputMessage(plan.type))
  typedLine.set(id, true)
  term?.focus()
}

onMounted(() => void load())
watch(pendingPrefill, (r) => {
  if (r) void prefill(takePrefill()!)
}, { immediate: true })
onBeforeUnmount(() => disconnect())
</script>

<template>
  <div class="panel director-terminal framed">
    <div class="title">
      {{ t('terminal.title') }}
      <button class="close" :title="t('terminal.closePanel')" @click="emit('close')">×</button>
    </div>
    <div class="terminal-warning">{{ t('terminal.notAnAgent') }}</div>
    <div v-if="error" class="hint bad">{{ error }}</div>
    <div v-if="!available" class="hint warn">{{ t('terminal.unavailable', { reason: reason ?? '' }) }}</div>

    <template v-if="current">
      <div class="terminal-bar">
        <span class="facts" :title="current.project">{{ current.id }} · {{ current.project }}</span>
        <span class="terminal-cost" :title="t('terminal.costTitle')">
          <template v-if="cost">{{ t('terminal.cost', { cost }) }}<span v-if="current.model"> · {{ current.model }}</span></template>
          <template v-else>{{ t('terminal.costNone') }}</template>
        </span>
        <button type="button" :title="t('terminal.copyTitle')" @click="copySelection">{{ t('terminal.copy') }}</button>
        <button type="button" :title="t('terminal.pasteTitle')" @click="paste">{{ t('terminal.paste') }}</button>
        <button type="button" :title="t('terminal.detachTitle')" @click="detach">{{ t('terminal.detach') }}</button>
        <button v-if="!ended" type="button" class="danger" :title="t('terminal.stopTitle')" @click="stop(current)">
          {{ t('terminal.stop') }}
        </button>
      </div>
      <div v-if="ended" class="hint">
        {{ current.exitCode === null ? t('terminal.ended') : t('terminal.endedCode', { code: current.exitCode }) }}
        <button type="button" @click="detach">{{ t('terminal.again') }}</button>
      </div>
      <div v-if="lost && !ended" class="hint bad lost">
        {{ t('terminal.lost') }}
        <button type="button" @click="reattach">{{ t('terminal.reattach') }}</button>
      </div>
      <div ref="screen" class="terminal-screen"></div>
    </template>

    <div v-else class="panel-scroll settings-form">
      <div class="hint">{{ t('terminal.intro') }}</div>
      <label>
        {{ t('terminal.project') }}
        <input v-model="chosen" list="terminal-projects" :placeholder="t('terminal.projectPlaceholder')" :aria-label="t('terminal.projectPlaceholder')" />
        <datalist id="terminal-projects">
          <option v-for="p in choices" :key="p" :value="p" />
        </datalist>
      </label>
      <div>
        <button class="action" type="button" :disabled="busy || !available || !chosen.trim()"
          :title="why([!available, t('ux.why.terminalUnavailable', { reason: reason ?? '' })], [!chosen.trim(), t('ux.why.needFolder')])"
          @click="start">
          {{ busy ? t('terminal.opening') : t('terminal.open') }}
        </button>
      </div>
      <template v-if="open.length">
        <div class="sub">{{ t('terminal.openOnes') }}</div>
        <div v-for="v in open" :key="v.id" class="terminal-line">
          <span class="facts">{{ v.id }} · {{ v.project }}</span>
          <span v-if="costText(v)" class="facts"> · {{ t('terminal.cost', { cost: costText(v) ?? '' }) }}</span>
          <button type="button" @click="attach(v)">{{ t('terminal.attach') }}</button>
          <button type="button" class="danger" @click="stop(v)">{{ t('terminal.stop') }}</button>
        </div>
      </template>
      <div class="hint">{{ t('terminal.journal') }}</div>
    </div>
  </div>
</template>
