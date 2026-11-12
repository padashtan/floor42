<script setup lang="ts">
import { computed, ref } from 'vue'
import { t } from '../i18n'
import { confirmed } from '../state/confirm'
import { offerAfterArchive } from '../state/afterArchive'
import { archiveMission } from '../state/office'

/**
 * Archives a closed mission once the director confirms (ADR-0054): offered only when it is completed or abandoned and
 * not archived yet. The daemon checks again (a session still running, a retrospective under way) and says why it refuses.
 */
const props = defineProps<{ mission: { id: string; title: string; status: string; archived: boolean } }>()

const closed = computed(
  () => (props.mission.status === 'COMPLETED' || props.mission.status === 'CANCELLED') && !props.mission.archived,
)
const busy = ref(false)

async function archive() {
  const m = props.mission
  if (!(await confirmed({ message: t('mission.archiveConfirm', { id: m.id, title: m.title }), confirmLabel: t('confirm.archive') }))) return
  busy.value = true
  try {
    if (await archiveMission(m.id)) offerAfterArchive(m.id)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <button v-if="closed" class="action archive-mission" :disabled="busy" :title="t('mission.archiveTitle')" @click="archive">
    {{ t('mission.archive') }}
  </button>
</template>
