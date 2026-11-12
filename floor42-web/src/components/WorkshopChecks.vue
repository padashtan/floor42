<script setup lang="ts">
import { computed, ref } from "vue";
import { api } from "../api/client";
import type { MissionView } from "../api/types";
import { t } from "../i18n";
import { clock } from "../ui/when";
import { workshopCall } from "../state/office";
import TerminalBlock from "./TerminalBlock.vue";
import { why } from "../ui/why";

/** The workshop's checks: the mission's check command run on one workspace, and its last measured run. */
const props = defineProps<{ mission: MissionView; agent: string | null; busy: boolean; agentAtWork: boolean }>();

const checking = ref(false);
const lastCheck = computed(
  () => [...(props.mission.checkRuns ?? [])].reverse().find((r) => r.workspace === props.agent) ?? null,
);

async function runChecks() {
  if (!props.agent) return;
  const agent = props.agent;
  checking.value = true;
  try {
    await workshopCall(() => api.runChecks(props.mission.id, agent));
  } finally {
    checking.value = false;
  }
}

</script>

<template>
  <div class="sub">{{ t("results.checks") }}</div>
  <div v-if="!mission.checkCommand" class="hint">{{ t("ws.noChecks") }}</div>
  <template v-else>
    <button
      class="action"
      :disabled="checking || busy || agentAtWork"
      :title="why([agentAtWork, t('why.agentAtWork')]) ?? t('ws.checksTitle', { command: mission.checkCommand })"
      @click="runChecks"
    >
      {{ checking ? t("ws.running") : t("ws.run", { command: mission.checkCommand }) }}
    </button>
    <div v-if="lastCheck" class="check-run">
      <span :class="lastCheck.passed ? 'passed' : 'failed'">
        {{
          lastCheck.passed
            ? t("ws.passed")
            : lastCheck.timedOut
              ? t("ws.timedOut")
              : t("ws.failed", { code: lastCheck.exitCode ?? "?" })
        }}
      </span>
      · {{ (lastCheck.durationMs / 1000).toFixed(1) }} s ·
      {{ clock(lastCheck.at) }} · {{ lastCheck.trigger }} ·
      <span class="hint">{{ t("ws.measured") }}</span>
      <TerminalBlock
        :text="lastCheck.output"
        :name="`check-${lastCheck.at}`"
        height="200px"
        follow
      />
    </div>
  </template>
</template>
