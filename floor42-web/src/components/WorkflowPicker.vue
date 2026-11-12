<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { WorkflowsView } from '../api/types'
import { t } from '../i18n'
import { stepLine } from '../ui/workflows'

/**
 * Which workflow a new mission pins (ADR-0050): Floor42's own, or one of the company's. A workflow this version of
 * Floor42 cannot read is shown but cannot be picked.
 */
const model = defineModel<string | null>({ required: true })
const list = ref<WorkflowsView | null>(null)
const failed = ref(false)

onMounted(async () => {
  try {
    list.value = await api.workflows()
  } catch {
    // Handled: said beside the form; the mission then follows Floor42's own workflow.
    failed.value = true
  }
})

const chosen = computed(() => list.value?.workflows.find((w) => w.name === model.value)?.workflow ?? list.value?.builtIn ?? null)
</script>

<template>
  <div v-if="failed" class="hint warn">{{ t('workflows.loadFailed') }}</div>
  <label v-if="list && list.workflows.length" class="hint workflow-picker">
    {{ t('workflows.pick') }}
    <select :value="model ?? ''" @change="model = ($event.target as HTMLSelectElement).value || null">
      <option value="">{{ t('workflows.builtIn') }}</option>
      <option v-for="w in list.workflows" :key="w.name" :value="w.name" :disabled="!w.workflow">
        {{ w.name }}{{ w.workflow ? '' : ` (${t('workflows.unreadable')})` }}
      </option>
    </select>
    <span v-if="chosen"> · {{ stepLine(chosen, t) }}</span>
  </label>
</template>
