import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ConsoleRequest } from '../api/console';
import { ListingActionsPanel } from '../listing/ListingActionsPanel';
import { ListingConversionShell } from '../listing/ListingConversionShell';
import { YesNo } from '../listing/ListingCommon';
import { ListingMeaningReview } from '../listing/ListingMeaningReview';
import { ListingManualPanel } from '../listing/ListingManualPanel';
import { ListingDependencyHold } from '../listing/ListingDependencyHold';
import { ListingOperationsReviewPanel } from '../listing/ListingOperationsReviewPanel';
import {
  PromotionDeclaration,
  PromotionPreparationForm,
  PromotionObservationForm,
} from '../listing/ListingPromotionTerms';
import { t } from '../listing/i18n/ui';
import { NativeScopeObservationForm } from '../listing/ListingNativeScope';
import { labelFor, LanguageProvider } from '../listing/i18n/language';

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

describe('declared action purpose', () => {
  it.each(['zh', 'ru'] as const)(
    'keeps bounded exploration manual and displays its exact purpose in %s',
    async (language) => {
      const candidate = {
        id: 'c1',
        storeId: STORE,
        platformListingId: LISTING,
        candidateKind: 'CONTENT_DESCRIPTION',
        comparisonRoundKey: 'r',
        state: 'OPEN',
        version: 1,
      };
      const prepared = action('DRAFT', 'MANUAL', { purposeCode: 'BOUNDED_EXPLORATION' });
      const { context, calls } = backend([
        [/\/candidates\?/u, [candidate]],
        [/\/candidates\/c1\/prepare$/u, prepared],
        [/\/actions\/a1$/u, prepared],
        [/\/actions\?/u, []],
      ]);
      render(
        <LanguageProvider initial={language}>
          <ListingActionsPanel context={context} listingId={LISTING} />
        </LanguageProvider>,
      );
      const form = await screen.findByRole('form', { name: 'c1' });
      expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0);
      fireEvent.change(within(form).getByLabelText(t('actionPurpose', language)), {
        target: { value: 'BOUNDED_EXPLORATION' },
      });
      expect(within(form).getByLabelText(t('path', language))).toHaveValue('MANUAL');
      expect(within(form).getByRole('option', { name: 'API' })).toBeDisabled();
      fireEvent.change(within(form).getByLabelText(t('targetText', language)), {
        target: { value: 'Описание' },
      });
      fireEvent.change(within(form).getByLabelText(t('kiz', language)), {
        target: { value: 'no' },
      });
      fireEvent.change(within(form).getByLabelText(t('purposeEvidence', language)), {
        target: { value: 'evidence://synthetic/purpose' },
      });
      fireEvent.change(within(form).getByLabelText(t('purposeUseConditions', language)), {
        target: { value: 'Use while the scoped evidence is current' },
      });
      fireEvent.change(within(form).getByLabelText(t('purposeEndConditions', language)), {
        target: { value: 'Reassess without claiming improvement' },
      });
      fireEvent.change(within(form).getByLabelText(t('purposeUseUntil', language)), {
        target: { value: '2026-12-01T00:00:00Z' },
      });
      fireEvent.click(within(form).getByRole('button', { name: t('submit', language) }));
      await waitFor(() => {
        expect(calls.filter((call) => call.method === 'POST')).toHaveLength(1);
      });
      expect(JSON.parse(calls.find((call) => call.method === 'POST')?.body ?? '{}')).toMatchObject({
        purpose: 'BOUNDED_EXPLORATION',
        executionPath: 'MANUAL',
        targetText: 'Описание',
        purposeBasis: {
          evidenceReference: 'evidence://synthetic/purpose',
          useConditions: ['Use while the scoped evidence is current'],
          endConditions: ['Reassess without claiming improvement'],
          useUntil: '2026-12-01T00:00:00Z',
        },
      });
      expect(
        await screen.findByText(
          `${t('actionPurpose', language)}: ${t('purposeExploration', language)}`,
        ),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole('button', { name: t('evaluation', language) }),
      ).not.toBeInTheDocument();
    },
  );
});

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
    expect(
      JSON.parse(calls.find((call) => call.url.endsWith('/measurements'))?.body ?? '{}'),
    ).toMatchObject({
      windowStart: '2026-08-01T00:00:00Z',
      windowEnd: '2026-08-31T00:00:00Z',
      retentionDays: 30,
      evidencePath: 'OFFICIAL_SUMMARY',
    });
    expect(calls.find((call) => call.url.endsWith('/facts/description'))?.body).toContain(
      '"kizMarkedDeclared":false',
    );
    expect(calls.find((call) => call.url.endsWith('/facts/display'))?.body).toContain(
      '"displayState":"NOT_DISPLAYED"',
    );
    fireEvent.click(screen.getByRole('button', { name: '← 健康队列' }));
    expect(await screen.findByRole('region', { name: '健康队列' })).toBeInTheDocument();
  });

  it.each([false, true])(
    'TC-UI-LC-F02 prepares an exact action (restoration=%s)',
    async (restoration) => {
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
      const source = 'e8427a26-0229-438f-b33c-b35c49ad1b40';
      if (restoration) {
        fireEvent.change(within(prepare).getByLabelText(/恢复来源命令 ID/u), {
          target: { value: source },
        });
        expect(within(prepare).getByRole('textbox', { name: /目标俄语描述/u })).toBeDisabled();
        expect(within(prepare).getByText(/本次恢复需要独立的新审核/u)).toBeInTheDocument();
      }
      fireEvent.change(within(prepare).getByLabelText(/КИЗ 标记声明/u), {
        target: { value: 'yes' },
      });
      expect(within(prepare).queryByPlaceholderText('0.10')).not.toBeInTheDocument();
      expect(within(prepare).getByText(/经营暴露根据完整影响范围/u)).toBeInTheDocument();
      fireEvent.click(within(prepare).getByRole('button', { name: '提交' }));

      await waitFor(() => {
        expect(calls.find((call) => call.url.endsWith('/candidates/c1/prepare'))?.body).toContain(
          '"kizMarkedDeclared":true',
        );
      });
      const request = JSON.parse(
        calls.find((call) => call.url.endsWith('/candidates/c1/prepare'))!.body!,
      ) as Record<string, unknown>;
      expect(request).not.toHaveProperty('exposureShare');
      expect(request.restoresCommandId).toBe(restoration ? source : null);
      expect(request.targetText).toBe(restoration ? null : 'Новый текст');
      expect(await screen.findByText('缺少绑定')).toBeInTheDocument();
    },
  );

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
      [
        /\/actions\/a1\/review-basis$/u,
        {
          actionId: 'a1',
          basisDigest: 'meaning-basis',
          ruleState: 'QUALIFIED',
          currentText: 'Не использовать для детей.',
          targetText: 'Использовать для детей.',
          conditions: [
            {
              code: 'SAFETY_CHANGE',
              condition: 'Изменение ограничений безопасности',
              axis: 'MATERIAL',
            },
            {
              code: 'LIMITED_CLARIFICATION',
              condition: 'Уточнение без изменения смысла',
              axis: 'ORDINARY',
            },
          ],
          affectedSet: {
            affectedSetId: 'set-1',
            digest: 'affected-digest',
            state: 'COMPLETE',
            listingVariantIds: ['listing-variant-1'],
            productVariantIds: ['product-variant-1'],
            nativeScopeObservationId: 'scope-1',
            identityLineage: '{"members":[]}',
          },
          calibrationEvidence: { state: 'CURRENT' },
          materialityEvidence: { state: 'CURRENT' },
          businessProtectionEvidence: { model: 'LC_CURRENT_BUSINESS_PROTECTION_1' },
          authorityDocument: '{}',
          applicableExperience: [],
        },
      ],
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
    expect(
      screen.getByRole('button', { name: 'Подтвердить (независимая проверка)' }),
    ).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Загрузить основания проверки смысла' }));
    expect(await screen.findByText('Не использовать для детей.')).toBeInTheDocument();
    expect(screen.getByText('Использовать для детей.')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('SAFETY_CHANGE'), { target: { value: 'APPLIES' } });
    fireEvent.change(screen.getByLabelText('Основание оценки условия 1'), {
      target: { value: 'Удалено отрицание' },
    });
    fireEvent.change(screen.getByLabelText('Основание оценки условия 2'), {
      target: { value: 'Смысл изменён' },
    });
    fireEvent.change(screen.getByLabelText('Ссылка на доказательство проверки смысла'), {
      target: { value: 'evidence://synthetic/meaning' },
    });
    fireEvent.click(
      screen.getByLabelText(
        'Я проверил полный текст или коммерческие условия и все условия оценки',
      ),
    );
    expect(
      screen.getByRole('button', { name: 'Подтвердить (независимая проверка)' }),
    ).toBeDisabled();
    fireEvent.change(screen.getByLabelText('LIMITED_CLARIFICATION'), {
      target: { value: 'DOES_NOT_APPLY' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить (независимая проверка)' }));
    await waitFor(() => {
      expect(calls.find((call) => call.url.endsWith('/review'))?.body).toContain(
        '"verdict":"ATTESTED"',
      );
    });
    expect(calls.find((call) => call.url.endsWith('/review'))?.body).toContain(
      '"basisDigest":"meaning-basis"',
    );
    expect(calls.find((call) => call.url.endsWith('/review'))?.body).toContain('Удалено отрицание');
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
      fullDisclosure: false,
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
    fireEvent.change(await screen.findByLabelText('退出权威引用'), {
      target: { value: 'authority://exit' },
    });
    fireEvent.change(screen.getByLabelText('当前证据 ID'), {
      target: { value: 'evidence-1' },
    });
    fireEvent.click(await screen.findByRole('button', { name: '授权退出' }));
    await waitFor(() => {
      expect(calls.some((call) => call.url.endsWith('/engagements/e1/exit'))).toBe(true);
    });
    fireEvent.change(await screen.findByLabelText('当前观测 ID'), {
      target: { value: 'observation-1' },
    });
    fireEvent.change(screen.getAllByLabelText('证据引用').at(-1)!, {
      target: { value: 'evidence://stop' },
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
      expect(calls.find((call) => call.url.endsWith('/engagements/e1/exit'))?.body).toContain(
        '"authorityReference":"authority://exit"',
      );
      expect(calls.find((call) => call.url.endsWith('/engagements/e1/release'))?.body).toContain(
        '"observationId":"observation-1"',
      );
    });
  });

  it.each(['zh', 'ru'] as const)(
    'adopts one exact existing promotion from the complete context in %s',
    async (language) => {
      const adopted = {
        id: 'adopted-1',
        platformListingId: LISTING,
        engagementKind: 'OFFICIAL_PROMOTION_PARTICIPATION',
        fullDisclosure: true,
        priceFreeze: true,
        autoParticipation: false,
        adopted: true,
        state: 'ACTIVE',
        version: 1,
      };
      const { context, calls } = backend([
        [/\/manual\/packets\?/u, []],
        [/\/manual\/listings\/[^/]+\/engagements$/u, adopted],
        [/\/manual\/engagements\?/u, [adopted]],
      ]);
      render(
        <LanguageProvider initial={language}>
          <ListingManualPanel context={context} />
        </LanguageProvider>,
      );
      const form = await screen.findByRole('form', { name: t('promotionAdoption', language) });
      const change = (key: Parameters<typeof t>[0], value: string) => {
        fireEvent.change(within(form).getByLabelText(t(key, language)), { target: { value } });
      };
      change('listing', LISTING);
      change('promotionKind', 'OFFICIAL_PROMOTION_PARTICIPATION');
      change('promotionContextObservationId', 'context-observation-1');
      change('promotionOriginalAuthority', 'authority://promotion/original');
      change('promotionOriginalAuthorityUntil', '2026-10-01T00:00');
      change('promotionResponsibleUser', 'responsible-user-1');
      change('promotionNativeKey', 'native-promotion-1');
      change('evidence', 'evidence://promotion/terms');
      const terms = within(
        within(form).getByRole('group', { name: t('promotionTerms', language) }),
      );
      fireEvent.change(terms.getByLabelText(`${t('promotionFieldName', language)} 1`), {
        target: { value: 'finalPrice' },
      });
      fireEvent.change(terms.getByLabelText(`${t('promotionFieldValue', language)} 1`), {
        target: { value: '200.0000' },
      });
      const obligations = within(
        within(form).getByRole('group', { name: t('promotionObligations', language) }),
      );
      fireEvent.change(obligations.getByLabelText(`${t('promotionFieldName', language)} 1`), {
        target: { value: 'fixedFee' },
      });
      fireEvent.change(obligations.getByLabelText(`${t('promotionFieldValue', language)} 1`), {
        target: { value: '10.0000' },
      });
      change('promotionPriceFreeze', 'yes');
      change('promotionAutoParticipation', 'no');
      fireEvent.submit(form);
      await waitFor(() => {
        expect(
          calls.some((call) => /\/manual\/listings\/[^/]+\/engagements$/u.test(call.url)),
        ).toBe(true);
      });
      const body = JSON.parse(
        calls.find((call) => /\/manual\/listings\/[^/]+\/engagements$/u.test(call.url))?.body ??
          '{}',
      );
      expect(body).toMatchObject({
        engagementKind: 'OFFICIAL_PROMOTION_PARTICIPATION',
        nativePromotionKey: 'native-promotion-1',
        terms: { finalPrice: '200.0000' },
        obligations: { fixedFee: '10.0000' },
        priceFreeze: true,
        autoParticipation: false,
        termsEvidenceReference: 'evidence://promotion/terms',
        contextObservationId: 'context-observation-1',
        originalAuthorityReference: 'authority://promotion/original',
        originalAuthorityValidUntil: expect.any(String),
        responsibleUserId: 'responsible-user-1',
      });
    },
  );

  it.each(['zh', 'ru'] as const)(
    'TC-UI-LC-F06 batches use the entered store and retain their controls in %s',
    async (language) => {
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
      shell(context, language);
      fireEvent.click(screen.getByRole('button', { name: t('tabGovernance', language) }));

      const create = await screen.findByRole('form', { name: t('batches', language) });
      fireEvent.change(within(create).getByLabelText(t('batchStoreId', language)), {
        target: { value: 'actual-authorized-store' },
      });
      fireEvent.change(within(create).getByLabelText(t('batches', language), { exact: true }), {
        target: { value: 'lot-2' },
      });
      fireEvent.click(within(create).getByRole('button', { name: t('submit', language) }));
      const article = await screen.findByText(/lot-1/u);
      const batchArticle = article.closest('article');
      expect(batchArticle).not.toBeNull();
      fireEvent.change(within(batchArticle!).getByLabelText(t('actions', language)), {
        target: { value: 'a9' },
      });
      fireEvent.click(within(batchArticle!).getByRole('button', { name: t('members', language) }));
      fireEvent.click(within(batchArticle!).getByRole('button', { name: t('cancel', language) }));

      fireEvent.click(screen.getByRole('button', { name: t('attestRepair', language) }));
      fireEvent.click(screen.getByRole('button', { name: t('consent', language) }));
      fireEvent.click(screen.getByRole('checkbox'));

      await waitFor(() => {
        expect(
          JSON.parse(
            calls.find((call) => call.url.endsWith('/batches') && call.method === 'POST')?.body ??
              '{}',
          ),
        ).toEqual({ storeId: 'actual-authorized-store', code: 'lot-2' });
        expect(calls.find((call) => call.url.endsWith('/b1/members'))?.body).toContain('a9');
        expect(calls.some((call) => call.url.endsWith('/b1/close'))).toBe(true);
        expect(calls.filter((call) => call.url.endsWith('/c1/attest'))).toHaveLength(2);
        expect(calls.some((call) => call.url.includes('activeOnly=false'))).toBe(true);
      });
    },
  );

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

describe('exact promotion declarations', () => {
  it.each(['zh', 'ru'] as const)(
    'requires explicit flags and a same-purpose simulation while preserving commercial values in %s',
    async (language) => {
      const purpose = language === 'zh' ? 'PROMOTION' : 'BOUNDED_EXPLORATION';
      const simulationId = `qualified-${purpose.toLowerCase()}`;
      const simulation = {
        id: simulationId,
        candidateId: 'promotion-candidate',
        inputsDigest: 'a'.repeat(64),
        computedAt: '2026-09-13T03:00:00Z',
        inverseState: 'RESOLVED',
        inputSnapshot: {
          purposeCode: purpose,
          qualificationState: 'QUALIFIED_CONDITIONAL_ECONOMICS',
        },
        scenarios: [],
      };
      const { context, calls } = backend([
        [/\/simulations$/u, [simulation]],
        [/\/prepare$/u, action('DRAFT', 'MANUAL')],
      ]);
      const onPrepared = vi.fn();
      render(
        <LanguageProvider initial={language}>
          <PromotionPreparationForm
            context={context}
            candidateId="promotion-candidate"
            kind="SELLER_DIRECT_DISCOUNT"
            onPrepared={onPrepared}
          />
        </LanguageProvider>,
      );
      const form = screen.getByRole('form', { name: 'promotion-candidate' });
      if (purpose === 'BOUNDED_EXPLORATION') {
        fireEvent.change(screen.getByLabelText(t('actionPurpose', language)), {
          target: { value: purpose },
        });
        fireEvent.change(screen.getByLabelText(t('purposeEvidence', language)), {
          target: { value: 'fixture://bounded-purpose' },
        });
        fireEvent.change(screen.getByLabelText(t('purposeUseConditions', language)), {
          target: { value: 'Use only for the declared promotion.' },
        });
        fireEvent.change(screen.getByLabelText(t('purposeEndConditions', language)), {
          target: { value: 'Stop when the declared promotion ends.' },
        });
        fireEvent.change(screen.getByLabelText(t('purposeUseUntil', language)), {
          target: { value: '2026-10-01T09:00' },
        });
      }
      fireEvent.change(screen.getByLabelText(t('promotionNativeKey', language)), {
        target: { value: ' exact/native-key ' },
      });
      fireEvent.change(
        within(form).getByLabelText(t('evidence', language), { selector: 'input[required]' }),
        {
          target: { value: 'fixture://exact-source' },
        },
      );
      for (const [group, name, value] of [
        ['promotionTerms', 'Цена', '200.0000'],
        ['promotionObligations', '固定承诺', '600.0000'],
      ] as const) {
        const fieldset = within(screen.getByRole('group', { name: t(group, language) }));
        fireEvent.change(fieldset.getByLabelText(`${t('promotionFieldName', language)} 1`), {
          target: { value: name },
        });
        fireEvent.change(fieldset.getByLabelText(`${t('promotionFieldValue', language)} 1`), {
          target: { value },
        });
      }
      fireEvent.submit(form);
      expect(calls).toHaveLength(0);
      fireEvent.change(screen.getByLabelText(t('promotionPriceFreeze', language)), {
        target: { value: 'yes' },
      });
      fireEvent.change(screen.getByLabelText(t('promotionAutoParticipation', language)), {
        target: { value: 'no' },
      });
      fireEvent.click(screen.getByRole('button', { name: t('promotionSimulationLoad', language) }));
      await screen.findByRole('option', { name: new RegExp(simulationId, 'u') });
      fireEvent.change(screen.getByLabelText(t('promotionSimulationSelect', language)), {
        target: { value: simulationId },
      });
      fireEvent.submit(form);
      await waitFor(() => {
        expect(onPrepared).toHaveBeenCalledWith('a1');
      });
      const prepared = JSON.parse(
        calls.find((call) => call.url.endsWith('/prepare'))?.body ?? '{}',
      ) as Record<string, unknown>;
      expect(prepared).toMatchObject({
        executionPath: 'MANUAL',
        targetText: null,
        purpose,
        simulationId,
        promotionTerms: {
          engagementKind: 'SELLER_DIRECT_DISCOUNT',
          nativePromotionKey: ' exact/native-key ',
          terms: { Цена: '200.0000' },
          obligations: { 固定承诺: '600.0000' },
          priceFreeze: true,
          autoParticipation: false,
          termsEvidenceReference: 'fixture://exact-source',
        },
      });
      if (purpose === 'PROMOTION') {
        expect(prepared.purposeBasis).toBeUndefined();
      } else {
        expect(prepared.purposeBasis).toMatchObject({
          evidenceReference: 'fixture://bounded-purpose',
          useConditions: ['Use only for the declared promotion.'],
          endConditions: ['Stop when the declared promotion ends.'],
          useUntil: expect.any(String),
        });
      }
    },
  );

  it('clears disclosed terms when a subsequent current permission check masks them', async () => {
    let allowed = true;
    const { context } = backend([
      [
        /\/promotion-terms$/u,
        () => ({
          actionId: 'a1',
          digest: 'd',
          fullDisclosure: allowed,
          terms: allowed
            ? {
                engagementKind: 'SELLER_DIRECT_DISCOUNT',
                nativePromotionKey: 'p',
                terms: { price: '200.0000' },
                obligations: { fee: '600.0000' },
                priceFreeze: true,
                autoParticipation: false,
                termsEvidenceReference: 'fixture://source',
              }
            : null,
        }),
      ],
    ]);
    render(<PromotionDeclaration context={context} actionId="a1" digest="d" />);
    fireEvent.click(screen.getByRole('button', { name: t('promotionReadTerms', 'zh') }));
    expect(await screen.findByText('600.0000')).toBeInTheDocument();
    allowed = false;
    fireEvent.click(screen.getByRole('button', { name: t('promotionReadTerms', 'zh') }));
    expect(screen.queryByText('600.0000')).not.toBeInTheDocument();
    expect(await screen.findByText(t('promotionTermsRestricted', 'zh'))).toBeInTheDocument();
  });
});

describe('promotion source observations and their independent verification reference', () => {
  it.each(['zh', 'ru'] as const)(
    'retains unknown commercial terms and separately captures complete terms in %s',
    async (language) => {
      const { context, calls } = backend([
        [/\/facts\/promotion$/u, { observationId: 'promotion-observation-1' }],
      ]);
      render(
        <LanguageProvider initial={language}>
          <PromotionObservationForm context={context} listingId={LISTING} />
        </LanguageProvider>,
      );
      fireEvent.change(screen.getByLabelText(t('promotionKind', language)), {
        target: { value: 'SELLER_DIRECT_DISCOUNT' },
      });
      fireEvent.change(screen.getByLabelText(t('promotionNativeKey', language)), {
        target: { value: ' exact/native-key ' },
      });
      fireEvent.change(screen.getByLabelText(t('promotionParticipationState', language)), {
        target: { value: 'PARTICIPATING' },
      });
      fireEvent.change(screen.getByLabelText(t('promotionObservedAt', language)), {
        target: { value: '2026-09-13T02:00' },
      });
      fireEvent.change(screen.getByLabelText(t('promotionObservationReference', language)), {
        target: { value: 'fixture://actual-observation' },
      });
      const form = screen.getByRole('form', { name: t('promotionObservation', language) });
      fireEvent.submit(form);
      expect(await screen.findByRole('status')).toHaveTextContent(
        t('promotionObservationSaved', language),
      );
      expect(JSON.parse(calls[0]?.body ?? '{}')).toMatchObject({
        declaration: null,
        nativePromotionKey: ' exact/native-key ',
        engagementKind: 'SELLER_DIRECT_DISCOUNT',
        participationState: 'PARTICIPATING',
        evidenceReference: 'fixture://actual-observation',
      });
      fireEvent.click(screen.getByLabelText(t('promotionTermsObserved', language)));
      fireEvent.change(screen.getByLabelText(t('promotionDeclarationSource', language)), {
        target: { value: 'fixture://commercial-source' },
      });
      for (const [group, name, value] of [
        ['promotionTerms', 'price', '200.0000'],
        ['promotionObligations', 'fixedFee', '600.0000'],
      ] as const) {
        const fields = within(screen.getByRole('group', { name: t(group, language) }));
        fireEvent.change(fields.getByLabelText(`${t('promotionFieldName', language)} 1`), {
          target: { value: name },
        });
        fireEvent.change(fields.getByLabelText(`${t('promotionFieldValue', language)} 1`), {
          target: { value },
        });
      }
      fireEvent.change(screen.getByLabelText(t('promotionPriceFreeze', language)), {
        target: { value: 'yes' },
      });
      fireEvent.change(screen.getByLabelText(t('promotionAutoParticipation', language)), {
        target: { value: 'no' },
      });
      fireEvent.click(screen.getByLabelText(t('promotionContextComplete', language)));
      for (const [key, value] of [
        ['promotionCoverageStart', '2026-09-01T00:00'],
        ['promotionCoverageEnd', '2026-09-30T00:00'],
        ['promotionVerificationExpires', '2026-10-01T00:00'],
        ['promotionEffectiveFrom', '2026-09-01T00:00'],
        ['promotionEffectiveTo', '2026-09-30T00:00'],
        ['promotionOriginalAuthorityUntil', '2026-10-01T00:00'],
      ] as const) {
        fireEvent.change(screen.getByLabelText(t(key, language)), { target: { value } });
      }
      fireEvent.change(screen.getByLabelText(t('promotionNewTransactionsState', language)), {
        target: { value: 'OPEN' },
      });
      fireEvent.change(screen.getByLabelText(t('promotionResidualState', language)), {
        target: { value: 'OUTSTANDING' },
      });
      fireEvent.change(screen.getByLabelText(t('promotionOriginalAuthority', language)), {
        target: { value: 'authority://promotion/original' },
      });
      for (const axis of [
        'CONCURRENT_LISTINGS',
        'AFFECTED_VARIANTS',
        'REVENUE_EXPOSURE',
        'CATEGORY_SHARE',
      ]) {
        expect(
          screen.getByRole('group', { name: labelFor('allowanceAxis', axis, language) }),
        ).toBeInTheDocument();
      }
      const revenueAxis = within(
        screen.getByRole('group', {
          name: labelFor('allowanceAxis', 'REVENUE_EXPOSURE', language),
        }),
      );
      fireEvent.change(revenueAxis.getByLabelText(t('promotionAxisValue', language)), {
        target: { value: '500.0000' },
      });
      fireEvent.change(revenueAxis.getByLabelText(t('promotionAxisUnit', language)), {
        target: { value: 'RUB' },
      });
      fireEvent.change(revenueAxis.getByLabelText(t('promotionAxisEvidence', language)), {
        target: { value: 'evidence://promotion/revenue' },
      });
      fireEvent.submit(form);
      await waitFor(() => {
        expect(calls).toHaveLength(2);
      });
      const posted = JSON.parse(calls[1]?.body ?? '{}') as {
        readonly context: {
          readonly records: readonly [
            {
              readonly axisDemands: Readonly<Record<string, unknown>>;
            },
          ];
        };
      };
      expect(posted).toMatchObject({
        declaration: {
          terms: { price: '200.0000' },
          obligations: { fixedFee: '600.0000' },
          priceFreeze: true,
          autoParticipation: false,
        },
        evidenceReference: 'fixture://actual-observation',
        context: {
          coverageStart: expect.any(String),
          coverageEnd: expect.any(String),
          verificationExpiresAt: expect.any(String),
          records: [
            {
              participationState: 'PARTICIPATING',
              effectiveFrom: expect.any(String),
              effectiveTo: expect.any(String),
              newTransactionsState: 'OPEN',
              residualObligationState: 'OUTSTANDING',
              originalAuthorityReference: 'authority://promotion/original',
              originalAuthorityValidUntil: expect.any(String),
              axisDemands: {
                REVENUE_EXPOSURE: {
                  value: '500.0000',
                  unitCode: 'RUB',
                  evidenceReference: 'evidence://promotion/revenue',
                },
              },
            },
          ],
        },
      });
      expect(Object.keys(posted.context.records[0].axisDemands)).toEqual(['REVENUE_EXPOSURE']);
    },
  );

  it('passes the exact independent participation observation without inventing description or display evidence', async () => {
    const packet = {
      id: 'p1',
      actionId: 'a1',
      executorUserId: 'u1',
      issuedAt: 't',
      expiresAt: 't',
      nativeListingKey: 'k',
      state: 'REPORTED',
      reports: [],
      verifications: [],
      version: 1,
    };
    const { context, calls } = backend([
      [/\/packets\?/u, [packet]],
      [/\/packets\/p1\/verify$/u, { ...packet, state: 'VERIFIED' }],
    ]);
    render(<ListingManualPanel context={context} />);
    const form = await screen.findByRole('form', { name: `${t('verify', 'zh')} p1` });
    fireEvent.change(within(form).getByLabelText(t('promotionObservationId', 'zh')), {
      target: { value: 'promotion-observation-1' },
    });
    fireEvent.change(within(form).getByDisplayValue('DISPLAYED'), { target: { value: 'UNKNOWN' } });
    fireEvent.change(within(form).getByLabelText(t('note', 'zh')), {
      target: { value: 'Independent participation only' },
    });
    fireEvent.submit(form);
    await waitFor(() => {
      expect(calls.some((call) => call.url.endsWith('/verify'))).toBe(true);
    });
    expect(JSON.parse(calls.find((call) => call.url.endsWith('/verify'))?.body ?? '{}')).toEqual({
      basis: 'INDEPENDENT_HUMAN',
      managementMatch: 'MATCHED_TARGET',
      displayState: 'UNKNOWN',
      note: 'Independent participation only',
      promotionObservationId: 'promotion-observation-1',
    });
  });
  it.each(['zh', 'ru'] as const)(
    'preserves the source enumeration and partial paging in %s',
    async (language) => {
      const { context, calls } = backend([
        [/facts\/native-scope$/, { observationId: 'scope-observation-1' }],
      ]);
      const view = render(
        <LanguageProvider initial={language}>
          <NativeScopeObservationForm context={context} listingId={LISTING} />
        </LanguageProvider>,
      );
      const change = (key: Parameters<typeof t>[0], value: string) => {
        fireEvent.change(screen.getByLabelText(t(key, language)), { target: { value } });
      };
      expect(screen.getByLabelText(t('nativeMemberKeys', language))).toHaveValue('');
      change('nativeScopeKind', 'WHOLE_LISTING');
      change('nativeScopeKey', ' native-listing ');
      change('nativeMemberKeys', ' native-one \nnative-two');
      change('nativeCoverage', 'PARTIAL');
      change('nativeExpectedCount', '3');
      change('nativeContinuation', ' next-page ');
      change('nativeScopeSource', ' fixture:source ');
      change('nativeScopeBasis', 'fixture:whole-card');
      change('nativeScopeObserved', '2026-09-13T02:00');
      change('nativeScopeExpires', '2026-09-13T03:00');
      fireEvent.submit(screen.getByRole('form', { name: t('nativeScopeCapture', language) }));
      expect(await screen.findByRole('status')).toHaveTextContent(t('nativeScopeSaved', language));
      expect(JSON.parse(calls[0]?.body ?? '{}')).toMatchObject({
        scopeKind: 'WHOLE_LISTING',
        nativeScopeKey: ' native-listing ',
        nativeVariantKeys: [' native-one ', 'native-two'],
        coverageState: 'PARTIAL',
        expectedMemberCount: 3,
        continuationReference: ' next-page ',
        sourceReference: ' fixture:source ',
        scopeBasisReference: 'fixture:whole-card',
      });
      view.rerender(
        <LanguageProvider initial={language}>
          <NativeScopeObservationForm
            context={{ ...context, accessToken: 'next-user' }}
            listingId={LISTING}
          />
        </LanguageProvider>,
      );
      expect(screen.queryByRole('status')).not.toBeInTheDocument();
      expect(screen.getByLabelText(t('nativeMemberKeys', language))).toHaveValue('');
    },
  );
});

describe('store-scoped operations review', () => {
  it.each(['zh', 'ru'] as const)(
    'loads the entered store and can refresh that exact scope in %s',
    async (language) => {
      const asOf = '2026-09-13T03:00:00Z';
      const reading = (kind: string) => ({ kind, periodStart: asOf, asOf, rows: [] });
      const bundle = {
        asOf,
        storeId: STORE,
        timezone: 'Europe/Moscow',
        current: reading('CURRENT_QUEUE'),
        daily: reading('DAILY_ACTION_BRIEF'),
        weekly: reading('WEEKLY_EVIDENCE_REVIEW'),
      };
      const { context, calls } = backend([[/\/listing\/operations-review\?/u, bundle]]);
      render(
        <LanguageProvider initial={language}>
          <ListingOperationsReviewPanel context={context} storeId={STORE} />
        </LanguageProvider>,
      );
      const form = screen.getByRole('form', { name: t('operationsReviewLoad', language) });
      const input = within(form).getByLabelText(t('operationsReviewStore', language));
      expect(input).toHaveValue(STORE);
      await waitFor(() => {
        expect(
          calls.filter((call) => call.url.includes('/listing/operations-review?')),
        ).toHaveLength(1);
      });
      fireEvent.change(input, { target: { value: 'actual-authorized-store' } });
      fireEvent.submit(form);
      await waitFor(() => {
        expect(
          calls.filter((call) => call.url.includes('/listing/operations-review?')),
        ).toHaveLength(2);
      });
      expect(calls.at(-1)?.url).toContain('storeId=actual-authorized-store');
      fireEvent.submit(form);
      await waitFor(() => {
        expect(
          calls.filter((call) => call.url.includes('/listing/operations-review?')),
        ).toHaveLength(3);
      });
    },
  );
});

describe('finite dependency holds', () => {
  it.each(['zh', 'ru'] as const)(
    'records one bounded hold against the original Task in %s',
    async (language) => {
      const hold = {
        id: 'hold-1',
        dependencyTaskId: 'dependency-task-1',
        minutes: 15,
        evidenceReference: 'fixture://dependency/confirmed',
        startedAt: '2026-09-13T03:00:00Z',
        expiresAt: '2026-09-13T03:15:00Z',
        state: 'ACTIVE',
        endedAt: null,
        endReason: null,
      };
      const { context, calls } = backend([[/\/dependency-holds$/u, hold]]);
      render(
        <LanguageProvider initial={language}>
          <ListingDependencyHold
            context={context}
            target={{ kind: 'ACTION', actionId: 'action-1' }}
          />
        </LanguageProvider>,
      );
      const region = screen.getByRole('region', { name: t('dependencyHoldTitle', language) });
      fireEvent.change(within(region).getByLabelText(t('dependencyTask', language)), {
        target: { value: hold.dependencyTaskId },
      });
      fireEvent.change(within(region).getByLabelText(t('dependencyHoldMinutes', language)), {
        target: { value: '15' },
      });
      fireEvent.change(within(region).getByLabelText(t('dependencyHoldEvidence', language)), {
        target: { value: hold.evidenceReference },
      });
      fireEvent.click(
        within(region).getByRole('button', { name: t('dependencyHoldSubmit', language) }),
      );
      await waitFor(() => {
        expect(within(region).getByText('ACTIVE')).toBeInTheDocument();
      });
      expect(calls).toHaveLength(1);
      expect(calls[0]?.url).toContain('/actions/action-1/responsibility/dependency-holds');
      expect(JSON.parse(calls[0]?.body ?? '{}')).toEqual({
        dependencyTaskId: hold.dependencyTaskId,
        minutes: 15,
        evidenceReference: hold.evidenceReference,
      });
    },
  );
});

it('discards a late review basis when the authenticated context changes', async () => {
  let resolve: ((response: Response) => void) | undefined;
  const pending = new Promise<Response>((done) => {
    resolve = done;
  });
  const fetchImpl = vi.fn(() => pending) as unknown as typeof fetch;
  const context: ConsoleRequest = {
    apiBaseUrl: 'http://127.0.0.1:8080',
    accessToken: 'first-user',
    fetchImpl,
  };
  const onOutcome = vi.fn();
  const form = (ctx: ConsoleRequest) => (
    <LanguageProvider initial="zh">
      <ListingMeaningReview context={ctx} actionId="a1" reason="Review" onOutcome={onOutcome} />
    </LanguageProvider>
  );
  const view = render(form(context));
  fireEvent.click(screen.getByRole('button', { name: '读取含义审核依据' }));
  view.rerender(form({ ...context, accessToken: 'second-user' }));
  await act(async () => {
    resolve?.(
      new Response(
        JSON.stringify({
          actionId: 'a1',
          basisDigest: 'old-basis',
          ruleState: 'QUALIFIED',
          currentText: 'Старый закрытый текст',
          conditions: [{ code: 'CHECK', condition: 'Old condition', axis: 'MATERIAL' }],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    await pending;
  });
  expect(screen.queryByText('Старый закрытый текст')).not.toBeInTheDocument();
  expect(screen.queryByText('Old condition')).not.toBeInTheDocument();
  expect(onOutcome).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: t('attest', 'zh') })).toBeDisabled();
});
