import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  use: {
    ...devices['Pixel 7'],
    browserName: 'chromium',
    channel: process.env.CI ? undefined : 'chrome',
    baseURL: 'http://127.0.0.1:5178',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure'
  },
  webServer: { command: 'npm run dev', url: 'http://127.0.0.1:5178', reuseExistingServer: true }
})
