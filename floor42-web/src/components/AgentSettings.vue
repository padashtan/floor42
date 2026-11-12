<script setup lang="ts">
import { MODELS } from '../ui/models'
import { computed, ref } from 'vue'
import { t } from '../i18n'
import { office, reconfigureAgent } from '../state/office'

const props = defineProps<{ agent: string }>()
const emit = defineEmits<{ close: [] }>()

const a = computed(() => office.agents.find((x) => x.id === props.agent))
const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max']
const model = ref(a.value?.model ?? 'claude-sonnet-5-5')
const effort = ref(a.value?.effort ?? 'medium')
const models = computed(() => (MODELS.includes(model.value) ? MODELS : [model.value, ...MODELS]))
const busy = ref(false)

async function save() {
  busy.value = true
  try {
    if (await reconfigureAgent(props.agent, model.value, effort.value)) emit('close')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="panel agent-settings">
    <div class="title">
      {{ a?.name ?? agent }} · {{ t('agentSettings.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="hint">{{ t('agentSettings.applies') }}</div>
    <label>{{ t('agentSettings.model') }} <select v-model="model"><option v-for="m in models" :key="m" :value="m">{{ m }}</option></select></label>
    <label>{{ t('agentSettings.effort') }} <select v-model="effort"><option v-for="e in EFFORTS" :key="e" :value="e">{{ e }}</option></select></label>
    <div class="hint">{{ t('agentSettings.models') }}</div>
    <div class="options">
      <button class="action recommended" :disabled="busy" @click="save">{{ t('common.save') }}</button>
      <button class="action" :disabled="busy" @click="emit('close')">{{ t('common.cancel') }}</button>
    </div>
  </div>
</template>
