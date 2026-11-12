<script setup lang="ts">
// Offered once per browser, on the first signed-in page (ADR-0045): either answer is final here; the tutorial stays
// in the Work menu and the palette.
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { t } from '../i18n'
import { confirming } from '../state/confirm'
import { markOffered, startTutorial } from '../state/tutorial'

const start = ref<HTMLButtonElement>()
// Escape is "later": the offer is final either way, and the key does not reach the panels behind it.
function key(e: KeyboardEvent) {
  // An open confirmation is above the offer: its Escape is its own.
  if (e.key !== 'Escape' || confirming()) return
  e.stopPropagation()
  markOffered()
}
onMounted(() => {
  start.value?.focus()
  window.addEventListener('keydown', key, true)
})
onBeforeUnmount(() => window.removeEventListener('keydown', key, true))
</script>

<template>
  <section class="panel tutorial-offer" role="dialog" aria-modal="false" aria-labelledby="tutorial-offer-title">
    <div id="tutorial-offer-title" class="title">{{ t('tutorial.offer.title') }}</div>
    <p>{{ t('tutorial.offer.body') }}</p>
    <div class="buttons">
      <button ref="start" class="action recommended" @click="startTutorial">{{ t('tutorial.offer.start') }}</button>
      <button class="action" @click="markOffered">{{ t('tutorial.offer.later') }}</button>
    </div>
  </section>
</template>

<style scoped>
.tutorial-offer {
  position: fixed;
  z-index: 61;
  left: 24px;
  bottom: 56px;
  width: min(340px, calc(100vw - 48px));
}
.buttons {
  display: flex;
  gap: 6px;
  margin-top: 8px;
}
</style>
