/**
 * Every request the operating console makes, and the parsing that stands
 * between a backend answer and the screen.
 *
 * Responses are validated before they are used, for the same reason the
 * metadata client validates: a console that rendered whatever it received would
 * turn a backend defect into a rendering defect that looks like something else.
 * Here it matters more, because the values decide whether somebody changes a
 * real price.
 *
 * The token is passed in rather than read from a module-level store, so the one
 * place a session lives stays the one place, and a test can drive a request
 * without a session at all.
 */

import type { ConfidenceState, ValueState } from '../state/confidence';

/** How long the console waits before treating the backend as unreachable. */
export const REQUEST_TIMEOUT_MS = 10_000;

/** Header carrying the correlation identifier in both directions. */
export const CORRELATION_HEADER = 'X-Correlation-ID';

/** Why a console request did not produce an answer. */
export type ConsoleFailure =
  | { readonly kind: 'unauthenticated' }
  | { readonly kind: 'step-up-required' }
  | { readonly kind: 'forbidden' }
  | { readonly kind: 'refused'; readonly status: number; readonly detail: string }
  | { readonly kind: 'unreachable'; readonly detail: string }
  | { readonly kind: 'malformed'; readonly detail: string };

/** Outcome of one console request. */
export type ConsoleOutcome<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly failure: ConsoleFailure };

/** One visible reason a card sits where it does in the availability queue. */
export interface AvailabilityRankFactor {
  readonly factorCode: string;
  readonly value: string | null;
  readonly weight: string | null;
  readonly contribution: string | null;
  readonly displayNote: string;
}

/** One demand window and how much of it could actually be observed. */
export interface AvailabilityDemandWindow {
  readonly windowCode: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly completedUnits: number | null;
  readonly dailyRate: string | null;
  readonly observedDays: string | null;
  readonly coverageRatio: string | null;
  readonly sampleSufficient: boolean;
  readonly censored: boolean;
  readonly censoringReason: string | null;
  readonly outlierShare: string | null;
  readonly eligibility: string;
}

/** One independently governed child risk. */
export interface AvailabilityChild {
  readonly id: string;
  readonly childKind: 'CHANNEL' | 'COMPANY';
  readonly platformCode: string | null;
  readonly storeId: string | null;
  readonly platformListingVariantId: string | null;
  readonly fulfillmentModeCode: string | null;
  readonly lane: string;
  readonly evidenceState: string;
  readonly confidenceState: string;
  readonly causeCode: string;
  readonly availableUnits: number | null;
  readonly dailyDemandRate: string | null;
  readonly daysOfCover: string | null;
  readonly coverageHorizonDays: number | null;
  readonly projectedStockoutAt: string | null;
  readonly profitLane: string;
  readonly profitAtRiskAmount: string | null;
  readonly profitAtRiskCurrency: string | null;
  readonly demandSelectionReason: string;
  readonly conservativeProofTerms: readonly string[];
  readonly blockerCodes: readonly string[];
  readonly rankFactors: readonly AvailabilityRankFactor[];
  readonly demandWindows: readonly AvailabilityDemandWindow[];
  readonly calculatedAt: string;
}

/** One grouped Internal Variant card. */
export interface AvailabilityCard {
  readonly id: string;
  readonly productVariantId: string;
  readonly skuCode: string;
  readonly displayName: string;
  readonly lane: string;
  readonly triggeringChildId: string | null;
  readonly rankScore: string | null;
  readonly policyVersionDigest: string;
  readonly asOf: string;
  readonly calculatedAt: string;
  readonly children: readonly AvailabilityChild[];
}

/** One subject on the daily work list. */
export interface PrioritySubject {
  readonly subjectId: string;
  readonly storeId: string;
  readonly priorityScore: string;
  readonly criticalFindingCount: number;
  readonly warningFindingCount: number;
  readonly declinedRuleCount: number;
  readonly netSales: string | null;
  readonly contributionProfit: string | null;
  readonly currencyCode: string | null;
  readonly blockingRuleCodes: readonly string[];
}

/** One canonical value, with everything needed to present it honestly. */
export interface MetricValue {
  readonly metricValueId: string;
  readonly metricCode: string;
  readonly valueState: ValueState;
  readonly numericValue: string | null;
  readonly currencyCode: string | null;
  readonly confidenceState: ConfidenceState;
  readonly estimated: boolean;
  readonly freshnessSeconds: number | null;
  readonly evidenceRefs: readonly string[];
}

/** One deterministic rule outcome. */
export interface DiagnosisFinding {
  readonly findingId: string;
  readonly ruleCode: string;
  readonly outcome: 'TRIGGERED' | 'CLEAR' | 'DECLINED';
  readonly severity: 'INFO' | 'WARNING' | 'CRITICAL';
  readonly declineReason: string | null;
  readonly detail: Readonly<Record<string, string>>;
  readonly blocksExecution: boolean;
  readonly metricValueIds: readonly string[];
}

/** One subject's complete diagnostic picture. */
export interface SubjectDiagnosis {
  readonly subjectId: string;
  readonly storeId: string;
  readonly window: string;
  readonly metrics: Readonly<Record<string, MetricValue>>;
  readonly findings: readonly DiagnosisFinding[];
}

/** AI output is advisory. Decimal money is encoded as text on this API. */
export type ClaimValue =
  string | number | boolean | null | readonly ClaimValue[] | { readonly [key: string]: ClaimValue };

export interface ExplanationClaim {
  readonly claimId: string;
  readonly kind: 'FACT' | 'INFERENCE' | 'RECOMMENDATION' | 'UNKNOWN';
  readonly ordinal: number;
  readonly statement: string;
  readonly confidenceLabel: string | null;
  readonly metricValueRefs: readonly string[];
  readonly findingRefs: readonly string[];
  readonly payload: Readonly<Record<string, ClaimValue>>;
  readonly accepted: boolean;
  readonly rejectionCode: string | null;
}

export interface AiExplanation {
  readonly invocationId: string;
  readonly subjectId: string;
  readonly outputSchemaVersion: 2;
  readonly state: string;
  readonly failureCode: string | null;
  readonly degraded: boolean;
  readonly claims: readonly ExplanationClaim[];
}

