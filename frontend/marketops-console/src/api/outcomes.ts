/**
 * The effect of executed actions (P10): every followed action of a store — a price command that
 * succeeded, a price changed by hand in the seller office, a promotion joined or left — with its
 * preliminary (7 against 7 days) and final (14 against 14 days) before/after readings.
 */

import type { AiExplanation, ConsoleOutcome, ConsoleRequest } from './console';
import { AI_REQUEST_TIMEOUT_MS, parseAiExplanation, request } from './console';

/** One stage's comparison of the days after an action with the days before it. */
export interface OutcomeReading {
  /** PRELIMINARY or FINAL. */
  readonly stage: string;
  readonly windowDays: number;
  readonly baselineFrom: string;
  readonly baselineTo: string;
  readonly observationFrom: string;
  readonly observationTo: string;
  /** Days of each window the store's daily order facts cover. */
  readonly baselineDaysCovered: number;
  readonly observationDaysCovered: number;
  /** Units ordered over the covered days; `null` when no day was covered. */
  readonly baselineOrderedUnits: number | null;
  readonly observationOrderedUnits: number | null;
  /** Search users of the newest search period inside each window, if any. */
  readonly baselineSearchUsers: number | null;
  readonly observationSearchUsers: number | null;
  /** Ozon's price index class at the end of each window, if stated. */
  readonly baselinePriceIndex: string | null;
  readonly observationPriceIndex: string | null;
  readonly observationBuyerPriceMin: string | null;
  readonly observationBuyerPriceMax: string | null;
  readonly stockoutObserved: boolean;
  readonly promotionObserved: boolean;
  /** Whether the price stayed where the action set it; `null` when it set none or none was observed. */
  readonly priceHeld: boolean | null;
  readonly otherActionObserved: boolean;
  /** IMPROVED, UNCHANGED, REGRESSED or INDETERMINATE. */
  readonly verdict: string;
  /** POSITIVE, NEGATIVE, MIXED, NONE or UNAVAILABLE. */
  readonly leadingSignal: string;
  readonly reasonCodes: readonly string[];
  readonly ruleVersion: number;
  readonly computedAt: string;
}

/** One followed action. */
export interface FollowedAction {
  readonly id: string;
  /** PRICE_COMMAND, PRICE_DECISION or PROMOTION_DECISION. */
  readonly actionSource: string;
  /** PRICE_CHANGE, PROMOTION_JOINED or PROMOTION_LEFT. */
  readonly actionKind: string;
  readonly sourceId: string;
  readonly recommendationId: string | null;
  readonly proposalState: string | null;
  readonly listingVariantId: string;
  readonly offerId: string | null;
  readonly title: string | null;
  readonly actedAt: string;
  readonly actedOn: string;
  readonly priorPrice: string | null;
  readonly targetPrice: string | null;
  readonly currencyCode: string | null;
  /** When each reading is expected: two days after its window, when the order facts arrive. */
  readonly preliminaryDueOn: string;
  readonly finalDueOn: string;
  readonly preliminary: OutcomeReading | null;
  readonly finalReading: OutcomeReading | null;
}

/** Whether one price command is followed, and how. */
export interface CommandOutcome {
  readonly followed: boolean;
  readonly action: FollowedAction | null;
}

/** One action as a week's snapshot keeps it, with its newest reading up to the week's end. */
export interface WeeklyReviewAction {
  readonly actionRef: string;
  readonly listingVariantId: string;
  readonly offerId: string | null;
  readonly title: string | null;
  readonly actionKind: string;
  readonly source: string;
  readonly actedOn: string;
  readonly priorPrice: string | null;
  readonly targetPrice: string | null;
  readonly currencyCode: string | null;
  /** NONE, PRELIMINARY or FINAL. */
  readonly stage: string;
  /** OBSERVING before any reading, else the reading's verdict. */
  readonly verdict: string;
  readonly leadingSignal: string | null;
  readonly reasons: readonly string[];
  readonly ordersBefore: number | null;
  readonly ordersAfter: number | null;
}

