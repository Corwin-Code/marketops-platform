import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { createHash } from 'node:crypto';

/**
 * The operating console in a real browser.
 *
 * Two different things are proven here, and they are kept apart on purpose.
 *
 * What the backend refuses is asserted against the real backend this suite
 * starts: an operating request without a token is refused by the running
 * application, not by a stub.
 *
 * What the console renders is asserted against responses this test supplies
 * through the browser's own network layer. That is a test of the console's
 * presentation and nothing else. None of it is evidence about a marketplace, a
 * model provider or any other external system, and it is not offered as such.
 */

const API_ORIGIN = 'http://127.0.0.1:8080';

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

test.describe('TC-BROWSER-010 nothing operational reaches an unauthenticated visitor', () => {
  test('shows the platform panel and no operating data', async ({ page }) => {
    await page.goto('/');

    await expect(page.getByRole('heading', { name: 'MarketOps Russia' })).toBeVisible();
    await expect(page.getByLabel('Platform state')).toBeVisible();
    await expect(page.getByLabel('Priority queue')).toHaveCount(0);
    await expect(page.getByLabel('Recommendation review')).toHaveCount(0);
    await expect(page.getByLabel('Command timeline')).toHaveCount(0);
  });

  test('the backend refuses an operating request that carries no token', async ({ page }) => {
    await page.goto('/');

    const status = await page.evaluate(async (origin) => {
      const response = await fetch(
        `${origin}/api/v1/console/diagnosis/stores/00000000-0000-0000-0000-000000000001/queue`,
        { method: 'GET', credentials: 'omit', cache: 'no-store' },
      );
      return response.status;
    }, API_ORIGIN);

    expect([401, 403]).toContain(status);
  });

  test('the backend refuses an operating request that carries a made-up token', async ({
    page,
  }) => {
    await page.goto('/');

    const status = await page.evaluate(async (origin) => {
      const response = await fetch(
        `${origin}/api/v1/console/commands/00000000-0000-0000-0000-000000000002`,
        {
          method: 'GET',
          headers: { Authorization: 'Bearer not-a-real-token' },
          credentials: 'omit',
          cache: 'no-store',
        },
      );
      return response.status;
    }, API_ORIGIN);

    expect([401, 403]).toContain(status);
  });

  test('the built bundle carries no token, password or secret reference', async ({ page }) => {
    const response = await page.goto('/');
    expect(response?.ok()).toBe(true);

    const scripts = await page.evaluate(() =>
      [...document.querySelectorAll('script[src]')].map((element) => element.getAttribute('src')),
    );
    for (const source of scripts) {
      if (source === null) {
        continue;
      }
      const bundle = await page.request.get(new URL(source, page.url()).toString());
      const text = await bundle.text();
      expect(text).not.toMatch(/secret-ref:\/\//);
      expect(text).not.toMatch(/BEGIN [A-Z ]*PRIVATE KEY/);
      expect(text).not.toMatch(/client_secret/);
    }
  });
});

/**
 * The placeholder bearer value this suite hands the console.
 *
 * Assembled rather than written as one literal so it cannot be mistaken — by a
 * reader or by the repository's secret scanner — for a credential. It
 * authenticates nothing: the backend refuses it, which one of the cases above
 * asserts directly.
 */
const PLACEHOLDER_BEARER = ['browser', 'suite', 'placeholder'].join('-');

/** The store this deployment's console is configured to work in. */
const STORE_ID = '00000000-0000-0000-0000-0000000000d1';

/**
 * Answer the identity provider inside the browser.
 *
 * The provider is an external system this suite must not contact, so its two
 * endpoints are answered here. What is being proven is the console's own flow —
 * that it asks for a code with a challenge, redeems it with the verifier it
 * kept, and only then shows operating data. Nothing here is evidence about any
 * identity provider's behaviour.
 */
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
      // A browser-based public client reads the token response with script, so
      // a real provider has to permit the console's origin. Answering without
      // these headers would fail the exchange for a reason that has nothing to
      // do with the console's own behaviour.
      headers: {
        'content-type': 'application/json',
        'access-control-allow-origin': '*',
      },
      body: JSON.stringify({
        access_token: PLACEHOLDER_BEARER,
        expires_in: 900,
        id_token: `header.${claims}.signature`,
      }),
    });
  });
}

