<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiError } from '../api/client'
import type { BackupView, BackupsView } from '../api/types'
import { t, tr, dateLocale } from '../i18n'
import { confirmed } from '../state/confirm'

/**
 * The journal's copies (ADR-0042), in the settings: taken at every start, once a day, or now. A restore is only
 * prepared here, in the daemon's memory: its own orderly stop applies it. A start never restores anything.
 */
/**
 * settings-redesign: `lock` is the settings panel's one-write-at-a-time guard, `locked` whether it is held: a backup or a
 * restore never starts while a setting is being written. Reading the list does not take it.
 */
const props = defineProps<{ locked?: boolean; lock?: (work: () => Promise<void>) => Promise<void> }>()
const view = ref<BackupsView | null>(null)
const error = ref('')
const busy = ref(false)

function message(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

async function run(action: () => Promise<BackupsView>) {
  busy.value = true
  try {
    view.value = await action()
    error.value = ''
  } catch (e) {
    error.value = message(e)
  } finally {
    busy.value = false
  }
}

/** A write (back up now, prepare or cancel a restore): under the panel's lock when there is one. */
function write(action: () => Promise<BackupsView>) {
  return props.lock ? props.lock(() => run(action)) : run(action)
}

/** Why no copy is made, in the director's language; the server's sentence only for a code this client lacks. */
function reason(v: BackupsView): string {
  return tr(`backups.why.${v.disabledCode}`, v.disabledReason ?? '', { folder: v.folder, path: v.disabledPath ?? '' })
}

function size(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function prepare(b: BackupView) {
  if (!(await confirmed({ message: t('backups.confirmRestore', { name: b.name }) }))) return
  void write(() => api.prepareRestore(b.name))
}

onMounted(() => void run(() => api.backups()))
</script>

<template>
  <div class="settings-block" data-setting="backups">
    <div class="name">{{ t('backups.title') }}</div>
    <div class="line">{{ t('backups.hint') }}</div>
    <div v-if="error" class="hint bad">{{ error }}</div>
    <template v-if="view">
      <div class="hint">
        {{ t('backups.folder', { folder: view.folder }) }} ·
        {{ t('backups.keep', { startup: view.startupKeep, daily: view.dailyKeep, manual: view.manualKeep }) }}
      </div>
      <div v-if="view.disabledReason" class="hint bad">{{ t('backups.disabled', { reason: reason(view) }) }}</div>
      <div v-if="view.lastError" class="hint bad">{{ t('backups.lastError', { error: view.lastError }) }}</div>
      <div v-if="view.lastRestore" class="hint" :class="view.lastRestore.restored ? 'warn' : 'bad'">
        <template v-if="view.lastRestore.restored && view.lastRestore.matchesJournal">
          {{ t('backups.restored', { name: view.lastRestore.backup, kept: view.lastRestore.keptAs ?? '—' }) }}
        </template>
        <template v-else-if="view.lastRestore.restored">
          {{ t('backups.restoredUnverified', { name: view.lastRestore.backup }) }}
        </template>
        <template v-else>{{ t('backups.restoreFailed', { name: view.lastRestore.backup, reason: view.lastRestore.reason ?? '' }) }}</template>
      </div>
      <div v-if="view.backups.some((b) => b.sizeBytes > (view?.restoreLimitBytes ?? 0))" class="hint warn">
        {{ t('backups.tooLarge', { limit: size(view.restoreLimitBytes) }) }}
      </div>
      <div v-if="view.refusedRestore" class="hint bad">
        {{ t('backups.refused', { name: view.refusedRestore.backup || '?' }) }}
      </div>
      <div v-if="view.pendingRestore" class="hint warn">
        {{ t('backups.pending', { name: view.pendingRestore }) }}
        <button class="link" :disabled="busy || locked" @click="write(() => api.cancelRestore())">{{ t('backups.cancel') }}</button>
      </div>
      <div>
        <button class="action" type="button" :disabled="busy || locked || !!view.disabledReason" @click="write(() => api.backUpNow())">
          {{ t('backups.now') }}
        </button>
      </div>
      <table v-if="view.backups.length" class="defaults">
        <thead>
          <tr>
            <th>{{ t('backups.col.date') }}</th>
            <th>{{ t('backups.col.kind') }}</th>
            <th>{{ t('backups.col.events') }}</th>
            <th>{{ t('backups.col.size') }}</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in view.backups" :key="b.name">
            <td :title="b.name">{{ new Date(b.createdAt).toLocaleString(dateLocale()) }}</td>
            <td>{{ tr(`backups.kind.${b.kind}`, b.kind) }}</td>
            <td>{{ b.events ?? t('backups.unreadable') }}</td>
            <td class="facts">{{ size(b.sizeBytes) }}</td>
            <td>
              <button
                class="link"
                :disabled="busy || locked || b.events === null || view.pendingRestore === b.name || b.sizeBytes > view.restoreLimitBytes"
                :title="b.sizeBytes > view.restoreLimitBytes ? t('backups.tooLarge', { limit: size(view.restoreLimitBytes) }) : ''"
                @click="prepare(b)"
              >
                {{ t('backups.restore') }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="hint">{{ t('backups.none') }}</div>
      <div class="hint">{{ t('backups.manual') }}</div>
    </template>
  </div>
</template>
