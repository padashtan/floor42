<script setup lang="ts">
// The board title bar: until when the mission's delegation holds. Nothing while IAM mode is off or none was granted.
import { nowMs } from '../ui/clock'
import { ref, watch } from 'vue'
import type { DelegationView } from '../api/identity'
import { request } from '../api/request'
import { dateLocale, t } from '../i18n'
import { iamOn } from '../ui/identities'
import { DELEGATION_LEAD_MS } from '../ui/iamEndings'
import { ending, when } from '../ui/sessionTime'

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
const time = () => when(until.value ?? 0, dateLocale(), nowMs())
</script>

<template>
  <span
    v-if="until !== null"
    class="session-time"
    :class="{ ending: ending(until, nowMs(), DELEGATION_LEAD_MS) }"
    :title="t('idn.deleg.untilTitle', { time: time() })"
    >{{ t('idn.deleg.until', { time: time() }) }}</span
  >
</template>
