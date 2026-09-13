import { expect, test } from '@playwright/test';
import type { Browser, BrowserContext, Locator, Page, Response } from '@playwright/test';

const API_ORIGIN = 'http://127.0.0.1:8080';
const CONSOLE_ORIGIN = 'http://127.0.0.1:4173';
const ACTION_PATH = '/api/v1/console/listing/actions';
const BUILD_STORE_ID = '00000000-0000-0000-0000-0000000000d1';
const PROMOTION_NATIVE_KEY = 'browser-official-promotion';
const PROMOTION_TERMS_EVIDENCE = 'evidence://synthetic/browser/promotion-terms';
const PROMOTION_EXIT_AUTHORITY = 'evidence://synthetic/browser/promotion-exit';

interface ListingJourneyFixture {
  readonly storeId: string;
  readonly listingId: string;
  readonly otherListingId: string;
  readonly supersededActionId: string;
  readonly manualActionId: string;
  readonly affectedListingVariantId: string;
  readonly affectedProductVariantId: string;
  readonly authorUserId: string;
  readonly reviewerUserId: string;
  readonly ownerUserId: string;
  readonly authorToken: string;
  readonly reviewerToken: string;
  readonly ownerToken: string;
  readonly revokedToken: string;
  readonly currentText: string;
  readonly targetText: string;
  readonly manualTargetText: string;
}

interface CandidateAnswer {
  readonly id: string;
  readonly storeId: string;
  readonly platformListingId: string;
  readonly state: string;
}

interface ActionAnswer {
  readonly id: string;
  readonly storeId: string;
  readonly platformListingId: string;
  readonly authorUserId: string;
  readonly executionPath: string;
  readonly materialityRoute: string;
  readonly state: string;
  readonly reviews: readonly { readonly reviewerUserId: string; readonly verdict: string }[];
}

interface MeaningBasisAnswer {
  readonly actionId: string;
  readonly ruleState: string;
  readonly currentText: string;
  readonly targetText: string;
  readonly conditions: readonly {
    readonly code: string;
    readonly axis: string;
  }[];
  readonly affectedSet: {
    readonly affectedSetId: string;
    readonly digest: string;
    readonly state: string;
    readonly listingVariantIds: readonly string[];
    readonly productVariantIds: readonly string[];
    readonly nativeScopeObservationId: string;
    readonly identityLineage: string;
  };
  readonly reviewEvidence?: {
    readonly reviewerUserId: string;
    readonly verdict: string;
    readonly factsDigest: string;
    readonly exposureEvidence: string;
  };
  readonly calibrationEvidence: Readonly<Record<string, string>>;
  readonly materialityEvidence: Readonly<Record<string, string>>;
  readonly businessProtectionEvidence: Readonly<Record<string, string>>;
}

interface AllowanceAnswer {
  readonly platformListingId: string;
  readonly resolved: boolean;
  readonly axes: readonly { readonly sufficient: boolean }[];
}

interface LaunchAnswer {
  readonly launched: boolean;
  readonly launchId?: string;
  readonly occupationIds: readonly string[];
  readonly insufficientAxes: readonly string[];
}

interface EvaluationAnswer {
  readonly planId: string;
  readonly actionId: string;
  readonly planDigest: string;
  readonly results: readonly {
    readonly id: string;
    readonly nodeCode: string;
    readonly stage: string;
    readonly verdict: string;
    readonly protectionVerdict: string;
  }[];
}

interface ManualPacketAnswer {
  readonly id: string;
  readonly actionId: string;
  readonly executorUserId: string;
  readonly issuedAt: string;
  readonly state: string;
  readonly reports: readonly { readonly reporterUserId: string; readonly reportState: string }[];
  readonly verifications: readonly {
    readonly verificationBasis: string;
    readonly managementMatch: string;
    readonly displayState: string;
  }[];
}

interface CommandAnswer {
  readonly id: string;
  readonly actionId: string;
  readonly state: string;
  readonly attemptNo: number;
}

interface PromotionEngagementAnswer {
  readonly id: string;
  readonly state: string;
  readonly fullDisclosure: boolean;
  readonly terms: Readonly<Record<string, string>>;
  readonly obligations: Readonly<Record<string, string>>;
}

async function routeIdentityProvider(page: Page, accessToken: string): Promise<void> {
  await page.route('https://id.example.test/authorize*', async (target) => {
    const requested = new URL(target.request().url());
    await target.fulfill({
      status: 302,
      headers: {
        location: `${requested.searchParams.get('redirect_uri') ?? ''}?code=listing-browser-code&state=${encodeURIComponent(requested.searchParams.get('state') ?? '')}`,
      },
      body: '',
    });
  });
  await page.route('https://id.example.test/token', async (target) => {
    await target.fulfill({
      status: 200,
      headers: {
        'content-type': 'application/json',
        'access-control-allow-origin': CONSOLE_ORIGIN,
      },
      body: JSON.stringify({ access_token: accessToken, expires_in: 600 }),
    });
  });
}

function matches(response: Response, method: string, pathname: string): boolean {
  return response.request().method() === method && new URL(response.url()).pathname === pathname;
}

function localDateTime(value: Date): string {
  const part = (number: number, length = 2) => String(number).padStart(length, '0');
  const minute = `${String(value.getFullYear())}-${part(value.getMonth() + 1)}-${part(value.getDate())}T${part(value.getHours())}:${part(value.getMinutes())}`;
  if (value.getSeconds() === 0 && value.getMilliseconds() === 0) return minute;
  const milliseconds = part(value.getMilliseconds(), 3).replace(/0+$/, '');
  return `${minute}:${part(value.getSeconds())}${milliseconds.length === 0 ? '' : `.${milliseconds}`}`;
}

