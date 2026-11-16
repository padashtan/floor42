<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api/client'
import type { OversightReport } from '../api/types'
import { t } from '../i18n'
import { type OversightSectionId, headerLine, parts } from '../ui/oversight'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'

/**
 * The mission's human oversight report (ADR-0075): who supervised, what the agents could do, what they did, what humans
 * overrode, read from the journal, each line with its event numbers. Masked by the daemon. The two downloads are the
 * daemon's own files, the JSON and the Markdown (English words, agent text inert).
 */
const props = defineProps<{ mission: string | null }>()
const emit = defineEmits<{ close: [] }>()

const report = ref<OversightReport | null>(null)
const loading = ref(false)
const error = ref('')
const current = ref<OversightSectionId>('supervision')
/** The last load asked for; an older answer is dropped. */
let seq = 0

async function load() {
  const asked = ++seq
  report.value = null
  error.value = ''
  if (!props.mission) return
  loading.value = true
  try {
    const answer = await api.oversightReport(props.mission)
    if (asked === seq) report.value = answer
  } catch (e) {
    if (asked === seq) error.value = t('oversight.failed', { message: (e as Error).message })
  } finally {
    if (asked === seq) loading.value = false
  }
}
watch(() => props.mission, load, { immediate: true })

const sections = computed(() => (report.value ? parts(report.value, t) : []))
const tabs = computed<SectionTab[]>(() =>
  sections.value.map((p) => ({ id: p.id, label: p.title, badge: { text: String(p.count) } })),
)
const part = computed(() => sections.value.find((p) => p.id === current.value) ?? null)
const file = (format: 'json' | 'md') =>
  `/api/missions/${encodeURIComponent(report.value?.mission.id ?? '')}/oversight-report?format=${format}&download=true`
</script>

<template>
  <SectionedPanel
    class="oversight-panel"
    id-prefix="oversight"
    :title="t('oversight.title', { mission: report?.mission.id ?? mission ?? '' })"
    :hint="t('oversight.statement')"
    :sections="tabs"
    :current="current"
    :focus-on-open="false"
    nav-width="220px"
    @choose="(id) => (current = id as OversightSectionId)"
    @close="emit('close')"
  >
    <template #top>
      <div v-if="!mission" class="hint">{{ t('oversight.noMission') }}</div>
      <div v-if="loading" class="hint" role="status">{{ t('oversight.loading') }}</div>
      <div v-if="error" class="warn" role="alert">{{ error }}</div>
      <div v-if="report" class="hint">{{ headerLine(report, t) }}</div>
      <div v-if="report" class="oversight-downloads">
        <a class="action" :href="file('md')" download>{{ t('oversight.downloadMd') }}</a>
        <a class="action" :href="file('json')" download>{{ t('oversight.downloadJson') }}</a>
      </div>
      <div v-if="report" class="hint">{{ t('oversight.mdNote') }}</div>
    </template>
    <template v-if="part" #default>
      <h3 class="oversight-title">{{ part.title }}</h3>
      <div v-if="part.rows.length === 0" class="hint">{{ t('oversight.none') }}</div>
      <ul v-else class="oversight-rows">
        <li v-for="(r, i) in part.rows" :key="i" class="oversight-row">
          <span class="oversight-text">{{ r.text }}</span>
          <span v-if="r.notes.length" class="hint oversight-notes">({{ r.notes.join('; ') }})</span>
        </li>
      </ul>
      <div v-if="part.capped" class="hint">{{ part.capped }}</div>
    </template>
  </SectionedPanel>
</template>

<style scoped>
.oversight-downloads {
  display: flex;
  gap: 24px;
  margin: 6px 0;
}
.oversight-title {
  margin: 0 0 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid #1d4a57;
  color: var(--cyan);
  font-size: 15px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.oversight-rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.oversight-text {
  overflow-wrap: anywhere;
}
.oversight-notes {
  margin-left: 6px;
}
</style>
