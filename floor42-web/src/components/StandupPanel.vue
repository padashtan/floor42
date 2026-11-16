<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api/client'
import type { Standup, StandupLine } from '../api/types'
import { t } from '../i18n'
import { lineText } from '../ui/standup'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'

/**
 * The 9 o'clock standup (ADR-0078): per agent, yesterday, today and what blocks, read from the journal; no model, and
 * nothing here is recorded. The agents are the list on the left, the chosen agent's three columns on the right, so
 * one agent reads on one screen. Ticket titles and question subjects are agent text, shown as plain text.
 */
const emit = defineEmits<{ close: [] }>()

const standup = ref<Standup | null>(null)
const loading = ref(true)
const error = ref('')
const current = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    standup.value = await api.standup(Intl.DateTimeFormat().resolvedOptions().timeZone)
    current.value = standup.value.agents[0]?.agent ?? ''
  } catch (e) {
    error.value = t('standup.failed', { message: (e as Error).message })
  } finally {
    loading.value = false
  }
}
void load()

const agents = computed(() => standup.value?.agents ?? [])
const tabs = computed<SectionTab[]>(() =>
  agents.value.map((a) => ({
    id: a.agent,
    label: a.name,
    badge: a.blocked.length ? { text: String(a.blocked.length), title: t('standup.blockedBadge', { count: a.blocked.length }), tone: 'changed' } : null,
  })),
)
const agent = computed(() => agents.value.find((a) => a.agent === current.value) ?? null)
const columns = computed<{ id: string; title: string; lines: StandupLine[] }[]>(() => {
  const a = agent.value
  if (!a) return []
  const yesterday = standup.value?.previousDay
  return [
    { id: 'yesterday', title: yesterday ? `${t('standup.yesterday')} (${yesterday})` : t('standup.yesterday'), lines: a.yesterday },
    { id: 'today', title: t('standup.today'), lines: a.today },
    { id: 'blocked', title: t('standup.blocked'), lines: a.blocked },
  ]
})
watch(agents, (list) => list.some((a) => a.agent === current.value) || (current.value = list[0]?.agent ?? ''))
</script>

<template>
  <SectionedPanel
    class="standup-panel"
    id-prefix="standup"
    :title="t('standup.title', { date: standup?.date ?? '' })"
    :hint="t('standup.hint')"
    :sections="tabs"
    :current="current"
    :focus-on-open="false"
    nav-width="170px"
    @choose="(id) => (current = id)"
    @close="emit('close')"
  >
    <template #top>
      <div v-if="loading" class="hint" role="status">{{ t('standup.loading') }}</div>
      <div v-if="error" class="warn" role="alert">{{ error }}</div>
      <div v-if="standup && !standup.previousDay" class="hint">{{ t('standup.noPrevious') }}</div>
      <div v-if="standup && !agents.length" class="hint">{{ t('standup.noAgents') }}</div>
    </template>
    <template v-if="agent" #default>
      <div class="standup-cols">
        <section v-for="c in columns" :key="c.id" class="standup-col" :class="`standup-${c.id}`">
          <h3 class="standup-head">{{ c.title }}</h3>
          <div v-if="!c.lines.length" class="hint">{{ t('standup.none') }}</div>
          <ul v-else class="standup-lines">
            <li v-for="(l, i) in c.lines" :key="i" class="standup-line">{{ lineText(l, t) }}</li>
          </ul>
        </section>
      </div>
    </template>
  </SectionedPanel>
</template>

<style scoped>
.standup-cols {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 24px;
}
.standup-head {
  margin: 0 0 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid #1d4a57;
  color: var(--cyan);
  font-size: 14px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.standup-blocked .standup-head {
  color: var(--amber, #e0a030);
}
.standup-lines {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.standup-line {
  overflow-wrap: anywhere;
}
</style>
