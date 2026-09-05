import { expect, test } from '@playwright/test';
import type { Browser, Page, APIRequestContext, Locator } from '@playwright/test';

type Role = 'MAKER' | 'OPS_LEAD' | 'OWNER';
interface Fixture {
  accessToken: string;
  userId: string;
  role: Role;
  caseId: string;
  candidateId: string;
  recommendationId: string;
  commandId?: string;
  taskId: string;
  storeId: string;
  foreignCaseId?: string;
  pagination?: {
    visibleCaseIds: readonly string[];
    watchCaseIds: readonly string[];
    dataRepairCaseIds: readonly string[];
    hiddenStoreId: string;
    hiddenCaseIds: readonly string[];
  };
  productionWriteEnabled: boolean;
  semanticVerificationState: string;
  apiCommandCount: number;
  queueCases: Readonly<Record<string, string>>;
}
const API = 'http://127.0.0.1:8080';
async function fixture(request: APIRequestContext, role: Role, scenario = 'API'): Promise<Fixture> {
  const response = await request.get(
    `http://127.0.0.1:8082/advertising-fixture?role=${role}&scenario=${scenario}`,
  );
  expect(response.ok()).toBe(true);
  return (await response.json()) as Fixture;
}
/** Real Chromium Tab traversal, with focus checked before Enter; no click or programmatic focus. */
async function activateWithKeyboard(page: Page, target: Locator): Promise<void> {
  await expect(target).toBeVisible();
  for (let step = 0; step < 100; step += 1) {
    if (await target.evaluate((element) => element === document.activeElement)) break;
    await page.keyboard.press('Tab');
  }
  await expect(target).toBeFocused();
  await page.keyboard.press('Enter');
}

/** Only OIDC exchange is intercepted; every advertising, workflow and evidence response is real. */
async function signIn(
  browser: Browser,
  request: APIRequestContext,
  role: Role,
  scenario = 'API',
  keyboardOnly = false,
): Promise<{ page: Page; data: Fixture }> {
  const data = await fixture(request, role, scenario);
  expect(data.productionWriteEnabled).toBe(false);
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.route('https://id.example.test/authorize*', async (route) => {
    const url = new URL(route.request().url());
    await route.fulfill({
      status: 302,
      headers: {
        location: `${url.searchParams.get('redirect_uri') ?? ''}?code=synthetic-browser&state=${encodeURIComponent(url.searchParams.get('state') ?? '')}`,
      },
      body: '',
    });
  });
  await page.route('https://id.example.test/token', async (route) => {
    const claims = Buffer.from(
      JSON.stringify({ name: role, auth_time: Math.floor(Date.now() / 1000) }),
    ).toString('base64url');
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json', 'access-control-allow-origin': '*' },
      body: JSON.stringify({
        access_token: data.accessToken,
        expires_in: 600,
        id_token: `header.${claims}.signature`,
      }),
    });
  });
  await page.goto('http://127.0.0.1:4173/');
  const signInButton = page.getByRole('button', { name: 'Continue to sign in' });
  if (keyboardOnly) await activateWithKeyboard(page, signInButton);
  else await signInButton.click();
  const row = page.locator(`[data-case-id="${data.caseId}"]`);
  await expect(row).toBeVisible();
  if (keyboardOnly) await activateWithKeyboard(page, row.getByRole('button'));
  else await row.getByRole('button').click();
  await expect(page.getByLabel('Advertising workflow')).toHaveAttribute('data-state', 'loaded');
  await expect(page.getByLabel('Advertising case')).toContainText('(UTC)');
  await expect(page.getByLabel('Advertising case')).toContainText('(Europe/Moscow)');
  return { page, data };
}