function boundedClaimValue(value: unknown, depth = 0): value is ClaimValue {
  if (depth > 8) return false;
  if (value === null || typeof value === 'boolean') return true;
  if (typeof value === 'string') return value.length <= 2000;
  if (typeof value === 'number') return Number.isSafeInteger(value);
  if (Array.isArray(value))
    return value.length <= 64 && value.every((item: unknown) => boundedClaimValue(item, depth + 1));
  return (
    isRecord(value) &&
    Object.keys(value).length <= 64 &&
    Object.values(value).every((item) => boundedClaimValue(item, depth + 1))
  );
}

function strictReferences(value: unknown): value is readonly string[] {
  return (
    Array.isArray(value) &&
    value.length <= 20 &&
    new Set(value).size === value.length &&
    value.every(
      (item: unknown) =>
        typeof item === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(item),
    )
  );
}

function parseExplanationClaim(body: unknown): ExplanationClaim | undefined {
  if (
    !isRecord(body) ||
    typeof body.claimId !== 'string' ||
    typeof body.statement !== 'string' ||
    body.statement.length === 0 ||
    body.statement.length > 2000 ||
    typeof body.ordinal !== 'number' ||
    !Number.isInteger(body.ordinal) ||
    body.ordinal < 1 ||
    body.ordinal > 20 ||
    (body.kind !== 'FACT' &&
      body.kind !== 'INFERENCE' &&
      body.kind !== 'RECOMMENDATION' &&
      body.kind !== 'UNKNOWN') ||
    typeof body.accepted !== 'boolean' ||
    !strictReferences(body.metricValueRefs) ||
    !strictReferences(body.findingRefs) ||
    !isRecord(body.payload) ||
    Array.isArray(body.payload) ||
    !boundedClaimValue(body.payload) ||
    (body.confidenceLabel !== null &&
      (typeof body.confidenceLabel !== 'string' ||
        !['LOW', 'MEDIUM', 'HIGH'].includes(body.confidenceLabel))) ||
    (body.accepted ? body.rejectionCode !== null : typeof body.rejectionCode !== 'string')
  )
    return undefined;
  const parameters = body.payload.proposedParameters;
  if (
    isRecord(parameters) &&
    'targetPrice' in parameters &&
    (typeof parameters.targetPrice !== 'string' ||
      !/^\d{1,14}(?:\.\d{1,4})?$/.test(parameters.targetPrice))
  )
    return undefined;
  return {
    claimId: body.claimId,
    kind: body.kind,
    ordinal: body.ordinal,
    statement: body.statement,
    confidenceLabel: body.confidenceLabel,
    metricValueRefs: body.metricValueRefs,
    findingRefs: body.findingRefs,
    payload: body.payload,
    accepted: body.accepted,
    rejectionCode: body.rejectionCode as string | null,
  };
}

/** Malformed members reject the response; partial failures are never filtered away. */
export function parseAiExplanation(body: unknown): AiExplanation | undefined {
  if (
    !isRecord(body) ||
    body.outputSchemaVersion !== 2 ||
    typeof body.invocationId !== 'string' ||
    typeof body.subjectId !== 'string' ||
    typeof body.state !== 'string' ||
    typeof body.degraded !== 'boolean' ||
    (body.failureCode !== null && typeof body.failureCode !== 'string') ||
    !Array.isArray(body.claims) ||
    body.claims.length > 80
  )
    return undefined;
  const claims = body.claims.map(parseExplanationClaim);
  if (claims.some((claim) => claim === undefined)) return undefined;
  const validated = claims.filter((claim): claim is ExplanationClaim => claim !== undefined);
  const accepted = validated.filter((claim) => claim.accepted).length;
  const rejected = validated.length - accepted;
  const states = [
    'PREPARED',
    'DISPATCHED',
    'SUCCEEDED',
    'PARTIAL_OUTPUT_REJECTED',
    'OUTPUT_REJECTED',
    'REFUSED',
    'PROVIDER_FAILED',
    'PROVIDER_OUTCOME_UNKNOWN',
  ];
  if (
    !states.includes(body.state) ||
    (body.state === 'SUCCEEDED' && (accepted === 0 || rejected !== 0 || body.degraded)) ||
    (body.state === 'PARTIAL_OUTPUT_REJECTED' &&
      (accepted === 0 || rejected === 0 || !body.degraded)) ||
    (body.state === 'OUTPUT_REJECTED' && (accepted !== 0 || !body.degraded)) ||
    (['REFUSED', 'PROVIDER_FAILED', 'PROVIDER_OUTCOME_UNKNOWN'].includes(body.state) &&
      (!body.degraded || validated.length !== 0)) ||
    (['PREPARED', 'DISPATCHED'].includes(body.state) && validated.length !== 0)
  )
    return undefined;
  return {
    invocationId: body.invocationId,
    subjectId: body.subjectId,
    outputSchemaVersion: 2,
    state: body.state,
    failureCode: body.failureCode,
    degraded: body.degraded,
    claims: validated,
  };
}

/** One proposal awaiting a decision. */
export interface Recommendation {
  readonly id: string;
  readonly storeId: string;
  readonly subjectId: string;
  readonly actionKind: string;
  readonly origin: string;
  readonly state: string;
  readonly priorityScore: string;
  readonly proposedParameters: Readonly<Record<string, string>>;
  readonly expectedEffect: Readonly<Record<string, string>>;
  readonly riskLabel: string;
  readonly validUntil: string;
  readonly terminalReason: string | null;
  readonly version: number;
}

/** What a price change would do, and whether it is currently allowed. */
export interface ImpactPreview {
  readonly recommendationId: string;
  readonly currencyCode: string | null;
  readonly currentPrice: string | null;
  readonly proposedPrice: string;
  readonly changeRate: string | null;
  readonly breakEvenPrice: string | null;
  readonly currentUnitProfit: string | null;
  readonly projectedUnitProfit: string | null;
  readonly currentMargin: string | null;
  readonly projectedMargin: string | null;
  readonly verdict: GuardrailVerdict;
}

/** The deterministic decision about one proposal. */
export interface GuardrailVerdict {
  readonly evaluationId: string;
  readonly purpose: string;
  readonly passed: boolean;
  readonly reasons: readonly string[];
  readonly policyVersion: number | null;
  readonly detail: Readonly<Record<string, string>>;
}

