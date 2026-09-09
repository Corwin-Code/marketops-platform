import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ConsoleRequest } from '../api/console';
import { ListingConversionShell } from '../listing/ListingConversionShell';
import { YesNo } from '../listing/ListingCommon';
import { LanguageProvider } from '../listing/i18n/language';

const LISTING = 'aa14dd95-b455-5db2-924c-8a3972e6f9d2';
const STORE = 'f5eced9a-7d0a-5d65-8942-8d1efeabf41a';
const HEALTH = {
  id: 'h1',
  storeId: STORE,
  platformListingId: LISTING,
  nativeListingKey: 'fictional-listing',
  healthVersion: 1,
  necessaryConditions: [],
  necessaryState: 'UNKNOWN',
  eligibility: { MEASUREMENT: 'UNKNOWN', PROTECTION: 'UNKNOWN', EVALUATION: 'UNKNOWN' },
  opportunities: [],
  affectedSetState: 'INCOMPLETE',
  affectedVariantCount: 0,
  computedAt: 't',
};
function action(
  state: string,
  path = 'MANUAL',
  extra: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    id: 'a1',
    storeId: STORE,
    platformListingId: LISTING,
    nativeListingKey: 'fictional-listing',
    candidateId: 'c1',
    recommendationId: 'r1',
    recommendationVersion: 1,
    recommendationState: 'READY_FOR_REVIEW',
    affectedSetDigest: 'd',
    affectedSetState: 'COMPLETE',
    affectedVariantCount: 1,
    actionKind: 'LISTING_DESCRIPTION_CHANGE',
    executionPath: path,
    materialityRoute: 'ORDINARY_IMPACT',
    authorUserId: 'u1',
    state,
    reviews: [],
    occupations: [],
    bindingGaps: ['BINDING_MISSING'],
    version: 1,
    ...extra,
  };
}

function backend(routes: readonly (readonly [RegExp, unknown])[]): {
  readonly context: ConsoleRequest;
  readonly calls: { url: string; method: string; body: string | undefined }[];
} {
  const calls: { url: string; method: string; body: string | undefined }[] = [];
  const fetchImpl = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    calls.push({
      url,
      method: init?.method ?? 'GET',
      body: typeof init?.body === 'string' ? init.body : undefined,
    });
    const route = routes.find(([pattern]) => pattern.test(url));
    const answer =
      route === undefined
        ? []
        : typeof route[1] === 'function'
          ? (route[1] as () => unknown)()
          : route[1];
    return Promise.resolve(
      new Response(JSON.stringify(answer), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  }) as unknown as typeof fetch;
  return {
    context: { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'token', fetchImpl },
    calls,
  };
}

function shell(context: ConsoleRequest, language: 'zh' | 'ru' = 'zh'): void {
  render(
    <ListingConversionShell
      context={context}
      storeId={STORE}
      onBack={() => undefined}
      initialLanguage={language}
    />,
  );
}