test('real Maker, Ops Lead and Owner decisions preserve minimum disclosure and exact versions', async ({
  browser,
  request,
}, testInfo) => {
  const maker = await signIn(browser, request, 'MAKER');
  await expect(
    maker.page.getByLabel('Advertising case').locator('[data-measure="profit"]'),
  ).toHaveAttribute('data-measure-state', 'MASKED');
  await expect(maker.page.getByRole('button', { name: 'Approve exact change' })).toHaveCount(0);
  await maker.page.getByRole('button', { name: 'Acknowledge responsibility' }).click();
  await maker.page
    .getByRole('textbox', { name: 'Reason for this decision', exact: true })
    .fill('Choose this generated bounded bid decrease');
  await maker.page.getByRole('button', { name: 'Select exact candidate', exact: true }).click();
  await expect(maker.page.getByLabel('Advertising workflow')).toContainText('VALIDATED');
  const rejected = await request.post(
    `${API}/api/v1/console/advertising/cases/${maker.data.caseId}/candidates/${maker.data.candidateId}/selection`,
    {
      headers: { Authorization: `Bearer ${maker.data.accessToken}` },
      data: { expectedVersion: 0, reason: 'stale duplicate selection' },
    },
  );
  expect([403, 409]).toContain(rejected.status());
  await maker.page.screenshot({
    path: testInfo.outputPath('maker-minimum-disclosure.png'),
    fullPage: true,
  });
  const ops = await signIn(browser, request, 'OPS_LEAD');
  await expect(
    ops.page.getByLabel('Advertising case').locator('[data-measure="profit"]'),
  ).toHaveAttribute('data-measure-state', 'AVAILABLE');
  await ops.page
    .getByRole('textbox', { name: 'Reason for this decision', exact: true })
    .fill('Scope and exact native bid reviewed');
  await ops.page.getByRole('button', { name: 'Record operational endorsement' }).click();
  await expect(ops.page.getByLabel('Advertising workflow')).toContainText('READY_FOR_REVIEW');
  await ops.page.screenshot({
    path: testInfo.outputPath('operations-endorsement.png'),
    fullPage: true,
  });
  const owner = await signIn(browser, request, 'OWNER');
  await owner.page.getByRole('button', { name: 'Review complete decision evidence' }).click();
  await expect(owner.page.getByLabel('Advertising decision preview')).toBeVisible();
  await expect(owner.page.getByLabel('Advertising decision preview')).toContainText(
    'Submitted Configuration',
  );
  await owner.page.screenshot({
    path: testInfo.outputPath('owner-exact-decision-evidence.png'),
    fullPage: true,
  });
  await owner.page
    .getByRole('textbox', { name: 'Reason for this decision', exact: true })
    .fill('Approve this exact fictional test change');
  await owner.page.getByRole('button', { name: 'Approve exact change', exact: true }).click();
  await expect(owner.page.getByLabel('Advertising workflow')).toContainText('APPROVED');
  await owner.page.getByRole('button', { name: 'Create approved command' }).click();
  await expect(owner.page.getByLabel('Advertising workflow')).toContainText(
    'Create approved command recorded',
  );
  await expect(owner.page.getByLabel('Advertising command timeline')).toHaveAttribute(
    'data-state',
    'loaded',
  );
  await expect(owner.page.getByLabel('Advertising command timeline')).toContainText('PENDING');
  await expect(owner.page.getByLabel('Outcome', { exact: true })).toHaveAttribute(
    'data-state',
    'empty',
  );
  await expect(owner.page.getByLabel('Native platform structure')).toContainText(
    'no production write authority',
  );
  await owner.page.screenshot({
    path: testInfo.outputPath('owner-approved-command.png'),
    fullPage: true,
  });
  for (const page of [maker.page, ops.page, owner.page]) await page.context().close();
});

test('real backend denies absent and forged credentials before advertising data is disclosed', async ({
  request,
}) => {
  for (const headers of [{}, { Authorization: 'Bearer synthetic-not-a-jwt' }]) {
    const response = await request.get(`${API}/api/v1/console/advertising/queue`, { headers });
    expect([401, 403]).toContain(response.status());
  }
});

