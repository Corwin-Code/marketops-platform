/**
 * Price suggestions (P8): proposing them from the newest findings, the guardrail verdicts recorded
 * about one, and what a person did with one in the marketplace's back office while the platform's
 * own writes are off. Prices are buyer prices as decimal text; times are ISO-8601 instants.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** What one suggestion pass did. */
export interface SuggestionPass {
  readonly calculationRunId: string | null;
  readonly expired: number;
  /** Suggestions for listings that had none. */
  readonly proposed: number;
  /** Undecided suggestions replaced because the findings moved. */
  readonly refreshed: number;
  /** Undecided suggestions withdrawn because the findings no longer call for them. */
  readonly withdrawn: number;
  readonly standing: number;
  /** Listings left alone because a decision about them is recent. */
  readonly cooling: number;
  readonly failed: number;
}

/** A person's decision about a suggestion. */
export type PriceDecisionKind = 'APPLIED_IN_SELLER_OFFICE' | 'NOT_APPLIED';

/** One recorded decision. */
export interface PriceDecision {
  readonly decisionId: string;
  readonly recommendationId: string;
  readonly subjectId: string;
  readonly decision: string;
  readonly appliedPrice: string | null;
  readonly currencyCode: string | null;
  readonly note: string | null;
  readonly decidedAt: string;
}

/** One guardrail verdict about a suggestion. */
export interface GuardrailEvaluation {
  readonly id: string;
  readonly purpose: string;
  readonly passed: boolean;
  readonly reasonCodes: readonly string[];
  readonly evaluatedAt: string;
}

/** Suggest prices from the store's newest seven-day findings. */
export function generatePriceSuggestions(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<SuggestionPass>> {
  return request(
    context,
    `/api/v1/console/workflow/stores/${encodeURIComponent(storeId)}/price-suggestions`,
    parseSuggestionPass,
    { method: 'POST' },
  );
}

/** Record that a suggestion was applied by hand in the back office, or not applied. */
export function recordPriceDecision(
  context: ConsoleRequest,
  recommendationId: string,
  input: {
    readonly decision: PriceDecisionKind;
    readonly appliedPrice: string | null;
    readonly note: string | null;
    readonly expectedVersion: number;
  },
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `/api/v1/console/workflow/recommendations/${encodeURIComponent(recommendationId)}/price-decision`,
    (body) => (isRecord(body) ? text(body.decisionId) : undefined),
    { method: 'POST', body: JSON.stringify(input) },
  );
}

/** The store's recorded decisions, newest first, optionally about one listing variant. */
export function fetchPriceDecisions(
  context: ConsoleRequest,
  storeId: string,
  subjectId?: string,
): Promise<ConsoleOutcome<readonly PriceDecision[]>> {
  return request(
    context,
    `/api/v1/console/workflow/stores/${encodeURIComponent(storeId)}/price-decisions` +
      (subjectId === undefined ? '' : `?subjectId=${encodeURIComponent(subjectId)}`),
    (body) => listOf(body, parseDecision),
  );
}

/** Every guardrail verdict about one suggestion, newest first. */
export function fetchGuardrailEvaluations(
  context: ConsoleRequest,
  recommendationId: string,
): Promise<ConsoleOutcome<readonly GuardrailEvaluation[]>> {
  return request(
    context,
    `/api/v1/console/workflow/recommendations/${encodeURIComponent(recommendationId)}/guardrail-evaluations?limit=5`,
    (body) => listOf(body, parseEvaluation),
  );
}

function parseSuggestionPass(body: unknown): SuggestionPass | undefined {
  if (!isRecord(body)) return undefined;
  const count = (value: unknown): number | undefined =>
    typeof value === 'number' && Number.isInteger(value) ? value : undefined;
  const expired = count(body.expired);
  const proposed = count(body.proposed);
  const refreshed = count(body.refreshed);
  const withdrawn = count(body.withdrawn);
  const standing = count(body.standing);
  const cooling = count(body.cooling);
  const failed = count(body.failed);
  if (
    expired === undefined ||
    proposed === undefined ||
    refreshed === undefined ||
    withdrawn === undefined ||
    standing === undefined ||
    cooling === undefined ||
    failed === undefined
  ) {
    return undefined;
  }
  return {
    calculationRunId: text(body.calculationRunId) ?? null,
    expired,
    proposed,
    refreshed,
    withdrawn,
    standing,
    cooling,
    failed,
  };
}

function parseDecision(value: unknown): PriceDecision | undefined {
  if (!isRecord(value)) return undefined;
  const decisionId = text(value.decisionId);
  const recommendationId = text(value.recommendationId);
  const subjectId = text(value.subjectId);
  const decision = text(value.decision);
  const decidedAt = text(value.decidedAt);
  if (
    decisionId === undefined ||
    recommendationId === undefined ||
    subjectId === undefined ||
    decision === undefined ||
    decidedAt === undefined
  ) {
    return undefined;
  }
  return {
    decisionId,
    recommendationId,
    subjectId,
    decision,
    appliedPrice: text(value.appliedPrice) ?? null,
    currencyCode: text(value.currencyCode) ?? null,
    note: text(value.note) ?? null,
    decidedAt,
  };
}

function parseEvaluation(value: unknown): GuardrailEvaluation | undefined {
  if (!isRecord(value)) return undefined;
  const id = text(value.id);
  const purpose = text(value.purpose);
  const evaluatedAt = text(value.evaluatedAt);
  if (
    id === undefined ||
    purpose === undefined ||
    evaluatedAt === undefined ||
    typeof value.passed !== 'boolean' ||
    !Array.isArray(value.reasonCodes) ||
    !value.reasonCodes.every((code) => typeof code === 'string')
  ) {
    return undefined;
  }
  return {
    id,
    purpose,
    passed: value.passed,
    reasonCodes: value.reasonCodes,
    evaluatedAt,
  };
}

function listOf<T>(
  body: unknown,
  parse: (value: unknown) => T | undefined,
): readonly T[] | undefined {
  if (!Array.isArray(body)) return undefined;
  const items = body.map(parse);
  return items.some((item) => item === undefined) ? undefined : (items as T[]);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}