/** One command and everything that happened to it. */
export interface PriceCommand {
  readonly id: string;
  readonly recommendationId: string;
  readonly storeId: string;
  readonly platformCode: string;
  readonly fulfillmentModeCode: string | null;
  readonly currencyCode: string;
  readonly priorPrice: string;
  readonly targetPrice: string;
  readonly state: string;
  readonly attemptNo: number;
  readonly failureCode: string | null;
  readonly attempts: readonly CommandAttempt[];
  readonly readbacks: readonly CommandReadback[];
}

/** One call made against a marketplace. */
export interface CommandAttempt {
  readonly id: string;
  readonly attemptNo: number;
  readonly purpose: string;
  readonly outcomeClass: string;
  readonly nativeStatus: string | null;
  readonly errorCode: string | null;
  readonly startedAt: string;
  readonly completedAt: string | null;
}

/** What a later read of the marketplace observed. */
export interface CommandReadback {
  readonly id: string;
  readonly observedAt: string;
  readonly observedPrice: string | null;
  readonly currencyCode: string | null;
  readonly matchState: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function text(source: Record<string, unknown>, key: string): string | undefined {
  const value = source[key];
  return typeof value === 'string' ? value : undefined;
}

function optionalText(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  return typeof value === 'string' ? value : null;
}

function integer(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  return typeof value === 'number' ? value : 0;
}

function optionalNumber(source: Record<string, unknown>, key: string): number | null {
  const value = source[key];
  return typeof value === 'number' ? value : null;
}

function textList(source: Record<string, unknown>, key: string): readonly string[] {
  const value = source[key];
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
}

function textMap(source: Record<string, unknown>, key: string): Readonly<Record<string, string>> {
  const value = source[key];
  if (!isRecord(value)) {
    return {};
  }
  const entries: Record<string, string> = {};
  for (const [name, item] of Object.entries(value)) {
    if (typeof item === 'string') {
      entries[name] = item;
    }
  }
  return entries;
}

/**
 * Decode a decimal the backend sent.
 *
 * Amounts travel as text and stay text all the way to the screen. Parsing one
 * into a JavaScript number would silently round it, and a rounded price is a
 * different price.
 */
function decimal(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  if (typeof value === 'string') {
    return value;
  }
  return typeof value === 'number' ? String(value) : null;
}

function parseRankFactor(body: unknown): AvailabilityRankFactor | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const factorCode = text(body, 'factorCode');
  const displayNote = text(body, 'displayNote');
  if (factorCode === undefined || displayNote === undefined) {
    return undefined;
  }
  return {
    factorCode,
    value: decimal(body, 'value'),
    weight: decimal(body, 'weight'),
    contribution: decimal(body, 'contribution'),
    displayNote,
  };
}

function parseDemandWindow(body: unknown): AvailabilityDemandWindow | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const windowCode = text(body, 'windowCode');
  const periodStart = text(body, 'periodStart');
  const periodEnd = text(body, 'periodEnd');
  const eligibility = text(body, 'eligibility');
  if (
    windowCode === undefined ||
    periodStart === undefined ||
    periodEnd === undefined ||
    eligibility === undefined
  ) {
    return undefined;
  }
  return {
    windowCode,
    periodStart,
    periodEnd,
    completedUnits: optionalNumber(body, 'completedUnits'),
    dailyRate: decimal(body, 'dailyRate'),
    observedDays: decimal(body, 'observedDays'),
    coverageRatio: decimal(body, 'coverageRatio'),
    sampleSufficient: body.sampleSufficient === true,
    censored: body.censored === true,
    censoringReason: optionalText(body, 'censoringReason'),
    outlierShare: decimal(body, 'outlierShare'),
    eligibility,
  };
}

/**
 * Read one child risk.
 *
 * A child whose kind, lane or evidence state is missing is dropped rather than
 * defaulted. A card that rendered an unknown evidence state as a confirmed one
 * would be exactly the false safety the whole surface exists to prevent.
 */
function parseAvailabilityChild(body: unknown): AvailabilityChild | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const id = text(body, 'id');
  const childKind = text(body, 'childKind');
  const lane = text(body, 'lane');
  const evidenceState = text(body, 'evidenceState');
  const confidenceState = text(body, 'confidenceState');
  const causeCode = text(body, 'causeCode');
  const profitLane = text(body, 'profitLane');
  const demandSelectionReason = text(body, 'demandSelectionReason');
  const calculatedAt = text(body, 'calculatedAt');
  if (
    id === undefined ||
    (childKind !== 'CHANNEL' && childKind !== 'COMPANY') ||
    lane === undefined ||
    evidenceState === undefined ||
    confidenceState === undefined ||
    causeCode === undefined ||
    profitLane === undefined ||
    demandSelectionReason === undefined ||
    calculatedAt === undefined
  ) {
    return undefined;
  }
  const factors = Array.isArray(body.rankFactors)
    ? body.rankFactors
        .map(parseRankFactor)
        .filter((factor): factor is AvailabilityRankFactor => factor !== undefined)
    : [];
  const windows = Array.isArray(body.demandWindows)
    ? body.demandWindows
        .map(parseDemandWindow)
        .filter((window): window is AvailabilityDemandWindow => window !== undefined)
    : [];
  return {
    id,
    childKind,
    platformCode: optionalText(body, 'platformCode'),
    storeId: optionalText(body, 'storeId'),
    platformListingVariantId: optionalText(body, 'platformListingVariantId'),
    fulfillmentModeCode: optionalText(body, 'fulfillmentModeCode'),
    lane,
    evidenceState,
    confidenceState,
    causeCode,
    availableUnits: optionalNumber(body, 'availableUnits'),
    dailyDemandRate: decimal(body, 'dailyDemandRate'),
    daysOfCover: decimal(body, 'daysOfCover'),
    coverageHorizonDays: optionalNumber(body, 'coverageHorizonDays'),
    projectedStockoutAt: optionalText(body, 'projectedStockoutAt'),
    profitLane,
    profitAtRiskAmount: decimal(body, 'profitAtRiskAmount'),
    profitAtRiskCurrency: optionalText(body, 'profitAtRiskCurrency'),
    demandSelectionReason,
    conservativeProofTerms: textList(body, 'conservativeProofTerms'),
    blockerCodes: textList(body, 'blockerCodes'),
    rankFactors: factors,
    demandWindows: windows,
    calculatedAt,
  };
}