/** One week's snapshot of the store's followed actions. */
export interface WeeklyReview {
  readonly id: string;
  readonly weekStart: string;
  readonly weekEnd: string;
  /** Whether the snapshot was taken after its week ended; a provisional one is taken again. */
  readonly weekComplete: boolean;
  readonly ordersFrom: string | null;
  readonly ordersTo: string | null;
  readonly ordersDaysCovered: number;
  readonly orderedUnits: number | null;
  readonly listingsWithOrders: number | null;
  readonly actionsActed: number;
  readonly readingsRecorded: number;
  readonly actionsObserving: number;
  readonly improvedCount: number;
  readonly unchangedCount: number;
  readonly regressedCount: number;
  readonly indeterminateCount: number;
  readonly actions: readonly WeeklyReviewAction[];
  readonly aiInvocationId: string | null;
  readonly compiledAt: string;
}

/** Why a week was kept without the model's answer: it names no followed action. */
export const NO_ACTION_TO_REVIEW = '本周还没有需要复盘的动作，已保存快照，没有调用 Qwen。';

/** Load a store's newest weekly snapshots, newest week first. */
export function fetchWeeklyReviews(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<readonly WeeklyReview[]>> {
  return request(
    context,
    `/api/v1/console/outcomes/stores/${encodeURIComponent(storeId)}/weekly-reviews`,
    (body) => listOf(body, parseWeeklyReview),
  );
}

/** The newest recorded weekly review answer, or `null` when none was ever asked for. */
export function fetchLatestWeeklyReviewExplanation(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<AiExplanation | null>> {
  return request(
    context,
    `/api/v1/console/explanations/stores/${encodeURIComponent(storeId)}/weekly-review/latest`,
    (body) => (body === undefined || body === null ? null : parseAiExplanation(body)),
  );
}

/**
 * Review the week still running: a provisional snapshot and the model's answer about it. Resolves to
 * the answer, read from the explanations it is recorded under.
 */
export async function requestWeeklyReview(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<AiExplanation>> {
  const reviewed = await request(
    context,
    `/api/v1/console/outcomes/stores/${encodeURIComponent(storeId)}/weekly-reviews`,
    parseWeeklyReview,
    { method: 'POST' },
    AI_REQUEST_TIMEOUT_MS,
  );
  if (!reviewed.ok) return reviewed;
  const invocationId = reviewed.value.aiInvocationId;
  if (invocationId === null) {
    // A week naming no followed action is kept without asking the model.
    return {
      ok: false,
      failure: {
        kind: 'refused',
        status: 409,
        detail: NO_ACTION_TO_REVIEW,
        code: 'NO_ACTION_TO_REVIEW',
      },
    };
  }
  return request(
    context,
    `/api/v1/console/explanations/${encodeURIComponent(invocationId)}`,
    parseAiExplanation,
  );
}

/** Load a store's followed actions, newest first. */
export function fetchStoreOutcomes(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<readonly FollowedAction[]>> {
  return request(
    context,
    `/api/v1/console/outcomes/stores/${encodeURIComponent(storeId)}`,
    (body) => listOf(body, parseFollowedAction),
  );
}

/** Load the followed action of one price command. */
export function fetchCommandOutcome(
  context: ConsoleRequest,
  commandId: string,
): Promise<ConsoleOutcome<CommandOutcome>> {
  return request(
    context,
    `/api/v1/console/outcomes/price-commands/${encodeURIComponent(commandId)}`,
    parseCommandOutcome,
  );
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseCommandOutcome(body: unknown): CommandOutcome | undefined {
  if (!isRecord(body) || typeof body.followed !== 'boolean') return undefined;
  if (!body.followed) return { followed: false, action: null };
  const action = parseFollowedAction(body.action);
  return action === undefined ? undefined : { followed: true, action };
}

/** Validate one followed action. */
export function parseFollowedAction(value: unknown): FollowedAction | undefined {
  if (!isRecord(value)) return undefined;
  const id = text(value.id);
  const actionSource = text(value.actionSource);
  const actionKind = text(value.actionKind);
  const sourceId = text(value.sourceId);
  const listingVariantId = text(value.listingVariantId);
  const actedAt = text(value.actedAt);
  const actedOn = text(value.actedOn);
  const preliminaryDueOn = text(value.preliminaryDueOn);
  const finalDueOn = text(value.finalDueOn);
  if (
    id === undefined ||
    actionSource === undefined ||
    actionKind === undefined ||
    sourceId === undefined ||
    listingVariantId === undefined ||
    actedAt === undefined ||
    actedOn === undefined ||
    preliminaryDueOn === undefined ||
    finalDueOn === undefined
  ) {
    return undefined;
  }
  const preliminary = isAbsent(value.preliminary) ? null : parseReading(value.preliminary);
  const finalReading = isAbsent(value.finalReading) ? null : parseReading(value.finalReading);
  if (preliminary === undefined || finalReading === undefined) return undefined;
  return {
    id,
    actionSource,
    actionKind,
    sourceId,
    recommendationId: text(value.recommendationId) ?? null,
    proposalState: text(value.proposalState) ?? null,
    listingVariantId,
    offerId: text(value.offerId) ?? null,
    title: text(value.title) ?? null,
    actedAt,
    actedOn,
    priorPrice: decimal(value.priorPrice),
    targetPrice: decimal(value.targetPrice),
    currencyCode: text(value.currencyCode) ?? null,
    preliminaryDueOn,
    finalDueOn,
    preliminary,
    finalReading,
  };
}

function parseWeeklyAction(value: unknown): WeeklyReviewAction | undefined {
  if (!isRecord(value) || !Array.isArray(value.reasons)) return undefined;
  const actionRef = text(value.actionRef);
  const listingVariantId = text(value.listingVariantId);
  const actionKind = text(value.actionKind);
  const source = text(value.source);
  const actedOn = text(value.actedOn);
  const stage = text(value.stage);
  const verdict = text(value.verdict);
  if (
    actionRef === undefined ||
    listingVariantId === undefined ||
    actionKind === undefined ||
    source === undefined ||
    actedOn === undefined ||
    stage === undefined ||
    verdict === undefined
  ) {
    return undefined;
  }
  return {
    actionRef,
    listingVariantId,
    offerId: text(value.offerId) ?? null,
    title: text(value.title) ?? null,
    actionKind,
    source,
    actedOn,
    priorPrice: decimal(value.priorPrice),
    targetPrice: decimal(value.targetPrice),
    currencyCode: text(value.currencyCode) ?? null,
    stage,
    verdict,
    leadingSignal: text(value.leadingSignal) ?? null,
    reasons: value.reasons.filter((reason): reason is string => typeof reason === 'string'),
    ordersBefore: count(value.ordersBefore),
    ordersAfter: count(value.ordersAfter),
  };
}

/** Validate one weekly snapshot. */
export function parseWeeklyReview(value: unknown): WeeklyReview | undefined {
  if (!isRecord(value) || !Array.isArray(value.actions)) return undefined;
  const id = text(value.id);
  const weekStart = text(value.weekStart);
  const weekEnd = text(value.weekEnd);
  const compiledAt = text(value.compiledAt);
  const numbers = [
    value.ordersDaysCovered,
    value.actionsActed,
    value.readingsRecorded,
    value.actionsObserving,
    value.improvedCount,
    value.unchangedCount,
    value.regressedCount,
    value.indeterminateCount,
  ];
  if (
    id === undefined ||
    weekStart === undefined ||
    weekEnd === undefined ||
    compiledAt === undefined ||
    typeof value.weekComplete !== 'boolean' ||
    numbers.some((number) => typeof number !== 'number')
  ) {
    return undefined;
  }
  const actions = value.actions.map(parseWeeklyAction);
  if (actions.some((action) => action === undefined)) return undefined;
  return {
    id,
    weekStart,
    weekEnd,
    weekComplete: value.weekComplete,
    ordersFrom: text(value.ordersFrom) ?? null,
    ordersTo: text(value.ordersTo) ?? null,
    ordersDaysCovered: value.ordersDaysCovered as number,
    orderedUnits: count(value.orderedUnits),
    listingsWithOrders: count(value.listingsWithOrders),
    actionsActed: value.actionsActed as number,
    readingsRecorded: value.readingsRecorded as number,
    actionsObserving: value.actionsObserving as number,
    improvedCount: value.improvedCount as number,
    unchangedCount: value.unchangedCount as number,
    regressedCount: value.regressedCount as number,
    indeterminateCount: value.indeterminateCount as number,
    actions: actions.filter((action): action is WeeklyReviewAction => action !== undefined),
    aiInvocationId: text(value.aiInvocationId) ?? null,
    compiledAt,
  };
}

function parseReading(value: unknown): OutcomeReading | undefined {
  if (!isRecord(value) || !Array.isArray(value.reasonCodes)) return undefined;
  const stage = text(value.stage);
  const baselineFrom = text(value.baselineFrom);
  const baselineTo = text(value.baselineTo);
  const observationFrom = text(value.observationFrom);
  const observationTo = text(value.observationTo);
  const verdict = text(value.verdict);
  const leadingSignal = text(value.leadingSignal);
  const computedAt = text(value.computedAt);
  if (
    stage === undefined ||
    baselineFrom === undefined ||
    baselineTo === undefined ||
    observationFrom === undefined ||
    observationTo === undefined ||
    verdict === undefined ||
    leadingSignal === undefined ||
    computedAt === undefined ||
    typeof value.windowDays !== 'number' ||
    typeof value.baselineDaysCovered !== 'number' ||
    typeof value.observationDaysCovered !== 'number' ||
    typeof value.stockoutObserved !== 'boolean' ||
    typeof value.promotionObserved !== 'boolean' ||
    typeof value.otherActionObserved !== 'boolean' ||
    typeof value.ruleVersion !== 'number'
  ) {
    return undefined;
  }
  return {
    stage,
    windowDays: value.windowDays,
    baselineFrom,
    baselineTo,
    observationFrom,
    observationTo,
    baselineDaysCovered: value.baselineDaysCovered,
    observationDaysCovered: value.observationDaysCovered,
    baselineOrderedUnits: count(value.baselineOrderedUnits),
    observationOrderedUnits: count(value.observationOrderedUnits),
    baselineSearchUsers: count(value.baselineSearchUsers),
    observationSearchUsers: count(value.observationSearchUsers),
    baselinePriceIndex: text(value.baselinePriceIndex) ?? null,
    observationPriceIndex: text(value.observationPriceIndex) ?? null,
    observationBuyerPriceMin: decimal(value.observationBuyerPriceMin),
    observationBuyerPriceMax: decimal(value.observationBuyerPriceMax),
    stockoutObserved: value.stockoutObserved,
    promotionObserved: value.promotionObserved,
    priceHeld: typeof value.priceHeld === 'boolean' ? value.priceHeld : null,
    otherActionObserved: value.otherActionObserved,
    verdict,
    leadingSignal,
    reasonCodes: value.reasonCodes.filter((code): code is string => typeof code === 'string'),
    ruleVersion: value.ruleVersion,
    computedAt,
  };
}

function listOf<T>(
  body: unknown,
  parse: (value: unknown) => T | undefined,
): readonly T[] | undefined {
  if (!Array.isArray(body)) return undefined;
  const items = body.map(parse);
  return items.some((item) => item === undefined)
    ? undefined
    : items.filter((item): item is T => item !== undefined);
}

function isAbsent(value: unknown): boolean {
  return value === null || value === undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}

function decimal(value: unknown): string | null {
  if (typeof value === 'string' && value !== '') return value;
  return typeof value === 'number' ? String(value) : null;
}

function count(value: unknown): number | null {
  return typeof value === 'number' ? value : null;
}
