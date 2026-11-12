<script setup lang="ts">
import { computed } from 'vue'
import type { StepView, WorkflowStepKind } from '../api/types'
import { t } from '../i18n'
import type { Key } from '../i18n'
import { MAX_TICKETS, OPTIONAL } from '../ui/workflows'
import { checksOf, keepsProduction as keepsProductionFor, MAX_CHECKS, tasksOf } from '../ui/workflowChecks'
import { MAX_TASKS, neighbour } from '../ui/workflowTaskEdit'
import { type Problem, type RowKey, rowsOf } from '../ui/workflowRows'
import { why } from '../ui/why'

/**
 * The selected workflow as a vertical timeline (ADR-0052). Read only, or editable in place: the same rows either way.
 * Fixed steps carry a lock whose reason shows on hover and on focus; each problem stands under its row.
 */
const props = defineProps<{ steps: StepView[]; editing: boolean; name: string; problems: Problem[]; busy: boolean }>()
const emit = defineEmits<{
  toggle: [WorkflowStepKind]
  maxTickets: [string]
  'update:name': [string]
  addCheck: []
  editCheck: [number]
  removeCheck: [number]
  moveCheck: [number, -1 | 1]
  addTask: []
  editTask: [number]
  removeTask: [number]
  moveTask: [number, -1 | 1]
}>()

const rows = computed(() => rowsOf(props.steps, props.editing))
const plan = computed(() => props.steps.find((s) => s.step === 'plan') ?? null)
const under = (row: RowKey) => props.problems.filter((p) => p.row === row)
const label = (k: WorkflowStepKind) => t(`workflows.step.${k}` as Key)
const idOf = (k: string) => `wf-row-${k}`
/** The director's checks, in their slot after the review (ADR-0052); production stays while one is there. */
const checks = computed(() => checksOf(props.steps))
const ofCheck = (i: number) => props.problems.filter((p) => p.row === 'check' && p.index === i)
const slotProblems = computed(() => props.problems.filter((p) => p.row === 'check' && p.index === undefined))
/** The director's tasks (ADR-0061): shown with their slot and runner; edited in place. */
const tasks = computed(() => tasksOf(props.steps))
const taskProblems = computed(() => props.problems.filter((p) => p.row === 'task' && p.index === undefined))
const ofTask = (i: number) => props.problems.filter((p) => p.row === 'task' && p.index === i)
const keepsProduction = (k: WorkflowStepKind) => k === 'produce' && keepsProductionFor(props.steps)
</script>