/** Read one grouped card. */
export function parseAvailabilityCard(body: unknown): AvailabilityCard | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const id = text(body, 'id');
  const productVariantId = text(body, 'productVariantId');
  const skuCode = text(body, 'skuCode');
  const displayName = text(body, 'displayName');
  const lane = text(body, 'lane');
  const policyVersionDigest = text(body, 'policyVersionDigest');
  const asOf = text(body, 'asOf');
  const calculatedAt = text(body, 'calculatedAt');
  if (
    id === undefined ||
    productVariantId === undefined ||
    skuCode === undefined ||
    displayName === undefined ||
    lane === undefined ||
    policyVersionDigest === undefined ||
    asOf === undefined ||
    calculatedAt === undefined
  ) {
    return undefined;
  }
  const children = Array.isArray(body.children)
    ? body.children
        .map(parseAvailabilityChild)
        .filter((child): child is AvailabilityChild => child !== undefined)
    : [];
  return {
    id,
    productVariantId,
    skuCode,
    displayName,
    lane,
    triggeringChildId: optionalText(body, 'triggeringChildId'),
    rankScore: decimal(body, 'rankScore'),
    policyVersionDigest,
    asOf,
    calculatedAt,
    children,
  };
}

/** Read one subject from the priority queue. */
export function parsePrioritySubject(body: unknown): PrioritySubject | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const subjectId = text(body, 'subjectId');
  const storeId = text(body, 'storeId');
  if (subjectId === undefined || storeId === undefined) {
    return undefined;
  }
  return {
    subjectId,
    storeId,
    priorityScore: decimal(body, 'priorityScore') ?? '0',
    criticalFindingCount: integer(body, 'criticalFindingCount'),
    warningFindingCount: integer(body, 'warningFindingCount'),
    declinedRuleCount: integer(body, 'declinedRuleCount'),
    netSales: decimal(body, 'netSales'),
    contributionProfit: decimal(body, 'contributionProfit'),
    currencyCode: optionalText(body, 'currencyCode'),
    blockingRuleCodes: textList(body, 'blockingRuleCodes'),
  };
}

/** Read one canonical value. */
export function parseMetricValue(body: unknown): MetricValue | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const metricValueId = text(body, 'metricValueId');
  const metricCode = text(body, 'metricCode');
  const valueState = text(body, 'valueState');
  const confidenceState = text(body, 'confidenceState');
  if (
    metricValueId === undefined ||
    metricCode === undefined ||
    valueState === undefined ||
    confidenceState === undefined
  ) {
    return undefined;
  }
  return {
    metricValueId,
    metricCode,
    valueState: valueState as ValueState,
    numericValue: decimal(body, 'numericValue'),
    currencyCode: optionalText(body, 'currencyCode'),
    confidenceState: confidenceState as ConfidenceState,
    estimated: body.estimated === true,
    freshnessSeconds: optionalNumber(body, 'freshnessSeconds'),
    evidenceRefs: textList(body, 'evidenceRefs'),
  };
}

/** Read one deterministic rule outcome. */
export function parseFinding(body: unknown): DiagnosisFinding | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const findingId = text(body, 'findingId');
  const ruleCode = text(body, 'ruleCode');
  const outcome = text(body, 'outcome');
  const severity = text(body, 'severity');
  if (
    findingId === undefined ||
    ruleCode === undefined ||
    outcome === undefined ||
    severity === undefined
  ) {
    return undefined;
  }
  return {
    findingId,
    ruleCode,
    outcome: outcome as DiagnosisFinding['outcome'],
    severity: severity as DiagnosisFinding['severity'],
    declineReason: optionalText(body, 'declineReason'),
    detail: textMap(body, 'detail'),
    blocksExecution: body.blocksExecution === true,
    metricValueIds: textList(body, 'metricValueIds'),
  };
}

/** Read one proposal. */
export function parseRecommendation(body: unknown): Recommendation | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const id = text(body, 'id');
  const storeId = text(body, 'storeId');
  const subjectId = text(body, 'subjectId');
  const actionKind = text(body, 'actionKind');
  const state = text(body, 'state');
  if (
    id === undefined ||
    storeId === undefined ||
    subjectId === undefined ||
    actionKind === undefined ||
    state === undefined
  ) {
    return undefined;
  }
  return {
    id,
    storeId,
    subjectId,
    actionKind,
    origin: text(body, 'origin') ?? 'DETERMINISTIC',
    state,
    priorityScore: decimal(body, 'priorityScore') ?? '0',
    proposedParameters: textMap(body, 'proposedParameters'),
    expectedEffect: textMap(body, 'expectedEffect'),
    riskLabel: text(body, 'riskLabel') ?? 'UNKNOWN',
    validUntil: text(body, 'validUntil') ?? '',
    terminalReason: optionalText(body, 'terminalReason'),
    version: integer(body, 'version'),
  };
}

/** Read the deterministic verdict. */
export function parseVerdict(body: unknown): GuardrailVerdict | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const evaluationId = text(body, 'evaluationId');
  if (evaluationId === undefined) {
    return undefined;
  }
  return {
    evaluationId,
    purpose: text(body, 'purpose') ?? 'UNKNOWN',
    passed: body.passed === true,
    reasons: textList(body, 'reasons'),
    policyVersion: optionalNumber(body, 'policyVersion'),
    detail: textMap(body, 'detail'),
  };
}

/** Read the projection of what a change would do. */
export function parsePreview(body: unknown): ImpactPreview | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const recommendationId = text(body, 'recommendationId');
  const verdict = parseVerdict(body.verdict);
  const proposedPrice = decimal(body, 'proposedPrice');
  if (recommendationId === undefined || verdict === undefined || proposedPrice === null) {
    return undefined;
  }
  return {
    recommendationId,
    currencyCode: optionalText(body, 'currencyCode'),
    currentPrice: decimal(body, 'currentPrice'),
    proposedPrice,
    changeRate: decimal(body, 'changeRate'),
    breakEvenPrice: decimal(body, 'breakEvenPrice'),
    currentUnitProfit: decimal(body, 'currentUnitProfit'),
    projectedUnitProfit: decimal(body, 'projectedUnitProfit'),
    currentMargin: decimal(body, 'currentMargin'),
    projectedMargin: decimal(body, 'projectedMargin'),
    verdict,
  };
}

