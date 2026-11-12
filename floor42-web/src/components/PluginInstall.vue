<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type {
  MissionAssetsView,
  PluginAction,
  PluginOutcome,
  PluginPreview,
  PluginsView,
  PluginTarget,
} from '../api/types'
import { t, tr, dateLocale } from '../i18n'
import { filterPlugins, givableIds, groupChoices } from './pluginInstall'
import PluginOutcomeBox from './PluginOutcomeBox.vue'
import PluginPreviewDialog from './PluginPreviewDialog.vue'

/**
 * Installing plugins from a marketplace (ADR-0040), only on the director's click, after a confirmation that shows the
 * source, publisher, version, licence, contents and the exact commands; and choosing what a mission's sessions receive.
 * Reading a marketplace from GitHub happens only on "Refresh the marketplace".
 */
const props = withDefaults(defineProps<{ mission?: string | null }>(), { mission: null })
const emit = defineEmits<{ changed: [] }>()

const view = ref<PluginsView | null>(null)
const error = ref('')
const busy = ref(false)
const search = ref('')
const open = ref<string | null>(null)
const preview = ref<PluginPreview | null>(null)
const acknowledged = ref(false)
const outcome = ref<PluginOutcome | null>(null)
const assets = ref<MissionAssetsView | null>(null)
const chosen = ref<string[]>([])
const saved = ref(false)

const groups = computed(() => groupChoices(assets.value?.choices ?? []))
const dirty = computed(() => {
  const now = [...(assets.value?.selected ?? [])].sort().join('\n')
  return now !== [...chosen.value].sort().join('\n')
})

