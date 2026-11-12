<script setup lang="ts">
import type { MissionReport } from '../api/types'
import { t } from '../i18n'

/** What each agent's worktree holds, in one line each; the files themselves are in the Deliverables panel. */
defineProps<{ report: MissionReport }>()
</script>

<template>
  <section class="me-section" data-report-section="deliverables">
    <div class="hint">{{ t('report.deliverablesHint') }}</div>
    <div v-if="!report.outputs.length" class="hint">{{ t('report.del.none') }}</div>
    <div v-for="o in report.outputs" :key="o.agent" class="me-row">
      <div class="main">
        <b>{{ o.agent }}</b>
        <code>{{ o.branch }}</code>
      </div>
      <div class="side">
        <span v-if="!o.readable" class="chip bad">{{ t('report.del.unreadable') }}</span>
        <span class="chip" :class="{ on: o.accepted > 0 }">{{ t('report.del.accepted', { count: o.accepted }) }}</span>
        <span v-if="o.uncommittedCount" class="chip bad">{{ t('report.del.uncommitted', { count: o.uncommittedCount }) }}</span>
        <span v-if="o.ignoredCount" class="chip bad">{{ t('report.del.ignored', { count: o.ignoredCount }) }}</span>
      </div>
    </div>
  </section>
</template>
