<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { t, dateLocale } from '../i18n'
import { office } from '../state/office'
import { requestProposalPrefill } from '../state/terminalPrefill'
import { proposalsOf, typedFor } from '../ui/proposals'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'

/**
 * The shadow proposals of the mission on the board (ADR-0082): what agents in shadow mode asked to write, run or fetch
 * and Floor42 did not run. The proposals are the list on the left; the chosen one on the right, with Copy and "Put in my
 * terminal", apart. Floor42 never runs a proposal: the director reads it, presses Enter himself, and tells the agent the
 * result in a directive. A Write or an Edit holds only its masked summary: the content is not in the journal.
 */
const emit = defineEmits<{ close: [] }>()

const proposals = computed(() => proposalsOf(office.mission?.actions ?? []))
const current = ref('')
watch(
  proposals,
  (list) => {
    if (!list.some((p) => p.id === current.value)) current.value = list[0]?.id ?? ''
  },
  { immediate: true },
)
const tabs = computed<SectionTab[]>(() =>
  proposals.value.map((p) => ({ id: p.id, label: t('proposals.row', { agent: p.agent, tool: p.tool }) })),
)
const chosen = computed(() => proposals.value.find((p) => p.id === current.value) ?? null)
const typed = computed(() => (chosen.value ? typedFor(chosen.value) : null))
const note = ref('')

async function copy() {
  if (!chosen.value) return
  try {
    await navigator.clipboard.writeText(chosen.value.summary)
    note.value = t('proposals.copied')
  } catch (e) {
    note.value = (e as Error).message
  }
}

function terminal(e: MouseEvent) {
  if (!typed.value) return
  const answer = requestProposalPrefill(typed.value, null, e)
  note.value = answer === 'ok' ? '' : t('proposals.refused', { why: answer })
}

const at = (iso: string) => new Date(iso).toLocaleString(dateLocale())
</script>

<template>
  <SectionedPanel
    class="proposals-panel"
    id-prefix="proposals"
    :title="t('proposals.title', { mission: office.mission?.id ?? '' })"
    :hint="t('proposals.hint')"
    :sections="tabs"
    :current="current"
    :focus-on-open="false"
    nav-width="260px"
    @choose="(id) => (current = id)"
    @close="emit('close')"
  >
    <template #top>
      <div v-if="!office.mission" class="hint">{{ t('proposals.noMission') }}</div>
      <div v-else-if="!proposals.length" class="hint">{{ t('proposals.none') }}</div>
    </template>
    <template v-if="chosen" #default>
      <table role="presentation" class="kv">
        <tbody>
        <tr><td>{{ t('proposals.agent') }}</td><td>{{ chosen.agent }}</td></tr>
        <tr><td>{{ t('proposals.tool') }}</td><td>{{ chosen.tool }}</td></tr>
        <tr><td>{{ t('proposals.time') }}</td><td>{{ at(chosen.requestedAt) }}</td></tr>
        <tr><td>{{ t('proposals.summary') }}</td><td><pre class="proposal-text">{{ chosen.summary }}</pre></td></tr>
        </tbody>
      </table>
      <div v-if="chosen.tool !== 'Bash'" class="hint">{{ t('proposals.summaryOnly') }}</div>
      <div class="proposal-actions">
        <button class="action" @click="copy">{{ t('proposals.copy') }}</button>
        <button class="action" :disabled="!typed" :title="typed ? t('proposals.terminalHint') : t('proposals.notTypable')" @click="terminal">
          {{ t('proposals.terminal') }}
        </button>
      </div>
      <div v-if="note" class="hint" role="status">{{ note }}</div>
    </template>
  </SectionedPanel>
</template>

<style scoped>
.proposal-text {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
/* Copy and "Put in my terminal" are two separate decisions: a wide gap keeps a slip from choosing the other. */
.proposal-actions {
  display: flex;
  gap: 32px;
  margin: 14px 0 6px;
}
</style>
