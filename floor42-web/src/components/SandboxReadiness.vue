<script setup lang="ts">
import { computed, h, onBeforeUnmount, ref, watch, type FunctionalComponent } from 'vue'
import { api, ApiError } from '../api/client'
import type { Checklist, ChecklistRow, SandboxReadiness, ToolLine } from '../api/types'
import { t, tr, dateLocale } from '../i18n'
import { itemKey, purposeKey } from '../ui/checklist'
import { normalize } from '../ui/sectioned'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'

/**
 * Sandbox readiness (ADR-0033): the tools the project needs, what the WSL sandbox really has (Floor42's last measure,
 * journaled), and the commands for the director to install the rest. Floor42 never runs those commands: no button here
 * installs anything. Re-measure is one run in the sandbox, 30 s at most.
 */
const props = withDefaults(defineProps<{ project?: string | null; check?: string | null; framed?: boolean }>(), {
  project: null,
  check: null,
  framed: false,
})
const emit = defineEmits<{ close: [] }>()

const view = ref<SandboxReadiness | null>(null)
const error = ref('')
const busy = ref(false)
const copied = ref(false)

const script = computed(() => (view.value?.commands ?? []).join('\n'))
const measuredAt = computed(() => (view.value?.measuredAt ? new Date(view.value.measuredAt).toLocaleString(dateLocale()) : null))
const needed = computed(() => (view.value?.tools ?? []).filter((x) => x.because.length > 0))
const extra = computed(() => (view.value?.tools ?? []).filter((x) => x.because.length === 0))

