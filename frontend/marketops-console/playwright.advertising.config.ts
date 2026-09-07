import { defineConfig } from '@playwright/test';
import { existsSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { resolveBrowserSourceIdentity } from './tests/browser/sourceIdentity.ts';

const isolatedConfig = process.env.MARKETOPS_AD_BROWSER_CONFIG;
if (
  isolatedConfig === undefined ||
  !isAbsolute(isolatedConfig) ||
  !existsSync(isolatedConfig) ||
  !/^\/(?:private\/)?tmp\/[A-Za-z0-9_./-]+\.properties$/u.test(isolatedConfig)
) {
  throw new Error(
    'MARKETOPS_AD_BROWSER_CONFIG must explicitly name the isolated /tmp .properties file',
  );
}
const sourceHead = resolveBrowserSourceIdentity(resolve(process.cwd(), '../..'));
/** This suite never invokes make backend-browser-run or reads the repository .env.local. */
export default defineConfig({
  testDir: './tests/advertising-browser',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 20_000 },
  outputDir: 'test-results/advertising',
  reporter: [['list'], ['html', { outputFolder: 'playwright-report/advertising', open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    browserName: 'chromium',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  webServer: [
    {
      command:
        'cd ../../backend/marketops-server && ./mvnw -B -ntp spring-boot:test-run@advertising-browser-fixture',
      env: {
        MARKETOPS_BROWSER_FIXTURE: 'ISOLATED_SYNTHETIC_DATABASE',
        MARKETOPS_AD_BROWSER_CONFIG: isolatedConfig,
        SPRING_CONFIG_IMPORT: `file:${isolatedConfig}`,
      },
      url: 'http://127.0.0.1:8082/advertising-fixture',
      stdout: 'pipe',
      reuseExistingServer: false,
      timeout: 180_000,
    },
    {
      command: 'npm run build && npm run preview -- --host 127.0.0.1 --port 4173 --strictPort',
      env: {
        MARKETOPS_BROWSER_ISOLATION: 'ISOLATED_SYNTHETIC_DATABASE',
        MARKETOPS_BUILD_COMMIT: sourceHead,
        VITE_MARKETOPS_API_BASE_URL: 'http://127.0.0.1:8080',
        VITE_MARKETOPS_ENVIRONMENT: 'isolated-advertising-browser',
        VITE_MARKETOPS_OIDC_AUTHORIZATION_ENDPOINT: 'https://id.example.test/authorize',
        VITE_MARKETOPS_OIDC_TOKEN_ENDPOINT: 'https://id.example.test/token',
        VITE_MARKETOPS_OIDC_CLIENT_ID: 'marketops-console',
        VITE_MARKETOPS_OIDC_AUDIENCE: 'marketops',
        VITE_MARKETOPS_STORE_ID: '00000000-0000-0000-0000-0000000000d3',
      },
      url: 'http://127.0.0.1:4173',
      reuseExistingServer: false,
      timeout: 60_000,
    },
  ],
});
