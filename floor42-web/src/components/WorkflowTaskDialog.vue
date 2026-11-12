<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import type { TaskView } from '../api/types'
import { t } from '../i18n'
import type { Key } from '../i18n'
import { trapTab } from '../ui/trapTab'
import { checkProblems, TEXT_FIELDS, TEXT_MAX, type TextField } from '../ui/workflowChecks'

/**
 * One of the director's tasks, defined over the timeline (ADR-0061): a ticket of its own, once per mission, run by a
 * developer, a reviewer or a researcher. Same step text rule as the check dialog. Nothing typed here changes what the
 * session may do: its permissions are the runner's on the mission, enforced by Floor42.
 */
const props = defineProps<{ task: TaskView; adding: boolean; production: boolean }>()
const emit = defineEmits<{ apply: [TaskView]; cancel: [] }>()

const draft = ref<TaskView>({ ...props.task })
const box = ref<HTMLElement>()
const first = ref<HTMLInputElement>()
let opener: HTMLElement | null = null

const problems = computed(() => {
  // Checked as it will be written: Apply normalises to NFKC, as the daemon does.
  const written = Object.fromEntries(TEXT_FIELDS.map((f) => [f, draft.value[f].normalize('NFKC')])) as Record<TextField, string>
  const found = checkProblems({ ...draft.value, ...written })
  const out: Partial<Record<TextField, string>> = {}
  for (const f of TEXT_FIELDS) {
    const p = found[f]
    if (p) out[f] = t(`workflows.chk.err.${p.code}` as Key, { position: p.position, max: TEXT_MAX[f] })
  }
  return out
})
const bodyFields = TEXT_FIELDS.filter((f) => f !== 'title')
const blocked = computed(() => Object.keys(problems.value).length > 0)
const writes = computed(() => draft.value.runBy === 'developer')
const fieldId = (f: TextField) => `wf-task-${f}`

onMounted(async () => {
  opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
  await nextTick()
  first.value?.focus()
})

function close(apply: boolean) {
  if (apply && blocked.value) return
  if (apply) emit('apply', { ...draft.value, reviewed: writes.value || draft.value.reviewed })
  else emit('cancel')
  if (opener?.isConnected) opener.focus()
}

function key(e: KeyboardEvent) {
  // Nothing typed here reaches the page's shortcuts.
  e.stopPropagation()
  if (e.key === 'Escape') {
    e.preventDefault()
    close(false)
  } else if (e.key === 'Tab') {
    trapTab(box.value, e, 'input:not(:disabled), textarea, select, button:not(:disabled)')
  }
}
</script>

<template>
  <div class="confirm-scrim" @pointerdown.self="close(false)">
    <div ref="box" class="panel confirm-dialog step-dialog" role="dialog" aria-modal="true" aria-labelledby="wf-task-heading" @keydown="key">
      <h3 id="wf-task-heading">{{ adding ? t('workflows.task.addTitle') : t('workflows.task.editTitle', { title: task.title }) }}</h3>
      <label class="confirm-field" :for="fieldId('title')">
        <span>{{ t('workflows.chk.title') }}</span>
        <input :id="fieldId('title')" ref="first" v-model="draft.title" type="text" :maxlength="TEXT_MAX.title" :aria-invalid="problems.title ? 'true' : undefined" />
        <span v-if="problems.title" class="hint warn" data-problem="title">{{ problems.title }}</span>
      </label>
      <fieldset class="confirm-field">
        <legend>{{ t('workflows.chk.where') }}</legend>
        <label>
          <input v-model="draft.slot" type="radio" value="beforeProduction" name="wf-task-slot" :disabled="!production" />
          {{ t('workflows.task.at.beforeProduction') }}
        </label>
        <label><input v-model="draft.slot" type="radio" value="beforeAcceptance" name="wf-task-slot" /> {{ t('workflows.task.at.beforeAcceptance') }}</label>
        <span v-if="!production" class="hint">{{ t('workflows.task.needsProduction') }}</span>
      </fieldset>
      <label class="confirm-field" for="wf-task-runner">
        <span>{{ t('workflows.chk.runBy') }}</span>
        <select id="wf-task-runner" v-model="draft.runBy">
          <option v-for="r in ['developer', 'reviewer', 'researcher']" :key="r" :value="r">{{ t(`workflows.task.runner.${r}` as Key) }}</option>
        </select>
      </label>
      <label v-for="f in bodyFields" :key="f" class="confirm-field" :for="fieldId(f)">
        <span>{{ t(`workflows.chk.${f}` as Key) }}</span>
        <textarea :id="fieldId(f)" v-model="draft[f]" :rows="f === 'does' ? 4 : 2" :maxlength="TEXT_MAX[f]" :aria-invalid="problems[f] ? 'true' : undefined"></textarea>
        <span v-if="problems[f]" class="hint warn" :data-problem="f">{{ problems[f] }}</span>
      </label>
      <label class="confirm-field">
        <span>
          <input type="checkbox" :checked="writes || draft.reviewed" :disabled="writes" @change="draft.reviewed = ($event.target as HTMLInputElement).checked" />
          {{ t('workflows.task.reviewedField') }}
        </span>
        <span v-if="writes" class="hint">{{ t('workflows.chk.err.WRITING_STEP_UNREVIEWED') }}</span>
      </label>
      <p class="hint">{{ t('workflows.task.permissions') }}</p>
      <div class="confirm-actions">
        <button class="action" @click="close(false)">{{ t('workflows.cancel') }}</button>
        <button class="action recommended" :disabled="blocked" @click="close(true)">{{ t('workflows.chk.apply') }}</button>
      </div>
    </div>
  </div>
</template>
