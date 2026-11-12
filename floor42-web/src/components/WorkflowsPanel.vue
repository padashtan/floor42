<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type { CheckView, ImportProblemView, StepView, TaskView, VersionView, WorkflowStepKind, WorkflowView, WorkflowsView } from '../api/types'
import { t, tr } from '../i18n'
import { confirmed } from '../state/confirm'
import { why } from '../ui/why'
import { downloadWorkflow, importProblemText } from '../ui/workflowFile'
import { mayLeaveWorkflow, workflowDirty } from '../ui/workflowGuard'
import { changed, nameKey, problemsOf, refusalKey, type Selection } from '../ui/workflowRows'
import { fresh, stepLine, toggle, withMaxTickets } from '../ui/workflows'
import { applyCheck, blankCheck, checksOf, moveCheck, removeCheck, savedSteps, tasksOf } from '../ui/workflowChecks'
import { applyTask, blankTask, moveTask, removeTask } from '../ui/workflowTaskEdit'
import WorkflowImport from './WorkflowImport.vue'
import WorkflowLibrary from './WorkflowLibrary.vue'
import WorkflowStepDialog from './WorkflowStepDialog.vue'
import WorkflowTaskDialog from './WorkflowTaskDialog.vue'
import WorkflowTimeline from './WorkflowTimeline.vue'
import WorkflowVersions from './WorkflowVersions.vue'

/**
 * The company's workflows (ADR-0050, ADR-0052): the library on the left, the selected workflow on the right as a
 * timeline, read only until "Edit" turns it editable in place. A new mission pins a copy of the one it picks: editing or
 * removing one here never changes a mission already created.
 */
const emit = defineEmits<{ close: [] }>()

interface Draft {
  name: string
  steps: StepView[]
  /** What the draft started from, to know whether it changed. */
  from: { name: string; steps: StepView[] }
  /** The saved name this draft replaces; null for a new name. */
  replaces: string | null
  /** The planner's proposal it adopts. */
  proposal: number | null
  /** Read from a file: the director reads the steps before saving. */
  imported: boolean
}

const list = ref<WorkflowsView | null>(null)
const error = ref<string | null>(null)
const busy = ref(false)
const selected = ref<Selection>({ kind: 'builtin' })
const draft = ref<Draft | null>(null)
const importProblems = ref<ImportProblemView[]>([])
const library = ref<InstanceType<typeof WorkflowLibrary>>()

const proposal = computed(() =>
  selected.value.kind === 'proposal' ? list.value?.proposals.find((p) => p.number === (selected.value as { number: number }).number) : undefined,
)
const template = computed(() =>
  selected.value.kind === 'template' ? list.value?.templates.find((x) => x.key === (selected.value as { key: string }).key) : undefined,
)
const company = computed(() =>
  selected.value.kind === 'company' ? list.value?.workflows.find((w) => w.name === (selected.value as { name: string }).name) : undefined,
)
/** The workflow shown: null when this build cannot read it. */
const shown = computed<WorkflowView | null>(() => {
  if (selected.value.kind === 'builtin') return list.value?.builtIn ?? null
  if (selected.value.kind === 'template') return template.value?.workflow ?? null
  if (selected.value.kind === 'proposal') return proposal.value?.workflow ?? null
  if (selected.value.kind === 'company') return company.value?.workflow ?? null
  return null
})
const steps = computed(() => draft.value?.steps ?? shown.value?.steps ?? [])
const taken = computed(() => {
  const d = draft.value
  if (!d) return false
  const k = nameKey(d.name)
  return !!list.value?.workflows.some((w) => nameKey(w.name) === k && !(d.replaces !== null && nameKey(d.replaces) === k))
})
const problems = computed(() => {
  if (!draft.value) return []
  return problemsOf(draft.value.steps, draft.value.name, taken.value)
})
/** The check the dialog edits: its index, or null for a new one. */
const dialog = ref<{ check: CheckView; index: number | null } | null>(null)
/** The task the dialog edits: its index, or null for a new one. */
const taskDialog = ref<{ task: TaskView; index: number | null } | null>(null)
const replacing = computed(() => !!draft.value?.replaces && nameKey(draft.value.replaces) === nameKey(draft.value.name))
const heading = computed(() => {
  if (draft.value && !draft.value.replaces && selected.value.kind !== 'proposal') {
    return draft.value.name.trim() || t(selected.value.kind === 'new' ? 'workflows.new' : 'workflows.tl.newFromBuiltIn')
  }
  if (selected.value.kind === 'builtin') return t('workflows.builtIn')
  if (selected.value.kind === 'template') return tr(`workflows.tpl.${selected.value.key}`, selected.value.key)
  if (selected.value.kind === 'proposal') return t('workflows.proposedBy', { agent: proposal.value?.by ?? '', mission: proposal.value?.mission ?? '', name: shown.value?.name ?? '' })
  return company.value?.name ?? ''
})

