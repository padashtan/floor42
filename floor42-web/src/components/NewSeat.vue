<script setup lang="ts">
import { MODELS } from '../ui/models'
import { ref } from 'vue'
import { api } from '../api/client'
import type { CommitteePanel, RoleView } from '../api/types'
import { t } from '../i18n'
import { why } from '../ui/why'

/**
 * A committee seat of the director's own: a specialist with a lens, who only reads and gives an opinion like every
 * member. Defined once, it is offered on every committee it sits on.
 */
const props = defineProps<{ panel: CommitteePanel }>()
const emit = defineEmits<{ defined: [roles: RoleView[], key: string] }>()

const open = ref(false)
const name = ref('')
const title = ref('')
const lens = ref('')
const model = ref('claude-sonnet-5-5')
const effort = ref('medium')
const panels = ref<CommitteePanel[]>([props.panel])
const busy = ref(false)
const error = ref<string | null>(null)

async function define() {
  busy.value = true
  error.value = null
  try {
    const roles = await api.defineSeat({
      name: name.value.trim(),
      title: title.value.trim(),
      lens: lens.value.trim(),
      model: model.value,
      effort: effort.value,
      panels: panels.value,
    })
    const added = roles.find((r) => r.custom && r.name === name.value.trim())
    emit('defined', roles, added?.key ?? '')
    name.value = ''
    title.value = ''
    lens.value = ''
    open.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <button v-if="!open" type="button" class="link hint" @click="open = true">+ {{ t('seat.new') }}</button>
  <form v-else class="new-seat" @submit.prevent="define">
    <div class="hint">{{ t('seat.explain') }}</div>
    <input v-model="name" maxlength="40" required :placeholder="t('seat.name')" :aria-label="t('seat.name')" />
    <input v-model="title" maxlength="80" required :placeholder="t('seat.title')" :aria-label="t('seat.title')" />
    <textarea v-model="lens" rows="3" maxlength="2000" required :placeholder="t('seat.lens')" :aria-label="t('seat.lens')"></textarea>
    <div class="row">
      <select v-model="model" :aria-label="t('seat.modelLabel')"><option v-for="m in MODELS" :key="m" :value="m">{{ m }}</option></select>
      <select v-model="effort" :aria-label="t('seat.effortLabel')">
        <option value="low">{{ t('seat.effort.low') }}</option>
        <option value="medium">{{ t('seat.effort.medium') }}</option>
        <option value="high">{{ t('seat.effort.high') }}</option>
      </select>
      <label class="hint"><input v-model="panels" type="checkbox" value="architecture" /> {{ t('seat.architecture') }}</label>
      <label class="hint"><input v-model="panels" type="checkbox" value="idea" /> {{ t('seat.idea') }}</label>
    </div>
    <div v-if="error" class="warn">{{ error }}</div>
    <div class="row">
      <button
        class="action"
        type="submit"
        :disabled="busy || !name.trim() || !title.trim() || !lens.trim() || !panels.length"
        :title="why([!name.trim() || !title.trim() || !lens.trim() || !panels.length, t('why.needFields')])"
      >
        {{ t('seat.define') }}
      </button>
      <button type="button" class="link hint" @click="open = false">{{ t('seat.cancel') }}</button>
    </div>
  </form>
</template>

<style scoped>
.new-seat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border-left: 2px solid currentColor;
  padding-left: 6px;
  margin-top: 4px;
}
.row {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: center;
}
.link {
  background: none;
  border: none;
  padding: 0;
  text-decoration: underline;
  cursor: pointer;
  text-align: left;
}
.warn {
  color: #e0a040;
}
</style>
