<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type { ArchiveOutcome, MissionSummary } from '../api/types'
import { t, tr } from '../i18n'
import { confirmed } from '../state/confirm'
import { archiveMissions, office, unarchiveMission } from '../state/office'
import { refusalText } from '../ui/refusal'
import ConveneRetroButton from './ConveneRetroButton.vue'

/**
 * The desk's Archives (ADR-0054): the missions the director put away, each restorable, and "Archive all closed missions"
 * to clean the board in one go, after a confirmation that gives the count. What the daemon left out says why.
 */
const archived = ref<MissionSummary[]>([])
const loadError = ref<string | null>(null)
const busy = ref(false)
/** What the last "Archive all closed missions" did: how many, and each one left out with its reason. */
const report = ref<{ done: number; leftOut: ArchiveOutcome[] } | null>(null)
const nothingToArchive = ref(false)
/** The mission just restored: back in the lists, and offered its retrospective if it never had one. */
const restored = ref<MissionSummary | null>(null)

async function load() {
  try {
    archived.value = [...(await api.archivedMissions())].reverse()
    loadError.value = null
  } catch (e) {
    loadError.value = e instanceof ApiError ? refusalText(e.code, e.message) : String(e)
  }
}
onMounted(load)
// Another tab or the bulk action changed the lists: the archives follow.
watch(() => office.missions.map((m) => m.id).join(), load)

async function cleanBoard() {
  busy.value = true
  report.value = null
  nothingToArchive.value = false
  try {
    let closed: MissionSummary[]
    try {
      closed = await api.archivableMissions()
    } catch (e) {
      office.error = e instanceof ApiError ? refusalText(e.code, e.message) : String(e)
      return
    }
    if (!closed.length) {
      nothingToArchive.value = true
      return
    }
    const message = closed.length === 1 ? t('archives.cleanConfirmOne') : t('archives.cleanConfirm', { count: closed.length })
    if (!(await confirmed({ message, confirmLabel: t('confirm.archive') }))) return
    const outcomes = await archiveMissions(closed.map((m) => m.id))
    if (!outcomes) return
    report.value = { done: outcomes.filter((o) => o.archived).length, leftOut: outcomes.filter((o) => !o.archived) }
    await load()
  } finally {
    busy.value = false
  }
}

async function restore(m: MissionSummary) {
  busy.value = true
  try {
    if (await unarchiveMission(m.id)) {
      restored.value = { ...m, archived: false }
      await load()
    }
  } finally {
    busy.value = false
  }
}

function why(o: ArchiveOutcome): string {
  return o.code ? tr(`refusal.${o.code}`, o.code) : ''
}
</script>

<template>
  <div class="mission-archives">
    <div class="hint">{{ t('archives.hint') }}</div>
    <button class="action clean-board" :disabled="busy" :title="t('archives.cleanTitle')" @click="cleanBoard">
      {{ t('archives.clean') }}
    </button>
    <div v-if="nothingToArchive" class="hint clean-none">{{ t('archives.cleanNone') }}</div>
    <div v-if="report" class="clean-report">
      <div>{{ report.done === 1 ? t('archives.cleanedOne') : t('archives.cleaned', { count: report.done }) }}</div>
      <div v-for="o in report.leftOut" :key="o.mission" class="warn">{{ t('archives.leftOut', { id: o.mission, why: why(o) }) }}</div>
    </div>
    <div v-if="restored" class="restored" :data-mission="restored.id">
      {{ t('archives.restored', { id: restored.id, title: restored.title }) }}
      <ConveneRetroButton :mission="restored" />
    </div>
    <div v-if="loadError" class="warn">{{ loadError }}</div>
    <div v-else-if="!archived.length" class="empty-state">{{ t('archives.none') }}</div>
    <ul v-else class="archived-list">
      <li v-for="m in archived" :key="m.id" class="archived-mission" :data-mission="m.id">
        <b>{{ m.id }}</b> · {{ m.title }} · {{ tr(`mstatus.${m.status}`, m.status) }} ·
        <span :class="m.origin === 'REAL' ? 'bad' : 'warn'">{{ t(`log.${m.origin}`) }}</span>
        <button class="action restore" :disabled="busy" :title="t('archives.restoreTitle')" @click="restore(m)">
          {{ t('archives.restore') }}
        </button>
      </li>
    </ul>
  </div>
</template>
