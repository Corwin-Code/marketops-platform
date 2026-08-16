import { expect, test } from '@playwright/test';
import type { Page, Response } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { frontendPackageVersion } from '../../vite.constants';

const repositoryRoot = resolve(process.cwd(), '../..');
const sourceHead = execFileSync('git', ['rev-parse', 'HEAD'], {
  cwd: repositoryRoot,
  encoding: 'utf8',
}).trim();
const packageManifest: unknown = JSON.parse(
  readFileSync(resolve(process.cwd(), 'package.json'), 'utf8'),
);
const frontendVersion = frontendPackageVersion(packageManifest);
const composeProject = process.env.COMPOSE_PROJECT_NAME ?? 'marketops-local';
const composeArguments = [
  'compose',
  '--project-name',
  composeProject,
  '--env-file',
  resolve(repositoryRoot, '.env.local'),
  '-f',
  resolve(repositoryRoot, 'infra/compose/docker-compose.yml'),
];

interface StatusBody {
  readonly database?: { readonly status?: string };
  readonly correlationId?: string;
}

function compose(...arguments_: string[]): void {
  execFileSync('docker', [...composeArguments, ...arguments_], {
    cwd: repositoryRoot,
    stdio: 'pipe',
  });
}

async function waitForDatabaseStatus(
  page: Page,
  expectedStatus: string,
): Promise<{ response: Response; body: StatusBody }> {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const response = await page.waitForResponse(
      (candidate) => candidate.url().endsWith('/api/v1/meta/status'),
      { timeout: Math.max(1, deadline - Date.now()) },
    );
    const body = (await response.json()) as StatusBody;
    if (body.database?.status === expectedStatus) {
      return { response, body };
    }
  }
  throw new Error(`metadata did not report database status ${expectedStatus}`);
}

function expectCorrelation(response: Response, body: StatusBody): void {
  const sent = response.request().headers()['x-correlation-id'];
  expect(sent).toBeTruthy();
  expect(response.headers()['x-correlation-id']).toBe(sent);
  expect(body.correlationId).toBe(sent);
}

test('the built console recovers across a real database outage', async ({ page }) => {
  const initialResponse = page.waitForResponse((response) =>
    response.url().endsWith('/api/v1/meta/status'),
  );

  await page.goto('/');

  const response = await initialResponse;
  const responseBody = (await response.json()) as StatusBody;
  expect(response.ok()).toBe(true);
  expect(response.headers()['access-control-allow-origin']).toBe('http://127.0.0.1:4173');
  expect(response.headers()['access-control-expose-headers']).toContain('X-Correlation-ID');
  expectCorrelation(response, responseBody);

  await expect(page.getByRole('heading', { name: 'MarketOps Russia' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Platform state' })).toHaveAttribute(
    'data-state',
    'ready',
  );
  await expect(page.getByText('marketops-server')).toBeVisible();
  await expect(page.getByText('The platform is usable.')).toBeVisible();
  await expect(page.getByRole('contentinfo', { name: 'Console build' })).toContainText(
    `Console ${frontendVersion} (${sourceHead})`,
  );

  try {
    compose('stop', 'postgres');
    const degraded = await waitForDatabaseStatus(page, 'DOWN');
    expectCorrelation(degraded.response, degraded.body);
    await expect(page.getByRole('region', { name: 'Platform state' })).toHaveAttribute(
      'data-state',
      'degraded',
    );
    await expect(page.getByText('The platform is not usable yet.')).toBeVisible();

    compose('up', '-d', '--wait', 'postgres');
    const recovered = await waitForDatabaseStatus(page, 'UP');
    expectCorrelation(recovered.response, recovered.body);
    await expect(page.getByRole('region', { name: 'Platform state' })).toHaveAttribute(
      'data-state',
      'ready',
    );
    await expect(page.getByText('The platform is usable.')).toBeVisible();
  } finally {
    compose('up', '-d', '--wait', 'postgres');
  }
});
