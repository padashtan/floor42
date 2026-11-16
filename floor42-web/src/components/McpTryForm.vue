<script setup lang="ts">
// "Try" on one tool: the form its schema gives (flat fields, else one JSON area), then the answer. The call uses the
// chosen agent's token on the director's behalf (the panel says so); what comes back is the server's text, shown as text.
import { computed, ref } from 'vue'
import { mcpApi, type McpToolView, type McpTryResult } from '../api/mcp'
import { t } from '../i18n'
import { argumentsOf, fieldsOf, refusalOf } from '../ui/mcp'

const props = defineProps<{ resource: string; tool: McpToolView; agent: string; mission: string; disabled: boolean }>()
const emit = defineEmits<{ close: [] }>()

const fields = computed(() => fieldsOf(props.tool.inputSchema))
const text = ref<Record<string, string>>({})
const flags = ref<Record<string, boolean>>({})
const result = ref<McpTryResult | null>(null)
const refused = ref<{ code: string; reason: string } | null>(null)
const badJson = ref(false)
const busy = ref(false)

async function call() {
  badJson.value = false
  refused.value = null
  result.value = null
  let args: Record<string, unknown>
  try {
    args = argumentsOf(fields.value, { ...text.value, ...flags.value })
  } catch {
    badJson.value = true
    return
  }
  busy.value = true
  try {
    const answer = await mcpApi.tryCall(props.resource, props.agent, props.mission, props.tool.name, args)
    if (answer.refusal) refused.value = { code: answer.refusal, reason: answer.firstLine }
    else result.value = answer
  } catch (e) {
    refused.value = refusalOf(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="mcp-try" @submit.prevent="call">
    <div class="sub">{{ t('mcp.try') }}: {{ tool.name }}</div>
    <label v-for="f in fields" :key="f.name" class="field">
      <span v-if="f.kind === 'json'">{{ t('mcp.json') }}</span>
      <span v-else>{{ f.name }}<b v-if="f.required" class="req" :title="t('mcp.required')"> *</b></span>
      <textarea v-if="f.kind === 'json'" v-model="text[f.name]" rows="5" spellcheck="false"></textarea>
      <input v-else-if="f.kind === 'boolean'" v-model="flags[f.name]" type="checkbox" />
      <select v-else-if="f.kind === 'enum'" v-model="text[f.name]">
        <option v-if="!f.required" value=""></option>
        <option v-for="o in f.options" :key="o" :value="o">{{ o }}</option>
      </select>
      <input v-else v-model="text[f.name]" :type="f.kind === 'number' ? 'number' : 'text'" :required="f.required" />
    </label>
    <div v-if="badJson" class="refused bad">{{ t('mcp.badJson') }}</div>
    <div class="try-actions">
      <button class="action" type="submit" :disabled="disabled || busy">{{ t('mcp.call') }}</button>
      <button class="action close-try" type="button" @click="emit('close')">{{ t('mcp.close') }}</button>
    </div>
    <div v-if="refused" class="refused bad" role="alert">{{ t('mcp.refused', refused) }}</div>
    <div v-if="result" class="try-result">
      <div class="first-line">{{ result.firstLine }}</div>
      <div class="hint">{{ t('mcp.untrusted') }}</div>
      <pre>{{ result.body }}</pre>
      <div class="hint">{{ t('mcp.recorded') }}</div>
    </div>
  </form>
</template>
