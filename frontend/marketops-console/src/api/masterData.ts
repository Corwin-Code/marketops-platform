/**
 * A store's listings against the internal catalogue: the mapping in force, the
 * proposals and conflicts still open, the unit cost the seller entered at the
 * marketplace and the internal purchase cost in force.
 *
 * Mapping decisions go through the mapping routes one candidate at a time;
 * adopting marketplace costs is one request for the whole selection, applied
 * all-or-nothing by the backend. Amounts are decimal text, exactly as stored.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** Where a listing stands in the mapping work. */
export type MappingState = 'MAPPED' | 'PROPOSED' | 'CONFLICT' | 'UNMATCHED';

/** Whether the seller's marketplace cost is the internal purchase cost in force. */
export type CostState = 'ADOPTED' | 'TO_ADOPT';

/** The mapping in force. */
export interface CurrentMapping {
  readonly productVariantId: string;
  readonly skuCode: string;
  readonly variantName: string;
  readonly productName: string;
  readonly variantStatus: string;
  readonly effectiveFrom: string;
}

/** An open proposal; `version` guards the decision. */
export interface ProposedMapping {
  readonly candidateId: string;
  readonly version: number;
  readonly productVariantId: string;
  readonly skuCode: string;
  readonly variantName: string;
  readonly productName: string;
  /** `BARCODE`, `NATIVE_SKU_KEY`, `MANUAL` … */
  readonly matchMethod: string;
  readonly confidence: string | null;
}

/** An open conflict. */
export interface OpenConflict {
  readonly conflictId: string;
  readonly version: number;
  /** `NO_CANDIDATE`, `DUPLICATE_BARCODE` … */
  readonly kind: string;
  readonly detail: string | null;
  readonly detectedAt: string;
}

/** The unit cost the seller entered at the marketplace (newest price observation). */
export interface SellerCost {
  readonly priceObservationId: string;
  readonly amount: string;
  readonly currencyCode: string | null;
  readonly observedAt: string;
}

/** The internal purchase cost in force. */
export interface PurchaseCost {
  readonly unitCost: string;
  readonly currencyCode: string | null;
  readonly effectiveFrom: string;
  /** `MARKETPLACE_RAW`, `MANUAL_ENTRY` or `INTERNAL_IMPORT`. */
  readonly sourceKind: string | null;
}

/** One listing variant against the internal catalogue. */
export interface MasterDataRow {
  readonly listingId: string;
  readonly listingVariantId: string;
  readonly nativeListingKey: string;
  readonly nativeSkuKey: string | null;
  readonly nativeItemKey: string | null;
  readonly nativeBarcode: string | null;
  readonly title: string | null;
  readonly state: MappingState;
  readonly mapping: CurrentMapping | null;
  readonly proposals: readonly ProposedMapping[];
  readonly conflicts: readonly OpenConflict[];
  readonly sellerCost: SellerCost | null;
  readonly purchaseCost: PurchaseCost | null;
  readonly costState: CostState | null;
}

/** Counts across the store's listings. */
export interface MasterDataSummary {
  readonly listings: number;
  readonly mapped: number;
  readonly proposed: number;
  readonly conflicts: number;
  readonly unmatched: number;
  readonly withSellerCost: number;
  readonly costAdopted: number;
  readonly costToAdopt: number;
}

/** The whole review. */
export interface MasterData {
  readonly storeId: string;
  readonly generatedAt: string;
  /** Whether the viewer may see and adopt costs for this store. */
  readonly costsVisible: boolean;
  readonly summary: MasterDataSummary;
  readonly rows: readonly MasterDataRow[];
}

/** What one matcher run examined. */
export interface ProposalRun {
  readonly listingVariantsExamined: number;
}

/** What one adoption did. */
export interface AdoptionResult {
  readonly adopted: number;
  readonly unchanged: number;
}

/** Load the review of one store. */
export function fetchMasterData(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<MasterData>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/master-data`,
    parseMasterData,
  );
}

/** Run the matcher over the store's unmapped listing variants. */
export function runProposals(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<ProposalRun>> {
  return request(
    context,
    `/api/v1/console/mapping/stores/${encodeURIComponent(storeId)}/proposals?limit=500`,
    (body) => {
      if (!isRecord(body)) return undefined;
      const examined = integer(body.listingVariantsExamined);
      return examined === undefined ? undefined : { listingVariantsExamined: examined };
    },
    { method: 'POST' },
  );
}

/** Confirm one proposal, opening a mapping from now on. */
export function confirmProposal(
  context: ConsoleRequest,
  candidateId: string,
  reason: string,
  expectedVersion: number,
): Promise<ConsoleOutcome<true>> {
  return request(
    context,
    `/api/v1/console/mapping/candidates/${encodeURIComponent(candidateId)}/confirmation`,
    (body) => (isRecord(body) ? true : undefined),
    { method: 'POST', body: JSON.stringify({ reason, expectedVersion }) },
  );
}

/** Adopt the seller's marketplace cost of the given listings, all or none. */
export function adoptMarketplaceCosts(
  context: ConsoleRequest,
  storeId: string,
  items: readonly { readonly listingVariantId: string; readonly priceObservationId: string }[],
  reason: string,
): Promise<ConsoleOutcome<AdoptionResult>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/marketplace-costs/adoption`,
    (body) => {
      if (!isRecord(body) || !Array.isArray(body.adopted)) return undefined;
      const unchanged = integer(body.unchanged);
      return unchanged === undefined ? undefined : { adopted: body.adopted.length, unchanged };
    },
    { method: 'POST', body: JSON.stringify({ items, reason }) },
  );
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseMasterData(body: unknown): MasterData | undefined {
  if (!isRecord(body)) return undefined;
  const storeId = text(body.storeId);
  const generatedAt = text(body.generatedAt);
  const summary = parseSummary(body.summary);
  if (storeId === undefined || generatedAt === undefined || summary === undefined) return undefined;
  if (typeof body.costsVisible !== 'boolean' || !Array.isArray(body.rows)) return undefined;
  const rows = body.rows.map(parseRow);
  if (rows.some((row) => row === undefined)) return undefined;
  return {
    storeId,
    generatedAt,
    costsVisible: body.costsVisible,
    summary,
    rows: rows as MasterDataRow[],
  };
}

