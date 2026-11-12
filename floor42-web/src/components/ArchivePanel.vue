<script setup lang="ts">
import { computed } from 'vue'
import type { ArchiveView, CommitLine } from '../api/types'
import { t, dateLocale } from '../i18n'
import { cssColor, uniformOf } from '../scene/identity'
import { office } from '../state/office'
import GitGraph from './GitGraph.vue'
import ReplayExport from './ReplayExport.vue'

/** The archive wall, read (W6): the repository's newest commits and what each agent's branch holds beyond them. */
const props = defineProps<{ archive: ArchiveView | null }>()
const emit = defineEmits<{ close: []; agent: [id: string]; deliverables: [] }>()

const name = (id: string) => office.agents.find((a) => a.id === id)?.name ?? id
const when = (c: CommitLine) => new Date(c.at).toLocaleString(dateLocale(), { dateStyle: 'short', timeStyle: 'short' })
const mission = computed(() => office.mission)
</script>

<template>
  <div class="panel archive-panel framed">
    <div class="title">
      {{ t('archive.title') }}
      <span v-if="mission" class="hint">· {{ mission.id }} · {{ t('archive.measured') }}</span>
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll body">
      <div v-if="!mission" class="hint">{{ t('board.noMission') }}</div>
      <template v-else>
        <button class="action" :title="t('deliverables.topTitle')" @click="emit('deliverables')">{{ t('deliverables.open') }}</button>
        <ReplayExport :key="mission.id" :mission="mission.id" />
        <div v-if="!props.archive" class="hint">{{ t('archive.loading') }}</div>
        <div v-else-if="!props.archive.available" class="hint">
          {{ props.archive.repository ? t('archive.unreadable', { path: props.archive.repository }) : t('archive.noRepository') }}
        </div>
        <template v-else>
          <div class="sub">{{ t('archive.graph') }} <span class="hint">· {{ t('archive.graphHint') }}</span></div>
          <GitGraph :graph="props.archive.graph" />
          <div class="sub">
            {{ t('archive.trunk', { branch: props.archive.branch || t('project.detached') }) }}
            <span class="hint">· {{ props.archive.repository }}</span>
          </div>
          <div v-if="props.archive.trunk.length === 0" class="hint">{{ t('project.noCommit') }}</div>
          <div v-for="c in props.archive.trunk" :key="c.hash" class="commit">
            <code>{{ c.hash }}</code> {{ c.subject }} <span class="hint">· {{ c.author }} · {{ when(c) }}</span>
          </div>
  
          <div class="sub">{{ t('archive.branches') }}</div>
          <div v-if="props.archive.branches.length === 0" class="hint">{{ t('archive.noBranch') }}</div>
          <div v-for="b in props.archive.branches" :key="b.agent" class="branch">
            <div class="head">
              <span class="dot" :style="{ background: cssColor(uniformOf(b.agent)) }"></span>
              <button class="link" @click="emit('agent', b.agent)">{{ name(b.agent) }}</button>
              <code>{{ b.branch }}</code>
              <span class="hint">
                ·
                {{ b.ahead < 0 ? t('archive.uncounted') : t('archive.ahead', { count: b.ahead }) }}
              </span>
            </div>
            <div v-for="c in b.commits" :key="c.hash" class="commit indent">
              <code>{{ c.hash }}</code> {{ c.subject }} <span class="hint">· {{ when(c) }}</span>
            </div>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
/* Placed like the other framed panels: without a position it opened over the top bar. */
.archive-panel {
  top: 90px;
  left: 50%;
  transform: translateX(-50%);
  width: min(560px, calc(100vw - 32px));
  max-height: calc(100vh - 170px);
  z-index: 24;
}
.commit {
  font-size: 0.9em;
  margin: 2px 0;
  word-break: break-word;
}
.indent {
  margin-left: 18px;
}
.branch .head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 6px;
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}
.link {
  background: none;
  border: none;
  padding: 0;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
</style>
