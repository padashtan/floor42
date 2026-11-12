<script setup lang="ts">
// cost-overview: the top bar's spend indicator. What the real mission on the board has spent against its cap, as
// Claude Code estimates it (never a bill), plus apart the caps of the sessions that reported no cost (an upper
// bound); a click opens the costs panel. Without a real mission, only the way in.
import { computed } from 'vue'
import { t } from '../i18n'
import { office } from '../state/office'
import { counted, spendLevel, usd } from '../ui/costs'

const emit = defineEmits<{ open: [] }>()

const spend = computed(() => {
  const m = office.mission
  if (!m || m.origin !== 'REAL') return null
  const spent = usd(m.spentUsdEstimated)
  // Sessions that reported no cost count for their cap: shown apart (≤), never folded into the estimate.
  const bounded = m.sessionsBounded > 0
  const bound = usd(m.spentUsdBound ?? 0)
  const more = bounded ? ' ' + t('cost.boundTitle', { bound, bounded: m.sessionsBounded }) : ''
  const level = spendLevel(counted(m), m.budgetUsd)
  if (m.budgetUsd !== null) {
    const cap = usd(m.budgetUsd)
    return {
      level,
      text: bounded ? t('cost.spendBounded', { spent, bound, cap }) : t('cost.spend', { spent, cap }),
      title: t('cost.spendTitle', { mission: m.id, spent, cap }) + more,
    }
  }
  return m.capDecided
    ? {
        level,
        text: bounded ? t('cost.spendBoundedNoCap', { spent, bound }) : t('cost.spendNoCap', { spent }),
        title: t('cost.spendNoCapTitle', { mission: m.id, spent }) + more,
      }
    : {
        level: 'undecided',
        text: bounded ? t('cost.spendBoundedUndecided', { spent, bound }) : t('cost.spendUndecided', { spent }),
        title: t('cost.spendUndecidedTitle', { mission: m.id, spent }) + more,
      }
})
</script>

<template>
  <button v-if="spend" class="spend" :class="spend.level" :title="spend.title" @click="emit('open')">{{ spend.text }}</button>
  <button v-else class="spend" :title="t('cost.topTitle')" @click="emit('open')">{{ t('cost.top') }}</button>
</template>

<style scoped>
.spend {
  padding: 1px 6px;
  font-family: ui-monospace, monospace;
  white-space: nowrap;
}
.spend.ok {
  color: var(--green);
}
.spend.near,
.spend.uncapped,
.spend.undecided {
  color: var(--amber);
}
.spend.over {
  color: var(--red);
  border-color: var(--red);
}
</style>
