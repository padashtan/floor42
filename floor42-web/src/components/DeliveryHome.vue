<script setup lang="ts">
import { computed } from 'vue'
import type { DeliveryCopyView } from '../api/types'
import { t } from '../i18n'
import { copyCounts } from '../ui/deliverables'

/**
 * What Floor42 copied into the mission's folder of its destination when the director accepted the delivery (ADR-0051),
 * as journaled: each file written, kept beside a file of the same name, already there, or left out and why.
 */
const props = defineProps<{ copy: DeliveryCopyView }>()
const counts = computed(() => copyCounts(props.copy))
</script>

<template>
  <div class="delivery-home" role="status">
    <div>
      {{ t('home.copiedTo', { folder: copy.folder }) }}
    </div>
    <div class="hint">
      {{ t('home.counts', { written: counts.written, keptBoth: counts.keptBoth, same: counts.same, leftOut: counts.leftOut }) }}
    </div>
    <div v-if="counts.leftOut > 0" class="hint left-out-help">{{ t('home.leftOutHelp') }}</div>
    <div v-if="copy.truncated" class="warn">{{ t('home.truncated') }}</div>
    <details v-if="copy.files.length">
      <summary>{{ t('home.files', { count: copy.files.length }) }}</summary>
      <ul class="files">
        <li v-for="(f, i) in copy.files" :key="i + ':' + f.agent + ':' + f.path" :class="'o-' + f.outcome">
          <code>{{ f.path }}</code>
          <template v-if="f.outcome === 'KEPT_BOTH'"> · {{ t('home.keptAs', { name: f.writtenAs ?? '' }) }}</template>
          <template v-else-if="f.outcome === 'LEFT_OUT'"> · {{ t('home.leftOutWhy', { why: f.reason ?? '' }) }}</template>
          <template v-else> · {{ t(`home.outcome.${f.outcome}`) }}</template>
        </li>
      </ul>
    </details>
  </div>
</template>

<style scoped>
.delivery-home {
  margin: 4px 0;
}
.files {
  margin: 2px 0;
  padding-left: 1.2em;
  max-height: 12em;
  overflow-y: auto;
}
.o-KEPT_BOTH {
  color: #e0a040;
}
.o-LEFT_OUT {
  color: #9aa7ad;
}
.warn {
  color: #e0a040;
}
</style>
