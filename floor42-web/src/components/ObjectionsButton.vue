<script setup lang="ts">
import { t } from '../i18n'
import { setPanel } from '../state/openPanels'

/** "Object" beside "Accept the delivery" (ADR-0060): opens the Objections panel (ObjectionsPanel.vue). */
defineProps<{ disabled: boolean }>()
</script>

<template>
  <button class="action object" :disabled="disabled" :title="t('objections.openTitle')" @click="setPanel('objections', true)">
    {{ t('objections.open') }}
  </button>
</template>
