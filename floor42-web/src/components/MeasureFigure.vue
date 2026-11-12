<script setup lang="ts">
import { computed } from 'vue'
import type { Measure } from '../api/types'
import { t } from '../i18n'
import { money, tagKey, time } from '../ui/missionReport'

/**
 * One figure of the end-of-mission report with its origin beside it (ADR-0039): a short tag (measured, estimated,
 * reported, simulated) whose tooltip says the whole basis. A figure nobody reported reads "not reported", never zero.
 */
const props = defineProps<{ measure: Measure; kind: 'money' | 'time' }>()

const shown = computed(() => (props.kind === 'money' ? money(props.measure) : time(props.measure)))
</script>

<template>
  <span class="figure">
    <span class="value">{{ shown.value ?? t('report.notReported') }}</span>
    <span class="origin" :class="measure.basis.toLowerCase()" :title="t(shown.basisKey)">{{ t(tagKey(shown) as never) }}</span>
  </span>
</template>

<style>
.figure {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}
.figure .origin {
  font-size: 12px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  border: 1px solid #1d4a57;
  color: var(--muted);
  padding: 0 5px;
}
.figure .origin.estimated {
  color: var(--amber);
  border-color: var(--amber);
}
.figure .origin.measured {
  color: var(--green);
  border-color: var(--green);
}
.figure .origin.simulated {
  color: var(--cyan);
  border-color: var(--cyan);
  border-style: dashed;
}
</style>
