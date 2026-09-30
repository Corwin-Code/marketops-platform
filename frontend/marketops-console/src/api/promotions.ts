/**
 * A store's current Ozon promotions and what joining each would mean for each product.
 *
 * Amounts, boosts and margins are decimal text exactly as the backend computed them; margins are
 * ratios (0.1685 = 16.85 %). Anything the marketplace did not state is `null`, never zero.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** One product of a promotion, with the estimated margins at the promotion's prices. */
export interface PromotionItem {
  readonly listingVariantId: string;
  readonly title: string | null;
  readonly offerId: string | null;
  readonly size: string | null;
  readonly color: string | null;
  /** `CANDIDATE` (can join) or `PARTICIPANT` (takes part). */
  readonly membership: string;
  readonly observedAt: string;
  readonly currencyCode: string | null;
  readonly price: string | null;
  readonly actionPrice: string | null;
  readonly maxActionPrice: string | null;
  readonly recommendedActionPrice: string | null;
  readonly aboveRecommended: boolean | null;
  readonly currentBoost: string | null;
  readonly minBoost: string | null;
  readonly maxBoost: string | null;
  readonly priceForMinBoost: string | null;
  readonly priceForMaxBoost: string | null;
  readonly minStock: number | null;
  readonly recommendedStock: number | null;
  readonly stock: number | null;
  /** `AUTOMATIC` or `SELLER` for a participant. */
  readonly addMode: string | null;
  readonly quarantined: boolean | null;
  readonly buyerPriceNow: string | null;
  readonly marginNow: string | null;
  /** The margin at the price a participant has in the promotion. */
  readonly marginAtActionPrice: string | null;
  readonly marginAtMaxActionPrice: string | null;
  readonly marginAtRecommendedPrice: string | null;
  readonly marginAtMaxBoostPrice: string | null;
  readonly breakEvenPrice: string | null;
  /**
   * `JOIN_KEEPS_FLOOR`, `JOIN_BELOW_FLOOR`, `JOIN_LOSES` or `UNKNOWN`: a participant judged at its
   * action price, a candidate at the highest price the promotion allows.
   */
  readonly verdict: string;
  readonly missing: readonly string[];
}

/** One promotion as the newest snapshot described it. */
export interface Promotion {
  readonly promotionId: string;
  readonly nativePromotionKey: string;
  readonly observedAt: string;
  readonly title: string | null;
  readonly promotionKind: string | null;
  readonly description: string | null;
  readonly startsAt: string | null;
  readonly endsAt: string | null;
  readonly freezesAt: string | null;
  readonly candidateCount: number | null;
  readonly participantCount: number | null;
  readonly bannedCount: number | null;
  readonly participating: boolean | null;
  readonly voucher: boolean | null;
  readonly targeted: boolean | null;
  readonly discountKind: string | null;
  readonly discountValue: string | null;
  readonly items: readonly PromotionItem[];
}

/** The store's current promotions. */
export interface StorePromotions {
  readonly storeId: string;
  readonly generatedAt: string;
  /** The margin floor the verdicts compare with, as a ratio. */
  readonly minimumMarginRate: string | null;
  readonly promotions: readonly Promotion[];
}

