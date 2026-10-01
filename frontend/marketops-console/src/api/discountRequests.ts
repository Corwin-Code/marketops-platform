/**
 * Buyers' requests to buy a product at a lower price (P8): counts, requests per month, the newest
 * requests and the SKUs asked about most. Amounts are decimal text in the request's currency;
 * percentages are decimal text in percent; times are ISO-8601 instants.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** Counts over the requests. */
export interface DiscountRequestSummary {
  readonly total: number;
  /** Requests still waiting for a decision (`NEW`). */
  readonly pending: number;
  readonly approved: number;
  readonly declined: number;
  /** Distinct SKUs asked about. */
  readonly items: number;
  /** Of them, SKUs the catalogue lists today. */
  readonly itemsInCatalog: number;
  readonly requestsInCatalog: number;
  /** The median discount buyers asked for, in percent of the price they saw. */
  readonly medianDiscountPercent: string | null;
  readonly firstRequestedAt: string | null;
  readonly latestRequestedAt: string | null;
  /** The earliest time a new request must be decided by, while it is ahead. */
  readonly nextDeadline: string | null;
}

/** Requests made in one UTC month. */
export interface DiscountRequestMonth {
  /** `YYYY-MM`. */
  readonly month: string;
  readonly requests: number;
}

/** One SKU buyers asked about. */
export interface DiscountRequestItem {
  readonly nativeItemKey: string;
  /** The listing variant carrying the SKU; `null` when the catalogue no longer lists it. */
  readonly subjectId: string | null;
  readonly productName: string | null;
  readonly requests: number;
  readonly pending: number;
  readonly latestRequestedAt: string | null;
  readonly medianDiscountPercent: string | null;
}

/** One request in its newest state. */
export interface DiscountRequest {
  readonly requestKey: string;
  readonly subjectId: string | null;
  readonly nativeItemKey: string | null;
  readonly productName: string | null;
  /** `NEW`, `APPROVED` or `DECLINED`. */
  readonly status: string | null;
  readonly requestedAt: string | null;
  readonly moderatedAt: string | null;
  /** For a new request, the time left to decide it. */
  readonly expiresAt: string | null;
  readonly currencyCode: string | null;
  /** The price before every discount when the buyer asked. */
  readonly originalPrice: string | null;
  readonly requestedPrice: string | null;
  readonly requestedDiscountPercent: string | null;
  readonly requestedQuantity: number | null;
  readonly approvedPrice: string | null;
  readonly approvedQuantity: number | null;
  readonly autoModerated: boolean | null;
}

/** The requests of a store or of one listing variant. */
export interface DiscountRequests {
  readonly storeId: string;
  readonly subjectId: string | null;
  /** When the store's newest answer was observed; `null` before the first. */
  readonly observedAt: string | null;
  readonly summary: DiscountRequestSummary;
  readonly months: readonly DiscountRequestMonth[];
  /** The SKUs asked about most, store-wide only. */
  readonly items: readonly DiscountRequestItem[];
  /** The newest requests first. */
  readonly requests: readonly DiscountRequest[];
}

/** Load a store's discount requests, or one listing variant's. */
export function fetchDiscountRequests(
  context: ConsoleRequest,
  storeId: string,
  subjectId?: string,
): Promise<ConsoleOutcome<DiscountRequests>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/discount-requests` +
      (subjectId === undefined ? '' : `?subjectId=${encodeURIComponent(subjectId)}`),
    parseDiscountRequests,
  );
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseDiscountRequests(body: unknown): DiscountRequests | undefined {
  if (!isRecord(body)) return undefined;
  const storeId = text(body.storeId);
  const summary = parseSummary(body.summary);
  const months = listOf(body.months, parseMonth);
  const items = listOf(body.items, parseItem);
  const requests = listOf(body.requests, parseRequest);
  if (
    storeId === undefined ||
    summary === undefined ||
    months === undefined ||
    items === undefined ||
    requests === undefined
  ) {
    return undefined;
  }
  return {
    storeId,
    subjectId: optionalText(body.subjectId),
    observedAt: optionalText(body.observedAt),
    summary,
    months,
    items,
    requests,
  };
}

function parseSummary(value: unknown): DiscountRequestSummary | undefined {
  if (!isRecord(value)) return undefined;
  const counts = [
    value.total,
    value.pending,
    value.approved,
    value.declined,
    value.items,
    value.itemsInCatalog,
    value.requestsInCatalog,
  ].map(count);
  const [total, pending, approved, declined, items, itemsInCatalog, requestsInCatalog] = counts;
  if (
    total === undefined ||
    pending === undefined ||
    approved === undefined ||
    declined === undefined ||
    items === undefined ||
    itemsInCatalog === undefined ||
    requestsInCatalog === undefined
  ) {
    return undefined;
  }
  return {
    total,
    pending,
    approved,
    declined,
    items,
    itemsInCatalog,
    requestsInCatalog,
    medianDiscountPercent: optionalText(value.medianDiscountPercent),
    firstRequestedAt: optionalText(value.firstRequestedAt),
    latestRequestedAt: optionalText(value.latestRequestedAt),
    nextDeadline: optionalText(value.nextDeadline),
  };
}

function parseMonth(value: unknown): DiscountRequestMonth | undefined {
  if (!isRecord(value)) return undefined;
  const month = text(value.month);
  const requests = count(value.requests);
  return month === undefined || requests === undefined ? undefined : { month, requests };
}

function parseItem(value: unknown): DiscountRequestItem | undefined {
  if (!isRecord(value)) return undefined;
  const nativeItemKey = text(value.nativeItemKey);
  const requests = count(value.requests);
  const pending = count(value.pending);
  if (nativeItemKey === undefined || requests === undefined || pending === undefined) {
    return undefined;
  }
  return {
    nativeItemKey,
    subjectId: optionalText(value.subjectId),
    productName: optionalText(value.productName),
    requests,
    pending,
    latestRequestedAt: optionalText(value.latestRequestedAt),
    medianDiscountPercent: optionalText(value.medianDiscountPercent),
  };
}

function parseRequest(value: unknown): DiscountRequest | undefined {
  if (!isRecord(value)) return undefined;
  const requestKey = text(value.requestKey);
  if (requestKey === undefined) return undefined;
  return {
    requestKey,
    subjectId: optionalText(value.subjectId),
    nativeItemKey: optionalText(value.nativeItemKey),
    productName: optionalText(value.productName),
    status: optionalText(value.status),
    requestedAt: optionalText(value.requestedAt),
    moderatedAt: optionalText(value.moderatedAt),
    expiresAt: optionalText(value.expiresAt),
    currencyCode: optionalText(value.currencyCode),
    originalPrice: optionalText(value.originalPrice),
    requestedPrice: optionalText(value.requestedPrice),
    requestedDiscountPercent: optionalText(value.requestedDiscountPercent),
    requestedQuantity: optionalCount(value.requestedQuantity),
    approvedPrice: optionalText(value.approvedPrice),
    approvedQuantity: optionalCount(value.approvedQuantity),
    autoModerated: typeof value.autoModerated === 'boolean' ? value.autoModerated : null,
  };
}

function listOf<T>(
  body: unknown,
  parse: (value: unknown) => T | undefined,
): readonly T[] | undefined {
  if (!Array.isArray(body)) return undefined;
  const parsed: T[] = [];
  for (const value of body) {
    const item = parse(value);
    if (item === undefined) return undefined;
    parsed.push(item);
  }
  return parsed;
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

function count(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : undefined;
}

function optionalCount(value: unknown): number | null {
  return count(value) ?? null;
}
