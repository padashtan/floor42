import { defineConfig } from '@playwright/test'

/**
 * End-to-end browser tests (npm run e2e): the installed Chrome (or Edge with FLOOR42_E2E_BROWSER=msedge) drives a
 * Floor42 daemon of their own, started from the packaged build with the fake Claude Code. No browser is downloaded.
 * On Linux, FLOOR42_E2E_EXECUTABLE names an installed Chromium instead (e.g. /usr/bin/chromium).
 * Not part of ./mvnw verify: the gate before a release (CLAUDE.md).
 */
const executablePath = process.env.FLOOR42_E2E_EXECUTABLE
const channel = executablePath ? undefined : (process.env.FLOOR42_E2E_BROWSER ?? 'chrome')

// Headless Chromium on WSL renders WebGL in software (SwiftShader, ~10 fps on the office scene) and the journeys time out.
// FLOOR42_E2E_GPU=true runs it headed on WSLg with the Windows GPU (Mesa d3d12, through ANGLE EGL): the window opens off
// screen and minimized so it does not take the desktop, the run is about twice as fast. Needs DISPLAY (WSLg sets it).
const gpu = process.env.FLOOR42_E2E_GPU === 'true'
const GPU_ARGS = ['--window-position=-32000,-32000', '--start-minimized', '--use-gl=angle', '--use-angle=gl-egl', '--ignore-gpu-blocklist', '--disable-gpu-sandbox']

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  outputDir: './e2e-results/artifacts',
  // One daemon per worker (a data folder of its own), and the journeys of a file share that company. Two workers cut
  // the run from 7.4 to 4.7 minutes, 40 of 40 green; FLOOR42_E2E_WORKERS=1 gives the single daemon, in file order, back.
  workers: Number(process.env.FLOOR42_E2E_WORKERS ?? 2),
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { outputFolder: './e2e-results/report', open: 'never' }]],
  use: {
    channel,
    launchOptions: {
      ...(executablePath ? { executablePath } : {}),
      ...(gpu ? { args: GPU_ARGS, env: { ...process.env, GALLIUM_DRIVER: 'd3d12', LD_LIBRARY_PATH: '/usr/lib/wsl/lib' } } : {}),
    },
    headless: process.env.FLOOR42_E2E_HEADED !== 'true' && !gpu,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
