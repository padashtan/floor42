<script setup lang="ts">
import { computed, ref } from 'vue'
import { t } from '../i18n'
import { defaultNote } from '../i18n/events'
import { confirmed } from '../state/confirm'
import { cancelMission } from '../state/office'

/** Abandons the mission once the director confirms: the same question and call as the desk's Abandon. */
const props = defineProps<{ mission: { id: string; title: string; status: string } }>()

const cancellable = computed(() => !['COMPLETED', 'CANCELLED'].includes(props.mission.status))
const busy = ref(false)

async function abandon() {
  const m = props.mission
  if (!(await confirmed({ message: t('mission.abandonConfirm', { id: m.id, title: m.title }), confirmLabel: t('confirm.abandon'), danger: true }))) return
  busy.value = true
  try {
    await cancelMission(m.id, defaultNote('note.abandoned'))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <button v-if="cancellable" class="action abandon-mission" :disabled="busy" @click="abandon">
    {{ t('mission.abandon') }}
  </button>
</template>
