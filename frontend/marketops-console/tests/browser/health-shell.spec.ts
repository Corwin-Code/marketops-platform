import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

const repositoryRoot = resolve(process.cwd(), '../..');
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

function compose(...arguments_: string[]): void {
  execFileSync('docker', [...composeArguments, ...arguments_], {
    cwd: repositoryRoot,
    stdio: 'pipe',
  });
}

test('the rendered console reads real backend metadata across the browser boundary', async ({
  page,
}) => {
  const metadataResponse = page.waitForResponse((response) =>
    response.url().endsWith('/api/v1/meta/status'),
  );

  await page.goto('/');

  const response = await metadataResponse;
  expect(response.ok()).toBe(true);
  expect(response.headers()['access-control-allow-origin']).toBe('http://127.0.0.1:5173');
  expect(response.headers()['access-control-expose-headers']).toContain('X-Correlation-ID');
  const sentCorrelationId = response.request().headers()['x-correlation-id'];
  const responseBody = (await response.json()) as { correlationId?: string };
  expect(sentCorrelationId).toBeTruthy();
  expect(response.headers()['x-correlation-id']).toBe(sentCorrelationId);
  expect(responseBody.correlationId).toBe(sentCorrelationId);

  await expect(page.getByRole('heading', { name: 'MarketOps Russia' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Platform state' })).toHaveAttribute(
    'data-state',
    'ready',
  );
  await expect(page.getByText('marketops-server')).toBeVisible();
  await expect(page.getByText('The platform is usable.')).toBeVisible();

  try {
    compose('stop', 'postgres');
    const degradedResponse = page.waitForResponse((candidate) =>
      candidate.url().endsWith('/api/v1/meta/status'),
    );
    await page.getByRole('button', { name: 'Check again' }).click();
    const degradedBody = (await (await degradedResponse).json()) as {
      database?: { status?: string };
    };
    expect(degradedBody.database?.status).toBe('DOWN');
    await expect(page.getByRole('region', { name: 'Platform state' })).toHaveAttribute(
      'data-state',
      'degraded',
    );
    await expect(page.getByText('The platform is not usable yet.')).toBeVisible();
  } finally {
    compose('up', '-d', '--wait', 'postgres');
  }
});
