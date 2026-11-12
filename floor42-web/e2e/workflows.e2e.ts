import { readFileSync } from 'node:fs'
import { PANEL_ACTIONS, runFromMenu } from './bar'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

// workflows (ADR-0050, ADR-0052): the library on the left, the timeline on the right, edited in place.
const t = CATALOGS.en
const WORKFLOWS = PANEL_ACTIONS.find((a) => a.id === 'workflows')!

test('the director edits a workflow on the timeline, sees problems under the rows, and saving lists it', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await runFromMenu(page, t, WORKFLOWS)
  const panel = page.locator('.workflows-panel')
  await expect(panel).toBeVisible()
  const library = panel.locator('.workflow-library')
  await expect(library.getByRole('button', { name: t['workflows.builtIn'] })).toHaveAttribute('aria-current', 'true')
  // Read only: no input on the timeline, the fixed steps carry their reason.
  await expect(panel.locator('.timeline input')).toHaveCount(0)
  await panel.locator('[data-step="approval"] .lock').focus()
  await expect(panel.locator('[data-step="approval"] .lock-why')).toContainText(t['workflows.why.approval'])
  // One model for both modes: production, then its own locked review row; the lock reason never covers another row.
  await expect(panel.locator('[data-step="produce"] .tl-title')).toHaveText(t['workflows.step.produce'])
  await expect(panel.locator('[data-step="review"]')).toHaveCount(1)
  await panel.locator('[data-step="review"] .lock').focus()
  await expect(panel.locator('[data-step="review"] .lock-why')).toBeVisible()
  await shot(page, 'workflows-read-1280')

  await panel.getByRole('button', { name: t['workflows.copy'] }).click()
  await expect(panel.locator('.tl-head h3')).toHaveText(t['workflows.tl.newFromBuiltIn'])
  await expect(panel.locator('.workflow-detail > .hint')).toHaveCount(0)
  const step = (label: string) => panel.locator('.tl-row', { hasText: label }).locator('input[type="checkbox"]')
  await expect(panel.locator('[data-step="approval"] input[type="checkbox"]')).toHaveCount(0)
  const save = panel.getByRole('button', { name: t['workflows.save'], exact: true })
  // No name yet: Save waits, and its tooltip gives the first problem.
  await expect(save).toBeDisabled()
  await expect(save).toHaveAttribute('title', t['workflows.nameMissing'])
  // Nothing to run: the problem stands under the production row.
  await step(t['workflows.step.produce']).uncheck()
  await step(t['workflows.step.committee']).uncheck()
  await expect(panel.locator('[data-step="produce"] .hint.warn')).toHaveText(t['workflows.problemNothing'])
  await shot(page, 'workflows-edit-problem-1280')
  await step(t['workflows.step.produce']).check()
  await step(t['workflows.step.retro']).uncheck()
  await panel.locator('.tl-max input').fill('3')
  await panel.locator('.tl-max input').dispatchEvent('change')
  await panel.getByLabel(t['workflows.tl.name']).fill('Light read')
  await expect(save).toBeEnabled()
  await shot(page, 'workflows-edit-1280')

  // Leaving with unsaved changes asks first; staying keeps the draft.
  await library.getByRole('button', { name: t['workflows.builtIn'] }).click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toContainText(t['workflows.leaveConfirm'])
  await dialog.getByRole('button', { name: t['confirm.cancel'] }).click()
  await expect(panel.getByLabel(t['workflows.tl.name'])).toHaveValue('Light read')

  await save.click()
  await expect(library.getByRole('button', { name: 'Light read' })).toHaveAttribute('aria-current', 'true')
  await expect(panel.locator('.workflow-detail .hint').first()).toContainText('Plan (3 tickets at most) → Approval → Production → Review → Acceptance')
  await expect(panel.locator('.timeline input')).toHaveCount(0)

  await page.setViewportSize({ width: 600, height: 900 })
  await shot(page, 'workflows-read-600')
  await panel.getByRole('button', { name: t['workflows.edit'] }).click()
  // The panel scrolls and its footer stays in view: the last row is reachable, Save never leaves the screen.
  await panel.locator('[data-step="retro"]').scrollIntoViewIfNeeded()
  await expect(panel.locator('[data-step="retro"]')).toBeInViewport()
  await expect(panel.getByRole('button', { name: t['workflows.saveReplace'] })).toBeInViewport()
  await shot(page, 'workflows-edit-600')
})

