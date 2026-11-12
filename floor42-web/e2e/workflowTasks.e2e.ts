import { PANEL_ACTIONS, runFromMenu } from './bar'
import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

// workflows (ADR-0050, ADR-0052): the library on the left, the timeline on the right, edited in place.
const t = CATALOGS.en
const WORKFLOWS = PANEL_ACTIONS.find((a) => a.id === 'workflows')!


test('the Research template reads with its task, and a copy of it is saved with the task kept', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await runFromMenu(page, t, WORKFLOWS)
  const panel = page.locator('.workflows-panel')
  const library = panel.locator('.workflow-library')

  // A workflow of one researcher's task: shown with its slot, its runner and that nobody reads it.
  await library.getByRole('button', { name: t['workflows.tpl.research'] }).click()
  const slot = panel.locator('[data-slot="tasks"]')
  await expect(slot.locator('[data-task="research"] .tl-title')).toHaveText('Research')
  await expect(slot.locator('[data-task="research"]')).toContainText(t['workflows.task.runner.researcher'])
  await expect(slot.locator('[data-task="research"]')).toContainText(t['workflows.task.unreviewed'])
  await expect(panel.locator('[data-step="produce"]')).toHaveCount(0)
  await shot(page, 'workflows-tasks-template-1280')

  // This daemon writes schema 3: the copy saves, and the task is still there when read again.
  await panel.getByRole('button', { name: t['workflows.copy'] }).click()
  const name = `Tasked ${Date.now() % 100000}`
  await panel.getByLabel(t['workflows.tl.name']).fill(name)
  const save = panel.getByRole('button', { name: t['workflows.save'], exact: true })
  await expect(save).toBeEnabled()
  await save.click()
  await expect(library.getByRole('button', { name })).toHaveAttribute('aria-current', 'true')
  await expect(panel.locator('[data-slot="tasks"] [data-task="research"] .tl-title')).toHaveText('Research')
  await shot(page, 'workflows-tasks-saved-1280')
})

test('the director adds a task through the dialog to a copy of Review only, saves it and finds it in the library', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])
  await runFromMenu(page, t, WORKFLOWS)
  const panel = page.locator('.workflows-panel')
  const library = panel.locator('.workflow-library')

  await library.getByRole('button', { name: t['workflows.tpl.review-only'] }).click()
  await panel.getByRole('button', { name: t['workflows.copy'] }).click()
  const name = `Reviewed ${Date.now() % 100000}`
  await panel.getByLabel(t['workflows.tl.name']).fill(name)
  const slot = panel.locator('[data-slot="tasks"]')
  await slot.getByRole('button', { name: t['workflows.task.add'] }).click()
  const dialog = page.getByRole('dialog', { name: t['workflows.task.addTitle'] })
  await expect(dialog).toBeVisible()
  const apply = dialog.getByRole('button', { name: t['workflows.chk.apply'] })
  await expect(apply).toBeDisabled()
  await dialog.getByLabel(t['workflows.chk.title']).fill('Second reading')
  await dialog.getByLabel(t['workflows.chk.does']).fill('Read the deliverable again')
  await dialog.getByLabel(t['workflows.chk.why']).fill('Never push to ma\u200bin')
  await expect(dialog.locator('[data-problem="why"]')).toBeVisible()
  await expect(apply).toBeDisabled()
  await dialog.getByLabel(t['workflows.chk.why']).fill('A second pair of eyes')
  await dialog.getByLabel(t['workflows.chk.handsOver']).fill('Remarks, point by point')
  await expect(apply).toBeEnabled()
  await shot(page, 'workflows-task-dialog-1280')
  await apply.click()
  await expect(dialog).toHaveCount(0)
  await expect(slot.locator('[data-task="second-reading"] .tl-title')).toHaveText('Second reading')

  const save = panel.getByRole('button', { name: t['workflows.save'], exact: true })
  await expect(save).toBeEnabled()
  await save.click()
  await expect(library.getByRole('button', { name })).toHaveAttribute('aria-current', 'true')
  await expect(panel.locator('[data-slot="tasks"] [data-task="second-reading"] .tl-title')).toHaveText('Second reading')
  await shot(page, 'workflows-task-saved-1280')
})
