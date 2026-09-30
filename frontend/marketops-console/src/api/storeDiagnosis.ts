/**
 * The store diagnosis: every observed listing of a store with the newest signal
 * of each kind the marketplace has given about it.
 *
 * Signals nobody observed are `null`, never zero. Amounts and ratings are
 * decimal text, exactly as stored, so nothing is rounded on the way to the
 * screen. Price competitiveness and search demand are the marketplace's own
 * analytics (confidence C): they explain a diagnosis and never drive a price
 * change.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** Whether a buyer can see and buy the listing. */
export interface DiagnosisVisibility {
  /** `YES`, `NO` or `UNKNOWN`. */
  readonly sellable: string;
  readonly nativeStatus: string | null;
  readonly blockedReason: string | null;
  readonly observedAt: string;
}

/** Units summed over the fulfillment modes of the newest stock snapshot. */
export interface DiagnosisStock {
  readonly available: number | null;
  readonly reserved: number | null;
  readonly fulfillmentModes: readonly string[];
  readonly observedAt: string;
}

/** The newest price and how competitive the marketplace says it is. */
export interface DiagnosisPrice {
  readonly currencyCode: string | null;
  readonly listPrice: string | null;
  readonly sellingPrice: string | null;
  readonly discountPrice: string | null;
  /** The marketplace's own word for the class, e.g. `RED`. */
  readonly indexNative: string | null;
  readonly platformCompetitorMinPrice: string | null;
  readonly platformCompetitorCurrencyCode: string | null;
  readonly externalCompetitorMinPrice: string | null;
  readonly externalCompetitorCurrencyCode: string | null;
  /** Ratio above the lowest competitor price on the same marketplace (0.5 = 50 % dearer). */
  readonly premiumOverPlatformCompetitor: string | null;
  readonly tariffs: DiagnosisTariffs;
  readonly observedAt: string;
}

/** The tariffs the marketplace stated with the price, as decimal text; each `null` when not stated. */
export interface DiagnosisTariffs {
  /** FBS sales commission in percent. */
  readonly salesCommissionPercentFbs: string | null;
  /** The highest stated FBS processing, trunk and last-mile tariffs summed. */
  readonly fbsLogisticsMax: string | null;
  readonly acquiringMax: string | null;
  /** VAT rate contained in the price (0.05 = 5 %). */
  readonly vatRate: string | null;
}

/** The marketplace's content rating, 0 to 100. */
export interface DiagnosisContent {
  readonly rating: string | null;
  readonly observedAt: string;
}

/** One search term buyers used to find the listing. */
export interface DiagnosisSearchTerm {
  readonly term: string;
  readonly searchUsers: number;
  /** Orders the marketplace attributes to the term; `null` when not stated. */
  readonly orderedCount: number | null;
}

/** Buyers who searched for the listing in the search period, and the terms they used. */
export interface DiagnosisSearch {
  /** `null` when only terms were observed. */
  readonly searchUsers: number | null;
  /** Sales the marketplace attributes to searches, decimal text. */
  readonly revenue: string | null;
  readonly revenueCurrencyCode: string | null;
  /** The most searched first. */
  readonly terms: readonly DiagnosisSearchTerm[];
}

/** Units ordered on the days of the window this listing has a record for. */
export interface DiagnosisOrders {
  readonly orderedUnits: number;
  readonly daysWithRecords: number;
}

/** One listing variant and its newest signals. */
export interface DiagnosisProduct {
  readonly listingId: string;
  readonly variantId: string;
  readonly nativeListingKey: string;
  readonly nativeSkuKey: string | null;
  readonly title: string | null;
  readonly visibility: DiagnosisVisibility | null;
  readonly stock: DiagnosisStock | null;
  readonly price: DiagnosisPrice | null;
  readonly content: DiagnosisContent | null;
  readonly search: DiagnosisSearch | null;
  readonly orders: DiagnosisOrders | null;
}

/** Counts across the store's listings. */
export interface DiagnosisSummary {
  readonly products: number;
  readonly notSellable: number;
  readonly sellabilityUnknown: number;
  readonly withoutStock: number;
  readonly priceIndexRed: number;
  readonly priceIndexYellow: number;
  readonly withOrders: number;
  /** Listings buyers searched for in the search period. */
  readonly withSearchDemand: number;
  /** Of those, the ones with no ordered unit in the order window. */
  readonly searchDemandWithoutOrders: number;
  readonly rated: number;
  readonly averageContentRating: string | null;
}

