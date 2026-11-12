import { type ChildProcess, execFileSync, spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

/**
 * A Floor42 daemon of its own for the browser tests: the packaged build, a free port, a data folder in the temp
 * directory and the fake Claude Code of the server tests. Never ~/.floor42, never a real agent or a paid model.
 */
export interface Daemon {
  url: string
  dataDir: string
  /** Everything the daemon wrote on its standard output and error so far. */
  output(): string
  /** The next sign-in link the daemon prints (delivery "stdout", ADR-0034), after `since` characters of output. */
  signInLink(since: number, timeoutMs?: number): Promise<string>
  /** A throwaway git repository with one commit, as the server tests make them. */
  gitRepository(): string
  stop(): Promise<void>
}

const ROOT = resolve(import.meta.dirname, '..', '..')
const JAR = join(ROOT, 'floor42-server', 'target', 'quarkus-app', 'quarkus-run.jar')
const FAKE = join(ROOT, 'floor42-server', 'src', 'test', 'resources', 'fake-claude')

/** Java 25 or later, as bin/floor42 looks for it: FLOOR42_JAVA, then JAVA_HOME, then the PATH. */
function findJava(): string {
  const home = process.env.JAVA_HOME
  const candidates = [process.env.FLOOR42_JAVA, home && join(home, 'bin', 'java'), 'java'].filter(Boolean) as string[]
  for (const java of candidates) {
    // `java -version` writes on stderr.
    const r = spawnSync(java, ['-version'], { encoding: 'utf8' })
    const major = Number(/version "(\d+)/.exec(`${r.stderr ?? ''}${r.stdout ?? ''}`)?.[1] ?? 0)
    if (major >= 25) return java
  }
  throw new Error('Java 25 or later is required for the daemon: set FLOOR42_JAVA or JAVA_HOME.')
}

function freePort(): Promise<number> {
  return new Promise((ok, ko) => {
    const server = createServer()
    server.once('error', ko)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      const port = typeof address === 'object' && address ? address.port : 0
      server.close(() => ok(port))
    })
  })
}

async function waitUp(url: string, child: ChildProcess, output: () => string): Promise<void> {
  const until = Date.now() + 90_000
  while (Date.now() < until) {
    if (child.exitCode !== null) throw new Error(`The daemon exited with ${child.exitCode}:\n${output()}`)
    try {
      const r = await fetch(`${url}/api/status`)
      if (r.ok) return
    } catch {
      // Not listening yet.
    }
    await new Promise((r) => setTimeout(r, 300))
  }
  throw new Error(`The daemon did not answer on ${url} within 90 s:\n${output()}`)
}

function git(dir: string, ...args: string[]): void {
  execFileSync('git', args, { cwd: dir, stdio: 'ignore' })
}

export async function startDaemon(): Promise<Daemon> {
  if (!existsSync(JAR)) {
    throw new Error(`No packaged daemon at ${JAR}: build it first (./mvnw -q package -DskipTests, or make e2e).`)
  }
  const java = findJava()
  const port = await freePort()
  const url = `http://127.0.0.1:${port}`
  const base = mkdtempSync(join(tmpdir(), 'floor42-e2e-'))
  const dataDir = join(base, 'data')
  const repos: string[] = []
  const claude = [
    java,
    join(FAKE, 'FakeClaude.java'),
    '--fake-script',
    join(FAKE, 'session.txt'),
    '--fake-review-script',
    join(FAKE, 'review.txt'),
  ]
    // A list in MicroProfile Config: a backslash escapes the next character, so Windows paths go with forward slashes.
    .map((part) => part.replaceAll('\\', '/'))
    .join(',')
  const props: Record<string, string> = {
    'quarkus.http.port': String(port),
    'floor42.data-dir': dataDir,
    'floor42.legacy-data-dir': join(base, 'no-legacy-data'),
    'floor42.sign-in.delivery': 'stdout',
    'floor42.claude.command': claude,
    'floor42.claude.hook-timeout-seconds': '60',
    'floor42.catalogue.claude-home': join(base, 'claude-home'),
    'floor42.gh.command': 'floor42-no-gh-in-e2e',
    'floor42.backup.dir': join(base, 'backups'),
    // Never the real claude: no plugin command, a listing base nothing answers, a fake interactive terminal.
    'floor42.plugins.claude-command': 'floor42-no-claude-in-e2e',
    'floor42.plugins.listing-base': 'http://127.0.0.1:9',
    // It draws Claude Code's input line, which the catalogue's "Prepare in my console" waits for (ADR-0041).
    'floor42.terminal.command': [java, join(ROOT, 'floor42-server', 'src', 'test', 'resources', 'fake-tty', 'FakeTty.java'), '--claude-prompt']
      .map((part) => part.replaceAll('\\', '/'))
      .join(','),
    'floor42.terminal.claude-home': join(base, 'claude-home'),
    'floor42.retro.enabled': 'false',
    'floor42.plan-limit.resume-margin-seconds': '1',
    // Any probe of the WSL sandbox fails at once instead of touching the director's distribution.
    'floor42.sandbox.distro': 'floor42-e2e-no-such-distro',
    // The fake Claude Code plays every session, as in the %test profile: the mandatory sandbox (ADR-0063) is off here.
    'floor42.sandbox.mandatory': 'false',
  }
  // As bin/floor42 and the Docker image start it: without the flag the daemon warns in red, over the panels.
  const args = ['-XX:+DisableAttachMechanism', ...Object.entries(props).map(([k, v]) => `-D${k}=${v}`), '-jar', JAR]
  // The fake Claude Code reads the hook token from this variable: never inherit the director's.
  const env = { ...process.env }
  delete env.FLOOR42_HOOK_TOKEN
  delete env.FLOOR42_DIRECTOR_TOKEN_FILE
  const child = spawn(java, args, { cwd: base, env, stdio: ['ignore', 'pipe', 'pipe'] })
  let out = ''
  child.stdout!.setEncoding('utf8').on('data', (d: string) => (out += d))
  child.stderr!.setEncoding('utf8').on('data', (d: string) => (out += d))
  const output = () => out
  try {
    await waitUp(url, child, output)
  } catch (e) {
    child.kill()
    throw e
  }
  return {
    url,
    dataDir,
    output,
    async signInLink(since, timeoutMs = 10_000) {
      const until = Date.now() + timeoutMs
      while (Date.now() < until) {
        const m = /Floor42 sign-in link \([^)]*\): (\S+)/.exec(out.slice(since))
        if (m) return m[1]
        await new Promise((r) => setTimeout(r, 100))
      }
      throw new Error('The daemon printed no sign-in link')
    },
    gitRepository() {
      const repo = mkdtempSync(join(base, 'repo-'))
      writeFileSync(join(repo, 'README.md'), '# Fake project\n')
      git(repo, 'init', '-q', '-b', 'main')
      git(repo, 'add', '.')
      git(repo, '-c', 'user.name=test', '-c', 'user.email=test@localhost', 'commit', '-q', '-m', 'init')
      repos.push(repo)
      return repo
    },
    async stop() {
      if (child.exitCode === null) {
        const exited = new Promise((r) => child.once('exit', r))
        child.kill()
        await Promise.race([exited, new Promise((r) => setTimeout(r, 10_000))])
      }
      // Windows may hold a file a moment after the process ends: a leftover temp folder is not a failure.
      try {
        rmSync(base, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 })
      } catch {
        // Kept in the temp directory.
      }
    },
  }
}
