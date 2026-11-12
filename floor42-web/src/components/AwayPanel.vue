<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { AwayLine, AwayView } from '../api/types'
import { t, tr } from '../i18n'
import { acknowledge, acknowledged, ago, awayActions, awayShown, costLine, hasNews } from '../ui/away'
import { onLiveEvent } from '../state/office'

/**
 * "While you were away", shown when the app opens: what happened since the journal sequence the director last
 * acknowledged in this browser (kept in localStorage, never in the journal), and what waits now. Real events only; a
 * cost only when Claude Code reported one, and said to be an estimate. Showing it acknowledges it: a reload does not repeat it.
 */
const emit = defineEmits<{ progress: []; desk: [] }>()

const summary = ref<AwayView | null>(null)
const now = nowMs()

/**
 * What happens while this page is open happens in front of the director: the acknowledged sequence follows the
 * journal (shortly after each live event), so a reload tells only what happened while no page was open. Before, it
 * moved only when this panel was closed, and every reload replayed what the director had just watched.
 */
let followTimer: number | undefined
async function follow() {
  try {
    acknowledge((await api.awaySummary(acknowledged())).until)
  } catch {
    // Tried again on the next event.
  }
}
const stopFollowing = onLiveEvent(() => {
  if (summary.value) return
  window.clearTimeout(followTimer)
  followTimer = window.setTimeout(() => void follow(), 1500)
})
onBeforeUnmount(() => {
  stopFollowing()
  window.clearTimeout(followTimer)
  awayShown.value = false
})
watch(summary, (s) => (awayShown.value = !!s), { immediate: true })

onMounted(async () => {
  const since = acknowledged()
  try {
    const s = await api.awaySummary(since)
    // First visit in this browser: nothing was acknowledged, so the summary starts from now.
    if (since === null) {
      acknowledge(s.until)
      return
    }
    if (hasNews(s)) summary.value = s
    // Shown once is told: a reload does not tell the same news again (it stays on this page until closed).
    acknowledge(s.until)
  } catch {
    // The summary is a courtesy: without it the office works as usual, and the next opening tries again.
  }
})

function close() {
  if (summary.value) acknowledge(summary.value.until)
  summary.value = null
  // What happened while the summary was open was seen too.
  void follow()
}
awayActions.close = close

function lineText(l: AwayLine): string {
  return [l.mission, l.ticket, l.agent ? `(${l.agent})` : null, l.text].filter(Boolean).join(' ')
}

function blockedText(l: AwayLine): string {
  return t(l.kind === 'STUCK' ? 'away.blocked.STUCK' : 'away.blocked.SUSPENDED', {
    mission: l.mission ?? '',
    ticket: l.ticket ?? '',
    agent: l.agent ?? '',
    work: l.text ? tr(`waits.work.${l.text}`, l.text) : '',
  })
}

const MAX = 8
</script>

<template>
  <div v-if="summary" class="panel away-panel framed">
    <div class="title">
      {{ t('away.title') }}
      <button class="close" :title="t('away.close')" @click="close">×</button>
    </div>
    <div class="panel-scroll">
      <div class="hint">{{ t('away.hint') }}</div>

      <div v-if="summary.delivered.length" class="group">
        <div class="label">{{ t('away.delivered', { count: summary.delivered.length }) }}</div>
        <div v-for="(l, n) in summary.delivered.slice(0, MAX)" :key="'d' + n" class="item">
          {{ lineText(l) }} <span class="facts">· {{ t('away.ago', { ago: ago(l.at, now) }) }}</span>
        </div>
        <div v-if="summary.delivered.length > MAX" class="facts">{{ t('away.andMore', { count: summary.delivered.length - MAX }) }}</div>
      </div>

      <div v-if="summary.merged.length" class="group">
        <div class="label">{{ t('away.merged', { count: summary.merged.length }) }}</div>
        <div v-for="(l, n) in summary.merged.slice(0, MAX)" :key="'m' + n" class="item">
          {{ t(l.kind === 'BRANCHES' ? 'away.merge.BRANCHES' : 'away.merge.PATCH', { what: lineText(l) }) }}
        </div>
        <div v-if="summary.merged.length > MAX" class="facts">{{ t('away.andMore', { count: summary.merged.length - MAX }) }}</div>
      </div>

      <div v-if="summary.waiting.length" class="group waiting">
        <div class="label">{{ t('away.waiting', { count: summary.waiting.length }) }}</div>
        <div v-for="(l, n) in summary.waiting.slice(0, MAX)" :key="'w' + n" class="item">
          {{ lineText(l) }} <span class="facts">· {{ t('away.ago', { ago: ago(l.at, now) }) }}</span>
        </div>
        <div v-if="summary.waiting.length > MAX" class="facts">{{ t('away.andMore', { count: summary.waiting.length - MAX }) }}</div>
      </div>

      <div v-if="summary.blocked.length" class="group blocked">
        <div class="label">{{ t('away.blockedTitle', { count: summary.blocked.length }) }}</div>
        <div v-for="(l, n) in summary.blocked" :key="'b' + n" class="item">{{ blockedText(l) }}</div>
      </div>

      <div class="group">
        <div class="label">{{ t('away.sessions', { count: summary.sessions, failed: summary.failedSessions }) }}</div>
        <div v-if="costLine(summary, (amount) => t('away.cost', { amount }))" class="item">
          {{ costLine(summary, (amount) => t('away.cost', { amount })) }}
        </div>
        <div v-else-if="summary.sessions" class="facts">{{ t('away.noCost') }}</div>
      </div>

      <div v-if="summary.more" class="facts">{{ t('away.more', { until: summary.until }) }}</div>

      <div class="actions">
        <button class="action" @click="emit('progress')">{{ t('away.openProgress') }}</button>
        <button v-if="summary.waiting.length" class="action" @click="emit('desk')">{{ t('away.openDesk') }}</button>
        <button class="action" @click="close">{{ t('away.close') }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.away-panel {
  /* Below the crew's cards (top 54 px, about 85 px high): it covered them. */
  top: 150px;
  left: 50%;
  transform: translateX(-50%);
  width: min(560px, calc(100vw - 48px));
  max-height: calc(100vh - 230px);
  z-index: 26;
  display: flex;
  flex-direction: column;
}
.panel-scroll {
  min-height: 0;
  overflow-y: auto;
}
.group {
  margin-top: 8px;
}
.label {
  font-size: 0.8em;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  opacity: 0.7;
}
.item {
  padding: 2px 0;
  overflow-wrap: anywhere;
}
.facts {
  opacity: 0.7;
}
.waiting .label {
  color: var(--amber, #e0a040);
  opacity: 1;
}
.blocked .label {
  color: var(--red, #e05050);
  opacity: 1;
}
.actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
