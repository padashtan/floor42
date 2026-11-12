<script setup lang="ts">
import { nowMs } from '../ui/clock'
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { FolderView, InitPreview, ProjectView, RepositoryFacts } from '../api/types'
import { t } from '../i18n'
import { confirmed } from '../state/confirm'
import { wanted } from '../state/library'
import { ago } from '../ui/away'
import { folderName, recentProjects } from '../ui/recent'
import FolderBrowser from './FolderBrowser.vue'

/**
 * The project a mission works on (W2): one already used, a folder found by browsing this machine, or a typed path.
 * Floor42 only reads folder names and what git says; it changes nothing here, but for making a folder without git a
 * repository when the director clicks for it.
 */
const model = defineModel<string>({ required: true })
defineProps<{ placeholder: string; required: boolean }>()

const projects = ref<ProjectView[]>([])
const browsing = ref(false)
const folder = ref<FolderView | null>(null)
const error = ref<string | null>(null)

/** Recent projects first (pinned, then by last activity, as the server sorts them); forgotten ones left out. */
async function loadProjects(list?: Promise<ProjectView[]>) {
  try {
    projects.value = recentProjects(await (list ?? api.projects()))
  } catch (e) {
    // No list: the director can still browse or type a path.
    if (list) error.value = e instanceof Error ? e.message : String(e)
  }
}
onMounted(() => loadProjects())

const showAll = ref(false)
const shown = computed(() => (showAll.value ? projects.value : projects.value.slice(0, 6)))

/** Pin or forget: journaled director events; the list comes back sorted. */
function pin(p: ProjectView) {
  void loadProjects(api.pinProject(p.path, !p.pinned))
}
async function forget(p: ProjectView) {
  if (!(await confirmed({ message: t('recent.forgetConfirm', { path: p.path }), danger: true }))) return
  void loadProjects(api.forgetProject(p.path))
}

function activity(p: ProjectView): string {
  return p.lastActivity ? t('recent.ago', { when: ago(p.lastActivity, nowMs()) }) : ''
}

const chosen = computed(() => projects.value.find((p) => p.path === model.value) ?? null)

// The library's "another folder" card: the browser opens as soon as the form shows.
watch(
  () => wanted.browse,
  (b) => {
    if (!b) return
    browsing.value = true
    wanted.browse = false
  },
  { immediate: true },
)

function browse() {
  browsing.value = !browsing.value
}

function use(path: string) {
  model.value = path
  browsing.value = false
}

/** What git says of the typed or chosen folder, read when it changes. */
const facts = ref<RepositoryFacts | null>(null)
/**
 * For a folder without git, or a repository without a commit: what making it a repository would do. The mission form
 * reads it, to initialise git when the mission is created.
 */
const preview = defineModel<InitPreview | null>('preview', { default: null })
const initializing = ref(false)
let asked = 0

async function inspect(path: string) {
  const mine = ++asked
  facts.value = null
  preview.value = null
  if (!path.trim()) return
  try {
    const view = await api.folders(path.trim())
    if (mine !== asked) return
    facts.value = view.git
    if (!view.git || !view.git.commit) {
      const p = await api.previewInit(path.trim())
      if (mine === asked) preview.value = p
    }
  } catch {
    // Not a folder (yet): nothing to say.
  }
}
watch(model, (path) => void inspect(path), { immediate: true })

