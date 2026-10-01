/**
 * A store's economics projection profile: generated from the marketplace's tariffs, submitted by one
 * Owner and approved by another before the price guardrail uses it. Amounts and rates of the profile
 * in force are decimal text; a draft's payload carries them as JSON numbers.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** One family of costs. */
export interface ProfileFamily {
  /** COMMISSION, FULFILLMENT_DELIVERY, STORAGE, PROMOTION, OTHER_VARIABLE, RETURN_LOSS, ADVERTISING or VARIABLE_TAX. */
  readonly familyCode: string;
  /** REQUIRED or VERIFIED_NOT_APPLICABLE. */
  readonly applicability: string;
  readonly evidence: string;
}

/** One cost of a family: a fixed amount, a rate of the price, or both. */
export interface ProfileComponent {
  readonly componentCode: string;
  readonly familyCode: string;
  /** FIXED, PERCENTAGE or FIXED_PLUS_PERCENTAGE. */
  readonly kind: string;
  readonly fixedAmount: string | null;
  /** A share of the price, e.g. `0.52`. */
  readonly rateValue: string | null;
  readonly evidence: string;
}

/** The profile in force. */
export interface EconomicsProfile {
  readonly profileId: string;
  readonly version: number;
  readonly fulfillmentModeCode: string;
  readonly currencyCode: string;
  readonly effectiveFrom: string;
  readonly verificationState: string;
  readonly verifiedAt: string;
  readonly verificationExpiresAt: string | null;
  readonly evidence: string;
  readonly minimumSupportedPrice: string;
  readonly maximumSupportedPrice: string;
  readonly families: readonly ProfileFamily[];
  readonly components: readonly ProfileComponent[];
}

/** One draft. */
export interface ProfileDraft {
  readonly draftId: string;
  readonly fulfillmentModeCode: string;
  readonly currencyCode: string;
  readonly families: readonly ProfileFamily[];
  readonly components: readonly ProfileComponent[];
  readonly minimumSupportedPrice: string | null;
  readonly maximumSupportedPrice: string | null;
  readonly evidence: string;
  readonly submittedAt: string;
  /** Whether the person viewing submitted it, and so cannot approve it. */
  readonly submittedByViewer: boolean;
  /** SUBMITTED, APPROVED, REJECTED or SUPERSEDED. */
  readonly state: string;
  readonly reviewedAt: string | null;
  readonly reviewedByViewer: boolean;
  readonly reviewNote: string | null;
  readonly profileId: string | null;
  readonly version: number;
}

/** A store's profile page. */
export interface EconomicsProfiles {
  readonly storeId: string;
  readonly generatedAt: string;
  /** The store's declared fulfilment modes in force. */
  readonly declaredModes: readonly string[];
  readonly profile: EconomicsProfile | null;
  /** Newest first. */
  readonly drafts: readonly ProfileDraft[];
}

/** Load the store's profile page. */
export function fetchEconomicsProfiles(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<EconomicsProfiles>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/economics-profiles`,
    parseEconomicsProfiles,
  );
}

/** Generate a draft from the store's newest tariffs and submit it. */
export function submitEconomicsProfileDraft(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<ProfileDraft>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/economics-profile-drafts`,
    parseDraft,
    { method: 'POST' },
  );
}

/** Approve another Owner's draft. */
export function approveEconomicsProfileDraft(
  context: ConsoleRequest,
  draftId: string,
  input: {
    readonly expectedVersion: number;
    readonly note: string | null;
    readonly verificationDays: number;
  },
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `/api/v1/console/economics-profile-drafts/${encodeURIComponent(draftId)}/approval`,
    (body) => (isRecord(body) ? text(body.profileId) : undefined),
    { method: 'POST', body: JSON.stringify(input) },
  );
}

