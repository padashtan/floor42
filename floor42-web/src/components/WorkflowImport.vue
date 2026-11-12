<script setup lang="ts">
import { ref } from 'vue'
import { api, ApiError } from '../api/client'
import type { ImportProblemView, WorkflowView } from '../api/types'
import { t } from '../i18n'
import { readFileText } from '../ui/workflowFile'

/**
 * "Import a file" (ADR-0052): the chosen file is sent to the daemon as text, which checks it in full and answers the
 * workflow or every problem by code. Nothing is saved here and nothing in the file is run: the workflow opens in the
 * editor for the director to read before saving.
 */
defineProps<{ busy: boolean }>()
const emit = defineEmits<{ imported: [WorkflowView]; problems: [ImportProblemView[]]; failed: [] }>()

const input = ref<HTMLInputElement>()

async function chosen(event: Event) {
  const field = event.target as HTMLInputElement
  const file = field.files?.[0]
  field.value = ''
  if (!file) return
  try {
    const result = await api.importWorkflow(await readFileText(file))
    if (result.workflow) emit('imported', result.workflow)
    else emit('problems', result.problems)
  } catch (e) {
    // Handled: a file past the daemon's cap is a problem of the file; anything else is the panel's general error.
    if (e instanceof ApiError && e.code === 'IMPORT_TOO_LARGE') emit('problems', [{ code: e.code, subject: null }])
    else emit('failed')
  }
}
</script>

<template>
  <button class="action lib-import" :disabled="busy" @click="input?.click()">{{ t('workflows.lib.import') }}</button>
  <input
    ref="input"
    class="lib-file"
    type="file"
    accept=".json,application/json"
    tabindex="-1"
    :aria-label="t('workflows.lib.import')"
    hidden
    @change="chosen"
  />
</template>
