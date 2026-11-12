<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api/client'
import type { UncommittedCheck } from '../api/types'
import { t } from '../i18n'
import { office } from '../state/office'
import { waits } from '../state/waits'
import { acceptanceRows, CATEGORIES, goLabel, inCategory, merged, todoRows, uncommittedRow, type TodoRow } from '../ui/todo'
import { openTodoGo } from '../ui/todoGo'
import { waited } from '../ui/waits'

/**
 * Everything that waits for the director, always on screen under the event log: approvals, questions, alerts and
 * rulings, each with its count and, per row, the one button that opens the place to act. Read from what the page
 * already holds (state/waits.ts, the open questions), so it follows the event stream like the Progress panel does.
 */
const emit = defineEmits<{ question: [id: string]; workshop: []; report: [mission: string]; progress: [] }>()

const KEY = 'floor42.todo.collapsed'
const stored = (): boolean => {
  try {
    return localStorage.getItem(KEY) === 'yes'
  } catch {
    return false
  }
}
const collapsed = ref(stored())
watch(collapsed, (c) => {
  try {
    localStorage.setItem(KEY, c ? 'yes' : 'no')
  } catch {
    // Not remembered: the panel just opens expanded next time.
  }
})

/** The clock of the "since" column, moved every second between two reads. */
const now = ref(nowMs())
/** The newest event as of the last tick: uncommitted work is read again at most once a tick, only after events. */
const seenEvent = ref(office.log[0]?.id ?? '')
let tick: number | undefined
let observer: ResizeObserver | undefined

/** The panel sits under the event log, which grows and shrinks with its lines: its top follows the log's bottom. */
function follow() {
  const log = document.querySelector<HTMLElement>('.hud > .log')
  const root = document.documentElement.style
  if (!log) return root.removeProperty('--log-bottom')
  root.setProperty('--log-bottom', `${Math.round(log.getBoundingClientRect().bottom) + 8}px`)
}

onMounted(() => {
  tick = window.setInterval(() => {
    now.value = nowMs()
    seenEvent.value = office.log[0]?.id ?? ''
  }, 10000)
  follow()
  const log = document.querySelector('.hud > .log')
  if (log && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(follow)
    observer.observe(log)
  }
  window.addEventListener('resize', follow)
})
onBeforeUnmount(() => {
  window.clearInterval(tick)
  observer?.disconnect()
  window.removeEventListener('resize', follow)
  document.documentElement.style.removeProperty('--log-bottom')
})

const base = computed(() => todoRows(waits.missions ?? [], office.questions, now.value))

/**
 * Work left uncommitted, read for each mission that waits for acceptance when its row appears and again when that
 * mission's progress changes (its status or its tickets done) or events arrived by the next 10 s tick (a commit
 * leaves progress as it is, not the log), not on every tick. A read that fails is kept as
 * 'failed' (said "cannot tell"), never as clean.
 */
const checks = reactive<Record<string, UncommittedCheck | 'failed'>>({})
/** The progress each mission was last read at; an answer for an older progress is dropped. */
const asked = new Map<string, string>()
const progressOf = (m: string) => {
  const p = waits.missions?.find((x) => x.mission === m)
  return p ? `${p.status}:${p.ticketsDone}/${p.ticketsAll}:${seenEvent.value}` : ''
}
watch(
  () => acceptanceRows(base.value).flatMap((r) => (r.mission ? [[r.mission, progressOf(r.mission)] as const] : [])),
  (waiting) => {
    for (const m of [...asked.keys()].filter((x) => !waiting.some(([w]) => w === x))) {
      asked.delete(m)
      delete checks[m]
    }
    for (const [m, at] of waiting.filter(([m, at]) => asked.get(m) !== at)) {
      asked.set(m, at)
      api.uncommitted(m).then(
        (c) => void (asked.get(m) === at && (checks[m] = c)),
        () => void (asked.get(m) === at && (checks[m] = 'failed')),
      )
    }
  },
  { immediate: true },
)
const rows = computed(() =>
  merged(base.value, acceptanceRows(base.value).flatMap((r) => uncommittedRow(r, checks[r.mission ?? '']) ?? [])),
)
const count = computed(() => rows.value.length)

function since(r: TodoRow): string {
  return r.since ? waited(Math.max(0, now.value - Date.parse(r.since))) : ''
}

function go(r: TodoRow) {
  const g = r.go
  if (g.panel === 'desk') emit('question', g.question)
  else if (g.panel === 'report') emit('report', g.mission)
  else if (g.panel === 'board' || g.panel === 'plan' || g.panel === 'quarantine') openTodoGo(g, () => {})
  else if (g.panel === 'workshop') emit('workshop')
  else emit('progress')
}
</script>

<template>
  <div class="panel todo" :class="{ collapsed }">
    <div class="title">
      {{ t('todo.title') }}
      <span v-if="count" class="count" data-testid="todo-total">{{ count }}</span>
      <button class="fold" :aria-expanded="!collapsed" :title="t(collapsed ? 'todo.expand' : 'todo.collapse')" :aria-label="t(collapsed ? 'todo.expand' : 'todo.collapse')" @click="collapsed = !collapsed">
        {{ collapsed ? '▸' : '▾' }}
      </button>
    </div>
    <template v-if="!collapsed">
      <div v-if="count === 0" class="empty">{{ t('todo.none') }}</div>
      <section v-for="c in CATEGORIES" :key="c" class="cat" :data-category="c">
        <div class="sub">
          {{ t(`todo.cat.${c}`) }}
          <span class="count" :class="{ zero: inCategory(rows, c).length === 0 }">{{ inCategory(rows, c).length }}</span>
        </div>
        <div v-for="r in inCategory(rows, c)" :key="r.key" class="row">
          <div class="what">
            <span class="text">{{ r.text }}</span>
            <span class="facts">
              {{ [r.mission, r.agent, since(r) ? t('todo.since', { time: since(r) }) : null].filter(Boolean).join(' · ') }}
            </span>
          </div>
          <button class="action" @click="go(r)">{{ t(goLabel(r)) }}</button>
        </div>
      </section>
    </template>
  </div>
</template>