for (const platform of ['OZON', 'WILDBERRIES']) {
  test(`${platform} actual governed manual path stays UNVERIFIED and requires an independent observer`, async ({
    browser,
    request,
  }, testInfo) => {
    const scenario = `MANUAL_${platform}`;
    const maker = await signIn(browser, request, 'MAKER', scenario);
    expect(maker.data.semanticVerificationState).toBe('UNVERIFIED');
    await expect(maker.page.getByLabel('Native platform structure')).toContainText(
      platform === 'WILDBERRIES' ? 'PLACEMENT' : 'KEYWORD',
    );
    await expect(
      maker.page.getByLabel('Native denomination, step and readback rules'),
    ).toContainText(platform === 'WILDBERRIES' ? 'CURRENCY_MINOR' : 'CURRENCY_MAJOR');
    await expect(maker.page.locator('[data-measure="current-bid"]')).toContainText(
      platform === 'WILDBERRIES' ? 'RUB (CURRENCY_MINOR)' : 'RUB (CURRENCY_MAJOR)',
    );
    await expect(maker.page.getByLabel('Governed manual proposals')).toContainText(
      'API profile: UNVERIFIED',
    );
    await expect(maker.page.getByRole('button', { name: 'Create approved command' })).toHaveCount(
      0,
    );
    await maker.page
      .getByRole('textbox', { name: 'Manual selection reason' })
      .fill('Execute the exact Owner-governed human proposal');
    const selectedResponse = maker.page.waitForResponse(
      (response) =>
        response.url().endsWith('/manual-selections') && response.request().method() === 'POST',
    );
    await maker.page.getByRole('button', { name: 'Select exact manual proposal' }).click();
    const selected = await selectedResponse;
    expect(selected.ok()).toBe(true);
    const packet = (await selected.json()) as { id: string; version: number };
    await expect(maker.page.getByLabel('Manual execution')).toContainText('DRAFT');
    await maker.page.screenshot({
      path: testInfo.outputPath(`${platform}-maker-manual-draft.png`),
      fullPage: true,
    });
    const ops = await signIn(browser, request, 'OPS_LEAD', scenario);
    await ops.page.getByRole('button', { name: 'Endorse manual packet' }).click();
    await expect(ops.page.getByLabel('Manual execution')).toContainText('ENDORSED');
    const owner = await signIn(browser, request, 'OWNER', scenario);
    await owner.page.getByRole('button', { name: 'Approve manual packet' }).click();
    await expect(owner.page.getByLabel('Manual execution')).toContainText('MANUAL_PACKET_ISSUED');
    await owner.page.screenshot({
      path: testInfo.outputPath(`${platform}-owner-manual-issued.png`),
      fullPage: true,
    });
    const executor = await signIn(browser, request, 'MAKER', scenario);
    await executor.page.getByRole('button', { name: 'Begin approved human execution' }).click();
    await executor.page.getByRole('button', { name: 'Report execution without proof' }).click();
    await expect(executor.page.getByLabel('Manual execution')).toContainText(
      'a report, not a proof',
    );
    await expect(
      executor.page.getByRole('button', { name: 'Record independent configuration observation' }),
    ).toHaveCount(0);
    const forbidden = await request.post(
      `${API}/api/v1/console/advertising/manual-packets/${packet.id}/independent-verification`,
      {
        headers: { Authorization: `Bearer ${executor.data.accessToken}` },
        data: { expectedVersion: 4, observedValue: '20' },
      },
    );
    expect([403, 409]).toContain(forbidden.status());
    const verifier = await signIn(browser, request, 'OPS_LEAD', scenario);
    await verifier.page
      .getByRole('textbox', { name: 'Independently observed exact native value' })
      .fill('20');
    await verifier.page
      .getByRole('button', { name: 'Record independent configuration observation' })
      .click();
    await expect(verifier.page.getByLabel('Manual execution')).toContainText(
      'MANUAL_CONFIGURATION_VERIFIED',
    );
    await expect(verifier.page.getByLabel('Manual execution')).toContainText(
      'current configuration proof',
    );
    await verifier.page
      .getByRole('button', { name: 'Observe canonical early sales safety' })
      .click();
    await expect(verifier.page.getByLabel('Manual execution')).toContainText('NOT_YET_EVALUABLE');
    await verifier.page.screenshot({
      path: testInfo.outputPath(`${platform}-independent-proof-early-safety-pending.png`),
      fullPage: true,
    });
    const final = await fixture(request, 'OWNER', scenario);
    expect(final.semanticVerificationState).toBe('UNVERIFIED');
    expect(final.apiCommandCount).toBe(0);
    for (const item of [maker, ops, owner, executor, verifier]) await item.page.context().close();
  });
}

