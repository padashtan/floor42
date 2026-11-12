<script setup lang="ts">
import type { AgentView } from '../api/types'
import { t, tr } from '../i18n'
import { cssColor, inkOn, uniformOf } from '../scene/identity'
import { contexts } from '../state/context'
import { office } from '../state/office'
import { contextTip, gauge } from '../ui/contextGauge'

/** The crew strip under the bar: one card per agent (click selects, double click opens, right click acts), and the two buttons that hire or dismiss. */
const emit = defineEmits<{
  agent: [agent: AgentView]
  /** Double click: the agent's profile and what it is doing now. */
  open: [id: string]
  /** Right click: the agent's actions, as in the scene. */
  menu: [id: string, x: number, y: number]
  /** An action of the registry (ui/actions.ts), by id: App.vue runs it. */
  action: [id: string]
}>()

const STATUS_COLOR: Record<string, string> = {
  AVAILABLE: 'var(--muted)',
  ASSIGNED: 'var(--text)',
  WORKING: 'var(--cyan)',
  REVIEWING: 'var(--cyan)',
  IN_COMMITTEE: 'var(--green)',
  IN_RETRO: 'var(--green)',
  IN_MEETING: 'var(--amber)',
  EXECUTING: 'var(--cyan)',
  WAITING_FOR_AUTHORIZATION: 'var(--amber)',
  WAITING_FOR_DIRECTOR: 'var(--amber)',
  PAUSED: 'var(--muted)',
}
</script>

<template>
  <div class="crew">
    <button
      v-for="a in office.agents"
      :key="a.id"
      class="card"
      :title="`${a.name} — ${a.role} — ${a.status}\n${t('top.crewHint')}`"
      @click="emit('agent', a)"
      @dblclick="emit('open', a.id)"
      @contextmenu.prevent="emit('menu', a.id, $event.clientX, $event.clientY)"
    >
      <div class="face" :style="{ background: cssColor(uniformOf(a.id)), color: inkOn(uniformOf(a.id)) }">{{ a.name[0] }}</div>
      <div class="name">
        <span v-if="a.core" class="core-star" :title="t('core.starTitle')">★</span>{{ a.name }}
      </div>
      <div class="state" :style="{ color: a.suspended ? 'var(--muted)' : STATUS_COLOR[a.status] }">
        {{ a.suspended ? t('top.suspended') : tr(`status.${a.status}`, a.status.toLowerCase()) }}
      </div>
      <div class="bar" :style="{ background: a.suspended ? 'var(--muted)' : STATUS_COLOR[a.status] }"></div>
      <div
        v-if="a.atWork && contexts[a.id]"
        class="ctx-bar"
        :class="gauge(contexts[a.id]).level"
        :title="contextTip(contexts[a.id])"
      >
        <div
          v-if="gauge(contexts[a.id]).share !== null"
          class="ctx-fill"
          :style="{ width: `${(gauge(contexts[a.id]).share ?? 0) * 100}%` }"
        ></div>
      </div>
    </button>
    <button class="card hire" :title="t('top.hireTitle')" @click="emit('action', 'hire')">
      <div class="face">+</div>
      <div class="name">{{ t('top.hire') }}</div>
      <div class="state">{{ t('top.onStaff', { count: office.agents.length }) }}</div>
    </button>
    <button class="card hire" :title="t('core.dismissAllTitle')" @click="emit('action', 'core')">
      <div class="face">★</div>
      <div class="name">{{ t('core.dismissAll') }}</div>
      <div class="state">{{ t('core.onStaff', { count: office.agents.filter((a) => a.core).length }) }}</div>
    </button>
  </div>
</template>

<style scoped>
.core-star {
  color: var(--amber);
  margin-right: 2px;
}
</style>
