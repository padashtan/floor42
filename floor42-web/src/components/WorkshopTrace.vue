<script setup lang="ts">
import { computed } from "vue";
import type { InterventionView } from "../api/types";
import { t, tr } from "../i18n";
import { clock } from "../ui/when";

/** What the director did in the workshop, newest first: edits, directives, patches accepted or archived. */
const props = defineProps<{ interventions: InterventionView[] }>();

const trace = computed(() => [...props.interventions].reverse());

function short(sha: string) {
  return sha.slice(0, 8);
}
</script>

<template>
  <div class="sub">{{ t("ws.trace") }}</div>
  <div v-if="trace.length === 0" class="hint">{{ t("ws.noTrace") }}</div>
  <div v-for="(i, n) in trace" :key="n" class="trace">
    <span class="when">{{ clock(i.at) }}</span>
    <b>{{ tr(`ws.kind.${i.kind}`, i.kind.replaceAll("_", " ").toLowerCase()) }}</b>
    <span v-if="i.ticket"> · {{ i.ticket }}</span> · {{ i.subject }}
    <span v-if="i.kind === 'PATCH_ACCEPTED' && i.detail">
      · {{ t("ws.commit") }} {{ short(i.detail) }}</span
    >
    <span v-if="i.kind === 'PATCH_REJECTED' && i.detail">
      · {{ t("ws.archivedIn") }} {{ i.detail }}</span
    >
  </div>
</template>