test('all four real HTTP lane projections preserve unknown values and expose no invented action', async ({
  browser,
  request,
}, testInfo) => {
  const owner = await signIn(browser, request, 'OWNER');
  await owner.page.getByRole('button', { name: 'Back to the advertising queue' }).click();
  for (const lane of ['PROTECTION', 'DATA_REPAIR', 'OPTIMIZATION', 'WATCH']) {
    const id = owner.data.queueCases[lane];
    expect(id).toBeDefined();
    const row = owner.page.locator(`[data-case-id="${id ?? ''}"]`);
    await expect(row).toHaveAttribute('data-lane', lane);
    await row.getByRole('button').click();
    await expect(owner.page.getByLabel('Advertising case')).toHaveAttribute('data-state', 'loaded');
    if (lane !== 'PROTECTION') {
      await expect(
        owner.page.getByLabel('Advertising case').locator('[data-measure="profit"]'),
      ).toHaveAttribute('data-measure-state', 'NOT_AVAILABLE');
      await expect(owner.page.getByRole('button', { name: 'Create approved command' })).toHaveCount(
        0,
      );
      await expect(
        owner.page.getByRole('button', { name: 'Select exact candidate', exact: true }),
      ).toHaveCount(0);
    }
    await owner.page.screenshot({
      path: testInfo.outputPath(`${lane}-actual-http-projection.png`),
      fullPage: true,
    });
    await owner.page.getByRole('button', { name: 'Back to the advertising queue' }).click();
  }
  await owner.page.context().close();
});

test('three distinct people accept an exact temporary exception, block actions, and end it', async ({
  browser,
  request,
}, testInfo) => {
  const maker = await signIn(browser, request, 'MAKER', 'EXCEPTION');
  await maker.page
    .getByRole('textbox', { name: 'Action reason', exact: true })
    .fill('Review this exact temporary operating risk');
  await maker.page
    .getByLabel('Exception expiry (ISO 8601 with UTC offset)')
    .fill(new Date(Date.now() + 600_000).toISOString());
  await maker.page
    .getByLabel('Required review time (ISO 8601 with UTC offset)')
    .fill(new Date(Date.now() + 300_000).toISOString());
  await maker.page
    .getByLabel('Exception evidence reference', { exact: true })
    .fill('fixture://browser/exact-case-risk');
  await maker.page.getByRole('button', { name: 'Request exact case exception' }).click();
  await expect(maker.page.getByLabel('Responsibility and exceptions')).toContainText('REQUESTED');
  await maker.page.getByRole('button', { name: 'Review frozen exception evidence' }).click();
  await expect(maker.page.getByLabel('Frozen exception evidence')).toContainText('MASKED');
  const ops = await signIn(browser, request, 'OPS_LEAD', 'EXCEPTION');
  await ops.page
    .getByRole('textbox', { name: 'Action reason', exact: true })
    .fill('Exact exposure and review deadline examined');
  await ops.page.getByRole('button', { name: 'ENDORSE exception', exact: true }).click();
  await expect(ops.page.getByLabel('Responsibility and exceptions')).toContainText('ENDORSED');
  const owner = await signIn(browser, request, 'OWNER', 'EXCEPTION');
  await owner.page.getByRole('button', { name: 'Review frozen exception evidence' }).click();
  await expect(owner.page.getByLabel('Frozen exception evidence')).toContainText(
    'Exposure Snapshot',
  );
  await owner.page
    .getByRole('textbox', { name: 'Action reason', exact: true })
    .fill('Accept only the frozen case and current time bound');
  await owner.page.getByRole('button', { name: 'APPROVE exception', exact: true }).click();
  await expect(owner.page.getByLabel('Advertising workflow')).toContainText(
    'ACCEPTED_EXCEPTION_ACTIVE',
  );
  await expect(
    owner.page.getByRole('button', { name: 'Approve exact change', exact: true }),
  ).toHaveCount(0);
  await owner.page.screenshot({
    path: testInfo.outputPath('owner-active-exact-exception.png'),
    fullPage: true,
  });
  await owner.page
    .getByRole('textbox', { name: 'Action reason', exact: true })
    .fill('End the time bound exception and rebuild from current evidence');
  await owner.page.getByRole('button', { name: 'End exception and rebuild decisions' }).click();
  await expect(owner.page.getByLabel('Responsibility and exceptions')).toContainText('ENDED');
  await expect(owner.page.getByLabel('Advertising workflow')).toContainText('ACTION_REQUIRED');
  await owner.page.screenshot({
    path: testInfo.outputPath('owner-ended-exception-rebuild.png'),
    fullPage: true,
  });
  for (const page of [maker.page, ops.page, owner.page]) await page.context().close();
});

