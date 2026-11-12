<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ActionView } from '../api/types'
import { t, tr, dateLocale } from '../i18n'
import { office } from '../state/office'
import { basisText, denialText } from '../i18n/events'
import { toolFamily } from '../ui/outputs'
import TerminalBlock from './TerminalBlock.vue'

/**
 * One tool call of an agent, recognisable at a glance: what kind of tool (colour), the command or the file, who, for
 * which ticket, when and how long Floor42 saw it last (request to last update, measured by Floor42), how it ended and
 * why it was allowed. Its output follows, laid out for reading.
 */
const props = withDefaults(defineProps<{ action: ActionView; collapsed?: boolean; height?: string }>(), {
  collapsed: false,
  height: '180px',
})

const open = ref(!props.collapsed)
const family = computed(() => toolFamily(props.action.tool, props.action.category))
const agent = computed(() => office.agents.find((x) => x.id === props.action.agent)?.name ?? props.action.agent)
const lines = computed(() => props.action.result?.split('\n').length ?? 0)
const STAGE_CLASS: Record<string, string> = {
  EXECUTED: 'ok',
  CONFIRMED: 'ok',
  AUTHORIZED: 'warn',
  REQUESTED: 'warn',
  DENIED: 'bad',
  FAILED: 'bad',
}

function clock(at: string) {
  return new Date(at).toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

/** From the request to the last state change Floor42 recorded: waiting for an approval counts. */
const elapsed = computed(() => {
  const ms = Date.parse(props.action.updatedAt) - Date.parse(props.action.requestedAt)
  if (!(ms >= 0)) return null
  return ms < 1000 ? '< 1 s' : ms < 60_000 ? `${Math.round(ms / 1000)} s` : `${Math.round(ms / 60_000)} min`
})
</script>

<template>
  <div class="exec-card" :class="family">
    <div class="exec-head">
      <span class="exec-kind">{{ t(`family.${family}`) }}</span>
      <span class="exec-tool">{{ action.tool }}</span>
      <code class="exec-summary">{{ action.summary }}</code>
    </div>
    <div class="exec-meta">
      <span :class="STAGE_CLASS[action.stage]">{{ tr(`stage.${action.stage}`, action.stage) }}</span>
      · <b>{{ agent }}</b>
      <span v-if="action.ticket"> · {{ action.ticket }}</span>
      · {{ clock(action.requestedAt) }}
      <span v-if="elapsed" :title="t('exec.elapsedTitle')"> · {{ elapsed }}</span>
      <span v-if="action.stage === 'AUTHORIZED' && action.runningSeconds" class="warn">
        · {{ t('exec.running', { seconds: action.runningSeconds }) }}</span
      >
    </div>
    <div v-if="action.decisionBasis" class="exec-basis">
      {{ basisText(action.decisionBasisCode, action.decisionBasisParams, action.decisionBasis) }}
    </div>
    <div v-if="action.denialReason" class="exec-basis bad">{{ denialText(action.denialReason) }}</div>
    <template v-if="action.result">
      <button v-if="!open" class="link" @click="open = true">{{ t('exec.showOutput', { count: lines }) }}</button>
      <TerminalBlock
        v-else
        :text="action.result"
        :tool="action.tool"
        :name="`${action.id}-output`"
        :height="height"
      />
    </template>
    <div v-else-if="action.ran" class="hint">{{ t('exec.noOutput') }}</div>
  </div>
</template>
