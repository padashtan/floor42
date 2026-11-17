<script setup lang="ts">
// External MCP servers (IAM mode), master-detail: the declared servers on the left; the chosen one on the right with
// its tools (a tick each), "Refresh tools as" an agent, and "Try" beside the table. Refresh and Try use the chosen
// agent's own token on the director's behalf, and the screen says so. Server text is shown as text, never as markup.
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { Resource, ResourceCatalogueView } from '../api/identity'
import { type McpServerView, mcpApi } from '../api/mcp'
import { request } from '../api/request'
import type { AgentView, MissionSummary } from '../api/types.office'
import { dateLocale, t } from '../i18n'
import { confirmed } from '../state/confirm'
import { iamOn } from '../ui/identities'
import { refusalOf, summaryOf, ticksAfter, TICK_HINTS } from '../ui/mcp'
import McpTryForm from './McpTryForm.vue'

const emit = defineEmits<{ close: [] }>()

type Refusal = { code: string; reason: string }
const iam = ref(false)
const servers = ref<McpServerView[]>([])
const resources = ref<Resource[]>([])
const agents = ref<AgentView[]>([])
const missions = ref<MissionSummary[]>([])
const chosen = ref<string | null>(null)
const trying = ref<string | null>(null)
const error = ref<string | null>(null)
const addRefusal = ref<Refusal | null>(null)
const refreshRefusal = ref<Refusal | null>(null)
const busy = ref(false)
const addResource = ref('')
const addPath = ref('/mcp')
const agent = ref('')
const mission = ref('')

const selected = computed(() => servers.value.find((s) => s.resource === chosen.value) ?? null)
const tool = computed(() => selected.value?.tools.find((x) => x.name === trying.value) ?? null)
const free = computed(() => resources.value.filter((r) => !servers.value.some((s) => s.resource === r.name)))
const when = (iso: string) => new Date(iso).toLocaleString(dateLocale())

function take(list: McpServerView[]) {
  servers.value = list
  if (!list.some((s) => s.resource === chosen.value)) chosen.value = list[0]?.resource ?? null
  trying.value = null
}
/** One server's new view, in its place in the list. */
const replace = (v: McpServerView) => void (servers.value = servers.value.map((s) => (s.resource === v.resource ? v : s)))

