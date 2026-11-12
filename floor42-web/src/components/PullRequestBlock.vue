<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type { PullRequestOutcome, PullRequestPreview, PullRequestView } from '../api/types'
import { t, tr, dateLocale } from '../i18n'
import { why } from '../ui/why'

/**
 * Pull requests on GitHub (ADR-0036), on the director's click only. "Open a pull request" asks Floor42 for a preview
 * (pushes nothing); a dialog shows remote, branch, base, title and body; only its confirmation pushes the branch
 * (never forced) and opens the pull request with the director's own gh. Checks are read on the director's click.
 */
// Pushing reads committed refs only: an agent at work in the worktree does not change them, so it never blocks this.
const props = defineProps<{ mission: string; workspace: string | null; accepted: boolean }>()

const list = ref<PullRequestView[]>([])
const preview = ref<PullRequestPreview | null>(null)
const title = ref('')
const body = ref('')
const outcome = ref<PullRequestOutcome | null>(null)
const error = ref('')
const busy = ref(false)
const refreshing = ref<number | null>(null)
const copied = ref(false)

const mine = computed(() => list.value.filter((p) => p.workspace === props.workspace))
const canConfirm = computed(
  () => !!preview.value && !preview.value.refused && !busy.value && !!title.value.trim() && !!body.value.trim(),
)

