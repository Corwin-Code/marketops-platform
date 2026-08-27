import { defineConfig } from '@playwright/test';
import { resolve } from 'node:path';
import { resolveBrowserSourceIdentity } from './tests/browser/sourceIdentity.ts';

const repositoryRoot = resolve(process.cwd(), '../..');
const sourceHead = resolveBrowserSourceIdentity(repositoryRoot);

/** Browser verification starts the real local backend and Vite console. */
export default defineConfig({
  testDir: './tests/browser',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 20_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    browserName: 'chromium',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  webServer: [
    {
      command: 'make -C ../.. backend-run',
      url: 'http://127.0.0.1:8080/actuator/health/readiness',
      reuseExistingServer: false,
      timeout: 180_000,
    },
    {
      command: 'npm run build && npm run preview -- --host 127.0.0.1 --port 4173 --strictPort',
      // The operating console only offers sign-in where an identity provider is
      // configured, so the browser suite builds it as a deployment that has
      // one. The provider itself is answered by the test through the browser's
      // network layer; no external system is contacted and nothing here is
      // evidence about one.
      env: {
        MARKETOPS_BUILD_COMMIT: sourceHead,
        VITE_MARKETOPS_OIDC_AUTHORIZATION_ENDPOINT: 'https://id.example.test/authorize',
        VITE_MARKETOPS_OIDC_TOKEN_ENDPOINT: 'https://id.example.test/token',
        VITE_MARKETOPS_OIDC_CLIENT_ID: 'marketops-console',
        VITE_MARKETOPS_OIDC_AUDIENCE: 'marketops',
        VITE_MARKETOPS_STORE_ID: '00000000-0000-0000-0000-0000000000d1',
      },
      url: 'http://127.0.0.1:4173',
      reuseExistingServer: false,
      timeout: 60_000,
    },
  ],
});
