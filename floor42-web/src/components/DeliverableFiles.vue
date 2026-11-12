<script setup lang="ts">
import { deliverableUrl } from '../api/client'
import type { DeliverablesView, DeliveredBranch, DeliveredFile } from '../api/types'
import { t, tr } from '../i18n'
import { viewKey } from '../state/viewer'
import { downloadable, formatSize, refOf } from '../ui/deliverables'

/** One branch's files: committed ones, then those that exist only in its worktree. View or download each. */
const props = defineProps<{ mission: string; view: DeliverablesView; branch: DeliveredBranch }>()
const emit = defineEmits<{ show: [branch: DeliveredBranch, file: DeliveredFile] }>()

const href = (f: DeliveredFile) => deliverableUrl(props.mission, refOf(props.branch.agent, f), true)
const status = (f: DeliveredFile) => tr(`deliverables.status.${f.status}`, f.status)
</script>

<template>
  <div class="files">
    <div class="sub">
      {{ t('deliverables.committed', { count: props.branch.committed.length }) }}
      <span class="hint">· {{ t('deliverables.committedHint', { start: props.branch.start ?? '?', tip: props.branch.tip ?? '?' }) }}</span>
    </div>
    <div v-if="props.branch.committed.length === 0" class="hint">{{ t('deliverables.noCommitted') }}</div>
    <div v-for="f in props.branch.committed" :key="'c:' + f.path" class="file">
      <span class="st" :class="'st-' + f.status" :title="status(f)">{{ f.status }}</span>
      <code class="path">{{ f.from ? `${f.from} → ${f.path}` : f.path }}</code>
      <span class="hint">{{ formatSize(f.size) }} · <code>{{ f.commit }}</code></span>
      <button v-if="downloadable(f)" class="small" :data-view-key="viewKey(props.branch.agent, f.path, f.committed)" @click="emit('show', props.branch, f)">{{ t('deliverables.view') }}</button>
      <a v-if="downloadable(f)" class="button small" :href="href(f)" download>{{ t('deliverables.download') }}</a>
    </div>

    <template v-if="props.branch.worktreePresent">
      <div class="sub warn">
        {{ t('deliverables.uncommitted', { count: props.branch.uncommitted.length }) }}
        <span class="hint">· {{ t('deliverables.uncommittedHint') }}</span>
      </div>
      <div v-if="props.branch.uncommitted.length === 0" class="hint">{{ t('deliverables.noUncommitted') }}</div>
      <div v-for="f in props.branch.uncommitted" :key="'w:' + f.path" class="file loose">
        <span class="st" :class="'st-' + (f.status === '?' ? 'N' : f.status === '!' ? 'I' : f.status)" :title="status(f)">
          {{ f.status }}
        </span>
        <code class="path">{{ f.path }}</code>
        <span class="tag">{{ f.ignored ? t('deliverables.ignoredTag') : t('deliverables.notCommittedTag') }}</span>
        <span v-if="f.link" class="tag danger">{{ t('deliverables.linkTag') }}</span>
        <span class="hint">{{ formatSize(f.size) }}</span>
        <button v-if="downloadable(f)" class="small" :data-view-key="viewKey(props.branch.agent, f.path, f.committed)" @click="emit('show', props.branch, f)">{{ t('deliverables.view') }}</button>
        <a v-if="downloadable(f)" class="button small" :href="href(f)" download>{{ t('deliverables.download') }}</a>
      </div>
      <div v-if="!props.view.ignoredShown && props.branch.ignoredHidden > 0" class="hint">
        {{ t('deliverables.ignoredHidden', { count: props.branch.ignoredHidden }) }}
      </div>
    </template>
    <div v-else class="hint">{{ t('deliverables.worktreeGone') }}</div>
    <div v-if="props.branch.truncated" class="hint">{{ t('deliverables.truncated') }}</div>
  </div>
</template>

<style scoped>
.file {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 0.9em;
  margin: 2px 0;
}
.path {
  word-break: break-all;
}
.st {
  display: inline-block;
  min-width: 1.4em;
  text-align: center;
  border-radius: 3px;
  font-weight: bold;
  background: var(--muted);
  color: #000;
}
.st-A,
.st-N {
  background: var(--green);
}
.st-M,
.st-R {
  background: var(--cyan);
}
.st-D {
  background: var(--red, #e05050);
}
.st-I {
  background: var(--muted);
}
.tag {
  font-size: max(12px, 0.8em);
  border: 1px solid var(--amber);
  color: var(--amber);
  border-radius: 3px;
  padding: 0 4px;
}
.tag.danger {
  border-color: var(--red, #e05050);
  color: var(--red, #e05050);
}
.warn {
  color: var(--amber);
}
.small {
  font-size: max(12px, 0.8em);
  padding: 3px 6px;
}
</style>