/** Read one command with its attempts and readbacks. */
export function parseCommand(body: unknown): PriceCommand | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const id = text(body, 'id');
  const state = text(body, 'state');
  const priorPrice = decimal(body, 'priorPrice');
  const targetPrice = decimal(body, 'targetPrice');
  if (id === undefined || state === undefined || priorPrice === null || targetPrice === null) {
    return undefined;
  }
  const attemptsValue = body.attempts;
  const readbacksValue = body.readbacks;
  return {
    id,
    recommendationId: text(body, 'recommendationId') ?? '',
    storeId: text(body, 'storeId') ?? '',
    platformCode: text(body, 'platformCode') ?? '',
    fulfillmentModeCode: optionalText(body, 'fulfillmentModeCode'),
    currencyCode: text(body, 'currencyCode') ?? '',
    priorPrice,
    targetPrice,
    state,
    attemptNo: integer(body, 'attemptNo'),
    failureCode: optionalText(body, 'failureCode'),
    attempts: Array.isArray(attemptsValue)
      ? attemptsValue.map(parseAttempt).filter((item): item is CommandAttempt => item !== undefined)
      : [],
    readbacks: Array.isArray(readbacksValue)
      ? readbacksValue
          .map(parseReadback)
          .filter((item): item is CommandReadback => item !== undefined)
      : [],
  };
}

function parseAttempt(body: unknown): CommandAttempt | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const id = text(body, 'id');
  const purpose = text(body, 'purpose');
  const outcomeClass = text(body, 'outcomeClass');
  const startedAt = text(body, 'startedAt');
  if (
    id === undefined ||
    purpose === undefined ||
    outcomeClass === undefined ||
    startedAt === undefined
  ) {
    return undefined;
  }
  return {
    id,
    attemptNo: integer(body, 'attemptNo'),
    purpose,
    outcomeClass,
    nativeStatus: optionalText(body, 'nativeStatus'),
    errorCode: optionalText(body, 'errorCode'),
    startedAt,
    completedAt: optionalText(body, 'completedAt'),
  };
}

function parseReadback(body: unknown): CommandReadback | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const id = text(body, 'id');
  const observedAt = text(body, 'observedAt');
  const matchState = text(body, 'matchState');
  if (id === undefined || observedAt === undefined || matchState === undefined) {
    return undefined;
  }
  return {
    id,
    observedAt,
    observedPrice: decimal(body, 'observedPrice'),
    currencyCode: optionalText(body, 'currencyCode'),
    matchState,
  };
}

/** Read one subject's complete diagnostic picture. */
export function parseDiagnosis(body: unknown): SubjectDiagnosis | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const subjectId = text(body, 'subjectId');
  const storeId = text(body, 'storeId');
  if (subjectId === undefined || storeId === undefined) {
    return undefined;
  }
  const metricsValue = body.metrics;
  const metrics: Record<string, MetricValue> = {};
  if (isRecord(metricsValue)) {
    for (const [code, item] of Object.entries(metricsValue)) {
      const parsed = parseMetricValue(item);
      if (parsed !== undefined) {
        metrics[code] = parsed;
      }
    }
  }
  const findingsValue = body.findings;
  return {
    subjectId,
    storeId,
    window: text(body, 'window') ?? 'D30',
    metrics,
    findings: Array.isArray(findingsValue)
      ? findingsValue
          .map(parseFinding)
          .filter((item): item is DiagnosisFinding => item !== undefined)
      : [],
  };
}

/** What one request needs in order to be made. */
export interface ConsoleRequest {
  /** Origin every request is sent to. */
  readonly apiBaseUrl: string;
  /** The token proving who is asking. */
  readonly accessToken: string;
  /** Request implementation, replaced in tests. */
  readonly fetchImpl?: typeof fetch;
}

/**
 * Make one console request and classify the answer.
 *
 * The refusals are separated because an operator does something different
 * about each. Signed out means sign in again; step-up required means
 * re-authenticate for this specific action; forbidden means ask for the grant.
 * Collapsing the three into "not allowed" would leave a person guessing which
 * of three unrelated problems they have.
 *
 * No response body reaches an error message. A backend problem detail can name
 * an internal host or an identifier the operator has no scope for, so only the
 * classification and the status travel outward.
 */
