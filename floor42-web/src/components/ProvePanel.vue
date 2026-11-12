<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { proofApi, type ProofView } from '../api/proof'
import { ApiError } from '../api/request'
import { t, tr } from '../i18n'
import { useGuard } from '../ui/guard'
import { PROVE_SECTIONS, type ProveSectionId, problemsText, proofLines, statusText } from '../ui/proof'
import ProveCommand from './ProveCommand.vue'
import ProveRun from './ProveRun.vue'
import ProveVerdict from './ProveVerdict.vue'
import ProveYours from './ProveYours.vue'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'

/**
 * "Prove the delivery" (ADR-0058), between the review and the director's acceptance, on the frame of Settings: the
 * producers' recipes (PROVE.md, their words), Floor42's run of the steps the sandbox can run (on a copy, network off,
 * never on the host), the director's own run of the others, and the verdict with "Send back to the producer". Every
 * run, report and verdict is journaled, bound to the recipe and the code it was about.
 */
const props = defineProps<{ mission: string | null }>()
const emit = defineEmits<{ close: [] }>()

const view = ref<ProofView | null>(null)
const error = ref('')
const done = ref('')
const current = ref<ProveSectionId>('recipe')
const { acting, guard } = useGuard()
let poll: ReturnType<typeof setTimeout> | undefined
/** The last load asked for; an older answer, or one after unmount, is dropped. */
let seq = 0
/** Set at unmount: nothing started by a call still pending (a run, a verdict) may load or poll again. */
let gone = false

const said = (e: unknown) => (e instanceof ApiError ? tr(`prove.error.${e.code}`, e.message) : String(e))

async function load() {
  if (gone) return
  clearTimeout(poll)
  const asked = ++seq
  if (!props.mission) return void (view.value = null)
  try {
    const answer = await proofApi.proof(props.mission)
    if (asked !== seq) return
    view.value = answer
  } catch (e) {
    if (asked !== seq) return
    error.value = said(e)
  }
  // A run goes on in the daemon: the page follows it until it ends.
  if (view.value?.running) poll = setTimeout(load, 1000)
}
// Another mission: nothing of the last one stays (its targets would go with the next verdict).
watch(() => props.mission, () => ((error.value = ''), (done.value = ''), (view.value = null), load()), { immediate: true })
onBeforeUnmount(() => ((gone = true), seq++, clearTimeout(poll)))

/** Runs one call of the screen, then shows what it answered or why it was refused. */
function act(work: (mission: string) => Promise<ProofView | null>, after?: () => void) {
  if (!props.mission) return
  const mission = props.mission
  error.value = ''
  done.value = ''
  void guard(async () => {
    try {
      const answer = await work(mission)
      if (answer && props.mission === mission) view.value = answer
      after?.()
      await load()
    } catch (e) {
      if (gone || props.mission !== mission) return
      error.value = said(e)
      // A refusal may mean the recipe or the code changed (PROOF_STALE): show what is there now.
      await load()
    }
  })
}

const run = () => act((m) => proofApi.run(m), () => (current.value = 'run'))
const say = (agent: string, step: number, works: boolean, seen: string) =>
  act((m) => proofApi.witness(m, { agent, step, works, seen, target: view.value?.worktrees.find((w) => w.agent === agent)?.target ?? null }))
const judge = (verdict: 'PROVEN' | 'NOT_PROVEN', note: string) =>
  act((m) => proofApi.verdict(m, { verdict, note, targets: view.value?.targets ?? '' }))
const sendBack = (agent: string, note: string) =>
  act(async (m) => {
    const sent = await proofApi.sendBack(m, { agent, note })
    if (!gone && props.mission === m) done.value = t('prove.sentBack', { ticket: sent.ticket, agent })
    return null
  })

const badge = (n: number) => (n ? { text: String(n) } : null)
const tabs = computed<SectionTab[]>(() =>
  PROVE_SECTIONS.map((id) => ({
    id,
    label: t(`prove.s.${id}`),
    badge: id === 'yours' && view.value ? badge(view.value.directorSteps) : null,
  })),
)
const proven = computed(() => (view.value ? proofLines(view.value).length === 0 : false))</script>

<template>
  <SectionedPanel
    class="prove-panel"
    id-prefix="prove"
    :title="t('prove.title')"
    :hint="t('prove.intro')"
    :sections="tabs"
    :current="current"
    :focus-on-open="false"
    nav-width="170px"
    @choose="(id) => (current = id as ProveSectionId)"
    @close="emit('close')"
  >
    <template #title-extra>
      <span v-if="view" class="hint">· {{ view.mission }} · {{ statusText(view) }}</span>
      <button class="action" :disabled="acting" @click="load">{{ t('prove.refresh') }}</button>
    </template>
    <template #top>
      <div v-if="!mission" class="hint">{{ t('prove.noMission') }}</div>
      <div v-if="error" class="warn" role="alert">{{ error }}</div>
      <div v-if="done" class="hint" role="status">{{ done }}</div>
    </template>
    <template v-if="view" #default>
      <h3 class="prove-title">{{ t(`prove.s.${current}`) }}</h3>
      <div v-if="current === 'recipe'" class="prove-section">
        <div class="hint">{{ t('prove.recipeHint') }}</div>
        <template v-for="w in view.worktrees" :key="w.agent">
          <h4>{{ w.agent }} <span class="hint">{{ w.path }}</span></h4>
          <div v-if="w.state === 'MISSING'" class="warn">{{ t('prove.missing') }}</div>
          <div v-else-if="w.state === 'UNKNOWN'" class="warn">
            {{ t('prove.unknown', { why: problemsText(w.problems) }) }}
          </div>
          <div v-else-if="w.problems.length" class="warn">
            {{ problemsText(w.problems) }}
          </div>
          <ProveCommand v-for="s in w.steps" :key="s.index" :step="s" />
        </template>
      </div>
      <ProveRun v-else-if="current === 'run'" :view="view" :acting="acting" @run="run" />
      <ProveYours v-else-if="current === 'yours'" :view="view" :acting="acting" @say="say" />
      <ProveVerdict v-else :view="view" :acting="acting" @send-back="sendBack" @verdict="judge" />
      <div v-if="current !== 'verdict'" class="hint prove-foot">{{ t(proven ? 'prove.verdict.PROVEN' : 'prove.verdict.INCOMPLETE') }}</div>
    </template>
  </SectionedPanel>
</template>

<style>
.prove-title {
  margin: 0 0 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid #1d4a57;
  color: var(--cyan);
  font-size: 15px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.prove-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  line-height: 1.5;
}
.prove-section h4 {
  margin: 6px 0 0;
}
.prove-foot {
  margin-top: 12px;
}
.prove-say {
  display: flex;
  gap: 16px;
}
.prove-section textarea {
  font: inherit;
  width: 100%;
}
.prove-cmd pre,
.prove-output {
  margin: 0;
  padding: 6px 8px;
  background: #061a21;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.prove-verdict.PROVEN,
.prove-outcome.PASSED,
.prove-said {
  color: #5fd38d;
}
.prove-verdict.NOT_PROVEN,
.prove-outcome.FAILED,
.prove-said.bad {
  color: #e06060;
}
</style>
