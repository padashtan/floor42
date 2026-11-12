<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { MissionProgress, ProgressItem, StallView } from '../api/types'
import { t, tr, dateLocale } from '../i18n'
import {
  itemText,
  loadWaits,
  notificationsDenied,
  notificationsSupported,
  nudgeMission,
  setNotifying,
  waits,
} from '../state/waits'
import { progressGo } from '../ui/todo'
import { openTodoGo } from '../ui/todoGo'
import { countdown, stallNeedsYou, waitedFor } from '../ui/waits'

/**
 * Where every mission stands: what runs now, what waits for the director and for how long, and whether the dispatcher
 * is stuck by its own rule. Floor42's reading of its journal and worktrees (state/waits.ts reads it every few seconds);
 * each line offers the button that settles it.
 */
const emit = defineEmits<{
  close: []
  question: [id: string]
  desk: []
  workshop: []
  meeting: []
  report: [mission: string]
}>()

const busy = ref(false)
/** The panel's clock: timers and countdowns move every second between two reads of the server. */
const now = ref(nowMs())
let tick: number | undefined

async function nudge(mission: string) {
  busy.value = true
  try {
    await nudgeMission(mission)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void loadWaits()
  tick = window.setInterval(() => (now.value = nowMs()), 1000)
})
onBeforeUnmount(() => window.clearInterval(tick))

function line(i: ProgressItem): string {
  return itemText(i, now.value)
}

/** How long a wait for the director has lasted, and the countdown to an agent's question being withdrawn. */
function timer(i: ProgressItem): string | null {
  const w = waitedFor(i, now.value)
  const left = countdown(i.deadline, now.value)
  if (!w && !left) return null
  return [w ? t('waits.waiting', { time: w }) : null, left ? t('waits.withdrawnIn', { left }) : null]
    .filter(Boolean)
    .join(' · ')
}

/** What the dispatcher says of the mission, when it is not plain work or plain quiet. */
function stallLine(s: StallView | undefined): string | null {
  if (!s || !s.reason || s.state === 'RUNNING' || s.state === 'STUCK') return null
  if (s.reason === 'NOT_DISPATCHED' || s.reason === 'NOTHING') return null
  return t(`waits.reason.${s.reason}`, {
    ticket: s.ticket ?? '',
    agent: s.agent ?? '',
    // Known when the dispatcher reserved the agent or a session of it is open; otherwise said as unknown.
    elsewhere: s.elsewhere ?? '…',
    work: s.work ? tr(`waits.work.${s.work}`, s.work) : '',
    until: s.until ? new Date(s.until).toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' }) : '',
  })
}

function act(m: MissionProgress, i: ProgressItem) {
  switch (i.kind) {
    case 'QUESTION':
      if (i.question) emit('question', i.question)
      break
    case 'WORKSHOP':
      emit('workshop')
      break
    case 'APPROVE_PLAN':
    case 'APPROVE_TICKET':
      openTodoGo(progressGo(i, m.mission)!, () => {})
      break
    case 'CLOSE_MISSION':
      emit('report', m.mission)
      break
    case 'SESSION_RISK':
      if (i.agent) openTodoGo({ panel: 'quarantine', mission: m.mission, agent: i.agent }, () => {})
      break
    default:
      void nudge(m.mission)
  }
}

/** The label of the button that settles a line; null when there is nothing to click. */
function button(i: ProgressItem): string | null {
  if (i.kind === 'SESSION') return null
  return t(`progress.do.${i.kind}`)
}
</script>

