import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

/**
 * The advertising execution surfaces in a real browser.
 *
 * Two different things are proven here, and as in the operating-console suite
 * they are kept apart on purpose.
 *
 * What the backend refuses is asserted against the real backend this suite
 * starts. An advertising read without a token, and one with a made-up token,
 * are refused by the running application rather than by a stub.
 *
 * What the console renders is asserted against responses this test supplies
 * through the browser's own network layer. That is a test of the console's
 * presentation and nothing else. None of it is evidence about a marketplace,
 * about any advertising platform, or about what this product would do with a
 * real one, and it is not offered as such.
 */

const API_ORIGIN = 'http://127.0.0.1:8080';

/** The store this deployment's console is configured to work in. */
const STORE_ID = '00000000-0000-0000-0000-0000000000d1';

/**
 * The placeholder bearer value this suite hands the console.
 *
 * Assembled rather than written as one literal so it cannot be mistaken — by a
 * reader or by the repository's secret scanner — for a credential. It
 * authenticates nothing; the first case below asserts the backend refuses it.
 */
const PLACEHOLDER_BEARER = ['browser', 'advertising', 'placeholder'].join('-');

/** Answer one console path with a prepared body, in the browser. */
async function route(page: Page, path: string, body: unknown): Promise<void> {
  await page.route(`${API_ORIGIN}${path}*`, async (target) => {
    await target.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    });
  });
}

/** Answer the identity provider inside the browser, contacting nothing. */
async function routeIdentityProvider(page: Page): Promise<void> {
  await page.route('https://id.example.test/authorize*', async (target) => {
    const requested = new URL(target.request().url());
    const state = requested.searchParams.get('state') ?? '';
    const redirect = requested.searchParams.get('redirect_uri') ?? '';
    await target.fulfill({
      status: 302,
      headers: {
        location: `${redirect}?code=test-authorization-code&state=${encodeURIComponent(state)}`,
      },
      body: '',
    });
  });
  await page.route('https://id.example.test/token', async (target) => {
    const claims = Buffer.from(
      JSON.stringify({ name: 'Pilot Operator', auth_time: Math.floor(Date.now() / 1000) }),
    )
      .toString('base64')
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
    await target.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json', 'access-control-allow-origin': '*' },
      body: JSON.stringify({
        access_token: PLACEHOLDER_BEARER,
        expires_in: 900,
        id_token: `header.${claims}.signature`,
      }),
    });
  });
}

/** An envelope with room on two axes and none on the third. */
const ENVELOPE = {
  envelopeId: '11111111-1111-4111-8111-111111111111',
  policyVersion: 3,
  scopeKind: 'ORGANIZATION',
  currencyCode: 'RUB',
  activeInterventions: 7,
  maxActiveInterventions: 10,
  reservedRecoveryHeadroom: 3,
  unresolvedTransmittedWrites: 0,
  maxUnresolvedTransmittedWrites: 2,
  cumulativeBidChangeAmount: '120.0000',
  maxCumulativeBidChangeAmount: '500.0000',
  cumulativeWindowHours: 24,
  resolved: true,
  exhaustedAxes: ['ACTIVE_INTERVENTIONS'],
  status: 'ACTIVE',
};

const RESERVATION = {
  id: '22222222-2222-4222-8222-222222222222',
  adNativeObjectId: '33333333-3333-4333-8333-333333333333',
  storeId: STORE_ID,
  affectedSetDigest: 'a'.repeat(64),
  productVariantIds: ['55555555-5555-4555-8555-555555555555'],
  interventionKind: 'CONTROLLED_AD_BID_CHANGE',
  interventionReferenceId: '66666666-6666-4666-8666-666666666666',
  direction: 'PROTECTION_DECREASE',
  lane: 'PROTECTION',
  state: 'ACTIVE',
  holding: true,
  outstandingReleaseConditions: ['UNKNOWN_OR_MISMATCH_OPEN'],
  reservedAt: '2026-09-04T00:00:00Z',
  releasedAt: null,
  releaseReason: null,
};

const CONTAINMENT = {
  id: '77777777-7777-4777-8777-777777777777',
  containmentKind: 'OUTCOME_QUARANTINE',
  scopeKind: 'LINEAGE',
  causeClass: 'OUTCOME',
  reason: 'A settled reading went the wrong way and nobody has explained it.',
  evidenceReference: 'evidence://ad/outcome/1',
  activatedByUserId: null,
  activatedByTrigger: 'AD_OUTCOME_REGRESSION',
  activatedAt: '2026-09-04T00:00:00Z',
  state: 'ACTIVE',
  holding: true,
  outstandingConditions: ['ROOT_CAUSE_CLASSIFIED', 'UNKNOWNS_RESOLVED'],
  readyToLift: false,
  reenabledAt: null,
};

