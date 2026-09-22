import { describe, expect, it, vi } from 'vitest';
import type { ConsoleRequest } from '../api/console';
import * as api from '../api/listingConversion';

/** Every route the listing client knows, answered with the smallest valid body. */
const LISTING = 'aa14dd95-b455-5db2-924c-8a3972e6f9d2';
const HEALTH = {
  id: 'h1',
  storeId: 's1',
  platformListingId: LISTING,
  nativeListingKey: 'k',
  healthVersion: 1,
  necessaryConditions: [],
  necessaryState: 'UNKNOWN',
  eligibility: { MEASUREMENT: 'UNKNOWN', PROTECTION: 'UNKNOWN', EVALUATION: 'UNKNOWN' },
  opportunities: [],
  affectedSetState: 'INCOMPLETE',
  affectedVariantCount: 0,
  computedAt: 't',
};
const MEASUREMENT = {
  id: 'm1',
  platformListingId: LISTING,
  windowStart: 'a',
  windowEnd: 'b',
  retentionWindowDays: 7,
  evidencePath: 'DETAIL',
  pathQualified: true,
  ratioState: 'DEFINED',
  primaryRatio: 0.25,
  visitCount: 4,
  retainedPurchaseVisitCount: 1,
  maturityReached: true,
  sourceStratified: true,
  computedAt: 't',
};
const ACTION = {
  id: 'a1',
  storeId: 's1',
  platformListingId: LISTING,
  nativeListingKey: 'k',
  candidateId: 'c1',
  recommendationId: 'r1',
  recommendationVersion: 1,
  recommendationState: 'READY_FOR_REVIEW',
  affectedSetDigest: 'd',
  affectedSetState: 'COMPLETE',
  affectedVariantCount: 1,
  actionKind: 'LISTING_DESCRIPTION_CHANGE',
  executionPath: 'MANUAL',
  materialityRoute: 'ORDINARY_IMPACT',
  authorUserId: 'u1',
  state: 'DRAFT',
  reviews: [],
  occupations: [],
  version: 1,
  binding: { id: 'b1', state: 'BOUND', expiresAt: 't', inapplicableReason: null },
  launch: { id: 'l1', launchedByUserId: 'u', launchedAt: 't' },
};
const CANDIDATE = {
  id: 'c1',
  storeId: 's1',
  platformListingId: LISTING,
  candidateKind: 'CONTENT_DESCRIPTION',
  comparisonRoundKey: 'r',
  state: 'OPEN',
  version: 1,
};
const PACKET = {
  id: 'p1',
  actionId: 'a1',
  executorUserId: 'u1',
  issuedAt: 't',
  expiresAt: 't',
  nativeListingKey: 'k',
  state: 'ISSUED',
  reports: [{ id: 'rp', reporterUserId: 'u1', reportState: 'APPLIED', note: 'n' }],
  verifications: [
    {
      id: 'v',
      verificationBasis: 'INDEPENDENT_HUMAN',
      managementMatch: 'MATCHED_TARGET',
      displayState: 'DISPLAYED',
    },
  ],
  version: 1,
};
const ENGAGEMENT = {
  id: 'e1',
  platformListingId: LISTING,
  engagementKind: 'SELLER_DIRECT_DISCOUNT',
  fullDisclosure: false,
  priceFreeze: false,
  autoParticipation: false,
  adopted: true,
  state: 'ACTIVE',
  version: 1,
};
const PROMOTION_TERMS = {
  engagementKind: 'SELLER_DIRECT_DISCOUNT',
  nativePromotionKey: 'promotion-1',
  terms: { finalPrice: '200.0000' },
  priceFreeze: true,
  autoParticipation: false,
  termsEvidenceReference: 'evidence://promotion/terms',
  obligations: { fixedFee: '10.0000' },
};
const PROMOTION_CONTEXT = {
  coverageStart: '2026-09-01T00:00:00Z',
  coverageEnd: '2026-09-30T00:00:00Z',
  verificationExpiresAt: '2026-10-01T00:00:00Z',
  records: [
    {
      declaration: PROMOTION_TERMS,
      participationState: 'PARTICIPATING',
      effectiveFrom: '2026-09-01T00:00:00Z',
      effectiveTo: '2026-09-30T00:00:00Z',
      newTransactionsState: 'OPEN',
      residualObligationState: 'OUTSTANDING',
      originalAuthorityReference: 'authority://promotion/original',
      originalAuthorityValidUntil: '2026-10-01T00:00:00Z',
      axisDemands: {
        REVENUE_EXPOSURE: {
          value: '500.0000',
          unitCode: 'RUB',
          evidenceReference: 'evidence://promotion/revenue',
        },
      },
    },
  ],
};
const BATCH = {
  id: 'b1',
  storeId: 's1',
  batchCode: 'code',
  state: 'OPEN',
  members: [{ actionId: 'a1', membershipState: 'MEMBER', sequenceNo: 1, actionState: 'DRAFT' }],
  version: 1,
};
const CONTAINMENT = {
  id: 'c1',
  scopeKind: 'ORGANIZATION',
  causeClass: 'PLATFORM_INCIDENT',
  causeOwnerRoleCode: 'OWNER',
  stoppedByUserId: 'u',
  stoppedAt: 't',
  reason: 'r',
  state: 'ACTIVE',
  attestations: [],
};
const ENTRY = {
  id: 'q1',
  triggerClass: 'ORDINARY',
  targetMinutes: 15,
  platformListingId: LISTING,
  state: 'FINISHED',
  acceptedAt: 't',
  latencySeconds: 12,
  withinTarget: true,
};
const EVALUATION = {
  planId: 'pl',
  actionId: 'a1',
  comparisonBasis: 'PRIOR_VERSION_WINDOW',
  crossPeriodWindowDays: 30,
  formalNodes: [
    { nodeCode: 'D14', maturityDays: 14, method: 'WILSON_LOWER_BOUND', threshold: '0.05' },
  ],
  stopRule: { nodeCode: 'D14' },
  results: [
    {
      id: 'n1',
      nodeCode: 'D14',
      stage: 'OPERATIONAL',
      revisionNo: 1,
      verdict: 'MET',
      protectionVerdict: 'PASS',
      protectionVector: { SUPPLY_COVERAGE: 'PASS' },
      primaryRatio: '0.06',
      conservativeBound: '0.051',
      acceptedThreshold: '0.05',
    },
  ],
};
const COMMAND = {
  id: 'cmd',
  actionId: 'a1',
  state: 'PENDING',
  priorTextCaptured: false,
  kizMarkedDeclared: false,
  equivalenceRule: 'EXACT',
  attemptNo: 1,
  retryBudgetRemaining: 2,
  approvalExpiresAt: 't',
  attempts: [{ id: 'at', attemptNo: 1, purpose: 'APPLY', outcomeClass: 'TIMEOUT', errorCode: 'x' }],
  readbacks: [{ id: 'rb', matchState: 'UNREADABLE', observedAt: 't' }],
  executionReceipts: [],
};

