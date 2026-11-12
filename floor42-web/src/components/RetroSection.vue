<script setup lang="ts">
import { inject, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../api/client'
import type { MissionView } from '../api/types'
import { dateLocale, t } from '../i18n'
import { confirmed } from '../state/confirm'
import { closeRetro } from '../state/office'
import { MEETING_BUSY } from '../ui/meetingBusy'
import TerminalBlock from './TerminalBlock.vue'

/** The mission's retrospective (ADR-0039): who spoke, what went well and badly, the conclusions and the lessons. */
const props = defineProps<{ retro: NonNullable<MissionView['retro']>; missionId: string }>()

const readRetro = ref<string | null>(null)
/** Shared with the meeting room: the close button waits for any other write of the room. */
const busy = inject(MEETING_BUSY, ref(false))

// A retrospective may wait hours for the plan's usage window to reset: the screen says until when, and the director
// may close it now (the turns still due are recorded as not heard).
const retroWaitsUntil = ref<string | null>(null)
async function loadRetroWait() {
  if (!props.missionId || props.retro.concluded) {
    retroWaitsUntil.value = null
    return
  }
  try {
    retroWaitsUntil.value = (await api.retroWait(props.missionId)).until
  } catch {
    retroWaitsUntil.value = null
  }
}
watch(() => [props.missionId, props.retro.pending.length, props.retro.concluded], loadRetroWait, { immediate: true })
const retroPoll = window.setInterval(loadRetroWait, 15_000)
onBeforeUnmount(() => window.clearInterval(retroPoll))

async function closeRetroNow() {
  if (!props.missionId || !(await confirmed({ message: t('retro.closeConfirm') }))) return
  busy.value = true
  try {
    await closeRetro(props.missionId)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="retro panel-scroll">
    <div class="topic">{{ t('retro.title', { mission: missionId }) }}</div>
    <div class="hint">
      {{ retro.concluded ? t('retro.concluded') : t('retro.atTable') }} · {{ t('retro.chairedBy', { who: retro.chair }) }} ·
      {{ t('retro.asRecorded') }}
    </div>
    <div v-if="!retro.concluded" class="retro-wait">
      <span v-if="retroWaitsUntil" class="warn">
        {{ t('retro.waits', { at: new Date(retroWaitsUntil).toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' }) }) }}
      </span>
      <button class="action" :disabled="busy" :title="t('retro.closeTitle')" @click="closeRetroNow">{{ t('retro.close') }}</button>
    </div>
    <div class="members">
      <span v-for="a in retro.participants" :key="a" class="member" :class="{ pending: retro.pending.includes(a) }">
        <b>{{ a }}</b><span v-if="a === retro.chair"> · {{ t('meeting.chair') }}</span>
        <span v-if="retro.pending.includes(a)" class="hint"> · {{ t('retro.toSpeak') }}</span>
      </span>
    </div>
    <div class="retro-lines">
      <div v-for="c in retro.contributions" :key="c.agent" class="contribution">
        <div class="who"><b>{{ c.agent }}</b></div>
        <div v-if="c.skipped" class="hint">{{ t('retro.skipped', { why: c.skipped }) }}</div>
        <template v-else>
          <div v-for="w in c.wentWell" :key="'w' + w" class="went well">+ {{ w }}</div>
          <div v-for="b in c.wentBadly" :key="'b' + b" class="went badly">− {{ b }}</div>
          <button class="link" @click="readRetro = readRetro === c.agent ? null : c.agent">
            {{ readRetro === c.agent ? t('meeting.hide') : t('meeting.readFull') }}
          </button>
          <TerminalBlock v-if="readRetro === c.agent" :text="c.text ?? ''" :name="`retro-${c.agent}`" format="markdown" />
        </template>
      </div>
    </div>
    <template v-if="retro.concluded">
      <div class="sub">{{ t('retro.conclusion') }}</div>
      <div v-if="retro.reason" class="hint">{{ t('retro.noConclusion', { why: retro.reason }) }}</div>
      <div v-for="c in retro.conclusions" :key="c">· {{ c }}</div>
      <div v-if="retro.lessons.length" class="sub">{{ t('retro.lessons') }}</div>
      <div v-for="l in retro.lessons" :key="l.id" class="hint">
        {{ l.id }} · {{ t(`lessons.status.${l.status}`) }} · {{ l.audience ? t(`lessons.for.${l.audience}`) : t('lessons.forEveryone') }}:
        {{ l.text }}
      </div>
    </template>
    <details class="facts">
      <summary>{{ t('retro.facts') }}</summary>
      <TerminalBlock :text="retro.facts" name="retro-facts" />
    </details>
  </div>
</template>

<style scoped>
.retro {
  margin-bottom: 12px;
}
.retro-lines {
  display: grid;
  gap: 8px;
  margin: 8px 0;
}
.went.well {
  color: var(--green);
}
.went.badly {
  color: var(--amber);
}
.link {
  background: none;
  border: none;
  color: var(--cyan);
  cursor: pointer;
  padding: 2px 0;
}
.facts {
  margin-top: 8px;
}
</style>