function fail(e: unknown) {
  error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

async function load() {
  busy.value = true
  try {
    view.value = await api.plugins()
    if (props.mission) {
      assets.value = await api.missionAssets(props.mission)
      chosen.value = [...assets.value.selected]
    } else {
      assets.value = null
    }
    error.value = ''
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

async function refresh(name: string) {
  busy.value = true
  try {
    view.value = await api.refreshMarketplace(name)
    error.value = ''
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

async function ask(target: PluginTarget, action: PluginAction, plugin: string, marketplace: string) {
  busy.value = true
  outcome.value = null
  acknowledged.value = false
  try {
    preview.value = await api.previewPlugin({ target, action, plugin, marketplace, digest: null })
    error.value = ''
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

async function confirm() {
  const p = preview.value
  if (!p) return
  busy.value = true
  try {
    outcome.value = await api.runPlugin({
      target: p.target,
      action: p.action,
      plugin: p.plugin,
      marketplace: p.marketplace,
      digest: p.digest,
    })
    view.value = outcome.value.view
    preview.value = null
    if (props.mission) assets.value = await api.missionAssets(props.mission)
    error.value = ''
    emit('changed')
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

function toggleGroup(ids: string[], on: boolean) {
  const rest = chosen.value.filter((i) => !ids.includes(i))
  chosen.value = on ? [...rest, ...ids] : rest
}

async function save() {
  if (!props.mission) return
  busy.value = true
  try {
    assets.value = await api.selectMissionAssets(props.mission, chosen.value)
    chosen.value = [...assets.value.selected]
    saved.value = true
    window.setTimeout(() => (saved.value = false), 1500)
    error.value = ''
    emit('changed')
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

function targetText(target: string): string {
  return tr(`plugins.target.${target}`, target)
}

function installedIn(target: PluginTarget, plugin: string, marketplace: string): boolean {
  return (view.value?.installed ?? []).some(
    (i) => i.target === target && i.plugin === plugin && i.marketplace === marketplace,
  )
}

watch(() => props.mission, () => void load(), { immediate: true })
</script>

<template>
  <div class="plugin-install">
    <div class="sub">{{ t('plugins.title') }}</div>
    <div class="hint">{{ t('plugins.intro') }}</div>
    <div v-if="error" class="hint bad">{{ error }}</div>
    <template v-if="view">
      <!-- The operating system's own error text (in its language) stays in the tooltip, not on the screen. -->
      <div class="hint" :class="view.cli.available ? '' : 'warn'" :title="view.cli.available ? undefined : view.cli.detail ?? undefined">
        {{ view.cli.available ? t('plugins.cli', { version: view.cli.version ?? '?' }) : t('plugins.cliUnavailable') }}
      </div>
      <div class="facts">{{ t('plugins.folders', { floor42: view.floor42Folder, claude: view.claudeFolder }) }}</div>

      <!-- The confirmation dialog: nothing runs before the director confirms it. -->
      <PluginPreviewDialog
        v-if="preview"
        v-model:acknowledged="acknowledged"
        :preview="preview"
        :busy="busy"
        @confirm="confirm"
        @cancel="preview = null"
      />

      <PluginOutcomeBox v-if="outcome" :outcome="outcome" @dismiss="outcome = null" />

      <div class="skills-group">{{ t('plugins.installedTitle') }}</div>
      <div v-if="!view.installed.length" class="hint">{{ t('plugins.noneInstalled') }}</div>
      <table role="presentation" v-else class="skills-table">
        <tbody>
          <tr v-for="i in view.installed" :key="`${i.target}:${i.plugin}@${i.marketplace}`">
            <td>
              <b>{{ i.plugin }}</b>@{{ i.marketplace }}
              <div class="facts">{{ targetText(i.target) }}<span v-if="i.version"> · {{ i.version }}</span></div>
            </td>
            <td>
              <template v-if="i.contents">
                <div class="facts">
                  {{ t('plugins.counts', { skills: i.contents.skills.length, agents: i.contents.agents.length }) }}
                </div>
                <div v-if="i.contents.hooks || i.contents.mcpServers" class="hint warn">
                  {{ t('plugins.runsCode') }}
                </div>
              </template>
              <div v-if="i.problem" class="hint warn">{{ tr(`skills.problem.${i.problem}`, i.problem) }}</div>
            </td>
            <td>
              <button
                class="action"
                type="button"
                :disabled="busy"
                @click="ask(i.target, 'UNINSTALL', i.plugin, i.marketplace)"
              >
                {{ t('plugins.uninstall') }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <template v-if="assets">
        <div class="skills-group">{{ t('plugins.missionTitle', { mission: assets.mission }) }}</div>
        <div class="hint">{{ t('plugins.missionHint') }}</div>
        <div v-if="!assets.skillTool || !assets.agentTool" class="hint warn">
          {{ t('plugins.toolsMissing', { skill: assets.skillTool ? '✓' : '✗', agent: assets.agentTool ? '✓' : '✗' }) }}
        </div>
        <div v-if="assets.missing.length" class="hint warn">
          {{ t('plugins.missing', { items: assets.missing.join(', ') }) }}
        </div>
        <div v-if="!groups.length" class="hint">{{ t('plugins.nothingToChoose') }}</div>
        <div v-for="g in groups" :key="g.key" class="plugin-choice">
          <label>
            <input
              type="checkbox"
              :checked="givableIds(g).length > 0 && givableIds(g).every((id) => chosen.includes(id))"
              :disabled="!givableIds(g).length"
              @change="toggleGroup(givableIds(g), ($event.target as HTMLInputElement).checked)"
            />
            <b>{{ g.plugin }}</b>@{{ g.marketplace }} <span class="facts">{{ targetText(g.target) }}</span>
          </label>
          <div v-for="c in g.items" :key="c.id" class="plugin-item">
            <label :class="c.givable ? '' : 'facts'">
              <input v-model="chosen" type="checkbox" :value="c.id" :disabled="!c.givable" />
              {{ tr(`skills.kind.${c.kind}`, c.kind) }} {{ c.name }}
              <span v-if="c.description" class="facts"> — {{ c.description }}</span>
              <span v-if="!c.givable" class="warn"> ({{ tr(`plugins.reason.${c.reason}`, c.reason ?? '') }})</span>
            </label>
          </div>
        </div>
        <button v-if="groups.length || assets.selected.length" class="action" type="button" :disabled="busy || !dirty" @click="save">
          {{ saved ? t('plugins.saved') : t('plugins.save') }}
        </button>
      </template>

      <div class="skills-group">{{ t('plugins.marketTitle') }}</div>
      <input v-model="search" type="search" :placeholder="t('plugins.search')" :aria-label="t('plugins.search')" />
      <div v-for="m in view.marketplaces" :key="m.name" class="plugin-market">
        <div>
          <button class="action" type="button" @click="open = open === m.name ? null : m.name">
            {{ open === m.name ? '▾' : '▸' }} {{ m.name }}
          </button>
          <span class="facts">
            <span v-if="m.official"> · {{ t('plugins.official') }}</span>
            <span v-if="m.owner"> · {{ m.owner }}</span>
            · {{ m.knownIn.length ? m.knownIn.map(targetText).join(', ') : t('plugins.notAdded') }}
            · {{ tr(`plugins.listedFrom.${m.listedFrom}`, m.listedFrom) }}
            <span v-if="m.listedAt"> ({{ new Date(m.listedAt).toLocaleString(dateLocale()) }})</span>
            · {{ t('plugins.pluginCount', { count: m.plugins.length }) }}
          </span>
          <button v-if="m.refreshable" class="action" type="button" :disabled="busy" @click="refresh(m.name)">
            {{ t('plugins.refresh') }}
          </button>
        </div>
        <div v-if="m.refreshable" class="facts">{{ t('plugins.refreshHint') }}</div>
        <table role="presentation" v-if="open === m.name" class="skills-table">
          <tbody>
            <tr v-for="p in filterPlugins(m, search)" :key="p.name">
              <td>
                <b>{{ p.name }}</b>
                <div class="facts">
                  <span v-if="p.publisher">{{ p.publisher }}</span><span v-if="p.version"> · {{ p.version }}</span>
                  <span v-if="p.category"> · {{ p.category }}</span> · {{ p.sourceKind }}
                </div>
              </td>
              <td>{{ p.description }}</td>
              <td>
                <button
                  class="action"
                  type="button"
                  :disabled="busy || installedIn('FLOOR42', p.name, m.name)"
                  @click="ask('FLOOR42', 'INSTALL', p.name, m.name)"
                >
                  {{ installedIn('FLOOR42', p.name, m.name) ? t('plugins.installedFor.FLOOR42') : t('plugins.install.FLOOR42') }}
                </button>
                <button
                  class="action"
                  type="button"
                  :disabled="busy || installedIn('CLAUDE', p.name, m.name)"
                  @click="ask('CLAUDE', 'INSTALL', p.name, m.name)"
                >
                  {{ installedIn('CLAUDE', p.name, m.name) ? t('plugins.installedFor.CLAUDE') : t('plugins.install.CLAUDE') }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="open === m.name && !m.plugins.length" class="hint">{{ t('plugins.noListing') }}</div>
      </div>
      <div class="hint">{{ t('plugins.neverAuto') }}</div>
    </template>
  </div>
</template>
