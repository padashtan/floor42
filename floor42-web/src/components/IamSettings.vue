<script setup lang="ts">
// The IAM section of the settings: the mode, its state, and the few values it needs. Saved for the next start of the
// daemon (the answer says so); the two Keycloak secrets are write only and never shown again.
import { onMounted, ref } from 'vue'
import type { IamSettingsAnswer, IamSettingsRequest, IamStatusView } from '../api/identity'
import { request } from '../api/request'
import { t } from '../i18n'
import IamChecks from './IamChecks.vue'
import IamSecretReplace from './IamSecretReplace.vue'

const status = ref<IamStatusView | null>(null)
const enabled = ref(false)
const issuer = ref('')
const hours = ref(8)
const daemonSecret = ref('')
const apiSecret = ref('')
const saved = ref(false)
/** A save went through: said even when nothing needs a restart. */
const savedOnce = ref(false)
const error = ref<string | null>(null)
const busy = ref(false)

async function load() {
  try {
    const s = await request<IamStatusView>('GET', '/api/iam/status')
    status.value = s
    enabled.value = s.savedEnabled
    issuer.value = s.issuer
    hours.value = s.maxTokenHours
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function save() {
  busy.value = true
  error.value = null
  try {
    const body: IamSettingsRequest = {
      enabled: enabled.value,
      issuer: issuer.value.trim(),
      maxTokenHours: hours.value,
      daemonSecret: daemonSecret.value,
      apiSecret: apiSecret.value,
    }
    saved.value = (await request<IamSettingsAnswer>('PUT', '/api/iam/settings', body)).restartNeeded
    savedOnce.value = true
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    daemonSecret.value = ''
    apiSecret.value = ''
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="settings-block iam-settings" data-setting="iam">
    <div class="name">{{ t('idn.set.title') }}</div>
    <div class="line">{{ t('idn.set.line') }}</div>
    <IamChecks v-if="status" :status="status" />
    <template v-if="status">
      <IamSecretReplace :status="status" kind="api" @saved="load" />
      <IamSecretReplace :status="status" kind="daemon" @saved="load" />
    </template>
    <form class="iam-form" @submit.prevent="save">
      <label class="choice"><input v-model="enabled" type="checkbox" name="iam-enabled" /> {{ t('idn.set.enabled') }}</label>
      <label>{{ t('idn.set.issuer') }} <input v-model="issuer" type="url" name="iam-issuer" required /></label>
      <label>{{ t('idn.set.hours') }} <input v-model.number="hours" type="number" min="1" max="8" name="iam-hours" /></label>
      <label>{{ t('idn.set.daemonSecret') }} <input v-model="daemonSecret" type="password" autocomplete="off" name="iam-daemon-secret" /></label>
      <label>{{ t('idn.set.apiSecret') }} <input v-model="apiSecret" type="password" autocomplete="off" name="iam-api-secret" /></label>
      <div class="line">{{ t('idn.set.secretKept') }}</div>
      <button class="action" type="submit" :disabled="busy">{{ t('idn.set.save') }}</button>
    </form>
    <div v-if="saved || status?.restartNeeded" class="line warn restart-needed" role="status">
      {{ saved ? t('idn.set.restart') : t('idn.set.restartNeeded') }}
    </div>
    <div v-else-if="savedOnce" class="line saved-ok" role="status">{{ t('idn.set.savedNoRestart') }}</div>
    <div v-if="error" class="error">{{ error }}</div>
  </div>
</template>

<style>
.iam-settings .iam-form {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 460px;
}
.iam-settings .iam-form label {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.iam-settings .iam-form label.choice {
  flex-direction: row;
  gap: 6px;
}
</style>
