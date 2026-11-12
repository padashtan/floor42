<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type { CatalogueView, InstalledItem, SessionItem, SuggestionItem } from '../api/types'
import { lang, t, tr, dateLocale } from '../i18n'
import { counts, filterSuggestions, groupInstalled, localText, type Filter, type Group } from './catalogue'
import PluginInstall from './PluginInstall.vue'
import SectionedPanel, { type SectionTab } from './SectionedPanel.vue'
import { requestPrefill } from '../state/terminalPrefill'
import { prefillFor, prefillRefusal } from '../ui/prefill'

/**
 * Skills and agents (W3): what is installed for Claude Code, whether it reaches Floor42's sessions (read from a real
 * session's init line, never assumed), a curated list of what could be added, and (ADR-0040) installing from a
 * marketplace on the director's confirmed click, with the choice of what each mission's sessions receive.
 */
const props = withDefaults(defineProps<{ mission?: string | null }>(), { mission: null })
const emit = defineEmits<{ close: [] }>()

const view = ref<CatalogueView | null>(null)
const error = ref('')
const busy = ref(false)
const copied = ref<string | null>(null)
const filter = ref<Filter>({ text: '', kind: 'ALL', reach: 'ALL' })

const readAt = computed(() =>
  view.value ? new Date(view.value.readAt).toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' }) : '',
)
const sessionAt = computed(() =>
  view.value?.launch.sessionAt ? new Date(view.value.launch.sessionAt).toLocaleString(dateLocale()) : null,
)
const groups = computed(() => groupInstalled(view.value?.installed ?? [], filter.value))
const total = computed(() => counts(view.value?.installed ?? []))
const suggestions = computed(() => filterSuggestions(view.value?.suggestions ?? [], filter.value))
const session = computed(() =>
  (view.value?.session ?? []).filter(
    (s) => (filter.value.kind === 'ALL' || filter.value.kind === s.kind) && (filter.value.reach === 'ALL' || filter.value.reach === s.state),
  ),
)

async function load() {
  busy.value = true
  try {
    view.value = await api.catalogue(props.mission || null)
    error.value = ''
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    busy.value = false
  }
}

async function copy(s: SuggestionItem) {
  try {
    await navigator.clipboard.writeText(s.install.join('\n'))
    copied.value = s.id
    window.setTimeout(() => (copied.value = null), 1500)
  } catch {
    copied.value = null
  }
}

function groupTitle(g: Group): string {
  if (g.level === 'USER') return t('skills.group.user')
  if (g.level === 'PROJECT') return t('skills.group.project', { repository: view.value?.repository ?? '' })
  return t('skills.group.plugin', { plugin: g.plugin ?? '' })
}

function reasonText(reason: string): string {
  return tr(`skills.reason.${reason}`, reason, {
    time: sessionAt.value ?? '',
    version: view.value?.launch.sessionVersion ?? '?',
  })
}

function toolText(offered: boolean | null): string {
  return offered === null ? t('skills.tool.unknown') : offered ? t('skills.tool.offered') : t('skills.tool.denied')
}

function stateClass(i: { state: string }): string {
  return `reach-${i.state.toLowerCase()}`
}

function kindText(i: InstalledItem | SuggestionItem): string {
  return tr(`skills.kind.${i.kind.toUpperCase()}`, i.kind)
}

/** A skill or an agent can be prepared in the director's console; a plugin line or a server cannot. */
function preparable(i: InstalledItem | SessionItem): boolean {
  return i.kind === 'SKILL' || i.kind === 'AGENT'
}

/** The command the button would prepare, for its title; null when the name is not one Floor42 will type. */
function command(i: InstalledItem | SessionItem): string | null {
  return prefillFor({ kind: i.kind, name: i.name, plugin: 'plugin' in i ? i.plugin : null })
}

/** Why the button is off, shown under it. */
function refusal(i: InstalledItem | SessionItem): string | null {
  const why = prefillRefusal({ kind: i.kind, name: i.name, plugin: 'plugin' in i ? i.plugin : null })
  return why ? t(why === 'builtin' ? 'prefill.builtin' : 'prefill.badName') : null
}

/** The director's click (ADR-0041, amendment of 2026-11-02): his own terminal, the command typed, never Enter. */
function prepare(i: InstalledItem | SessionItem, e: MouseEvent) {
  const project = 'level' in i && i.level === 'PROJECT' ? (view.value?.repository ?? null) : null
  const answer = requestPrefill({ kind: i.kind, name: i.name, plugin: 'plugin' in i ? i.plugin : null }, project, e)
  if (answer === 'busy') error.value = t('prefill.busy')
  else if (answer !== 'ok') error.value = t('prefill.refused')
}

