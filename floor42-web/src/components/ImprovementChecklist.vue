<script setup lang="ts">
import { computed, ref } from 'vue'
import { t, dateLocale } from '../i18n'
import { ask } from '../state/confirm'
import { why } from '../ui/why'
import { addNote, checkNote, closeNote, noteToTicket, office, removeNote } from '../state/office'

const text = ref('')
const busy = ref(false)
const showArchive = ref(false)

const open = computed(() => office.notes.filter((n) => !n.done))
// The archive: notes closed, newest first, with the ticket made of them and the commit that implemented them.
const archive = computed(() =>
  office.notes.filter((n) => n.done).sort((a, b) => (b.doneAt ?? '').localeCompare(a.doneAt ?? '')),
)
// A note becomes a ticket of a real mission in progress: the director wrote it, so it goes to work without approval.
const running = computed(() => office.missions.filter((m) => m.origin === 'REAL' && m.status === 'IN_PROGRESS'))
const target = ref('')
const mission = computed(() => (running.value.some((m) => m.id === target.value) ? target.value : running.value[0]?.id ?? ''))

async function run(action: () => Promise<boolean>, reset = false) {
  busy.value = true
  try {
    if ((await action()) && reset) text.value = ''
  } finally {
    busy.value = false
  }
}

/** Closes a note into the archive, asking for the commit that implemented it (optional). */
async function close(id: string, current: string | null = null) {
  const commit = await ask({
    message: t('notes.commitPrompt', { id }),
    input: { label: t('notes.commitLabel'), value: current ?? '' },
  })
  if (commit === null) return
  void run(() => closeNote(id, (typeof commit === 'string' ? commit : '').trim() || null))
}

function day(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString(dateLocale(), { dateStyle: 'short', timeStyle: 'short' }) : ''
}
</script>

<template>
  <div class="sub">
    {{ t('notes.title') }}
    <span class="hint">· {{ t('notes.open', { count: open.length }) }}<span v-if="archive.length">, {{ t('notes.archived', { count: archive.length }) }}</span></span>
  </div>
  <form class="note-form" @submit.prevent="text.trim() && run(() => addNote(text), true)">
    <input v-model="text" maxlength="2000" :placeholder="t('notes.placeholder')" :aria-label="t('notes.placeholder')" />
    <button class="action" type="submit" :disabled="busy || !text.trim()" :title="why([!text.trim(), t('why.needText')])">
      {{ t('notes.add') }}
    </button>
  </form>
  <div v-if="running.length > 1" class="hint">
    {{ t('notes.toMission') }}
    <select v-model="target" :aria-label="t('notes.toMission')">
      <option v-for="m in running" :key="m.id" :value="m.id">{{ m.id }} · {{ m.title }}</option>
    </select>
  </div>
  <div v-for="n in open" :key="n.id" class="note">
    <span class="note-id">{{ n.id }}</span>
    <span class="text">{{ n.text }}</span>
    <span v-if="n.ticket" class="chip" :title="t('notes.ticketTitle', { ticket: n.ticket })">→ {{ n.ticket }}</span>
    <button
      v-else
      class="link"
      :disabled="busy || !mission"
      :title="mission ? t('notes.toTicketTitle', { mission }) : t('notes.noMission')"
      @click="run(() => noteToTicket(n.id, mission))"
    >
      {{ t('notes.toTicket') }}
    </button>
    <button class="link" :disabled="busy" :title="t('notes.closeTitle')" @click="close(n.id)">{{ t('notes.close') }}</button>
    <button class="forget" :disabled="busy" :title="t('notes.remove')" @click="run(() => removeNote(n.id))">×</button>
  </div>
  <div v-if="archive.length" class="hint">
    <button class="link" @click="showArchive = !showArchive">
      {{ showArchive ? t('notes.hideArchive') : t('notes.showArchive', { count: archive.length }) }}
    </button>
  </div>
  <table v-if="showArchive" class="note-archive">
    <thead>
      <tr>
        <th>{{ t('notes.colNote') }}</th>
        <th>{{ t('notes.colText') }}</th>
        <th>{{ t('notes.colTicket') }}</th>
        <th>{{ t('notes.colCommit') }}</th>
        <th>{{ t('notes.colClosed') }}</th>
        <th></th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="n in archive" :key="n.id">
        <td class="note-id">{{ n.id }}</td>
        <td>{{ n.text }}</td>
        <td>{{ n.ticket ?? '—' }}</td>
        <td>
          <code v-if="n.commit" class="commit">{{ n.commit }}</code>
          <button v-else class="link" :disabled="busy" :title="t('notes.addCommitTitle')" @click="close(n.id)">{{ t('notes.addCommit') }}</button>
        </td>
        <td class="hint">{{ day(n.doneAt) }}</td>
        <td>
          <button class="link" :disabled="busy" :title="t('notes.reopen')" @click="run(() => checkNote(n.id, false))">↺</button>
        </td>
      </tr>
    </tbody>
  </table>
</template>
