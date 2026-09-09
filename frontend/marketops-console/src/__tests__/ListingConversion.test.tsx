import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ConsoleRequest } from '../api/console';
import {
  parseConversionMeasurement,
  parseLaunchAnswer,
  parseListingAction,
  parseListingHealth,
} from '../api/listingConversion';
import { ListingConversionShell } from '../listing/ListingConversionShell';
import { BACKEND_CODES, LANGUAGES, labelFor, storedLanguage } from '../listing/i18n/language';
import { UI_KEYS, t } from '../listing/i18n/ui';

/** A scripted backend: each route answers with its body, and every call is recorded. */
function backend(routes: Readonly<Record<string, unknown>>): {
  readonly fetchImpl: typeof fetch;
  readonly calls: {
    readonly url: string;
    readonly method: string;
    readonly body: string | undefined;
  }[];
} {
  const calls: {
    readonly url: string;
    readonly method: string;
    readonly body: string | undefined;
  }[] = [];
  const fetchImpl = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const method = init?.method ?? 'GET';
    calls.push({ url, method, body: typeof init?.body === 'string' ? init.body : undefined });
    const path = url.replace('http://127.0.0.1:8080', '');
    const key = Object.keys(routes).find((candidate) => path.startsWith(candidate));
    if (key === undefined) {
      return Promise.resolve(
        new Response(JSON.stringify({ detail: 'no route ' + path }), { status: 404 }),
      );
    }
    const answer = routes[key];
    if (answer instanceof Response) return Promise.resolve(answer);
    return Promise.resolve(
      new Response(JSON.stringify(answer), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  }) as unknown as typeof fetch;
  return { fetchImpl, calls };
}

function context(fetchImpl: typeof fetch): ConsoleRequest {
  return { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'token', fetchImpl };
}

function memoryStorage(): Pick<Storage, 'getItem' | 'setItem'> & {
  readonly values: Map<string, string>;
} {
  const values = new Map<string, string>();
  return {
    values,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => {
      values.set(key, value);
    },
  };
}

const LISTING = 'aa14dd95-b455-5db2-924c-8a3972e6f9d2';
const STORE = 'f5eced9a-7d0a-5d65-8942-8d1efeabf41a';
const ACTION = '5c000000-0000-5000-8000-00000000000a';

const HEALTH = {
  id: '5c000000-0000-5000-8000-000000000007',
  storeId: STORE,
  platformListingId: LISTING,
  nativeListingKey: 'fictional-listing',
  healthVersion: 1,
  necessaryConditions: [
    { code: 'AFFECTED_SET_COMPLETE', state: 'PASS', evidenceReference: 'core.lc_affected_set' },
    { code: 'NOT_CONTAINED', state: 'PASS', evidenceReference: 'ops.lc_containment' },
  ],
  necessaryState: 'PASS',
  eligibility: { MEASUREMENT: 'ELIGIBLE', PROTECTION: 'ELIGIBLE', EVALUATION: 'UNKNOWN' },
  opportunities: [
    { code: 'KIZ_MARKING_UNDECLARED', evidenceReference: 'core.lc_description_observation' },
  ],
  affectedSetState: 'COMPLETE',
  affectedVariantCount: 1,
  sourceTime: '2026-09-01T00:00:00Z',
  acquisitionTime: '2026-09-01T00:05:00Z',
  computedAt: '2026-09-01T00:06:00Z',
};

const MEASUREMENT = {
  id: '5c000000-0000-5000-8000-0000000000m1',
  platformListingId: LISTING,
  definitionVersion: 1,
  windowStart: '2026-08-01T00:00:00Z',
  windowEnd: '2026-08-31T00:00:00Z',
  retentionWindowDays: 14,
  evidencePath: 'DETAIL',
  pathQualified: false,
  qualificationReasonCodes: ['PURCHASE_LINKS_ABSENT'],
  visitCount: 120,
  retainedPurchaseVisitCount: null,
  primaryRatio: null,
  ratioState: 'NOT_AVAILABLE',
  maturityReached: false,
  sourceStratified: true,
  sellableSplit: { YES: 'UNDEFINED' },
  excludedTransitionDays: ['2026-08-10'],
  sourceTime: '2026-08-31T00:00:00Z',
  computedAt: '2026-09-01T00:00:00Z',
};