watch(
  () => draft.value && changed(draft.value, draft.value.from),
  (dirty) => (workflowDirty.value = !!dirty),
  { deep: true },
)
onBeforeUnmount(() => (workflowDirty.value = false))

async function load() {
  await act(() => api.workflows())
}

async function act(action: () => Promise<WorkflowsView>): Promise<boolean> {
  busy.value = true
  try {
    list.value = await action()
    error.value = null
    return true
  } catch (e) {
    error.value = t(e instanceof ApiError ? refusalKey(e.code) : 'workflows.err.generic')
    return false
  } finally {
    busy.value = false
  }
}

function begin(from: WorkflowView, name: string, replaces: string | null, proposalNumber: number | null) {
  importProblems.value = []
  const steps = from.steps.map((s) => ({ ...s }))
  draft.value = { name, steps, from: { name, steps: steps.map((s) => ({ ...s })) }, replaces, proposal: proposalNumber, imported: false }
}

async function select(s: Selection) {
  if (!(await mayLeaveWorkflow())) return
  draft.value = null
  importProblems.value = []
  workflowDirty.value = false
  selected.value = s
}

async function startNew() {
  if (!list.value || !(await mayLeaveWorkflow())) return
  selected.value = { kind: 'new' }
  begin({ name: '', steps: fresh(list.value.builtIn), mode: 'loop' }, '', null, null)
}

function startEdit() {
  const s = selected.value
  if (!shown.value) return
  if (s.kind === 'company') begin(shown.value, s.name, s.name, null)
  else if (s.kind === 'proposal') begin(shown.value, shown.value.name, null, s.number)
  else begin(shown.value, '', null, null)
}

/** A checked file opens in the editor as a new workflow: unsaved, so leaving it asks first. */
async function onImported(w: WorkflowView) {
  if (!(await mayLeaveWorkflow())) return
  selected.value = { kind: 'new' }
  begin(w, w.name, null, null)
  if (draft.value) Object.assign(draft.value, { imported: true, from: { name: '', steps: [] } })
}

async function restore(v: VersionView) {
  if (company.value && v.workflow) await act(() => api.saveWorkflow(company.value!.name, savedSteps(v.workflow!.steps), true, null))
}

async function exportFile() {
  if (selected.value.kind !== 'company') return
  try {
    await downloadWorkflow(selected.value.name)
  } catch {
    // Handled: nothing was written; the general error stands.
    error.value = t('workflows.err.generic')
  }
}

async function cancel() {
  if (!(await mayLeaveWorkflow())) return
  workflowDirty.value = false
  if (selected.value.kind === 'new') selected.value = { kind: 'builtin' }
  draft.value = null
}

async function save() {
  const d = draft.value
  if (!d || problems.value.length) return
  const name = d.name.trim()
  if (!(await act(() => api.saveWorkflow(name, savedSteps(d.steps), replacing.value, d.proposal)))) return
  workflowDirty.value = false
  draft.value = null
  selected.value = { kind: 'company', name }
}

async function remove() {
  const s = selected.value
  if (s.kind !== 'company' || !(await confirmed({ message: t('workflows.removeConfirm', { name: s.name }), danger: true }))) return
  if (await act(() => api.removeWorkflow(s.name))) selected.value = { kind: 'builtin' }
}

async function dismiss() {
  if (proposal.value && (await act(() => api.dismissWorkflowProposal(proposal.value!.number)))) selected.value = { kind: 'builtin' }
}

const toggleStep = (k: WorkflowStepKind) => draft.value && (draft.value.steps = toggle(draft.value.steps, k))
const steps$ = (f: (s: StepView[]) => StepView[]) => draft.value && (draft.value.steps = f(draft.value.steps))
const editCheck = (i: number) => (dialog.value = { check: { ...checksOf(steps.value)[i] }, index: i })
const applied = (c: CheckView) => {
  steps$((s) => applyCheck(s, c, dialog.value?.index ?? null))
  dialog.value = null
}
const editTask = (i: number) => (taskDialog.value = { task: { ...tasksOf(steps.value)[i] }, index: i })
const appliedTask = (k: TaskView) => {
  steps$((s) => applyTask(s, k, taskDialog.value?.index ?? null))
  taskDialog.value = null
}
const setMax = (v: string) => draft.value && (draft.value.steps = withMaxTickets(draft.value.steps, v === '' ? null : Number(v)))

onMounted(load)
</script>

