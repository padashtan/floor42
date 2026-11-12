<script setup lang="ts">
// The board title bar: until when the mission's delegation holds. Nothing while IAM mode is off or none was granted.
import { nowMs } from '../ui/clock'
import { ref, watch } from 'vue'
import type { DelegationView } from '../api/identity'
import { request } from '../api/request'
import { dateLocale, t } from '../i18n'
import { iamOn } from '../ui/identities'
import { clock, ending } from '../ui/sessionTime'

const props = defineProps<{ mission: string }>()
const until = ref<number | null>(null)

watch(
  () => props.mission,
  async (id) => {
    until.value = null
    if (!(await iamOn())) return
    const d = await request<DelegationView>('GET', `/api/iam/missions/${encodeURIComponent(id)}/delegation`).catch(() => null)
    until.value = d && !d.revoked ? Date.parse(d.expiresAt) : null
  },
  { immediate: true },
)
const time = () => clock(until.value ?? 0, dateLocale())
</script>

<template>
  <span
    v-if="until !== null"
    class="session-time"
    :class="{ ending: ending(until, nowMs()) }"
    :title="t('idn.deleg.untilTitle', { time: time() })"
    >{{ t('idn.deleg.until', { time: time() }) }}</span
  >
</template>