/** Load the store's current promotions with the economics of each product. */
export function fetchStorePromotions(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<StorePromotions>> {
  return request(
    context,
    `/api/v1/console/diagnosis/stores/${encodeURIComponent(storeId)}/promotions`,
    parseStorePromotions,
  );
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseStorePromotions(body: unknown): StorePromotions | undefined {
  if (!isRecord(body) || !Array.isArray(body.promotions)) return undefined;
  const storeId = text(body.storeId);
  const generatedAt = text(body.generatedAt);
  if (storeId === undefined || generatedAt === undefined) return undefined;
  const promotions = body.promotions.map(parsePromotion);
  if (promotions.some((promotion) => promotion === undefined)) return undefined;
  return {
    storeId,
    generatedAt,
    minimumMarginRate: decimal(body.minimumMarginRate),
    promotions: promotions as Promotion[],
  };
}

function parsePromotion(value: unknown): Promotion | undefined {
  if (!isRecord(value) || !Array.isArray(value.items)) return undefined;
  const promotionId = text(value.promotionId);
  const nativePromotionKey = text(value.nativePromotionKey);
  const observedAt = text(value.observedAt);
  if (promotionId === undefined || nativePromotionKey === undefined || observedAt === undefined) {
    return undefined;
  }
  const items = value.items.map(parseItem);
  if (items.some((item) => item === undefined)) return undefined;
  return {
    promotionId,
    nativePromotionKey,
    observedAt,
    title: optionalText(value.title),
    promotionKind: optionalText(value.promotionKind),
    description: optionalText(value.description),
    startsAt: optionalText(value.startsAt),
    endsAt: optionalText(value.endsAt),
    freezesAt: optionalText(value.freezesAt),
    candidateCount: integer(value.candidateCount),
    participantCount: integer(value.participantCount),
    bannedCount: integer(value.bannedCount),
    participating: flag(value.participating),
    voucher: flag(value.voucher),
    targeted: flag(value.targeted),
    discountKind: optionalText(value.discountKind),
    discountValue: decimal(value.discountValue),
    items: items as PromotionItem[],
  };
}

function parseItem(value: unknown): PromotionItem | undefined {
  if (!isRecord(value)) return undefined;
  const listingVariantId = text(value.listingVariantId);
  const membership = text(value.membership);
  const observedAt = text(value.observedAt);
  const verdict = text(value.verdict);
  if (
    listingVariantId === undefined ||
    membership === undefined ||
    observedAt === undefined ||
    verdict === undefined
  ) {
    return undefined;
  }
  return {
    listingVariantId,
    title: optionalText(value.title),
    offerId: optionalText(value.offerId),
    size: optionalText(value.size),
    color: optionalText(value.color),
    membership,
    observedAt,
    currencyCode: optionalText(value.currencyCode),
    price: decimal(value.price),
    actionPrice: decimal(value.actionPrice),
    maxActionPrice: decimal(value.maxActionPrice),
    recommendedActionPrice: decimal(value.recommendedActionPrice),
    aboveRecommended: flag(value.aboveRecommended),
    currentBoost: decimal(value.currentBoost),
    minBoost: decimal(value.minBoost),
    maxBoost: decimal(value.maxBoost),
    priceForMinBoost: decimal(value.priceForMinBoost),
    priceForMaxBoost: decimal(value.priceForMaxBoost),
    minStock: integer(value.minStock),
    recommendedStock: integer(value.recommendedStock),
    stock: integer(value.stock),
    addMode: optionalText(value.addMode),
    quarantined: flag(value.quarantined),
    buyerPriceNow: decimal(value.buyerPriceNow),
    marginNow: decimal(value.marginNow),
    marginAtActionPrice: decimal(value.marginAtActionPrice),
    marginAtMaxActionPrice: decimal(value.marginAtMaxActionPrice),
    marginAtRecommendedPrice: decimal(value.marginAtRecommendedPrice),
    marginAtMaxBoostPrice: decimal(value.marginAtMaxBoostPrice),
    breakEvenPrice: decimal(value.breakEvenPrice),
    verdict,
    missing: Array.isArray(value.missing)
      ? value.missing.filter((entry): entry is string => typeof entry === 'string')
      : [],
  };
}

/** A decision a person took by hand in the seller back office about one product of a promotion. */
export interface PromotionDecision {
  readonly decisionId: string;
  readonly promotionId: string;
  readonly listingVariantId: string;
  /** `JOINED`, `SKIPPED` or `LEFT`. */
  readonly decision: string;
  /** The price set in the promotion, recorded only for `JOINED`. */
  readonly actionPrice: string | null;
  readonly currencyCode: string | null;
  readonly note: string | null;
  readonly decidedAt: string;
}

/** What one decision records. */
export interface PromotionDecisionInput {
  readonly listingVariantId: string;
  readonly decision: string;
  readonly actionPrice: string | null;
  readonly currencyCode: string | null;
  readonly note: string | null;
}

/** Load the newest decision on every product of every promotion of the store. */
export function fetchPromotionDecisions(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<readonly PromotionDecision[]>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/promotion-decisions`,
    parsePromotionDecisions,
  );
}

/** Record one decision; the platform itself joins and leaves nothing. */
export function recordPromotionDecision(
  context: ConsoleRequest,
  storeId: string,
  promotionId: string,
  input: PromotionDecisionInput,
): Promise<ConsoleOutcome<PromotionDecision>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/promotions/${encodeURIComponent(promotionId)}/decisions`,
    parsePromotionDecision,
    { method: 'POST', body: JSON.stringify(input) },
  );
}

/** Validate a list of decisions; anything that does not match the contract is `undefined`. */
export function parsePromotionDecisions(body: unknown): readonly PromotionDecision[] | undefined {
  if (!Array.isArray(body)) return undefined;
  const decisions = body.map(parsePromotionDecision);
  return decisions.some((decision) => decision === undefined)
    ? undefined
    : (decisions as PromotionDecision[]);
}

/** Validate one decision; anything that does not match the contract is `undefined`. */
export function parsePromotionDecision(value: unknown): PromotionDecision | undefined {
  if (!isRecord(value)) return undefined;
  const decisionId = text(value.decisionId);
  const promotionId = text(value.promotionId);
  const listingVariantId = text(value.listingVariantId);
  const decision = text(value.decision);
  const decidedAt = text(value.decidedAt);
  if (
    decisionId === undefined ||
    promotionId === undefined ||
    listingVariantId === undefined ||
    decision === undefined ||
    decidedAt === undefined
  ) {
    return undefined;
  }
  return {
    decisionId,
    promotionId,
    listingVariantId,
    decision,
    actionPrice: decimal(value.actionPrice),
    currencyCode: optionalText(value.currencyCode),
    note: optionalText(value.note),
    decidedAt,
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

function integer(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) ? value : null;
}

function flag(value: unknown): boolean | null {
  return typeof value === 'boolean' ? value : null;
}

/** A decimal kept as text: parsing it into a JavaScript number would round it. */
function decimal(value: unknown): string | null {
  if (typeof value === 'string') return value;
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : null;
}
