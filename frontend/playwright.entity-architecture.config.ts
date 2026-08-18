import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.ENTITY_ARCH_UI_BASE_URL?.trim() || 'http://localhost:3000';

export default defineConfig({
  testDir: './e2e/entity-architecture',
  outputDir: './test-results/entity-architecture/artifacts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 120_000,
  expect: { timeout: 30_000 },
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/entity-architecture/results.json' }],
    ['junit', { outputFile: 'test-results/entity-architecture/junit.xml' }]
  ],
  use: {
    ...devices['Desktop Edge'],
    baseURL,
    channel: 'msedge',
    headless: true,
    serviceWorkers: 'block',
    trace: 'on',
    screenshot: 'on',
    video: 'retain-on-failure'
  }
});
