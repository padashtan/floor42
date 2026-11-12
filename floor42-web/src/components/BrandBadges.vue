<script setup lang="ts">
import { computed } from 'vue'
import { t, tr } from '../i18n'
import { office } from '../state/office'

/** What the daemon can do, independent of any mission: live agents need a recent enough Claude Code. */
const daemon = computed(() => {
  const status = office.status
  if (!status) return { label: '…', live: false, title: t('top.connecting') }
  return status.runtime.available
    ? { label: t('top.liveReady'), live: true, title: `Claude Code ${status.runtime.version}` }
    : { label: t('top.simulationOnly'), live: false, title: status.runtime.reason ?? '' }
})
/** The version and the commit the daemon was built from, as it reports them; nothing guessed when unknown. */
const build = computed(() => {
  const status = office.status
  if (!status) return null
  const b = status.build
  const label = b?.commit ? `v${status.version} · ${b.commit}${b.dirty ? '*' : ''}` : `v${status.version}`
  const title = b?.commitFull
    ? t('top.buildTitle', { version: status.version, commit: b.commitFull, time: b.commitTime ?? '?' }) +
      (b.dirty ? ' ' + t('top.buildDirty') : '')
    : t('top.buildUnknown', { version: status.version })
  return { label, title }
})
/** The origin of the active mission on the board, never mixed up with the daemon's capability. */
const board = computed(() =>
  office.mission && !['COMPLETED', 'CANCELLED'].includes(office.mission.status) ? office.mission : null,
)
</script>

<template>
  <span v-if="build" class="badge build shrink" :title="build.title">{{ build.label }}</span>
  <span class="badge shrink" :class="daemon.live ? 'ready' : 'off'" :title="daemon.title">{{ daemon.label }}</span>
  <span
    v-if="board"
    class="badge shrink"
    :class="board.origin === 'REAL' ? 'real' : 'sim'"
    :title="`${board.id} · ${t(`log.${board.origin}`)}: ${board.title} (${tr(`mstatus.${board.status}`, board.status)})`"
  >
    {{ board.id }} · {{ t(`log.${board.origin}`) }}
  </span>
</template>
