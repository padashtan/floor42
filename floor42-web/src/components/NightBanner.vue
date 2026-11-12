<script setup lang="ts">
import { computed } from 'vue'
import type { NightView } from '../api/types'
import { t, dateLocale } from '../i18n'
import { phrased } from '../i18n/events'

/** The office closed for the night: why (the plan's quota, measured by Claude Code), and when it opens again. */
const props = defineProps<{ night: NightView }>()

const resumesAt = computed(() => {
  const at = props.night.resumesAt
  if (!at) return null
  const d = new Date(at)
  const today = new Date().toDateString() === d.toDateString()
  const time = d.toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' })
  return today ? time : `${d.toLocaleDateString(dateLocale(), { weekday: 'long' })} ${time}`
})
const reason = computed(() => {
  const s = phrased('night', props.night.code, { label: props.night.label }, props.night.reason)
  return s.charAt(0).toUpperCase() + s.slice(1)
})
</script>

<template>
  <div class="night-banner">
    <div class="moon">☾</div>
    <div>
      <b>{{ t('night.goodNight') }}</b> {{ t('night.wentHome', { reason }) }}
      <span v-if="resumesAt">{{ t('night.backAt', { time: resumesAt }) }}</span>
      <span v-else>{{ t('night.unknown') }}</span>
    </div>
  </div>
</template>
