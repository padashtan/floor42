<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { proofApi } from '../api/proof'
import { t } from '../i18n'
import { office, onLiveEvent } from '../state/office'
import { setPanel } from '../state/openPanels'
import { waits } from '../state/waits'
import { goLabel, todoRows, type TodoRow } from '../ui/todo'
import { openTodoGo } from '../ui/todoGo'

/**
 * What waits for the director stays on screen until it is settled (pilot log entry 15): a toast goes away, the desk's
 * badge is easy to miss. The blocking and critical questions first, then the oldest; the others unfold under it.
 * Under them, once the board's mission has no ticket left to do, one line says its delivery must be proven (ADR-0058)
 * before it can be completed (pilot log entry 19).
 */
const emit = defineEmits<{ open: [question: string] }>()

const now = ref(nowMs())
let tick: number | undefined
let unlisten: (() => void) | undefined
onMounted(() => {
  tick = window.setInterval(() => {
    now.value = nowMs()
    void readProof()
  }, 30_000)
  unlisten = onLiveEvent((e) => e.type.startsWith('Proof') && void readProof())
})
onUnmounted(() => {
  window.clearInterval(tick)
  unlisten?.()
})

/** Work done = at least one ticket done and none left to do (proposed, to do, in progress, in review). */
const workDone = computed(() => {
  const m = office.mission
  if (!m || m.status === 'COMPLETED' || m.status === 'CANCELLED') return null
  const open = m.tickets.some((x) => ['PROPOSED', 'TODO', 'IN_PROGRESS', 'IN_REVIEW'].includes(x.status))
  return !open && m.tickets.some((x) => x.status === 'DONE') ? m.id : null
})
// Same read as the accept flow: a state means not proven; null a proven delivery; a failed read says nothing.
const unproven = ref<boolean | null>(null)
async function readProof() {
  const id = workDone.value
  if (!id) return void (unproven.value = null)
  try {
    const a = await proofApi.acceptance(id)
    if (workDone.value === id) unproven.value = a.state !== null
  } catch {
    unproven.value = null
  }
}
watch(workDone, readProof, { immediate: true })
const proofLine = computed(() =>
  workDone.value && unproven.value !== null ? t(unproven.value ? 'waiting.prove' : 'waiting.proven', { mission: workDone.value }) : '',
)

// The To do panel's rows (one source): a question ranks by its blocking and criticality, other approvals just after.
const rank = (r: TodoRow) => {
  const q = office.questions.find((x) => r.go.panel === 'desk' && x.id === r.go.question)
  return q ? (q.blocking ? 0 : 2) + (q.criticality === 'HIGH' ? 0 : 1) : 1.5
}
const waiting = computed(() =>
  todoRows(waits.missions ?? [], office.questions, now.value).sort((a, b) => rank(a) - rank(b) || (a.since ?? '').localeCompare(b.since ?? '')),
)
const unfolded = ref(false)
const shown = computed(() => (unfolded.value ? waiting.value : waiting.value.slice(0, 1)))

const line = (r: TodoRow) => t('waiting.line', { mission: r.mission ?? '', subject: r.text })
const since = (r: TodoRow) => (r.since ? t('waiting.since', { min: Math.max(0, Math.round((now.value - Date.parse(r.since)) / 60_000)) }) : '')
function open(r: TodoRow) {
  if (r.go.panel === 'desk') emit('open', r.go.question)
  else openTodoGo(r.go, (q) => emit('open', q))
}
</script>

<template>
  <div v-if="waiting.length || proofLine" class="waiting-banner" role="status" data-testid="waiting-banner">
    <div v-for="r in shown" :key="r.key" class="waiting-row">
      <span class="waiting-mark" aria-hidden="true">⚠</span>
      <b class="waiting-text">{{ line(r) }}</b>
      <span class="hint">{{ since(r) }}</span>
      <button class="action recommended" type="button" @click="open(r)">{{ t(r.go.panel === 'desk' ? 'ux.toast.open' : goLabel(r)) }}</button>
    </div>
    <div v-if="proofLine" class="waiting-row" data-testid="proof-line">
      <span class="waiting-mark" aria-hidden="true">✓</span>
      <b class="waiting-text">{{ proofLine }}</b>
      <button class="action recommended" type="button" @click="setPanel(unproven ? 'prove' : 'desk', true)">
        {{ unproven ? t('waiting.proveAction') : t('waiting.completeAction') }}
      </button>
    </div>
    <button v-if="waiting.length > 1" class="action waiting-more" type="button" :aria-expanded="unfolded" @click="unfolded = !unfolded">
      {{ unfolded ? '▴' : `▾ ${t('waiting.more', { count: waiting.length - 1 })}` }}
    </button>
  </div>
</template>
