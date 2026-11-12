<script setup lang="ts">
// Re-enter one Keycloak client secret (floor42-api or floor42-daemon). The value lives in the input only until the
// request is sent: it is cleared before the answer is read, whatever the answer. Never logged, never shown back.
import { ref } from 'vue'
import type { IamSecretRequest, IamSettingsAnswer, IamStatusView } from '../api/identity'
import { request } from '../api/request'
import { t } from '../i18n'

const props = defineProps<{ status: IamStatusView; kind: 'api' | 'daemon' }>()
const emit = defineEmits<{ saved: [] }>()

const open = ref(false)
const secret = ref('')
const busy = ref(false)
const error = ref<string | null>(null)
const result = ref<string | null>(null)
const refusedHint = ref(false)

async function save() {
  const value = secret.value
  secret.value = ''
  if (!value.trim()) return
  busy.value = true
  error.value = null
  const wasRefused = props.status.code === 'IAM_API_CREDENTIAL_REFUSED' || props.status.code === 'IAM_API_SECRET_MISSING'
  try {
    // A route of its own: it writes the vault only, the saved settings are not touched.
    const body: IamSecretRequest = { secret: value }
    const answer = await request<IamSettingsAnswer>('PUT', `/api/iam/secrets/${props.kind}`, body)
    result.value = answer.restartNeeded ? t('idn.rep.savedRestart') : t('idn.rep.saved')
    refusedHint.value = wasRefused && props.kind === 'api'
    open.value = false
    emit('saved')
  } catch (e) {
    // The message is the server's own; the secret is not part of it.
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

function cancel() {
  secret.value = ''
  error.value = null
  open.value = false
}
</script>

<template>
  <div class="iam-replace" :data-kind="kind">
    <button v-if="!open" class="action" type="button" @click="open = true">{{ t(`idn.rep.open.${kind}`) }}</button>
    <form v-else class="iam-form" @submit.prevent="save">
      <label>
        {{ t(`idn.rep.label.${kind}`) }}
        <input v-model="secret" type="password" autocomplete="off" :spellcheck="false" :name="`iam-replace-${kind}`" required />
      </label>
      <div class="buttons">
        <button class="action" type="submit" :disabled="busy">{{ t('idn.rep.confirm') }}</button>
        <button class="action" type="button" :disabled="busy" @click="cancel">{{ t('idn.rep.cancel') }}</button>
      </div>
    </form>
    <div v-if="result" class="line ok" role="status">{{ result }}</div>
    <div v-if="result && refusedHint" class="line hint">{{ t('idn.rep.refusedHint') }}</div>
    <div v-if="error" class="error">{{ error }}</div>
  </div>
</template>

<style>
.iam-replace {
  margin: 6px 0;
}
.iam-replace .buttons {
  display: flex;
  gap: 8px;
}
</style>
