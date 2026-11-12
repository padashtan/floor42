<script setup lang="ts">
import { t } from '../i18n'
import { dismiss, office } from '../state/office'
import { setPanel } from '../state/openPanels'

defineEmits<{ answer: [question: string] }>()
</script>

<template>
  <div class="toasts" role="status" aria-live="polite">
    <div v-for="toast in office.toasts" :key="toast.id" class="panel toast" :role="toast.error ? 'alert' : undefined">
      <span>{{ toast.text }}</span>
      <button v-if="toast.question" class="action" @click="$emit('answer', toast.question); dismiss(toast.id)">{{ t('toast.answer') }}</button>
      <button v-else-if="toast.panel" class="action" @click="setPanel(toast.panel, true); dismiss(toast.id)">{{ t('ux.toast.open') }}</button>
      <a v-else-if="toast.link" class="action" :href="toast.link.href">{{ toast.link.label }}</a>
      <button class="close" :aria-label="t('toast.close')" @click="dismiss(toast.id)">×</button>
    </div>
  </div>
</template>