watch(() => props.mission, () => void load(), { immediate: true })

/** A search shows what matches in the installed list and the suggestions, whichever section is chosen. */
const searching = computed(() => filter.value.text.trim().length > 0)
type Part = 'sessions' | 'installed' | 'plugins' | 'suggestions'
const current = ref<Part>('installed')
const tabs = computed<SectionTab[]>(() => [
  { id: 'installed', label: t('skills.s.installed'), badge: view.value?.installed.length ? { text: String(view.value.installed.length) } : null },
  { id: 'sessions', label: t('skills.s.sessions') },
  { id: 'plugins', label: t('skills.s.plugins') },
  { id: 'suggestions', label: t('skills.s.suggestions'), badge: view.value?.suggestions.length ? { text: String(view.value.suggestions.length) } : null },
])
</script>

<template>
  <SectionedPanel
    class="skills-panel"
    id-prefix="skills"
    v-model:query="filter.text"
    :title="t('skills.title')"
    :hint="t('skills.intro')"
    searchable
    :sections="tabs"
    :current="current"
    :focus-on-open="false"
    nav-width="170px"
    @choose="(id) => (current = id as Part)"
    @close="emit('close')"
  >
    <template #top>
      <div class="sk-bar">
        <button class="action" type="button" :disabled="busy" @click="load">
          {{ busy ? t('skills.reading') : t('skills.refresh') }}
        </button>
        <span v-if="view" class="hint"> {{ t('skills.readAt', { time: readAt }) }}</span>
        <span v-if="error" class="hint bad">{{ error }}</span>
      </div>
    </template>
    <div class="settings-form">
      <template v-if="view">
        <section v-if="!searching && current === 'sessions'" class="sk-block">
        <div class="sub">{{ t('skills.sessions') }}</div>
        <div class="hint" :class="view.launch.isolation === 'unknown' ? 'warn' : ''">
          {{ tr(`skills.isolation.${view.launch.isolation}`, view.launch.isolation) }}
        </div>
        <div class="hint">
          <template v-if="sessionAt">
            {{ t('skills.judgedBy', { time: sessionAt, version: view.launch.sessionVersion ?? '?' }) }}
            <span v-if="view.launch.sessionOfMission">{{ t('skills.ofMission') }}</span>
            <span v-else-if="view.mission">{{ t('skills.notOfMission') }}</span>
          </template>
          <span v-else class="warn">{{ t('skills.noSession') }}</span>
        </div>
        <ul class="skills-flags hint">
          <li v-for="f in view.launch.flags" :key="f">
            <code>{{ f }}</code> {{ tr(`skills.flag.${f}`, '') }}
          </li>
          <li><code>--settings</code> {{ t('skills.flag.settings') }}</li>
          <li>{{ t('skills.flag.cwd') }}</li>
        </ul>
        <div class="hint">
          Skill: {{ toolText(view.launch.skillTool) }} · Agent: {{ toolText(view.launch.agentTool) }}
        </div>
        <div v-if="!view.mission" class="hint">{{ t('skills.noMission') }}</div>
        </section>

        <section v-if="searching || current === 'installed'" class="sk-block">
        <div class="skills-filter">
          <select v-model="filter.kind" :aria-label="t('skills.filter.kind')">
            <option value="ALL">{{ t('skills.filter.allKinds') }}</option>
            <option value="SKILL">{{ t('skills.kind.SKILL') }}</option>
            <option value="AGENT">{{ t('skills.kind.AGENT') }}</option>
            <option value="PLUGIN">{{ t('skills.kind.PLUGIN') }}</option>
            <option value="MCP">{{ t('skills.kind.MCP') }}</option>
          </select>
          <select v-model="filter.reach" :aria-label="t('skills.filter.state')">
            <option value="ALL">{{ t('skills.filter.allStates') }}</option>
            <option value="AVAILABLE">{{ t('skills.state.AVAILABLE') }}</option>
            <option value="NOT_AVAILABLE">{{ t('skills.state.NOT_AVAILABLE') }}</option>
            <option value="UNKNOWN">{{ t('skills.state.UNKNOWN') }}</option>
          </select>
        </div>

        <div class="sub">{{ t('skills.installed') }}</div>
        <div class="hint">
          {{
            t('skills.counts', {
              available: total.AVAILABLE,
              not: total.NOT_AVAILABLE,
              unknown: total.UNKNOWN,
            })
          }}
        </div>
        <div v-if="!view.installed.length" class="hint">{{ t('skills.noneInstalled') }}</div>
        <template v-for="g in groups" :key="g.key">
          <div class="skills-group">{{ groupTitle(g) }}</div>
          <table role="presentation" class="skills-table">
            <tbody>
              <tr v-for="i in g.items" :key="`${i.kind}:${i.path}`">
                <td>
                  <b>{{ i.name }}</b>
                  <div class="facts">
                    {{ kindText(i) }}<span v-if="i.version"> · {{ i.version }}</span
                    ><span v-if="i.model"> · {{ i.model }}</span>
                  </div>
                </td>
                <td>
                  <div v-if="i.description">{{ i.description }}</div>
                  <div class="facts"><code>{{ i.path }}</code></div>
                  <div v-if="i.tools.length" class="facts">{{ t('skills.tools', { tools: i.tools.join(', ') }) }}</div>
                  <div v-if="i.problem" class="hint warn">{{ tr(`skills.problem.${i.problem}`, i.problem) }}</div>
                </td>
                <td :class="stateClass(i)">
                  {{ tr(`skills.state.${i.state}`, i.state) }}
                  <div class="facts">{{ reasonText(i.reason) }}</div>
                  <template v-if="preparable(i)">
                    <button class="prefill" type="button" :disabled="!command(i)"
                      :title="command(i) ? t('prefill.title', { command: command(i)!.trim() }) : (refusal(i) ?? '')" @click="prepare(i, $event)">
                      {{ t('prefill.button') }}
                    </button>
                    <div v-if="refusal(i)" class="facts prefill-refusal">{{ refusal(i) }}</div>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
        </template>

        <template v-if="session.length">
          <div class="sub">{{ t('skills.sessionOnly') }}</div>
          <div class="hint">{{ t('skills.sessionOnlyHint') }}</div>
          <div class="skills-session">
            <span v-for="s in session" :key="`${s.kind}:${s.name}`" :class="stateClass(s)" :title="reasonText(s.reason)">
              {{ s.name }} <span class="facts">({{ tr(`skills.kind.${s.kind}`, s.kind) }})</span>
              <button v-if="preparable(s) && command(s)" class="prefill" type="button"
                :title="t('prefill.title', { command: command(s)!.trim() })" :aria-label="t('prefill.button') + ': ' + s.name" @click="prepare(s, $event)">
                {{ t('prefill.button') }}
              </button>
            </span>
          </div>
        </template>
        </section>

        <section v-if="!searching && current === 'plugins'" class="sk-block">
          <PluginInstall :mission="mission" @changed="load" />
        </section>

        <section v-if="searching || current === 'suggestions'" class="sk-block">
        <div class="sub">{{ t('skills.suggestions') }}</div>
        <div class="hint">{{ t('skills.suggestionsHint', { date: view.checked ?? '?' }) }}</div>
        <div v-for="s in suggestions" :key="s.id" class="skills-suggestion">
          <div>
            <b>{{ s.name }}</b> <span class="facts">{{ kindText(s) }}</span>
            <span v-if="s.installed" class="facts"> · {{ t('skills.alreadyInstalled') }}</span>
          </div>
          <div>{{ localText(s.brings, lang) }}</div>
          <div class="hint warn">{{ t('skills.risks', { risks: localText(s.risks, lang) }) }}</div>
          <div class="facts">
            <a :href="s.source" target="_blank" rel="noopener noreferrer">{{ s.source }}</a>
            <span v-if="s.license"> · {{ t('skills.license', { license: s.license }) }}</span>
          </div>
          <div :class="stateClass(s)">
            {{ tr(`skills.state.${s.state}`, s.state) }} <span class="facts">{{ reasonText(s.reason) }}</span>
          </div>
          <pre class="sandbox-commands">{{ s.install.join('\n') }}</pre>
          <button class="action" type="button" @click="copy(s)">
            {{ copied === s.id ? t('skills.copied') : t('skills.copy') }}
          </button>
        </div>
        <div class="hint">{{ t('skills.neverInstalls') }}</div>
        </section>
      </template>
    </div>
  </SectionedPanel>
</template>
