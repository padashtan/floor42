<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ProofView, ProofWorktree } from '../api/proof'
import { t } from '../i18n'
import { proofLines, verdictLine } from '../ui/proof'

/**
 * The verdict (ADR-0058): what is still unproven, the director's verdict (journaled, bound to the recipes and the
 * code), and "Send back to the producer" for a worktree whose proof failed: the producer's ticket gets a directive with
 * the evidence and the director's recorded reports.
 */
const props = defineProps<{ view: ProofView; acting: boolean }>()
const emit = defineEmits<{ sendBack: [agent: string, note: string]; verdict: [verdict: 'PROVEN' | 'NOT_PROVEN', note: string] }>()
const notes = ref<Record<string, string>>({})
const chosen = ref<'PROVEN' | 'NOT_PROVEN' | null>(null)
const basis = ref('')

const lines = computed(() => proofLines(props.view))

/** Something to send back: a step Floor42 ran failed, one of the director's did not work, or words to say. */
function canSendBack(w: ProofWorktree): boolean {
  const floor42 = !!w.lastRun && !w.lastRun.stale && w.lastRun.steps.some((s) => s.outcome === 'FAILED')
  return floor42 || w.witnesses.some((x) => !x.works) || !!notes.value[w.agent]?.trim()
}
</script>

<template>
  <div class="prove-section">
    <div v-if="view.verdict" class="prove-verdict" :class="view.verdict.verdict">
      {{ verdictLine(view.verdict) }}
      <span v-if="view.verdict.note" class="hint">· {{ view.verdict.note }}</span>
    </div>
    <div v-if="view.verdict && !view.verdict.current" class="warn">{{ t('prove.verdictOld') }}</div>
    <ul v-if="lines.length" class="warn">
      <li v-for="(l, i) in lines" :key="i">{{ l }}</li>
    </ul>
    <h4>{{ t('prove.giveVerdict') }}</h4>
    <div class="prove-say" role="radiogroup" :aria-label="t('prove.giveVerdict')">
      <label><input v-model="chosen" type="radio" name="prove-verdict" value="PROVEN" /> {{ t('prove.verdictProven') }}</label>
      <label><input v-model="chosen" type="radio" name="prove-verdict" value="NOT_PROVEN" /> {{ t('prove.verdictNotProven') }}</label>
    </div>
    <textarea v-model="basis" rows="2" maxlength="4000" :placeholder="t('prove.verdictNote')" :aria-label="t('prove.verdictNote')" />
    <div>
      <button class="action primary" :disabled="acting || !chosen" @click="emit('verdict', chosen!, basis)">{{ t('prove.verdictRecord') }}</button>
    </div>
    <div v-for="w in view.worktrees" :key="w.agent" class="prove-back">
      <h4>{{ t('prove.sendBackTo', { agent: w.agent }) }}</h4>
      <textarea v-model="notes[w.agent]" rows="2" maxlength="4000" :placeholder="t('prove.sendBackPlaceholder')" :aria-label="t('prove.sendBackPlaceholder')" />
      <button class="action danger" :disabled="acting || !canSendBack(w)" :title="t('prove.sendBackTitle')" @click="emit('sendBack', w.agent, notes[w.agent] ?? '')">
        {{ t('prove.sendBack') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.prove-verdict {
  font-size: 16px;
  font-weight: bold;
}
.prove-back {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}
</style>
