<script setup lang="ts">
import { computed } from 'vue'
import { t, dateLocale } from '../i18n'
import { office } from '../state/office'

const emit = defineEmits<{ close: [] }>()

const usage = computed(() => office.usage)

function when(iso: string): string {
  return new Date(iso).toLocaleString(dateLocale(), { weekday: 'short', hour: '2-digit', minute: '2-digit' })
}

function pct(v: number): string {
  return Math.round(v * 100) + '%'
}

function tokens(n: number): string {
  return n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n)
}
</script>

<template>
  <div class="panel usage framed">
    <div class="title">
      {{ t('usage.title') }}
      <button class="close" :title="t('desk.close')" @click="emit('close')">×</button>
    </div>
    <div class="panel-scroll">
    <template v-if="usage">
      <div class="sub">{{ t('usage.windows') }}</div>
      <div class="hint">{{ t('quota.panelSource') }}</div>
      <div v-if="usage.windows.length === 0" class="hint">{{ t('usage.noReading') }}</div>
      <div v-for="w in usage.windows" :key="w.window" class="window">
        <div class="name">{{ w.label }}</div>
        <div class="bar" :class="{ stale: !w.current }">
          <div
            class="fill"
            :class="{ high: w.utilization >= usage.threshold, medium: w.utilization >= 0.7 }"
            :style="{ width: pct(Math.min(1, w.utilization)) }"
          ></div>
          <div class="limit" :style="{ left: pct(usage.threshold) }" :title="t('usage.limitTitle')"></div>
        </div>
        <div class="facts">
          <template v-if="w.current">
            {{ t('usage.current', { pct: pct(w.utilization), resets: when(w.resetsAt), read: when(w.observedAt) }) }}
          </template>
          <template v-else>{{ t('usage.stale', { at: when(w.resetsAt) }) }}</template>
          <span v-if="w.expectedIncrease !== null">
            · {{ w.expectedIncrease < 0.01 ? t('usage.addsLittle') : t('usage.adds', { pct: pct(w.expectedIncrease) }) }}</span
          >
        </div>
      </div>
      <div class="hint">
        {{ t('usage.holds', { pct: pct(usage.threshold) }) }}
        <a :href="usage.accountUsageUrl" target="_blank" rel="noopener">{{ t('usage.account') }}</a>
      </div>

      <div class="sub">{{ t('usage.perDay') }}</div>
      <table>
        <thead>
          <tr><th>{{ t('usage.day') }}</th><th>{{ t('profile.sessions') }}</th><th>{{ t('usage.cost') }}</th><th>{{ t('usage.in') }}</th><th>{{ t('usage.out') }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="d in usage.days" :key="d.key">
            <td>{{ d.key }}</td>
            <td>{{ d.sessions }}</td>
            <td>{{ d.costUsdEstimated.toFixed(4) }} USD</td>
            <td>{{ tokens(d.inputTokens) }}</td>
            <td>{{ tokens(d.outputTokens) }}</td>
          </tr>
          <tr v-if="usage.days.length === 0"><td colspan="5" class="hint">{{ t('usage.none') }}</td></tr>
        </tbody>
      </table>

      <div class="sub">{{ t('usage.perMission') }}</div>
      <table>
        <thead>
          <tr><th>{{ t('desk.mission') }}</th><th>{{ t('profile.sessions') }}</th><th>{{ t('usage.cost') }}</th><th>{{ t('usage.in') }}</th><th>{{ t('usage.out') }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="m in usage.missions" :key="m.key">
            <td>
              <b>{{ m.key }}</b> {{ m.title }}
              <span v-if="m.archived" class="hint archived-mark">· {{ t('archives.mark') }}</span>
            </td>
            <td>{{ m.sessions }}</td>
            <td>{{ m.costUsdEstimated.toFixed(4) }} USD</td>
            <td>{{ tokens(m.inputTokens) }}</td>
            <td>{{ tokens(m.outputTokens) }}</td>
          </tr>
        </tbody>
      </table>
    </template>
    <div v-else class="hint">{{ t('common.loading') }}</div>
    </div>
  </div>
</template>
