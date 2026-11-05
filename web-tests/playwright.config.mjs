import { defineConfig } from '@playwright/test';

const port = process.env.TGB_DEV_PORT || '8765';

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.mjs',
  globalSetup: './global-setup.mjs',
  // Gradle is slow to start the dev server on small machines.
  globalTimeout: 15 * 60 * 1000,
  timeout: 60 * 1000,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    storageState: '.auth/state.json',
    browserName: 'chromium',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'window', use: { viewport: { width: 1100, height: 750 } } },
    { name: 'phone', use: { viewport: { width: 390, height: 844 } } },
    // A full-HD desktop for the desktop layout (Task 10a); the other specs run at the two sizes above.
    { name: 'wide', testMatch: 'desktop-layout.spec.mjs', use: { viewport: { width: 1920, height: 1040 } } },
  ],
});
