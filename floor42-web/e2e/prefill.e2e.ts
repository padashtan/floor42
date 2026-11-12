import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { PANEL_ACTIONS, runFromMenu } from './bar'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

// The catalogue prepares a command in the director's console (ADR-0041, amendment of 2026-11-02): typed, never Enter.
const t = CATALOGS.en
const SKILLS = PANEL_ACTIONS.find((a) => a.id === 'skills')!
const TERMINAL = PANEL_ACTIONS.find((a) => a.id === 'terminal')!

test('a skill of the catalogue is prepared in the director’s console without Enter', async ({ page, daemon }) => {
  // A skill of the director's, in the Claude home the daemon reads for its catalogue (daemon.ts).
  const skill = join(daemon.dataDir, '..', 'claude-home', 'skills', 'demo-skill')
  mkdirSync(skill, { recursive: true })
  writeFileSync(join(skill, 'SKILL.md'), '---\nname: demo-skill\ndescription: A sample skill\n---\nSample.\n')
  const clear = join(daemon.dataDir, '..', 'claude-home', 'skills', 'clear')
  mkdirSync(clear, { recursive: true })
  writeFileSync(join(clear, 'SKILL.md'), '---\nname: clear\ndescription: Same name as a built-in command\n---\nSample.\n')
  const repo = daemon.gitRepository()

  await page.setViewportSize({ width: 1400, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  // The director opens his console on a project, then leaves the panel: that project is the last one.
  await runFromMenu(page, t, TERMINAL)
  const terminal = page.locator('.director-terminal')
  await terminal.getByPlaceholder(t['terminal.projectPlaceholder']).fill(repo)
  await terminal.getByRole('button', { name: t['terminal.open'] }).click()
  const screen = terminal.locator('.terminal-screen')
  await expect(screen).toContainText('FAKE-TTY ready')
  // The program asks a question, as Claude Code's folder trust screen does: a static screen, quiet.
  await screen.click()
  await page.keyboard.type('ask')
  await page.keyboard.press('Enter')
  await expect(screen).toContainText('Do you trust the files in this folder?')
  await terminal.getByRole('button', { name: t['terminal.detach'] }).click()
  await terminal.locator('.close').click()

  await runFromMenu(page, t, SKILLS)
  const panel = page.locator('.skills-panel')
  // A skill named like a built-in command is not prepared, and the row says why.
  const builtin = panel.locator('.skills-table tr', { hasText: 'clear' })
  await expect(builtin.getByRole('button', { name: t['prefill.button'] })).toBeDisabled()
  await expect(builtin.locator('.prefill-refusal')).toHaveText(t['prefill.builtin'])
  const row = panel.locator('.skills-table tr', { hasText: 'demo-skill' })
  const prepare = row.getByRole('button', { name: t['prefill.button'] })
  await expect(prepare).toBeVisible()
  // D26: a second load of the panel answering late (here 4 s) must not wipe the hint the first preparation showed.
  let n = 0
  await page.route('**/api/projects', async (route) => {
    if (++n === 2) await new Promise((r) => setTimeout(r, 4000))
    await route.continue()
  })
  await prepare.click()

  // Nothing is typed into the question: the director is told to answer it, then to paste or type the command.
  await expect(terminal).toBeVisible()
  await expect(terminal.locator('.hint.bad')).toContainText('Claude Code is asking something')
  await expect(terminal.locator('.hint.bad')).toContainText('/demo-skill')
  await page.waitForTimeout(5000)
  await expect(terminal.locator('.hint.bad')).toContainText('/demo-skill', { timeout: 1000 })
  await expect(screen).not.toContainText('answered')
  expect(await screen.textContent()).not.toContain('> /demo-skill')

  // He answers it; the input line comes back.
  await screen.click()
  await page.keyboard.type('1')
  await page.keyboard.press('Enter')
  await expect(screen).toContainText('answered: 1')
  await terminal.locator('.close').click()

  await runFromMenu(page, t, SKILLS)
  // By keyboard: the focus on the button, Enter.
  await prepare.focus()
  await page.keyboard.press('Enter')

  await expect(terminal).toBeVisible()
  // The terminal echoes what is typed; the fake answers only a whole line: no "echo:" means no Enter was sent.
  await expect(screen).toContainText('/demo-skill')
  await page.waitForTimeout(1500)
  await expect(screen).not.toContainText('echo: /demo-skill')
  await shot(page, 'prefill-skill-in-console')

  // A second one: the line already holds the first, so the director is asked before anything is added.
  await terminal.locator('.close').click()
  await runFromMenu(page, t, SKILLS)
  await panel.locator('.skills-table tr', { hasText: 'demo-skill' }).getByRole('button', { name: t['prefill.button'] }).click()
  await expect(page.getByText('You already typed something in your console', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: t['prefill.append'] }).click()
  await expect(screen).toContainText(/\/demo-skill\s+\/demo-skill/)
  await expect(screen).not.toContainText('echo:')
})
