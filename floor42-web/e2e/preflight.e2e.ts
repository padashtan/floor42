import { execFileSync } from 'node:child_process'
import { mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

const git = (cwd: string, ...args: string[]) => execFileSync('git', args, { cwd, stdio: 'ignore' })

/**
 * The new mission form names the folder's build tool, proposes a checks command (not run) and warns of a file the
 * agents will not see. A blocker cannot show here: the e2e daemon runs the fake Claude Code with the sandbox not
 * mandatory; PreflightTest and ui/preflight.test.ts cover it.
 */
test('the form names the build tool, proposes the checks and warns of a dirty tree', { tag: '@projects' }, async ({ page }) => {
  const repo = realpathSync(mkdtempSync(join(tmpdir(), 'floor42-e2e-preflight-')))
  try {
    writeFileSync(join(repo, 'pom.xml'), '<project/>')
    writeFileSync(join(repo, 'mvnw'), '#!/bin/sh\n')
    git(repo, 'init', '-q', '-b', 'main')
    git(repo, 'add', '.')
    git(repo, '-c', 'user.name=test', '-c', 'user.email=test@localhost', 'commit', '-q', '-m', 'init')
    writeFileSync(join(repo, 'notes.txt'), 'not committed\n')

    await page.setViewportSize({ width: 1600, height: 900 })
    await useLanguage(page, 'en')
    await page.goto('/')
    await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

    await page.keyboard.press('d')
    const desk = page.locator('.desk-panel')
    await desk.locator('.tabs button', { hasText: t['desk.newMission'] }).click()
    const form = desk.locator('form.new-mission')
    await form.getByPlaceholder(t['mission.fieldRepository']).fill(repo)

    const card = form.locator('.preflight')
    await expect(card.locator('.profile')).toContainText('Maven project (pom.xml, mvnw) on branch main')
    await expect(card).toContainText('1 file(s) not committed')
    await expect(card.locator('.proposal code')).toHaveText('./mvnw -q verify')
    await shot(page, 'preflight-card')

    await card.getByRole('button', { name: t['preflight.checksUse'] }).click()
    await expect(form.getByPlaceholder(t['results.checksPlaceholder'])).toHaveValue('./mvnw -q verify')
    await expect(card.locator('.proposal')).toHaveCount(0)
    await expect(form.getByRole('button', { name: t['mission.propose'] })).toBeEnabled()
    await page.keyboard.press('Escape')
  } finally {
    rmSync(repo, { recursive: true, force: true })
  }
})
