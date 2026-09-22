import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/**
 * The Owner's launch allowance maintenance: every allowance version with what
 * it has occupied, and the two writes, publish and retire.
 *
 * Every decimal stays a string: a limit, reserve or occupied value is never
 * turned into a floating-point number on its way to or from the screen.
 */

const ALLOWANCES = '/api/v1/console/listing/allowances';

/** A store a store-scoped allowance may name. */
export interface AllowanceStore {
  readonly storeId: string;
  readonly code: string;
  readonly displayName: string;
  readonly platformCode: string | null;
  readonly currencyCode: string | null;
  readonly canPublish: boolean;
}

/** The disposal reserve one current calibration package accepts for one axis. */
export interface AllowanceReservePolicy {
  readonly packageId: string;
  readonly packageCode: string;
  readonly packageVersion: number;
  readonly purposeCode: string;
  readonly scopeKind: string;
  readonly platformCode: string | null;
  readonly storeId: string | null;
  readonly storePlatformCode: string | null;
  readonly axisCode: string;
  readonly reserveValue: string;
}

/** Where an allowance version stands at the time of reading. */
export type AllowanceLifecycle = 'CURRENT' | 'SCHEDULED' | 'ENDED' | 'RETIRED';

/** One allowance version. */
export interface ExposureAllowance {
  readonly id: string;
  readonly version: number;
  readonly axisCode: string;
  readonly scopeKind: string;
  readonly platformCode: string | null;
  readonly storeId: string | null;
  readonly storeName: string | null;
  readonly unitCode: string;
  readonly limitValue: string;
  readonly reserveValue: string;
  /** Present for current and scheduled versions only. */
  readonly occupiedValue: string | null;
  readonly headroom: string | null;
  readonly occupancyUnresolved: boolean | null;
  readonly liveOccupations: number | null;
  readonly lifecycle: AllowanceLifecycle;
  readonly effectiveFrom: string;
  readonly effectiveTo: string | null;
  readonly publishedAt: string;
  readonly publishedByName: string | null;
  readonly evidenceReference: string;
  readonly publishReason: string | null;
  readonly supersededByAllowanceId: string | null;
  readonly retiredAt: string | null;
  readonly retiredByName: string | null;
  readonly retireReason: string | null;
  readonly canManage: boolean;
}

/** Everything the maintenance page shows. */
export interface AllowanceOverview {
  readonly asOf: string;
  readonly canPublishOrganization: boolean;
  readonly stores: readonly AllowanceStore[];
  readonly platforms: readonly string[];
  readonly reservePolicies: readonly AllowanceReservePolicy[];
  readonly allowances: readonly ExposureAllowance[];
}

/** A new allowance version as the Owner enters it. */
export interface AllowanceDraft {
  readonly scopeKind: 'ORGANIZATION' | 'PLATFORM' | 'STORE';
  readonly platformCode?: string;
  readonly storeId?: string;
  readonly axisCode: string;
  readonly limitValue: string;
  readonly reserveValue: string;
  /** ISO instant; omitted means "from now". */
  readonly effectiveFrom?: string;
  readonly evidenceReference: string;
  readonly reason: string;
}

/** A reserve below what a current calibration package accepts. */
export interface AllowanceReserveWarning {
  readonly packageId: string;
  readonly packageCode: string;
  readonly purposeCode: string;
  readonly axisCode: string;
  readonly acceptedReserve: string;
  readonly publishedReserve: string;
}

/** What one publication did. */
export interface AllowancePublication {
  readonly allowanceId: string;
  readonly allowanceVersion: number;
  readonly unitCode: string;
  readonly effectiveFrom: string;
  readonly reserveWarnings: readonly AllowanceReserveWarning[];
}

type Row = Record<string, unknown>;

function row(body: unknown): Row | undefined {
  return typeof body === 'object' && body !== null ? (body as Row) : undefined;
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function optionalText(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

/** A decimal kept as text; a JSON number is accepted but never produced by the backend. */
function decimal(value: unknown): string | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  return text(value);
}

function integer(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) ? value : undefined;
}

function list<T>(
  value: unknown,
  parse: (body: unknown) => T | undefined,
): readonly T[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const out: T[] = [];
  for (const item of value) {
    const parsed = parse(item);
    if (parsed === undefined) return undefined;
    out.push(parsed);
  }
  return out;
}

const LIFECYCLES: readonly string[] = ['CURRENT', 'SCHEDULED', 'ENDED', 'RETIRED'];

function parseStore(body: unknown): AllowanceStore | undefined {
  const r = row(body);
  const storeId = text(r?.storeId);
  const code = text(r?.code);
  if (r === undefined || storeId === undefined || code === undefined) return undefined;
  return {
    storeId,
    code,
    displayName: text(r.displayName) ?? code,
    platformCode: optionalText(r.platformCode),
    currencyCode: optionalText(r.currencyCode),
    canPublish: r.canPublish === true,
  };
}