function message(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

async function load() {
  try {
    list.value = await api.pullRequests(props.mission)
  } catch (e) {
    error.value = message(e)
  }
}

async function ask() {
  busy.value = true
  error.value = ''
  outcome.value = null
  try {
    preview.value = await api.pullRequestPreview(props.mission, props.workspace)
    title.value = preview.value.title
    body.value = preview.value.body
  } catch (e) {
    error.value = message(e)
  } finally {
    busy.value = false
  }
}

async function confirmOpen() {
  const p = preview.value
  if (!p || p.refused || !p.remote || !p.base) return
  busy.value = true
  error.value = ''
  try {
    outcome.value = await api.openPullRequest(props.mission, {
      workspace: p.workspace,
      remote: p.remote,
      branch: p.branch,
      base: p.base,
      title: title.value.trim(),
      body: body.value.trim(),
      remoteUrl: p.remoteUrl,
    })
    preview.value = null
    await load()
  } catch (e) {
    error.value = message(e)
  } finally {
    busy.value = false
  }
}

async function refresh(pr: PullRequestView) {
  refreshing.value = pr.number
  error.value = ''
  try {
    await api.refreshPullRequestChecks(props.mission, pr.number)
    await load()
  } catch (e) {
    error.value = message(e)
  } finally {
    refreshing.value = null
  }
}

async function copyFix(command: string) {
  try {
    await navigator.clipboard.writeText(command)
    copied.value = true
    window.setTimeout(() => (copied.value = false), 1500)
  } catch {
    copied.value = false
  }
}

function summary(pr: PullRequestView): string {
  if (!pr.checksAt) return t('pr.checksNever')
  if (pr.checks.length === 0) return t('pr.checksNone')
  const failed = pr.checks.filter((c) => c.bucket === 'fail' || c.bucket === 'cancel').length
  const pending = pr.checks.filter((c) => c.bucket === 'pending').length
  if (failed) return t('pr.checksFailed', { failed, count: pr.checks.length })
  if (pending) return t('pr.checksPending', { pending, count: pr.checks.length })
  return t('pr.checksPassed', { count: pr.checks.length })
}

function when(at: string | null): string {
  return at ? new Date(at).toLocaleString(dateLocale()) : ''
}

watch(
  () => [props.mission, props.workspace],
  () => {
    preview.value = null
    outcome.value = null
    error.value = ''
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="block pull-requests">
    <div class="sub">{{ t('pr.title') }}</div>
    <div class="row">
      <button
        class="action"
        :disabled="busy || !accepted"
        :title="why([!accepted, t('pr.whyNothingAccepted')]) ?? t('pr.openTitle')"
        @click="ask"
      >
        {{ busy && !preview ? t('pr.asking') : t('pr.open') }}
      </button>
      <span class="hint">{{ t('pr.hint') }}</span>
    </div>

    <div v-if="error" class="hint warn">{{ error }}</div>
    <div v-if="outcome" class="pr-outcome" :class="outcome.outcome.toLowerCase()">
      <b>{{ tr(`pr.outcome.${outcome.outcome}`, outcome.outcome) }}</b>
      <a v-if="outcome.pullRequest" :href="outcome.pullRequest.url" target="_blank" rel="noopener noreferrer">
        #{{ outcome.pullRequest.number }}
      </a>
      <span v-if="outcome.pushed === true"> · {{ t('pr.pushed') }}</span>
      <span v-else-if="outcome.pushed === false"> · {{ t('pr.notPushed') }}</span>
      <span v-else-if="outcome.outcome !== 'OPENED'"> · {{ t('pr.pushUnknown') }}</span>
      <div v-if="outcome.detail" class="hint">{{ outcome.detail }}</div>
      <button v-if="outcome.retry" class="link" :disabled="busy" @click="ask">{{ t('pr.retry') }}</button>
      <button v-else-if="outcome.outcome === 'UNKNOWN'" class="link" :disabled="busy" @click="ask">
        {{ t('pr.lookAgain') }}
      </button>
    </div>

    <div v-for="pr in mine" :key="pr.number" class="pr-line">
      <a :href="pr.url" target="_blank" rel="noopener noreferrer">#{{ pr.number }}</a>
      <code>{{ pr.branch }}</code> → <code>{{ pr.base }}</code>
      <span class="hint">· {{ when(pr.openedAt) }}<span v-if="pr.found"> · {{ t('pr.found') }}</span></span>
      <div class="row">
        <span :class="['pr-checks', pr.checks.some((c) => c.bucket === 'fail') ? 'failed' : '']">{{ summary(pr) }}</span>
        <span v-if="pr.checksAt" class="hint">{{ t('pr.checksAt', { time: when(pr.checksAt) }) }}</span>
        <button class="link" :disabled="refreshing !== null" :title="t('pr.refreshTitle')" @click="refresh(pr)">
          {{ refreshing === pr.number ? t('pr.refreshing') : t('pr.refresh') }}
        </button>
      </div>
      <ul v-if="pr.checks.length" class="pr-check-list">
        <li v-for="c in pr.checks" :key="c.name" :class="c.bucket">
          {{ c.name }} · {{ tr(`pr.bucket.${c.bucket}`, c.bucket) }}
        </li>
      </ul>
    </div>

    <div v-if="preview" class="pr-dialog" role="dialog" aria-modal="true" :aria-label="t('pr.dialogTitle')">
      <div class="pr-dialog-box">
        <div class="title">{{ t('pr.dialogTitle') }}</div>
        <div class="hint warn">{{ t('pr.outward') }}</div>

        <div v-if="!preview.gh.available || !preview.gh.authenticated" class="pr-gh">
          <div>{{ preview.gh.detail }}</div>
          <div v-if="preview.gh.fix" class="row">
            <code>{{ preview.gh.fix }}</code>
            <button class="link" @click="copyFix(preview.gh.fix)">
              {{ copied ? t('pr.copied') : t('pr.copy') }}
            </button>
          </div>
          <div class="hint">{{ t('pr.neverLogin') }}</div>
        </div>
        <div v-if="preview.refused" class="hint warn">
          <b>{{ preview.refused }}</b>: {{ preview.refusal }}
        </div>

        <table class="pr-facts">
          <tbody>
            <tr>
              <th>{{ t('pr.remote') }}</th>
              <td>
                <code>{{ preview.remote ?? '—' }}</code> <span class="hint">{{ preview.remoteUrl ?? '' }}</span>
              </td>
            </tr>
            <tr>
              <th>{{ t('pr.repository') }}</th>
              <td><code>{{ preview.repository ?? '—' }}</code></td>
            </tr>
            <tr>
              <th>{{ t('pr.branch') }}</th>
              <td>
                <code>{{ preview.branch || '—' }}</code>
                <span v-if="preview.head" class="hint">
                  {{ t('pr.headAt', { head: preview.head.slice(0, 8), count: preview.commits }) }}</span
                >
              </td>
            </tr>
            <tr>
              <th>{{ t('pr.base') }}</th>
              <td><code>{{ preview.base ?? '—' }}</code></td>
            </tr>
          </tbody>
        </table>
        <div v-if="preview.pending > 0" class="hint warn">{{ t('pr.pending', { count: preview.pending }) }}</div>
        <div v-else-if="preview.pending < 0" class="hint warn">{{ t('pr.pendingUnknown') }}</div>
        <div v-if="preview.existing" class="hint">
          {{ t('pr.existing', { number: preview.existing.number }) }}
          <a :href="preview.existing.url" target="_blank" rel="noopener noreferrer">{{ preview.existing.url }}</a>
        </div>

        <label class="sub">{{ t('pr.titleField') }}</label>
        <input v-model="title" :aria-label="t('pr.titleField')" />
        <label class="sub">{{ t('pr.bodyField') }}</label>
        <textarea v-model="body" rows="14" :aria-label="t('pr.bodyField')" spellcheck="false"></textarea>
        <div class="hint">{{ t('pr.bodyHint') }}</div>

        <div class="row">
          <button
            class="action recommended"
            :disabled="!canConfirm"
            :title="why([!!preview.refused, preview.refusal ?? ''], [!title.trim() || !body.trim(), t('why.needText')])"
            @click="confirmOpen"
          >
            {{
              busy
                ? t('pr.opening')
                : preview.existing
                  ? t('pr.confirmExisting')
                  : t('pr.confirm', { branch: preview.branch, remote: preview.remote ?? '' })
            }}
          </button>
          <button class="action" :disabled="busy" @click="preview = null">{{ t('common.cancel') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
