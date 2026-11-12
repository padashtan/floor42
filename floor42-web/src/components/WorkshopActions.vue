<script setup lang="ts">
import type { TicketView, WorkspaceView } from "../api/types";
import { t } from "../i18n";
import PullRequestBlock from "./PullRequestBlock.vue";
import { why } from "../ui/why";

/** The workshop's decisions: send a ticket back with a directive, accept the workspace's changes, or archive them. */
defineProps<{
  mission: string | null;
  workspace: WorkspaceView | null;
  tickets: TicketView[];
  pending: number;
  /** The pending files ticked for the next accept. */
  picked: number;
  busy: boolean;
  atWork: string[];
  accepted: boolean;
}>();
const emit = defineEmits<{ send: []; accept: []; reject: [] }>();
const ticket = defineModel<string | null>("ticket", { required: true });
const directive = defineModel<string>("directive", { required: true });
const message = defineModel<string>("message", { required: true });
const reason = defineModel<string>("reason", { required: true });
</script>

<template>
  <footer class="workshop-actions" :aria-label="t('ws.footerTitle')">
    <div v-if="atWork.length > 0" class="hint warn footer-why">
      {{ t("ws.agentAtWork", { who: atWork.join(", ") }) }}
    </div>
    <div class="block">
      <div class="sub">{{ t("ws.directive") }}</div>
      <div class="row">
        <select v-model="ticket" :aria-label="t('ws.directiveTicket')">
          <option v-for="tk in tickets" :key="tk.id" :value="tk.id">
            {{ tk.id }} · {{ tk.title }}
          </option>
        </select>
      </div>
      <textarea
        v-model="directive"
        rows="2"
        :placeholder="t('ws.directivePlaceholder')" :aria-label="t('ws.directivePlaceholder')" />
      <button
        class="action"
        :disabled="busy || atWork.length > 0 || !directive.trim()"
        :title="why([atWork.length > 0, t('why.agentAtWork')], [!directive.trim(), t('why.needText')])"
        @click="emit('send')"
      >
        {{ t("ws.sendBack") }}
      </button>
      <span class="hint">{{ t("ws.sendBackHint") }}</span>
    </div>
    <div class="block">
      <div class="sub">{{ t("ws.accept") }}</div>
      <input v-model="message" :placeholder="t('ws.commitMessage')" :aria-label="t('ws.commitMessage')" />
      <button
        class="action recommended"
        :disabled="busy || atWork.length > 0 || picked === 0 || !message.trim()"
        :title="why([atWork.length > 0, t('why.agentAtWork')], [picked === 0, t('why.noChanges')], [!message.trim(), t('why.needMessage')])"
        @click="emit('accept')"
      >
        {{ t("ws.commitOn", { branch: workspace?.branch ?? "" }) }}
      </button>
      <div v-if="pending > 0" class="hint picked-count">{{ t("ws.pickedCount", { picked, total: pending }) }}</div>
      <div class="hint">{{ t("ws.untouched") }}</div>
    </div>
    <div class="block">
      <div class="sub">{{ t("ws.reject") }}</div>
      <input v-model="reason" :placeholder="t('board.fieldWhy')" :aria-label="t('board.fieldWhy')" />
      <button
        class="action"
        :disabled="busy || atWork.length > 0 || pending === 0 || !reason.trim()"
        :title="why([atWork.length > 0, t('why.agentAtWork')], [pending === 0, t('why.noChanges')], [!reason.trim(), t('why.needReason')])"
        @click="emit('reject')"
      >
        {{ t("ws.archive") }}
      </button>
    </div>
    <PullRequestBlock v-if="mission && workspace" :mission="mission" :workspace="workspace.agent" :accepted="accepted" />
  </footer>
</template>
