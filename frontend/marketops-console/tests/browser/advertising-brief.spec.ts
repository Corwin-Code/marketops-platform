import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

/**
 * The published brief in a real browser.
 *
 * As in the execution suite, two separate things are proven and kept apart.
 * What the backend refuses is asserted against the running application. What
 * the console renders is asserted against bodies this test supplies through the
 * browser's own network layer, and is evidence about presentation only — not
 * about any marketplace, advertising platform, or figure in it.
 */

const API_ORIGIN = 'http://127.0.0.1:8080';
const BRIEFS = `${API_ORIGIN}/api/v1/console/advertising/briefs`;
const PERIOD = '2026-09-04';

/** Answer one exact console URL with a prepared body. */
async function answer(page: Page, url: string, body: unknown, status = 200): Promise<void> {
  await page.route(url, async (target) => {
    await target.fulfill({
      status,
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
        access_token: ['browser', 'brief', 'placeholder'].join('-'),
        expires_in: 900,
        id_token: `header.${claims}.signature`,
      }),
    });
  });
}

/** The other queue-page reads, answered empty so only the brief is under test. */
async function quietTheRestOfTheQueuePage(page: Page): Promise<void> {
  await page.route(`${API_ORIGIN}/api/v1/console/advertising/exposure*`, (target) =>
    target.fulfill({ status: 200, contentType: 'application/json', body: '{}' }),
  );
  for (const path of ['reservations', 'containments', 'queue']) {
    await page.route(`${API_ORIGIN}/api/v1/console/advertising/${path}*`, (target) =>
      target.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  }
}

const READING_ONE = {
  id: '11111111-1111-4111-8111-111111111111',
  briefKind: 'DAILY_ACTION_BRIEF',
  periodKey: PERIOD,
  asOf: '2026-09-04T06:00:00Z',
  revisionNo: 1,
  revisionKind: 'ORIGINAL',
  supersedesPublicationId: null,
  adjustmentReason: null,
  lateFactReference: null,
  gapCodes: ['NO_CANONICAL_GATE_EVIDENCE_SOURCE'],
  contentDigest: 'a'.repeat(64),
  publishedAt: '2026-09-04T06:00:01Z',
  restatement: false,
  fullyCovered: false,
  sections: [
    {
      sectionCode: 'IMMEDIATE_PROTECTION_AND_REGRESSION',
      ordinal: 1,
      itemCount: 1,
      coverageState: 'COMPLETE',
      blockerCodes: [],
      summaryNote: null,
      complete: true,
      items: [
        {
          subjectKind: 'AD_CASE',
          referenceId: '22222222-2222-4222-8222-222222222222',
          lane: 'PROTECTION',
          causeCode: 'PROVEN_ADVERTISING_LOSS',
          valueState: 'AVAILABLE',
          numericValue: '-4200.0000',
          currencyCode: 'RUB',
          evidenceState: 'CANONICAL_CONFIRMED',
          blockerCodes: [],
          observedAt: '2026-09-04T05:00:00Z',
        },
      ],
    },
    {
      sectionCode: 'WATCH',
      ordinal: 2,
      itemCount: 0,
      coverageState: 'COMPLETE',
      blockerCodes: [],
      summaryNote: null,
      complete: true,
      items: [],
    },
    {
      sectionCode: 'GATE_EVIDENCE',
      ordinal: 3,
      itemCount: 0,
      coverageState: 'NOT_AVAILABLE',
      blockerCodes: ['NO_CANONICAL_GATE_EVIDENCE_SOURCE'],
      summaryNote: 'this product holds no canonical source for this topic',
      complete: false,
      items: [],
    },
  ],
};

const READING_TWO = {
  ...READING_ONE,
  id: '33333333-3333-4333-8333-333333333333',
  revisionNo: 2,
  revisionKind: 'REVISION',
  supersedesPublicationId: READING_ONE.id,
  adjustmentReason: 'a completed sale settled after the cut',
  lateFactReference: 'fact://late/completed-sale',
  contentDigest: 'b'.repeat(64),
  restatement: true,
};

test.describe('TC-BROWSER-017 the brief reads are refused without an authenticated caller', () => {
  for (const path of [
    '/api/v1/console/advertising/briefs/DAILY_ACTION_BRIEF',
    `/api/v1/console/advertising/briefs/DAILY_ACTION_BRIEF/${PERIOD}`,
    `/api/v1/console/advertising/briefs/DAILY_ACTION_BRIEF/${PERIOD}/history`,
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
});

test.describe('TC-BROWSER-018 the brief renders as published', () => {
  test('every section is shown, including the ones that found nothing', async ({ page }) => {
    await routeIdentityProvider(page);
    await quietTheRestOfTheQueuePage(page);
    await answer(page, `${BRIEFS}/DAILY_ACTION_BRIEF/${PERIOD}/history`, [READING_ONE]);
    await answer(page, `${BRIEFS}/DAILY_ACTION_BRIEF`, READING_ONE);

    await page.goto('/');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();

    const brief = page.getByLabel('Advertising brief');
    await expect(brief).toBeVisible();
    // A section that vanished when empty would make "we looked and there was
    // nothing" and "we never looked" the same page.
    await expect(brief.locator('[data-section]')).toHaveCount(3);
    await expect(brief.locator('[data-section="WATCH"]')).toContainText(
      'Nothing fell into this topic',
    );
    await expect(brief.locator('[data-section="GATE_EVIDENCE"]')).toHaveAttribute(
      'data-coverage',
      'NOT_AVAILABLE',
    );
    await expect(brief.locator('[data-section="GATE_EVIDENCE"]')).toContainText(
      'No canonical source for this topic',
    );
  });

  test('a restatement keeps the reading somebody acted on', async ({ page }) => {
    await routeIdentityProvider(page);
    await quietTheRestOfTheQueuePage(page);
    await answer(page, `${BRIEFS}/DAILY_ACTION_BRIEF/${PERIOD}/history`, [
      READING_ONE,
      READING_TWO,
    ]);
    await answer(page, `${BRIEFS}/DAILY_ACTION_BRIEF`, READING_TWO);

    await page.goto('/');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();

    const brief = page.getByLabel('Advertising brief');
    await expect(brief.locator('[data-restatement="true"]')).toContainText(
      'fact://late/completed-sale',
    );
    await expect(brief.getByLabel('Earlier readings').getByRole('listitem')).toHaveCount(2);
  });

  test('nothing published is stated, not shown as a fault', async ({ page }) => {
    await routeIdentityProvider(page);
    await quietTheRestOfTheQueuePage(page);
    await answer(page, `${BRIEFS}/DAILY_ACTION_BRIEF`, { detail: 'not found' }, 404);

    await page.goto('/');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();

    const brief = page.getByLabel('Advertising brief');
    await expect(brief).toHaveAttribute('data-state', 'none');
    await expect(brief).toContainText('No reading has been published');
  });
});
