<script setup lang="ts">
// What one agent may use among the director's catalogue resources: each line says held or not, with its button. A grant
// is a tuple in OpenFGA that this screen writes (and the journal records); nothing else writes it (ADR-0080). A
// resource call also needs the mission's delegation, which the plan's approval gives: the line says so.
import { onMounted, ref, watch } from 'vue'
import type { ResourceCatalogueView } from '../api/identity'
import { ApiError, followStepUp, request } from '../api/request'
import { t } from '../i18n'

const props = defineProps<{ agent: string }>()

const catalogue = ref<ResourceCatalogueView | null>(null)
const error = ref<string | null>(null)
const busy = ref(false)
const notice = ref<string | null>(null)

const held = (name: string) => catalogue.value?.grants[props.agent]?.includes(name) ?? false

async function load() {
  try {
    catalogue.value = await request<ResourceCatalogueView>('GET', '/api/iam/resources')
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

/** One click changes one resource: the agent then holds exactly the list shown, which the server writes as tuples. */
async function change(name: string, grant: boolean) {
  if (busy.value || !catalogue.value) return
  busy.value = true
  notice.value = null
  error.value = null
  const now = catalogue.value.grants[props.agent] ?? []
  const names = grant ? [...now, name] : now.filter((n) => n !== name)
  try {
    const answer = await request<ResourceCatalogueView | { redirect: string }>('PUT', `/api/iam/agents/${encodeURIComponent(props.agent)}/grants`, { names }).then(followStepUp)
    // 202 {redirect} (IAM mode): the page leaves for the step-up; the grant is written when Keycloak confirmed it
    if ('redirect' in answer) return
    catalogue.value = answer
    notice.value = t(grant ? 'idn.g.granted' : 'idn.g.revoked', { agent: props.agent, resource: name })
  } catch (e) {
    // OpenFGA out of reach: nothing was written, and the line says so instead of showing a state that is not one
    error.value = e instanceof ApiError && e.status === 503 ? t('idn.g.unavailable') : e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

onMounted(load)
watch(() => props.agent, load)
</script>

<template>
  <div class="identity-grants" :data-agent="agent">
    <div class="sub">{{ t('idn.g.title') }}</div>
    <div v-if="error" class="error" role="alert">{{ error }}</div>
    <div v-if="notice" class="ok" role="status">{{ notice }}</div>
    <div v-if="catalogue && !catalogue.resources.length" class="hint">{{ t('idn.g.none') }}</div>
    <div v-for="r in catalogue?.resources ?? []" :key="r.name" class="grant-row" :data-resource="r.name" :class="{ held: held(r.name) }">
      <span class="name">{{ r.name }}</span>
      <span class="state" :class="held(r.name) ? 'ready' : 'missing'">{{ held(r.name) ? t('idn.g.held') : t('idn.g.notHeld') }}</span>
      <button v-if="held(r.name)" class="small" :disabled="busy" @click="change(r.name, false)">{{ t('idn.g.revoke') }}</button>
      <button v-else class="small" :disabled="busy" @click="change(r.name, true)">{{ t('idn.g.grant') }}</button>
    </div>
    <div v-if="catalogue?.resources.length" class="hint">{{ t('idn.g.needsDelegation') }}</div>
  </div>
</template>

<style>
.identity-grants .grant-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 3px 0;
}
.identity-grants .grant-row .name {
  flex: 1;
  min-width: 0;
  font-weight: 700;
}
.identity-grants .grant-row .state {
  min-width: 90px;
  font-weight: 700;
}
.identity-grants .grant-row .state.ready {
  color: var(--green);
}
.identity-grants .grant-row .state.missing {
  color: var(--amber);
}
</style>