/** The days the order sums cover, ending at the store's latest traffic day. */
export interface DiagnosisOrdersWindow {
  readonly from: string;
  readonly to: string;
  readonly daysCovered: number;
  readonly days: number;
}

/** The period search demand is reported for: inclusive start, exclusive end. */
export interface DiagnosisSearchPeriod {
  readonly from: string;
  readonly to: string;
}

/** The whole diagnosis of one store. */
export interface StoreDiagnosis {
  readonly storeId: string;
  readonly generatedAt: string;
  readonly summary: DiagnosisSummary;
  readonly ordersWindow: DiagnosisOrdersWindow | null;
  readonly searchPeriod: DiagnosisSearchPeriod | null;
  readonly products: readonly DiagnosisProduct[];
}

/** Load the diagnosis of one store. */
export function fetchStoreDiagnosis(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<StoreDiagnosis>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/diagnosis`,
    parseStoreDiagnosis,
  );
}

/** The card's description, seller-written Russian text. */
export interface ListingDescription {
  /** `null` when it was too long to keep. */
  readonly text: string | null;
  readonly length: number;
  /** When it was first seen as it is. */
  readonly since: string;
}

/** One condition of a content rating group. */
export interface RatingCondition {
  /** Ozon's own key, e.g. `text_annotation_500_chars`. */
  readonly conditionKey: string;
  /** Ozon's description, Russian. */
  readonly text: string | null;
  /** `null` when Ozon did not say. */
  readonly met: boolean | null;
  readonly points: string | null;
}

/** An attribute Ozon names to fill to raise a group, and whether the card now carries it. */
export interface ImproveAttribute {
  readonly attributeKey: string;
  /** Ozon's name, Russian. */
  readonly name: string | null;
  readonly filled: boolean;
}

/** One group of the content rating; rating and weight are decimal text. */
export interface RatingGroup {
  readonly groupKey: string;
  readonly groupName: string | null;
  readonly rating: string | null;
  /** Share of the content rating in percent. */
  readonly weight: string | null;
  readonly improveAtLeast: number | null;
  readonly conditions: readonly RatingCondition[];
  readonly improveAttributes: readonly ImproveAttribute[];
  readonly since: string;
}

/** What a listing card says and what its content rating finds missing. */
export interface ListingContent {
  readonly variantId: string;
  /** `null` when no catalog snapshot recorded the card yet. */
  readonly catalogObservedAt: string | null;
  readonly imageCount: number | null;
  readonly attributeCount: number;
  readonly description: ListingDescription | null;
  /** The rich content, by length only. */
  readonly richContent: { readonly length: number; readonly since: string } | null;
  readonly ratingGroups: readonly RatingGroup[];
}

/** Load what one listing card says and what its content rating finds missing. */
export function fetchListingContent(
  context: ConsoleRequest,
  storeId: string,
  variantId: string,
): Promise<ConsoleOutcome<ListingContent>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/listing-variants/${encodeURIComponent(variantId)}/content`,
    parseListingContent,
  );
}

/** Validate a listing content answer; anything that does not match the contract is `undefined`. */
export function parseListingContent(body: unknown): ListingContent | undefined {
  if (!isRecord(body)) return undefined;
  const variantId = text(body.variantId);
  const attributeCount = integer(body.attributeCount);
  if (
    variantId === undefined ||
    attributeCount === undefined ||
    !Array.isArray(body.ratingGroups)
  ) {
    return undefined;
  }
  const groups = body.ratingGroups.map(parseRatingGroup);
  if (groups.some((group) => group === undefined)) return undefined;
  const description = isRecord(body.description) ? body.description : undefined;
  const descriptionLength = description === undefined ? undefined : integer(description.length);
  const descriptionSince = description === undefined ? undefined : text(description.since);
  const rich = isRecord(body.richContent) ? body.richContent : undefined;
  const richLength = rich === undefined ? undefined : integer(rich.length);
  const richSince = rich === undefined ? undefined : text(rich.since);
  return {
    variantId,
    catalogObservedAt: optionalText(body.catalogObservedAt),
    imageCount: integer(body.imageCount) ?? null,
    attributeCount,
    description:
      description === undefined || descriptionLength === undefined || descriptionSince === undefined
        ? null
        : {
            text: optionalText(description.text),
            length: descriptionLength,
            since: descriptionSince,
          },
    richContent:
      richLength === undefined || richSince === undefined
        ? null
        : { length: richLength, since: richSince },
    ratingGroups: groups as RatingGroup[],
  };
}