test('scoped emergency hold remains active until independent recovery conditions are established', async ({
  browser,
  request,
}, testInfo) => {
  const maker = await signIn(browser, request, 'MAKER');
  const reviewer = await fixture(request, 'OPS_LEAD');
  await maker.page
    .getByLabel('Stop reason', { exact: true })
    .fill('Investigate this exact synthetic object');
  await maker.page
    .getByLabel('Stop evidence reference', { exact: true })
    .fill('fixture://browser/native-object-hold');
  await maker.page.getByLabel('Responsible Operations Lead user ID').fill(reviewer.userId);
  await maker.page.getByRole('button', { name: 'Apply emergency object hold' }).click();
  await expect(maker.page.getByLabel('Scoped advertising stop')).toContainText(
    'Scoped stop recorded',
  );
  await maker.page.getByRole('button', { name: 'Back to the advertising queue' }).click();
  await expect(
    maker.page.getByLabel('Containment').locator('[data-kind="EMERGENCY_ENTITY_HOLD"]'),
  ).toHaveAttribute('data-state', 'ACTIVE');
  await maker.page.screenshot({
    path: testInfo.outputPath('maker-scoped-emergency-hold.png'),
    fullPage: true,
  });
  const ops = await signIn(browser, request, 'OPS_LEAD');
  await ops.page.getByRole('button', { name: 'Back to the advertising queue' }).click();
  const hold = ops.page.getByLabel('Containment').locator('[data-kind="EMERGENCY_ENTITY_HOLD"]');
  await hold
    .getByLabel('Canonical recovery evidence reference')
    .fill('fixture://browser/independent-recovery-review');
  await hold.getByRole('button', { name: 'ATTEST OPERATIONS ENDORSEMENT', exact: true }).click();
  await expect(hold).toHaveAttribute('data-state', 'REENABLEMENT_REVIEW');
  await expect(hold.getByRole('button', { name: 'REENABLE', exact: true })).toHaveCount(0);
  await expect(hold.getByLabel('Outstanding reenablement conditions')).toContainText(
    'ROOT_CAUSE_CLASSIFIED',
  );
  await ops.page.screenshot({
    path: testInfo.outputPath('operations-incomplete-recovery-remains-held.png'),
    fullPage: true,
  });
  for (const page of [maker.page, ops.page]) await page.context().close();
});

for (const scenario of [
  'HISTORY_UNKNOWN',
  'HISTORY_MISMATCH',
  'HISTORY_REGRESSION',
  'HISTORY_EXPIRED',
]) {
  test(`${scenario} preserves actual HTTP command history, unresolved state and separate outcomes`, async ({
    browser,
    request,
  }, testInfo) => {
    // Canonical rows are explicit synthetic read oracles. These assertions prove
    // HTTP disclosure and rendering; computation and Provider semantics have separate tests.
    const owner = await signIn(browser, request, 'OWNER', scenario);
    const timeline = owner.page.getByLabel('Advertising command timeline');
    await expect(timeline).toHaveAttribute('data-state', 'loaded');
    await expect(timeline).toHaveAttribute('data-command-id', owner.data.commandId ?? 'missing');
    await expect(timeline).toContainText('CURRENCY_MAJOR');
    await expect(timeline).toContainText('independently established');
    await expect(owner.page.getByRole('button', { name: 'Create approved command' })).toHaveCount(
      0,
    );
    if (scenario === 'HISTORY_UNKNOWN' || scenario === 'HISTORY_MISMATCH') {
      await expect(timeline).toContainText(
        scenario === 'HISTORY_UNKNOWN' ? 'UNKNOWN_REQUIRES_READBACK' : 'READBACK_MISMATCH',
      );
      await expect(timeline.getByRole('alert')).toContainText('Preserve the reservation');
      await expect(timeline.getByLabel('Outcome', { exact: true })).toHaveAttribute(
        'data-state',
        'empty',
      );
      if (scenario === 'HISTORY_MISMATCH') {
        await expect(
          timeline.getByRole('list', { name: 'Advertising native configuration readbacks' }),
        ).toContainText('27');
      }
    } else if (scenario === 'HISTORY_REGRESSION') {
      const outcome = timeline.getByLabel('Outcome', { exact: true });
      await expect(outcome).toHaveAttribute('data-state', 'loaded');
      for (const stage of ['OPERATIONAL', 'RETAINED', 'SETTLED', 'SETTLED_REVISED']) {
        await expect(outcome.locator(`[data-stage="${stage}"]`)).toHaveCount(1);
      }
      await expect(outcome.locator('[data-stage="SETTLED_REVISED"]')).toContainText('REGRESSED');
      await expect(outcome.locator('[data-stage="SETTLED_REVISED"]')).toContainText(
        'restatement 2',
      );
      await expect(timeline.getByLabel('Exact prior bid compensation')).toBeVisible();
    } else {
      await timeline.getByRole('button', { name: 'Refresh command and outcome evidence' }).click();
      await expect(timeline).toContainText('Approval has expired', { timeout: 30_000 });
      await expect(timeline).toContainText('observations remain in history');
    }
    await owner.page.screenshot({
      path: testInfo.outputPath(`${scenario}-actual-http-history.png`),
      fullPage: true,
    });
    if (scenario === 'HISTORY_UNKNOWN') {
      const maker = await signIn(browser, request, 'MAKER', scenario);
      const response = await request.get(
        `${API}/api/v1/console/advertising/commands/${maker.data.commandId ?? 'missing'}`,
        { headers: { Authorization: `Bearer ${maker.data.accessToken}` } },
      );
      expect(response.ok()).toBe(true);
      const minimum = (await response.json()) as Readonly<Record<string, unknown>>;
      expect(minimum.disclosureState).toBe('MASKED');
      expect(minimum).not.toHaveProperty('materialityRoute');
      expect(minimum).not.toHaveProperty('candidateBasis');
      await expect(maker.page.getByLabel('Advertising command timeline')).toContainText(
        'UNKNOWN_REQUIRES_READBACK',
      );
      await maker.page.screenshot({
        path: testInfo.outputPath('maker-native-unknown-history-masked.png'),
        fullPage: true,
      });
      await maker.page.context().close();
    }
    await owner.page.context().close();
  });
}

