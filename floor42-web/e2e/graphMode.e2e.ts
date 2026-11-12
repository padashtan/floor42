import { CATALOGS, expect, shot, test, useLanguage } from './fixtures'

const t = CATALOGS.en

interface Mission {
  id: string
  title: string
  status: string
  tickets: {
    id: string
    status: string
    kind: string
    toRecheck: boolean
    dependsOn: string[]
    node: { risk: string; tier: string; tests: string[]; runs: { outcome: string }[] } | null
  }[]
  questions: { id: string; status: string }[]
  workflow: { workflow: { mode: string } | null }
}

/**
 * Journey: a graph mission (ADR-0062). The director chooses "Graph" on the new mission form, the fake planner draws two
 * nodes, the plan's approval shows the graph with the risk and the tier of each node (the director changes one risk),
 * and the board draws it too. Then the graph is extended by one planner session, and a node reopened after its
 * dependent was done marks that dependent "to recheck" (increment 2).
 */
test('the director creates a graph mission and sees the graph at the approval and on the board', async ({ page, daemon }) => {
  test.setTimeout(180_000)
  page.on('dialog', (d) => void d.accept())
  const headers = { 'X-Floor42-Request': '1', Origin: daemon.url }
  const repo = daemon.gitRepository()
  await page.setViewportSize({ width: 1600, height: 900 })
  await useLanguage(page, 'en')
  await page.goto('/')
  await expect(page.locator('.badge.ready')).toHaveText(t['top.liveReady'])

  await page.keyboard.press('d')
  const desk = page.locator('.desk-panel')
  await expect(desk).toBeVisible()
  await desk.locator('.tabs button', { hasText: t['desk.newMission'] }).click()
  const form = desk.locator('form.new-mission')
  await form.getByPlaceholder(t['mission.fieldTitle']).fill('Graph journey')
  // [fake:graph-plan] makes the fake planner write TEST, RISK and TIER lines and a second node after the first.
  await form.getByPlaceholder(t['mission.fieldObjective']).fill('Describe the project [fake:graph-plan]')
  await form.getByPlaceholder(t['mission.fieldRepository']).fill(repo)

  // The graph needs the planner: without it the choice is closed and says why.
  const planner = form.locator('label', { hasText: t['mission.letGalenPlan'] }).locator('input')
  await planner.uncheck()
  const graph = form.locator('fieldset.team-mode').getByRole('radio').nth(1)
  await expect(graph).toBeDisabled()
  await expect(form.locator('fieldset.team-mode')).toContainText(t['mission.mode.needsPlan'])
  await planner.check()
  await expect(graph).toBeEnabled()
  await graph.check()
  await shot(page, 'graph-mode-01-new')
  await form.getByRole('button', { name: t['mission.propose'], exact: true }).click()

  let id = ''
  const mission = async (): Promise<Mission> => (await page.request.get(`/api/missions/${id}`)).json()
  await expect
    .poll(async () => {
      const all: Mission[] = await (await page.request.get('/api/missions')).json()
      id = all.find((x) => x.title === 'Graph journey')?.id ?? ''
      return id && (await mission()).status
    })
    .toBe('AWAITING_APPROVAL')
  expect((await mission()).workflow.workflow?.mode).toBe('graph')

  // The planner's plan is approved with a cap; the nodes are then proposed, with the graph drawn beside the actions.
  await desk.locator('.tabs button', { hasText: t['desk.mission'] }).first().click()
  const cap = desk.locator('.plan-cap')
  await cap.locator('input[inputmode="decimal"]').fill('1')
  await cap.locator('button.action').click()
  await expect.poll(async () => (await mission()).tickets.filter((x) => x.kind !== 'PLANNING').length).toBe(2)

  // The nodes are proposed under "To decide", the graph drawn beside the approval actions.
  await desk.locator('.tabs button', { hasText: t['desk.toDecide'] }).click()
  const proposed = desk.locator('.proposed')
  await expect(proposed.locator('.graph-node')).toHaveCount(2)
  await expect(proposed.locator('.graph-edge')).toHaveCount(1)
  await expect(proposed.locator('.proposal-marks').first()).toContainText(t['graph.risk.low'])
  await expect(proposed.locator('.proposal-marks').nth(1)).toContainText(t['graph.risk.high'])
  await expect(proposed).toContainText('Test: cat SUMMARY.md => it prints the summary')
  // The director's risk is the last word: the first node, low for the planner, is made high before the approval.
  await proposed.locator('.risk-flip').first().click()
  await expect(proposed.locator('.proposal-marks').first()).toContainText(t['graph.risk.high'])
  await shot(page, 'graph-mode-02-approval')

  // Approving the selection puts the nodes on the board, still drawn as a graph.
  await proposed.getByRole('button', { name: /Approve selected/ }).click()
  await expect.poll(async () => (await mission()).tickets.filter((x) => x.status === 'PROPOSED').length).toBe(0)
  const nodes = (await mission()).tickets.filter((x) => x.kind !== 'PLANNING')
  expect(nodes.map((x) => x.node?.risk), 'the director changed the first, the planner kept the second').toEqual(['high', 'high'])
  expect(nodes[0].node?.tests).toEqual(['cat SUMMARY.md'])
  await page.keyboard.press('b')
  const board = page.locator('.board-panel')
  await expect(board).toBeVisible()
  await expect(board.locator('.task-graph .graph-node')).toHaveCount(2)
  await expect(board.locator('.task-graph .graph-edge')).toHaveCount(1)
  await shot(page, 'graph-mode-03-board')

  // Extend the graph: paid, so said before the click. One planner session proposes two nodes, the first after an
  // existing one; they wait for the approval and the nodes already there are not touched.
  const extend = board.locator('form.extend-graph')
  await expect(extend).toContainText(t['graph.extend.hint'])
  await extend.getByPlaceholder(t['graph.extend.goal']).fill('Add the release notes')
  await extend.getByRole('button', { name: t['graph.extend.go'] }).click()
  await expect.poll(async () => (await mission()).tickets.filter((x) => x.status === 'PROPOSED').length).toBe(2)
  const added = (await mission()).tickets.filter((x) => x.status === 'PROPOSED')
  expect(added[0].dependsOn, 'after a node that was already there').toEqual([nodes[0].id])
  expect(added[1].dependsOn).toEqual([added[0].id])
  await expect(board.locator('.task-graph .graph-node')).toHaveCount(4)
  await shot(page, 'graph-mode-04-extended')

  // Both first nodes get done (the director allows what the developer asks), then the first is reopened: the node
  // that was done after it is marked to recheck, and nothing reopens it.
  await expect
    .poll(
      async () => {
        const m = await mission()
        for (const q of m.questions.filter((x) => x.status === 'OPEN')) {
          await page.request.post(`/api/questions/${q.id}/answer`, { headers, data: { answer: 'ALLOW' } })
        }
        return nodes.every((n) => m.tickets.find((x) => x.id === n.id)?.status === 'DONE')
      },
      { timeout: 120_000, intervals: [1_000] },
    )
    .toBe(true)
  const sandbox = (await mission()).tickets.find((x) => x.id === nodes[0].id)!.node!
  expect(sandbox.runs.map((r) => r.outcome), 'the sandbox is off: the test is said not run, never run outside it').toEqual(['not run'])
  const directive = await page.request.post(`/api/missions/${id}/workshop/directive`, {
    headers,
    data: { ticket: nodes[0].id, directive: 'The interface changed.' },
  })
  expect(directive.status()).toBe(200)
  await expect.poll(async () => (await mission()).tickets.find((x) => x.id === nodes[1].id)?.toRecheck).toBe(true)
  const recheck = board.locator('.task-graph .graph-node', { hasText: nodes[1].id })
  await expect(recheck.locator('.recheck')).toHaveText(t['graph.toRecheck'])
  expect((await mission()).tickets.find((x) => x.id === nodes[1].id)?.status, 'never reopened by itself').toBe('DONE')
  await recheck.click()
  await expect(page.locator('.ticket-detail .recheck')).toContainText(t['graph.toRecheckHint'])
  await shot(page, 'graph-mode-05-recheck')
})
