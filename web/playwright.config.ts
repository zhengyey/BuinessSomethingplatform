import { defineConfig, devices } from '@playwright/test'

const baseURL = `http://127.0.0.1:${process.env.LABREVIEW_WEB_PORT ?? 5173}`

export default defineConfig({
  testDir: './tests/e2e',
  use: {
    ...devices['Desktop Chrome'],
    baseURL,
    channel: process.env.LABREVIEW_BROWSER_CHANNEL || undefined,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run dev',
    url: baseURL,
    reuseExistingServer: false,
  },
})
