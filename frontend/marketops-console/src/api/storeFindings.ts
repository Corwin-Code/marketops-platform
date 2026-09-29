/**
 * What the newest completed calculation run concluded about a store's
 * listings: triggered findings with the values they compared, and the
 * estimated unit economics. Every number is the run's own; amounts are
 * decimal text. Recalculating is an explicit action recorded with its author.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { CALCULATION_REQUEST_TIMEOUT_MS, request } from './console';

/** One metric value of a run. */
export interface FindingMetric {
  /** `AVAILABLE`, `NOT_AVAILABLE` or `UNDEFINED`. */
  readonly valueState: string;
  readonly value: string | null;
  readonly currencyCode: string | null;
  readonly confidenceState: string;
}

/** One triggered finding. */
export interface ListingFinding {
  readonly findingId: string;
  readonly ruleCode: string;
  readonly severity: string;
  /** The values the rule compared, as the run stored them. */
  readonly detail: Readonly<Record<string, string>>;
}

/** One listing's findings and metric values. */
export interface ListingFindings {
  readonly listingVariantId: string;
  readonly findings: readonly ListingFinding[];
  readonly metrics: Readonly<Record<string, FindingMetric>>;
}

/** How many listings one rule triggered for. */
export interface RuleCount {
  readonly ruleCode: string;
  readonly severity: string;
  readonly listings: number;
}

/** The run the conclusions come from. */
export interface FindingsRun {
  readonly runId: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly completedAt: string | null;
  readonly subjectCount: number | null;
}

/** The newest completed run's conclusions for a store. */
export interface StoreFindings {
  readonly storeId: string;
  readonly window: string;
  /** `null` when the store was never calculated over the window. */
  readonly run: FindingsRun | null;
  readonly summary: readonly RuleCount[];
  readonly listings: readonly ListingFindings[];
}

/** Load the newest seven-day conclusions of a store. */
export function fetchStoreFindings(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<StoreFindings>> {
  return request(
    context,
    `/api/v1/console/diagnosis/stores/${encodeURIComponent(storeId)}/listing-findings?window=D7`,
    parseStoreFindings,
  );
}

/** Recalculate the store over the last seven days; answers how many listings it covered. */
export function recalculateStore(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<number>> {
  return request(
    context,
    `/api/v1/console/diagnosis/stores/${encodeURIComponent(storeId)}/recalculation?window=D7`,
    (body) => {
      if (!isRecord(body)) return undefined;
      const subjects = body.subjectCount;
      return typeof subjects === 'number' && Number.isInteger(subjects) ? subjects : 0;
    },
    { method: 'POST' },
    CALCULATION_REQUEST_TIMEOUT_MS,
  );
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseStoreFindings(body: unknown): StoreFindings | undefined {
  if (!isRecord(body)) return undefined;
  const storeId = text(body.storeId);
  const window = text(body.window);
  if (storeId === undefined || window === undefined) return undefined;
  if (!Array.isArray(body.summary) || !Array.isArray(body.listings)) return undefined;
  return {
    storeId,
    window,
    run: parseRun(body.run),
    summary: body.summary
      .map(parseRuleCount)
      .filter((count): count is RuleCount => count !== undefined),
    listings: body.listings
      .map(parseListing)
      .filter((listing): listing is ListingFindings => listing !== undefined),
  };
}

function parseRun(value: unknown): FindingsRun | null {
  if (!isRecord(value)) return null;
  const runId = text(value.runId);
  const periodStart = text(value.periodStart);
  const periodEnd = text(value.periodEnd);
  if (runId === undefined || periodStart === undefined || periodEnd === undefined) return null;
  return {
    runId,
    periodStart,
    periodEnd,
    completedAt: text(value.completedAt) ?? null,
    subjectCount: typeof value.subjectCount === 'number' ? value.subjectCount : null,
  };
}

function parseRuleCount(value: unknown): RuleCount | undefined {
  if (!isRecord(value)) return undefined;
  const ruleCode = text(value.ruleCode);
  const severity = text(value.severity);
  const listings = value.listings;
  if (ruleCode === undefined || severity === undefined || typeof listings !== 'number') {
    return undefined;
  }
  return { ruleCode, severity, listings };
}

function parseListing(value: unknown): ListingFindings | undefined {
  if (!isRecord(value)) return undefined;
  const listingVariantId = text(value.listingVariantId);
  if (listingVariantId === undefined) return undefined;
  const findings = Array.isArray(value.findings)
    ? value.findings
        .map(parseFinding)
        .filter((finding): finding is ListingFinding => finding !== undefined)
    : [];
  const metrics: Record<string, FindingMetric> = {};
  if (isRecord(value.metrics)) {
    for (const [code, metric] of Object.entries(value.metrics)) {
      const parsed = parseMetric(metric);
      if (parsed !== undefined) metrics[code] = parsed;
    }
  }
  return { listingVariantId, findings, metrics };
}

function parseFinding(value: unknown): ListingFinding | undefined {
  if (!isRecord(value)) return undefined;
  const findingId = text(value.findingId);
  const ruleCode = text(value.ruleCode);
  const severity = text(value.severity);
  if (findingId === undefined || ruleCode === undefined || severity === undefined) return undefined;
  const detail: Record<string, string> = {};
  if (isRecord(value.detail)) {
    for (const [key, entry] of Object.entries(value.detail)) {
      if (typeof entry === 'string') detail[key] = entry;
      else if (typeof entry === 'number' && Number.isFinite(entry)) detail[key] = String(entry);
    }
  }
  return { findingId, ruleCode, severity, detail };
}

function parseMetric(value: unknown): FindingMetric | undefined {
  if (!isRecord(value)) return undefined;
  const valueState = text(value.valueState);
  const confidenceState = text(value.confidenceState);
  if (valueState === undefined || confidenceState === undefined) return undefined;
  return {
    valueState,
    value: typeof value.value === 'string' ? value.value : null,
    currencyCode: text(value.currencyCode) ?? null,
    confidenceState,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}
