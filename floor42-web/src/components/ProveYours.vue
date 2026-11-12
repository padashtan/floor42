<script setup lang="ts">
import { reactive } from 'vue'
import type { ProofView } from '../api/proof'
import { t } from '../i18n'
import { directorSteps, stepKey, witnessOf } from '../ui/proof'
import { clock } from '../ui/when'
import ProveCommand from './ProveCommand.vue'

/**
 * The director's run (ADR-0058): the steps Floor42 does not run (Docker, a desktop, the network, or no sandbox). The
 * director copies each command, runs it on their machine and records what they saw (journaled, bound to the recipe
 * and the code). Floor42 never runs these.
 */
defineProps<{ view: ProofView; acting: boolean }>()
const emit = defineEmits<{ say: [agent: string, step: number, works: boolean, seen: string] }>()
/** What the director is about to record, per step, before they press Record. */
const draft = reactive<Record<string, { works: boolean | null; seen: string }>>({})
const of = (key: string) => (draft[key] ??= { works: null, seen: '' })
</script>

<template>
  <div class="prove-section">
    <div class="hint">{{ t('prove.yoursHint') }}</div>
    <div v-if="!directorSteps(view).length" class="hint">{{ t('prove.yoursNone') }}</div>
    <ProveCommand v-for="{ worktree, step } in directorSteps(view)" :key="stepKey(worktree.agent, step.index)" :step="step">
      <div class="hint">{{ worktree.agent }}</div>
      <div v-if="witnessOf(worktree, step.index)" class="prove-said" :class="{ bad: !witnessOf(worktree, step.index)!.works }">
        {{ t('prove.saidAt', { time: clock(witnessOf(worktree, step.index)!.at) }) }}:
        {{ witnessOf(worktree, step.index)!.works ? t('prove.works') : t('prove.notWorks') }}
        <span v-if="witnessOf(worktree, step.index)!.seen">· {{ witnessOf(worktree, step.index)!.seen }}</span>
      </div>
      <div class="prove-say" role="radiogroup" :aria-label="t('prove.yoursAsk')">
        <label>
          <input type="radio" :name="stepKey(worktree.agent, step.index)" @change="of(stepKey(worktree.agent, step.index)).works = true" />
          {{ t('prove.works') }}
        </label>
        <label>
          <input type="radio" :name="stepKey(worktree.agent, step.index)" @change="of(stepKey(worktree.agent, step.index)).works = false" />
          {{ t('prove.notWorks') }}
        </label>
      </div>
      <textarea
        v-if="of(stepKey(worktree.agent, step.index)).works === false"
        v-model="of(stepKey(worktree.agent, step.index)).seen"
        rows="3"
        maxlength="4000"
        :placeholder="t('prove.seenPlaceholder')"
        :aria-label="t('prove.seenPlaceholder')"
      />
      <div>
        <button
          class="action"
          :disabled="acting || of(stepKey(worktree.agent, step.index)).works === null || (of(stepKey(worktree.agent, step.index)).works === false && !of(stepKey(worktree.agent, step.index)).seen.trim())"
          @click="emit('say', worktree.agent, step.index, !!of(stepKey(worktree.agent, step.index)).works, of(stepKey(worktree.agent, step.index)).seen)"
        >
          {{ t('prove.say') }}
        </button>
      </div>
    </ProveCommand>
  </div>
</template>

