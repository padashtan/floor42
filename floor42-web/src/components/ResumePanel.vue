<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { ResumableMission, ResumableSession, ResumeMode, ResumeView } from '../api/types'
import { t, tr, type Key } from '../i18n'
import { answer, office, putOnBoard, resumeAgent, resumeMission, workshopCall } from '../state/office'
import { nudgeMission } from '../state/waits'
import { ago } from '../ui/away'

/**
 * What the director can pick up again (ADR-0038): missions not closed, and sessions that ended before their report.
 * Opening the panel starts nothing; each "Resume" goes through a path that already existed (Retry, resuming a mission
 * or an agent, the dispatcher looking again). For a developer's session, continuing it with Claude Code's --resume is
 * offered only when Floor42 measured it safe, and the button says which of the two will happen.
 */
const emit = defineEmits<{ close: []; desk: [question: string | null] }>()

const view = ref<ResumeView | null>(null)
const error = ref<string | null>(null)
const busy = ref(false)

async function load() {
  error.value = null
  try {
    view.value = await api.resumable()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
onMounted(load)

async function run(action: () => Promise<unknown>) {
  busy.value = true
  try {
    await action()
    await load()
  } finally {
    busy.value = false
  }
}

function resume(m: ResumableMission) {
  switch (m.action) {
    case 'RESUME_MISSION':
      return run(() => resumeMission(m.id))
    case 'RETRY':
      return run(() => answer(m.retryQuestion!, 'Retry'))
    case 'RESUME_AGENTS':
      return run(async () => {
        for (const a of m.suspendedAgents) await resumeAgent(a)
      })
    case 'NUDGE':
      return run(() => nudgeMission(m.id))
    case 'ANSWER':
    case 'APPROVE':
      void putOnBoard(m.id)
      emit('desk', m.action === 'ANSWER' ? m.retryQuestion : null)
      return
  }
}

function resumeSession(s: ResumableSession, mode: ResumeMode) {
  return run(async () => {
    const v = await workshopCall(() => api.resumeSession(s.session, mode))
    if (v) view.value = v
  })
}

function when(iso: string | null): string {
  return iso ? ago(iso, nowMs()) : '—'
}

function continuity(code: string): string {
  return tr(`resume.why.${code}`, code)
}
</script>

<template>
  <div class="panel resume-panel framed">
    <div class="title">
      {{ t('resume.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll">
      <div class="hint">{{ t('resume.hint') }}</div>
      <div v-if="error" class="warn">{{ error }}</div>
      <div v-if="!view" class="hint">…</div>
      <template v-else>
        <div class="sub">{{ t('resume.missions') }}</div>
        <div v-if="view.missions.length === 0" class="hint">{{ t('resume.noMission') }}</div>
        <div v-for="m in view.missions" :key="m.id" class="row">
          <div class="what">
            <b>{{ m.id }}</b> {{ m.title }}
            <span class="badge" :class="m.origin === 'REAL' ? 'real' : 'sim'">{{ t(`log.${m.origin}`) }}</span>
            <span class="state" :class="m.state">{{ t(`resume.state.${m.state}` as Key) }}</span>
            <div class="facts">
              <span v-if="m.doing">{{ t('resume.doing', { ticket: m.doing, title: m.doingTitle ?? '', status: tr(`board.col.${m.doingStatus}`, m.doingStatus ?? '') }) }} · </span>
              <span v-if="m.openQuestions">{{ t('resume.questions', { count: m.openQuestions }) }} · </span>
              <span v-if="m.suspendedAgents.length">{{ t('resume.agentsSuspended', { agents: m.suspendedAgents.join(', ') }) }} · </span>
              {{ t('resume.lastActivity', { when: when(m.lastActivity) }) }}
              <span v-if="m.workspace" class="path" :title="m.workspace"> · {{ m.workspace }}</span>
            </div>
          </div>
          <div class="buttons">
            <button v-if="office.mission?.id !== m.id" class="action" @click="putOnBoard(m.id)">{{ t('resume.putOnBoard') }}</button>
            <span v-else class="hint">{{ t('resume.onBoard') }}</span>
            <button
              v-if="m.action !== 'NONE'"
              class="action recommended"
              :disabled="busy"
              :title="t(`resume.actionTitle.${m.action}` as Key)"
              @click="resume(m)"
            >
              {{ t(`resume.action.${m.action}` as Key) }}
            </button>
          </div>
        </div>

        <div class="sub">{{ t('resume.sessions') }}</div>
        <div v-if="view.sessions.length === 0" class="hint">{{ t('resume.noSession') }}</div>
        <div v-for="s in view.sessions" :key="s.session" class="row">
          <div class="what">
            <b>{{ s.agentName }}</b> · {{ s.mission }} {{ s.ticket }} {{ s.ticketTitle }}
            <span class="state" :class="s.interrupted ? 'INTERRUPTED' : 'WAITING'">
              {{ s.interrupted ? t('resume.interrupted') : t('resume.failed') }}
            </span>
            <div class="facts">
              {{ t('resume.ended', { when: when(s.endedAt) }) }}
              <span v-if="s.lastAction"> · {{ t('resume.lastAction', { action: s.lastAction }) }}</span>
              <span v-if="s.continuedFrom"> · {{ t('resume.alreadyContinued', { from: s.continuedFrom }) }}</span>
            </div>
            <div class="facts">
              <template v-if="s.continuable">{{ t('resume.canContinue') }}</template>
              <template v-else>{{ t('resume.freshOnly', { why: continuity(s.continuity) }) }}</template>
            </div>
          </div>
          <div class="buttons">
            <template v-if="s.retryQuestion">
              <button class="action" :disabled="busy" :title="t('resume.freshTitle')" @click="resumeSession(s, 'FRESH')">
                {{ t('resume.fresh') }}
              </button>
              <button
                v-if="s.continuable"
                class="action recommended"
                :disabled="busy"
                :title="t('resume.continueTitle')"
                @click="resumeSession(s, 'CONTINUE')"
              >
                {{ t('resume.continue') }}
              </button>
            </template>
            <span v-else class="hint">{{ t('resume.noRetry') }}</span>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.resume-panel {
  top: 90px;
  left: 50%;
  transform: translateX(-50%);
  width: min(760px, calc(100vw - 48px));
  max-height: calc(100vh - 170px);
  z-index: 24;
  display: flex;
  flex-direction: column;
}
.panel-scroll {
  min-height: 0;
  overflow-y: auto;
}
.row {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px solid color-mix(in srgb, currentColor 12%, transparent);
}
.what {
  min-width: 0;
}
.facts {
  opacity: 0.75;
  font-size: 0.9em;
}
.path {
  word-break: break-all;
}
.buttons {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-end;
  flex-shrink: 0;
}
.state {
  margin-left: 6px;
  font-size: 0.8em;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.state.INTERRUPTED,
.state.SUSPENDED {
  color: #e0a040;
}
.state.RUNNING {
  color: var(--cyan, #4cc);
}
.warn {
  color: #e0a040;
}
</style>
