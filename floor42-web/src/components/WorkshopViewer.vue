<script setup lang="ts">
import { computed } from "vue";
import { t } from "../i18n";
import { diffLines } from "./diff";
import TerminalBlock from "./TerminalBlock.vue";
import { why } from "../ui/why";

/** The workshop's viewer: a file's diff, the file itself, or the director's edit of it. */
const props = defineProps<{
  path: string | null;
  mode: "diff" | "file" | "edit";
  text: string | null;
  binary: boolean;
  busy: boolean;
  agentAtWork: boolean;
  /** A pending change the file viewer can open (ADR-0051): read through the deliverables route. */
  viewable?: boolean;
  /** The key the focus returns to when the viewer closes (state/viewer.ts). */
  viewKey?: string;
}>();
const emit = defineEmits<{ open: [path: string, mode: "diff" | "file"]; edit: [path: string]; save: []; view: [path: string] }>();
const draft = defineModel<string>("draft", { required: true });
const note = defineModel<string>("note", { required: true });

const lines = computed(() => (props.mode === "diff" && props.text !== null ? diffLines(props.text) : []));
</script>

<template>
  <section class="workshop-main">
    <div v-if="!path" class="hint">{{ t("ws.pick") }}</div>
    <template v-else>
      <div class="viewer-head">
        <b class="viewer-path">{{ path }}</b>
        <div class="tabs">
          <button
            :class="{ on: mode === 'diff' }"
            @click="emit('open', path, 'diff')"
          >
            {{ t("ws.diff") }}
          </button>
          <button
            :class="{ on: mode === 'file' }"
            @click="emit('open', path, 'file')"
          >
            {{ t("ws.file") }}
          </button>
          <button
            :class="{ on: mode === 'edit' }"
            :disabled="binary"
            :title="why([binary, t('why.binary')])"
            @click="emit('edit', path)"
          >
            {{ t("board.edit") }}
          </button>
          <button v-if="viewable" :data-view-key="viewKey" @click="emit('view', path)">{{ t("ws.viewFile") }}</button>
        </div>
      </div>
      <div v-if="mode === 'diff'" class="code">
        <div v-if="lines.length === 0" class="hint">{{ t("ws.noChange") }}</div>
        <div v-for="(l, n) in lines" :key="n" class="line" :class="l.kind">
          {{ l.text || " " }}
        </div>
      </div>
      <div v-else-if="mode === 'file'" class="code" :class="{ 'fill-host': !binary }">
        <div v-if="binary" class="hint">{{ t("ws.binary") }}</div>
        <TerminalBlock v-else class="fill" :text="text ?? ''" :name="path ?? 'file'" height="calc(100vh - 360px)" />
      </div>
      <div v-else class="editor">
        <textarea v-model="draft" spellcheck="false" :aria-label="t('ws.file')"></textarea>
        <div class="row">
          <input
            v-model="note"
            :placeholder="t('ws.why')" :aria-label="t('ws.why')" />
          <button
            class="action recommended"
            :disabled="busy || agentAtWork"
            :title="why([agentAtWork, t('why.agentAtWork')])"
            @click="emit('save')"
          >
            {{ t("common.save") }}
          </button>
          <button
            class="action"
            :disabled="busy"
            @click="emit('open', path, 'file')"
          >
            {{ t("common.cancel") }}
          </button>
        </div>
      </div>
    </template>
  </section>
</template>