function parseSummary(value: unknown): MasterDataSummary | undefined {
  if (!isRecord(value)) return undefined;
  const keys = [
    'listings',
    'mapped',
    'proposed',
    'conflicts',
    'unmatched',
    'withSellerCost',
    'costAdopted',
    'costToAdopt',
  ] as const;
  const read: Partial<Record<(typeof keys)[number], number>> = {};
  for (const key of keys) {
    const count = integer(value[key]);
    if (count === undefined) return undefined;
    read[key] = count;
  }
  return read as MasterDataSummary;
}

const STATES: readonly MappingState[] = ['MAPPED', 'PROPOSED', 'CONFLICT', 'UNMATCHED'];

function parseRow(value: unknown): MasterDataRow | undefined {
  if (!isRecord(value)) return undefined;
  const listingId = text(value.listingId);
  const listingVariantId = text(value.listingVariantId);
  const nativeListingKey = text(value.nativeListingKey);
  const state = STATES.find((known) => known === value.state);
  if (
    listingId === undefined ||
    listingVariantId === undefined ||
    nativeListingKey === undefined ||
    state === undefined
  ) {
    return undefined;
  }
  const costState =
    value.costState === 'ADOPTED' || value.costState === 'TO_ADOPT' ? value.costState : null;
  return {
    listingId,
    listingVariantId,
    nativeListingKey,
    nativeSkuKey: optionalText(value.nativeSkuKey),
    nativeItemKey: optionalText(value.nativeItemKey),
    nativeBarcode: optionalText(value.nativeBarcode),
    title: optionalText(value.title),
    state,
    mapping: parseMapping(value.mapping),
    proposals: Array.isArray(value.proposals)
      ? value.proposals
          .map(parseProposal)
          .filter((item): item is ProposedMapping => item !== undefined)
      : [],
    conflicts: Array.isArray(value.conflicts)
      ? value.conflicts
          .map(parseConflict)
          .filter((item): item is OpenConflict => item !== undefined)
      : [],
    sellerCost: parseSellerCost(value.sellerCost),
    purchaseCost: parsePurchaseCost(value.purchaseCost),
    costState,
  };
}

function parseMapping(value: unknown): CurrentMapping | null {
  if (!isRecord(value)) return null;
  const productVariantId = text(value.productVariantId);
  const skuCode = text(value.skuCode);
  const effectiveFrom = text(value.effectiveFrom);
  if (productVariantId === undefined || skuCode === undefined || effectiveFrom === undefined) {
    return null;
  }
  return {
    productVariantId,
    skuCode,
    variantName: text(value.variantName) ?? skuCode,
    productName: text(value.productName) ?? '',
    variantStatus: text(value.variantStatus) ?? '',
    effectiveFrom,
  };
}

function parseProposal(value: unknown): ProposedMapping | undefined {
  if (!isRecord(value)) return undefined;
  const candidateId = text(value.candidateId);
  const version = integer(value.version);
  const productVariantId = text(value.productVariantId);
  const skuCode = text(value.skuCode);
  const matchMethod = text(value.matchMethod);
  if (
    candidateId === undefined ||
    version === undefined ||
    productVariantId === undefined ||
    skuCode === undefined ||
    matchMethod === undefined
  ) {
    return undefined;
  }
  return {
    candidateId,
    version,
    productVariantId,
    skuCode,
    variantName: text(value.variantName) ?? skuCode,
    productName: text(value.productName) ?? '',
    matchMethod,
    confidence: decimal(value.confidence),
  };
}

function parseConflict(value: unknown): OpenConflict | undefined {
  if (!isRecord(value)) return undefined;
  const conflictId = text(value.conflictId);
  const version = integer(value.version);
  const kind = text(value.kind);
  const detectedAt = text(value.detectedAt);
  if (
    conflictId === undefined ||
    version === undefined ||
    kind === undefined ||
    detectedAt === undefined
  ) {
    return undefined;
  }
  return { conflictId, version, kind, detail: optionalText(value.detail), detectedAt };
}

function parseSellerCost(value: unknown): SellerCost | null {
  if (!isRecord(value)) return null;
  const priceObservationId = text(value.priceObservationId);
  const amount = decimal(value.amount);
  const observedAt = text(value.observedAt);
  if (priceObservationId === undefined || amount === null || observedAt === undefined) return null;
  return {
    priceObservationId,
    amount,
    currencyCode: optionalText(value.currencyCode),
    observedAt,
  };
}

function parsePurchaseCost(value: unknown): PurchaseCost | null {
  if (!isRecord(value)) return null;
  const unitCost = decimal(value.unitCost);
  const effectiveFrom = text(value.effectiveFrom);
  if (unitCost === null || effectiveFrom === undefined) return null;
  return {
    unitCost,
    currencyCode: optionalText(value.currencyCode),
    effectiveFrom,
    sourceKind: optionalText(value.sourceKind),
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

function integer(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) ? value : undefined;
}

/** A decimal kept as text: parsing it into a number would round it. */
function decimal(value: unknown): string | null {
  if (typeof value === 'string') return value;
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : null;
}
