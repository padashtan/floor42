<script setup lang="ts">
import { computed } from 'vue'
import type { MissionWorkflowView } from '../api/types'
import { t } from '../i18n'
import { followsKey } from '../ui/workflowRows'
import { stepLine } from '../ui/workflows'

/** The workflow a mission follows (ADR-0050): its name and steps, or why this version of Floor42 does not run it. */
const props = defineProps<{ workflow: MissionWorkflowView | null | undefined }>()

const followsLine = computed(() => {
  const w = props.workflow
  if (!w?.workflow) return ''
  return t(followsKey(w), { name: w.follows?.name ?? w.workflow.name, version: w.follows?.version ?? 0 })
})
</script>

<template>
  <div v-if="workflow" class="hint workflow-line">
    <template v-if="workflow.workflow">
      {{ followsLine }}
      · {{ stepLine(workflow.workflow, t) }}
    </template>
    <span v-else class="bad">{{ t('workflows.unrunnable', { reason: workflow.refusal ?? '' }) }}</span>
  </div>
</template>
