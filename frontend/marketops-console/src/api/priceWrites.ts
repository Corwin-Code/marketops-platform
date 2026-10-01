/**
 * Controlling the platform's price writes (W1): a store's write status, the write switches, the pilot
 * allowlist and what an operator can do about a price command that stopped moving.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** How far a store is from taking price writes. */
export interface WriteStatus {
  readonly storeId: string;
  /** The marketplace the store sells on, e.g. OZON. */
  readonly platformCode: string | null;
  /** The deployment's production-write switch. */
  readonly productionWritesEnabled: boolean;
  /** The marketplace's price-change capability, or `null` when none is registered. */
  readonly capabilityId: string | null;
  /** UNKNOWN, UNVERIFIED or VERIFIED. */
  readonly capabilityVerification: string | null;
  /** What still stands in the way at store level (allowlist and promotions aside). */
  readonly reasons: readonly string[];
}

/** One write switch. */
export interface WriteSwitch {
  readonly id: string;
  /** GLOBAL, PLATFORM, MARKETPLACE_ACCOUNT, STORE or CAPABILITY. */
  readonly scopeKind: string;
  readonly platformCode: string | null;
  readonly marketplaceAccountId: string | null;
  readonly storeId: string | null;
  readonly capabilityId: string | null;
  /** ENABLED or DISABLED. */
  readonly state: string;
  readonly status: string;
  readonly updatedAt: string;
}

/** One allowlist entry: a whole store, or one listing variant of it. */
export interface AllowlistEntry {
  readonly id: string;
  /** PRICE_CHANGE, or LISTING_CONTENT_CHANGE for title and description writes (W2). */
  readonly actionKind: string;
  readonly platformCode: string;
  readonly storeId: string;
  readonly platformListingVariantId: string | null;
  readonly validFrom: string;
  readonly validUntil: string;
  /** ACTIVE or REVOKED. */
  readonly status: string;
  readonly reason: string;
  readonly revokedReason: string | null;
  readonly version: number;
}

/** Load a store's write status. */
export function fetchWriteStatus(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<WriteStatus>> {
  return request(
    context,
    `/api/v1/console/commands/stores/${encodeURIComponent(storeId)}/write-status`,
    parseWriteStatus,
  );
}

/** Load the price-write switches. */
export function fetchWriteSwitches(
  context: ConsoleRequest,
): Promise<ConsoleOutcome<readonly WriteSwitch[]>> {
  return request(context, '/api/v1/console/commands/kill-switch', (body) =>
    listOf(body, parseSwitch),
  );
}

/** Turn price writes off (never gated) or on (needs a recent sign-in) at one scope. */
export function moveWriteSwitch(
  context: ConsoleRequest,
  direction: 'enable' | 'disable',
  input: {
    readonly scopeKind: string;
    readonly scopeReference: string | null;
    readonly storeId: string | null;
    readonly reason: string;
  },
): Promise<ConsoleOutcome<true>> {
  return request(context, `/api/v1/console/commands/kill-switch/${direction}`, () => true, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

/** Load the pilot allowlist. */
export function fetchAllowlist(
  context: ConsoleRequest,
): Promise<ConsoleOutcome<readonly AllowlistEntry[]>> {
  return request(context, '/api/v1/console/policy/pilot-allowlist', (body) =>
    listOf(body, parseAllowlistEntry),
  );
}

/** Put a store, or one of its listing variants, on the allowlist for a window. */
export function grantAllowlist(
  context: ConsoleRequest,
  input: {
    readonly platformCode: string;
    readonly storeId: string;
    readonly platformListingVariantId: string | null;
    readonly validFrom: string;
    readonly validUntil: string;
    readonly reason: string;
    /** PRICE_CHANGE when absent. */
    readonly actionKind?: string;
  },
): Promise<ConsoleOutcome<true>> {
  return request(context, '/api/v1/console/policy/pilot-allowlist', () => true, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

/** Take an entry off the allowlist. */
export function revokeAllowlist(
  context: ConsoleRequest,
  entryId: string,
  input: { readonly reason: string; readonly expectedVersion: number },
): Promise<ConsoleOutcome<true>> {
  return request(
    context,
    `/api/v1/console/policy/pilot-allowlist/${encodeURIComponent(entryId)}/revocation`,
    () => true,
    { method: 'POST', body: JSON.stringify(input) },
  );
}

/**
 * Act on a price command that stopped moving: read the platform again, take it over by hand, or
 * close it as failed. All need a recent sign-in.
 */
export function resolveCommand(
  context: ConsoleRequest,
  commandId: string,
  action: 'readback' | 'manual-resolution' | 'closure',
  reason: string,
): Promise<ConsoleOutcome<true>> {
  return request(
    context,
    `/api/v1/console/commands/${encodeURIComponent(commandId)}/${action}`,
    () => true,
    { method: 'POST', body: JSON.stringify({ reason }) },
  );
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseWriteStatus(body: unknown): WriteStatus | undefined {
  if (!isRecord(body) || !Array.isArray(body.reasons)) return undefined;
  const storeId = text(body.storeId);
  if (storeId === undefined || typeof body.productionWritesEnabled !== 'boolean') return undefined;
  return {
    storeId,
    platformCode: text(body.platformCode) ?? null,
    productionWritesEnabled: body.productionWritesEnabled,
    capabilityId: text(body.capabilityId) ?? null,
    capabilityVerification: text(body.capabilityVerification) ?? null,
    reasons: body.reasons.filter((reason): reason is string => typeof reason === 'string'),
  };
}

function parseSwitch(value: unknown): WriteSwitch | undefined {
  if (!isRecord(value)) return undefined;
  const id = text(value.id);
  const scopeKind = text(value.scopeKind);
  const state = text(value.state);
  const status = text(value.status);
  const updatedAt = text(value.updatedAt);
  if (
    id === undefined ||
    scopeKind === undefined ||
    state === undefined ||
    status === undefined ||
    updatedAt === undefined
  ) {
    return undefined;
  }
  return {
    id,
    scopeKind,
    platformCode: text(value.platformCode) ?? null,
    marketplaceAccountId: text(value.marketplaceAccountId) ?? null,
    storeId: text(value.storeId) ?? null,
    capabilityId: text(value.capabilityId) ?? null,
    state,
    status,
    updatedAt,
  };
}

function parseAllowlistEntry(value: unknown): AllowlistEntry | undefined {
  if (!isRecord(value)) return undefined;
  const id = text(value.id);
  const platformCode = text(value.platformCode);
  const storeId = text(value.storeId);
  const validFrom = text(value.validFrom);
  const validUntil = text(value.validUntil);
  const status = text(value.status);
  const reason = text(value.reason);
  if (
    id === undefined ||
    platformCode === undefined ||
    storeId === undefined ||
    validFrom === undefined ||
    validUntil === undefined ||
    status === undefined ||
    reason === undefined ||
    typeof value.version !== 'number'
  ) {
    return undefined;
  }
  return {
    id,
    actionKind: text(value.actionKind) ?? 'PRICE_CHANGE',
    platformCode,
    storeId,
    platformListingVariantId: text(value.platformListingVariantId) ?? null,
    validFrom,
    validUntil,
    status,
    reason,
    revokedReason: text(value.revokedReason) ?? null,
    version: value.version,
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}
