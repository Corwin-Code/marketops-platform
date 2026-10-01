/**
 * A store's own commercial policy: the limits its price changes are checked against. Rates and
 * amounts are decimal text; counts and durations are numbers.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** One limit of the vocabulary a policy is configured from. */
export interface LimitKind {
  readonly code: string;
  readonly displayName: string;
  /** RATE, AMOUNT, COUNT or DURATION_SECONDS: which typed value carries it. */
  readonly valueKind: string;
  /** The guardrail reason a breach produces. */
  readonly guardrailCode: string;
  readonly requiredForPriceWrite: boolean;
  readonly ordinal: number;
}

/** One configured limit; exactly one value is set. */
export interface PolicyLimit {
  readonly limitCode: string;
  readonly rateValue: string | null;
  readonly amountValue: string | null;
  readonly countValue: number | null;
  readonly durationSeconds: number | null;
}

/** One policy version. */
export interface PolicyVersion {
  readonly policyId: string;
  readonly policyCode: string;
  readonly policyVersion: number;
  /** STORE for the store's own; ORGANIZATION or PLATFORM when a wider policy applies. */
  readonly scopeKind: string;
  readonly lifecycleObjective: string;
  readonly currencyCode: string;
  readonly effectiveFrom: string;
  readonly effectiveTo: string | null;
  /** ACTIVE, ENDED or CANCELLED. */
  readonly status: string;
  readonly reason: string;
  readonly publishedByViewer: boolean;
}

/** A store's policy page. */
export interface StorePolicy {
  readonly storeId: string;
  readonly generatedAt: string;
  /** The store's currency, the one its policy amounts are in. */
  readonly currencyCode: string | null;
  readonly limitKinds: readonly LimitKind[];
  /** The policy the store's listings are checked against now. */
  readonly inForce: PolicyVersion | null;
  /** The limits of the policy in force, in the vocabulary's order. */
  readonly limits: readonly PolicyLimit[];
  /** The store's own versions, newest first. */
  readonly versions: readonly PolicyVersion[];
}

/** One limit to publish: exactly the value its kind names (a rate as a share, e.g. `0.15`). */
export interface LimitInput {
  readonly limitCode: string;
  readonly rateValue?: number;
  readonly amountValue?: number;
  readonly countValue?: number;
  readonly durationSeconds?: number;
}

/** Load the store's policy page. */
export function fetchStorePolicy(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<StorePolicy>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/commercial-policy`,
    parseStorePolicy,
  );
}

/** Publish a new version of the store's own policy; the answer is the new version's id. */
export function publishStorePolicy(
  context: ConsoleRequest,
  storeId: string,
  input: {
    readonly lifecycleObjective: string;
    readonly limits: readonly LimitInput[];
    readonly reason: string;
  },
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/commercial-policy`,
    (body) => (isRecord(body) ? text(body.policyId) : undefined),
    { method: 'POST', body: JSON.stringify(input) },
  );
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseStorePolicy(body: unknown): StorePolicy | undefined {
  if (
    !isRecord(body) ||
    !Array.isArray(body.limitKinds) ||
    !Array.isArray(body.limits) ||
    !Array.isArray(body.versions)
  ) {
    return undefined;
  }
  const storeId = text(body.storeId);
  const generatedAt = text(body.generatedAt);
  const limitKinds = body.limitKinds.map(parseLimitKind);
  const limits = body.limits.map(parseLimit);
  const versions = body.versions.map(parseVersion);
  const inForce = body.inForce === null ? null : parseVersion(body.inForce);
  if (
    storeId === undefined ||
    generatedAt === undefined ||
    inForce === undefined ||
    limitKinds.some((kind) => kind === undefined) ||
    limits.some((limit) => limit === undefined) ||
    versions.some((version) => version === undefined)
  ) {
    return undefined;
  }
  return {
    storeId,
    generatedAt,
    currencyCode: text(body.currencyCode) ?? null,
    limitKinds: limitKinds.filter((kind): kind is LimitKind => kind !== undefined),
    inForce,
    limits: limits.filter((limit): limit is PolicyLimit => limit !== undefined),
    versions: versions.filter((version): version is PolicyVersion => version !== undefined),
  };
}

function parseLimitKind(value: unknown): LimitKind | undefined {
  if (!isRecord(value)) return undefined;
  const code = text(value.code);
  const displayName = text(value.displayName);
  const valueKind = text(value.valueKind);
  const guardrailCode = text(value.guardrailCode);
  if (
    code === undefined ||
    displayName === undefined ||
    valueKind === undefined ||
    guardrailCode === undefined ||
    typeof value.requiredForPriceWrite !== 'boolean' ||
    typeof value.ordinal !== 'number'
  ) {
    return undefined;
  }
  return {
    code,
    displayName,
    valueKind,
    guardrailCode,
    requiredForPriceWrite: value.requiredForPriceWrite,
    ordinal: value.ordinal,
  };
}

function parseLimit(value: unknown): PolicyLimit | undefined {
  if (!isRecord(value)) return undefined;
  const limitCode = text(value.limitCode);
  if (limitCode === undefined) return undefined;
  return {
    limitCode,
    rateValue: text(value.rateValue) ?? null,
    amountValue: text(value.amountValue) ?? null,
    countValue: typeof value.countValue === 'number' ? value.countValue : null,
    durationSeconds: typeof value.durationSeconds === 'number' ? value.durationSeconds : null,
  };
}

function parseVersion(value: unknown): PolicyVersion | undefined {
  if (!isRecord(value)) return undefined;
  const policyId = text(value.policyId);
  const policyCode = text(value.policyCode);
  const scopeKind = text(value.scopeKind);
  const lifecycleObjective = text(value.lifecycleObjective);
  const currencyCode = text(value.currencyCode);
  const effectiveFrom = text(value.effectiveFrom);
  const status = text(value.status);
  const reason = text(value.reason);
  if (
    policyId === undefined ||
    policyCode === undefined ||
    typeof value.policyVersion !== 'number' ||
    scopeKind === undefined ||
    lifecycleObjective === undefined ||
    currencyCode === undefined ||
    effectiveFrom === undefined ||
    status === undefined ||
    reason === undefined ||
    typeof value.publishedByViewer !== 'boolean'
  ) {
    return undefined;
  }
  return {
    policyId,
    policyCode,
    policyVersion: value.policyVersion,
    scopeKind,
    lifecycleObjective,
    currencyCode,
    effectiveFrom,
    effectiveTo: text(value.effectiveTo) ?? null,
    status,
    reason,
    publishedByViewer: value.publishedByViewer,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}
