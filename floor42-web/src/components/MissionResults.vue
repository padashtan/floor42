<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { t, tr } from '../i18n'
import { why } from '../ui/why'
import { phrased } from '../i18n/events'
import { configureChecks, office, setBudget } from '../state/office'

const mission = computed(() => office.mission)
const open = computed(() => mission.value && !['COMPLETED', 'CANCELLED'].includes(mission.value.status))
const real = computed(() => mission.value?.origin === 'REAL' && mission.value.workspace)

const command = ref('')
const automatic = ref(false)
const budget = ref('')
const busy = ref(false)

// The form follows the mission on the board, and what the daemon recorded after each change.
watch(
  () => [mission.value?.id, mission.value?.checkCommand, mission.value?.checksAutomatic, mission.value?.budgetUsd],
  () => {
    command.value = mission.value?.checkCommand ?? ''
    automatic.value = mission.value?.checksAutomatic ?? false
    budget.value = mission.value?.budgetUsd == null ? '' : String(mission.value.budgetUsd)
  },
  { immediate: true },
)

const budgetValue = computed(() => {
  const v = budget.value.trim().replace(',', '.')
  if (v === '') return null
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : NaN
})

async function run(action: () => Promise<boolean>) {
  busy.value = true
  try {
    await action()
  } finally {
    busy.value = false
  }
}

function basisClass(basis: string) {
  if (basis.startsWith('measured')) return 'measured'
  if (basis.startsWith('estimated')) return 'estimated'
  if (basis === 'unavailable') return 'unavailable'
  return 'reported'
}
</script>

<template>
  <template v-if="mission">
    <div class="sub">{{ t('results.title') }} · {{ mission.id }}</div>
    <table role="presentation" class="scorecard">
      <tr v-for="l in mission.scorecard" :key="l.label">
        <td class="label">{{ tr(`score.${l.label}`, l.label) }}</td>
        <td>{{ phrased('scorev', l.code, l.params, l.value) }}</td>
        <td class="basis" :class="basisClass(l.basis)">{{ tr(`basis.${l.basis}`, l.basis) }}</td>
      </tr>
    </table>

    <template v-if="real && open">
      <div class="sub">{{ t('results.checks') }}</div>
      <div class="row">
        <input v-model="command" :placeholder="t('results.checksPlaceholder')" :aria-label="t('results.checksPlaceholder')" />
        <button
          class="action"
          :disabled="busy || (automatic && !command.trim())"
          :title="why([automatic && !command.trim(), t('ux.why.needCommand')])"
          @click="run(() => configureChecks(mission!.id, command, automatic))"
        >
          {{ t('common.save') }}
        </button>
      </div>
      <label class="hint"><input v-model="automatic" type="checkbox" /> {{ t('results.automatic') }}</label>
      <div class="hint warn">{{ t('results.checksWarning') }}</div>

      <div class="sub">{{ t('results.budget') }}</div>
      <div class="row">
        <input v-model="budget" :placeholder="t('results.budgetPlaceholder')" :aria-label="t('results.budgetPlaceholder')" />
        <button
          class="action"
          :disabled="busy || Number.isNaN(budgetValue)"
          :title="why([Number.isNaN(budgetValue), t('ux.why.badAmount')])"
          @click="run(() => setBudget(mission!.id, budgetValue))"
        >
          {{ t('common.save') }}
        </button>
      </div>
      <div class="hint">{{ t('results.spent', { usd: mission.spentUsdEstimated.toFixed(4) }) }}</div>
      <div v-if="mission.sessionsBounded > 0" class="hint">
        {{ t('results.bound', { usd: (mission.spentUsdBound ?? 0).toFixed(4), count: mission.sessionsBounded }) }}
      </div>
    </template>
  </template>
</template>