async function request<T>(
  context: ConsoleRequest,
  path: string,
  parse: (body: unknown) => T | undefined,
  init?: RequestInit,
  timeoutMillis = REQUEST_TIMEOUT_MS,
): Promise<ConsoleOutcome<T>> {
  const controller = new AbortController();
  const timer = setTimeout(() => {
    controller.abort();
  }, timeoutMillis);
  const send = context.fetchImpl ?? fetch;

  try {
    const response = await send(`${context.apiBaseUrl}${path}`, {
      ...init,
      headers: buildHeaders(context.accessToken, init),
      signal: controller.signal,
      credentials: 'omit',
      cache: 'no-store',
    });

    if (response.status === 401) {
      return { ok: false, failure: { kind: 'unauthenticated' } };
    }
    if (response.status === 403) {
      const detail = await classifyForbidden(response);
      return { ok: false, failure: detail };
    }
    if (!response.ok) {
      return {
        ok: false,
        failure: { kind: 'refused', status: response.status, detail: response.statusText },
      };
    }
    if (response.status === 204) {
      const parsed = parse(undefined);
      return parsed === undefined
        ? { ok: false, failure: { kind: 'malformed', detail: 'no content was expected' } }
        : { ok: true, value: parsed };
    }

    let body: unknown;
    try {
      body = await response.json();
    } catch {
      return { ok: false, failure: { kind: 'malformed', detail: 'the body was not valid JSON' } };
    }
    const parsed = parse(body);
    return parsed === undefined
      ? { ok: false, failure: { kind: 'malformed', detail: 'the body did not match the contract' } }
      : { ok: true, value: parsed };
  } catch (error) {
    const detail = error instanceof Error || error instanceof DOMException ? error.name : 'unknown';
    return { ok: false, failure: { kind: 'unreachable', detail } };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Tell a step-up requirement apart from a missing grant.
 *
 * Both arrive as a refusal, and the difference decides what the operator does
 * next: one is solved by re-authenticating, the other by being granted an
 * action they do not hold. The backend names which in the problem type, so only
 * that field is read and nothing else from the body is kept.
 */
async function classifyForbidden(response: Response): Promise<ConsoleFailure> {
  try {
    const body: unknown = await response.json();
    if (typeof body === 'object' && body !== null) {
      const type = (body as Record<string, unknown>).type;
      if (typeof type === 'string' && type.includes('step-up-required')) {
        return { kind: 'step-up-required' };
      }
    }
  } catch {
    // A refusal without a readable body is still a refusal.
  }
  return { kind: 'forbidden' };
}

/**
 * The headers every console request carries.
 *
 * Built as a record rather than spread from the caller's init, so a caller
 * cannot replace the authorization header by accident and a correlation
 * identifier is always present for an operator report to be matched against.
 */
function buildHeaders(accessToken: string, init?: RequestInit): Record<string, string> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    Authorization: `Bearer ${accessToken}`,
    [CORRELATION_HEADER]: crypto.randomUUID(),
  };
  if (init?.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  return headers;
}

function list<T>(parse: (body: unknown) => T | undefined) {
  return (body: unknown): readonly T[] | undefined =>
    Array.isArray(body)
      ? body.map(parse).filter((item): item is T => item !== undefined)
      : undefined;
}

/**
 * The stockout and availability queue, most urgent first.
 *
 * The backend orders it and the console does not re-sort. The order is a
 * deterministic figure with a published definition, and a console that
 * re-ordered it would be presenting its own opinion as the product's.
 */
export function fetchAvailabilityQueue(
  context: ConsoleRequest,
  lane?: string,
  limit = 50,
  offset = 0,
): Promise<ConsoleOutcome<readonly AvailabilityCard[]>> {
  const laneFilter = lane === undefined ? '' : `lane=${encodeURIComponent(lane)}&`;
  return request(
    context,
    `/api/v1/console/availability/queue?${laneFilter}limit=${String(limit)}` +
      `&offset=${String(offset)}`,
    list(parseAvailabilityCard),
  );
}

/** One accountable availability case, as the console sees it. */
export interface AvailabilityCase {
  /** The case. */
  readonly id: string;
  /** The card it was raised from. */
  readonly cardId: string;
  /** The exact child that produced it. */
  readonly childId: string;
  /** Why somebody is needed. */
  readonly causeCode: string;
  /** The deduplication identity of the cause. */
  readonly causeKey: string;
  /** The lane that activated it. */
  readonly severity: string;
  /** Where it stands. */
  readonly state: string;
  /** The role accountable for the cause. */
  readonly accountableRoleCode: string;
  /** Who owns it, or null. */
  readonly assigneeUserId: string | null;
  /** When accountable action is due. */
  readonly actionDueAt: string;
  /** When fresh outcome evidence is due, or null. */
  readonly outcomeDueAt: string | null;
  /** How many times the same cause has returned. */
  readonly reopenCount: number;
  /** How far it has been raised. */
  readonly escalationLevel: number;
  /** When the cause was first raised. */
  readonly firstActivatedAt: string;
}

/** One entry in a case's history. */
export interface CaseJournalEntry {
  /** Its position in the case's history. */
  readonly sequenceNo: number;
  /** What happened. */
  readonly eventKind: string;
  /** The state before, or null. */
  readonly fromState: string | null;
  /** The state after, or null. */
  readonly toState: string | null;
  /** The structured action, or null. */
  readonly actionKind: string | null;
  /** How a verification came out, or null. */
  readonly verificationOutcome: string | null;
  /** Why. */
  readonly reason: string;
  /** The artefact behind it, or null. */
  readonly evidenceReference: string | null;
  /** When it happened. */
  readonly occurredAt: string;
}

/** One bounded, governed acceptance of a calculated risk. */
export interface AcceptedException {
  /** The acceptance. */
  readonly id: string;
  /** The cause being accepted. */
  readonly causeCode: string;
  /** The business reason. */
  readonly reasonCode: string;
  /** Where the request stands. */
  readonly state: string;
  /** How much authority the decision needs. */
  readonly requiredAuthority: string;
  /** When the grant starts, or null. */
  readonly effectiveFrom: string | null;
  /** When the grant ends, or null. */
  readonly expiresAt: string | null;
  /** When it must be reviewed, or null. */
  readonly reviewAt: string | null;
  /** Why it stopped being valid, or null. */
  readonly invalidationReason: string | null;
}

function parseAvailabilityCase(body: unknown): AvailabilityCase | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const id = text(body, 'id');
  const cardId = text(body, 'cardId');
  const childId = text(body, 'childId');
  const causeCode = text(body, 'causeCode');
  const causeKey = text(body, 'causeKey');
  const severity = text(body, 'severity');
  const state = text(body, 'state');
  const accountableRoleCode = text(body, 'accountableRoleCode');
  const actionDueAt = text(body, 'actionDueAt');
  const firstActivatedAt = text(body, 'firstActivatedAt');
  if (
    id === undefined ||
    cardId === undefined ||
    childId === undefined ||
    causeCode === undefined ||
    causeKey === undefined ||
    severity === undefined ||
    state === undefined ||
    accountableRoleCode === undefined ||
    actionDueAt === undefined ||
    firstActivatedAt === undefined
  ) {
    return undefined;
  }
  return {
    id,
    cardId,
    childId,
    causeCode,
    causeKey,
    severity,
    state,
    accountableRoleCode,
    assigneeUserId: optionalText(body, 'assigneeUserId'),
    actionDueAt,
    outcomeDueAt: optionalText(body, 'outcomeDueAt'),
    reopenCount: integer(body, 'reopenCount'),
    escalationLevel: integer(body, 'escalationLevel'),
    firstActivatedAt,
  };
}