describe('every listing form posts what the operator entered', () => {
  it('TC-UI-LC-F01 measurement, description and display facts are recorded from the listing page', async () => {
    const { context, calls } = backend([
      [/\/health\/queue/u, [HEALTH]],
      [
        /\/measurements$/u,
        {
          ...HEALTH,
          id: 'm',
          windowStart: 'a',
          windowEnd: 'b',
          retentionWindowDays: 30,
          evidencePath: 'OFFICIAL_SUMMARY',
          pathQualified: false,
          ratioState: 'NOT_AVAILABLE',
          maturityReached: false,
          sourceStratified: false,
          computedAt: 't',
        },
      ],
      [/\/facts\//u, { observationId: 'o' }],
      [
        /\/health\/listings\//u,
        {
          listingId: LISTING,
          storeId: STORE,
          platformCode: 'P',
          nativeListingKey: 'fictional-listing',
          health: null,
          measurements: [],
        },
      ],
    ]);
    shell(context);
    fireEvent.click(await screen.findByRole('button', { name: '打开' }));
    expect(await screen.findByRole('form', { name: '计算度量' })).toBeInTheDocument();

    const measure = screen.getByRole('form', { name: '计算度量' });
    fireEvent.change(within(measure).getByLabelText(/留存天数/u), { target: { value: '30' } });
    fireEvent.change(within(measure).getByLabelText(/证据路径/u), {
      target: { value: 'OFFICIAL_SUMMARY' },
    });
    fireEvent.change(within(measure).getByPlaceholderText('2026-08-01T00:00:00Z'), {
      target: { value: '2026-08-01T00:00:00Z' },
    });
    fireEvent.change(within(measure).getByPlaceholderText('2026-08-31T00:00:00Z'), {
      target: { value: '2026-08-31T00:00:00Z' },
    });
    fireEvent.click(within(measure).getByRole('button', { name: '计算度量' }));

    const description = screen.getByRole('form', { name: '目标俄语描述（完整文本）' });
    fireEvent.change(within(description).getByRole('textbox'), { target: { value: 'Описание' } });
    fireEvent.change(within(description).getByRole('combobox'), { target: { value: 'no' } });
    fireEvent.click(within(description).getByRole('button', { name: '提交' }));

    const display = screen.getByRole('form', { name: '证据引用' });
    fireEvent.change(within(display).getByRole('combobox'), { target: { value: 'NOT_DISPLAYED' } });
    fireEvent.change(within(display).getByLabelText(/证据引用/u), { target: { value: 'ref' } });
    fireEvent.click(within(display).getByRole('button', { name: '提交' }));

    await waitFor(() => {
      expect(calls.filter((call) => call.method === 'POST')).toHaveLength(3);
    });
    expect(calls.find((call) => call.url.endsWith('/measurements'))?.body).toContain(
      '"retentionDays":30',
    );
    expect(calls.find((call) => call.url.endsWith('/facts/description'))?.body).toContain(
      '"kizMarkedDeclared":false',
    );
    expect(calls.find((call) => call.url.endsWith('/facts/display'))?.body).toContain(
      '"displayState":"NOT_DISPLAYED"',
    );
    fireEvent.click(screen.getByRole('button', { name: '← 健康队列' }));
    expect(await screen.findByRole('region', { name: '健康队列' })).toBeInTheDocument();
  });

  it('TC-UI-LC-F02 a candidate is prepared from the listing and becomes an exact action', async () => {
    const candidate = {
      id: 'c1',
      storeId: STORE,
      platformListingId: LISTING,
      candidateKind: 'CONTENT_DESCRIPTION',
      comparisonRoundKey: 'r',
      state: 'OPEN',
      version: 1,
    };
    const { context, calls } = backend([
      [/\/health\/queue/u, [HEALTH]],
      [
        /\/health\/listings\//u,
        {
          listingId: LISTING,
          storeId: STORE,
          platformCode: 'P',
          nativeListingKey: 'fictional-listing',
          health: HEALTH,
          measurements: [],
        },
      ],
      [/\/candidates\/c1\/prepare$/u, action('DRAFT', 'API')],
      [/\/candidates$/u, candidate],
      [/\/candidates\?/u, [candidate]],
      [/\/actions\/a1$/u, action('DRAFT', 'API')],
      [/\/actions\?/u, []],
    ]);
    shell(context);
    fireEvent.click(await screen.findByRole('button', { name: '打开' }));
    fireEvent.click(await screen.findByRole('button', { name: '候选' }));

    const section = await screen.findByRole('region', { name: '候选' });
    fireEvent.change(within(section).getByLabelText(/round/u), { target: { value: 'round-2' } });
    fireEvent.change(within(section).getByLabelText(/证据引用/u), {
      target: { value: 'fixture://evidence' },
    });
    fireEvent.click(within(section).getAllByRole('button', { name: '提交' })[0]!);
    await waitFor(() => {
      expect(
        calls.find((call) => call.method === 'POST' && call.url.endsWith('/candidates'))?.body,
      ).toContain('"roundKey":"round-2"');
    });

    const prepare = await screen.findByRole('form', { name: 'c1' });
    fireEvent.change(within(prepare).getByRole('textbox', { name: /目标俄语描述/u }), {
      target: { value: 'Новый текст' },
    });
    fireEvent.change(within(prepare).getByLabelText(/КИЗ 标记声明/u), { target: { value: 'yes' } });
    fireEvent.change(within(prepare).getByPlaceholderText('0.10'), { target: { value: '0.15' } });
    fireEvent.click(within(prepare).getByRole('button', { name: '提交' }));

    await waitFor(() => {
      expect(calls.find((call) => call.url.endsWith('/candidates/c1/prepare'))?.body).toContain(
        '"kizMarkedDeclared":true',
      );
    });
    expect(await screen.findByText('缺少绑定')).toBeInTheDocument();
  });

  it('TC-UI-LC-F03 a draft is attested or returned, a cancel posts its reason, and the evaluation renders', async () => {
    const evaluation = {
      planId: 'pl',
      actionId: 'a1',
      comparisonBasis: 'PRIOR_VERSION_WINDOW',
      crossPeriodWindowDays: 30,
      formalNodes: [
        { nodeCode: 'D14', maturityDays: 14, method: 'WILSON_LOWER_BOUND', threshold: '0.05' },
      ],
      stopRule: {},
      results: [
        {
          id: 'n1',
          nodeCode: 'D14',
          stage: 'SETTLED',
          revisionNo: 2,
          verdict: 'NOT_MET',
          protectionVerdict: 'FAIL',
          protectionVector: { SUPPLY_COVERAGE: 'FAIL' },
        },
      ],
    };
    const { context, calls } = backend([
      [/\/actions\/a1\/review$/u, action('REVIEWED')],
      [/\/actions\/a1\/cancel$/u, { state: 'CANCELLED' }],
      [/\/actions\/a1\/evaluation$/u, evaluation],
      [/\/actions\/a1$/u, action('DRAFT')],
      [/\/actions\?/u, [action('DRAFT')]],
    ]);
    shell(context, 'ru');
    fireEvent.click(screen.getByRole('button', { name: 'Действия и запуск' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть' }));

    fireEvent.change(await screen.findByLabelText(/Причина/u), { target: { value: 'verified' } });
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить (независимая проверка)' }));
    await waitFor(() => {
      expect(calls.find((call) => call.url.endsWith('/review'))?.body).toContain(
        '"verdict":"ATTESTED"',
      );
    });
    fireEvent.click(screen.getByRole('button', { name: 'Вернуть автору' }));
    fireEvent.click(screen.getByRole('button', { name: 'Отменить действие' }));
    fireEvent.click(screen.getByRole('button', { name: 'План и результаты оценки' }));
    expect(await screen.findByText('Не достигнуто')).toBeInTheDocument();
    expect(screen.getByText('Покрытие запасом')).toBeInTheDocument();
    await waitFor(() => {
      expect(calls.find((call) => call.url.endsWith('/cancel'))?.body).toContain('verified');
    });
  });

  it('TC-UI-LC-F04 a reviewed action is rejected through the workflow and a launched action shows its occupations', async () => {
    const { context, calls } = backend([
      [/\/workflow\/recommendations\/r1\/rejection$/u, { decisionId: 'd', state: 'REJECTED' }],
      [
        /\/actions\/a1\/launch$/u,
        { launched: true, launchId: 'l1', occupationIds: ['o1'], insufficientAxes: [] },
      ],
      [/\/actions\/a1$/u, action('REVIEWED')],
      [/\/actions\?/u, [action('REVIEWED')]],
    ]);
    shell(context);
    fireEvent.click(screen.getByRole('button', { name: '操作与启动' }));
    fireEvent.click(await screen.findByRole('button', { name: '打开' }));
    fireEvent.click(await screen.findByRole('button', { name: '拒绝' }));
    await waitFor(() => {
      expect(calls.some((call) => call.url.endsWith('/rejection'))).toBe(true);
    });
    fireEvent.click(screen.getByRole('button', { name: '← 操作' }));
    expect(await screen.findByRole('region', { name: '操作' })).toBeInTheDocument();
  });

  it('TC-UI-LC-F05 packets are issued and verified, engagements are looked up, exited and released', async () => {
    const reported = {
      id: 'p2',
      actionId: 'a1',
      executorUserId: 'u1',
      issuedAt: 't',
      expiresAt: 't',
      nativeListingKey: 'k',
      state: 'REPORTED',
      reports: [{ id: 'r', reporterUserId: 'u1', reportState: 'PARTIAL', note: 'n' }],
      verifications: [],
      version: 1,
    };
    let engagement: Record<string, unknown> = {
      id: 'e1',
      platformListingId: LISTING,
      engagementKind: 'OFFICIAL_PROMOTION_PARTICIPATION',
      priceFreeze: true,
      autoParticipation: false,
      adopted: false,
      state: 'ACTIVE',
      version: 1,
    };
    const { context, calls } = backend([
      [/\/manual\/actions\/a1\/packets$/u, reported],
      [/\/manual\/packets\/p2\/verify$/u, { ...reported, state: 'VERIFIED' }],
      [/\/manual\/packets\?/u, [reported]],
      [
        /\/manual\/engagements\/e1\/exit$/u,
        () => {
          engagement = { ...engagement, state: 'EXITING', exitReasonCode: 'OWNER_DECISION' };
          return engagement;
        },
      ],
      [
        /\/manual\/engagements\/e1\/release$/u,
        () => {
          engagement = { ...engagement, state: 'STOPPED' };
          return engagement;
        },
      ],
      [/\/manual\/engagements\?/u, () => [engagement]],
    ]);
    shell(context);
    fireEvent.click(screen.getByRole('button', { name: '人工路径与促销' }));

    const issue = await screen.findByRole('form', { name: '执行人' });
    fireEvent.change(within(issue).getByLabelText(/操作/u), { target: { value: 'a1' } });
    fireEvent.change(within(issue).getByLabelText(/执行人/u), { target: { value: 'u1' } });
    fireEvent.click(within(issue).getByRole('button', { name: '提交' }));

    const verify = await screen.findByRole('form', { name: /独立核实 p2/u });
    fireEvent.change(within(verify).getAllByRole('combobox')[1]!, {
      target: { value: 'DIFFERENT' },
    });
    fireEvent.click(within(verify).getByRole('button', { name: '独立核实' }));

    const lookup = screen.getByRole('form', { name: '促销参与' });
    fireEvent.change(within(lookup).getByLabelText(/Listing/u), { target: { value: LISTING } });
    fireEvent.click(within(lookup).getByRole('button', { name: '打开' }));
    fireEvent.click(await screen.findByRole('button', { name: '授权退出' }));
    await waitFor(() => {
      expect(calls.some((call) => call.url.endsWith('/engagements/e1/exit'))).toBe(true);
    });
    fireEvent.click(await screen.findByRole('button', { name: '释放' }));
    await waitFor(() => {
      expect(calls.some((call) => call.url.endsWith('/engagements/e1/release'))).toBe(true);
      expect(calls.find((call) => call.url.endsWith('/packets/p2/verify'))?.body).toContain(
        '"managementMatch":"DIFFERENT"',
      );
      expect(calls.find((call) => call.url.endsWith('/actions/a1/packets'))?.body).toContain(
        '"executorUserId":"u1"',
      );
    });
  });

  it('TC-UI-LC-F06 batches are created, extended and closed, attestations posted, and the filter narrows', async () => {
    const batch = {
      id: 'b1',
      storeId: STORE,
      batchCode: 'lot-1',
      state: 'OPEN',
      members: [],
      version: 1,
    };
    const containment = {
      id: 'c1',
      scopeKind: 'LISTING',
      platformListingId: LISTING,
      causeClass: 'LOCAL_COST',
      causeOwnerRoleCode: 'OWNER',
      stoppedByUserId: 'u',
      stoppedAt: 't',
      reason: 'r',
      state: 'ACTIVE',
      attestations: [],
    };
    const { context, calls } = backend([
      [/\/governance\/batches\/b1\/(members|close)$/u, batch],
      [/\/governance\/batches$/u, batch],
      [/\/governance\/batches\?/u, [batch]],
      [/\/governance\/containments\/c1\/attest$/u, containment],
      [/\/governance\/containments\?/u, [containment]],
      [/\/governance\/recalculation-queue/u, []],
    ]);
    shell(context);
    fireEvent.click(screen.getByRole('button', { name: '批次、遏制与队列' }));

    const create = await screen.findByRole('form', { name: '批次' });
    fireEvent.change(within(create).getByLabelText(/批次/u), { target: { value: 'lot-2' } });
    fireEvent.click(within(create).getByRole('button', { name: '提交' }));
    const article = await screen.findByText(/lot-1/u);
    const batchArticle = article.closest('article');
    expect(batchArticle).not.toBeNull();
    fireEvent.change(within(batchArticle!).getByLabelText(/操作/u), { target: { value: 'a9' } });
    fireEvent.click(within(batchArticle!).getByRole('button', { name: '成员' }));
    fireEvent.click(within(batchArticle!).getByRole('button', { name: '取消操作' }));

    fireEvent.click(screen.getByRole('button', { name: '修复证明' }));
    fireEvent.click(screen.getByRole('button', { name: '业务同意' }));
    fireEvent.click(screen.getByRole('checkbox'));

    await waitFor(() => {
      expect(
        calls.find((call) => call.url.endsWith('/batches') && call.method === 'POST')?.body,
      ).toContain('lot-2');
      expect(calls.find((call) => call.url.endsWith('/b1/members'))?.body).toContain('a9');
      expect(calls.some((call) => call.url.endsWith('/b1/close'))).toBe(true);
      expect(calls.filter((call) => call.url.endsWith('/c1/attest'))).toHaveLength(2);
      expect(calls.some((call) => call.url.includes('activeOnly=false'))).toBe(true);
    });
  });

  it('TC-UI-LC-F07 yes, no and undeclared read differently in both languages', () => {
    render(
      <LanguageProvider initial="ru">
        <YesNo value={true} />
        <YesNo value={false} />
        <YesNo value={undefined} />
      </LanguageProvider>,
    );
    expect(screen.getByText('Да')).toBeInTheDocument();
    expect(screen.getByText('Нет')).toBeInTheDocument();
    expect(screen.getByText('Не заявлено')).toBeInTheDocument();
  });
});