<template>
  <ol class="timeline" :aria-label="t('workflows.tl.steps')">
    <li v-if="editing" class="tl-row tl-name">
      <label class="tl-label" for="wf-name">{{ t('workflows.tl.name') }}</label>
      <input
        id="wf-name"
        :value="name"
        maxlength="60"
        :aria-invalid="under('name').length ? 'true' : undefined"
        :aria-describedby="`${idOf('name-hint')}${under('name').length ? ' ' + idOf('name-problem') : ''}`"
        @input="emit('update:name', ($event.target as HTMLInputElement).value)"
      />
      <div :id="idOf('name-hint')" class="hint">{{ t('workflows.name') }}</div>
      <div v-for="p in under('name')" :id="idOf('name-problem')" :key="p.key" class="hint warn">{{ t(p.key) }}</div>
    </li>
    <template v-for="r in rows" :key="r.kind">
    <li class="tl-row" :class="{ off: !r.present, fixed: !!r.locked }" :data-step="r.kind">
      <span class="tl-dot" aria-hidden="true"></span>
      <div class="tl-body">
        <div class="tl-line">
          <span class="tl-title">{{ label(r.kind) }}</span>
          <span class="tl-desc">{{ t(`workflows.tl.desc.${r.kind}` as Key) }}</span>
          <template v-if="r.kind === 'plan' && plan && r.present">
            <label v-if="editing" class="tl-max">
              {{ t('workflows.maxTickets') }}
              <input
                type="number"
                min="1"
                :max="MAX_TICKETS"
                :value="plan.maxTickets ?? MAX_TICKETS"
                @change="emit('maxTickets', ($event.target as HTMLInputElement).value)"
              />
            </label>
            <span v-else-if="plan.maxTickets !== null && plan.maxTickets !== MAX_TICKETS" class="facts">
              {{ t('workflows.planAtMost', { count: plan.maxTickets }) }}
            </span>
          </template>
          <span v-if="r.locked && r.present" class="tl-lock">
            <span
              class="lock"
              tabindex="0"
              role="img"
              :aria-label="t('workflows.tl.fixed')"
              :aria-describedby="idOf(`why-${r.kind}`)"
            >
              <svg viewBox="0 0 12 14" width="12" height="14" aria-hidden="true">
                <rect x="1.5" y="6" width="9" height="7" fill="currentColor" />
                <path d="M3.5 6V4a2.5 2.5 0 0 1 5 0v2" fill="none" stroke="currentColor" stroke-width="1.4" />
              </svg>
            </span>
          </span>
          <label
            v-else-if="editing && OPTIONAL.includes(r.kind)"
            class="tl-keep"
            :title="why([keepsProduction(r.kind), t('workflows.chk.keepsProduction')])"
          >
            <input type="checkbox" :checked="r.present" :disabled="busy || keepsProduction(r.kind)" @change="emit('toggle', r.kind)" />
            {{ r.present ? t('workflows.tl.keep') : t('workflows.tl.leftOut') }}
          </label>
        </div>
        <div v-if="r.locked && r.present" :id="idOf(`why-${r.kind}`)" class="hint lock-why" role="tooltip">{{ t(r.locked) }}</div>
        <div v-for="p in under(r.kind)" :key="p.key" class="hint warn tl-problem">{{ t(p.key) }}</div>
      </div>
    </li>
    <li v-if="r.kind === 'review' && r.present && (editing || checks.length)" class="tl-row tl-slot" data-slot="checks">
      <div class="tl-line">
        <span class="tl-desc">{{ t('workflows.chk.slot.checks') }}</span>
        <button
          v-if="editing"
          class="action tl-add"
          :disabled="busy || checks.length >= MAX_CHECKS"
          :title="why([checks.length >= MAX_CHECKS, t('workflows.chk.err.TOO_MANY_STEPS')])"
          @click="emit('addCheck')"
        >{{ t('workflows.chk.add') }}</button>
      </div>
      <ol class="tl-checks">
        <li v-for="(c, i) in checks" :key="c.key || i" class="tl-check" :data-check="c.key">
          <div class="tl-line">
            <span class="tl-title">{{ c.title }}</span>
            <span class="tl-desc">{{ t('workflows.chk.runner.reviewer') }}, {{ c.blocks ? t('workflows.chk.blocksShort') : t('workflows.chk.advisoryShort') }}</span>
            <template v-if="editing">
              <button class="action" :disabled="busy || i === 0" :title="why([i === 0, t('workflows.chk.firstInSlot')])" :aria-label="t('workflows.chk.up', { title: c.title })" @click="emit('moveCheck', i, -1)">↑</button>
              <button class="action" :disabled="busy || i === checks.length - 1" :title="why([i === checks.length - 1, t('workflows.chk.lastInSlot')])" :aria-label="t('workflows.chk.down', { title: c.title })" @click="emit('moveCheck', i, 1)">↓</button>
              <button class="action" :disabled="busy" @click="emit('editCheck', i)">{{ t('workflows.chk.edit') }}</button>
              <button class="action" :disabled="busy" @click="emit('removeCheck', i)">{{ t('workflows.chk.remove') }}</button>
            </template>
          </div>
          <div v-if="!editing" class="hint">{{ c.does }}</div>
          <div v-if="!editing" class="hint">{{ t('workflows.chk.whyLine', { why: c.why }) }}</div>
          <div v-for="p in ofCheck(i)" :key="p.key" class="hint warn tl-problem">{{ t(p.key, p.params) }}</div>
        </li>
      </ol>
      <div v-for="p in slotProblems" :key="p.key" class="hint warn tl-problem">{{ t(p.key) }}</div>
    </li>
    </template>
    <li v-if="editing || tasks.length" class="tl-row tl-slot" data-slot="tasks">
      <div class="tl-line">
        <span class="tl-desc">{{ t('workflows.task.slot') }}</span>
        <button
          v-if="editing"
          class="action tl-add"
          :disabled="busy || tasks.length >= MAX_TASKS"
          :title="why([tasks.length >= MAX_TASKS, t('workflows.task.err.TOO_MANY')])"
          @click="emit('addTask')"
        >{{ t('workflows.task.add') }}</button>
      </div>
      <ol class="tl-checks">
        <li v-for="(k, i) in tasks" :key="k.key" class="tl-check" :data-task="k.key">
          <div class="tl-line">
            <span class="tl-title">{{ k.title }}</span>
            <span class="tl-desc">{{ t(`workflows.task.at.${k.slot}` as Key) }}, {{ t(`workflows.task.runner.${k.runBy}` as Key) }}, {{ k.reviewed ? t('workflows.task.reviewed') : t('workflows.task.unreviewed') }}</span>
            <template v-if="editing">
              <button class="action" :disabled="busy || neighbour(tasks, i, -1) < 0" :title="why([neighbour(tasks, i, -1) < 0, t('workflows.task.firstInSlot')])" :aria-label="t('workflows.chk.up', { title: k.title })" @click="emit('moveTask', i, -1)">↑</button>
              <button class="action" :disabled="busy || neighbour(tasks, i, 1) < 0" :title="why([neighbour(tasks, i, 1) < 0, t('workflows.task.lastInSlot')])" :aria-label="t('workflows.chk.down', { title: k.title })" @click="emit('moveTask', i, 1)">↓</button>
              <button class="action" :disabled="busy" @click="emit('editTask', i)">{{ t('workflows.chk.edit') }}</button>
              <button class="action" :disabled="busy" @click="emit('removeTask', i)">{{ t('workflows.chk.remove') }}</button>
            </template>
          </div>
          <div v-if="!editing" class="hint">{{ k.does }}</div>
          <div v-if="!editing" class="hint">{{ t('workflows.chk.whyLine', { why: k.why }) }}</div>
          <div v-for="p in ofTask(i)" :key="p.key" class="hint warn tl-problem">{{ t(p.key, p.params) }}</div>
        </li>
      </ol>
      <div v-for="p in taskProblems" :key="p.key" class="hint warn tl-problem">{{ t(p.key) }}</div>
    </li>
  </ol>
</template>
