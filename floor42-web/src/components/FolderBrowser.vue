<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiError } from '../api/client'
import type { FolderView } from '../api/types'
import { t, tr } from '../i18n'

/** The folder browser of the project picker: folder names on this machine, read when the director opens a folder. */
const props = defineProps<{ start: string | null }>()
const folder = defineModel<FolderView | null>('folder', { required: true })
const error = defineModel<string | null>('error', { required: true })
const emit = defineEmits<{ use: [path: string] }>()

async function open(path: string | null) {
  error.value = null
  try {
    folder.value = await api.folders(path)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
onMounted(() => {
  if (!folder.value) void open(props.start)
})

/** A new folder in the folder shown: the daemon checks the name and the place, then the browser opens it. */
const naming = ref(false)
const name = ref('')
const busy = ref(false)
async function create() {
  const parent = folder.value?.path
  if (!parent || !name.value.trim() || busy.value) return
  error.value = null
  busy.value = true
  try {
    folder.value = await api.newFolder(parent, name.value.trim())
    naming.value = false
    name.value = ''
  } catch (e) {
    error.value =
      e instanceof ApiError ? tr(`project.newFolder.refused.${e.code}`, e.message, { name: name.value }) : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="browser">
    <div v-if="error" class="warn">{{ error }}</div>
    <template v-if="folder">
      <div class="here">
        <button v-if="folder.path" type="button" class="link" @click="open(folder.parent)">
          ↑ {{ t('project.up') }}
        </button>
        <b>{{ folder.path ?? t('project.start') }}</b>
        <button v-if="folder.git" type="button" class="action" @click="emit('use', folder.path!)">
          {{ t('project.useThis') }}
        </button>
        <button v-else-if="folder.path" type="button" class="action" @click="emit('use', folder.path!)">
          {{ t('project.chooseWithoutGit') }}
        </button>
        <button v-if="folder.path && !naming" type="button" class="link new-folder" @click="naming = true">
          {{ t('project.newFolder') }}
        </button>
      </div>
      <div v-if="folder.path && naming" class="here">
        <input
          v-model="name"
          class="new-folder-name"
          :aria-label="t('project.newFolder.name')"
          :placeholder="t('project.newFolder.name')"
          maxlength="80"
          @keydown.enter.prevent="create"
        />
        <button type="button" class="action small" :disabled="!name.trim() || busy" @click="create">
          {{ t('project.newFolder.create') }}
        </button>
        <button type="button" class="link" @click="naming = false">{{ t('project.newFolder.cancel') }}</button>
      </div>
      <div v-if="folder.folders.length === 0" class="hint">
        {{ t('project.empty') }}
      </div>
      <div class="list">
        <div v-for="f in folder.folders" :key="f.path" class="entry">
          <button type="button" class="link" @click="open(f.path)">📁 {{ f.name }}</button>
          <button v-if="f.repository" type="button" class="action small" @click="emit('use', f.path)">
            git · {{ t('project.useThis') }}
          </button>
        </div>
      </div>
      <div v-if="folder.truncated" class="hint">
        {{ t('project.truncated') }}
      </div>
    </template>
  </div>
</template>

<style scoped>
.browser {
  border: 1px solid currentColor;
  border-radius: 4px;
  padding: 6px;
  max-height: 260px;
  overflow: auto;
}
.here {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 4px;
  word-break: break-all;
}
.entry {
  display: flex;
  justify-content: space-between;
  gap: 6px;
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
</style>
