<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { CommitteeView } from '../api/types'
import { t } from '../i18n'

/** The committee at a glance: its topic (folded to three lines when long), stage, round, members and constraints. */
const props = defineProps<{ committee: CommitteeView; missionId: string; count: number }>()

// A long topic folds to three lines so the debate and the ruling stay in view; the choice is kept per mission.
const TOPIC_KEY = 'floor42.meeting.topicOpen.'
const topic = ref<HTMLElement>()
const topicOpen = ref(false)
const topicLong = ref(false)
watch(
  () => props.missionId,
  (id) => {
    try {
      topicOpen.value = !!id && localStorage.getItem(TOPIC_KEY + id) === '1'
    } catch {
      topicOpen.value = false
    }
  },
  { immediate: true },
)
watch(
  () => [props.committee.topic, topicOpen.value],
  async () => {
    await nextTick()
    const el = topic.value
    // Folded, the text is longer than its box; unfolded, it stays a toggle so it can be folded back.
    topicLong.value = !!el && (topicOpen.value || el.scrollHeight > el.clientHeight + 1)
  },
  { immediate: true, flush: 'post' },
)
function toggleTopic() {
  topicOpen.value = !topicOpen.value
  const id = props.missionId
  if (!id) return
  try {
    if (topicOpen.value) localStorage.setItem(TOPIC_KEY + id, '1')
    else localStorage.removeItem(TOPIC_KEY + id)
  } catch {
    // storage unavailable: the topic just is not remembered open
  }
}
</script>

<template>
  <section class="mr-stack committee-overview">
    <div class="mr-card question">
      <div class="card-label">{{ t('meeting.card.question') }}</div>
      <div ref="topic" class="topic" :class="{ folded: !topicOpen }">{{ committee.topic }}</div>
      <button v-if="topicLong" class="link topic-toggle" @click="toggleTopic">
        {{ topicOpen ? t('meeting.topicLess') : t('meeting.topicMore') }}
      </button>
      <div class="hint">
        <span v-if="count > 1">{{ t('meeting.committeeNo', { n: committee.number, count }) }} · </span>
        <span class="stage" :class="committee.stage.toLowerCase()">{{ t(`meeting.stage.${committee.stage as 'ANALYSIS'}`) }}</span>
        · {{ t('meeting.round', { n: committee.round }) }}
      </div>
      <div v-if="committee.moderated && committee.stage !== 'ANALYSIS'" class="hint">
        <span v-if="committee.agreement" class="good">{{ t('meeting.agreement', { member: committee.agreement }) }}</span>
        <span v-else class="warn">
          {{ t('meeting.noAgreement', { rounds: committee.debateRounds, max: committee.maxDebateRounds }) }}
        </span>
      </div>
      <div v-if="committee.constraints.length" class="constraints">
        {{ t('meeting.constraints') }} <span v-for="c in committee.constraints" :key="c" class="constraint">{{ c }}</span>
      </div>
    </div>
    <div class="mr-members">
      <div v-for="m in committee.members" :key="m.agent" class="mr-card member-card" :class="{ pending: m.pending }">
        <b>{{ m.agent }}</b>
        <span>{{ m.role }}<template v-if="m.chair"> · {{ t('meeting.chair') }}</template></span>
        <span class="state">{{ m.pending ? t('meeting.contributing') : t('meeting.card.done') }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.topic {
  flex: none;
  white-space: pre-wrap;
}
.topic.folded {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  overflow: hidden;
}
.topic-toggle {
  align-self: flex-start;
  margin-bottom: 4px;
}
.link {
  background: none;
  border: none;
  color: var(--cyan);
  cursor: pointer;
  padding: 2px 0;
}
</style>
