<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import type { CheckView } from '../api/types'
import { t } from '../i18n'
import type { Key } from '../i18n'
import { trapTab } from '../ui/trapTab'
import { checkProblems, TEXT_FIELDS, TEXT_MAX, type TextField } from '../ui/workflowChecks'

/**
 * One of the director's checks, defined over the timeline (ADR-0052): what it does and why, what it hands over, who runs
 * it and whether it blocks. The dialog edits a step; the timeline edits the workflow. Nothing typed here changes what
 * the check's session may do: its permissions are a reviewer's on the mission, enforced by Floor42.
 */
const props = defineProps<{ check: CheckView; adding: boolean }>()
const emit = defineEmits<{ apply: [CheckView]; cancel: [] }>()

const draft = ref<CheckView>({ ...props.check })
const box = ref<HTMLElement>()
const first = ref<HTMLInputElement>()
let opener: HTMLElement | null = null

const problems = computed(() => {
  // Checked as it will be written: Apply normalises to NFKC, as the daemon does.
  const written = { ...draft.value, ...Object.fromEntries(TEXT_FIELDS.map((f) => [f, draft.value[f].normalize('NFKC')])) }
  const found = checkProblems(written)
  const out: Partial<Record<TextField, string>> = {}
  for (const f of TEXT_FIELDS) {
    const p = found[f]
    if (p) out[f] = t(`workflows.chk.err.${p.code}` as Key, { position: p.position, max: TEXT_MAX[f] })
  }
  return out
})
const bodyFields = TEXT_FIELDS.filter((f) => f !== 'title')
const blocked = computed(() => Object.keys(problems.value).length > 0)
const fieldId = (f: TextField) => `wf-chk-${f}`

onMounted(async () => {
  opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
  await nextTick()
  first.value?.focus()
})

function close(apply: boolean) {
  if (apply && blocked.value) return
  if (apply) emit('apply', { ...draft.value })
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
    <div
      ref="box"
      class="panel confirm-dialog step-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="wf-chk-heading"
      @keydown="key"
    >
      <h3 id="wf-chk-heading">{{ adding ? t('workflows.chk.addTitle') : t('workflows.chk.editTitle', { title: check.title }) }}</h3>
      <label class="confirm-field" :for="fieldId('title')">
        <span>{{ t('workflows.chk.title') }}</span>
        <input
          :id="fieldId('title')"
          ref="first"
          v-model="draft.title"
          type="text"
          :maxlength="TEXT_MAX.title"
          :aria-invalid="problems.title ? 'true' : undefined"
        />
        <span v-if="problems.title" class="hint warn" data-problem="title">{{ problems.title }}</span>
      </label>
      <fieldset class="confirm-field">
        <legend>{{ t('workflows.chk.where') }}</legend>
        <label><input type="radio" checked disabled /> {{ t('workflows.chk.slot.checks') }}</label>
        <span class="hint">{{ t('workflows.chk.slotLater') }}</span>
      </fieldset>
      <label class="confirm-field" for="wf-chk-runner">
        <span>{{ t('workflows.chk.runBy') }}</span>
        <select id="wf-chk-runner" v-model="draft.runBy">
          <option value="reviewer">{{ t('workflows.chk.runner.reviewer') }}</option>
        </select>
        <span class="hint">{{ t('workflows.chk.runnerHint') }}</span>
      </label>
      <label v-for="f in bodyFields" :key="f" class="confirm-field" :for="fieldId(f)">
        <span>{{ t(`workflows.chk.${f}` as Key) }}</span>
        <textarea
          :id="fieldId(f)"
          v-model="draft[f]"
          :rows="f === 'does' ? 4 : 2"
          :maxlength="TEXT_MAX[f]"
          :aria-invalid="problems[f] ? 'true' : undefined"
        ></textarea>
        <span v-if="problems[f]" class="hint warn" :data-problem="f">{{ problems[f] }}</span>
      </label>
      <fieldset class="confirm-field">
        <legend>{{ t('workflows.chk.whenFound') }}</legend>
        <label><input v-model="draft.blocks" type="radio" :value="true" name="wf-chk-blocks" /> {{ t('workflows.chk.blocks') }}</label>
        <label><input v-model="draft.blocks" type="radio" :value="false" name="wf-chk-blocks" /> {{ t('workflows.chk.advisory') }}</label>
      </fieldset>
      <p class="hint">{{ t('workflows.chk.permissions') }}</p>
      <div class="confirm-actions">
        <button class="action" @click="close(false)">{{ t('workflows.cancel') }}</button>
        <button class="action recommended" :disabled="blocked" @click="close(true)">{{ t('workflows.chk.apply') }}</button>
      </div>
    </div>
  </div>
</template>