async function fillPromotionTerms(
  form: Locator,
  evidenceLabel = '所观察商业条款的来源',
): Promise<void> {
  await form.getByLabel('促销类型').selectOption('OFFICIAL_PROMOTION_PARTICIPATION');
  await form.getByLabel('平台促销标识').fill(PROMOTION_NATIVE_KEY);
  await form.getByLabel(evidenceLabel).fill(PROMOTION_TERMS_EVIDENCE);
  const terms = form.getByRole('group', { name: '商业条款' });
  await terms.getByLabel('条款名称 1').fill('finalPrice');
  await terms.getByLabel('条款原值 1').fill('200.0000');
  const obligations = form.getByRole('group', { name: '承诺与退出后义务' });
  await obligations.getByLabel('条款名称 1').fill('exit.OWNER_DECISION');
  await obligations.getByLabel('条款原值 1').fill(PROMOTION_EXIT_AUTHORITY);
  await form.getByLabel('是否锁定价格').selectOption('no');
  await form.getByLabel('是否自动参与').selectOption('no');
}

async function recordPromotionContext(
  page: Page,
  region: Locator,
  listingId: string,
  participationState: 'PARTICIPATING' | 'NOT_PARTICIPATING',
  effectiveFrom: string,
  effectiveTo: string,
  originalAuthorityUntil: string,
): Promise<string> {
  const healthQueue = region.getByRole('region', { name: '健康队列' });
  await healthQueue
    .locator(`[data-listing="${listingId}"]`)
    .getByRole('button', { name: '打开' })
    .click();
  const form = region.getByRole('form', { name: '记录促销参与观察' });
  await form.getByLabel('本次已观察到完整商业条款').check();
  await fillPromotionTerms(form);
  await form.getByLabel('观察到的参与状态').selectOption(participationState);
  await form.getByLabel('实际观察时间（本地时间）').fill(localDateTime(new Date()));
  await form
    .getByLabel('本次观察的证据引用')
    .fill(
      participationState === 'PARTICIPATING'
        ? 'evidence://synthetic/browser/promotion-current'
        : 'evidence://synthetic/browser/promotion-stopped',
    );
  await form.getByLabel('本次来源是完整促销枚举').check();
  await form.getByLabel('枚举覆盖开始').fill(localDateTime(new Date(Date.now() - 3_600_000)));
  await form.getByLabel('枚举覆盖结束').fill(localDateTime(new Date(Date.now() + 3_600_000)));
  await form.getByLabel('枚举核验有效期至').fill(localDateTime(new Date(Date.now() + 3_600_000)));
  await form.getByLabel('活动生效开始').fill(effectiveFrom);
  await form.getByLabel('活动生效结束').fill(effectiveTo);
  await form
    .getByLabel('新增交易状态')
    .selectOption(participationState === 'PARTICIPATING' ? 'OPEN' : 'STOPPED');
  await form.getByLabel('存量义务状态').selectOption('OUTSTANDING');
  if (participationState === 'PARTICIPATING') {
    await form.getByLabel('原商业权威引用').fill(PROMOTION_EXIT_AUTHORITY);
    await form.getByLabel('原商业权威有效期至').fill(originalAuthorityUntil);
    const concurrent = form.getByRole('group', { name: '并发 Listing 数' });
    await concurrent.getByLabel('数值').fill('1');
    await concurrent.getByLabel('单位').fill('COUNT');
    await concurrent
      .getByLabel('轴证据引用')
      .fill('evidence://synthetic/browser/promotion-concurrent');
  }
  const observationPath = `/api/v1/console/listing/health/listings/${listingId}/facts/promotion`;
  const [response] = await Promise.all([
    page.waitForResponse((candidate) => matches(candidate, 'POST', observationPath)),
    form.getByRole('button', { name: '提交' }).click(),
  ]);
  expect(response.status()).toBe(200);
  const answer = (await response.json()) as { readonly observationId: string };
  expect(answer.observationId).toBeTruthy();
  return answer.observationId;
}

async function openListingConsole(page: Page, token: string): Promise<void> {
  await routeIdentityProvider(page, token);
  await page.goto('/');
  await page.getByRole('button', { name: 'Continue to sign in' }).click();
  await page
    .getByRole('button', { name: 'Listing 转化与内容 / Конверсия и контент карточек' })
    .click();
  await expect(page.getByRole('region', { name: 'Listing 转化与内容' })).toBeVisible();
}

async function newListingContext(
  browser: Browser,
  token: string,
): Promise<{ readonly context: BrowserContext; readonly page: Page }> {
  const context = await browser.newContext({ baseURL: CONSOLE_ORIGIN });
  const page = await context.newPage();
  await openListingConsole(page, token);
  return { context, page };
}

/**
 * A bounded real-network loop through the isolated listing graph.
 *
 * Only the fictional identity provider is answered in the page. Every listing
 * response and every state transition below comes from Spring and PostgreSQL.
 */