test.describe('TC-BROWSER-012 asynchronous export presentation and browser download integrity', () => {
  for (const corrupt of [false, true]) {
    test(
      corrupt
        ? 'refuses a corrupt final part without offering a partial file'
        : 'waits for 202 completion and saves only the verified large export',
      async ({ page }) => {
        // UI/network fixtures only. DiagnosticExportIT separately exercises these
        // routes, authorization, SQL and custody against the real PG17 backend.
        await routeIdentityProvider(page);
        const id = '00000000-0000-4000-8000-000000000123';
        const line =
          JSON.stringify({
            schemaVersion: 1,
            recordType: 'METRIC_INPUT',
            metricValueId: id,
            referenceKind: 'METRIC_VALUE',
            referenceId: id,
          }) + '\n';
        const part = line.repeat(10000);
        const digest = (body: string): string => createHash('sha256').update(body).digest('hex');
        const job = {
          id,
          storeId: STORE_ID,
          window: 'D30',
          state: 'SUCCEEDED',
          createdAt: '2026-08-28T00:00:00Z',
          snapshotAt: '2026-08-28T00:00:01Z',
          expiresAt: '2026-08-28T01:00:01Z',
          rowCount: 30000,
          byteLength: Buffer.byteLength(part) * 3,
          completedParts: 3,
          failureCode: null,
        };
        const document = JSON.stringify({
          schemaVersion: 1,
          format: 'marketops-diagnostic-ndjson-v1',
          exportId: id,
          storeId: STORE_ID,
          window: 'D30',
          snapshotAt: job.snapshotAt,
          rowCount: job.rowCount,
          byteLength: job.byteLength,
          parts: [1, 2, 3].map((n) => ({
            partNumber: n,
            firstOrdinal: (n - 1) * 10000 + 1,
            lastOrdinal: n * 10000,
            rowCount: 10000,
            byteLength: Buffer.byteLength(part),
            sha256: digest(part),
          })),
        });
        let submitted = false;
        let statusRead = false;
        const downloadedParts: number[] = [];
        await page.route(`${API_ORIGIN}/api/v1/console/diagnosis/**`, async (target) => {
          const path = new URL(target.request().url()).pathname;
          const headers = {
            'access-control-allow-origin': 'http://127.0.0.1:4173',
            'access-control-allow-headers': 'Authorization, Idempotency-Key, X-Correlation-ID',
            'access-control-allow-methods': 'GET,POST,OPTIONS',
            'cache-control': 'no-store',
          };
          if (target.request().method() === 'OPTIONS') {
            await target.fulfill({ status: 204, headers });
            return;
          }
          let body: unknown = [];
          let status = 200;
          if (path.endsWith(`/stores/${STORE_ID}/exports`)) {
            expect(target.request().method()).toBe('POST');
            expect(target.request().headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
            submitted = true;
            status = 202;
            body = {
              ...job,
              state: 'QUEUED',
              rowCount: 0,
              byteLength: 0,
              completedParts: 0,
              snapshotAt: null,
              expiresAt: null,
            };
          } else if (path.endsWith(`/exports/${id}`)) {
            expect(submitted).toBe(true);
            statusRead = true;
            body = job;
          } else if (path.endsWith('/manifest')) {
            expect(statusRead).toBe(true);
            body = { document, sha256: digest(document) };
          } else if (path.includes('/parts/')) {
            expect(statusRead).toBe(true);
            const number = Number(path.split('/').at(-1));
            downloadedParts.push(number);
            await target.fulfill({
              status: 200,
              headers,
              contentType: 'application/octet-stream',
              body: corrupt && number === 3 ? part.replace('METRIC_INPUT', 'METRIC_BROKE') : part,
            });
            return;
          }
          await target.fulfill({
            status,
            headers,
            contentType: 'application/json',
            body: JSON.stringify(body),
          });
        });
        await page.goto('/');
        await page.getByRole('button', { name: 'Continue to sign in' }).click();
        await page.getByRole('button', { name: 'Prepare export' }).click();
        await expect(page.getByLabel('Diagnostic export')).toContainText('QUEUED');
        expect(downloadedParts).toEqual([]);
        const button = page.getByRole('button', { name: 'Download verified export' });
        await expect(button).toBeVisible();
        if (corrupt) {
          let downloads = 0;
          page.on('download', () => {
            downloads++;
          });
          await button.click();
          // Scoped to the export panel. Every panel on this page reports its own
          // failure, and the console is configured here with a bearer the backend
          // refuses, so the advertising panels legitimately show a refusal of
          // their own. A page-wide alert query would resolve to whichever
          // arrived first and say nothing about the export.
          await expect(page.getByLabel('Diagnostic export').getByRole('alert')).toContainText(
            'No file was saved',
          );
          expect(downloads).toBe(0);
        } else {
          const ready = page.waitForEvent('download');
          await button.click();
          const download = await ready;
          expect(download.suggestedFilename()).toBe(`diagnostic-${id}.ndjson`);
          const stream = await download.createReadStream();
          const actual = createHash('sha256');
          let size = 0;
          for await (const chunk of stream) {
            const bytes = Buffer.from(chunk as Uint8Array);
            size += bytes.length;
            actual.update(bytes);
          }
          expect(size).toBe(job.byteLength);
          expect(actual.digest('hex')).toBe(digest(part.repeat(3)));
        }
        expect(downloadedParts).toEqual([1, 2, 3]);
      },
    );
  }
});

test.describe('TC-BROWSER-011 the operator journey runs in a real browser', () => {
  test('signs in, reaches the work list, and never labels doubt as certainty', async ({ page }) => {
    await routeIdentityProvider(page);
    await route(page, `/api/v1/console/diagnosis/stores/${STORE_ID}/queue`, [
      {
        subjectId: 'variant-1',
        storeId: STORE_ID,
        priorityScore: '820.0000',
        criticalFindingCount: 1,
        warningFindingCount: 2,
        declinedRuleCount: 1,
        netSales: null,
        contributionProfit: null,
        currencyCode: null,
        blockingRuleCodes: ['DATA_BLOCKED'],
      },
    ]);
    await route(page, '/api/v1/console/diagnosis/listing-variants/variant-1', {
      subjectId: 'variant-1',
      storeId: STORE_ID,
      window: 'D30',
      metrics: {
        UNIT_COST: {
          metricValueId: 'm1',
          metricCode: 'UNIT_COST',
          valueState: 'NOT_AVAILABLE',
          numericValue: null,
          currencyCode: null,
          confidenceState: 'CANONICAL_CONFIRMED',
          estimated: false,
          freshnessSeconds: null,
          evidenceRefs: [],
        },
        OBSERVED_SELLING_PRICE: {
          metricValueId: 'm2',
          metricCode: 'OBSERVED_SELLING_PRICE',
          valueState: 'AVAILABLE',
          numericValue: '100.0000',
          currencyCode: 'RUB',
          confidenceState: 'STALE',
          estimated: false,
          freshnessSeconds: 200_000,
          evidenceRefs: ['provenance-1'],
        },
      },
      findings: [
        {
          findingId: 'f1',
          ruleCode: 'DATA_BLOCKED',
          outcome: 'TRIGGERED',
          severity: 'CRITICAL',
          declineReason: null,
          detail: { missing: 'UNIT_COST' },
          blocksExecution: true,
          metricValueIds: ['m1'],
        },
      ],
    });

    await page.goto('/');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();

    await expect(page.getByLabel('Priority queue')).toBeVisible();
    await expect(page.getByText('Blocked: DATA_BLOCKED')).toBeVisible();
    await expect(page.getByText('Signed in as Pilot Operator')).toBeVisible();

    await page.getByRole('button', { name: 'variant-1' }).click();
    await expect(page.getByLabel('Subject diagnosis')).toBeVisible();

    // An unavailable figure is an absence whatever its recorded confidence says.
    const unitCost = page.locator('.value-cell[data-label="UNIT_COST"]');
    await expect(unitCost).toHaveAttribute('data-tone', 'absent');
    await expect(unitCost).toContainText('Not available');
    await expect(unitCost).toContainText('—');

    // A stale figure carries its qualifier next to the number, in text.
    const price = page.locator('.value-cell[data-label="OBSERVED_SELLING_PRICE"]');
    await expect(price).toHaveAttribute('data-tone', 'qualified');
    await expect(price).toContainText('Stale');

    // Nothing anywhere on this screen claims a confirmed value.
    await expect(page.locator('.value-cell[data-tone="confirmed"]')).toHaveCount(0);

    // The rule that blocks a write says so where the operator is looking.
    await expect(page.getByTestId('blocks-write')).toBeVisible();
  });

  test('an ended session takes the operator back to sign-in with no data left on screen', async ({
    page,
  }) => {
    await routeIdentityProvider(page);
    await route(page, `/api/v1/console/diagnosis/stores/${STORE_ID}/queue`, []);

    await page.goto('/');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();
    await expect(page.getByLabel('Priority queue')).toBeVisible();

    await page.getByRole('button', { name: 'Sign out' }).click();

    await expect(page.getByRole('button', { name: 'Continue to sign in' })).toBeVisible();
    await expect(page.getByLabel('Priority queue')).toHaveCount(0);
  });

  test('a refused sign-in leaves the visitor told rather than blank', async ({ page }) => {
    await page.route('https://id.example.test/authorize*', async (target) => {
      const requested = new URL(target.request().url());
      const redirect = requested.searchParams.get('redirect_uri') ?? '';
      await target.fulfill({
        status: 302,
        headers: { location: `${redirect}?error=access_denied` },
        body: '',
      });
    });

    await page.goto('/');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();

    await expect(page.getByTestId('sign-in-problem')).toContainText('refused the sign-in');
    await expect(page.getByLabel('Priority queue')).toHaveCount(0);
  });
});