function parseReservePolicy(body: unknown): AllowanceReservePolicy | undefined {
  const r = row(body);
  const packageId = text(r?.packageId);
  const packageCode = text(r?.packageCode);
  const axisCode = text(r?.axisCode);
  const scopeKind = text(r?.scopeKind);
  const reserveValue = text(r?.reserveValue);
  if (
    r === undefined ||
    packageId === undefined ||
    packageCode === undefined ||
    axisCode === undefined ||
    scopeKind === undefined ||
    reserveValue === undefined
  ) {
    return undefined;
  }
  return {
    packageId,
    packageCode,
    packageVersion: integer(r.packageVersion) ?? 0,
    purposeCode: text(r.purposeCode) ?? '',
    scopeKind,
    platformCode: optionalText(r.platformCode),
    storeId: optionalText(r.storeId),
    storePlatformCode: optionalText(r.storePlatformCode),
    axisCode,
    reserveValue,
  };
}

function parseAllowance(body: unknown): ExposureAllowance | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id);
  const version = integer(r.version);
  const axisCode = text(r.axisCode);
  const scopeKind = text(r.scopeKind);
  const unitCode = text(r.unitCode);
  const limitValue = decimal(r.limitValue);
  const reserveValue = decimal(r.reserveValue);
  const lifecycle = text(r.lifecycle);
  const effectiveFrom = text(r.effectiveFrom);
  const publishedAt = text(r.publishedAt);
  if (
    id === undefined ||
    version === undefined ||
    axisCode === undefined ||
    scopeKind === undefined ||
    unitCode === undefined ||
    limitValue === undefined ||
    reserveValue === undefined ||
    lifecycle === undefined ||
    !LIFECYCLES.includes(lifecycle) ||
    effectiveFrom === undefined ||
    publishedAt === undefined
  ) {
    return undefined;
  }
  return {
    id,
    version,
    axisCode,
    scopeKind,
    platformCode: optionalText(r.platformCode),
    storeId: optionalText(r.storeId),
    storeName: optionalText(r.storeName),
    unitCode,
    limitValue,
    reserveValue,
    occupiedValue: decimal(r.occupiedValue) ?? null,
    headroom: decimal(r.headroom) ?? null,
    occupancyUnresolved: typeof r.occupancyUnresolved === 'boolean' ? r.occupancyUnresolved : null,
    liveOccupations: integer(r.liveOccupations) ?? null,
    lifecycle: lifecycle as AllowanceLifecycle,
    effectiveFrom,
    effectiveTo: optionalText(r.effectiveTo),
    publishedAt,
    publishedByName: optionalText(r.publishedByName),
    evidenceReference: text(r.evidenceReference) ?? '',
    publishReason: optionalText(r.publishReason),
    supersededByAllowanceId: optionalText(r.supersededByAllowanceId),
    retiredAt: optionalText(r.retiredAt),
    retiredByName: optionalText(r.retiredByName),
    retireReason: optionalText(r.retireReason),
    canManage: r.canManage === true,
  };
}

function parseOverview(body: unknown): AllowanceOverview | undefined {
  const r = row(body);
  const asOf = text(r?.asOf);
  if (r === undefined || asOf === undefined) return undefined;
  const stores = list(r.stores, parseStore);
  const reservePolicies = list(r.reservePolicies, parseReservePolicy);
  const allowances = list(r.allowances, parseAllowance);
  const platforms = list(r.platforms, text);
  if (
    stores === undefined ||
    reservePolicies === undefined ||
    allowances === undefined ||
    platforms === undefined
  ) {
    return undefined;
  }
  return {
    asOf,
    canPublishOrganization: r.canPublishOrganization === true,
    stores,
    platforms,
    reservePolicies,
    allowances,
  };
}

function parseWarning(body: unknown): AllowanceReserveWarning | undefined {
  const r = row(body);
  const packageId = text(r?.packageId);
  const acceptedReserve = text(r?.acceptedReserve);
  if (r === undefined || packageId === undefined || acceptedReserve === undefined) return undefined;
  return {
    packageId,
    packageCode: text(r.packageCode) ?? '',
    purposeCode: text(r.purposeCode) ?? '',
    axisCode: text(r.axisCode) ?? '',
    acceptedReserve,
    publishedReserve: text(r.publishedReserve) ?? '',
  };
}

function parsePublication(body: unknown): AllowancePublication | undefined {
  const r = row(body);
  const allowanceId = text(r?.allowanceId);
  const allowanceVersion = integer(r?.allowanceVersion);
  const effectiveFrom = text(r?.effectiveFrom);
  const reserveWarnings = list(r?.reserveWarnings, parseWarning);
  if (
    r === undefined ||
    allowanceId === undefined ||
    allowanceVersion === undefined ||
    effectiveFrom === undefined ||
    reserveWarnings === undefined
  ) {
    return undefined;
  }
  return {
    allowanceId,
    allowanceVersion,
    unitCode: text(r.unitCode) ?? '',
    effectiveFrom,
    reserveWarnings,
  };
}

function post(body: unknown): RequestInit {
  return { method: 'POST', body: JSON.stringify(body) };
}

export function fetchAllowances(
  context: ConsoleRequest,
): Promise<ConsoleOutcome<AllowanceOverview>> {
  return request(context, ALLOWANCES, parseOverview);
}

export function publishAllowance(
  context: ConsoleRequest,
  draft: AllowanceDraft,
): Promise<ConsoleOutcome<AllowancePublication>> {
  return request(context, ALLOWANCES, parsePublication, post(draft));
}

export function retireAllowance(
  context: ConsoleRequest,
  allowanceId: string,
  reason: string,
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `${ALLOWANCES}/${encodeURIComponent(allowanceId)}/retire`,
    (body) => text(row(body)?.state),
    post({ reason }),
  );
}
