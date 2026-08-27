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
): Promise<ConsoleOutcome<T>> {
  const controller = new AbortController();
  const timer = setTimeout(() => {
    controller.abort();
  }, REQUEST_TIMEOUT_MS);
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

/** The store's open proposals, most urgent first. */
export function fetchRecommendations(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<readonly Recommendation[]>> {
  return request(
    context,
    `/api/v1/console/workflow/stores/${storeId}/recommendations`,
    list(parseRecommendation),
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