<template>
  <div class="panel workflows-panel framed">
    <div class="title">
      {{ t('workflows.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll">
      <div v-if="error" class="hint bad" role="alert">{{ error }}</div>
      <div class="workflow-panes">
        <WorkflowLibrary ref="library" :list="list" :selected="selected" :busy="busy" @select="select" @new="startNew">
          <WorkflowImport
            :busy="busy"
            @imported="onImported"
            @problems="(p) => (importProblems = p)"
            @failed="error = t('workflows.err.generic')"
          />
        </WorkflowLibrary>
        <section class="workflow-detail" :aria-label="heading">
          <button class="action tl-back" @click="library?.focus()">{{ t('workflows.lib.back') }}</button>
          <div class="tl-head">
            <h3>{{ heading }}</h3>
            <button v-if="shown && !draft" class="action" :disabled="busy" @click="startEdit">
              {{ t(selected.kind === 'company' ? 'workflows.edit' : selected.kind === 'proposal' ? 'workflows.adopt' : 'workflows.copy') }}
            </button>
          </div>
          <div v-if="importProblems.length" class="imp-problems" role="alert">
            <div>{{ t('workflows.imp.title', { count: importProblems.length }) }}</div>
            <ul>
              <li v-for="(p, i) in importProblems" :key="i" :data-code="p.code">{{ importProblemText(p) }}</li>
            </ul>
          </div>
          <div v-if="draft?.imported" class="hint" role="status">{{ t('workflows.imp.banner') }}</div>
          <div v-else-if="draft?.proposal != null" class="hint">{{ t('workflows.adopting', { number: draft.proposal }) }}</div>
          <div v-else-if="!draft && template" class="hint">
            {{ tr(`workflows.tpl.desc.${template.key}`, '') }} {{ stepLine(template.workflow, t) }}
          </div>
          <div v-else-if="!draft && shown" class="hint">{{ stepLine(shown, t) }}</div>
          <div v-else-if="!draft && selected.kind !== 'new'" class="hint bad">{{ t('workflows.unreadable') }}</div>
          <WorkflowTimeline
            v-if="draft || shown"
            :steps="steps"
            :editing="!!draft"
            :name="draft?.name ?? ''"
            :problems="problems"
            :busy="busy"
            @toggle="toggleStep"
            @max-tickets="setMax"
            @update:name="(v) => draft && (draft.name = v)"
            @add-check="dialog = { check: blankCheck(), index: null }"
            @edit-check="editCheck"
            @remove-check="(i) => steps$((s) => removeCheck(s, i))"
            @move-check="(i, by) => steps$((s) => moveCheck(s, i, by))"
            @add-task="taskDialog = { task: blankTask(), index: null }"
            @edit-task="editTask"
            @remove-task="(i) => steps$((s) => removeTask(s, i))"
            @move-task="(i, by) => steps$((s) => moveTask(s, i, by))"
          />
          <WorkflowTaskDialog
            v-if="taskDialog"
            :task="taskDialog.task"
            :adding="taskDialog.index === null"
            :production="steps.some((s) => s.step === 'produce')"
            @apply="appliedTask"
            @cancel="taskDialog = null"
          />
          <WorkflowStepDialog v-if="dialog" :check="dialog.check" :adding="dialog.index === null" @apply="applied" @cancel="dialog = null" />
          <WorkflowVersions v-if="company && !draft" :workflow="company" :busy="busy" @restore="restore" />
          <div v-if="draft" class="tl-foot">
            <span class="hint tl-pin">{{ t('workflows.pinHint') }}</span>
            <span class="hint" :class="{ warn: problems.length }">
              {{ problems.length ? t(problems.length === 1 ? 'workflows.tl.oneProblem' : 'workflows.tl.problems', { count: problems.length }) : '' }}
            </span>
            <button
              class="action recommended"
              :disabled="busy || problems.length > 0"
              :title="why([problems.length > 0, problems.length ? t(problems[0].key, problems[0].params) : ''])"
              @click="save"
            >{{ replacing ? t('workflows.saveReplace') : t('workflows.save') }}</button>
            <button class="action" :disabled="busy" @click="cancel">{{ t('workflows.cancel') }}</button>
          </div>
          <div v-else class="tl-foot">
            <button v-if="company?.workflow" class="action" :disabled="busy" @click="exportFile">{{ t('workflows.export') }}</button>
            <button v-if="selected.kind === 'company'" class="action" :disabled="busy" @click="remove">{{ t('workflows.remove') }}</button>
            <button v-if="selected.kind === 'proposal'" class="action" :disabled="busy" @click="dismiss">{{ t('workflows.dismiss') }}</button>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>
