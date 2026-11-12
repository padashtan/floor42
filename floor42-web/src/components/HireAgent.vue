<script setup lang="ts">
import { MODELS } from '../ui/models'
import { sameName } from '../ui/names'
import { computed, ref, toRef } from 'vue'
import { t } from '../i18n'
import { why } from '../ui/why'
import { drafts, resetHireDraft } from '../state/drafts'
import { hireAgent, office } from '../state/office'

/** The director hires an agent for a work role (ADR-0019). What the role may do is enforced by Floor42, by code. */
const emit = defineEmits<{ close: [] }>()

const ROLES = computed(() =>
  (['DEVELOPER', 'REVIEWER', 'RESEARCHER', 'ANALYST'] as const).map((key) => ({
    key,
    label: t(`hire.role.${key}`),
    does: t(`hire.role.${key}.does`),
  })),
)
const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max']

// Kept in the drafts store (state/drafts.ts): another panel taking this one's place loses nothing.
const name = toRef(drafts.hire, 'name')
const title = toRef(drafts.hire, 'title')
const workRole = toRef(drafts.hire, 'workRole')
const model = toRef(drafts.hire, 'model')
const effort = toRef(drafts.hire, 'effort')
const busy = ref(false)
const role = computed(() => ROLES.value.find((r) => r.key === workRole.value)!)
/** Two agents at work never share a name, whatever its case: the daemon refuses it too (NAME_TAKEN). */
const taken = computed(() => {
  const wanted = sameName(name.value)
  return wanted !== '' && office.agents.some((a) => sameName(a.name) === wanted)
})

/** Cancel throws the draft away; the × and Escape keep it for the next opening. */
function cancel() {
  resetHireDraft()
  emit('close')
}

async function hire() {
  if (busy.value || taken.value) return
  busy.value = true
  try {
    if (await hireAgent(name.value.trim(), title.value.trim(), workRole.value, model.value, effort.value)) {
      resetHireDraft()
      emit('close')
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="panel hire-panel framed">
    <div class="title">
      {{ t('hire.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <form class="panel-scroll hire-form" @submit.prevent="hire">
      <label>{{ t('hire.name') }} <input v-model="name" :placeholder="t('hire.namePlaceholder')" required maxlength="40" :aria-label="t('hire.namePlaceholder')" /></label>
      <div v-if="taken" class="hint">{{ t('why.nameTaken') }}</div>
      <div class="roles">
        <label v-for="r in ROLES" :key="r.key" class="role" :class="{ on: workRole === r.key }">
          <input v-model="workRole" type="radio" :value="r.key" /> {{ r.label }}
        </label>
      </div>
      <div class="hint">{{ role.does }}</div>
      <label>{{ t('hire.jobTitle') }} <input v-model="title" :placeholder="t('hire.jobTitlePlaceholder', { role: role.label })" maxlength="60" :aria-label="t('hire.jobTitlePlaceholder', { role: role.label })" /></label>
      <label>
        {{ t('agentSettings.model') }}
        <select v-model="model"><option v-for="m in MODELS" :key="m" :value="m">{{ m }}</option></select>
      </label>
      <label>
        {{ t('agentSettings.effort') }}
        <select v-model="effort"><option v-for="e in EFFORTS" :key="e" :value="e">{{ e }}</option></select>
      </label>
      <div class="hint">{{ t('hire.cost') }}</div>
      <div class="options">
        <button class="action recommended" type="submit" :disabled="busy || !name.trim() || taken" :title="why([!name.trim(), t('why.needName')], [taken, t('why.nameTaken')])">{{ t('top.hire') }}</button>
        <button class="action" type="button" @click="cancel">{{ t('common.cancel') }}</button>
      </div>
    </form>
  </div>
</template>
