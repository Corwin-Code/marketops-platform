import { expect, test } from '@playwright/test';

/**
 * The availability journey in a real browser against the real backend.
 *
 * Nothing is stubbed below the console: the card, its children and the case
 * were produced by the same calculation and activation policy a worker runs.
 * The identity provider is answered through the browser's own network layer, so
 * no external system is contacted and nothing here is evidence about one.
 */
test('TC-BROWSER-014 stockout queue, accountable case and structured action', async ({ page }) => {
  const response = await page.request.get('http://127.0.0.1:8082/fixture');
  expect(response.ok()).toBe(true);
  const fixture = (await response.json()) as {
    accessToken: string;
    storeId: string;
    productVariantId: string;
  };

  await page.route('https://id.example.test/authorize*', async (target) => {
    const request = new URL(target.request().url());
    await target.fulfill({
      status: 302,
      headers: {
        location: `${request.searchParams.get('redirect_uri') ?? ''}?code=synthetic-code&state=${encodeURIComponent(request.searchParams.get('state') ?? '')}`,
      },
      body: '',
    });
  });
  await page.route('https://id.example.test/token', async (target) => {
    await target.fulfill({
      status: 200,
      headers: {
        'content-type': 'application/json',
        'access-control-allow-origin': 'http://127.0.0.1:4173',
      },
      body: JSON.stringify({ access_token: fixture.accessToken, expires_in: 600 }),
    });
  });

  await page.goto('/');
  await page.getByRole('button', { name: 'Continue to sign in' }).click();

  const queue = page.getByLabel('Stockout and availability');
  await expect(queue).toBeVisible();
  const card = queue.getByTestId('availability-card').first();
  await expect(card).toHaveAttribute('data-lane', 'CRITICAL');
  await expect(card.getByTestId('card-trigger')).toContainText('raised by');

  // The two children are shown apart, and the channel names its own cause
  // rather than inheriting a blended parent state.
  const channel = card.getByTestId('availability-child').filter({ hasText: 'OZON' }).first();
  await expect(channel).toHaveAttribute('data-child-kind', 'CHANNEL');
  await expect(channel).toHaveAttribute('data-evidence-tone', 'confirmed');
  await expect(channel.getByTestId('child-cause')).toContainText('nothing available');

  const cases = page.getByLabel('Availability cases');
  await expect(cases).toBeVisible();
  const accountable = cases.getByTestId('availability-case').first();
  await expect(accountable.getByTestId('case-state')).toHaveText('Open');
  // The two clocks are separately rendered; a single merged badge would name
  // neither failure.
  await expect(accountable.getByTestId('case-action-due')).toBeVisible();
  await expect(accountable.getByTestId('case-outcome-due')).toBeVisible();

  // There is no control anywhere that means "seen".
  await expect(page.getByRole('button', { name: /acknowledge/i })).toHaveCount(0);

  await accountable.getByTestId('action-evidence').fill('ev://ozon/restock/browser-1');
  await accountable.getByTestId('action-reason').fill('replenishment bound to the listing');
  await accountable.getByTestId('action-submit').click();

  // Recording an action starts verification and never claims success.
  await expect(cases.getByTestId('availability-case').first().getByTestId('case-state')).toHaveText(
    'Verifying',
  );
  await expect(page.getByText('Verified', { exact: true })).toHaveCount(0);

  await accountable.getByTestId('case-load-journal').click();
  await expect(accountable.getByTestId('case-journal')).toContainText('ACTION_RECORDED');

  // The fixture driver is test-classpath-only. It feeds the workflow authority
  // a calculated improvement observation, then a returning calculated risk and
  // a governed exception request; no public route can name success.
  const returned = await page.request.post(
    'http://127.0.0.1:8082/availability/reopen-and-exception',
    { headers: { 'X-Fixture-Driver': 'browser-test' } },
  );
  const returnedBody = await returned.text();
  expect(returned.ok(), returnedBody).toBe(true);
  expect(JSON.parse(returnedBody)).toMatchObject({
    caseState: 'REOPENED',
    reopenCount: 1,
    exceptionState: 'REQUESTED',
  });

  await page.reload();
  await page.getByRole('button', { name: 'Continue to sign in' }).click();
  const reopened = page
    .getByLabel('Availability cases')
    .getByTestId('availability-case')
    .filter({ hasText: 'Channel has nothing available' })
    .first();
  await expect(reopened.getByTestId('case-state')).toHaveText('Reopened');
  await expect(reopened.getByTestId('case-reopens')).toHaveText('1');
  await reopened.getByTestId('case-load-journal').click();
  await expect(reopened.getByTestId('case-journal')).toContainText('VERIFICATION_OBSERVED');
  await expect(reopened.getByTestId('case-journal')).toContainText('REOPENED');
  await expect(reopened.getByTestId('case-exceptions')).toContainText('Requested');
  await expect(reopened.getByTestId('exception-authority')).toContainText('RISK_AUTHORITY');
});