test('TC-BROWSER-017 real bilingual listing decision and closed-write journey', async ({
  page,
  browser,
}) => {
  const fixtureResponse = await page.request.get('http://127.0.0.1:8082/fixture');
  expect(fixtureResponse.ok()).toBe(true);
  const fixture = (await fixtureResponse.json()) as {
    readonly listingJourney: ListingJourneyFixture;
  };
  const listing = fixture.listingJourney;
  expect(listing.storeId).not.toBe(BUILD_STORE_ID);
  expect(new Set([listing.authorUserId, listing.reviewerUserId, listing.ownerUserId]).size).toBe(3);

  const revoked = await page.request.get(`${API_ORIGIN}/api/v1/console/listing/health/queue`, {
    headers: { Authorization: `Bearer ${listing.revokedToken}` },
  });
  expect(revoked.status()).toBe(200);
  expect(await revoked.json()).toEqual([]);

  const cancelled = await page.request.post(
    `${API_ORIGIN}${ACTION_PATH}/${listing.supersededActionId}/cancel`,
    {
      headers: { Authorization: `Bearer ${listing.authorToken}` },
      data: { reason: 'Withdraw the unused synthetic action before the browser decision round' },
    },
  );
  expect(cancelled.status()).toBe(200);
  expect(await cancelled.json()).toEqual({ state: 'CANCELLED' });

  await openListingConsole(page, listing.authorToken);
  const chinese = page.getByRole('region', { name: 'Listing 转化与内容' });
  const healthQueue = chinese.getByRole('region', { name: '健康队列' });
  const listingRow = healthQueue.locator(`[data-listing="${listing.listingId}"]`);
  await expect(listingRow).toContainText('fictional-listing');
  await listingRow.getByRole('button', { name: '打开' }).click();
  await expect(chinese.locator(`[data-listing="${listing.listingId}"]`)).toContainText(
    'fictional-listing',
  );
  await chinese.getByRole('button', { name: '候选', exact: true }).click();

  const candidates = chinese.getByRole('region', { name: '候选' });
  const candidateForm = candidates.locator('form').first();
  await candidateForm.getByLabel('round').fill('browser-real-round');
  await candidateForm.getByLabel('证据引用').fill('evidence://synthetic/browser/source');
  const candidatePath = `${ACTION_PATH}/candidates`;
  const [candidateResponse] = await Promise.all([
    page.waitForResponse((response) => matches(response, 'POST', candidatePath)),
    candidateForm.getByRole('button', { name: '提交' }).click(),
  ]);
  expect(candidateResponse.status()).toBe(200);
  const candidate = (await candidateResponse.json()) as CandidateAnswer;
  expect(candidate).toMatchObject({
    storeId: listing.storeId,
    platformListingId: listing.listingId,
    state: 'OPEN',
  });

  const candidateRow = candidates.locator(`[data-candidate="${candidate.id}"]`);
  const preparation = candidateRow.getByRole('form', { name: candidate.id });
  await preparation.getByLabel('目标俄语描述（完整文本）').fill(listing.targetText);
  const preparePath = `${candidatePath}/${candidate.id}/prepare`;
  const [missingMaterialResponse] = await Promise.all([
    page.waitForResponse((response) => matches(response, 'POST', preparePath)),
    preparation.getByRole('button', { name: '提交' }).click(),
  ]);
  expect(missingMaterialResponse.status()).toBeGreaterThanOrEqual(400);
  expect(missingMaterialResponse.status()).toBeLessThan(500);
  const candidatesAfterRefusal = await page.request.get(
    `${API_ORIGIN}${candidatePath}?listingId=${listing.listingId}`,
    { headers: { Authorization: `Bearer ${listing.authorToken}` } },
  );
  expect(candidatesAfterRefusal.ok()).toBe(true);
  const unchangedCandidates = (await candidatesAfterRefusal.json()) as readonly CandidateAnswer[];
  expect(unchangedCandidates.find((item) => item.id === candidate.id)?.state).toBe('OPEN');

  await preparation.getByLabel('КИЗ 标记声明').selectOption('no');
  const [preparedResponse] = await Promise.all([
    page.waitForResponse((response) => matches(response, 'POST', preparePath)),
    preparation.getByRole('button', { name: '提交' }).click(),
  ]);
  expect(preparedResponse.status()).toBe(200);
  const action = (await preparedResponse.json()) as ActionAnswer;
  expect(action).toMatchObject({
    storeId: listing.storeId,
    platformListingId: listing.listingId,
    authorUserId: listing.authorUserId,
    executionPath: 'API',
    state: 'DRAFT',
  });
  const authorAction = chinese.locator(`[data-action="${action.id}"]`);
  await expect(
    authorAction.locator('[data-family="actionState"][data-code="DRAFT"]'),
  ).toBeVisible();
  await expect(authorAction).toContainText(listing.targetText);

  const promotionEffectiveFrom = localDateTime(new Date(Date.now() - 3_600_000));
  const promotionEffectiveTo = localDateTime(new Date(Date.now() + 86_400_000));
  const promotionAuthorityUntil = localDateTime(new Date(Date.now() + 7_200_000));
  let initialPromotionObservationId: string;
  const reviewerSession = await newListingContext(browser, listing.reviewerToken);
  try {
    const reviewerChinese = reviewerSession.page.getByRole('region', {
      name: 'Listing 转化与内容',
    });
    initialPromotionObservationId = await recordPromotionContext(
      reviewerSession.page,
      reviewerChinese,
      listing.listingId,
      'PARTICIPATING',
      promotionEffectiveFrom,
      promotionEffectiveTo,
      promotionAuthorityUntil,
    );
    await reviewerChinese.getByRole('button', { name: '操作与启动' }).click();
    const reviewerActions = reviewerChinese.getByRole('region', { name: '操作' });
    const manualRow = reviewerActions.locator(`[data-action="${listing.manualActionId}"]`);
    await expect(
      manualRow.locator('[data-family="executionPath"][data-code="MANUAL"]'),
    ).toBeVisible();
    const actionRow = reviewerActions.locator(`[data-action="${action.id}"]`);
    await actionRow.getByRole('button', { name: '打开' }).click();
    const reviewerAction = reviewerChinese.locator(`[data-action="${action.id}"]`);
    await reviewerAction.getByLabel('理由').fill('逐项核对完整俄语文本与当前有效条件');

    const meaning = reviewerAction.getByRole('region', { name: '读取含义审核依据' });
    const basisPath = `${ACTION_PATH}/${action.id}/review-basis`;
    const [basisResponse] = await Promise.all([
      reviewerSession.page.waitForResponse((response) => matches(response, 'GET', basisPath)),
      meaning.getByRole('button', { name: '读取含义审核依据' }).click(),
    ]);
    expect(basisResponse.status()).toBe(200);
    const basis = (await basisResponse.json()) as MeaningBasisAnswer;
    expect(basis).toMatchObject({
      actionId: action.id,
      ruleState: 'QUALIFIED',
      currentText: listing.currentText,
      targetText: listing.targetText,
    });
    expect(basis.conditions.map((condition) => condition.code)).toEqual(
      expect.arrayContaining(['LIMITED_CLARIFICATION', 'SAFETY_OR_PRODUCT_FACT_CHANGE']),
    );
    for (const [index, condition] of basis.conditions.entries()) {
      await meaning
        .getByLabel(condition.code)
        .selectOption(condition.axis === 'MATERIAL' ? 'APPLIES' : 'DOES_NOT_APPLY');
      await meaning
        .getByLabel(`条件判断依据 ${String(index + 1)}`)
        .fill(`完整文本条件 ${condition.code} 已独立核对`);
    }
    await meaning
      .getByLabel('含义审核证据引用')
      .fill('evidence://synthetic/browser/independent-meaning');
    await meaning.getByLabel('我已审阅完整文本或商业条款并覆盖所有条件').check();

    await reviewerChinese.getByRole('button', { name: 'Русский' }).click();
    const reviewerRussian = reviewerSession.page.getByRole('region', {
      name: 'Конверсия и контент карточек',
    });
    const russianAction = reviewerRussian.locator(`[data-action="${action.id}"]`);
    await expect(russianAction).toContainText(listing.currentText);
    await expect(russianAction).toContainText(listing.targetText);
    const reviewPath = `${ACTION_PATH}/${action.id}/review`;
    const [reviewResponse] = await Promise.all([
      reviewerSession.page.waitForResponse((response) => matches(response, 'POST', reviewPath)),
      russianAction.getByRole('button', { name: 'Подтвердить (независимая проверка)' }).click(),
    ]);
    expect(reviewResponse.status()).toBe(200);
    const reviewed = (await reviewResponse.json()) as ActionAnswer;
    expect(reviewed.state).toBe('REVIEWED');
    expect(reviewed.materialityRoute).toBe('MATERIAL_IMPACT');
    expect(reviewed.authorUserId).toBe(listing.authorUserId);
    expect(listing.reviewerUserId).not.toBe(listing.authorUserId);
    expect(reviewed.reviews).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ reviewerUserId: listing.reviewerUserId, verdict: 'ATTESTED' }),
      ]),
    );
    await expect(
      russianAction.locator('[data-family="actionState"][data-code="REVIEWED"]'),
    ).toBeVisible();
  } finally {
    await reviewerSession.context.close();
  }
  expect(initialPromotionObservationId).toBeTruthy();

  const ownerSession = await newListingContext(browser, listing.ownerToken);
  try {
    const ownerPage = ownerSession.page;
    const ownerChinese = ownerPage.getByRole('region', { name: 'Listing 转化与内容' });
    await ownerChinese.getByRole('button', { name: '操作与启动' }).click();
    const ownerActions = ownerChinese.getByRole('region', { name: '操作' });
    await expect(
      ownerActions
        .locator(`[data-action="${listing.manualActionId}"]`)
        .locator('[data-family="executionPath"][data-code="MANUAL"]'),
    ).toBeVisible();
    const ownerRow = ownerActions.locator(`[data-action="${action.id}"]`);
    await ownerRow.getByRole('button', { name: '打开' }).click();
    const ownerAction = ownerChinese.locator(`[data-action="${action.id}"]`);
    await expect(
      ownerAction.locator('[data-family="actionState"][data-code="REVIEWED"]'),
    ).toBeVisible();
    await ownerAction.getByLabel('理由').fill('批准已独立核对的重大影响变更');

    const approvalMaterials = ownerAction.getByRole('region', { name: '最终批准材料' });
    const approvalButton = ownerAction.getByRole('button', { name: '批准（工作流决定）' });
    await expect(approvalButton).toBeDisabled();
    const [ownerBasisResponse] = await Promise.all([
      ownerPage.waitForResponse((response) =>
        matches(response, 'GET', `${ACTION_PATH}/${action.id}/review-basis`),
      ),
      approvalMaterials.getByRole('button', { name: '读取最终批准材料' }).click(),
    ]);
    expect(ownerBasisResponse.status()).toBe(200);
    const ownerBasis = (await ownerBasisResponse.json()) as MeaningBasisAnswer;
    expect(ownerBasis).toMatchObject({
      actionId: action.id,
      currentText: listing.currentText,
      targetText: listing.targetText,
      affectedSet: {
        state: 'COMPLETE',
        listingVariantIds: [listing.affectedListingVariantId],
        productVariantIds: [listing.affectedProductVariantId],
      },
      reviewEvidence: {
        reviewerUserId: listing.reviewerUserId,
        verdict: 'ATTESTED',
      },
      materialityEvidence: { state: 'CURRENT' },
      businessProtectionEvidence: { model: 'LC_CURRENT_BUSINESS_PROTECTION_1' },
    });
    expect(ownerBasis.affectedSet.identityLineage).toContain(listing.affectedListingVariantId);
    expect(ownerBasis.reviewEvidence?.exposureEvidence).toContain('QUALIFIED');
    await expect(approvalMaterials).toContainText(listing.currentText);
    await expect(approvalMaterials).toContainText(listing.affectedListingVariantId);
    await expect(approvalMaterials).toContainText(listing.affectedProductVariantId);
    await expect(approvalMaterials).toContainText('LC_CURRENT_BUSINESS_PROTECTION_1');
    await expect(approvalButton).toBeEnabled();

    const approvalResponsePromise = ownerPage.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname.startsWith('/api/v1/console/workflow/recommendations/') &&
        new URL(response.url()).pathname.endsWith('/approval'),
    );
    await approvalButton.click();
    const approvalResponse = await approvalResponsePromise;
    expect(approvalResponse.status()).toBe(200);
    await expect(
      ownerAction.locator('[data-family="actionState"][data-code="APPROVED"]'),
    ).toBeVisible();

    await ownerChinese.getByRole('button', { name: '批次、遏制与队列' }).click();
    const initialGovernance = ownerChinese.getByRole('region', { name: '批次、遏制与队列' });
    await expect(initialGovernance).toHaveAttribute('data-state', 'loaded');
    const batchForm = initialGovernance.getByRole('form', { name: '批次' });
    await batchForm.getByLabel('批次所属店铺 ID').fill(listing.storeId);
    await batchForm.getByLabel('批次', { exact: true }).fill('browser-partial-launch');
    await batchForm.getByRole('button', { name: '提交' }).click();
    let batch = initialGovernance
      .locator('[data-batch]')
      .filter({ hasText: 'browser-partial-launch' });
    await expect(batch).toBeVisible();
    await batch.getByLabel('操作').fill(action.id);
    await batch.getByRole('button', { name: '成员' }).click();
    batch = initialGovernance.locator('[data-batch]').filter({ hasText: 'browser-partial-launch' });
    await expect(batch).toContainText(action.id);
    await batch.getByLabel('操作').fill(listing.manualActionId);
    await batch.getByRole('button', { name: '成员' }).click();
    batch = initialGovernance.locator('[data-batch]').filter({ hasText: 'browser-partial-launch' });
    await expect(batch).toContainText(listing.manualActionId);

    await ownerChinese.getByRole('button', { name: '操作与启动' }).click();
    const refreshedOwnerActions = ownerChinese.getByRole('region', { name: '操作' });
    await refreshedOwnerActions
      .locator(`[data-action="${action.id}"]`)
      .getByRole('button', {
        name: '打开',
      })
      .click();
    const refreshedOwnerAction = ownerChinese.locator(`[data-action="${action.id}"]`);

    const allowancePath = `${ACTION_PATH}/${action.id}/allowance-preview`;
    const [allowanceResponse] = await Promise.all([
      ownerPage.waitForResponse((response) => matches(response, 'POST', allowancePath)),
      refreshedOwnerAction.getByRole('button', { name: '额度预览' }).click(),
    ]);
    expect(allowanceResponse.status()).toBe(200);
    const allowance = (await allowanceResponse.json()) as AllowanceAnswer;
    expect(allowance.platformListingId).toBe(listing.listingId);
    expect(allowance.resolved).toBe(true);
    expect(allowance.axes.length).toBeGreaterThan(0);
    expect(allowance.axes.every((axis) => axis.sufficient)).toBe(true);
    await expect(refreshedOwnerAction.getByRole('table', { name: '额度预览' })).toHaveAttribute(
      'data-resolved',
      'true',
    );

    const launchPath = `${ACTION_PATH}/${action.id}/launch`;
    const [launchResponse] = await Promise.all([
      ownerPage.waitForResponse((response) => matches(response, 'POST', launchPath)),
      refreshedOwnerAction.getByRole('button', { name: '启动（获取额度）' }).click(),
    ]);
    expect(launchResponse.status()).toBe(200);
    const launch = (await launchResponse.json()) as LaunchAnswer;
    expect(launch.launched).toBe(true);
    expect(launch.launchId).toBeTruthy();
    expect(launch.occupationIds.length).toBeGreaterThan(0);
    await expect(refreshedOwnerAction.locator('[data-launched="true"]')).toBeVisible();
    await expect(
      refreshedOwnerAction.locator('[data-family="actionState"][data-code="LAUNCHED"]'),
    ).toBeVisible();

    await refreshedOwnerAction.getByRole('button', { name: '← 操作' }).click();
    const manualRow = ownerChinese
      .getByRole('region', { name: '操作' })
      .locator(`[data-action="${listing.manualActionId}"]`);
    await manualRow.getByRole('button', { name: '打开' }).click();
    const manualAction = ownerChinese.locator(`[data-action="${listing.manualActionId}"]`);
    await expect(
      manualAction.locator('[data-family="materialityRoute"][data-code="ORDINARY_IMPACT"]'),
    ).toBeVisible();
    const manualLaunchPath = `${ACTION_PATH}/${listing.manualActionId}/launch`;
    const [manualLaunchResponse] = await Promise.all([
      ownerPage.waitForResponse((response) => matches(response, 'POST', manualLaunchPath)),
      manualAction.getByRole('button', { name: '启动（获取额度）' }).click(),
    ]);
    expect(manualLaunchResponse.status()).toBe(200);
    const manualLaunch = (await manualLaunchResponse.json()) as LaunchAnswer;
    expect(manualLaunch.launched).toBe(false);
    expect(manualLaunch.occupationIds).toHaveLength(0);
    expect(manualLaunch.insufficientAxes).toContain('CONCURRENT_LISTINGS');
    await expect(manualAction.locator('[data-launched="false"]')).toBeVisible();
    await expect(
      manualAction.locator('[data-family="actionState"][data-code="APPROVED_NOT_LAUNCHABLE"]'),
    ).toBeVisible();

    await ownerChinese.getByRole('button', { name: '批次、遏制与队列' }).click();
    const partialGovernance = ownerChinese.getByRole('region', { name: '批次、遏制与队列' });
    const partialBatch = partialGovernance
      .locator('[data-batch]')
      .filter({ hasText: 'browser-partial-launch' });
    await expect(partialBatch.locator('[data-code="LAUNCHED"]')).toHaveCount(1);
    await expect(partialBatch.locator('[data-code="APPROVED_NOT_LAUNCHABLE"]')).toHaveCount(1);

    const expandedAllowance = await ownerPage.request.post(
      'http://127.0.0.1:8082/listing/allowance/expand',
      { headers: { 'X-Fixture-Driver': 'browser-test' } },
    );
    expect(expandedAllowance.status()).toBe(200);
    expect(await expandedAllowance.json()).toMatchObject({ state: 'EXPANDED', limit: 2 });

    await ownerChinese.getByRole('button', { name: '操作与启动' }).click();
    await ownerChinese
      .getByRole('region', { name: '操作' })
      .locator(`[data-action="${listing.manualActionId}"]`)
      .getByRole('button', { name: '打开' })
      .click();
    const expandedManualAction = ownerChinese.locator(`[data-action="${listing.manualActionId}"]`);
    const [expandedManualLaunchResponse] = await Promise.all([
      ownerPage.waitForResponse((response) => matches(response, 'POST', manualLaunchPath)),
      expandedManualAction.getByRole('button', { name: '启动（获取额度）' }).click(),
    ]);
    expect(expandedManualLaunchResponse.status()).toBe(200);
    const expandedManualLaunch = (await expandedManualLaunchResponse.json()) as LaunchAnswer;
    expect(expandedManualLaunch.launched).toBe(true);
    expect(expandedManualLaunch.launchId).toBeTruthy();
    expect(expandedManualLaunch.occupationIds.length).toBeGreaterThan(0);
    await expect(expandedManualAction.locator('[data-launched="true"]')).toBeVisible();

    await ownerChinese.getByRole('button', { name: '人工路径与促销' }).click();
    const ownerManualPanel = ownerChinese.getByRole('region', { name: '人工执行包' });
    const issueForm = ownerManualPanel.getByRole('form', { name: '执行人' });
    await issueForm.getByLabel('操作').fill(listing.manualActionId);
    await issueForm.getByLabel('执行人').fill(listing.authorUserId);
    const packetPath = `/api/v1/console/listing/manual/actions/${listing.manualActionId}/packets`;
    const [packetResponse] = await Promise.all([
      ownerPage.waitForResponse((response) => matches(response, 'POST', packetPath)),
      issueForm.getByRole('button', { name: '提交' }).click(),
    ]);
    expect(packetResponse.status()).toBe(200);
    const packet = (await packetResponse.json()) as ManualPacketAnswer;
    expect(packet).toMatchObject({
      actionId: listing.manualActionId,
      executorUserId: listing.authorUserId,
      state: 'ISSUED',
    });

    const executorSession = await newListingContext(browser, listing.authorToken);
    try {
      const executorChinese = executorSession.page.getByRole('region', {
        name: 'Listing 转化与内容',
      });
      await executorChinese.getByRole('button', { name: '人工路径与促销' }).click();
      const executorPacket = executorChinese.locator(`[data-packet="${packet.id}"]`);
      await expect(executorPacket).toHaveAttribute('data-packet-state', 'ISSUED');
      const reportForm = executorPacket.getByRole('form', { name: `报告执行 ${packet.id}` });
      await reportForm
        .getByLabel('操作时间')
        .fill(new Date(new Date(packet.issuedAt).getTime() + 1).toISOString());
      await reportForm.getByLabel('备注').fill('执行人按批准包完成管理端描述更新');
      const reportPath = `/api/v1/console/listing/manual/packets/${packet.id}/report`;
      const [reportResponse] = await Promise.all([
        executorSession.page.waitForResponse((response) => matches(response, 'POST', reportPath)),
        reportForm.getByRole('button', { name: '报告执行' }).click(),
      ]);
      expect(reportResponse.status()).toBe(200);
      const reported = (await reportResponse.json()) as ManualPacketAnswer;
      expect(reported.state).toBe('REPORTED');
      expect(reported.reports).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            reporterUserId: listing.authorUserId,
            reportState: 'APPLIED',
          }),
        ]),
      );
    } finally {
      await executorSession.context.close();
    }

    const manualVerifier = await newListingContext(browser, listing.reviewerToken);
    try {
      const verifierPage = manualVerifier.page;
      const verifierChinese = verifierPage.getByRole('region', { name: 'Listing 转化与内容' });
      const verifierQueue = verifierChinese.getByRole('region', { name: '健康队列' });
      await verifierQueue
        .locator(`[data-listing="${listing.otherListingId}"]`)
        .getByRole('button', { name: '打开' })
        .click();
      const listingDetail = verifierChinese.locator(`[data-listing="${listing.otherListingId}"]`);
      const descriptionForm = listingDetail.getByRole('form', {
        name: '目标俄语描述（完整文本）',
      });
      await descriptionForm.getByLabel('目标俄语描述（完整文本）').fill(listing.manualTargetText);
      await descriptionForm.getByLabel('КИЗ 标记声明').selectOption('no');
      const descriptionPath = `/api/v1/console/listing/health/listings/${listing.otherListingId}/facts/description`;
      const [descriptionResponse] = await Promise.all([
        verifierPage.waitForResponse((response) => matches(response, 'POST', descriptionPath)),
        descriptionForm.getByRole('button', { name: '提交' }).click(),
      ]);
      expect(descriptionResponse.status()).toBe(200);
      const descriptionObservation = (await descriptionResponse.json()) as {
        readonly observationId: string;
      };

      const displayForm = listingDetail.getByRole('form', { name: '证据引用', exact: true });
      await displayForm.locator('select').selectOption('DISPLAYED');
      await displayForm.getByLabel('目标俄语描述（完整文本）').fill(listing.manualTargetText);
      await displayForm
        .getByLabel('证据引用', { exact: true })
        .fill('evidence://synthetic/browser/manual-display');
      const displayPath = `/api/v1/console/listing/health/listings/${listing.otherListingId}/facts/display`;
      const [displayResponse] = await Promise.all([
        verifierPage.waitForResponse((response) => matches(response, 'POST', displayPath)),
        displayForm.getByRole('button', { name: '提交' }).click(),
      ]);
      expect(displayResponse.status()).toBe(200);
      const displayObservation = (await displayResponse.json()) as {
        readonly observationId: string;
      };

      await verifierChinese.getByRole('button', { name: '人工路径与促销' }).click();
      const verifierManualPanel = verifierChinese.getByRole('region', { name: '人工执行包' });
      const lookupForm = verifierManualPanel.getByRole('form', { name: '按行动读取执行包' });
      await lookupForm.getByLabel('操作').fill(listing.manualActionId);
      const [packetLookupResponse] = await Promise.all([
        verifierPage.waitForResponse((response) => matches(response, 'GET', packetPath)),
        lookupForm.getByRole('button', { name: '打开' }).click(),
      ]);
      expect(packetLookupResponse.status()).toBe(200);
      const verifierPacket = verifierManualPanel.locator(`[data-packet="${packet.id}"]`);
      await expect(verifierPacket).toHaveAttribute('data-packet-state', 'REPORTED');
      const verifyForm = verifierPacket.getByRole('form', { name: `独立核实 ${packet.id}` });
      await verifyForm.getByLabel('管理端正文观察编号').fill(descriptionObservation.observationId);
      await verifyForm.getByLabel('买家端展示观察编号').fill(displayObservation.observationId);
      await verifyForm.getByLabel('备注').fill('独立人员核对管理端正文与买家端实际展示');
      const verifyPath = `/api/v1/console/listing/manual/packets/${packet.id}/verify`;
      const [verifyResponse] = await Promise.all([
        verifierPage.waitForResponse((response) => matches(response, 'POST', verifyPath)),
        verifyForm.getByRole('button', { name: '独立核实' }).click(),
      ]);
      expect(verifyResponse.status()).toBe(200);
      const verified = (await verifyResponse.json()) as ManualPacketAnswer;
      expect(verified.state).toBe('VERIFIED');
      expect(verified.verifications).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            verificationBasis: 'INDEPENDENT_HUMAN',
            managementMatch: 'MATCHED_TARGET',
            displayState: 'DISPLAYED',
          }),
        ]),
      );
    } finally {
      await manualVerifier.context.close();
    }

    await ownerChinese.getByRole('button', { name: '操作与启动' }).click();
    await ownerChinese
      .getByRole('region', { name: '操作' })
      .locator(`[data-action="${action.id}"]`)
      .getByRole('button', { name: '打开' })
      .click();
    const completedOwnerAction = ownerChinese.locator(`[data-action="${action.id}"]`);

    const evaluationPath = `${ACTION_PATH}/${action.id}/evaluation`;
    const [evaluationResponse] = await Promise.all([
      ownerPage.waitForResponse((response) => matches(response, 'GET', evaluationPath)),
      completedOwnerAction.getByRole('button', { name: '评估计划与结果' }).click(),
    ]);
    expect(evaluationResponse.status()).toBe(200);
    let evaluation = (await evaluationResponse.json()) as EvaluationAnswer;
    expect(evaluation.actionId).toBe(action.id);
    expect(evaluation.planDigest).toMatch(/^[0-9a-f]{64}$/u);
    await expect(completedOwnerAction.locator(`[data-plan="${evaluation.planId}"]`)).toBeVisible();

    const nodeEvaluationPath = `${evaluationPath}/nodes`;
    const evaluationForm = completedOwnerAction.getByRole('form', { name: '记录评估结果' });
    await evaluationForm.getByLabel('形式节点').selectOption('D14');
    await evaluationForm.getByLabel('评估阶段').selectOption('OPERATIONAL');
    const [nodeEvaluationResponse] = await Promise.all([
      ownerPage.waitForResponse((response) => matches(response, 'POST', nodeEvaluationPath)),
      evaluationForm.getByRole('button', { name: '记录评估结果' }).click(),
    ]);
    expect(nodeEvaluationResponse.status()).toBe(200);
    evaluation = (await nodeEvaluationResponse.json()) as EvaluationAnswer;
    expect(evaluation.results).toHaveLength(1);
    const evaluationResult = evaluation.results[0];
    if (evaluationResult === undefined) throw new Error('evaluation result was not returned');
    expect(evaluationResult).toMatchObject({ nodeCode: 'D14', stage: 'OPERATIONAL' });
    await expect(
      completedOwnerAction.locator(`[id="evaluation-result-${evaluationResult.id}"]`),
    ).toHaveAttribute('data-verdict', evaluationResult.verdict);

    const commandPath = `/api/v1/console/listing-description-commands/actions/${action.id}`;
    const commandResponsePromise = ownerPage.waitForResponse((response) =>
      matches(response, 'GET', commandPath),
    );
    const gateResponsePromise = ownerPage.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        /^\/api\/v1\/console\/listing-description-commands\/[^/]+\/gate$/u.test(
          new URL(response.url()).pathname,
        ),
    );
    await completedOwnerAction.getByRole('button', { name: '描述写入命令' }).click();
    const commandResponse = await commandResponsePromise;
    expect(commandResponse.status()).toBe(200);
    const command = (await commandResponse.json()) as CommandAnswer;
    expect(command).toMatchObject({ actionId: action.id, state: 'PENDING', attemptNo: 0 });
    const gateResponse = await gateResponsePromise;
    expect(gateResponse.status()).toBe(200);
    const gate = (await gateResponse.json()) as { readonly reasons: readonly string[] };
    expect(gate.reasons).toContain('PRODUCTION_WRITE_DISABLED');
    const commandTimeline = completedOwnerAction.locator(
      `[data-command="${command.id}"][data-command-state="PENDING"]`,
    );
    await expect(commandTimeline).toContainText('生产写入未启用');

    await ownerChinese.getByRole('button', { name: '人工路径与促销' }).click();
    const manualPanel = ownerChinese.getByRole('region', { name: '人工执行包' });
    const adoption = manualPanel.getByRole('form', { name: '纳管存量促销' });
    await adoption.getByLabel('Listing').fill(listing.listingId);
    await fillPromotionTerms(adoption, '证据引用');
    await adoption.getByLabel('完整促销上下文观测 ID').fill(initialPromotionObservationId);
    await adoption.getByLabel('原商业权威引用').fill(PROMOTION_EXIT_AUTHORITY);
    await adoption.getByLabel('原商业权威有效期至').fill(promotionAuthorityUntil);
    await adoption.getByLabel('当前责任人 ID').fill(listing.ownerUserId);
    const adoptionPath = `/api/v1/console/listing/manual/listings/${listing.listingId}/engagements`;
    const [adoptionResponse] = await Promise.all([
      ownerPage.waitForResponse((response) => matches(response, 'POST', adoptionPath)),
      adoption.getByRole('button', { name: '提交' }).click(),
    ]);
    expect(adoptionResponse.status()).toBe(200);
    const adopted = (await adoptionResponse.json()) as PromotionEngagementAnswer;
    expect(adopted).toMatchObject({
      state: 'ACTIVE',
      fullDisclosure: true,
      terms: { finalPrice: '200.0000' },
      obligations: { 'exit.OWNER_DECISION': PROMOTION_EXIT_AUTHORITY },
    });
    let engagement = manualPanel.locator(`[data-engagement="${adopted.id}"]`);
    await expect(engagement).toHaveAttribute('data-engagement-state', 'ACTIVE');
    await expect(engagement).toContainText('200.0000');
    await expect(engagement).toContainText(PROMOTION_TERMS_EVIDENCE);
    await engagement.getByLabel('退出权威引用').fill(PROMOTION_EXIT_AUTHORITY);
    await engagement.getByLabel('当前证据 ID').fill(initialPromotionObservationId);
    const exitPath = `/api/v1/console/listing/manual/engagements/${adopted.id}/exit`;
    const [exitResponse] = await Promise.all([
      ownerPage.waitForResponse((response) => matches(response, 'POST', exitPath)),
      engagement.getByRole('button', { name: '授权退出' }).click(),
    ]);
    expect(exitResponse.status()).toBe(200);
    expect(((await exitResponse.json()) as PromotionEngagementAnswer).state).toBe('EXITING');
    engagement = manualPanel.locator(`[data-engagement="${adopted.id}"]`);
    await expect(engagement).toHaveAttribute('data-engagement-state', 'EXITING');

    const promotionVerifier = await newListingContext(browser, listing.reviewerToken);
    let stoppedPromotionObservationId = '';
    try {
      stoppedPromotionObservationId = await recordPromotionContext(
        promotionVerifier.page,
        promotionVerifier.page.getByRole('region', { name: 'Listing 转化与内容' }),
        listing.listingId,
        'NOT_PARTICIPATING',
        promotionEffectiveFrom,
        promotionEffectiveTo,
        promotionAuthorityUntil,
      );
    } finally {
      await promotionVerifier.context.close();
    }
    await engagement.getByLabel('当前观测 ID').fill(stoppedPromotionObservationId);
    await engagement.getByLabel('证据引用').fill('evidence://synthetic/browser/promotion-stopped');
    const releasePath = `/api/v1/console/listing/manual/engagements/${adopted.id}/release`;
    const [releaseResponse] = await Promise.all([
      ownerPage.waitForResponse((response) => matches(response, 'POST', releasePath)),
      engagement.getByRole('button', { name: '释放' }).click(),
    ]);
    expect(releaseResponse.status()).toBe(200);
    expect(((await releaseResponse.json()) as PromotionEngagementAnswer).state).toBe('STOPPED');
    await expect(manualPanel.locator(`[data-engagement="${adopted.id}"]`)).toHaveAttribute(
      'data-engagement-state',
      'STOPPED',
    );

    await ownerChinese.getByRole('button', { name: '批次、遏制与队列' }).click();
    const governance = ownerChinese.getByRole('region', { name: '批次、遏制与队列' });
    await expect(governance).toHaveAttribute('data-state', 'loaded');
    await expect(governance).toContainText(listing.listingId);
    const completedBatch = governance
      .locator('[data-batch]')
      .filter({ hasText: 'browser-partial-launch' });
    await expect(completedBatch).toContainText(action.id);
    await expect(completedBatch).toContainText(listing.manualActionId);
    await expect(completedBatch.locator('[data-code="LAUNCHED"]')).toHaveCount(1);
    await expect(completedBatch.locator('[data-code="VERIFIED"]')).toHaveCount(1);

    await ownerChinese.getByRole('button', { name: '行动简报与证据复盘' }).click();
    const reviewPanel = ownerChinese.getByRole('region', { name: 'Listing 经营复盘' });
    await expect(reviewPanel.locator('[role="alert"][data-failure="forbidden"]')).toBeVisible();
    const reviewForm = reviewPanel.getByRole('form', { name: '加载／刷新复盘' });
    const storeInput = reviewForm.getByLabel('复盘店铺 ID');
    await storeInput.fill(listing.storeId);
    const reviewPath = '/api/v1/console/listing/operations-review';
    const [reviewResponse] = await Promise.all([
      ownerPage.waitForResponse((response) => {
        const url = new URL(response.url());
        return (
          matches(response, 'GET', reviewPath) &&
          url.searchParams.get('storeId') === listing.storeId
        );
      }),
      reviewForm.getByRole('button', { name: '加载／刷新复盘' }).click(),
    ]);
    expect(reviewResponse.status()).toBe(200);
    const assertReading = async (kind: string): Promise<void> => {
      const reading = reviewPanel.locator(`[data-reading="${kind}"]`);
      await expect(reading).toContainText(action.id);
      await expect(reading).toContainText(listing.targetText);
      await expect(reading).toContainText(command.id);
      await expect(reading).toContainText(evaluation.planDigest);
      await expect(reading).toContainText(evaluationResult.id);
    };
    await assertReading('CURRENT_QUEUE');
    await reviewPanel.getByRole('button', { name: '每日行动简报' }).click();
    await assertReading('DAILY_ACTION_BRIEF');
    await reviewPanel.getByRole('button', { name: '每周证据复盘' }).click();
    await assertReading('WEEKLY_EVIDENCE_REVIEW');

    await storeInput.fill(BUILD_STORE_ID);
    const [forbiddenReview] = await Promise.all([
      ownerPage.waitForResponse((response) => {
        const url = new URL(response.url());
        return (
          matches(response, 'GET', reviewPath) && url.searchParams.get('storeId') === BUILD_STORE_ID
        );
      }),
      reviewForm.getByRole('button', { name: '加载／刷新复盘' }).click(),
    ]);
    expect(forbiddenReview.status()).toBe(403);
    await expect(reviewPanel.locator('[role="alert"][data-failure="forbidden"]')).toBeVisible();
    await expect(reviewPanel).not.toContainText(listing.targetText);
  } finally {
    await ownerSession.context.close();
  }
});