function action(state: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: ACTION,
    storeId: STORE,
    platformListingId: LISTING,
    nativeListingKey: 'fictional-listing',
    candidateId: '5c000000-0000-5000-8000-000000000008',
    recommendationId: '5c000000-0000-5000-8000-000000000009',
    recommendationVersion: 2,
    recommendationState: 'APPROVED',
    affectedSetDigest: 'a'.repeat(64),
    affectedSetState: 'COMPLETE',
    affectedVariantCount: 1,
    actionKind: 'LISTING_DESCRIPTION_CHANGE',
    executionPath: 'API',
    currentTextDigest: 'b'.repeat(64),
    targetText: 'Новое описание товара',
    targetTextDigest: 'c'.repeat(64),
    kizMarkedDeclared: false,
    materialityRoute: 'ORDINARY_IMPACT',
    contentAxisMaterial: false,
    exposureAxisMaterial: false,
    calibrationPackageId: '5c000000-0000-5000-8000-000000000001',
    calibrationVersion: 1,
    authorUserId: '0998716b-6f78-56da-bbea-554b20cfd093',
    state,
    reviews: [
      {
        id: '5c000000-0000-5000-8000-00000000000b',
        reviewerUserId: '8ec704dd-3aa5-529c-93db-def4bbf39260',
        verdict: 'ATTESTED',
        reason: 'ok',
        reviewedAt: '2026-09-01T00:00:00Z',
      },
    ],
    binding: null,
    launch: null,
    occupations: [],
    bindingGaps: [],
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    version: 3,
    ...extra,
  };
}

describe('the listing console speaks two languages completely', () => {
  it('TC-UI-LC-001 every backend code family has a Chinese and a Russian label', () => {
    expect(Object.keys(BACKEND_CODES).length).toBeGreaterThan(40);
    for (const [family, codes] of Object.entries(BACKEND_CODES)) {
      expect(Object.keys(codes).length, family).toBeGreaterThan(0);
      for (const [code, labels] of Object.entries(codes)) {
        for (const language of LANGUAGES) {
          expect(labels[language].length, `${family}.${code}.${language}`).toBeGreaterThan(0);
        }
      }
    }
    for (const key of UI_KEYS) {
      expect(t(key, 'zh').length, key).toBeGreaterThan(0);
      expect(t(key, 'ru').length, key).toBeGreaterThan(0);
    }
  });

  it('TC-UI-LC-002 an unrecognised code is shown as itself and marked, never as nothing', () => {
    expect(labelFor('actionState', 'SOMETHING_NEW', 'zh')).toBe('SOMETHING_NEW（未识别）');
    expect(labelFor('actionState', 'SOMETHING_NEW', 'ru')).toBe('SOMETHING_NEW (не распознано)');
    expect(labelFor('actionState', undefined, 'ru')).toBe('—');
    expect(labelFor('actionState', 'LAUNCHED', 'ru')).toBe('Запущено');
    expect(storedLanguage(undefined)).toBe('zh');
    expect(storedLanguage({ getItem: () => 'ru' })).toBe('ru');
    expect(
      storedLanguage({
        getItem: () => {
          throw new Error('blocked');
        },
      }),
    ).toBe('zh');
  });
});

describe('the parsers refuse what the backend never sends', () => {
  it('TC-UI-LC-003 a health row without its three eligibility layers is not a health row', () => {
    expect(parseListingHealth(HEALTH)?.necessaryState).toBe('PASS');
    expect(
      parseListingHealth({ ...HEALTH, eligibility: { MEASUREMENT: 'ELIGIBLE' } }),
    ).toBeUndefined();
    expect(parseListingHealth({ ...HEALTH, necessaryConditions: [{ code: 'X' }] })).toBeUndefined();
    expect(parseListingHealth('nope')).toBeUndefined();
  });

  it('TC-UI-LC-004 a measurement keeps its ratio state and its decimal as text', () => {
    const parsed = parseConversionMeasurement({
      ...MEASUREMENT,
      primaryRatio: '0.041667',
      ratioState: 'DEFINED',
    });
    expect(parsed?.primaryRatio).toBe('0.041667');
    expect(parsed?.excludedTransitionDays).toEqual(['2026-08-10']);
    expect(parseConversionMeasurement({ ...MEASUREMENT, ratioState: undefined })).toBeUndefined();
    expect(parseListingAction(action('DRAFT'))?.reviews).toHaveLength(1);
    expect(parseListingAction(action('DRAFT', { binding: { id: 'x' } }))).toBeUndefined();
    expect(
      parseLaunchAnswer({ launched: false, insufficientAxes: ['CONCURRENT_LISTINGS'] })
        ?.insufficientAxes,
    ).toEqual(['CONCURRENT_LISTINGS']);
    expect(parseLaunchAnswer({})).toBeUndefined();
  });
});

