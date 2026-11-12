<script setup lang="ts">
import { computed } from 'vue'
import { t } from '../i18n'
import { office } from '../state/office'
import QuestionCard, { agentOfQuestion } from './QuestionCard.vue'

/** What waits for the director from one agent: opened by a double click on it. */
const props = defineProps<{ agent: string }>()
const emit = defineEmits<{ close: []; profile: [] }>()

const name = computed(() => office.agents.find((a) => a.id === props.agent)?.name ?? props.agent)
const pending = computed(() => office.questions.filter((q) => agentOfQuestion(q) === props.agent))
</script>

<template>
  <div class="panel requests-panel framed">
    <div class="title">
      {{ name.toUpperCase() }} · {{ t('requests.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll">
      <div v-if="pending.length === 0" class="empty-state">
        {{ t('requests.nothing', { name }) }}
        <button class="action" @click="emit('profile')">{{ t('requests.profile') }}</button>
      </div>
      <QuestionCard v-for="q in pending" :key="q.id" :q="q" />
    </div>
  </div>
</template>
