<script setup lang="ts">
import { computed } from 'vue'
import type { CostBound } from '../api/types'
import { t } from '../i18n'

/** The caps of the sessions that reported no cost, said apart from the estimate; nothing when there is none. */
const props = defineProps<{ bound: CostBound }>()
const text = computed(() => {
  const v = props.bound.costUsd.value
  return props.bound.sessions > 0 && v !== null ? t('report.bound', { usd: v.toFixed(v < 1 ? 4 : 2), count: props.bound.sessions }) : ''
})
</script>

<template>
  <span v-if="text" class="hint">{{ text }}</span>
</template>