describe('Listing Health on the console', () => {
  it('TC-UI-LC-005 the queue shows three layers by label, in Chinese then in Russian', async () => {
    const storage = memoryStorage();
    const { fetchImpl } = backend({
      '/api/v1/console/listing/health/queue': [HEALTH],
    });
    render(
      <ListingConversionShell
        context={context(fetchImpl)}
        storeId={STORE}
        onBack={() => undefined}
        storage={storage}
      />,
    );

    expect(await screen.findByText('fictional-listing')).toBeInTheDocument();
    const queue = screen.getByRole('region', { name: '健康队列' });
    expect(within(queue).getAllByText('通过').length).toBeGreaterThan(0);
    expect(within(queue).getByText('КИЗ 标记未声明')).toBeInTheDocument();
    expect(within(queue).queryByText('KIZ_MARKING_UNDECLARED')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Русский' }));

    expect(await screen.findByRole('region', { name: 'Очередь здоровья' })).toBeInTheDocument();
    expect(screen.getByText('Маркировка КИЗ не заявлена')).toBeInTheDocument();
    expect(storage.values.get('marketops.listing.language')).toBe('ru');
  });

  it('TC-UI-LC-006 a listing opens to its conditions and measurements, and a recompute is a POST', async () => {
    const { fetchImpl, calls } = backend({
      '/api/v1/console/listing/health/queue': [HEALTH],
      [`/api/v1/console/listing/health/listings/${LISTING}/recompute`]: HEALTH,
      [`/api/v1/console/listing/health/listings/${LISTING}`]: {
        listingId: LISTING,
        storeId: STORE,
        platformCode: 'FICTIONAL',
        nativeListingKey: 'fictional-listing',
        health: HEALTH,
        measurements: [MEASUREMENT],
      },
    });
    render(
      <ListingConversionShell
        context={context(fetchImpl)}
        storeId={STORE}
        onBack={() => undefined}
        initialLanguage="zh"
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: '打开' }));

    expect(await screen.findByText(/受影响集合完整/u)).toBeInTheDocument();
    expect(screen.getByText('不可用')).toBeInTheDocument();
    expect(screen.getByText('缺少购买关联')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '重新计算健康' }));
    await waitFor(() => {
      expect(calls.some((call) => call.method === 'POST' && call.url.endsWith('/recompute'))).toBe(
        true,
      );
    });
    expect(await screen.findByRole('status')).toHaveTextContent('已记录。');
  });

  it('TC-UI-LC-007 a refusal is shown in the operator language with the backend detail', async () => {
    const { fetchImpl } = backend({
      '/api/v1/console/listing/health/queue': new Response(
        JSON.stringify({ detail: 'RESOURCE_SCOPE_DENIED' }),
        {
          status: 403,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    });
    render(
      <ListingConversionShell
        context={context(fetchImpl)}
        storeId={STORE}
        onBack={() => undefined}
        initialLanguage="ru"
      />,
    );

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveAttribute('data-failure', 'forbidden');
    expect(alert).toHaveTextContent('Нет доступа');
  });
});