test('real keyboard navigation and scoped HTTP pagination preserve page boundaries and lane resets', async ({
  browser,
  request,
}, testInfo) => {
  const maker = await signIn(browser, request, 'MAKER', 'PAGINATION', true);
  const expected = maker.data.pagination;
  expect(expected).toBeDefined();
  if (expected === undefined) throw new Error('Dedicated pagination seed is required');
  expect(expected.visibleCaseIds).toHaveLength(56);
  expect(expected.watchCaseIds).toHaveLength(53);
  expect(expected.dataRepairCaseIds).toHaveLength(2);
  await expect(maker.page.getByLabel('Advertising case')).toContainText(
    'Синтетическая реклама — страница и клавиатура',
  );

  const acknowledgement = maker.page.waitForResponse(
    (response) =>
      response.url().endsWith(`/tasks/${maker.data.taskId}/acknowledgement`) &&
      response.request().method() === 'POST',
  );
  await activateWithKeyboard(
    maker.page,
    maker.page.getByRole('button', { name: 'Acknowledge responsibility', exact: true }),
  );
  expect((await acknowledgement).status()).toBe(204);
  const workflow = await request.get(
    `${API}/api/v1/console/advertising/cases/${maker.data.caseId}/workflow`,
    { headers: { Authorization: `Bearer ${maker.data.accessToken}` } },
  );
  expect(workflow.ok()).toBe(true);
  const responsibility = (await workflow.json()) as {
    slo: { acknowledgedAt: string | null; firstAttributableActionAt: string | null };
  };
  expect(responsibility.slo.acknowledgedAt).not.toBeNull();
  // Keyboard ACK proves acknowledgement only; it must not start the Action-stage clock.
  expect(responsibility.slo.firstAttributableActionAt).toBeNull();
  await maker.page.screenshot({
    path: testInfo.outputPath('keyboard-acknowledgement-not-action.png'),
    fullPage: true,
  });
  await activateWithKeyboard(
    maker.page,
    maker.page.getByRole('button', { name: 'Back to the advertising queue', exact: true }),
  );

  const visibleRows = maker.page.locator('[data-case-id]');
  const displayedIds = async (): Promise<readonly (string | null)[]> =>
    visibleRows.evaluateAll((rows) => rows.map((row) => row.getAttribute('data-case-id')));
  await expect(visibleRows).toHaveCount(50);
  expect(await displayedIds()).toEqual(expected.visibleCaseIds.slice(0, 50));
  const pages = maker.page.getByRole('navigation', { name: 'Advertising queue pages' });
  await expect(pages.getByRole('button', { name: 'Previous page' })).toBeDisabled();

  const expectQueueRequest = (offset: string, lane: string | null) =>
    maker.page.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        url.pathname === '/api/v1/console/advertising/queue' &&
        url.searchParams.get('limit') === '50' &&
        url.searchParams.get('offset') === offset &&
        url.searchParams.get('lane') === lane &&
        response.request().method() === 'GET'
      );
    });
  const secondPageResponse = expectQueueRequest('50', null);
  await activateWithKeyboard(maker.page, pages.getByRole('button', { name: 'Next page' }));
  const secondPage = await secondPageResponse;
  expect(secondPage.ok()).toBe(true);
  const secondRows = (await secondPage.json()) as readonly { id: string }[];
  expect(secondRows.map((row) => row.id)).toEqual(expected.visibleCaseIds.slice(50));
  await expect(visibleRows).toHaveCount(6);
  expect(await displayedIds()).toEqual(expected.visibleCaseIds.slice(50));
  await expect(pages).toContainText('Page 2');
  await expect(pages.getByRole('button', { name: 'Next page' })).toBeDisabled();
  await maker.page.screenshot({
    path: testInfo.outputPath('keyboard-page-two-six-visible-cases.png'),
    fullPage: true,
  });

  const firstPageResponse = expectQueueRequest('0', null);
  await activateWithKeyboard(maker.page, pages.getByRole('button', { name: 'Previous page' }));
  const firstPage = await firstPageResponse;
  expect(firstPage.ok()).toBe(true);
  const firstRows = (await firstPage.json()) as readonly { id: string }[];
  expect(firstRows.map((row) => row.id)).toEqual(expected.visibleCaseIds.slice(0, 50));
  expect(new Set([...firstRows, ...secondRows].map((row) => row.id)).size).toBe(56);
  await expect(visibleRows).toHaveCount(50);

  const pageAgain = expectQueueRequest('50', null);
  await activateWithKeyboard(maker.page, pages.getByRole('button', { name: 'Next page' }));
  expect((await pageAgain).ok()).toBe(true);
  await expect(visibleRows).toHaveCount(6);
  const watchFirst = expectQueueRequest('0', 'WATCH');
  await activateWithKeyboard(
    maker.page,
    maker.page.getByRole('button', { name: 'WATCH', exact: true }),
  );
  expect((await watchFirst).ok()).toBe(true);
  await expect(visibleRows).toHaveCount(50);
  expect(await displayedIds()).toEqual(expected.watchCaseIds.slice(0, 50));
  await expect(pages).toContainText('Page 1');
  const watchSecond = expectQueueRequest('50', 'WATCH');
  await activateWithKeyboard(maker.page, pages.getByRole('button', { name: 'Next page' }));
  expect((await watchSecond).ok()).toBe(true);
  await expect(visibleRows).toHaveCount(3);
  expect(await displayedIds()).toEqual(expected.watchCaseIds.slice(50));
  await expect(pages.getByRole('button', { name: 'Next page' })).toBeDisabled();
  await maker.page.screenshot({
    path: testInfo.outputPath('keyboard-watch-second-page-three-cases.png'),
    fullPage: true,
  });
  const repairFirst = expectQueueRequest('0', 'DATA_REPAIR');
  await activateWithKeyboard(
    maker.page,
    maker.page.getByRole('button', { name: 'DATA_REPAIR', exact: true }),
  );
  expect((await repairFirst).ok()).toBe(true);
  await expect(visibleRows).toHaveCount(2);
  expect(await displayedIds()).toEqual(expected.dataRepairCaseIds);
  await expect(pages).toContainText('Page 1');
  await expect(pages.getByRole('button', { name: 'Previous page' })).toBeDisabled();
  await expect(pages.getByRole('button', { name: 'Next page' })).toBeDisabled();

  const headers = { Authorization: `Bearer ${maker.data.accessToken}` };
  const complete = await request.get(`${API}/api/v1/console/advertising/queue?limit=200&offset=0`, {
    headers,
  });
  expect(complete.ok()).toBe(true);
  const scoped = (await complete.json()) as readonly { id: string; storeId: string }[];
  expect(scoped.map((row) => row.id)).toEqual(expected.visibleCaseIds);
  expect(scoped.every((row) => row.storeId === maker.data.storeId)).toBe(true);
  // These known fixture IDs are intentional negative inputs, never application-generated links.
  for (const hiddenId of [...expected.hiddenCaseIds, maker.data.foreignCaseId]) {
    expect(hiddenId).toBeDefined();
    const denied = await request.get(
      `${API}/api/v1/console/advertising/cases/${hiddenId ?? 'missing'}`,
      { headers },
    );
    expect([403, 404]).toContain(denied.status());
  }
  await maker.page.context().close();
});
