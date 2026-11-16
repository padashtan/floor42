<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api/client'
import type { ProofDossier } from '../api/types'
import { t } from '../i18n'
import { type DossierSectionId, headerLine, parts, toMarkdown } from '../ui/proofDossier'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'

/**
 * The mission proof dossier (ADR-0071): what the mission did, read from its journal, each line with its source and its
 * event numbers. Always masked by the daemon; the two downloads hold exactly what is shown here, the Markdown written
 * by this page in its language, the JSON as the daemon sent it.
 */
const props = defineProps<{ mission: string | null }>()
const emit = defineEmits<{ close: [] }>()

const dossier = ref<ProofDossier | null>(null)
const loading = ref(false)
const error = ref('')
const done = ref('')
const current = ref<DossierSectionId>('identities')
/** The last load asked for; an older answer is dropped. */
let seq = 0

async function load() {
  const asked = ++seq
  dossier.value = null
  error.value = ''
  done.value = ''
  if (!props.mission) return
  loading.value = true
  try {
    const answer = await api.proofDossier(props.mission)
    if (asked === seq) dossier.value = answer
  } catch (e) {
    if (asked === seq) error.value = t('dossier.failed', { message: (e as Error).message })
  } finally {
    if (asked === seq) loading.value = false
  }
}
watch(() => props.mission, load, { immediate: true })

const sections = computed(() => (dossier.value ? parts(dossier.value, t) : []))
const tabs = computed<SectionTab[]>(() =>
  sections.value.map((p) => ({ id: p.id, label: p.title, badge: { text: String(p.count) } })),
)
const part = computed(() => sections.value.find((p) => p.id === current.value) ?? null)

function save(name: string, text: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
  done.value = t('dossier.downloaded', { file: name })
}
const fileName = (ext: string) => `floor42-dossier-${dossier.value?.mission.id ?? ''}.${ext}`
const downloadMd = () => dossier.value && save(fileName('md'), toMarkdown(dossier.value, t), 'text/markdown;charset=utf-8')
const downloadJson = () => dossier.value && save(fileName('json'), JSON.stringify(dossier.value, null, 2), 'application/json')
</script>

<template>
  <SectionedPanel
    class="dossier-panel"
    id-prefix="dossier"
    :title="t('dossier.title', { mission: dossier?.mission.id ?? mission ?? '' })"
    :hint="t('dossier.statement')"
    :sections="tabs"
    :current="current"
    :focus-on-open="false"
    nav-width="190px"
    @choose="(id) => (current = id as DossierSectionId)"
    @close="emit('close')"
  >
    <template #top>
      <div v-if="!mission" class="hint">{{ t('dossier.noMission') }}</div>
      <div v-if="loading" class="hint" role="status">{{ t('dossier.loading') }}</div>
      <div v-if="error" class="warn" role="alert">{{ error }}</div>
      <div v-if="dossier" class="hint">{{ headerLine(dossier, t) }}</div>
      <div v-if="dossier" class="dossier-downloads">
        <button class="action" @click="downloadMd">{{ t('dossier.downloadMd') }}</button>
        <button class="action" @click="downloadJson">{{ t('dossier.downloadJson') }}</button>
      </div>
      <div v-if="done" class="hint" role="status">{{ done }}</div>
    </template>
    <template v-if="part" #default>
      <h3 class="dossier-title">{{ part.title }}</h3>
      <div v-if="part.rows.length === 0" class="hint">{{ t('dossier.none') }}</div>
      <ul v-else class="dossier-rows">
        <li v-for="(r, i) in part.rows" :key="i" class="dossier-row">
          <span class="dossier-text">{{ r.text }}</span>
          <span class="hint dossier-notes">({{ r.notes.join('; ') }})</span>
        </li>
      </ul>
      <div v-if="part.capped" class="hint">{{ part.capped }}</div>
    </template>
  </SectionedPanel>
</template>

<style scoped>
.dossier-downloads {
  display: flex;
  gap: 24px;
  margin: 6px 0;
}
.dossier-title {
  margin: 0 0 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid #1d4a57;
  color: var(--cyan);
  font-size: 15px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.dossier-rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dossier-text {
  overflow-wrap: anywhere;
}
.dossier-notes {
  margin-left: 6px;
}
</style>
