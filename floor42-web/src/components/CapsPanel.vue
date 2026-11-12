<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { SettingView } from '../api/types'
import { t } from '../i18n'
import { changeSetting, office, setBudget } from '../state/office'
import { why } from '../ui/why'
import { counted } from '../ui/costs'

/**
 * Cost caps (ADR-0025): the most one session may spend, and the budget of the mission on screen. Claude Code enforces
 * the session cap itself; Floor42 holds the next session once a mission's budget is spent. Spent is Claude Code's
 * estimate, never a bill; a session that reported no cost counts for its cap, shown apart as an upper bound.
 */
const emit = defineEmits<{ close: [] }>()

const KEY = 'SESSION_BUDGET_USD'
const cap = ref<SettingView | null>(null)
const capDraft = ref('')
const budgetDraft = ref('')
const busy = ref(false)
const loadError = ref('')

const mission = computed(() => office.mission)
const left = computed(() => {
  const m = mission.value
  return m && m.budgetUsd !== null ? Math.max(0, m.budgetUsd - counted(m)) : null
})
/** What the next session of this mission would run under: the lower of the two. */
const effective = computed(() => {
  const c = Number(cap.value?.value ?? NaN)
  if (Number.isNaN(c)) return null
  return left.value === null ? c : Math.max(0.05, Math.min(c, left.value))
})

const usd = (n: number | null | undefined) => (n === null || n === undefined ? '—' : `${n.toFixed(2)} USD`)

async function load() {
  try {
    cap.value = (await api.settings()).find((s) => s.key === KEY) ?? null
    capDraft.value = cap.value?.value ?? ''
    loadError.value = ''
  } catch (e) {
    cap.value = null
    loadError.value = e instanceof Error ? e.message : String(e)
  }
  budgetDraft.value = mission.value?.budgetUsd?.toString() ?? ''
}

async function saveCap(value: string | null) {
  busy.value = true
  try {
    await changeSetting(KEY, value)
    await load()
  } finally {
    busy.value = false
  }
}

async function saveBudget(value: number | null) {
  if (!mission.value) return
  busy.value = true
  try {
    await setBudget(mission.value.id, value)
    budgetDraft.value = value?.toString() ?? ''
  } finally {
    busy.value = false
  }
}

onMounted(load)
watch(() => mission.value?.id, () => void (budgetDraft.value = mission.value?.budgetUsd?.toString() ?? ''))
</script>

<template>
  <div class="panel caps-panel framed">
    <div class="title">
      {{ t('caps.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll settings-form">
      <div class="hint">{{ t('caps.intro') }}</div>

      <div class="sub">{{ t('caps.session') }}</div>
      <div v-if="loadError" class="hint warn caps-load-error">
        {{ t('ux.loadFailed', { reason: loadError }) }}
        <button class="action" type="button" @click="load">{{ t('ux.retry') }}</button>
      </div>
      <div v-if="cap" class="setting">
        <div class="name">
          {{ usd(Number(cap.value)) }}
          <span class="facts"
            >{{ t('settings.default', { value: cap.defaultValue }) }}<template v-if="cap.changed">
              · {{ t('settings.changed') }}</template
            ></span
          >
        </div>
        <form class="row" @submit.prevent="saveCap(capDraft)">
          <input v-model="capDraft" type="number" min="0.05" max="100" step="0.05" :aria-label="t('settings.SESSION_BUDGET_USD')" />
          <button
            class="action"
            type="submit"
            :disabled="busy || capDraft === cap.value"
            :title="why([capDraft === cap.value, t('why.unchanged')])"
          >{{ t('settings.save') }}</button>
          <button v-if="cap.changed" class="action" type="button" :disabled="busy" @click="saveCap(null)">
            {{ t('settings.reset') }}
          </button>
        </form>
        <div class="hint">{{ t('caps.sessionHint') }}</div>
      </div>

      <div class="sub">{{ t('caps.mission') }}</div>
      <div v-if="!mission" class="hint">{{ t('caps.noMission') }}</div>
      <div v-else class="setting">
        <div class="name">{{ mission.title }}</div>
        <dl class="caps-facts">
          <dt>{{ t('caps.budget') }}</dt>
          <dd>{{ mission.budgetUsd === null ? t('caps.none') : usd(mission.budgetUsd) }}</dd>
          <dt>{{ t('caps.spent') }}</dt>
          <dd>{{ usd(mission.spentUsdEstimated) }}</dd>
          <template v-if="mission.sessionsBounded > 0">
            <dt>{{ t('caps.bound') }}</dt>
            <dd>{{ t('caps.boundValue', { usd: usd(mission.spentUsdBound ?? 0), count: mission.sessionsBounded }) }}</dd>
          </template>
          <dt>{{ t('caps.left') }}</dt>
          <dd>{{ left === null ? '—' : usd(left) }}</dd>
          <dt>{{ t('caps.next') }}</dt>
          <dd>
            <b>{{ usd(effective) }}</b>
          </dd>
        </dl>
        <form class="row" @submit.prevent="saveBudget(budgetDraft === '' ? null : Number(budgetDraft))">
          <input v-model="budgetDraft" type="number" min="0" step="0.5" :placeholder="t('caps.none')" :aria-label="t('caps.budget')" />
          <button class="action" type="submit" :disabled="busy">{{ t('settings.save') }}</button>
          <button v-if="mission.budgetUsd !== null" class="action" type="button" :disabled="busy" @click="saveBudget(null)">
            {{ t('caps.removeBudget') }}
          </button>
        </form>
        <div class="hint">{{ t('caps.missionHint') }}</div>
      </div>
    </div>
  </div>
</template>