test('the French panel writes the import problems and the timeline editor problem in French', async ({ page }) => {
  const fr = CATALOGS.fr
  await page.setViewportSize({ width: 1280, height: 900 })
  await useLanguage(page, 'fr')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(fr['top.liveReady'])
  await runFromMenu(page, fr, WORKFLOWS)
  const panel = page.locator('.workflows-panel')
  await expect(panel.locator('.workflow-library').getByRole('button', { name: fr['workflows.tpl.decision'] })).toBeVisible()
  const bad = { format: 'floor42-workflow', formatVersion: 1, schemaVersion: 7, document: {} }
  await panel.locator('input.lib-file').setInputFiles({ name: 'new.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(bad)) })
  await expect(panel.locator('.imp-problems li[data-code="IMPORT_SCHEMA_NEWER"]')).toHaveText(fr['workflows.imp.IMPORT_SCHEMA_NEWER'].replace('{subject}', '7'))
  await shot(page, 'workflows-library-bad-file-fr-1280')
  await panel.getByRole('button', { name: fr['workflows.copy'] }).click()
  const step = (label: string) => panel.locator('.tl-row', { hasText: label }).locator('input[type="checkbox"]')
  await step(fr['workflows.step.produce']).uncheck()
  await step(fr['workflows.step.committee']).uncheck()
  await expect(panel.locator('[data-step="produce"] .hint.warn')).toHaveText(fr['workflows.problemNothing'])
  await expect(panel.getByRole('button', { name: fr['workflows.save'], exact: true })).toHaveAttribute('title', fr['workflows.nameMissing'])
  await shot(page, 'workflows-edit-problem-fr-1280')
})

test('the library: start from a template, derive versions and restore, export, import, and a bad file by code', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await runFromMenu(page, t, WORKFLOWS)
  const panel = page.locator('.workflows-panel')
  const library = panel.locator('.workflow-library')
  const row = (label: string) => panel.locator('.tl-row', { hasText: label })
  const name = `Library ${Date.now() % 100000}`

  // A template reads like any workflow; "Start from it" opens a copy to name.
  await library.getByRole('button', { name: t['workflows.tpl.light-change'] }).click()
  await expect(panel.locator('.tl-head h3')).toHaveText(t['workflows.tpl.light-change'])
  await expect(panel.locator('.workflow-detail .hint').first()).toContainText(t['workflows.tpl.desc.light-change'])
  await expect(row(t['workflows.step.committee'])).toHaveCount(0)
  await shot(page, 'workflows-library-template-1280')
  await panel.getByRole('button', { name: t['workflows.copy'] }).click()
  await panel.getByLabel(t['workflows.tl.name']).fill(name)
  await panel.getByRole('button', { name: t['workflows.save'], exact: true }).click()
  await expect(library.getByRole('button', { name })).toHaveAttribute('aria-current', 'true')

  // Version 1; an edit makes version 2; "Restore" saves version 1 again as version 3.
  const versions = panel.locator('.wf-versions')
  await versions.locator('summary').click()
  await expect(versions.locator('summary')).toHaveText(t['workflows.versions'].replace('{count}', '1'))
  await panel.getByRole('button', { name: t['workflows.edit'] }).click()
  await row(t['workflows.step.committee']).locator('input[type="checkbox"]').check()
  await panel.getByRole('button', { name: t['workflows.saveReplace'] }).click()
  await versions.locator('summary').click()
  await expect(versions.locator('li')).toHaveCount(2)
  await expect(versions.locator('[data-version="2"]')).toContainText(t['workflows.version.current'])
  await expect(row(t['workflows.step.committee'])).toHaveCount(1)
  await versions.locator('[data-version="1"]').getByRole('button', { name: t['workflows.version.restore'] }).click()
  await expect(versions.locator('li')).toHaveCount(3)
  await expect(versions.locator('[data-version="3"]')).toContainText(t['workflows.version.current'])
  await expect(row(t['workflows.step.committee'])).toHaveCount(0)
  await shot(page, 'workflows-library-versions-1280')

  // Export: the file is named after the workflow and holds the document as saved.
  const download = page.waitForEvent('download')
  await panel.getByRole('button', { name: t['workflows.export'] }).click()
  const file = await download
  expect(file.suggestedFilename()).toBe(`${name}.floor42-workflow.json`)
  const text = readFileSync((await file.path())!, 'utf8')
  const exported = JSON.parse(text)
  expect(exported).toMatchObject({ format: 'floor42-workflow', formatVersion: 1, schemaVersion: 1 })
  expect(exported.document.name).toBe(name)

  // Import it back: it opens unsaved, the name is taken, so Save waits until it is renamed.
  const choose = panel.locator('input.lib-file')
  await choose.setInputFiles({ name: 'back.json', mimeType: 'application/json', buffer: Buffer.from(text) })
  await expect(panel.getByText(t['workflows.imp.banner'])).toBeVisible()
  await expect(panel.getByLabel(t['workflows.tl.name'])).toHaveValue(name)
  const save = panel.getByRole('button', { name: t['workflows.save'], exact: true })
  await expect(save).toBeDisabled()
  await panel.getByLabel(t['workflows.tl.name']).fill(`${name} imported`)
  await expect(save).toBeEnabled()
  await shot(page, 'workflows-library-imported-1280')
  await save.click()
  await expect(library.getByRole('button', { name: `${name} imported` })).toHaveAttribute('aria-current', 'true')

  // A bad file is refused by code, every problem at once, and nothing is saved.
  const bad = {
    format: 'floor42-workflow',
    formatVersion: 1,
    schemaVersion: 1,
    document: { name: 'Bad', steps: [{ step: 'approval', colour: 'red' }, { step: 'ship' }, { step: 'accept' }] },
  }
  await choose.setInputFiles({ name: 'bad.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(bad)) })
  const problems = panel.locator('.imp-problems')
  await expect(problems.locator('li')).toHaveCount(2)
  await expect(problems.locator('li[data-code="IMPORT_UNKNOWN_KEY"]')).toHaveText(t['workflows.imp.IMPORT_UNKNOWN_KEY'].replace('{subject}', 'colour'))
  await expect(problems.locator('li[data-code="IMPORT_STEP_UNKNOWN"]')).toHaveText(t['workflows.imp.IMPORT_STEP_UNKNOWN'].replace('{subject}', 'ship'))
  await shot(page, 'workflows-library-bad-file-1280')
  await choose.setInputFiles({ name: 'big.json', mimeType: 'application/json', buffer: Buffer.alloc(70_000, 120) })
  await expect(problems.locator('li')).toHaveCount(1)
  await expect(problems.locator('li[data-code="IMPORT_TOO_LARGE"]')).toHaveText(t['workflows.imp.IMPORT_TOO_LARGE'])
  await choose.setInputFiles({ name: 'text.json', mimeType: 'application/json', buffer: Buffer.from('not json') })
  await expect(problems.locator('li[data-code="IMPORT_NOT_JSON"]')).toHaveCount(1)
  await expect(library.getByRole('button', { name: 'Bad' })).toHaveCount(0)
})

test('the director defines a check in its dialog and saves the workflow with it', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await runFromMenu(page, t, WORKFLOWS)
  const panel = page.locator('.workflows-panel')
  const slot = panel.locator('[data-slot="checks"]')

  // The template reads with its advisory check after the review.
  await panel.locator('.workflow-library').getByRole('button', { name: t['workflows.tpl.document-production'] }).click()
  await expect(slot.locator('[data-check="proofreading"] .tl-title')).toHaveText('Proofreading')
  await expect(slot.locator('[data-check="proofreading"]')).toContainText(t['workflows.chk.advisoryShort'])
  await shot(page, 'workflows-checks-template-1280')

  await panel.getByRole('button', { name: t['workflows.copy'] }).click()
  const name = `Checked ${Date.now() % 100000}`
  await panel.getByLabel(t['workflows.tl.name']).fill(name)
  // Production stays while a check reads it.
  await expect(panel.locator('[data-step="produce"] input[type="checkbox"]')).toBeDisabled()

  await slot.getByRole('button', { name: t['workflows.chk.add'] }).click()
  const dialog = page.getByRole('dialog', { name: t['workflows.chk.addTitle'] })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText(t['workflows.chk.permissions'])
  const apply = dialog.getByRole('button', { name: t['workflows.chk.apply'] })
  await dialog.getByLabel(t['workflows.chk.title']).fill('Security check')
  await dialog.getByLabel(t['workflows.chk.does']).fill('Look for secrets in the changed files')
  // A zero-width space the screen does not show: refused under its field, with its position.
  await dialog.getByLabel(t['workflows.chk.why']).fill('Never push to ma​in')
  await expect(dialog.locator('[data-problem="why"]')).toHaveText(t['workflows.chk.err.STEP_TEXT_HIDDEN_CHARACTER'].replace('{position}', '17'))
  await expect(apply).toBeDisabled()
  // A braille blank is a symbol the screen draws as nothing: refused from the daemon's table, with its position.
  await dialog.getByLabel(t['workflows.chk.why']).fill('Braille\u2800blank')
  await expect(dialog.locator('[data-problem="why"]')).toHaveText(t['workflows.chk.err.STEP_TEXT_HIDDEN_CHARACTER'].replace('{position}', '8'))
  await expect(apply).toBeDisabled()
  await dialog.getByLabel(t['workflows.chk.why']).fill('Our customer contract requires it')
  await dialog.getByLabel(t['workflows.chk.handsOver']).fill('The findings, file by file')
  await expect(apply).toBeEnabled()
  await shot(page, 'workflows-checks-dialog-1280')
  await apply.click()
  await expect(dialog).toHaveCount(0)

  // Two checks in their slot, moved within it only.
  const titles = slot.locator('.tl-check .tl-title')
  await expect(titles).toHaveText(['Proofreading', 'Security check'])
  await slot.getByRole('button', { name: t['workflows.chk.up'].replace('{title}', 'Security check') }).click()
  await expect(titles).toHaveText(['Security check', 'Proofreading'])
  await expect(slot.getByRole('button', { name: t['workflows.chk.up'].replace('{title}', 'Security check') })).toBeDisabled()

  // This daemon writes schema 2: Save is open with checks.
  const save = panel.getByRole('button', { name: t['workflows.save'], exact: true })
  await expect(save).toBeEnabled()
  await shot(page, 'workflows-checks-edit-1280')
  await save.click()
  await expect(panel.locator('.workflow-library').getByRole('button', { name })).toBeVisible()
  await panel.locator('.workflow-library').getByRole('button', { name }).click()
  await expect(panel.locator('[data-slot="checks"] .tl-check .tl-title')).toHaveText(['Security check', 'Proofreading'])
})
