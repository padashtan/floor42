<script setup lang="ts">
// An agent's profile: its latest identity token and trust chain (IAM mode). Absent while IAM mode is off.
import { onMounted, ref, watch } from 'vue'
import type { TokenView } from '../api/identity'
import { request } from '../api/request'
import { t } from '../i18n'
import { iamOn } from '../ui/identities'
import TokenCard from './TokenCard.vue'

const props = defineProps<{ agent: string }>()
const latest = ref<TokenView | null>(null)

async function load() {
  latest.value = null
  if (!(await iamOn())) return
  try {
    const all = await request<TokenView[]>('GET', '/api/iam/tokens')
    latest.value = all.filter((x) => x.agent === props.agent).at(-1) ?? null
  } catch {
    // Unreachable: the profile shows nothing about identity.
    latest.value = null
  }
}
onMounted(load)
watch(() => props.agent, load)
</script>

<template>
  <template v-if="latest">
    <div class="sub">{{ t('idn.tokens') }}</div>
    <TokenCard :token="latest" />
  </template>
</template>
