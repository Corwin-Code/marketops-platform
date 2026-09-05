import { expect, test } from '@playwright/test';

/** Console responses are real; one request is aborted to exercise outage and recovery. */
test('TC-BROWSER-013 authenticated evidence, approval, command and readback journey', async ({
  page,
}) => {
  const response = await page.request.get('http://127.0.0.1:8082/fixture');
  expect(response.ok()).toBe(true);
  const fixture = (await response.json()) as {
    accessToken: string;
    storeId: string;
    subjectId: string;
    recommendationId: string;
    provenanceId: string;
  };
  await page.route('https://id.example.test/authorize*', async (target) => {
    const request = new URL(target.request().url());
    expect(request.searchParams.get('code_challenge_method')).toBe('S256');
    expect(request.searchParams.get('code_challenge')).toBeTruthy();
    await target.fulfill({
      status: 302,
      headers: {
        location: `${request.searchParams.get('redirect_uri') ?? ''}?code=synthetic-code&state=${encodeURIComponent(request.searchParams.get('state') ?? '')}`,
      },
      body: '',
    });
  });
  await page.route('https://id.example.test/token', async (target) => {
    expect(target.request().postData()).toContain('code_verifier=');
    await target.fulfill({
      status: 200,
      headers: {
        'content-type': 'application/json',
        'access-control-allow-origin': 'http://127.0.0.1:4173',
      },
      body: JSON.stringify({ access_token: fixture.accessToken, expires_in: 600 }),
    });
  });
  const queuePath = `http://127.0.0.1:8080/api/v1/console/diagnosis/stores/${fixture.storeId}/queue*`;
  await page.route(queuePath, (target) => target.abort('failed'));
  await page.goto('/');
  await page.getByRole('button', { name: 'Continue to sign in' }).click();
  const queueFailure = page
    .getByLabel('Priority queue')
    .getByRole('alert')
    .filter({ hasText: /^The platform did not answer\. Nothing was changed\.$/u });
  await expect(queueFailure).toHaveCount(1);
  await expect(queueFailure).toHaveText('The platform did not answer. Nothing was changed.');
  await expect(page.getByRole('button', { name: fixture.subjectId, exact: true })).toHaveCount(0);
  await page.unroute(queuePath);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.getByRole('button', { name: 'Continue to sign in' }).click();
  await expect(page.getByLabel('Priority queue')).toBeVisible();
  await page.getByRole('button', { name: 'Prepare export' }).click();
  const downloadButton = page.getByRole('button', { name: 'Download verified export' });
  await expect(downloadButton).toBeEnabled();
  const downloaded = page.waitForEvent('download');
  await downloadButton.click();
  const download = await downloaded;
  expect(await download.failure()).toBeNull();
  expect(download.suggestedFilename()).toMatch(/\.ndjson$/);
  await page.getByRole('button', { name: fixture.subjectId, exact: true }).click();
  await expect(page.getByLabel('Subject diagnosis')).toBeVisible();
  await page
    .getByRole('button', { name: 'View evidence for OBSERVED_SELLING_PRICE', exact: true })
    .click();
  await page
    .getByRole('button', { name: `View source ${fixture.provenanceId}`, exact: true })
    .click();
  await expect(page.getByLabel('Source provenance')).toContainText('MANUAL_ENTRY');
  await expect(page.getByLabel('Source provenance')).toContainText('No stored source bytes');
  await page
    .getByRole('button', { name: `Review recommendation ${fixture.recommendationId}`, exact: true })
    .click();
  await expect(page.getByTestId('recommendation-state')).toHaveText('READY_FOR_REVIEW');
  await expect(page.getByRole('button', { name: 'Approve this change' })).toBeDisabled();
  await page.getByRole('button', { name: 'Check what this would do' }).click();
  await expect(page.getByTestId('guardrail-verdict')).toContainText('Guardrails pass');
  await page.getByLabel('Why are you deciding this?').fill('Synthetic browser evidence reviewed');
  await page.getByRole('button', { name: 'Approve this change' }).click();
  await expect(page.getByLabel('Command timeline')).toBeVisible();
  await expect(page.getByTestId('command-state')).toContainText('No call has been made');
  await expect(page.getByTestId('no-readback')).toBeVisible();
  // Advance only this synthetic command through the actual worker and immutable custody.
  const advance = await page.request.post('http://127.0.0.1:8082/advance', {
    headers: { 'X-Fixture-Driver': 'browser-test' },
  });
  expect(advance.ok()).toBe(true);
  await page.getByRole('button', { name: 'Refresh command' }).click();
  await expect(page.getByTestId('command-state')).toContainText(
    'A readback observed the intended price',
  );
  await expect(page.getByLabel('Calls made').locator('li')).toHaveCount(2);
  await expect(page.getByLabel('What the marketplace holds')).toContainText(
    'this is the price that was intended',
  );
  expect(
    (
      await page.request.get(
        'http://127.0.0.1:8080/api/v1/console/workflow/stores/00000000-0000-0000-0000-000000000099/recommendations',
        {
          headers: { Authorization: `Bearer ${fixture.accessToken}` },
        },
      )
    ).status(),
  ).toBe(403);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByLabel('Command timeline')).toHaveCount(0);
  expect(
    await page.evaluate(() => [...Object.keys(localStorage), ...Object.keys(sessionStorage)]),
  ).not.toContain('access_token');
  // Recover from a new login using only the existing recommendation/command route.
  await page.getByRole('button', { name: 'Continue to sign in' }).click();
  await page.getByRole('button', { name: fixture.subjectId, exact: true }).click();
  await page
    .getByRole('button', { name: `Review recommendation ${fixture.recommendationId}`, exact: true })
    .click();
  await page.getByRole('button', { name: 'Open existing command' }).click();
  await expect(page.getByTestId('command-state')).toContainText(
    'A readback observed the intended price',
  );
  await expect(page.getByLabel('Calls made').locator('li')).toHaveCount(2);
  await page.getByRole('button', { name: 'Sign out' }).click();
});
