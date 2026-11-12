<script setup lang="ts">
// Unsafe mode (ADR-0063): off by default, shown in red when on. Switching on needs a reason, and every switch is journaled.
import { onMounted, ref } from 'vue'
import { request } from '../api/request'
import type { UnsafeModeRequest, UnsafeModeView } from '../api/unsafe'
import { t } from '../i18n'

const view = ref<UnsafeModeView | null>(null)
const reason = ref('')
const error = ref<string | null>(null)
const busy = ref(false)

async function send(on: boolean) {
  busy.value = true
  error.value = null
  try {
    const body: UnsafeModeRequest = { on, reason: on ? reason.value.trim() : null }
    view.value = await request<UnsafeModeView>('PUT', '/api/unsafe-mode', body)
    reason.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  try {
    view.value = await request<UnsafeModeView>('GET', '/api/unsafe-mode')
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
})
</script>

<template>
  <div class="settings-block unsafe-mode" data-setting="unsafe">
    <div class="name">{{ t('unsafe.title') }}</div>
    <div class="line">{{ t('unsafe.line') }}</div>
    <div v-if="view?.on" class="line unsafe-on" role="alert">{{ t('unsafe.warn') }}</div>
    <div v-if="view?.sandboxWorks === true" class="line">{{ t('unsafe.sandbox.ok') }}</div>
    <div v-else-if="view?.sandboxWorks === false" class="line warn">{{ t('unsafe.sandbox.ko') }}</div>
    <form v-if="view" class="unsafe-form" @submit.prevent="send(!view.on)">
      <label v-if="!view.on">{{ t('unsafe.reason') }} <input v-model="reason" type="text" name="unsafe-reason" required /></label>
      <button class="action" type="submit" :disabled="busy">{{ view.on ? t('unsafe.off') : t('unsafe.on') }}</button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>
  </div>
</template>

<style>
.unsafe-mode .unsafe-on {
  color: #fff;
  background: #b00020;
  padding: 6px 8px;
  font-weight: bold;
}
.unsafe-mode .unsafe-form {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 460px;
}
</style>
