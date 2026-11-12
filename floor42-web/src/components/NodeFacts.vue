<script setup lang="ts">
import type { TicketView } from '../api/types'
import { t } from '../i18n'

/**
 * What a graph node carries (ADR-0062): its risk and tier, the tests Floor42 runs after delivery with what each run
 * showed, and the mark of a done node whose dependency was reopened since. Nothing here is acted on.
 */
defineProps<{ ticket: TicketView }>()

const outcome = (o: string) => t(o === 'passed' ? 'graph.run.passed' : o === 'failed' ? 'graph.run.failed' : 'graph.run.notRun')
</script>

<template>
  <div v-if="ticket.node || ticket.toRecheck" class="node-facts">
    <div v-if="ticket.node" class="context">
      <span :class="{ bad: ticket.node.risk === 'high' }">{{ t(`graph.risk.${ticket.node.risk}`) }}</span>
      · {{ t(`graph.tier.${ticket.node.tier}`) }}
    </div>
    <div v-if="ticket.toRecheck" class="context warn recheck">{{ t('graph.toRecheck') }} : {{ t('graph.toRecheckHint') }}</div>
    <template v-if="ticket.node?.tests.length">
      <div class="sub">{{ t('graph.facts.tests') }}</div>
      <ul class="criteria">
        <li v-for="c in ticket.node.tests" :key="c">
          <code>{{ c }}</code>
        </li>
      </ul>
      <div v-for="(r, i) in ticket.node.runs" :key="i" class="context node-run" :class="r.outcome.replace(' ', '-')">
        <code>{{ r.command }}</code> : {{ outcome(r.outcome) }}
      </div>
    </template>
  </div>
</template>

<style scoped>
.bad,
.failed {
  color: var(--red);
}
.warn {
  color: var(--amber);
}
</style>
