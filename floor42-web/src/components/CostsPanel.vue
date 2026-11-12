<script setup lang="ts">
// cost-overview: what the sessions cost, read from the journal by the daemon. Today, this week and this month across
// real missions; each mission's total against its cap; the selected mission per agent and per ticket. Every amount is
// Claude Code's estimate (≈), never a bill; a sum missing some sessions' cost is marked as a lower bound (≥).
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type { CostLine, CostsOverview, MissionCost, MissionCosts } from '../api/types'
import { t, tr } from '../i18n'
import { office, onLiveEvent } from '../state/office'
import { shownAmount, tokens, usd } from '../ui/costs'

const emit = defineEmits<{ close: [] }>()

const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
const overview = ref<CostsOverview | null>(null)
const detail = ref<MissionCosts | null>(null)
const selected = ref<string | null>(office.mission?.id ?? null)
const error = ref<string | null>(null)

const periods = computed(() => {
  const o = overview.value
  return o ? ([['today', o.today], ['week', o.week], ['month', o.month]] as const) : []
})

function message(e: unknown) {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

async function load() {
  try {
    overview.value = await api.costs(zone)
    selected.value ??= overview.value.missions[0]?.mission ?? null
    error.value = null
  } catch (e) {
    error.value = message(e)
  }
  await loadDetail()
}

async function loadDetail() {
  const id = selected.value
  if (!id) {
    detail.value = null
    return
  }
  try {
    const d = await api.missionCosts(id)
    if (selected.value === id) detail.value = d
  } catch (e) {
    error.value = message(e)
  }
}

watch(selected, () => void loadDetail())

// A session's cost is known when it ends; a budget changes with the director's decision.
let timer: number | undefined
const off = onLiveEvent((e) => {
  if (!['AgentSessionEnded', 'AgentSessionStarted', 'BudgetSet'].includes(e.type)) return
  window.clearTimeout(timer)
  timer = window.setTimeout(() => void load(), 800)
})
onMounted(load)
onUnmounted(() => {
  off()
  window.clearTimeout(timer)
})

function cap(m: MissionCost) {
  if (m.budgetUsd !== null) return `${usd(m.budgetUsd)} USD`
  return m.capDecided ? t('cost.capNone') : t('cost.capUndecided')
}

function sessionsText(l: CostLine) {
  const parts = [String(l.sessions)]
  if (l.sessionsOpen) parts.push(t('cost.open', { count: l.sessionsOpen }))
  if (l.sessionsWithoutCost) parts.push(t('cost.noCost', { count: l.sessionsWithoutCost }))
  return parts.join(' · ')
}

const basis = (b: string) => tr(`basis.${b}`, b)
const groups = computed(() =>
  detail.value
    ? [
        { label: t('cost.perAgent'), lines: detail.value.agents },
        { label: t('cost.perTicket'), lines: detail.value.tickets },
      ]
    : [],
)
</script>

<template>
  <div class="panel costs-panel framed">
    <div class="title">
      {{ t('cost.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll">
      <div class="hint">{{ t('cost.legend') }}</div>
      <div class="hint">{{ t('cost.boundLegend') }}</div>
      <div v-if="error" class="hint warn">{{ t('cost.failed', { error }) }}</div>
      <div v-if="!overview && !error" class="hint">…</div>

      <template v-if="overview">
        <div class="sub">{{ t('cost.periods') }}</div>
        <table class="costs">
          <tr>
            <th></th>
            <th>{{ t('cost.col.amount') }}</th>
            <th>{{ t('cost.col.tokens') }} ({{ t('cost.inOut') }})</th>
            <th>{{ t('cost.col.sessions') }}</th>
          </tr>
          <tr v-for="[key, l] in periods" :key="key">
            <td>{{ t(`cost.${key}`) }}</td>
            <td :title="basis(l.costBasis)">{{ shownAmount(l) === '—' ? '—' : `${shownAmount(l)} USD` }}</td>
            <td :title="basis(l.tokensBasis)">{{ tokens(l.inputTokens) }} / {{ tokens(l.outputTokens) }}</td>
            <td :title="basis(l.sessionsBasis)">{{ sessionsText(l) }}</td>
          </tr>
        </table>
        <div class="hint">{{ t('cost.periodsHint', { zone: overview.zone }) }}</div>

        <div class="sub">{{ t('cost.missions') }}</div>
        <div v-if="!overview.missions.length" class="hint">{{ t('cost.none') }}</div>
        <table v-else class="costs pick">
          <tr>
            <th>{{ t('cost.col.mission') }}</th>
            <th>{{ t('cost.col.amount') }}</th>
            <th>{{ t('cost.col.cap') }}</th>
            <th>{{ t('cost.col.sessions') }}</th>
          </tr>
          <tr
            v-for="m in overview.missions"
            :key="m.mission"
            :class="{ on: m.mission === selected, simulated: m.origin !== 'REAL' }"
            @click="selected = m.mission"
          >
            <td :title="m.title">
              <b>{{ m.mission }}</b> {{ m.title }}
              <span v-if="m.origin !== 'REAL'" class="warn">· {{ t('cost.simulated') }}</span>
              <span v-if="m.archived" class="hint archived-mark">· {{ t('archives.mark') }}</span>
            </td>
            <td :title="basis(m.total.costBasis)">{{ shownAmount(m.total) }}</td>
            <td :class="{ warn: m.origin === 'REAL' && m.budgetUsd === null }">{{ cap(m) }}</td>
            <td :title="basis(m.total.sessionsBasis)">{{ sessionsText(m.total) }}</td>
          </tr>
        </table>
      </template>

      <template v-if="detail">
        <div class="sub">{{ detail.mission.mission }} · {{ detail.mission.title }}</div>
        <div class="hint">
          {{ shownAmount(detail.mission.total) === '—' ? t('cost.unavailable') : `${shownAmount(detail.mission.total)} USD` }}
          · {{ cap(detail.mission) }} · {{ t('cost.col.tokens') }} {{ tokens(detail.mission.total.inputTokens) }} /
          {{ tokens(detail.mission.total.outputTokens) }}
        </div>
        <template v-for="g in groups" :key="g.label">
          <div class="sub">{{ g.label }}</div>
          <div v-if="!g.lines.length" class="hint">{{ t('cost.noSession') }}</div>
          <table v-else class="costs">
            <tr>
              <th></th>
              <th>{{ t('cost.col.amount') }}</th>
              <th>{{ t('cost.col.tokens') }} ({{ t('cost.inOut') }})</th>
              <th>{{ t('cost.col.sessions') }}</th>
            </tr>
            <tr v-for="l in g.lines" :key="l.key">
              <td :title="l.key">{{ l.name ?? l.key }}</td>
              <td :title="basis(l.costBasis)">{{ shownAmount(l) }}</td>
              <td :title="basis(l.tokensBasis)">{{ tokens(l.inputTokens) }} / {{ tokens(l.outputTokens) }}</td>
              <td :title="basis(l.sessionsBasis)">{{ sessionsText(l) }}</td>
            </tr>
          </table>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.costs-panel {
  top: 90px;
  right: 24px;
  width: min(560px, calc(100vw - 48px));
  max-height: calc(100vh - 170px);
  z-index: 24;
}
.costs {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.costs th {
  text-align: left;
  color: var(--muted);
  font-weight: 400;
}
.costs td,
.costs th {
  padding: 2px 6px 2px 0;
  vertical-align: top;
}
.costs td:nth-child(n + 2) {
  font-family: ui-monospace, monospace;
  white-space: nowrap;
}
.costs.pick tr:not(:first-child) {
  cursor: pointer;
}
.costs.pick tr.on td {
  color: var(--cyan);
}
.warn {
  color: var(--amber);
}
</style>
