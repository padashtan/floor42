<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, toRef, watch } from 'vue'
import { ApiError, api } from '../api/client'
import type { MeetingView } from '../api/types'
import { dateLocale, t, type Key } from '../i18n'
import { drafts, resetMeetingDraft } from '../state/drafts'
import { callAt } from '../state/meetingCall'
import { office, workshopCall } from '../state/office'
import { choosable, defaultMission, meetableMissions, seated } from '../ui/meeting'
import { oneAtATime, panelView } from '../ui/meetingTurns'
import { why } from '../ui/why'
import MeetingAsk from './MeetingAsk.vue'
import MeetingConversation from './MeetingConversation.vue'

/**
 * The director's meeting (ADR-0047, 0049): choose a mission, the agents and the table, call them; while it is open, ask
 * for their view, read the conversation, reply, give notes and close. An open meeting always shows here, its close
 * button with it; a failed read shows its error, never an empty call form.
 */
const emit = defineEmits<{ close: [] }>()

/** The meetings as read last; null before the first read succeeded. */
const meetings = ref<MeetingView[] | null>(null)
const readError = ref<string | null>(null)
const mission = ref<string | null>(defaultMission(office.missions, office.mission?.id ?? null))
const chosen = ref<string[]>([])
const agenda = toRef(drafts.meeting, 'agenda')
const text = toRef(drafts.meeting, 'text')
const to = ref<string>('')
const directive = ref(false)
const busy = ref(false)

const missions = computed(() => meetableMissions(office.missions))
const view = computed(() => panelView(meetings.value, readError.value, mission.value))
const shown = computed(() => (view.value.kind === 'meeting' ? view.value.meeting : null))
const agents = ref(choosable(office.agents, []))
const past = computed(() => (meetings.value ?? []).filter((m) => !m.open).slice(0, 5))

