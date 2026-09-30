/**
 * The store's own standing on the marketplace: subscription, penalty balance and localization, its
 * ratings with the marketplace's verdict on each, and its warehouses. Values are decimal text as
 * stated; times are ISO-8601 instants. Each part is absent until it was collected.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** Subscription, penalty balance and localization. */
export interface StandingSummary {
  readonly premium: boolean | null;
  readonly premiumPlus: boolean | null;
  readonly penaltyScoreExceeded: boolean | null;
  /** `null` when the store sold nothing in the last 14 days. */
  readonly localizationCalculatedAt: string | null;
  readonly localizationPercentage: string | null;
  readonly observedAt: string;
}

/** One rating with the marketplace's status. */
export interface StandingRating {
  /** The marketplace's system name, e.g. `rating_price_red`. */
  readonly ratingKey: string;
  readonly groupName: string | null;
  readonly ratingName: string | null;
  /** `INDEX`, `PERCENT`, `TIME`, `RATIO`, `REVIEW_SCORE` or `COUNT`. */
  readonly valueType: string | null;
  /** `HIGHER_IS_BETTER`, `LOWER_IS_BETTER` or `NEUTRAL`. */
  readonly direction: string | null;
  /** `OK`, `WARNING`, `CRITICAL` or `UNKNOWN_STATUS`. */
  readonly status: string | null;
  readonly currentValue: string | null;
  readonly pastValue: string | null;
}

/** One warehouse; name, address and phone are never read. */
export interface StandingWarehouse {
  readonly nativeWarehouseKey: string;
  readonly warehouseType: string | null;
  /** `created` (active), `new`, `disabled`, `blocked`, `disabled_due_to_limit` or `error`. */
  readonly status: string | null;
  readonly rfbs: boolean | null;
  /** `PICK_UP` or `DROP_OFF`. */
  readonly firstMileKind: string | null;
  readonly workingDayCount: number | null;
  /** `-1` without a limit. */
  readonly postingsLimit: number | null;
  readonly pausedAt: string | null;
}

/** The store's standing. */
export interface StoreStanding {
  readonly storeId: string;
  readonly summary: StandingSummary | null;
  readonly ratings: readonly StandingRating[];
  readonly ratingsAt: string | null;
  readonly warehouses: readonly StandingWarehouse[];
  readonly warehousesAt: string | null;
}

/** Load a store's standing. */
export function fetchStoreStanding(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<StoreStanding>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/standing`,
    parseStoreStanding,
  );
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseStoreStanding(body: unknown): StoreStanding | undefined {
  if (!isRecord(body)) return undefined;
  const storeId = text(body.storeId);
  if (storeId === undefined || !Array.isArray(body.ratings) || !Array.isArray(body.warehouses)) {
    return undefined;
  }
  const summary = body.summary === null ? null : parseSummary(body.summary);
  const ratings = body.ratings.map(parseRating);
  const warehouses = body.warehouses.map(parseWarehouse);
  if (
    summary === undefined ||
    ratings.some((rating) => rating === undefined) ||
    warehouses.some((warehouse) => warehouse === undefined)
  ) {
    return undefined;
  }
  return {
    storeId,
    summary,
    ratings: ratings.filter((rating): rating is StandingRating => rating !== undefined),
    ratingsAt: optionalText(body.ratingsAt),
    warehouses: warehouses.filter(
      (warehouse): warehouse is StandingWarehouse => warehouse !== undefined,
    ),
    warehousesAt: optionalText(body.warehousesAt),
  };
}

function parseSummary(value: unknown): StandingSummary | undefined {
  if (!isRecord(value)) return undefined;
  const observedAt = text(value.observedAt);
  if (observedAt === undefined) return undefined;
  return {
    premium: flag(value.premium),
    premiumPlus: flag(value.premiumPlus),
    penaltyScoreExceeded: flag(value.penaltyScoreExceeded),
    localizationCalculatedAt: optionalText(value.localizationCalculatedAt),
    localizationPercentage: optionalText(value.localizationPercentage),
    observedAt,
  };
}

function parseRating(value: unknown): StandingRating | undefined {
  if (!isRecord(value)) return undefined;
  const ratingKey = text(value.ratingKey);
  if (ratingKey === undefined) return undefined;
  return {
    ratingKey,
    groupName: optionalText(value.groupName),
    ratingName: optionalText(value.ratingName),
    valueType: optionalText(value.valueType),
    direction: optionalText(value.direction),
    status: optionalText(value.status),
    currentValue: optionalText(value.currentValue),
    pastValue: optionalText(value.pastValue),
  };
}

function parseWarehouse(value: unknown): StandingWarehouse | undefined {
  if (!isRecord(value)) return undefined;
  const nativeWarehouseKey = text(value.nativeWarehouseKey);
  if (nativeWarehouseKey === undefined) return undefined;
  return {
    nativeWarehouseKey,
    warehouseType: optionalText(value.warehouseType),
    status: optionalText(value.status),
    rfbs: flag(value.rfbs),
    firstMileKind: optionalText(value.firstMileKind),
    workingDayCount: optionalNumber(value.workingDayCount),
    postingsLimit: optionalNumber(value.postingsLimit),
    pausedAt: optionalText(value.pausedAt),
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}

function optionalText(value: unknown): string | null {
  return text(value) ?? null;
}

function flag(value: unknown): boolean | null {
  return typeof value === 'boolean' ? value : null;
}

function optionalNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}
