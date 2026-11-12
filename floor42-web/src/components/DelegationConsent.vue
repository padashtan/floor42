<script setup lang="ts">
// IAM mode: what the director delegated to a mission's agents (given at the approval, see PlanCap), and the button that
// takes it back. Absent while IAM mode is off or the mission has no delegation.
import { onMounted, ref, watch } from 'vue'
import type { DelegationView } from '../api/identity'
import { request } from '../api/request'
import { dateLocale, t } from '../i18n'
import { confirmed } from '../state/confirm'
import { iamOn } from '../ui/identities'

const props = defineProps<{ mission: string }>()
const delegation = ref<DelegationView | null>(null)
const error = ref<string | null>(null)
const path = () => `/api/iam/missions/${encodeURIComponent(props.mission)}/delegation`

async function load() {
  delegation.value = null
  if (!(await iamOn())) return
  // None granted (404): nothing to show.
  delegation.value = await request<DelegationView>('GET', path()).catch(() => null)
}

async function revoke() {
  if (!(await confirmed({ message: t('idn.deleg.confirm'), confirmLabel: t('idn.deleg.revoke'), danger: true }))) return
  try {
    delegation.value = await request<DelegationView>('POST', `${path()}/revoke`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

onMounted(load)
watch(() => props.mission, load)
</script>

<template>
  <div v-if="delegation" class="hint delegation" :class="{ revoked: delegation.revoked }" data-testid="delegation">
    <template v-if="delegation.revoked">{{ t('idn.deleg.revoked') }}</template>
    <template v-else>
      {{ t('idn.deleg.line', { until: new Date(delegation.expiresAt).toLocaleDateString(dateLocale()) }) }}
      <button class="small" type="button" @click="revoke">{{ t('idn.deleg.revoke') }}</button>
    </template>
    <span v-if="error" class="bad"> {{ error }}</span>
  </div>
</template>
