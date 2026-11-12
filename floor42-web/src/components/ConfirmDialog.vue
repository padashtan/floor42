<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { t } from '../i18n'
import { pending } from '../state/confirm'
import { trapTab } from '../ui/trapTab'

/** The page's one confirmation (state/confirm.ts): modal, Escape cancels, Enter confirms, the focus stays inside. */
const text = ref('')
const box = ref<HTMLElement>()
const field = ref<HTMLInputElement>()
const cancelButton = ref<HTMLButtonElement>()
const confirmButton = ref<HTMLButtonElement>()
let opener: HTMLElement | null = null
/**
 * A reason (it starts with a default the director may keep) and a required text (a new file's path) cannot be confirmed
 * empty: the button waits, Enter does nothing.
 */
const required = computed(() => !!pending.value?.request.reason || !!pending.value?.request.input?.required)
const missing = computed(() => required.value && !text.value.trim())

watch(pending, async (open) => {
  if (!open) {
    if (opener?.isConnected) opener.focus()
    opener = null
    return
  }
  opener ??= document.activeElement instanceof HTMLElement ? document.activeElement : null
  text.value = open.request.reason?.default ?? open.request.input?.value ?? ''
  await nextTick()
  if (field.value) {
    field.value.focus()
    field.value.select()
  } else (open.request.danger ? cancelButton.value : confirmButton.value)?.focus()
})
onBeforeUnmount(() => pending.value?.settle(null))

function answer(ok: boolean) {
  const open = pending.value
  if (!open || (ok && missing.value)) return
  open.settle(!ok ? null : open.request.reason || open.request.input ? text.value : true)
}

function key(e: KeyboardEvent) {
  // Nothing typed here reaches the page's shortcuts.
  e.stopPropagation()
  if (e.key === 'Escape') {
    e.preventDefault()
    answer(false)
  } else if (e.key === 'Enter' && (e.target as HTMLElement).tagName === 'INPUT') {
    e.preventDefault()
    answer(true)
  } else if (e.key === 'Tab') {
    trapTab(box.value, e, 'input, button:not(:disabled)')
  }
}
</script>

<template>
  <div v-if="pending" class="confirm-scrim" @pointerdown.self="answer(false)">
    <div ref="box" class="panel confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="confirm-message" @keydown="key">
      <p id="confirm-message" class="confirm-message">{{ pending.request.message }}</p>
      <label v-if="pending.request.reason || pending.request.input" class="confirm-field">
        <span>{{ pending.request.reason?.label ?? pending.request.input?.label ?? t('confirm.reason') }}</span>
        <input ref="field" v-model="text" type="text" :required="required" />
      </label>
      <div class="confirm-actions">
        <button ref="cancelButton" class="action" @click="answer(false)">{{ t('confirm.cancel') }}</button>
        <button
          ref="confirmButton"
          class="action"
          :class="pending.request.danger ? 'danger' : 'recommended'"
          :disabled="missing"
          @click="answer(true)"
        >
          {{ pending.request.confirmLabel ?? t('confirm.ok') }}
        </button>
      </div>
    </div>
  </div>
</template>