async function load() {
  iam.value = await iamOn()
  try {
    take((await mcpApi.list()).servers)
    resources.value = await request<ResourceCatalogueView>('GET', '/api/iam/resources').then((c) => c.resources, () => [])
    addResource.value ||= free.value[0]?.name ?? ''
    if (iam.value) {
      ;[agents.value, missions.value] = await Promise.all([api.agents(), api.missions()])
      agent.value ||= agents.value[0]?.id ?? ''
      mission.value ||= missions.value[0]?.id ?? ''
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

/** Runs one call; the refusal of the server (a code and its sentence) lands in `into`, in red. */
async function run(into: typeof addRefusal, work: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  into.value = null
  try {
    await work()
  } catch (e) {
    into.value = refusalOf(e)
  } finally {
    busy.value = false
  }
}

const add = () =>
  run(addRefusal, async () => {
    const v = await mcpApi.declare(addResource.value, addPath.value)
    take([...servers.value.filter((s) => s.resource !== v.resource), v])
    chosen.value = v.resource
    addResource.value = free.value[0]?.name ?? ''
  })

const refresh = () => run(refreshRefusal, async () => replace(await mcpApi.refresh(selected.value!.resource, agent.value, mission.value)))

const tick = (name: string, on: boolean) => {
  const s = selected.value!
  return run(refreshRefusal, async () => {
    try {
      const answer = await mcpApi.tick(s.resource, ticksAfter(s, name, on))
      // 202 {redirect} (IAM mode): the page leaves for the step-up; the ticks change when Keycloak confirmed it
      if (!('redirect' in answer)) replace(answer)
    } catch (e) {
      // A stale hash or a moved endpoint: what is on screen is out of date, so read the list again.
      if (refusalOf(e).code in TICK_HINTS) take((await mcpApi.list()).servers)
      throw e
    }
  })
}
const refusalText = (r: Refusal) => (r.code in TICK_HINTS ? t(TICK_HINTS[r.code]) : t('mcp.refused', r))

async function remove() {
  const s = selected.value!
  if (!(await confirmed({ message: t('mcp.removeAsk', { name: s.resource }), confirmLabel: t('mcp.remove'), danger: true }))) return
  await run(refreshRefusal, async () => void take((await mcpApi.remove(s.resource)).servers))
}

onMounted(load)
</script>

<template>
  <div class="panel mcp-panel framed">
    <div class="title">
      {{ t('mcp.top') }}
      <button class="close" :title="t('mcp.close')" @click="emit('close')">×</button>
    </div>
    <div class="hint live">{{ t('mcp.live') }}</div>
    <div v-if="!iam" class="hint warn iam-off">{{ t('mcp.iamOff') }}</div>
    <div v-if="error" class="error">{{ error }}</div>
    <div class="mcp-split">
      <div class="mcp-list" role="listbox" :aria-label="t('mcp.top')">
        <div v-if="!servers.length" class="hint none">{{ t('mcp.none') }}</div>
        <button
          v-for="s in servers"
          :key="s.resource"
          class="server-item"
          :class="{ selected: chosen === s.resource }"
          role="option"
          :aria-selected="chosen === s.resource"
          :data-resource="s.resource"
          @click="((chosen = s.resource), (trying = null))"
        >
          <span class="name">{{ s.resource }}</span>
          <span class="hint server-address">{{ s.baseUrl }}{{ s.path }} · {{ s.audience }}</span>
          <span class="hint">{{ t('mcp.allowed', { n: summaryOf(s).allowed, m: summaryOf(s).total }) }}</span>
          <span v-if="summaryOf(s).changed" class="amber changed-mark">{{ t('mcp.changedMark') }}</span>
        </button>
        <form class="add-form" @submit.prevent="add">
          <div class="sub">{{ t('mcp.add') }}</div>
          <div v-if="!free.length" class="hint no-resource">{{ t('mcp.noResource') }}</div>
          <template v-else>
            <label class="field">
              <span>{{ t('mcp.resource') }}</span>
              <select v-model="addResource">
                <option v-for="r in free" :key="r.name" :value="r.name">{{ r.name }}</option>
              </select>
            </label>
            <label class="field">
              <span>{{ t('mcp.path') }}</span>
              <input v-model="addPath" type="text" required />
              <span class="hint">{{ t('mcp.pathHint') }}</span>
            </label>
            <div><button class="action" type="submit" :disabled="busy">{{ t('mcp.add') }}</button></div>
          </template>
          <div v-if="addRefusal" class="refused bad add-refused" role="alert">{{ t('mcp.refused', addRefusal) }}</div>
        </form>
      </div>
      <div v-if="selected" :key="selected.resource" class="mcp-detail" :data-resource="selected.resource">
        <div class="facts">
          <strong>{{ selected.resource }}</strong>
          · {{ t('mcp.address') }}: {{ selected.baseUrl }}{{ selected.path }}
          · {{ t('mcp.audience') }}: {{ selected.audience }}
          · {{ selected.pinnedAt ? t('mcp.pinnedAt', { when: when(selected.pinnedAt) }) : t('mcp.neverPinned') }}
        </div>
        <form class="refresh-form" @submit.prevent="refresh">
          <div class="sub">{{ t('mcp.refreshAs') }}</div>
          <div class="pickers">
            <label>{{ t('mcp.agent') }} <select v-model="agent"><option v-for="a in agents" :key="a.id" :value="a.id">{{ a.name }}</option></select></label>
            <label>{{ t('mcp.mission') }} <select v-model="mission"><option v-for="m in missions" :key="m.id" :value="m.id">{{ m.id }} {{ m.title }}</option></select></label>
          </div>
          <div class="hint as-note">{{ t('mcp.asNote', { agent: agents.find((a) => a.id === agent)?.name ?? agent, mission }) }}</div>
          <div><button class="action" type="submit" :disabled="!iam || busy || !agent || !mission">{{ t('mcp.refresh') }}</button></div>
          <div v-if="refreshRefusal" class="refused bad" role="alert">{{ refusalText(refreshRefusal) }}</div>
        </form>
        <div class="tools-and-try">
          <table class="tools">
            <thead>
              <tr><th>{{ t('mcp.allow') }}</th><th>{{ t('mcp.tool') }}</th><th>{{ t('mcp.description') }} <span class="hint">({{ t('mcp.fromServer') }})</span></th><th></th></tr>
            </thead>
            <tbody>
              <tr v-for="x in selected.tools" :key="x.name" class="tool" :class="{ changed: x.changed }" :data-tool="x.name">
                <td><input type="checkbox" :checked="x.ticked && !x.changed" :disabled="busy" :aria-label="t('mcp.allow') + ' ' + x.name" @change="tick(x.name, ($event.target as HTMLInputElement).checked)" /></td>
                <td>{{ x.name }}</td>
                <td>
                  <span class="desc">{{ x.description }}</span>
                  <div v-if="x.changed" class="changed-note">{{ t('mcp.changed') }}</div>
                </td>
                <td><button class="action try" type="button" :disabled="!iam" @click="trying = x.name">{{ t('mcp.try') }}</button></td>
              </tr>
            </tbody>
          </table>
          <McpTryForm v-if="tool" :key="tool.name" :resource="selected.resource" :tool="tool" :agent="agent" :mission="mission" :disabled="!iam" @close="trying = null" />
        </div>
        <div class="remove-row"><button class="action danger" type="button" :disabled="busy" @click="remove">{{ t('mcp.remove') }}</button></div>
      </div>
    </div>
  </div>
</template>