describe('actions, allowance and launch', () => {
  it('TC-UI-LC-008 an approved action previews its allowance and a short launch names the axis', async () => {
    const { fetchImpl, calls } = backend({
      '/api/v1/console/listing/actions/candidates': [],
      [`/api/v1/console/listing/actions/${ACTION}/allowance-preview`]: {
        platformListingId: LISTING,
        resolved: true,
        axes: [
          {
            allowanceId: 'x',
            axisCode: 'CONCURRENT_LISTINGS',
            scopeKind: 'ORGANIZATION',
            limitValue: '1',
            reserveValue: '0',
            occupiedValue: '1',
            requestedValue: '1',
            headroom: '0',
            sufficient: false,
            unitCode: 'COUNT',
          },
        ],
      },
      [`/api/v1/console/listing/actions/${ACTION}/launch`]: {
        launched: false,
        launchId: null,
        occupationIds: [],
        insufficientAxes: ['CONCURRENT_LISTINGS'],
      },
      [`/api/v1/console/listing/actions/${ACTION}`]: action('APPROVED'),
      '/api/v1/console/listing/actions': [action('APPROVED')],
    });
    render(
      <ListingConversionShell
        context={context(fetchImpl)}
        storeId={STORE}
        onBack={() => undefined}
        initialLanguage="zh"
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '操作与启动' }));
    fireEvent.click(await screen.findByRole('button', { name: '打开' }));

    fireEvent.click(await screen.findByRole('button', { name: '额度预览' }));
    const table = await screen.findByRole('table', { name: '额度预览' });
    expect(within(table).getByText('并发 Listing 数')).toBeInTheDocument();
    expect(within(table).getByText('不足')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '启动（获取额度）' }));
    const status = await screen.findByRole('status');
    expect(status).toHaveAttribute('data-launched', 'false');
    expect(status).toHaveTextContent('CONCURRENT_LISTINGS');
    expect(calls.filter((call) => call.method === 'POST').map((call) => call.url)).toEqual(
      expect.arrayContaining([
        expect.stringContaining('/allowance-preview'),
        expect.stringContaining('/launch'),
      ]),
    );
  });

  it('TC-UI-LC-009 a reviewed action is approved through the workflow decision, never a listing endpoint', async () => {
    const { fetchImpl, calls } = backend({
      '/api/v1/console/workflow/recommendations': { decisionId: 'd1', state: 'APPROVED' },
      [`/api/v1/console/listing/actions/${ACTION}`]: action('REVIEWED'),
      '/api/v1/console/listing/actions': [action('REVIEWED')],
    });
    render(
      <ListingConversionShell
        context={context(fetchImpl)}
        storeId={STORE}
        onBack={() => undefined}
        initialLanguage="ru"
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Действия и запуск' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть' }));

    fireEvent.change(await screen.findByLabelText(/Причина/u), { target: { value: 'согласен' } });
    fireEvent.click(screen.getByRole('button', { name: 'Одобрить (решение процесса)' }));

    await waitFor(() => {
      const approval = calls.find((call) => call.url.includes('/workflow/recommendations/'));
      expect(approval?.url).toContain('/approval');
      expect(approval?.body).toContain('"expectedVersion":2');
    });
  });

  it('TC-UI-LC-010 a launched API action shows its description command and the closed gate by reason', async () => {
    const { fetchImpl } = backend({
      [`/api/v1/console/listing-description-commands/actions/${ACTION}`]: {
        id: 'cmd-1',
        actionId: ACTION,
        recommendationId: 'r',
        storeId: STORE,
        platformListingId: LISTING,
        platformCode: 'FICTIONAL',
        state: 'PENDING',
        priorTextDigest: 'b'.repeat(64),
        targetTextDigest: 'c'.repeat(64),
        priorTextCaptured: true,
        kizMarkedDeclared: false,
        equivalenceRule: 'EXACT',
        affectedSetDigest: 'a'.repeat(64),
        attemptNo: 0,
        retryBudgetRemaining: 3,
        failureCode: null,
        approvalExpiresAt: '2026-09-02T00:00:00Z',
        createdAt: '2026-09-01T00:00:00Z',
        updatedAt: '2026-09-01T00:00:00Z',
        terminalAt: null,
        attempts: [],
        readbacks: [],
      },
      '/api/v1/console/listing-description-commands/cmd-1/gate': {
        reasons: ['PRODUCTION_WRITE_DISABLED'],
      },
      [`/api/v1/console/listing/actions/${ACTION}`]: action('LAUNCHED', {
        launch: { id: 'l1', launchedByUserId: 'u', launchedAt: '2026-09-01T01:00:00Z' },
        occupations: [
          {
            id: 'o1',
            axisCode: 'CONCURRENT_LISTINGS',
            requestedValue: '1',
            occupiedValue: '1',
            state: 'ACQUIRED',
            acquiredAt: '2026-09-01T01:00:00Z',
            releasedAt: null,
            releaseBasis: null,
          },
        ],
      }),
      '/api/v1/console/listing/actions': [action('LAUNCHED')],
    });
    render(
      <ListingConversionShell
        context={context(fetchImpl)}
        storeId={STORE}
        onBack={() => undefined}
        initialLanguage="zh"
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '操作与启动' }));
    fireEvent.click(await screen.findByRole('button', { name: '打开' }));

    fireEvent.click(await screen.findByRole('button', { name: '描述写入命令' }));

    const command = await screen.findByRole('region', { name: '描述写入命令' });
    expect(command).toHaveAttribute('data-command-state', 'PENDING');
    expect(await within(command).findByText('生产写入未启用')).toBeInTheDocument();
    expect(screen.getByText('已获取')).toBeInTheDocument();
  });
});