async function initialize() {
  if (!preview.value) return
  initializing.value = true
  error.value = null
  try {
    await api.initRepository(preview.value.path)
    await inspect(model.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    initializing.value = false
  }
}

function summary(g: RepositoryFacts): string {
  return t('project.facts', {
    branch: g.branch || t('project.detached'),
    commit: g.commit ? `${g.commit} ${g.subject}` : t('project.noCommit'),
  })
}
</script>

<template>
  <div class="picker">
    <div class="row">
      <input v-model="model" :placeholder="placeholder" :required="required" list="floor42-projects" :aria-label="placeholder" />
      <button type="button" @click="browse">
        {{ browsing ? t('project.closeBrowser') : t('project.browse') }}
      </button>
    </div>
    <datalist id="floor42-projects">
      <option v-for="p in projects.filter((x) => x.exists)" :key="p.path" :value="p.path">
        {{ p.lastTitle }}
      </option>
    </datalist>
    <div v-if="projects.length && !model" class="recent">
      <div class="hint">{{ t('recent.title') }}</div>
      <div v-for="p in shown" :key="p.path" class="recent-row" :class="{ gone: !p.exists }">
        <button
          type="button"
          class="link name"
          :disabled="!p.exists"
          :title="p.exists ? t('recent.useTitle', { path: p.path }) : t('recent.goneTitle', { path: p.path })"
          @click="use(p.path)"
        >
          <b>{{ folderName(p.path) }}</b>
        </button>
        <span class="hint detail" :title="p.path">
          <template v-if="p.lastMission">{{ t('recent.last', { mission: p.lastMission, title: p.lastTitle ?? '' }) }}</template>
          <template v-else>{{ t('recent.allArchived') }}</template>
          <template v-if="activity(p)"> · {{ activity(p) }}</template>
          <span v-if="!p.exists" class="warn"> · {{ t('recent.gone') }}</span>
        </span>
        <button
          type="button"
          class="icon"
          :class="{ on: p.pinned }"
          :title="p.pinned ? t('recent.unpin') : t('recent.pin')"
          @click="pin(p)"
        >
          {{ p.pinned ? '★' : '☆' }}
        </button>
        <button type="button" class="icon" :title="t('recent.forget')" @click="forget(p)">×</button>
      </div>
      <button v-if="projects.length > 6" type="button" class="link hint" @click="showAll = !showAll">
        {{ showAll ? t('recent.fewer') : t('recent.all', { count: projects.length }) }}
      </button>
    </div>
    <div v-if="model" class="hint">
      <template v-if="facts">
        {{ summary(facts) }}
        <span v-if="facts.uncommitted" class="warn">
          · {{ t('project.uncommitted', { count: facts.uncommitted }) }}</span
        >
      </template>
      <template v-else>{{ t('project.notRepository') }}</template>
      <div v-if="preview" class="init">
        <span v-if="preview.refused" class="warn">{{ preview.refused }}</span>
        <template v-else>
          <span>{{ preview.files === 0 ? t('project.initEmpty') : t('project.initFiles', { count: preview.files }) }}</span>
          <button type="button" class="action" :disabled="initializing" @click="initialize">
            {{ preview.repository ? t('project.firstCommit') : t('project.initHere') }}
          </button>
        </template>
      </div>
      <div v-if="error && !browsing" class="warn">{{ error }}</div>
      <span v-if="chosen?.missions.length">
        ·
        {{ t('project.usedBy', { missions: chosen.missions.join(', ') }) }}</span
      >
    </div>

    <FolderBrowser
      v-if="browsing"
      v-model:folder="folder"
      v-model:error="error"
      :start="model.trim() || null"
      @use="use"
    />
  </div>
</template>

<style scoped>
.picker {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.row {
  display: flex;
  gap: 6px;
}
.row input {
  flex: 1;
  min-width: 0;
}
.recent {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.recent-row {
  display: flex;
  gap: 6px;
  align-items: baseline;
}
.recent-row .detail {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recent-row.gone .name {
  opacity: 0.55;
  cursor: not-allowed;
}
.icon {
  background: none;
  border: none;
  padding: 0 4px;
  color: inherit;
  cursor: pointer;
  opacity: 0.7;
}
.icon.on {
  color: #e0c040;
  opacity: 1;
}
.link {
  background: none;
  border: none;
  padding: 0;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
  text-align: left;
}
.small {
  font-size: 0.85em;
  padding: 0 6px;
}
.warn {
  color: #e0a040;
}
.init {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 2px;
}
</style>
