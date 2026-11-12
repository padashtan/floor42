<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { computed } from 'vue'
import { t } from '../i18n'
import { office } from '../state/office'
import { waits } from '../state/waits'
import { elsewhere, goLabel, todoRows } from '../ui/todo'
import { openTodoGo } from '../ui/todoGo'

/** What waits in missions other than the board's (a plan, proposed tickets), from the same source as the To do panel. */
const rows = computed(() => elsewhere(todoRows(waits.missions ?? [], [], nowMs()), office.mission?.id ?? null))
</script>

<template>
  <template v-if="rows.length">
    <div class="sub">{{ t('desk.elsewhere') }}</div>
    <div v-for="r in rows" :key="r.key" class="row elsewhere">
      <span class="text">{{ r.mission }} · {{ r.text }}</span>
      <button class="action" @click="openTodoGo(r.go, () => {})">{{ t(goLabel(r)) }}</button>
    </div>
  </template>
</template>
