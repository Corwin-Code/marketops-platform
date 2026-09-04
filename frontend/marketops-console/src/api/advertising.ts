/**
 * The advertising console's view of the backend.
 *
 * Every field the backend refuses to collapse arrives here separately, and the
 * parser refuses a body that is missing one rather than defaulting it. A
 * console that filled in a missing evidence state with a plausible one would be
 * inventing the very thing the state exists to report.
 */

/** One factor that contributed to a case's rank. */
export interface AdvertisingRankFactor {
  readonly code: string;
  readonly value: number | undefined;
  readonly weight: number | undefined;
  readonly contribution: number | undefined;
  readonly absenceReason: string | undefined;
}

/** One advertising case, as the queue and the case view read it. */
export interface AdvertisingCase {
  readonly id: string;
  readonly storeId: string;
  readonly platformCode: string;
  readonly adNativeObjectId: string;
  readonly nativeObjectKind: string;
  readonly nativeObjectName: string | undefined;
  readonly lane: string;
  readonly protectionTier: string | undefined;
  readonly causeCode: string;
  readonly accountableRoleCode: string | undefined;
  readonly evidenceState: string;
  readonly confidenceState: string;
  readonly blockerCodes: readonly string[];
  readonly contributionProfitState: string;
  readonly contributionProfitAmount: number | undefined;
  readonly profitPerAdRubState: string;
  readonly profitPerAdRubValue: number | undefined;
  readonly profitCurrencyCode: string | undefined;
  readonly officialSpendState: string;
  readonly officialSpendAmount: number | undefined;
  readonly eligibleTrafficState: string;
  readonly eligibleTrafficCount: number | undefined;
  readonly maxCpcState: string;
  readonly maxCpcAmount: number | undefined;
  readonly currentBidState: string;
  readonly currentBidAmount: number | undefined;
  readonly rankScore: number;
  readonly asOf: string;
  readonly rankFactors: readonly AdvertisingRankFactor[];
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function decimal(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === 'string' && value.length > 0) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
}

/** One rank factor, or `undefined` when the body cannot be read as one. */
export function parseAdvertisingRankFactor(body: unknown): AdvertisingRankFactor | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const code = text(record.code);
  if (code === undefined) {
    return undefined;
  }
  return {
    code,
    value: decimal(record.value),
    weight: decimal(record.weight),
    contribution: decimal(record.contribution),
    absenceReason: text(record.absenceReason),
  };
}

/**
 * One case, or `undefined` when a field the console must not invent is missing.
 *
 * The required set is deliberately the identity and every state. An amount may
 * be absent — that is what a value state is for — but the state that explains
 * its absence may not.
 */
export function parseAdvertisingCase(body: unknown): AdvertisingCase | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const id = text(record.id);
  const storeId = text(record.storeId);
  const adNativeObjectId = text(record.adNativeObjectId);
  const lane = text(record.lane);
  const causeCode = text(record.causeCode);
  const evidenceState = text(record.evidenceState);
  const confidenceState = text(record.confidenceState);
  if (
    id === undefined ||
    storeId === undefined ||
    adNativeObjectId === undefined ||
    lane === undefined ||
    causeCode === undefined ||
    evidenceState === undefined ||
    confidenceState === undefined
  ) {
    return undefined;
  }
  const factors = Array.isArray(record.rankFactors)
    ? record.rankFactors
        .map(parseAdvertisingRankFactor)
        .filter((item): item is AdvertisingRankFactor => item !== undefined)
    : [];
  return {
    id,
    storeId,
    platformCode: text(record.platformCode) ?? 'UNKNOWN',
    adNativeObjectId,
    nativeObjectKind: text(record.nativeObjectKind) ?? 'UNKNOWN',
    nativeObjectName: text(record.nativeObjectName),
    lane,
    protectionTier: text(record.protectionTier),
    causeCode,
    accountableRoleCode: text(record.accountableRoleCode),
    evidenceState,
    confidenceState,
    blockerCodes: Array.isArray(record.blockerCodes)
      ? record.blockerCodes.filter((item): item is string => typeof item === 'string')
      : [],
    contributionProfitState: text(record.contributionProfitState) ?? 'UNKNOWN',
    contributionProfitAmount: decimal(record.contributionProfitAmount),
    profitPerAdRubState: text(record.profitPerAdRubState) ?? 'UNKNOWN',
    profitPerAdRubValue: decimal(record.profitPerAdRubValue),
    profitCurrencyCode: text(record.profitCurrencyCode),
    officialSpendState: text(record.officialSpendState) ?? 'UNKNOWN',
    officialSpendAmount: decimal(record.officialSpendAmount),
    eligibleTrafficState: text(record.eligibleTrafficState) ?? 'UNKNOWN',
    eligibleTrafficCount: decimal(record.eligibleTrafficCount),
    maxCpcState: text(record.maxCpcState) ?? 'UNKNOWN',
    maxCpcAmount: decimal(record.maxCpcAmount),
    currentBidState: text(record.currentBidState) ?? 'UNKNOWN',
    currentBidAmount: decimal(record.currentBidAmount),
    rankScore: decimal(record.rankScore) ?? 0,
    asOf: text(record.asOf) ?? '',
    rankFactors: factors,
  };
}