<template>
  <div class="panel progress-panel framed">
    <div class="title">
      {{ t('progress.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll">
      <div class="hint">{{ t('waits.hint') }}</div>
      <div v-if="notificationsSupported()" class="notify">
        <label>
          <input
            type="checkbox"
            :checked="waits.notifying"
            :disabled="notificationsDenied()"
            @change="setNotifying(($event.target as HTMLInputElement).checked)"
          />
          {{ t('waits.notify') }}
        </label>
        <span v-if="notificationsDenied()" class="facts">{{ t('waits.notifyDenied') }}</span>
      </div>
      <div v-if="waits.error" class="warn">{{ waits.error }}</div>
      <div v-if="waits.missions === null" class="hint">…</div>
      <div v-else-if="waits.missions.length === 0" class="hint">{{ t('progress.none') }}</div>
      <section v-for="m in waits.missions ?? []" :key="m.mission" class="mission">
        <div class="head">
          <b>{{ m.mission }}</b> {{ m.title }}
          <span class="facts">
            · {{ tr(`progress.mstatus.${m.status}`, m.status) }} · {{ t('progress.tickets', { done: m.ticketsDone, all: m.ticketsAll }) }}
          </span>
          <div class="bar"><div :style="{ width: m.ticketsAll ? (100 * m.ticketsDone) / m.ticketsAll + '%' : '0' }" /></div>
          <div v-if="stallLine(m.stall)" class="stall" :class="{ you: stallNeedsYou(m.stall) }">
            <b v-if="stallNeedsYou(m.stall)" class="your-turn">{{ t('waits.yourTurn') }}</b>
            {{ stallLine(m.stall) }}
            <button v-if="stallNeedsYou(m.stall)" class="action" @click="emit('workshop')">{{ t('progress.do.WORKSHOP') }}</button>
          </div>
        </div>

        <template v-for="group in (['stuck', 'forYou', 'running'] as const)" :key="group">
          <div v-if="m[group].length" class="group" :class="group">
            <div class="label">{{ group === 'stuck' ? t('waits.group.stuck') : t(`progress.group.${group}`) }}</div>
            <div v-for="(i, n) in m[group]" :key="group + n" class="item">
              <span class="dot" :class="i.kind === 'SILENT' ? 'stuck' : group" />
              <span class="text">
                {{ line(i) }}
                <span v-if="group !== 'running' && timer(i)" class="timer" :class="{ due: !!i.deadline }">{{ timer(i) }}</span>
              </span>
              <button v-if="button(i)" class="action" :disabled="busy" @click="act(m, i)">{{ button(i) }}</button>
            </div>
          </div>
        </template>
        <div v-if="!m.stuck.length && !m.forYou.length && !m.running.length && !stallLine(m.stall)" class="hint">
          {{ t('progress.quiet') }}
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.progress-panel {
  top: 90px;
  left: 50%;
  transform: translateX(-50%);
  width: min(680px, calc(100vw - 48px));
  max-height: calc(100vh - 170px);
  z-index: 24;
  display: flex;
  flex-direction: column;
}
.panel-scroll {
  min-height: 0;
  overflow-y: auto;
}
.mission {
  margin: 10px 0 14px;
}
.head .facts {
  opacity: 0.75;
}
.bar {
  height: 4px;
  margin-top: 4px;
  background: color-mix(in srgb, currentColor 15%, transparent);
  border-radius: 2px;
  overflow: hidden;
}
.bar > div {
  height: 100%;
  background: var(--green, #4c4);
  transition: width 0.4s ease;
}
.group {
  margin-top: 6px;
}
.label {
  font-size: 0.8em;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  opacity: 0.7;
}
.item {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 3px 0;
}
.item .text {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}
.dot.running {
  background: var(--cyan, #3cc);
  animation: pulse 1.6s ease-in-out infinite;
}
.dot.forYou {
  background: var(--amber, #e0a040);
}
.dot.stuck {
  background: var(--red, #e05050);
}
.warn {
  color: var(--amber, #e0a040);
}
.stall {
  margin-top: 4px;
  font-size: 0.9em;
  opacity: 0.85;
}
.stall.you {
  opacity: 1;
  color: var(--amber, #e0a040);
}
.stall .your-turn {
  margin-right: 4px;
}
.timer {
  margin-left: 6px;
  font-size: 0.85em;
  opacity: 0.75;
  white-space: nowrap;
}
.timer.due {
  color: var(--amber, #e0a040);
  opacity: 1;
}
.notify {
  margin: 4px 0 6px;
  font-size: 0.9em;
}
.notify .facts {
  margin-left: 6px;
  opacity: 0.75;
}
@keyframes pulse {
  50% {
    opacity: 0.35;
  }
}
@media (prefers-reduced-motion: reduce) {
  .dot.running {
    animation: none;
  }
}
</style>
