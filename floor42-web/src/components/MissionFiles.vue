<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type { MergeResult, ProjectState } from '../api/types'
import { t } from '../i18n'
import { openViewer, viewKey } from '../state/viewer'
import { why } from '../ui/why'

/**
 * The director's files for a mission: given from here or copied into the project folder, committed on the director's
 * branch on their click, then merged into the agents' worktrees so the agents see them.
 */
const props = defineProps<{ mission: string }>()

const state = ref<ProjectState | null>(null)
const folder = ref('inputs')
const chosen = ref<File[]>([])
const busy = ref(false)
const error = ref<string | null>(null)
const merged = ref<MergeResult[] | null>(null)
/** The pending paths the director ticked: only these are committed, never the whole folder. */
const ticked = ref<string[]>([])
const input = ref<HTMLInputElement | null>(null)

async function load() {
  try {
    state.value = await api.missionFiles(props.mission)
    const pending = state.value.pending
    ticked.value = ticked.value.filter((p) => pending.includes(p))
  } catch {
    state.value = null
  }
}
onMounted(load)
watch(() => props.mission, () => {
  merged.value = null
  ticked.value = []
  void load()
})

async function act(call: () => Promise<void>) {
  busy.value = true
  error.value = null
  try {
    await call()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    busy.value = false
  }
}

function pick(e: Event) {
  chosen.value = Array.from((e.target as HTMLInputElement).files ?? [])
}

/** The file's bytes in base64, read in the browser: nothing leaves the machine but to the local daemon. */
function base64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result).replace(/^data:[^,]*,/, ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

const give = () =>
  act(async () => {
    const files = await Promise.all(chosen.value.map(async (f) => ({ name: f.name, content: await base64(f) })))
    state.value = await api.giveFiles(props.mission, folder.value.trim(), files)
    chosen.value = []
    if (input.value) input.value.value = ''
  })
const commit = () =>
  act(async () => {
    state.value = await api.commitMissionFiles(props.mission, ticked.value)
    ticked.value = []
  })
const tickAll = (on: boolean) => {
  ticked.value = on && state.value ? [...state.value.pending] : []
}
/** Opens one of the director's own pending files in the viewer panel (ADR-0051); closing it brings the desk back. */
const view = (path: string) =>
  openViewer(
    { mission: props.mission, agent: '', path, committed: false, own: true },
    { from: 'desk', key: viewKey('', path, false), state: {} },
  )
const update = () =>
  act(async () => {
    merged.value = await api.updateAgents(props.mission)
    await load()
  })
</script>

<template>
  <div class="sub">{{ t('files.title') }}</div>
  <div v-if="!state" class="hint">{{ t('files.none') }}</div>
  <template v-else>
    <div class="hint">{{ t('files.explain', { branch: state.branch || '?' }) }}</div>

    <div class="files-block">
      <input ref="input" type="file" multiple :aria-label="t('files.choose')" @change="pick" />
      <input v-model="folder" class="folder" :placeholder="t('files.folder')" :aria-label="t('files.folder')" />
      <button class="action" :disabled="busy || chosen.length === 0" :title="why([chosen.length === 0, t('why.noFiles')])" @click="give">
        {{ t('files.give', { count: chosen.length }) }}
      </button>
    </div>

    <div v-if="state.pendingCount" class="files-block">
      <span class="warn">{{ t('files.pending', { count: state.pendingCount }) }}</span>
      <span class="hint">{{ t('files.confirmHint') }}</span>
      <div class="pending-list">
        <div v-for="p in state.pending" :key="p" class="pending-path">
          <label>
            <input v-model="ticked" type="checkbox" :value="p" />
            <code>{{ p }}</code>
          </label>
          <button class="action view" :data-view-key="viewKey('', p, false)" :title="t('files.viewHint')" @click="view(p)">
            {{ t('files.view') }}
          </button>
        </div>
      </div>
      <span v-if="state.pendingCount > state.pending.length" class="hint">
        {{ t('files.shownOf', { shown: state.pending.length, count: state.pendingCount }) }}
      </span>
      <button class="action" :disabled="busy" @click="tickAll(true)">{{ t('files.tickAll') }}</button>
      <button class="action" :disabled="busy || ticked.length === 0" @click="tickAll(false)">{{ t('files.tickNone') }}</button>
      <button
        class="action"
        :disabled="busy || ticked.length === 0"
        :title="why([ticked.length === 0, t('why.noneTicked')])"
        @click="commit"
      >
        {{ t('files.commitTicked', { count: ticked.length }) }}
      </button>
    </div>

    <div v-if="state.agents.length" class="files-block">
      <span v-for="a in state.agents" :key="a.agent" class="hint">
        {{ a.agent }} · {{ a.atWork ? t('files.atWork') : a.upToDate ? t('files.upToDate') : t('files.behind') }}
      </span>
      <button
        class="action"
        :disabled="busy || state.agents.some((a) => a.atWork) || state.agents.every((a) => a.upToDate)"
        :title="
          why(
            [state.agents.some((a) => a.atWork), t('why.agentAtWork')],
            [state.agents.every((a) => a.upToDate), t('why.allUpToDate')],
          )
        "
        @click="update"
      >
        {{ t('files.update') }}
      </button>
    </div>
    <div v-else class="hint">{{ t('files.noAgent') }}</div>

    <div v-for="r in merged ?? []" :key="r.agent" :class="r.clean ? 'hint' : 'warn'">
      {{ r.agent }} · {{ r.skipped ? t('files.upToDate') : r.clean ? t('files.merged') : t('files.conflict', { detail: r.detail ?? '' }) }}
    </div>
    <div v-if="error" class="warn">{{ error }}</div>
  </template>
</template>

<style scoped>
.files-block {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: center;
  margin: 4px 0;
}
.folder {
  width: 9em;
}
.pending-list {
  display: flex;
  flex-direction: column;
  max-height: 12em;
  overflow-y: auto;
  width: 100%;
}
.pending-path {
  display: flex;
  gap: 4px;
  align-items: center;
}
.pending-path .view {
  margin-left: auto;
  padding: 1px 6px;
}
.warn {
  color: #e0a040;
}
</style>
