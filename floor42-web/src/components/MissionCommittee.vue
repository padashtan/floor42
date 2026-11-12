<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { CommitteePanel, RoleView } from '../api/types'
import { t } from '../i18n'
import { setVacantSeats } from '../state/drafts'
import NewSeat from './NewSeat.vue'

/** The committee of a new mission: none, an architecture committee, or an idea committee, and who sits on it. */
const panel = defineModel<'none' | CommitteePanel>('panel', { required: true })
const seated = defineModel<string[]>('seated', { required: true })
const roles = ref<RoleView[]>([])
const withCommittee = computed(() => panel.value !== 'none')
const chairKey = computed(() => (panel.value === 'idea' ? 'moderator' : 'architect'))
const panelRoles = computed(() =>
  panel.value === 'none' ? [] : roles.value.filter((r) => !r.vacant && r.panels.includes(panel.value as CommitteePanel)),
)

/** Each committee starts with its usual seats: the whole idea committee, or security and a developer. */
function choosePanel(p: 'none' | CommitteePanel) {
  panel.value = p
  seated.value =
    p === 'idea'
      ? roles.value.filter((r) => r.panels.includes('idea') && r.key !== 'moderator' && !r.custom && !r.vacant).map((r) => r.key)
      : ['security', 'developer'].filter((k) => !roles.value.some((r) => r.key === k && r.vacant))
}

/** A seat the director just defined is offered at once, and seated on the committee being prepared. */
function seatDefined(all: RoleView[], key: string) {
  roles.value = all
  if (key && !seated.value.includes(key)) seated.value.push(key)
}

onMounted(async () => {
  try {
    roles.value = await api.committeeRoles()
    // A seat whose member left is not offered, nor seated by default: the blank form a draft is compared to drops it too.
    const vacant = roles.value.filter((r) => r.vacant).map((r) => r.key)
    setVacantSeats(vacant)
    seated.value = seated.value.filter((k) => !vacant.includes(k))
  } catch {
    roles.value = []
  }
})
</script>

<template>
  <label class="hint"><input type="radio" :checked="panel === 'none'" @change="choosePanel('none')" /> {{ t('mission.noCommittee') }}</label>
  <label class="hint"><input type="radio" :checked="panel === 'architecture'" @change="choosePanel('architecture')" /> {{ t('mission.withCommittee') }}</label>
  <label class="hint"><input type="radio" :checked="panel === 'idea'" @change="choosePanel('idea')" /> {{ t('mission.withIdeaCommittee') }}</label>
  <div v-if="panel === 'architecture'" class="hint">{{ t('perm.committeeTable') }}</div>
  <div v-if="withCommittee" class="roles">
    <span v-for="r in panelRoles.filter((x) => x.key === chairKey)" :key="r.key" class="hint" :title="r.lens">
      {{ r.name }} · {{ r.title }} · {{ t(panel === 'idea' ? 'mission.alwaysModerates' : 'mission.alwaysChairs') }}
    </span>
    <label v-for="r in panelRoles.filter((x) => x.key !== chairKey)" :key="r.key" class="hint" :title="r.lens">
      <input v-model="seated" type="checkbox" :value="r.key" />
      {{ r.name }} · {{ r.title }}<span v-if="r.custom"> · {{ t('seat.yours') }}</span>
    </label>
    <NewSeat :key="panel" :panel="panel as CommitteePanel" @defined="seatDefined" />
    <div v-if="panel === 'idea'" class="hint">{{ t('mission.ideaHow', { seats: seated.length }) }}</div>
  </div>
</template>
