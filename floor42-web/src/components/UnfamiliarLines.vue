<script setup lang="ts">
import { computed, ref } from 'vue'
import { api, ApiError } from '../api/client'
import { t } from '../i18n'
import { office } from '../state/office'

/**
 * Kinds of stream line Claude Code sent that Floor42 does not know, each with its shape (keys and value types, never
 * the values). The director accepts a kind: Floor42 then records its lines in the session, what looks like a secret
 * masked, and stops warning. No code to change.
 */
const runtime = computed(() => office.status?.runtime)
const busy = ref(false)
const error = ref<string | null>(null)

async function accept(kind: string) {
  busy.value = true
  error.value = null
  try {
    const current = (await api.settings()).find((s) => s.key === 'ACCEPTED_STREAM_LINES')?.value ?? ''
    const kinds = [...current.split(',').map((k) => k.trim()).filter((k) => k), kind]
    await api.changeSetting('ACCEPTED_STREAM_LINES', [...new Set(kinds)].join(','))
    office.status = await api.status()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div v-if="runtime?.unfamiliar?.length" class="hint warn">
    {{ t('mission.unfamiliar', { version: runtime.version ?? '' }) }}
    <div v-for="u in runtime.unfamiliar" :key="u.kind" class="line">
      <b>{{ u.kind }}</b>
      <code>{{ u.shape }}</code>
      <button class="action" :disabled="busy" :title="t('mission.acceptLineHint')" @click="accept(u.kind)">
        {{ t('mission.acceptLine') }}
      </button>
    </div>
    <div v-if="error">{{ error }}</div>
  </div>
</template>

<style scoped>
.line {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: center;
  margin-top: 4px;
}
code {
  word-break: break-all;
}
</style>
