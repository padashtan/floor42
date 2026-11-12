<script setup lang="ts">
// cost-overview: a real plan is approved with its cap. The field starts from the mission's budget, else the default
// the settings offer; "no cap" is a box the director ticks, recorded as their choice. The server refuses the rest.
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { ConsentView, ResourceCatalogueView } from '../api/identity'
import { request } from '../api/request'
import type { MissionView, PlanApproval } from '../api/types'
import { t } from '../i18n'
import { capChoice, defaultCap, usd } from '../ui/costs'
import { iamOn } from '../ui/identities'
import { why } from '../ui/why'

const props = defineProps<{ mission: MissionView; busy: boolean }>()
const emit = defineEmits<{ approve: [cap: PlanApproval] }>()

/** undefined while the settings are read. */
const offered = ref<number | null | undefined>(undefined)
const draft = ref('')
const noCap = ref(false)
/** IAM mode: what the approval delegates, shown before the click; null when IAM mode is off. */
const consent = ref<ConsentView | null>(null)
/** IAM mode: the broker's catalogue; each resource is delegated only if the director ticks it (none by default). */
const catalogue = ref<ResourceCatalogueView | null>(null)
const picked = ref<string[]>([])
const grantedTo = (name: string) =>
  Object.entries(catalogue.value?.grants ?? {})
    .filter(([, names]) => names.includes(name))
    .map(([agent]) => agent)

async function readConsent() {
  const q = picked.value.length ? `?resources=${encodeURIComponent(picked.value.join(','))}` : ''
  consent.value = await request<ConsentView>('GET', `/api/iam/missions/${encodeURIComponent(props.mission.id)}/consent${q}`).catch(() => null)
}

function fill() {
  const m = props.mission
  noCap.value = m.capDecided && m.budgetUsd === null
  const start = m.budgetUsd ?? offered.value ?? null
  draft.value = start === null ? '' : String(start)
}

onMounted(async () => {
  try {
    offered.value = defaultCap((await api.settings()).find((s) => s.key === 'MISSION_BUDGET_USD')?.value)
  } catch {
    offered.value = null
  }
  fill()
  // Asked only in IAM mode: the route answers 409 otherwise, and the browser logs that as a console error.
  if (!(await iamOn())) return
  catalogue.value = await request<ResourceCatalogueView>('GET', '/api/iam/resources').catch(() => null)
  await readConsent()
})
watch(() => [props.mission.id, props.mission.budgetUsd, props.mission.capDecided], fill)
watch(
  () => props.mission.id,
  () => {
    picked.value = []
    consent.value = null
  },
)
watch(picked, readConsent)

const choice = computed(() => {
  const c = capChoice(draft.value, noCap.value)
  return c && picked.value.length ? { ...c, resources: [...picked.value] } : c
})
</script>

<template>
  <div class="plan-cap">
    <label class="hint">
      {{ t('cost.planCap') }}
      <input v-model="draft" :disabled="noCap" inputmode="decimal" :placeholder="t('cost.planCapPlaceholder')" /> USD
    </label>
    <div class="hint">
      {{ offered === undefined ? '…' : offered === null ? t('cost.planNoDefault') : t('cost.planDefault', { usd: usd(offered) }) }}
      {{ t('cost.planCapHint') }}
    </div>
    <label class="hint warn"><input v-model="noCap" type="checkbox" /> {{ t('cost.noCap') }}</label>
    <fieldset v-if="catalogue?.resources.length" class="plan-resources">
      <legend class="hint">{{ t('iam.pickResources') }}</legend>
      <label v-for="r in catalogue.resources" :key="r.name" class="hint" :data-resource="r.name">
        <input v-model="picked" type="checkbox" :value="r.name" />
        {{ r.name }} · {{ r.methods.join(', ') }} {{ r.pathPrefixes.join(', ') }}
        · {{ grantedTo(r.name).length ? t('iam.grantedTo', { agents: grantedTo(r.name).join(', ') }) : t('iam.grantedNobody') }}
      </label>
    </fieldset>
    <div v-if="consent" class="hint">
      {{ t('iam.consent', { until: new Date(consent.expiresAt).toLocaleDateString() }) }}
      {{ consent.resources.length ? t('iam.consentResources', { names: consent.resources.join(', ') }) : '' }}
    </div>
    <button
      class="action recommended"
      :disabled="busy || !choice"
      :title="why([!choice, t('cost.why')])"
      @click="choice && emit('approve', choice)"
    >
      {{ choice?.noCap ? t('cost.approveNoCap') : choice ? t('cost.approveWith', { usd: usd(choice.budgetUsd!) }) : t('mission.approvePlan') }}
    </button>
  </div>
</template>
