<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api/client'
import type { JournalAlertsView, UnreadableEventView } from '../api/types'
import { t, tr, dateLocale } from '../i18n'
import { office } from '../state/office'

/**
 * D14 (ADR-0042): the journal rows the daemon could not read at startup. Replay left them out, so the state on screen
 * may be incomplete: said loudly, with the list. The rows themselves stay in the journal; their content is never shown.
 */
const events = ref<UnreadableEventView[]>([])
const open = ref(false)
const newer = computed(() => events.value.some((e) => e.reason === 'NEWER_VERSION'))
/**
 * Also said loudly (ADR-0044, amended): a restore file this start refused (something other than the director asked
 * for a rollback), another program holding the journal, a daemon its own OS user can read.
 */
const alerts = ref<JournalAlertsView | null>(null)
const dumpable = computed(() => office.status?.process?.nonDumpable === false)
/** Said on Linux and in Docker, where the daemon is otherwise closed to its OS user (nonDumpable is known there). */
const attachOpen = computed(
  () => office.status?.process?.nonDumpable != null && office.status?.process?.attachDisabled === false,
)

async function acknowledge() {
  try {
    alerts.value = await api.acknowledgeContinuity()
  } catch {
    await readAlerts()
  }
}
let timer: ReturnType<typeof setInterval> | undefined

async function readAlerts() {
  try {
    alerts.value = await api.journalAlerts()
  } catch {
    alerts.value = null
  }
}

onMounted(async () => {
  try {
    events.value = await api.unreadableEvents()
  } catch {
    events.value = []
  }
  await readAlerts()
  timer = setInterval(() => void readAlerts(), 30_000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div v-if="alerts?.refusedRestore" class="journal-banner" role="alert">
    <b>{{
      t('journal.refusedRestore', {
        name: alerts.refusedRestore.backup || '?',
        kept: alerts.refusedRestore.keptAs ?? '—',
      })
    }}</b>
  </div>
  <div v-if="alerts?.locked" class="journal-banner" role="alert">
    <b>{{ t('journal.locked', { since: new Date(alerts.locked.since).toLocaleString(dateLocale()) }) }}</b>
  </div>
  <div
    v-if="alerts?.continuity"
    class="journal-banner"
    :class="{ notice: alerts.continuity.reason === 'FIRST_CHECK' }"
    :role="alerts.continuity.reason === 'FIRST_CHECK' ? 'status' : 'alert'"
  >
    <b>{{
      t(`journal.continuity.${alerts.continuity.reason}`, {
        seen: alerts.continuity.recordedEvents ?? '?',
        at: alerts.continuity.recordedAt ? new Date(alerts.continuity.recordedAt).toLocaleString(dateLocale()) : '?',
        found: alerts.continuity.foundEvents ?? '?',
      })
    }}</b>
    <span v-if="alerts.continuity.displaced"> {{ t('journal.continuity.displaced', { name: alerts.continuity.displaced }) }}</span>
    <span v-if="alerts.continuity.reason !== 'FIRST_CHECK' && alerts.continuity.reason !== 'ANCHOR_FAILED'">
      {{ t('journal.continuity.detection') }}</span
    >
    <button class="action" type="button" @click="acknowledge">{{ t('journal.continuity.acknowledge') }}</button>
  </div>
  <div v-if="alerts?.configFiles?.length" class="journal-banner" role="alert">
    <b>{{ t('journal.configFiles', { files: alerts.configFiles.join(', ') }) }}</b>
  </div>
  <div v-if="attachOpen" class="journal-banner" role="alert">
    <b>{{ t('journal.attach') }}</b>
  </div>
  <div v-if="dumpable" class="journal-banner" role="alert">
    <b>{{ t('journal.dumpable', { requested: String(office.status?.process.nonDumpableRequested) }) }}</b>
  </div>
  <div v-if="events.length" class="journal-banner" role="alert">
    <b>{{ t('journal.unreadable', { count: events.length }) }}</b>
    <span v-if="newer"> {{ t('journal.newerHint') }}</span>
    <button class="action" type="button" @click="open = !open">
      {{ open ? t('journal.hide') : t('journal.show') }}
    </button>
  </div>
  <div v-if="open && events.length" class="panel journal-panel framed">
    <div class="title">
      {{ t('journal.title') }}
      <button class="close" :title="t('desk.close')" @click="open = false">×</button>
    </div>
    <div class="panel-scroll settings-form">
      <div class="hint">{{ t('journal.explain') }}</div>
      <table class="defaults">
        <thead>
          <tr>
            <th>#</th>
            <th>{{ t('journal.col.type') }}</th>
            <th>{{ t('journal.col.mission') }}</th>
            <th>{{ t('journal.col.reason') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in events" :key="e.sequence">
            <td>{{ e.sequence }}</td>
            <td>
              <b>{{ e.type }}</b>
            </td>
            <td>{{ e.mission ?? '—' }}</td>
            <td>
              <span :class="e.reason === 'NEWER_VERSION' ? 'warn' : 'bad'">{{ tr(`journal.reason.${e.reason}`, e.reason) }}</span>
              <div class="facts">{{ e.detail }}</div>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="hint">{{ t('journal.whatToDo') }}</div>
    </div>
  </div>
</template>
