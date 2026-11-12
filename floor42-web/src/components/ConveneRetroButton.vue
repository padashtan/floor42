<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api/client'
import type { RetroOffer } from '../api/types'
import { t, tr } from '../i18n'
import { confirmed } from '../state/confirm'
import { conveneRetro } from '../state/office'

/**
 * "Convene the retrospective" for a closed mission that has none (ADR-0054), for instance one archived at once and
 * restored. Its sessions are paid: they start only on this click, after a confirmation. When the daemon says it cannot
 * be convened (retrospectives off, the workflow holds none, nobody worked on it), the button is absent and the reason is
 * shown; a mission that already had its retrospective shows nothing.
 */
const props = defineProps<{ mission: { id: string; status: string; archived: boolean } }>()

/** Reasons worth saying: the others mean there is nothing to convene (it is open, or it had its retrospective). */
const SAID = ['RETRO_DISABLED', 'RETRO_NOT_IN_WORKFLOW', 'NO_PARTICIPANTS']

const closed = computed(
  () => (props.mission.status === 'COMPLETED' || props.mission.status === 'CANCELLED') && !props.mission.archived,
)
const offer = ref<RetroOffer | null>(null)
const busy = ref(false)

async function load() {
  offer.value = null
  if (!closed.value) return
  try {
    offer.value = await api.retroOffer(props.mission.id)
  } catch {
    offer.value = null
  }
}
watch(() => [props.mission.id, props.mission.status, props.mission.archived], load, { immediate: true })

async function convene() {
  if (!(await confirmed({ message: t('retro.conveneConfirm', { id: props.mission.id }), confirmLabel: t('retro.convene') }))) return
  busy.value = true
  try {
    await conveneRetro(props.mission.id)
    await load()
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <template v-if="offer">
    <button v-if="offer.available" class="action convene-retro" :disabled="busy" :title="t('retro.conveneTitle')" @click="convene">
      {{ t('retro.convene') }}
    </button>
    <div v-else-if="offer.code && SAID.includes(offer.code)" class="hint convene-retro-why">
      {{ t('retro.conveneAbsent', { why: tr(`refusal.${offer.code}`, offer.code) }) }}
    </div>
  </template>
</template>
