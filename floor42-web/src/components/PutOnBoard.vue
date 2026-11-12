<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { computed } from 'vue'
import { t } from '../i18n'
import { putOnBoard } from '../state/office'
import { waits } from '../state/waits'
import { todoRows } from '../ui/todo'

/** An empty board while a plan waits for approval: say so, with the one action that puts that mission on the board. */
const waiting = computed(() => todoRows(waits.missions ?? [], [], nowMs()).find((r) => r.go.panel === 'plan')?.mission ?? null)
</script>

<template>
  <div v-if="waiting" class="empty-state put-on-board">
    {{ t('board.planWaits', { mission: waiting }) }}
    <button class="action" @click="putOnBoard(waiting)">{{ t('board.putOn', { mission: waiting }) }}</button>
  </div>
  <slot v-else />
</template>

<style scoped>
.put-on-board .action {
  margin-left: 10px;
}
</style>
