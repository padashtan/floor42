<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { proofApi, type ProofView } from '../api/proof'
import { t, tr } from '../i18n'
import { stamp } from '../ui/when'
import { proofLines, statusText, verdictLine } from '../ui/proof'

/**
 * The proof in the end-of-mission report (ADR-0058): who verified (Floor42 in the sandbox, the director by hand), when,
 * on which commit, what came out, and the director's verdict, all as journaled.
 */
const props = defineProps<{ mission: string }>()
const emit = defineEmits<{ prove: [] }>()
const view = ref<ProofView | null>(null)
const failed = ref(false)

async function load() {
  try {
    view.value = await proofApi.proof(props.mission)
    failed.value = false
  } catch {
    // Handled: the section says the proof could not be read, never that it passed.
    view.value = null
    failed.value = true
  }
}
watch(() => props.mission, load, { immediate: true })
const lines = computed(() => (view.value ? proofLines(view.value) : []))
const when = stamp
const count = (w: ProofView['worktrees'][number], outcome: string) =>
  w.lastRun ? w.lastRun.steps.filter((s) => s.outcome === outcome).length : 0
</script>

<template>
  <div class="me-section">
    <div v-if="failed" class="warn" role="alert">{{ t('prove.line.cannotCheck') }}</div>
    <template v-if="view">
      <div><b>{{ statusText(view) }}</b></div>
      <div v-if="view.unprovenAccepted" class="warn">{{ t('prove.reportOverride', { state: tr(`prove.state.${view.unprovenAccepted}`, view.unprovenAccepted) }) }}</div>
      <ul v-if="lines.length" class="warn">
        <li v-for="(l, i) in lines" :key="i">{{ l }}</li>
      </ul>
      <div v-for="w in view.worktrees" :key="w.agent" class="me-row">
        <div class="main">
          <b>{{ w.agent }}</b>
          <span class="hint">{{ t(`prove.recipeState.${w.state}`, { steps: w.steps.length }) }}</span>
          <span v-if="w.lastRun" class="hint">
            {{ t('prove.reportRun', { time: when(w.lastRun.at), head: w.lastRun.head.slice(0, 8), passed: count(w, 'PASSED'), failed: count(w, 'FAILED') }) }}
          </span>
          <span v-if="w.lastRun?.stale" class="warn">{{ t('prove.stale') }}</span>
          <span v-if="w.witnesses.length" class="hint">
            {{ t('prove.reportYours', { works: w.witnesses.filter((x) => x.works).length, fails: w.witnesses.filter((x) => !x.works).length }) }}
          </span>
        </div>
      </div>
      <div v-if="view.verdict" class="hint">
        {{ verdictLine(view.verdict) }}
        <span v-if="!view.verdict.current" class="warn">· {{ t('prove.verdictOld') }}</span>
      </div>
    </template>
    <div>
      <button class="action primary" @click="emit('prove')">{{ t('prove.open') }}</button>
    </div>
  </div>
</template>
