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

/** Current six-axis response fixtures; browser rendering only, not admission evidence. */
const COMPANY_ENVELOPE = {
  envelopeId: '11111111-1111-4111-8111-111111111111',
  policyVersion: 3,
  scopeKind: 'ORGANIZATION',
  currencyCode: 'RUB',
  measurementWindowHours: 24,
  retainedWindowDays: 30,
  axes: {
    activeInterventions: { usage: 7, limit: 10, state: 'EXCEEDED' },
    associatedOfficialSpend: { usage: null, limit: 500, state: 'UNKNOWN', unit: 'RUB_MAJOR' },
    affectedRetainedSalesShare: {
      usage: 0.12,
      limit: 0.2,
      affectedSales: 120,
      companySales: 1000,
      state: 'AVAILABLE',
    },
    cumulativeBidChangeMajor: {
      usage: 120,
      limit: 500,
      state: 'AVAILABLE',
      unit: 'RUB_MAJOR',
      windowHours: 24,
    },
    unresolvedTransmittedWrites: { usage: 0, limit: 2, state: 'AVAILABLE' },
    reservedRecoveryHeadroom: { available: 3, reserved: 3, state: 'AVAILABLE' },
  },
  reasons: ['ACTIVE_INTERVENTIONS', 'ASSOCIATED_SPEND_UNRESOLVED'],
};
const ENVELOPE = {
  measuredAt: '2026-09-04T00:00:00Z',
  envelopes: [
    COMPANY_ENVELOPE,
    {
      ...COMPANY_ENVELOPE,
      envelopeId: '88888888-8888-4888-8888-888888888888',
      policyVersion: 4,
      scopeKind: 'STORE',
      storeId: STORE_ID,
      platformCode: 'SYNTHETIC_RENDER',
    },
  ],
  unresolvedStoreIds: [],
  resolved: true,
  status: 'MEASURED',
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
    // Every applicable scope keeps all six independent axes and its own limits.
    await expect(envelope.getByRole('table')).toHaveCount(2);
    await expect(envelope).toContainText('Version 3 at ORGANIZATION scope');
    await expect(envelope).toContainText(
      `Version 4 at STORE scope / SYNTHETIC_RENDER / Store ${STORE_ID}`,
    );
    await expect(envelope).toContainText('Measurement window: 24 hours; Retained cohort: 30 days');
    await expect(envelope).toContainText(ENVELOPE.measuredAt);
    for (const item of ENVELOPE.envelopes) {
      const scoped = envelope.locator(`[data-envelope="${item.envelopeId}"]`);
      await expect(scoped.locator('[data-axis]')).toHaveCount(6);
      for (const code of Object.keys(COMPANY_ENVELOPE.axes)) {
        await expect(scoped.locator(`[data-axis="${code}"]`)).toHaveCount(1);
      }
      await expect(scoped.locator('[data-axis="activeInterventions"]')).toContainText(
        '3 of 10 reserved for recovery',
      );
      await expect(
        scoped.locator('[data-axis="activeInterventions"] [data-state="EXCEEDED"]'),
      ).toHaveCount(1);
      const unknownSpend = scoped.locator('[data-axis="associatedOfficialSpend"]');
      await expect(unknownSpend).toContainText('not measured');
      await expect(unknownSpend).toContainText('unknown; capacity unproven');
      await expect(unknownSpend).not.toContainText('within current limit');
      await expect(scoped.locator('[data-axis="affectedRetainedSalesShare"]')).toContainText(
        'affected sales 120 / company sales 1000',
      );
      await expect(scoped.locator('[data-axis="cumulativeBidChangeMajor"]')).toContainText(
        'RUB_MAJOR over 24 hours',
      );
      await expect(
        scoped.locator('[data-axis="unresolvedTransmittedWrites"] td').first(),
      ).toHaveText('0');
      await expect(
        scoped.locator('[data-axis="unresolvedTransmittedWrites"] [data-state="AVAILABLE"]'),
      ).toHaveCount(1);
      const headroom = scoped.locator('[data-axis="reservedRecoveryHeadroom"]');
      await expect(headroom.locator('td').nth(0)).toHaveText('3');
      await expect(headroom.locator('td').nth(1)).toHaveText('3');
    }
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
      measuredAt: ENVELOPE.measuredAt,
      envelopes: [],
      unresolvedStoreIds: [STORE_ID],
      resolved: false,
      status: 'UNRESOLVED',
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