/** Withdraw one's own draft, or reject another Owner's. */
export function closeEconomicsProfileDraft(
  context: ConsoleRequest,
  draftId: string,
  input: { readonly expectedVersion: number; readonly reason: string },
): Promise<ConsoleOutcome<true>> {
  return request(
    context,
    `/api/v1/console/economics-profile-drafts/${encodeURIComponent(draftId)}/closure`,
    () => true,
    { method: 'POST', body: JSON.stringify(input) },
  );
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseEconomicsProfiles(body: unknown): EconomicsProfiles | undefined {
  if (!isRecord(body) || !Array.isArray(body.declaredModes) || !Array.isArray(body.drafts)) {
    return undefined;
  }
  const storeId = text(body.storeId);
  const generatedAt = text(body.generatedAt);
  if (storeId === undefined || generatedAt === undefined) return undefined;
  const declaredModes = body.declaredModes.filter(
    (mode): mode is string => typeof mode === 'string',
  );
  const profile = body.profile === null ? null : parseProfile(body.profile);
  const drafts = body.drafts.map(parseDraft);
  if (profile === undefined || drafts.some((draft) => draft === undefined)) return undefined;
  return {
    storeId,
    generatedAt,
    declaredModes,
    profile,
    drafts: drafts.filter((draft): draft is ProfileDraft => draft !== undefined),
  };
}

function parseProfile(value: unknown): EconomicsProfile | undefined {
  if (!isRecord(value) || !Array.isArray(value.families) || !Array.isArray(value.components)) {
    return undefined;
  }
  const profileId = text(value.profileId);
  const fulfillmentModeCode = text(value.fulfillmentModeCode);
  const currencyCode = text(value.currencyCode);
  const effectiveFrom = text(value.effectiveFrom);
  const verificationState = text(value.verificationState);
  const verifiedAt = text(value.verifiedAt);
  const evidence = text(value.evidence);
  const minimumSupportedPrice = text(value.minimumSupportedPrice);
  const maximumSupportedPrice = text(value.maximumSupportedPrice);
  const families = value.families.map(parseFamily);
  const components = value.components.map(parseComponent);
  if (
    profileId === undefined ||
    typeof value.version !== 'number' ||
    fulfillmentModeCode === undefined ||
    currencyCode === undefined ||
    effectiveFrom === undefined ||
    verificationState === undefined ||
    verifiedAt === undefined ||
    evidence === undefined ||
    minimumSupportedPrice === undefined ||
    maximumSupportedPrice === undefined ||
    families.some((family) => family === undefined) ||
    components.some((component) => component === undefined)
  ) {
    return undefined;
  }
  return {
    profileId,
    version: value.version,
    fulfillmentModeCode,
    currencyCode,
    effectiveFrom,
    verificationState,
    verifiedAt,
    verificationExpiresAt: text(value.verificationExpiresAt) ?? null,
    evidence,
    minimumSupportedPrice,
    maximumSupportedPrice,
    families: families.filter((family): family is ProfileFamily => family !== undefined),
    components: components.filter(
      (component): component is ProfileComponent => component !== undefined,
    ),
  };
}

function parseDraft(value: unknown): ProfileDraft | undefined {
  if (!isRecord(value) || !isRecord(value.payload)) return undefined;
  const payload = value.payload;
  const draftId = text(value.draftId);
  const fulfillmentModeCode = text(value.fulfillmentModeCode);
  const currencyCode = text(value.currencyCode);
  const evidence = text(value.evidence);
  const submittedAt = text(value.submittedAt);
  const state = text(value.state);
  const families = Array.isArray(payload.families) ? payload.families.map(parseFamily) : [];
  const components = Array.isArray(payload.components)
    ? payload.components.map(parseComponent)
    : [];
  if (
    draftId === undefined ||
    fulfillmentModeCode === undefined ||
    currencyCode === undefined ||
    evidence === undefined ||
    submittedAt === undefined ||
    state === undefined ||
    typeof value.submittedByViewer !== 'boolean' ||
    typeof value.reviewedByViewer !== 'boolean' ||
    typeof value.version !== 'number' ||
    families.some((family) => family === undefined) ||
    components.some((component) => component === undefined)
  ) {
    return undefined;
  }
  return {
    draftId,
    fulfillmentModeCode,
    currencyCode,
    families: families.filter((family): family is ProfileFamily => family !== undefined),
    components: components.filter(
      (component): component is ProfileComponent => component !== undefined,
    ),
    minimumSupportedPrice: numberText(payload.minimumSupportedPrice),
    maximumSupportedPrice: numberText(payload.maximumSupportedPrice),
    evidence,
    submittedAt,
    submittedByViewer: value.submittedByViewer,
    state,
    reviewedAt: text(value.reviewedAt) ?? null,
    reviewedByViewer: value.reviewedByViewer,
    reviewNote: text(value.reviewNote) ?? null,
    profileId: text(value.profileId) ?? null,
    version: value.version,
  };
}

function parseFamily(value: unknown): ProfileFamily | undefined {
  if (!isRecord(value)) return undefined;
  const familyCode = text(value.familyCode);
  const applicability = text(value.applicability);
  const evidence = text(value.evidence);
  return familyCode === undefined || applicability === undefined || evidence === undefined
    ? undefined
    : { familyCode, applicability, evidence };
}

function parseComponent(value: unknown): ProfileComponent | undefined {
  if (!isRecord(value)) return undefined;
  const componentCode = text(value.componentCode);
  const familyCode = text(value.familyCode);
  const kind = text(value.kind);
  const evidence = text(value.evidence);
  if (
    componentCode === undefined ||
    familyCode === undefined ||
    kind === undefined ||
    evidence === undefined
  ) {
    return undefined;
  }
  return {
    componentCode,
    familyCode,
    kind,
    fixedAmount: numberText(value.fixedAmount),
    rateValue: numberText(value.rateValue),
    evidence,
  };
}

/** A decimal as text, from either text or a JSON number. */
function numberText(value: unknown): string | null {
  if (typeof value === 'string' && value !== '') return value;
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  return null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}