function parseJournalEntry(body: unknown): CaseJournalEntry | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const eventKind = text(body, 'eventKind');
  const reason = text(body, 'reason');
  const occurredAt = text(body, 'occurredAt');
  if (eventKind === undefined || reason === undefined || occurredAt === undefined) {
    return undefined;
  }
  return {
    sequenceNo: integer(body, 'sequenceNo'),
    eventKind,
    fromState: optionalText(body, 'fromState'),
    toState: optionalText(body, 'toState'),
    actionKind: optionalText(body, 'actionKind'),
    verificationOutcome: optionalText(body, 'verificationOutcome'),
    reason,
    evidenceReference: optionalText(body, 'evidenceReference'),
    occurredAt,
  };
}

function parseAcceptedException(body: unknown): AcceptedException | undefined {
  if (!isRecord(body)) {
    return undefined;
  }
  const id = text(body, 'id');
  const causeCode = text(body, 'causeCode');
  const reasonCode = text(body, 'reasonCode');
  const state = text(body, 'state');
  const requiredAuthority = text(body, 'requiredAuthority');
  if (
    id === undefined ||
    causeCode === undefined ||
    reasonCode === undefined ||
    state === undefined ||
    requiredAuthority === undefined
  ) {
    return undefined;
  }
  return {
    id,
    causeCode,
    reasonCode,
    state,
    requiredAuthority,
    effectiveFrom: optionalText(body, 'effectiveFrom'),
    expiresAt: optionalText(body, 'expiresAt'),
    reviewAt: optionalText(body, 'reviewAt'),
    invalidationReason: optionalText(body, 'invalidationReason'),
  };
}

/** The organization's accountable availability work, most urgent first. */
export function fetchAvailabilityCases(
  context: ConsoleRequest,
  limit = 50,
): Promise<ConsoleOutcome<readonly AvailabilityCase[]>> {
  return request(
    context,
    `/api/v1/console/availability/cases?limit=${String(limit)}`,
    list(parseAvailabilityCase),
  );
}

/** Everything that ever happened to one case, oldest first. */
export function fetchCaseJournal(
  context: ConsoleRequest,
  caseId: string,
): Promise<ConsoleOutcome<readonly CaseJournalEntry[]>> {
  return request(
    context,
    `/api/v1/console/availability/cases/${encodeURIComponent(caseId)}/journal`,
    list(parseJournalEntry),
  );
}

/** Every acceptance ever recorded against one case. */
export function fetchCaseExceptions(
  context: ConsoleRequest,
  caseId: string,
): Promise<ConsoleOutcome<readonly AcceptedException[]>> {
  return request(
    context,
    `/api/v1/console/availability/cases/${encodeURIComponent(caseId)}/exceptions`,
    list(parseAcceptedException),
  );
}

/**
 * Record accountable structured action.
 *
 * The call takes an action kind and the reference to the artefact behind it,
 * and there is deliberately no shape it will accept that means "looked at it".
 * A console that offered an acknowledgement button would be offering something
 * the product refuses to treat as action.
 */
export function recordCaseAction(
  context: ConsoleRequest,
  caseId: string,
  actionKind: string,
  evidenceReference: string,
  reason: string,
): Promise<ConsoleOutcome<AvailabilityCase>> {
  return request(
    context,
    `/api/v1/console/availability/cases/${encodeURIComponent(caseId)}/action`,
    parseAvailabilityCase,
    { method: 'POST', body: JSON.stringify({ actionKind, evidenceReference, reason }) },
  );
}

/** Record what a fresh cause-specific observation showed. */
export function observeCaseVerification(
  context: ConsoleRequest,
  caseId: string,
  verificationKind: string,
  outcome: string,
  reason: string,
): Promise<ConsoleOutcome<AvailabilityCase>> {
  return request(
    context,
    `/api/v1/console/availability/cases/${encodeURIComponent(caseId)}/verification`,
    parseAvailabilityCase,
    { method: 'POST', body: JSON.stringify({ verificationKind, outcome, reason }) },
  );
}

/** One grouped card with every child, factor and window behind it. */
export function fetchAvailabilityCard(
  context: ConsoleRequest,
  productVariantId: string,
): Promise<ConsoleOutcome<AvailabilityCard>> {
  return request(
    context,
    `/api/v1/console/availability/cards/${encodeURIComponent(productVariantId)}`,
    parseAvailabilityCard,
  );
}

/** The store's daily work list, most urgent first. */
export function fetchPriorityQueue(
  context: ConsoleRequest,
  storeId: string,
  window = 'D30',
): Promise<ConsoleOutcome<readonly PrioritySubject[]>> {
  return request(
    context,
    `/api/v1/console/diagnosis/stores/${storeId}/queue?window=${window}`,
    list(parsePrioritySubject),
  );
}

/** Everything currently known about one listing variant. */
export function fetchDiagnosis(
  context: ConsoleRequest,
  subjectId: string,
  storeId: string,
  window = 'D30',
): Promise<ConsoleOutcome<SubjectDiagnosis>> {
  return request(
    context,
    `/api/v1/console/diagnosis/listing-variants/${subjectId}` +
      `?storeId=${storeId}&window=${window}`,
    parseDiagnosis,
  );
}

/** An explicit request only; neither component mounting nor polling spends provider quota. */
export function requestExplanation(
  context: ConsoleRequest,
  subjectId: string,
  storeId: string,
): Promise<ConsoleOutcome<AiExplanation>> {
  return request(
    context,
    `/api/v1/console/explanations/listing-variants/${encodeURIComponent(subjectId)}?storeId=${encodeURIComponent(storeId)}&window=D30`,
    parseAiExplanation,
    { method: 'POST' },
    70_000,
  );
}

/** The store's open proposals, most urgent first. */
export function fetchRecommendations(
  context: ConsoleRequest,
  storeId: string,
  subjectId?: string,
): Promise<ConsoleOutcome<readonly Recommendation[]>> {
  return request(
    context,
    `/api/v1/console/workflow/stores/${encodeURIComponent(storeId)}/recommendations` +
      (subjectId === undefined ? '' : `?subjectId=${encodeURIComponent(subjectId)}`),
    list(parseRecommendation),
  );
}