function parseRatingGroup(value: unknown): RatingGroup | undefined {
  if (!isRecord(value)) return undefined;
  const groupKey = text(value.groupKey);
  const since = text(value.since);
  if (groupKey === undefined || since === undefined) return undefined;
  const conditions = Array.isArray(value.conditions)
    ? value.conditions.flatMap((condition: unknown): RatingCondition[] => {
        if (!isRecord(condition)) return [];
        const conditionKey = text(condition.conditionKey);
        if (conditionKey === undefined) return [];
        return [
          {
            conditionKey,
            text: optionalText(condition.text),
            met: typeof condition.met === 'boolean' ? condition.met : null,
            points: decimal(condition.points),
          },
        ];
      })
    : [];
  const improveAttributes = Array.isArray(value.improveAttributes)
    ? value.improveAttributes.flatMap((attribute: unknown): ImproveAttribute[] => {
        if (!isRecord(attribute)) return [];
        const attributeKey = text(attribute.attributeKey);
        if (attributeKey === undefined) return [];
        return [
          {
            attributeKey,
            name: optionalText(attribute.name),
            filled: attribute.filled === true,
          },
        ];
      })
    : [];
  return {
    groupKey,
    groupName: optionalText(value.groupName),
    rating: decimal(value.rating),
    weight: decimal(value.weight),
    improveAtLeast: integer(value.improveAtLeast) ?? null,
    conditions,
    improveAttributes,
    since,
  };
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseStoreDiagnosis(body: unknown): StoreDiagnosis | undefined {
  if (!isRecord(body)) return undefined;
  const storeId = text(body.storeId);
  const generatedAt = text(body.generatedAt);
  const summary = parseSummary(body.summary);
  if (storeId === undefined || generatedAt === undefined || summary === undefined) return undefined;
  if (!Array.isArray(body.products)) return undefined;
  const products = body.products.map(parseProduct);
  if (products.some((product) => product === undefined)) return undefined;
  return {
    storeId,
    generatedAt,
    summary,
    ordersWindow: parseOrdersWindow(body.ordersWindow) ?? null,
    searchPeriod: parseSearchPeriod(body.searchPeriod),
    products: products as DiagnosisProduct[],
  };
}

function parseSummary(value: unknown): DiagnosisSummary | undefined {
  if (!isRecord(value)) return undefined;
  const counts = [
    'products',
    'notSellable',
    'sellabilityUnknown',
    'withoutStock',
    'priceIndexRed',
    'priceIndexYellow',
    'withOrders',
    'withSearchDemand',
    'searchDemandWithoutOrders',
    'rated',
  ] as const;
  const read: Partial<Record<(typeof counts)[number], number>> = {};
  for (const key of counts) {
    const count = integer(value[key]);
    if (count === undefined) return undefined;
    read[key] = count;
  }
  return {
    ...(read as Record<(typeof counts)[number], number>),
    averageContentRating: decimal(value.averageContentRating),
  };
}

function parseOrdersWindow(value: unknown): DiagnosisOrdersWindow | undefined {
  if (!isRecord(value)) return undefined;
  const from = text(value.from);
  const to = text(value.to);
  const daysCovered = integer(value.daysCovered);
  const days = integer(value.days);
  if (from === undefined || to === undefined || daysCovered === undefined || days === undefined) {
    return undefined;
  }
  return { from, to, daysCovered, days };
}

function parseSearchPeriod(value: unknown): DiagnosisSearchPeriod | null {
  if (!isRecord(value)) return null;
  const from = text(value.from);
  const to = text(value.to);
  return from === undefined || to === undefined ? null : { from, to };
}

function parseProduct(value: unknown): DiagnosisProduct | undefined {
  if (!isRecord(value)) return undefined;
  const listingId = text(value.listingId);
  const variantId = text(value.variantId);
  const nativeListingKey = text(value.nativeListingKey);
  if (listingId === undefined || variantId === undefined || nativeListingKey === undefined) {
    return undefined;
  }
  return {
    listingId,
    variantId,
    nativeListingKey,
    nativeSkuKey: optionalText(value.nativeSkuKey),
    title: optionalText(value.title),
    visibility: parseVisibility(value.visibility),
    stock: parseStock(value.stock),
    price: parsePrice(value.price),
    content: parseContent(value.content),
    search: parseSearch(value.search),
    orders: parseOrders(value.orders),
  };
}

function parseVisibility(value: unknown): DiagnosisVisibility | null {
  if (!isRecord(value)) return null;
  const sellable = text(value.sellable);
  const observedAt = text(value.observedAt);
  if (sellable === undefined || observedAt === undefined) return null;
  return {
    sellable,
    nativeStatus: optionalText(value.nativeStatus),
    blockedReason: optionalText(value.blockedReason),
    observedAt,
  };
}

function parseStock(value: unknown): DiagnosisStock | null {
  if (!isRecord(value)) return null;
  const observedAt = text(value.observedAt);
  if (observedAt === undefined) return null;
  return {
    available: integer(value.available) ?? null,
    reserved: integer(value.reserved) ?? null,
    fulfillmentModes: Array.isArray(value.fulfillmentModes)
      ? value.fulfillmentModes.filter((mode): mode is string => typeof mode === 'string')
      : [],
    observedAt,
  };
}

function parsePrice(value: unknown): DiagnosisPrice | null {
  if (!isRecord(value)) return null;
  const observedAt = text(value.observedAt);
  if (observedAt === undefined) return null;
  return {
    currencyCode: optionalText(value.currencyCode),
    listPrice: decimal(value.listPrice),
    sellingPrice: decimal(value.sellingPrice),
    discountPrice: decimal(value.discountPrice),
    indexNative: optionalText(value.indexNative),
    platformCompetitorMinPrice: decimal(value.platformCompetitorMinPrice),
    platformCompetitorCurrencyCode: optionalText(value.platformCompetitorCurrencyCode),
    externalCompetitorMinPrice: decimal(value.externalCompetitorMinPrice),
    externalCompetitorCurrencyCode: optionalText(value.externalCompetitorCurrencyCode),
    premiumOverPlatformCompetitor: decimal(value.premiumOverPlatformCompetitor),
    tariffs: parseTariffs(value.tariffs),
    observedAt,
  };
}

function parseTariffs(value: unknown): DiagnosisTariffs {
  const tariffs = isRecord(value) ? value : {};
  return {
    salesCommissionPercentFbs: decimal(tariffs.salesCommissionPercentFbs),
    fbsLogisticsMax: decimal(tariffs.fbsLogisticsMax),
    acquiringMax: decimal(tariffs.acquiringMax),
    vatRate: decimal(tariffs.vatRate),
  };
}

function parseContent(value: unknown): DiagnosisContent | null {
  if (!isRecord(value)) return null;
  const observedAt = text(value.observedAt);
  if (observedAt === undefined) return null;
  return { rating: decimal(value.rating), observedAt };
}

function parseSearch(value: unknown): DiagnosisSearch | null {
  if (!isRecord(value)) return null;
  const terms = Array.isArray(value.terms)
    ? value.terms.map(parseSearchTerm).filter((term): term is DiagnosisSearchTerm => term !== null)
    : [];
  return {
    searchUsers: integer(value.searchUsers) ?? null,
    revenue: decimal(value.revenue),
    revenueCurrencyCode: optionalText(value.revenueCurrencyCode),
    terms,
  };
}

function parseSearchTerm(value: unknown): DiagnosisSearchTerm | null {
  if (!isRecord(value)) return null;
  const term = text(value.term);
  const searchUsers = integer(value.searchUsers);
  if (term === undefined || searchUsers === undefined) return null;
  return { term, searchUsers, orderedCount: integer(value.orderedCount) ?? null };
}

function parseOrders(value: unknown): DiagnosisOrders | null {
  if (!isRecord(value)) return null;
  const orderedUnits = integer(value.orderedUnits);
  const daysWithRecords = integer(value.daysWithRecords);
  if (orderedUnits === undefined || daysWithRecords === undefined) return null;
  return { orderedUnits, daysWithRecords };
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

/**
 * A decimal the backend sent, kept as text. Parsing one into a JavaScript
 * number would silently round it, and a rounded amount is a different amount.
 */
function decimal(value: unknown): string | null {
  if (typeof value === 'string') return value;
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : null;
}
