<script setup lang="ts">
import { ref } from 'vue'
import { ApiError, api } from '../api/client'
import type { MaskedItem, ReplayExportPreview } from '../api/types'
import { lang, t } from '../i18n'

/**
 * Exporting a mission as a replay to share (ADR-0035): the director asks, reads what the file will hold and what was
 * masked, then confirms. The file is built by the daemon for the very content previewed (its digest).
 */
const props = defineProps<{ mission: string }>()

/** The player's words, sent in the screen's language: the file speaks it offline. */
const FILE_LABELS = [
  'play',
  'pause',
  'back',
  'forward',
  'speed',
  'real',
  'simulated',
  'realTip',
  'simulatedTip',
  'header',
  'pacing',
  'room',
  'board',
  'log',
  'todo',
  'inProgress',
  'inReview',
  'done',
  'cancelled',
  'idle',
  'noAgents',
  'event',
  'origins',
  'cost',
  'costs',
  'output',
  'clipped',
] as const

const preview = ref<ReplayExportPreview | null>(null)
const busy = ref(false)
const message = ref('')

async function open(): Promise<void> {
  busy.value = true
  message.value = ''
  try {
    preview.value = await api.replayPreview(props.mission)
  } catch (e) {
    message.value = t('replayExport.failed', { message: (e as Error).message })
  } finally {
    busy.value = false
  }
}

async function confirm(): Promise<void> {
  const p = preview.value
  if (!p) return
  busy.value = true
  message.value = ''
  const labels: Record<string, string> = {}
  for (const k of FILE_LABELS) labels[k] = t(`replayExport.file.${k}`)
  try {
    const file = await api.exportReplay(props.mission, { digest: p.digest, language: lang.value, labels })
    const name = `floor42-replay-${p.mission}.html`
    const url = URL.createObjectURL(file)
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    URL.revokeObjectURL(url)
    preview.value = null
    message.value = t('replayExport.done', { file: name })
  } catch (e) {
    if (e instanceof ApiError && e.code === 'REPLAY_CHANGED') {
      preview.value = await api.replayPreview(props.mission).catch(() => null)
      message.value = t('replayExport.changed')
    } else {
      message.value = t('replayExport.failed', { message: (e as Error).message })
    }
  } finally {
    busy.value = false
  }
}

const kind = (k: MaskedItem['kind'], count: number) => t(`replayExport.kind.${k}`, { count })
const where = (d: MaskedItem) =>
  d.event === 0
    ? t('replayExport.missionItem', { field: d.field, kind: kind(d.kind, 1) })
    : t('replayExport.item', { event: d.event, type: d.type, field: d.field, kind: kind(d.kind, 1) })
</script>

<template>
  <div class="replay-export">
    <button v-if="!preview" :disabled="busy" :title="t('replayExport.buttonTitle')" @click="open">
      {{ busy ? t('replayExport.loading') : t('replayExport.button') }}
    </button>
    <div v-else class="preview">
      <div class="sub">{{ t('replayExport.title', { mission: preview.mission }) }}</div>
      <div>
        {{
          t('replayExport.included', {
            events: preview.events,
            real: preview.real,
            simulated: preview.simulated,
            tickets: preview.tickets,
            outputs: preview.outputs,
            costs: preview.costs,
          })
        }}
      </div>
      <div v-if="preview.agents.length" class="hint">
        {{ t('replayExport.agents', { names: preview.agents.join(', ') }) }}
      </div>
      <div class="sub">{{ t('replayExport.masked') }}</div>
      <div v-if="preview.masked.length === 0" class="hint">{{ t('replayExport.nothingMasked') }}</div>
      <ul v-else>
        <li v-for="m in preview.masked" :key="m.kind">{{ kind(m.kind, m.count) }}</li>
      </ul>
      <details v-if="preview.details.length">
        <summary>{{ t('replayExport.where') }}</summary>
        <div v-for="(d, i) in preview.details" :key="i" class="hint">{{ where(d) }}</div>
        <div v-if="preview.detailsTruncated" class="hint">{{ t('replayExport.more') }}</div>
      </details>
      <div class="hint">{{ t('replayExport.never') }}</div>
      <div class="hint">{{ t('replayExport.statement', { version: preview.version, mission: preview.mission }) }}</div>
      <div class="actions">
        <button :disabled="busy" @click="confirm">{{ t('replayExport.confirm') }}</button>
        <button :disabled="busy" @click="preview = null">{{ t('replayExport.cancel') }}</button>
      </div>
    </div>
    <div v-if="message" class="hint">{{ message }}</div>
  </div>
</template>

<style scoped>
.replay-export {
  margin-bottom: 8px;
}
.preview {
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 4px;
  padding: 6px 8px;
}
.preview ul {
  margin: 2px 0 4px;
  padding-left: 18px;
}
.actions {
  display: flex;
  gap: 6px;
  margin-top: 6px;
}
</style>
