<script setup lang="ts">
import { t } from '../i18n'
import { stamp } from '../ui/when'

/** Says at the top of the board that the mission was abandoned, when and why; its open tickets read as dropped. */
defineProps<{ mission: { status: string; closedAt: string | null; outcome: string | null } }>()
</script>

<template>
  <div v-if="mission.status === 'CANCELLED'" class="delivery abandoned" role="status">
    <div class="delivery-head">
      {{ t('board.abandoned', { date: mission.closedAt ? stamp(mission.closedAt) : '?', reason: mission.outcome ?? '' }) }}
    </div>
    <div class="hint">{{ t('board.abandonedHint') }}</div>
  </div>
</template>
