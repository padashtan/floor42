<script setup lang="ts">
import { ref } from 'vue'
import { ApiError } from '../api/client'
import { askBackApi } from '../api/askBack'
import { t } from '../i18n'
import { refusalText } from '../ui/refusal'
import { why } from '../ui/why'

/**
 * The director asks an agent back instead of answering (ADR-0059). It says what it costs (one more paid turn of the
 * agent's session, within its cap), that the agent reads the words as data, and, when the session no longer waits,
 * the paths that remain. The event stream refreshes the desk once the question is withdrawn.
 */
const props = defineProps<{ question: string; agent: string }>()
const emit = defineEmits<{ close: [] }>()

const text = ref('')
const busy = ref(false)
const gone = ref(false)
const error = ref('')

async function send() {
  busy.value = true
  error.value = ''
  try {
    await askBackApi.send(props.question, text.value)
    emit('close')
  } catch (e) {
    if (e instanceof ApiError && e.code === 'SESSION_GONE') gone.value = true
    else error.value = e instanceof ApiError ? refusalText(e.code, e.message) : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="ask-back" data-ask-back @submit.prevent="send">
    <div class="hint">{{ t('askBack.cost', { agent }) }}</div>
    <textarea
      v-model="text"
      rows="3"
      maxlength="4000"
      :placeholder="t('askBack.placeholder', { agent })"
      :aria-label="t('askBack.placeholder', { agent })"
      :disabled="busy || gone"
      @keydown.ctrl.enter.prevent="send"
    ></textarea>
    <div v-if="gone" class="warn" data-ask-back-gone>{{ t('askBack.gone', { agent }) }}</div>
    <div v-if="error" class="warn">{{ error }}</div>
    <div class="options">
      <button class="action" type="submit" :disabled="busy || gone || !text.trim()" :title="why([!text.trim(), t('why.needText')])">
        {{ t('askBack.send', { agent }) }}
      </button>
      <button class="action" type="button" @click="emit('close')">{{ gone ? t('askBack.close') : t('askBack.cancel') }}</button>
    </div>
  </form>
</template>

<style>
.ask-back {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 6px;
  padding: 6px 8px;
  border-left: 2px solid var(--cyan);
}
</style>