describe('the manual path and governance', () => {
  it('TC-UI-LC-011 an issued packet carries the exact text and its report is posted', async () => {
    const packet = {
      id: 'p1',
      actionId: ACTION,
      launchId: 'l1',
      executorUserId: 'u1',
      issuedByUserId: 'u2',
      issuedAt: '2026-09-01T00:00:00Z',
      expiresAt: '2026-09-01T01:00:00Z',
      nativeListingKey: 'fictional-listing',
      affectedSetDigest: 'a'.repeat(64),
      targetText: 'Точный текст пакета',
      state: 'ISSUED',
      reports: [],
      verifications: [],
      version: 1,
    };
    const { fetchImpl, calls } = backend({
      '/api/v1/console/listing/manual/packets/p1/report': { ...packet, state: 'REPORTED' },
      '/api/v1/console/listing/manual/packets': [packet],
    });
    render(
      <ListingConversionShell
        context={context(fetchImpl)}
        storeId={STORE}
        onBack={() => undefined}
        initialLanguage="ru"
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Ручной путь и акции' }));

    expect(await screen.findByText('Точный текст пакета')).toBeInTheDocument();
    const form = screen.getByRole('form', { name: /Отчитаться об исполнении p1/u });
    fireEvent.change(within(form).getByLabelText(/Время операции/u), {
      target: { value: '2026-09-01T00:30:00Z' },
    });
    fireEvent.click(within(form).getByRole('button', { name: 'Отчитаться об исполнении' }));

    await waitFor(() => {
      const report = calls.find((call) => call.url.endsWith('/packets/p1/report'));
      expect(report?.body).toContain('"reportState":"APPLIED"');
    });
  });

  it('TC-UI-LC-012 a stop is one form, and re-enabling is refused until two people attest', async () => {
    const containment = {
      id: 'c1',
      scopeKind: 'LISTING',
      platformListingId: LISTING,
      storeId: STORE,
      platformCode: 'FICTIONAL',
      batchId: null,
      causeClass: 'SAFETY_FAILURE',
      causeOwnerRoleCode: 'OWNER',
      stoppedByUserId: 'owner',
      stoppedAt: '2026-09-01T00:00:00Z',
      reason: 'stop',
      evidenceReference: 'fixture://stop',
      state: 'ACTIVE',
      reenabledAt: null,
      attestations: [
        {
          id: 'a1',
          attestationKind: 'REPAIR_ATTESTATION',
          actorUserId: 'owner',
          evidenceReference: 'x',
          attestedAt: 't',
        },
      ],
    };
    const { fetchImpl, calls } = backend({
      '/api/v1/console/listing/governance/batches': [],
      '/api/v1/console/listing/governance/containments/stop': containment,
      '/api/v1/console/listing/governance/containments/c1/reenable': new Response(
        JSON.stringify({
          detail:
            'reenablement needs a repair attestation and a business consent by different people',
        }),
        { status: 409, headers: { 'Content-Type': 'application/json' } },
      ),
      '/api/v1/console/listing/governance/containments': [containment],
      '/api/v1/console/listing/governance/recalculation-queue': [
        {
          id: 'q1',
          triggerClass: 'RISK',
          targetMinutes: 5,
          platformListingId: LISTING,
          triggerReference: 'x',
          sourceTime: 't',
          acceptedAt: '2026-09-01T00:00:00Z',
          startedAt: null,
          finishedAt: null,
          state: 'QUEUED',
          latencySeconds: null,
          withinTarget: true,
        },
      ],
    });
    render(
      <ListingConversionShell
        context={context(fetchImpl)}
        storeId={STORE}
        onBack={() => undefined}
        initialLanguage="zh"
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '批次、遏制与队列' }));

    const article = await screen.findByRole('article');
    expect(article).toHaveAttribute('data-containment-state', 'ACTIVE');
    expect(article.textContent).toContain('修复证明 owner');
    expect(screen.getByText('风险（5 分钟）')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '恢复（需两人证明）' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveAttribute('data-failure', 'refused');
    expect(alert).toHaveTextContent('409');

    const stop = screen.getByRole('form', { name: '停止此 Listing' });
    fireEvent.change(within(stop).getByLabelText(/理由/u), { target: { value: '安全' } });
    fireEvent.click(within(stop).getByRole('button', { name: '停止整个组织' }));
    await waitFor(() => {
      const posted = calls.find((call) => call.url.endsWith('/containments/stop'));
      expect(posted?.body).toContain('"scopeKind":"ORGANIZATION"');
    });
  });
});