/** The bounded, typed input edges of one exact stored metric version. */
export interface MetricInputs {
  readonly metricValueId: string;
  readonly references: readonly { readonly kind: string; readonly id: string }[];
  readonly truncated: boolean;
}

/** Source metadata only: source bytes and storage locators are not fetched by this view. */
export interface EvidenceSource {
  readonly provenanceId: string;
  readonly sourceKind: string;
  readonly sourceTime: string | null;
  readonly ingestionTime: string;
  readonly contentSha256: string | null;
}

export function fetchMetricInputs(
  context: ConsoleRequest,
  subjectId: string,
  storeId: string,
  metricValueId: string,
): Promise<ConsoleOutcome<MetricInputs>> {
  return request(
    context,
    `/api/v1/console/diagnosis/listing-variants/${encodeURIComponent(subjectId)}/metrics/${encodeURIComponent(metricValueId)}/inputs?storeId=${encodeURIComponent(storeId)}`,
    (body) => {
      if (
        !isRecord(body) ||
        body.metricValueId !== metricValueId ||
        typeof body.truncated !== 'boolean' ||
        !Array.isArray(body.references) ||
        body.references.length > 200
      )
        return undefined;
      const references: { kind: string; id: string }[] = [];
      for (const ref of body.references) {
        if (
          !isRecord(ref) ||
          typeof ref.kind !== 'string' ||
          ![
            'FACT_PROVENANCE',
            'COST_VERSION',
            'FINANCE_INPUT_VERSION',
            'METRIC_VALUE',
            'LISTING_MAPPING',
          ].includes(ref.kind) ||
          typeof ref.id !== 'string' ||
          !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(ref.id)
        )
          return undefined;
        references.push({ kind: ref.kind, id: ref.id });
      }
      return { metricValueId, references, truncated: body.truncated };
    },
  );
}

export function fetchEvidenceSource(
  context: ConsoleRequest,
  provenanceId: string,
): Promise<ConsoleOutcome<EvidenceSource>> {
  return request(
    context,
    `/api/v1/console/evidence/${encodeURIComponent(provenanceId)}`,
    (body) => {
      if (
        !isRecord(body) ||
        body.provenanceId !== provenanceId ||
        typeof body.sourceKind !== 'string' ||
        typeof body.ingestionTime !== 'string' ||
        !Number.isFinite(Date.parse(body.ingestionTime)) ||
        !(
          body.sourceTime === null ||
          (typeof body.sourceTime === 'string' && Number.isFinite(Date.parse(body.sourceTime)))
        ) ||
        !(
          body.contentSha256 === null ||
          (typeof body.contentSha256 === 'string' && /^[0-9a-f]{64}$/.test(body.contentSha256))
        )
      )
        return undefined;
      return {
        provenanceId,
        sourceKind: body.sourceKind,
        sourceTime: body.sourceTime,
        ingestionTime: body.ingestionTime,
        contentSha256: body.contentSha256,
      };
    },
  );
}

/** What the change would do, and whether it is currently allowed. */
export function requestImpactPreview(
  context: ConsoleRequest,
  recommendationId: string,
): Promise<ConsoleOutcome<ImpactPreview>> {
  return request(
    context,
    `/api/v1/console/workflow/recommendations/${recommendationId}/impact-preview`,
    parsePreview,
    { method: 'POST' },
  );
}

/** Record a decision about one proposal. */
export function decide(
  context: ConsoleRequest,
  recommendationId: string,
  decision: 'approval' | 'rejection' | 'policy-authorization',
  reason: string,
  expectedVersion: number,
): Promise<ConsoleOutcome<{ readonly decisionId: string; readonly state: string }>> {
  return request(
    context,
    `/api/v1/console/workflow/recommendations/${recommendationId}/${decision}`,
    (body) => {
      if (typeof body !== 'object' || body === null) {
        return undefined;
      }
      const payload = body as Record<string, unknown>;
      const decisionId = text(payload, 'decisionId');
      const state = text(payload, 'state');
      return decisionId === undefined || state === undefined ? undefined : { decisionId, state };
    },
    { method: 'POST', body: JSON.stringify({ reason, expectedVersion }) },
  );
}

/** Create the command for an authorized proposal. */
export function createCommand(
  context: ConsoleRequest,
  recommendationId: string,
  expectedVersion: number,
): Promise<ConsoleOutcome<{ readonly commandId: string }>> {
  return request(
    context,
    `/api/v1/console/workflow/recommendations/${recommendationId}/command`,
    (body) => {
      if (typeof body !== 'object' || body === null) {
        return undefined;
      }
      const commandId = text(body as Record<string, unknown>, 'commandId');
      return commandId === undefined ? undefined : { commandId };
    },
    { method: 'POST', body: JSON.stringify({ expectedVersion }) },
  );
}

/** One command with its attempts and readbacks. */
export function fetchCommand(
  context: ConsoleRequest,
  commandId: string,
): Promise<ConsoleOutcome<PriceCommand>> {
  return request(context, `/api/v1/console/commands/${commandId}`, parseCommand);
}

/** Recover an existing command without repeating an approval or execution request. */
export function fetchRecommendationCommand(
  context: ConsoleRequest,
  recommendationId: string,
): Promise<ConsoleOutcome<PriceCommand>> {
  return request(
    context,
    `/api/v1/console/commands/recommendations/${encodeURIComponent(recommendationId)}`,
    parseCommand,
  );
}

/** Why the write gate is currently closed for a command, if it is. */
export function fetchGate(
  context: ConsoleRequest,
  commandId: string,
): Promise<
  ConsoleOutcome<{ readonly open: boolean; readonly blockingReasons: readonly string[] }>
> {
  return request(context, `/api/v1/console/commands/${commandId}/gate`, (body) => {
    if (typeof body !== 'object' || body === null) {
      return undefined;
    }
    const payload = body as Record<string, unknown>;
    return { open: payload.open === true, blockingReasons: textList(payload, 'blockingReasons') };
  });
}

/** Commands of one store that a person has to look at. */
export function fetchCommandsNeedingAttention(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<readonly PriceCommand[]>> {
  return request(
    context,
    `/api/v1/console/commands/stores/${storeId}/needing-attention`,
    list(parseCommand),
  );
}