function message(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

/**
 * The machine checklist (the same list as `floor42 doctor`), in the panel only: what Floor42 requires and what the
 * sandbox has, each with the command to copy. Nothing here runs a command.
 */
const checklist = ref<Checklist | null>(null)
const copiedRow = ref<string | null>(null)

async function loadChecklist() {
  if (!props.framed) return
  try {
    checklist.value = await api.machineChecklist()
  } catch (e) {
    checklist.value = null
    error.value = message(e)
  }
}

function rowName(r: ChecklistRow): string {
  const key = itemKey(r)
  return key ? tr(key, r.id, { distro: checklist.value?.distro ?? '' }) : r.id
}

async function copyRow(r: ChecklistRow) {
  if (!r.command) return
  try {
    await navigator.clipboard.writeText(r.command)
    copiedRow.value = `${r.sandbox}:${r.id}`
    window.setTimeout(() => (copiedRow.value = null), 1500)
  } catch {
    copiedRow.value = null
  }
}

async function load() {
  void loadChecklist()
  try {
    view.value = await api.sandboxReadiness(props.project || null, props.check || null)
    error.value = ''
  } catch (e) {
    view.value = null
    // A path still being typed is not a folder yet: nothing to say about it.
    error.value = e instanceof ApiError && e.status === 404 ? '' : message(e)
  }
}

async function remeasure() {
  busy.value = true
  try {
    view.value = await api.probeSandbox(props.project || null, props.check || null)
    error.value = ''
    void loadChecklist()
  } catch (e) {
    error.value = message(e)
  } finally {
    busy.value = false
  }
}

async function copy() {
  try {
    await navigator.clipboard.writeText(script.value)
    copied.value = true
    window.setTimeout(() => (copied.value = false), 1500)
  } catch {
    copied.value = false
  }
}

/** The tool's state in words: MISSING only comes from a measure. */
function stateText(x: ToolLine): string {
  return tr(`sandbox.state.${x.state}`, x.state)
}

// The project path is typed or picked: read it again once the director stops typing.
let timer: number | undefined
watch(
  () => [props.project, props.check],
  () => {
    window.clearTimeout(timer)
    timer = window.setTimeout(() => void load(), 400)
  },
  { immediate: true },
)
onBeforeUnmount(() => window.clearTimeout(timer))

// Framed, the panel shows one section at a time (the machine, the tools, the install commands, the environment);
// embedded in a form it shows them all, in this order, without the machine checklist.
type Part = 'machine' | 'tools' | 'install' | 'environment'
const current = ref<Part>('machine')
const query = ref('')
const searching = computed(() => props.framed && query.value.trim().length > 0)
/** While searching, the machine rows and the tools that match are shown together; else the chosen section. */
const show = (id: Part) => !props.framed || (searching.value ? id === 'machine' || id === 'tools' : current.value === id)
const matches = (text: string) => normalize(text).includes(normalize(query.value))
const rows = computed(() => (checklist.value?.rows ?? []).filter((r) => !searching.value || matches(`${rowName(r)} ${r.id}`)))
const toolRows = computed(() => [...needed.value, ...extra.value].filter((x) => !searching.value || matches(x.tool)))
const tabs = computed<SectionTab[]>(() => {
  const missing = (view.value?.tools ?? []).filter((x) => x.state === 'MISSING').length
  const commands = view.value?.commands.length ?? 0
  return [
    { id: 'machine', label: t('sandbox.s.machine'), badge: checklist.value && !checklist.value.ready ? { text: '!', tone: 'changed' } : null },
    { id: 'tools', label: t('sandbox.s.tools'), badge: missing ? { text: String(missing), tone: 'changed' } : null },
    { id: 'install', label: t('sandbox.s.install'), badge: commands ? { text: String(commands) } : null },
    { id: 'environment', label: t('sandbox.s.environment') },
  ]
})
/** Embedded in a form: a titled block, no frame. */
const Plain: FunctionalComponent = (_props, { slots }) =>
  h('div', { class: 'sandbox-readiness' }, [h('div', { class: 'sub' }, t('sandbox.title')), slots.default?.()])
const frame = computed(() =>
  props.framed
    ? {
        is: SectionedPanel,
        bind: {
          class: 'sandbox-panel',
          idPrefix: 'sandbox',
          title: t('sandbox.title'),
          hint: t('sandbox.intro'),
          searchable: true,
          query: query.value,
          'onUpdate:query': (v: string) => (query.value = v),
          sections: tabs.value,
          current: current.value,
          focusOnOpen: false,
          navWidth: '170px',
          onChoose: (id: string) => (current.value = id as Part),
          onClose: () => emit('close'),
        },
      }
    : { is: Plain, bind: {} },
)
</script>

<template>
  <component :is="frame.is" v-bind="frame.bind">
    <div :class="framed ? 'settings-form sb-sections' : 'settings-form'">
      <div v-if="error" class="hint bad">{{ error }}</div>
      <section v-if="checklist && show('machine')" class="sb-block">
        <div class="sub">{{ t('checklist.title') }}</div>
        <div class="hint">{{ t('checklist.intro') }}</div>
        <div :class="checklist.ready ? 'hint' : 'hint warn'" class="checklist-verdict">
          {{ checklist.ready ? t('checklist.ready') : t('checklist.notReady') }}
        </div>
        <table class="sandbox-tools machine-checklist">
          <thead>
            <tr>
              <th>{{ t('checklist.col.item') }}</th>
              <th>{{ t('checklist.col.for') }}</th>
              <th>{{ t('checklist.col.state') }}</th>
              <th>{{ t('checklist.col.command') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="`${r.sandbox}:${r.id}`" :data-row="`${r.sandbox ? 'sandbox' : 'host'}-${r.id}`">
              <td>
                <b>{{ rowName(r) }}</b>
                <span v-if="r.sandbox" class="facts"> · {{ t('checklist.inSandbox') }}</span>
              </td>
              <td>
                {{ tr(purposeKey(r), r.id) }}
                <div v-if="r.note" class="hint warn">{{ tr(`checklist.note.${r.note}`, r.note) }}</div>
                <div v-if="r.measuredAt" class="facts">
                  {{ t('sandbox.measuredAt', { time: new Date(r.measuredAt).toLocaleString(dateLocale()) }) }}
                </div>
              </td>
              <td :class="`state-${r.state.toLowerCase()}`" :title="r.version ?? ''">
                {{ tr(`checklist.state.${r.state}`, r.state) }}
                <span v-if="r.version && r.state === 'PRESENT'" class="facts"> · {{ r.version }}</span>
              </td>
              <td>
                <template v-if="r.command && r.state !== 'PRESENT'">
                  <code class="checklist-command">{{ r.command }}</code>
                  <button class="action" type="button" @click="copyRow(r)">
                    {{ copiedRow === `${r.sandbox}:${r.id}` ? t('checklist.copied') : t('checklist.copy') }}
                  </button>
                </template>
                <span v-else class="facts">—</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="hint">{{ t('checklist.doctor') }}</div>
      </section>
      <template v-if="view">
        <section v-if="show('tools')" class="sb-block">
          <div class="hint">
            <span v-if="measuredAt">{{ t('sandbox.measuredAt', { time: measuredAt }) }}</span>
            <span v-else class="warn">{{ t('sandbox.neverMeasured') }}</span>
          </div>
          <div>
            <button class="action" type="button" :disabled="busy" @click="remeasure">
              {{ busy ? t('sandbox.measuring') : t('sandbox.remeasure') }}
            </button>
            <span class="hint"> {{ t('sandbox.remeasureHint') }}</span>
          </div>
          <table v-if="toolRows.length" class="sandbox-tools">
            <thead>
              <tr>
                <th>{{ t('sandbox.col.tool') }}</th>
                <th>{{ t('sandbox.col.need') }}</th>
                <th>{{ t('sandbox.col.state') }}</th>
                <th>{{ t('sandbox.col.version') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="x in toolRows" :key="x.tool">
                <td>
                  <b>{{ x.tool }}</b>
                </td>
                <td>
                  <template v-if="x.because.length">
                    {{ x.wanted ? t('sandbox.wanted', { version: x.wanted }) : t('sandbox.anyVersion') }}
                    <span class="facts">· {{ x.because.join(', ') }}</span>
                  </template>
                  <span v-else class="facts">{{ t('sandbox.notNeeded') }}</span>
                </td>
                <td :class="`state-${x.state.toLowerCase()}`" :title="x.path ?? ''">{{ stateText(x) }}</td>
                <td>
                  <span class="facts">{{ x.version ?? '—' }}</span>
                  <div v-if="x.note" class="hint warn">{{ tr(`sandbox.note.${x.note}`, x.note) }}</div>
                </td>
              </tr>
            </tbody>
          </table>
        </section>

        <section v-if="show('install')" class="sb-block">
          <template v-if="view.commands.length">
            <div class="sub">{{ t('sandbox.install') }}</div>
            <div class="hint">{{ t('sandbox.installHint', { distro: view.distro }) }}</div>
            <pre class="sandbox-commands">{{ script }}</pre>
            <div>
              <button class="action" type="button" @click="copy">{{ copied ? t('sandbox.copied') : t('sandbox.copy') }}</button>
            </div>
          </template>
          <div v-else-if="framed" class="hint">{{ t('sandbox.nothingToInstall') }}</div>
          <div v-for="m in view.manual" :key="m" class="hint warn">{{ t('sandbox.manual', { tool: m }) }}</div>
          <div v-if="view.measured && !view.recipes" class="hint warn">{{ t('sandbox.noRecipes') }}</div>
        </section>

        <section v-if="show('environment')" class="sb-block">
          <div class="hint">
            <span :class="view.enabled ? '' : 'warn'">{{ view.enabled ? t('sandbox.on') : t('sandbox.off') }}</span>
            · {{ view.distro }}<span v-if="view.os"> ({{ view.os }})</span>
          </div>
          <div v-if="view.reason" class="hint warn">{{ t('sandbox.reason', { reason: view.reason }) }}</div>
          <div v-if="!view.project" class="hint">{{ t('sandbox.noProject') }}</div>
          <div class="hint">{{ t('sandbox.home') }}</div>
          <div class="hint">{{ view.network ? t('sandbox.networkOn') : t('sandbox.networkOff') }}</div>
        </section>
      </template>
    </div>
  </component>
</template>