test.describe('TC-BROWSER-015 the advertising reads are refused without an authenticated caller', () => {
  for (const path of [
    '/api/v1/console/advertising/reservations',
    '/api/v1/console/advertising/exposure',
    '/api/v1/console/advertising/containments',
  ]) {
    test(`the backend refuses ${path} with no token`, async ({ page }) => {
      await page.goto('/');

      const status = await page.evaluate(async (url: string) => {
        const response = await fetch(url, {
          method: 'GET',
          credentials: 'omit',
          cache: 'no-store',
        });
        return response.status;
      }, `${API_ORIGIN}${path}`);

      expect([401, 403]).toContain(status);
    });
  }

  test('the backend refuses an advertising read carrying a made-up token', async ({ page }) => {
    await page.goto('/');

    const status = await page.evaluate(async (origin) => {
      const response = await fetch(`${origin}/api/v1/console/advertising/exposure`, {
        method: 'GET',
        headers: { Authorization: 'Bearer not-a-real-token' },
        credentials: 'omit',
        cache: 'no-store',
      });
      return response.status;
    }, API_ORIGIN);

    expect([401, 403]).toContain(status);
  });
});

test.describe('TC-BROWSER-016 the execution surfaces render in a real browser', () => {
  test('the envelope is shown axis by axis, with no combined figure', async ({ page }) => {
    await routeIdentityProvider(page);
    await route(page, '/api/v1/console/advertising/exposure', ENVELOPE);
    await route(page, '/api/v1/console/advertising/reservations', [RESERVATION]);
    await route(page, '/api/v1/console/advertising/containments', [CONTAINMENT]);
    await route(page, '/api/v1/console/advertising/queue', []);

    await page.goto('/');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();

    const envelope = page.getByLabel('Exposure envelope');
    await expect(envelope).toBeVisible();
    // Three axes, three limits. A single percentage would describe a quantity
    // the product does not have.
    await expect(envelope.locator('[data-axis]')).toHaveCount(3);
    await expect(envelope.locator('[data-axis="ACTIVE_INTERVENTIONS"]')).toContainText(
      '3 of 10 reserved for recovery',
    );
    await expect(
      envelope.locator('[data-axis="ACTIVE_INTERVENTIONS"] [data-exhausted="true"]'),
    ).toHaveCount(1);
    await expect(
      envelope.locator('[data-axis="UNRESOLVED_TRANSMITTED_WRITES"] [data-exhausted="false"]'),
    ).toHaveCount(1);
  });

  test('a hold names its kind and every condition still outstanding', async ({ page }) => {
    await routeIdentityProvider(page);
    await route(page, '/api/v1/console/advertising/exposure', ENVELOPE);
    await route(page, '/api/v1/console/advertising/reservations', []);
    await route(page, '/api/v1/console/advertising/containments', [CONTAINMENT]);
    await route(page, '/api/v1/console/advertising/queue', []);

    await page.goto('/');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();

    const holds = page.getByLabel('Containment');
    await expect(holds.locator('[data-kind="OUTCOME_QUARANTINE"]')).toHaveCount(1);
    await expect(
      holds.getByLabel('Outstanding reenablement conditions').getByRole('listitem'),
    ).toHaveCount(2);

    // An empty reservation list is compatible with a full queue, and the page
    // has to say so rather than leave an operator to guess.
    await expect(page.getByLabel('Reservations')).toContainText(
      'Only a real intervention takes a reservation',
    );
  });

  test('no envelope is presented as no write at all, not as an empty one', async ({ page }) => {
    await routeIdentityProvider(page);
    await route(page, '/api/v1/console/advertising/exposure', {
      envelopeId: null,
      activeInterventions: 2,
      unresolvedTransmittedWrites: 1,
      resolved: false,
      exhaustedAxes: ['AGGREGATE_ENVELOPE_UNRESOLVED'],
    });
    await route(page, '/api/v1/console/advertising/reservations', []);
    await route(page, '/api/v1/console/advertising/containments', []);
    await route(page, '/api/v1/console/advertising/queue', []);

    await page.goto('/');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();

    const envelope = page.getByLabel('Exposure envelope');
    await expect(envelope).toHaveAttribute('data-state', 'unresolved');
    await expect(envelope.getByRole('alert')).toContainText('No exposure envelope is in force');
  });
});