/** One read at a time: a poll while one is under way is skipped rather than piling up behind a slow daemon. */
const load = oneAtATime(async () => {
  try {
    meetings.value = await api.meetings()
    readError.value = null
  } catch (e) {
    // Shown in the panel itself: a failed read never passes for "no meeting".
    if (e instanceof ApiError && e.needsLogin) office.needsLogin = true
    readError.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
})

async function invite(m: string | null) {
  if (!m) return
  const inv = await workshopCall(() => api.meetingInvitation(m), false)
  const suggested = inv?.suggested ?? []
  agents.value = choosable(office.agents, suggested)
  chosen.value = suggested.filter((a) => office.agents.some((x) => x.id === a))
}

watch(mission, (m) => void invite(m), { immediate: true })
onMounted(load)
// Who is seated and who speaks change as sessions end: the panel reads again, whatever it shows.
const poll = window.setInterval(() => void load(), 3_000)
onBeforeUnmount(() => window.clearInterval(poll))

async function run(action: () => Promise<MeetingView | null>) {
  busy.value = true
  try {
    const v = await action()
    if (v) await load()
    return v
  } finally {
    busy.value = false
  }
}

async function call() {
  const m = mission.value
  if (!m) return
  const v = await run(() => workshopCall(() => api.callMeeting(m, chosen.value, agenda.value.trim() || null, callAt.value)))
  // The meeting exists: what was typed for it is no longer a draft.
  if (v) resetMeetingDraft()
  return v
}

async function say() {
  const m = shown.value
  if (!m || !text.value.trim()) return
  const use = directive.value ? 'ALSO_DIRECTIVE' : 'NOTE_ONLY'
  const said = await run(() => workshopCall(() => api.meetingNote(m.id, to.value || null, text.value.trim(), use)))
  if (said) {
    text.value = ''
    directive.value = false
  }
}

async function close() {
  const m = shown.value
  if (!m) return
  if (await run(() => workshopCall(() => api.closeMeeting(m.id)))) to.value = ''
}

function at(iso: string): string {
  return new Date(iso).toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' })
}

function statusOf(a: MeetingView['attendees'][number]): string {
  return a.finishing || a.status !== 'IN_MEETING' ? t('meet.finishing') : t('meet.seated')
}

const callRefused = computed(() =>
  why([!mission.value, t('meet.noMission')], [chosen.value.length === 0, t('meet.attendees')], [busy.value, '…']),
)
</script>

<template>
  <div class="panel director-meeting framed">
    <div class="title">
      {{ t('meet.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll">
      <div v-if="readError" class="error meeting-read-error">
        {{ t('meetv.readFailed', { error: readError }) }}
        <button class="action small" @click="load">{{ t('meetv.retry') }}</button>
      </div>
      <div v-if="view.kind === 'loading'" class="hint">{{ t('meetv.loading') }}</div>

      <template v-if="shown">
        <div class="sub">{{ t('meet.open', { meeting: shown.id, mission: `${shown.mission} ${shown.missionTitle}` }) }}</div>
        <div class="hint">{{ t('meetv.at', { place: t(shown.place === 'CONFERENCE' ? 'meetv.placeConference' : 'meetv.placeTable') }) }}</div>
        <div v-if="shown.agenda" class="hint">{{ t('meet.agendaShown', { agenda: shown.agenda }) }}</div>
        <ul class="attendees">
          <li v-for="a in shown.attendees" :key="a.id" :class="{ seated: a.status === 'IN_MEETING' && !a.finishing }">
            <b>{{ a.name }}</b>: {{ statusOf(a) }}
          </li>
        </ul>
        <div class="hint">{{ seated(shown).seated }}/{{ seated(shown).of }} {{ t('meet.seated') }}</div>
        <div class="buttons">
          <button class="action recommended meeting-close" :disabled="busy" :title="t('meet.closeHint')" @click="close">
            {{ t('meet.close') }}
          </button>
        </div>

        <div class="sub">{{ t('meetv.conversation') }}</div>
        <MeetingConversation :meeting="shown" />
        <MeetingAsk :meeting="shown" @asked="load" />

        <div class="sub">{{ t('meetv.reply') }}</div>
        <label class="field">
          {{ t('meet.to') }}
          <select v-model="to" class="meeting-to">
            <option value="">{{ t('meet.toAll') }}</option>
            <option v-for="a in shown.attendees" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
        </label>
        <label class="field">
          {{ t('meet.note') }}
          <textarea v-model="text" class="meeting-note" rows="3" maxlength="2000" />
        </label>
        <label class="check"><input v-model="directive" type="checkbox" /> {{ t('meet.directive') }}</label>
        <div class="buttons">
          <button class="action meeting-say" :disabled="busy || !text.trim()" @click="say">{{ t('meet.say') }}</button>
        </div>
        <div class="hint">{{ t('meet.closeHint') }}</div>
      </template>

      <template v-else-if="view.kind === 'call'">
        <div v-if="missions.length === 0" class="hint">{{ t('meet.noMission') }}</div>
        <template v-else>
          <label class="field">
            {{ t('meet.mission') }}
            <select v-model="mission" class="meeting-mission">
              <option v-for="m in missions" :key="m.id" :value="m.id">{{ m.id }} {{ m.title }}</option>
            </select>
          </label>
          <label class="field">
            {{ t('meetv.place') }}
            <select v-model="callAt" class="meeting-place">
              <option value="TABLE">{{ t('meetv.placeTable') }}</option>
              <option value="CONFERENCE">{{ t('meetv.placeConference') }}</option>
            </select>
          </label>
          <div class="sub">{{ t('meet.attendees') }}</div>
          <div class="hint">{{ t('meet.suggested') }}</div>
          <label v-for="a in agents" :key="a.id" class="check attendee">
            <input v-model="chosen" type="checkbox" :value="a.id" />
            {{ a.name }} <span class="role">{{ a.role }} · {{ t(`status.${a.status}` as Key) }}</span>
          </label>
          <label class="field">
            {{ t('meet.agenda') }}
            <input v-model="agenda" class="meeting-agenda" maxlength="500" />
          </label>
          <div class="hint">{{ t('meet.callHint') }}</div>
          <div class="buttons">
            <button class="action recommended meeting-call" :disabled="!!callRefused" :title="callRefused" @click="call">
              {{ t('meet.call') }}
            </button>
          </div>
        </template>
      </template>

      <template v-if="past.length">
        <div class="sub">{{ t('meet.past') }}</div>
        <ul class="past">
          <li v-for="m in past" :key="m.id">
            <b>{{ m.id }}</b> {{ m.mission }}: {{ m.attendees.map((a) => a.name).join(', ') }} ·
            {{ t('meet.closedAt', { at: at(m.closedAt ?? m.calledAt) }) }}
          </li>
        </ul>
      </template>
    </div>
  </div>
</template>

<style scoped>
.director-meeting {
  width: min(520px, 92vw);
}
.attendees,
.past {
  list-style: none;
  margin: 4px 0;
  padding: 0;
}
.attendees li {
  color: var(--amber);
}
.attendees li.seated {
  color: var(--green);
}
.field {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 6px 0;
}
.check {
  display: block;
  margin: 2px 0;
}
.role {
  color: var(--muted);
}
.buttons {
  display: flex;
  gap: 6px;
  margin: 6px 0;
}
.error {
  color: var(--red, #e55);
}
</style>
