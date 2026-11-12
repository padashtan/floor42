<script setup lang="ts">
import { computed } from 'vue'
import type { CompanyWorkflowView, VersionView } from '../api/types'
import { dateLocale, t } from '../i18n'
import { stepLine } from '../ui/workflows'

/**
 * The saves of one company workflow (ADR-0052), newest first. A version is the number of saves since the workflow's
 * latest removal; "Restore" saves an earlier document again, as a new version. The current one has nothing to restore.
 */
const props = defineProps<{ workflow: CompanyWorkflowView; busy: boolean }>()
const emit = defineEmits<{ restore: [VersionView] }>()

const newestFirst = computed(() => [...props.workflow.versions].reverse())
const when = (iso: string) => new Date(iso).toLocaleString(dateLocale(), { dateStyle: 'medium', timeStyle: 'short' })
</script>

<template>
  <details class="wf-versions">
    <summary>{{ t('workflows.versions', { count: workflow.versions.length }) }}</summary>
    <ol class="wf-version-list" reversed>
      <li v-for="v in newestFirst" :key="v.version" :data-version="v.version">
        <span class="wf-version-title">{{ t('workflows.version.line', { version: v.version, date: when(v.savedAt) }) }}</span>
        <span v-if="v.version === workflow.version" class="facts">{{ t('workflows.version.current') }}</span>
        <button
          v-else
          class="action"
          :disabled="busy || !v.workflow"
          :title="v.workflow ? '' : t('workflows.unreadable')"
          @click="emit('restore', v)"
        >{{ t('workflows.version.restore') }}</button>
        <div class="hint">{{ v.workflow ? stepLine(v.workflow, t) : t('workflows.unreadable') }}</div>
      </li>
    </ol>
  </details>
</template>