const ROUTES: readonly (readonly [RegExp, unknown])[] = [
  [/\/listing-description-commands\/actions\//u, COMMAND],
  [/\/listing-description-commands\/[^/]+\/gate$/u, { reasons: ['ACTION_NOT_LAUNCHED'] }],
  [/\/health\/queue/u, [HEALTH]],
  [/\/health\/listings\/[^/]+\/recompute$/u, HEALTH],
  [/\/health\/listings\/[^/]+\/measurements$/u, MEASUREMENT],
  [/\/health\/listings\/[^/]+\/facts\//u, { observationId: 'obs' }],
  [
    /\/health\/listings\/[^/]+$/u,
    {
      listingId: LISTING,
      storeId: 's1',
      platformCode: 'P',
      nativeListingKey: 'k',
      health: null,
      measurements: [MEASUREMENT],
    },
  ],
  [/\/actions\/candidates\/[^/]+\/prepare$/u, ACTION],
  [/\/actions\/candidates$/u, CANDIDATE],
  [/\/actions\/candidates\?/u, [CANDIDATE]],
  [/\/actions\/occupations\/[^/]+\/release$/u, { state: 'RELEASED' }],
  [/\/actions\/[^/]+\/review$/u, ACTION],
  [/\/actions\/[^/]+\/cancel$/u, { state: 'CANCELLED' }],
  [
    /\/actions\/[^/]+\/allowance-preview$/u,
    { platformListingId: LISTING, resolved: false, axes: [] },
  ],
  [
    /\/actions\/[^/]+\/launch$/u,
    { launched: true, launchId: 'l', occupationIds: ['o'], insufficientAxes: [] },
  ],
  [/\/actions\/[^/]+\/evaluation\/nodes$/u, EVALUATION],
  [/\/actions\/[^/]+\/evaluation$/u, EVALUATION],
  [/\/actions\?/u, [ACTION]],
  [/\/actions\/[^/?]+$/u, ACTION],
  [/\/manual\/packets\/[^/]+\/(report|verify)$/u, PACKET],
  [/\/manual\/packets\?/u, [PACKET]],
  [
    /\/manual\/actions\/[^/]+\/packets$/u,
    (method: string) => (method === 'GET' ? [PACKET] : PACKET),
  ],
  [/\/manual\/engagements\/[^/]+\/(exit|release)$/u, ENGAGEMENT],
  [/\/manual\/engagements\?/u, [ENGAGEMENT]],
  [/\/manual\/listings\/[^/]+\/engagements$/u, ENGAGEMENT],
  [/\/governance\/batches\/[^/]+\/(members|close)$/u, BATCH],
  [/\/governance\/batches\?/u, [BATCH]],
  [/\/governance\/batches$/u, BATCH],
  [/\/governance\/containments\/stop$/u, CONTAINMENT],
  [/\/governance\/containments\/[^/]+\/(attest|reenable)$/u, CONTAINMENT],
  [/\/governance\/containments\?/u, [CONTAINMENT]],
  [/\/governance\/recalculation-queue/u, [ENTRY]],
];

function backend(): {
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
    const route = ROUTES.find(([pattern]) => pattern.test(url));
    const method = init?.method ?? 'GET';
    const body =
      route === undefined
        ? { detail: 'unrouted ' + url }
        : typeof route[1] === 'function'
          ? (route[1] as (method: string) => unknown)(method)
          : route[1];
    return Promise.resolve(
      new Response(JSON.stringify(body), {
        status: route === undefined ? 404 : 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  }) as unknown as typeof fetch;
  return {
    context: { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'token', fetchImpl },
    calls,
  };
}

describe('the listing client covers every console route', () => {
  it('TC-UI-LC-API-001 each request goes to its route with its method and is parsed', async () => {
    const { context, calls } = backend();
    const answers = await Promise.all([
      api.fetchHealthQueue(context, 'PASS'),
      api.fetchListingDetail(context, LISTING),
      api.recomputeHealth(context, LISTING),
      api.measureConversion(context, LISTING, 'a', 'b', 14, 'DETAIL'),
      api.recordDescriptionFact(context, LISTING, 'текст', undefined, ''),
      api.recordDisplayFact(context, LISTING, 'DISPLAYED', '', 'ref'),
      api.recordPromotionFact(
        context,
        LISTING,
        PROMOTION_TERMS,
        'PARTICIPATING',
        '2026-09-13T00:00:00Z',
        'evidence://promotion/observation',
        undefined,
        PROMOTION_CONTEXT,
      ),
      api.fetchActions(context, 'DRAFT'),
      api.fetchAction(context, 'a1'),
      api.fetchCandidates(context, LISTING),
      api.prepareCandidate(context, LISTING, 'CONTENT_DESCRIPTION', 'r', ['e']),
      api.prepareAction(context, 'c1', 'API', 'текст', true),
      api.reviewAction(context, 'a1', 'ATTESTED', 'ok'),
      api.cancelAction(context, 'a1', 'why'),
      api.previewAllowance(context, 'a1'),
      api.launchAction(context, 'a1'),
      api.releaseOccupation(context, 'o1', 'STOP_EVIDENCE', 'ev', 'ref'),
      api.fetchEvaluation(context, 'a1'),
      api.evaluateNode(context, 'a1', {
        nodeCode: 'D14',
        stage: 'OPERATIONAL',
        lateFactReference: 'fixture://late-evaluation',
      }),
      api.fetchDescriptionCommand(context, 'a1'),
      api.fetchDescriptionGate(context, 'cmd'),
      api.fetchMyPackets(context),
      api.fetchActionPackets(context, 'a1'),
      api.issuePacket(context, 'a1', 'u1'),
      api.reportPacket(context, 'p1', 't', 'APPLIED', 'n'),
      api.verifyPacket(context, 'p1', 'INDEPENDENT_HUMAN', 'MATCHED_TARGET', 'DISPLAYED', 'n'),
      api.fetchEngagements(context, LISTING),
      api.adoptEngagement(
        context,
        LISTING,
        PROMOTION_TERMS,
        'context-observation-1',
        'authority://promotion/original',
        '2026-10-01T00:00:00Z',
        'responsible-user-1',
      ),
      api.authorizeExit(context, 'e1', 'OWNER_DECISION', 'authority://exit', 'evidence-1'),
      api.releaseEngagement(
        context,
        'e1',
        'NEW_TRANSACTIONS_STOPPED',
        'observation-1',
        'evidence://stop',
      ),
      api.fetchBatches(context),
      api.createBatch(context, 's1', 'code'),
      api.addBatchMember(context, 'b1', 'a1'),
      api.closeBatch(context, 'b1'),
      api.fetchContainments(context, false),
      api.stopScope(context, 'ORGANIZATION', undefined, 'PLATFORM_INCIDENT', 'OWNER', 'r', 'e'),
      api.attestContainment(context, 'c1', 'BUSINESS_CONSENT', 'e'),
      api.reenableContainment(context, 'c1'),
      api.fetchRecalculationQueue(context),
    ]);

    for (const [index, answer] of answers.entries()) {
      expect(answer.ok, `answer ${String(index)}: ${JSON.stringify(answer)}`).toBe(true);
    }
    expect(calls).toHaveLength(answers.length);
    expect(calls.find((call) => call.url.endsWith('/candidates/c1/prepare'))?.body).not.toContain(
      'exposureShare',
    );
    expect(calls.find((call) => call.url.endsWith('/evaluation/nodes'))?.body).toContain(
      '"nodeCode":"D14"',
    );
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(25);
    expect(calls.find((call) => call.url.includes('/facts/description'))?.body).toContain(
      '"languageCode":"ru"',
    );
    expect(calls.find((call) => call.url.includes('/facts/description'))?.body).toContain(
      '"kizMarkedDeclared":null',
    );
    expect(
      JSON.parse(calls.find((call) => call.url.includes('/facts/promotion'))?.body ?? '{}'),
    ).toMatchObject({ context: PROMOTION_CONTEXT });
    expect(
      JSON.parse(
        calls.find((call) => /\/manual\/listings\/[^/]+\/engagements$/u.test(call.url))?.body ??
          '{}',
      ),
    ).toMatchObject({
      ...PROMOTION_TERMS,
      contextObservationId: 'context-observation-1',
      originalAuthorityReference: 'authority://promotion/original',
      responsibleUserId: 'responsible-user-1',
    });
    expect(calls.find((call) => call.url.includes('/containments/stop'))?.body).toContain(
      '"platformListingId":null',
    );
    expect(calls.find((call) => call.url.includes('containments?'))?.url).toContain(
      'activeOnly=false',
    );
  });

  it('TC-UI-LC-API-002 the parsers keep every optional field honest', () => {
    const measurement = api.parseConversionMeasurement(MEASUREMENT);
    expect(measurement?.primaryRatio).toBe('0.25');
    expect(measurement?.sellableSplit).toEqual({});
    expect(api.parseListingAction(ACTION)?.binding?.state).toBe('BOUND');
    expect(api.parseListingAction({ ...ACTION, launch: { id: 'x' } })).toBeUndefined();
    expect(api.parseManualPacket(PACKET)?.reports[0]?.reportState).toBe('APPLIED');
    expect(api.parseManualPacket({ ...PACKET, reports: [{}] })).toBeUndefined();
    expect(api.parsePromotionEngagement({ ...ENGAGEMENT, adopted: 'yes' })).toBeUndefined();
    expect(api.parseBatch(BATCH)?.members).toHaveLength(1);
    expect(api.parseBatch({ ...BATCH, members: [{ actionId: 1 }] })).toBeUndefined();
    expect(api.parseContainment(CONTAINMENT)?.platformListingId).toBeUndefined();
    expect(
      api.parseContainment({ ...CONTAINMENT, attestations: [{ attestationKind: 'X' }] }),
    ).toBeUndefined();
    expect(api.parseRecalculationEntry(ENTRY)?.latencySeconds).toBe(12);
    expect(api.parseRecalculationEntry({ ...ENTRY, withinTarget: 'yes' })).toBeUndefined();
    expect(api.parseEvaluation(EVALUATION)?.results[0]?.protectionVector).toEqual({
      SUPPLY_COVERAGE: 'PASS',
    });
    const observedEvaluation = api.parseEvaluation({
      ...EVALUATION,
      results: [
        {
          ...EVALUATION.results[0],
          conservativeBound: null,
          evaluationEvidence: {
            measurementAcquiredAt: null,
            measurementComputedAt: '2026-09-13T00:00:00Z',
            qualificationGaps: ['QUALIFIED_CONTROL_AND_VERSION_COVERAGE_UNRESOLVED'],
            fixedTrafficObservation: {
              state: 'OBSERVATIONAL_ONLY',
              referenceStandardized: '0.1',
              targetStandardized: '0.12',
              observedDifference: '0.02',
            },
          },
        },
      ],
    })?.results[0];
    expect(observedEvaluation?.conservativeBound).toBeUndefined();
    expect(observedEvaluation?.measurementAcquiredAt).toBeUndefined();
    expect(observedEvaluation?.measurementComputedAt).toBe('2026-09-13T00:00:00Z');
    expect(observedEvaluation?.fixedTrafficObservation?.observedDifference).toBe('0.02');
    expect(observedEvaluation?.qualificationGaps).toEqual([
      'QUALIFIED_CONTROL_AND_VERSION_COVERAGE_UNRESOLVED',
    ]);
    expect(
      api.parseEvaluation({ ...EVALUATION, formalNodes: [{ nodeCode: 'X' }] }),
    ).toBeUndefined();
    expect(api.parseEvaluation({ ...EVALUATION, results: [{ id: 'x' }] })).toBeUndefined();
    const revision = {
      id: 'revision',
      originalNodeResultId: 'original',
      revisedNodeResultId: 'revised',
      revisionReason: 'LATE_FACT',
      lateFactReference: 'fixture://late-fact',
      recordedAt: '2026-09-13T00:00:00Z',
    };
    const frozenHistory = api.parseEvaluation({
      ...EVALUATION,
      planDigest: 'a'.repeat(64),
      frozenAt: '2026-08-01T00:00:00Z',
      latestBoundary: '2026-09-30T00:00:00Z',
      revisions: [revision],
    });
    expect(frozenHistory?.revisions).toEqual([revision]);
    expect(frozenHistory?.latestBoundary).toBe('2026-09-30T00:00:00Z');
    expect(frozenHistory?.planDigest).toBe('a'.repeat(64));
    expect(
      api.parseEvaluation({
        ...EVALUATION,
        revisions: [{ ...revision, originalNodeResultId: null }],
      }),
    ).toBeUndefined();
    expect(api.parseDescriptionCommand(COMMAND)?.attempts[0]?.errorCode).toBe('x');
    const receipt = {
      id: 'receipt',
      executionState: 'MANAGEMENT_VERIFIED',
      readbackId: 'rb',
      mutationAttemptId: 'apply',
      nativeStatusAttemptId: 'status',
      gaps: [],
      recordedAt: 't',
      taskEventId: 'event',
      taskRecordedAt: 't2',
    };
    expect(
      api.parseDescriptionCommand({ ...COMMAND, executionReceipts: [receipt] })
        ?.executionReceipts[0]?.taskEventId,
    ).toBe('event');
    expect(
      api.parseDescriptionCommand({
        ...COMMAND,
        executionReceipts: [{ ...receipt, executionState: 'BUSINESS_SUCCESS' }],
      }),
    ).toBeUndefined();
    expect(
      api.parseDescriptionCommand({ ...COMMAND, executionReceipts: [{ ...receipt, gaps: [42] }] }),
    ).toBeUndefined();
    expect(
      api.parseDescriptionCommand({ ...COMMAND, executionReceipts: undefined }),
    ).toBeUndefined();
    expect(api.parseDescriptionCommand({ ...COMMAND, readbacks: [{ id: 'x' }] })).toBeUndefined();
    expect(api.parseDescriptionCommand({ ...COMMAND, attempts: [{ id: 'x' }] })).toBeUndefined();
    expect(api.parseCandidate({ ...CANDIDATE, version: 'v' })).toBeUndefined();
    expect(
      api.parseAllowance({ platformListingId: LISTING, resolved: true, axes: [{ axisCode: 'X' }] }),
    ).toBeUndefined();
    expect(
      api.parseListingDetail({
        listingId: LISTING,
        storeId: 's',
        platformCode: 'P',
        nativeListingKey: 'k',
        health: { id: 'bad' },
        measurements: [],
      }),
    ).toBeUndefined();
    expect(
      api.parseListingDetail({
        listingId: LISTING,
        storeId: 's',
        platformCode: 'P',
        nativeListingKey: 'k',
        measurements: [],
      })?.health,
    ).toBeUndefined();
    expect(api.parseListingHealth({ ...HEALTH, opportunities: [{ code: 'X' }] })).toBeUndefined();
    expect(api.parseListingAction({ ...ACTION, reviews: [{ id: 'x' }] })).toBeUndefined();
    expect(api.parseListingAction({ ...ACTION, occupations: [{ id: 'x' }] })).toBeUndefined();
  });
});

describe('exact meaning review basis', () => {
  const basis = {
    actionId: 'a1',
    basisDigest: 'basis',
    ruleState: 'QUALIFIED',
    currentText: 'Не использовать для детей.\r\n',
    targetText: 'Использовать для детей.\r\n',
    conditions: [
      { code: 'SAFETY_CHANGE', condition: 'Изменение ограничений безопасности', axis: 'MATERIAL' },
    ],
    affectedSet: {
      affectedSetId: 'set-1',
      digest: 'affected-digest',
      state: 'COMPLETE',
      listingVariantIds: ['listing-variant-1'],
      productVariantIds: ['product-variant-1'],
      nativeScopeObservationId: 'scope-observation-1',
      identityLineage: '{"members":[]}',
    },
    calibrationEvidence: { state: 'CURRENT' },
    materialityEvidence: { state: 'CURRENT' },
    businessProtectionEvidence: { model: 'LC_CURRENT_BUSINESS_PROTECTION_1' },
    authorityDocument: '{}',
    applicableExperience: [],
  };
  it('preserves exact selected simulation decimals and rejects lossy numeric material', () => {
    const selectedSimulation = {
      id: 'simulation',
      inputsDigest: 'digest',
      computedAt: '2026-09-01T00:00:00Z',
      currency: 'RUB',
      periodStart: 'start',
      periodEnd: 'end',
      referenceProfitLine: '9007199254740993.0001',
      inverseMinimumQuantity: null,
      inverseState: 'NO_SOLUTION',
      qualificationState: 'UNQUALIFIED',
      conditionalScenariosPassed: false,
      inputEvidence: '{"original":"evidence"}',
      scenarios: [
        {
          code: 'DOWNSIDE',
          state: 'COMPUTED',
          quantity: '99999999999999',
          netRevenue: '9007199254740993.0001',
          contributionProfit: '-0.0001',
          missingInputs: [],
        },
      ],
    };
    expect(
      api.parseMeaningReviewBasis({ ...basis, selectedSimulation })?.selectedSimulation
        ?.scenarios[0]?.netRevenue,
    ).toBe('9007199254740993.0001');
    expect(
      api.parseMeaningReviewBasis({
        ...basis,
        selectedSimulation: {
          ...selectedSimulation,
          referenceProfitLine: Number('9007199254740993'),
        },
      }),
    ).toBeUndefined();
    expect(
      api.parseMeaningReviewBasis({
        ...basis,
        selectedSimulation: { ...selectedSimulation, referenceProfitLine: 'NaN' },
      }),
    ).toBeUndefined();
  });
  it('preserves full Russian text and rejects incomplete or duplicate conditions', () => {
    expect(api.parseMeaningReviewBasis(basis)?.currentText).toBe(basis.currentText);
    expect(api.parseMeaningReviewBasis(basis)?.targetText).toBe(basis.targetText);
    expect(
      api.parseMeaningReviewBasis({
        ...basis,
        conditions: [basis.conditions[0], basis.conditions[0]],
      }),
    ).toBeUndefined();
    expect(
      api.parseMeaningReviewBasis({ ...basis, conditions: [{ code: 'A', axis: 'MATERIAL' }] }),
    ).toBeUndefined();
    expect(
      api.parseMeaningReviewBasis({
        ...basis,
        conditions: [{ ...basis.conditions[0], axis: 'GUESS' }],
      }),
    ).toBeUndefined();
    expect(
      api.parseMeaningReviewBasis({ ...basis, promotionTerms: { priceFreeze: false } }),
    ).toBeUndefined();
  });
  it('sends the explicit condition answers and exact basis in the review request', async () => {
    const { context, calls } = backend();
    const meaningAssessment: api.MeaningAssessment = {
      model: 'LC_MEANING_REVIEW_1',
      basisDigest: 'basis',
      complete: true,
      evidenceReference: 'evidence://synthetic/meaning',
      answers: [{ code: 'SAFETY_CHANGE', state: 'APPLIES', reason: 'Удалено отрицание' }],
    };
    expect(
      (await api.reviewAction(context, 'a1', 'ATTESTED', 'Complete review', meaningAssessment)).ok,
    ).toBe(true);
    expect(JSON.parse(calls.find((c) => c.url.endsWith('/review'))!.body!)).toEqual({
      verdict: 'ATTESTED',
      reason: 'Complete review',
      meaningAssessment,
    });
  });
});
