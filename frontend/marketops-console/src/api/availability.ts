import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/**
 * Every request of the stockout and availability screens, and the parsing
 * between a backend answer and the screen.
 *
 * Nothing here writes to a marketplace: the writes change somebody's work (a
 * case, an acceptance request, an inbound claim, a policy version), never a
 * platform. Decimals stay text all the way to the screen, and a member whose
 * lane or evidence state is missing is dropped rather than defaulted, because a
 * card that rendered an unknown state as a confirmed one would be exactly the
 * false safety the surface exists to prevent.
 *
 * "May do" (`allowedActions`, `blockedActions`, `canAttest`, `canManage`) is
 * the server's advice for the person asking; every write is authorized again.
 */

const BASE = '/api/v1/console/availability';

// ------------------------------------------------------------------ risk cards

/** One visible reason a card sits where it does in the availability queue. */
export interface AvailabilityRankFactor {
  readonly factorCode: string;
  readonly value: string | null;
  readonly weight: string | null;
  readonly contribution: string | null;
  readonly displayNote: string;
}

/** One demand window and how much of it could actually be observed. */
export interface AvailabilityDemandWindow {
  readonly windowCode: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly completedUnits: number | null;
  readonly dailyRate: string | null;
  readonly observedDays: string | null;
  readonly coverageRatio: string | null;
  readonly sampleSufficient: boolean;
  readonly censored: boolean;
  readonly censoringReason: string | null;
  readonly outlierShare: string | null;
  readonly eligibility: string;
}

/** One independently governed child risk. */
export interface AvailabilityChild {
  readonly id: string;
  readonly childKind: 'CHANNEL' | 'COMPANY';
  readonly platformCode: string | null;
  readonly storeId: string | null;
  readonly platformListingVariantId: string | null;
  readonly fulfillmentModeCode: string | null;
  readonly lane: string;
  readonly evidenceState: string;
  readonly confidenceState: string;
  readonly causeCode: string;
  readonly availableUnits: number | null;
  readonly dailyDemandRate: string | null;
  readonly daysOfCover: string | null;
  readonly coverageHorizonDays: number | null;
  readonly projectedStockoutAt: string | null;
  readonly profitLane: string;
  readonly profitAtRiskAmount: string | null;
  readonly profitAtRiskCurrency: string | null;
  readonly demandSelectionReason: string;
  readonly conservativeProofTerms: readonly string[];
  readonly blockerCodes: readonly string[];
  readonly rankFactors: readonly AvailabilityRankFactor[];
  readonly demandWindows: readonly AvailabilityDemandWindow[];
  readonly calculatedAt: string;
}

/** One grouped Internal Variant card. */
export interface AvailabilityCard {
  readonly id: string;
  readonly productVariantId: string;
  readonly skuCode: string;
  readonly displayName: string;
  readonly lane: string;
  readonly triggeringChildId: string | null;
  readonly rankScore: string | null;
  readonly policyVersionDigest: string;
  readonly asOf: string;
  readonly calculatedAt: string;
  readonly children: readonly AvailabilityChild[];
}

/** One page of anything, with the matching total when the backend counted it. */
export interface Page<T> {
  readonly items: readonly T[];
  /** Matching rows in total; undefined when the backend did not count them. */
  readonly total: number | undefined;
  readonly offset: number;
  readonly limit: number;
}

/** An internal variant a person may name, as a picker shows it. */
export interface VariantOption {
  readonly productVariantId: string;
  readonly skuCode: string;
  readonly displayName: string;
  readonly colorLabel: string | null;
  readonly sizeLabel: string | null;
  readonly status: string | null;
}

/** Which of the person's grants narrows a variant picker. */
export type VariantPurpose = 'VIEW' | 'INBOUND_ATTEST' | 'SUPPLY_POLICY_MANAGE';

// ------------------------------------------------------------------ cases

/** The product and channel a case is about. */
export interface CaseSubject {
  readonly productVariantId: string;
  readonly skuCode: string | null;
  readonly displayName: string | null;
  readonly childKind: string | null;
  readonly platformCode: string | null;
  readonly fulfillmentModeCode: string | null;
  readonly storeId: string | null;
  readonly storeCode: string | null;
  readonly storeName: string | null;
  /** The marketplace's own key for the listing variant. */
  readonly platformSkuKey: string | null;
}

/** The acceptance request or acceptance currently occupying a case. */
export interface OpenException {
  readonly id: string;
  readonly state: string;
  readonly requiredAuthority: string;
  readonly expiresAt: string | null;
}

/** One action the server does not offer the viewer now, and its stable reason. */
export interface BlockedAction {
  readonly action: string;
  readonly reason: string;
}

/** One accountable availability case, as the console sees it. */
export interface AvailabilityCase {
  readonly id: string;
  readonly cardId: string;
  readonly childId: string;
  readonly causeCode: string;
  readonly causeKey: string;
  /** The lane that activated it. */
  readonly severity: string;
  readonly state: string;
  readonly accountableRoleCode: string;
  readonly assigneeUserId: string | null;
  readonly actionDueAt: string;
  readonly originalActionDueAt: string | null;
  /** When an acceptance paused the action clock, or null while it runs. */
  readonly actionSlaPausedAt: string | null;
  /** What was left of the action clock when it paused, in milliseconds. */
  readonly actionSlaRemainingMillis: number | null;
  readonly outcomeDueAt: string | null;
  readonly reopenCount: number;
  readonly escalationLevel: number;
  readonly firstActivatedAt: string;
  readonly lastEvidenceAt: string | null;
  readonly improvementFirstSeenAt: string | null;
  /** Absent in the answer of a write; present on every read. */
  readonly subject: CaseSubject | undefined;
  readonly openException: OpenException | null;
  readonly allowedActions: readonly string[];
  readonly blockedActions: readonly BlockedAction[];
}

/** Which cases a list asks for. */
export type CaseView = 'LIVE' | 'ESCALATED' | 'EXCEPTION_PENDING' | 'ALL';

/** One entry in a case's history, with the person named. */
export interface CaseJournalEntry {
  readonly sequenceNo: number;
  readonly eventKind: string;
  readonly fromState: string | null;
  readonly toState: string | null;
  readonly actionKind: string | null;
  readonly verificationKind: string | null;
  readonly verificationOutcome: string | null;
  readonly actorUserId: string | null;
  /** Staff name, or null when nothing human did it. */
  readonly actorName: string | null;
  readonly actorRoleCode: string | null;
  readonly reason: string;
  readonly evidenceReference: string | null;
  readonly observedAt: string | null;
  readonly occurredAt: string;
}

/** One bounded, governed acceptance of a calculated risk, with every recorded field. */
export interface AcceptedException {
  readonly id: string;
  readonly caseId: string | null;
  readonly childId: string | null;
  readonly causeCode: string;
  readonly scopeKind: string | null;
  readonly scopeReference: string | null;
  readonly reasonCode: string;
  readonly rationale: string | null;
  readonly expectedConsequence: string | null;
  readonly consequenceAmount: string | null;
  readonly consequenceCurrency: string | null;
  readonly evidenceReference: string | null;
  readonly requestedByUserId: string | null;
  readonly requestedByName: string | null;
  readonly requestedAt: string | null;
  readonly decisionOwnerRoleCode: string | null;
  readonly requiredAuthority: string;
  readonly state: string;
  readonly effectiveFrom: string | null;
  readonly expiresAt: string | null;
  readonly reviewAt: string | null;
  readonly invalidatedAt: string | null;
  readonly invalidationReason: string | null;
  readonly occurrenceCount: number | null;
  readonly acceptedSeverity: string | null;
  readonly acceptedProfitAtRiskAmount: string | null;
  readonly acceptedProfitAtRiskCurrency: string | null;
  readonly acceptedCaseReopenCount: number | null;
}

/** One recorded decision on a request, with the decider named. */
export interface ExceptionDecisionRecord {
  readonly id: string;
  readonly decision: string;
  readonly authorityLevel: string;
  readonly decidedByUserId: string | null;
  readonly decidedByName: string | null;
  readonly decidedByRoleCode: string | null;
  readonly delegationReference: string | null;
  readonly requesterIsApprover: boolean;
  readonly separationRequired: boolean;
  readonly stepUpSatisfied: boolean;
  readonly reason: string;
  readonly grantedEffectiveFrom: string | null;
  readonly grantedExpiresAt: string | null;
  readonly decidedAt: string;
}

/** The case an acceptance disposes of. */
export interface ExceptionCaseSummary {
  readonly caseId: string;
  readonly causeCode: string;
  readonly severity: string;
  readonly state: string;
  readonly accountableRoleCode: string;
  readonly escalationLevel: number;
  readonly reopenCount: number;
}

/** How a decision on the request would be sized now. */
export interface DecisionTerms {
  readonly policyInForce: boolean;
  readonly requiredAuthority: string;
  readonly separationRequired: boolean;
  readonly periodExceedsMaximum: boolean;
}

/** The person asking, as far as a decision page needs them. */
export interface DecisionViewer {
  readonly userId: string;
  readonly decidingRole: string | null;
  readonly stepUpSatisfied: boolean;
  readonly stepUpValidUntil: string | null;
}

/** One acceptance request in full, with what the viewer may do about it. */
export interface ExceptionDetail {
  readonly exception: AcceptedException;
  readonly decisions: readonly ExceptionDecisionRecord[];
  readonly caseSummary: ExceptionCaseSummary | undefined;
  readonly subject: CaseSubject | undefined;
  readonly decisionTerms: DecisionTerms | undefined;
  readonly viewer: DecisionViewer | undefined;
  readonly allowedActions: readonly string[];
  readonly blockedActions: readonly BlockedAction[];
}

/** One scope a request on a case may name. */
export interface ExceptionScopeOption {
  readonly scopeKind: string;
  readonly reference: string;
  readonly label: string;
  readonly platformCode: string | null;
  readonly fulfillmentModeCode: string | null;
  readonly storeId: string | null;
  readonly storeCode: string | null;
  readonly storeName: string | null;
  readonly platformSkuKey: string | null;
  readonly skuCode: string | null;
  readonly displayName: string | null;
}

/** Everything a request form needs before anybody asks. */
export interface ExceptionOptions {
  readonly caseId: string;
  readonly causeCode: string;
  readonly severity: string;
  readonly scopes: readonly ExceptionScopeOption[];
  readonly reasonCodes: readonly string[];
  readonly policyInForce: boolean;
  readonly maxDurationDays: number | null;
  readonly materialDurationDays: number | null;
  readonly materialProfitAtRisk: string | null;
  readonly materialCurrency: string | null;
  readonly repeatOccurrenceCount: number | null;
  readonly repeatLookbackDays: number | null;
  readonly occurrenceCount: number | null;
  readonly profitAtRiskAmount: string | null;
  readonly profitAtRiskCurrency: string | null;
}

/** How much authority a request would need, as the rules would decide now. */
export interface ExceptionPreview {
  readonly policyInForce: boolean;
  readonly requiredAuthority: string;
  readonly separationRequired: boolean;
  readonly occurrenceCount: number | null;
}

/** What sizes a request: its exposure and period. */
export interface ExceptionSizingDraft {
  readonly consequenceAmount: string | null;
  readonly consequenceCurrency: string | null;
  readonly effectiveFrom: string;
  readonly expiresAt: string;
}

/** A request to accept a case's risk for a bounded period. */
export interface ExceptionRequestDraft extends ExceptionSizingDraft {
  readonly scopeKind: string;
  readonly scopeReference: string;
  readonly reasonCode: string;
  readonly rationale: string;
  readonly expectedConsequence: string;
  readonly evidenceReference: string;
  readonly reviewAt: string;
}

// ------------------------------------------------------------------ supply authority

/** Current append-only state of one governed inbound claim. */
export interface InboundAttestation {
  readonly id: string;
  readonly productVariantId: string;
  readonly externalReference: string;
  readonly versionId: string;
  readonly versionNo: number;
  readonly quantity: number;
  readonly expectedArrivalFrom: string;
  readonly expectedArrivalTo: string;
  readonly businessStatus: string;
  readonly evidenceReference: string;
  readonly lastVerifiedAt: string;
}

/** One claim at its current version, as the list shows it. */
export interface InboundListItem extends InboundAttestation {
  readonly skuCode: string | null;
  readonly displayName: string | null;
  readonly changeKind: string | null;
  readonly sourceTime: string | null;
  readonly recordedAt: string | null;
  readonly reason: string | null;
  readonly attestedByName: string | null;
  readonly canAttest: boolean;
}

/** One page of current inbound claims. */
export interface InboundPage extends Page<InboundListItem> {
  /** Whether the person may register a claim on any variant. */
  readonly canAttestAny: boolean;
}

export interface InboundAttestationDraft {
  readonly productVariantId: string;
  readonly externalReference: string;
  readonly quantity: number;
  readonly expectedArrivalFrom: string;
  readonly expectedArrivalTo: string;
  readonly businessStatus: string;
  readonly evidenceReference: string;
  readonly sourceTime: string;
  /** Optional when registering; a blank reason is sent as null, never as empty text. */
  readonly reason: string | null;
}

/** Identity returned after an effective-dated policy publication or retirement. */
export interface ManagedAvailabilityPolicy {
  readonly id: string;
  readonly kind: string;
  readonly version: number;
  readonly scopeReference: string;
  readonly effectiveFrom: string;
  readonly effectiveTo: string | null;
  readonly status: string;
}

/** One lead-time and safety version, with its scope spelled out. */
export interface LeadTimePolicy {
  readonly id: string;
  readonly version: number;
  readonly scopeKind: string;
  /** The stored scope identity versions of one scope share. */
  readonly scopeKey: string;
  readonly productVariantId: string | null;
  readonly skuCode: string | null;
  readonly displayName: string | null;
  readonly supplierCode: string | null;
  readonly routeCode: string | null;
  readonly categoryCode: string | null;
  readonly leadTimeDaysMin: number;
  readonly leadTimeDaysMax: number;
  readonly safetyDays: number;
  readonly reason: string | null;
  readonly evidenceReference: string | null;
  readonly lastReviewedAt: string | null;
  readonly effectiveFrom: string;
  readonly effectiveTo: string | null;
  readonly status: string;
  /** CURRENT, SCHEDULED or ENDED for an active version, otherwise its status. */
  readonly lifecycle: string;
  readonly ownerName: string | null;
  readonly createdAt: string | null;
  readonly canManage: boolean;
}

/** One page of lead-time versions and what the person may do. */
export interface LeadTimePolicyPage extends Page<LeadTimePolicy> {
  readonly canPublishOrganization: boolean;
  readonly canPublishVariant: boolean;
  readonly stepUpSatisfied: boolean;
  readonly stepUpValidUntil: string | null;
}

export interface LeadTimePolicyDraft {
  readonly scopeKind: string;
  readonly productVariantId: string | null;
  readonly supplierCode: string | null;
  readonly routeCode: string | null;
  readonly categoryCode: string | null;
  readonly leadTimeDaysMin: number;
  readonly leadTimeDaysMax: number;
  readonly safetyDays: number;
  readonly reason: string;
  readonly evidenceReference: string;
  readonly lastReviewedAt: string;
  readonly effectiveFrom: string;
  readonly effectiveTo: string | null;
  readonly fallbackOfId: string | null;
  readonly supersedesPolicyId: string | null;
}

// ------------------------------------------------------------------ parsing

type Row = Record<string, unknown>;

function row(body: unknown): Row | undefined {
  return typeof body === 'object' && body !== null && !Array.isArray(body)
    ? (body as Row)
    : undefined;
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function optionalText(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

function integer(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) ? value : undefined;
}

function optionalInteger(value: unknown): number | null {
  return integer(value) ?? null;
}

/**
 * A decimal the backend sent, kept as text. Parsing one into a JavaScript
 * number would silently round it, and a rounded amount is a different amount.
 */
function decimal(value: unknown): string | null {
  if (typeof value === 'string') return value;
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : null;
}

function textList(value: unknown): readonly string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
}

/** Members that do not parse are dropped; a list that is not a list is empty. */
function members<T>(value: unknown, parse: (body: unknown) => T | undefined): readonly T[] {
  return Array.isArray(value)
    ? value.map(parse).filter((item): item is T => item !== undefined)
    : [];
}

/** A bare array of members, or undefined when the body is not one. */
function list<T>(parse: (body: unknown) => T | undefined) {
  return (body: unknown): readonly T[] | undefined =>
    Array.isArray(body) ? members(body, parse) : undefined;
}

/** The page envelope, or a bare array from an older backend. */
function page<T>(parse: (body: unknown) => T | undefined) {
  return (body: unknown): Page<T> | undefined => {
    if (Array.isArray(body)) {
      const items = members(body, parse);
      return { items, total: undefined, offset: 0, limit: items.length };
    }
    const r = row(body);
    if (r === undefined || !Array.isArray(r.items)) return undefined;
    const items = members(r.items, parse);
    return {
      items,
      total: integer(r.total),
      offset: integer(r.offset) ?? 0,
      limit: integer(r.limit) ?? items.length,
    };
  };
}

function parseRankFactor(body: unknown): AvailabilityRankFactor | undefined {
  const r = row(body);
  const factorCode = text(r?.factorCode);
  const displayNote = text(r?.displayNote);
  if (r === undefined || factorCode === undefined || displayNote === undefined) return undefined;
  return {
    factorCode,
    value: decimal(r.value),
    weight: decimal(r.weight),
    contribution: decimal(r.contribution),
    displayNote,
  };
}

function parseDemandWindow(body: unknown): AvailabilityDemandWindow | undefined {
  const r = row(body);
  const windowCode = text(r?.windowCode);
  const periodStart = text(r?.periodStart);
  const periodEnd = text(r?.periodEnd);
  const eligibility = text(r?.eligibility);
  if (
    r === undefined ||
    windowCode === undefined ||
    periodStart === undefined ||
    periodEnd === undefined ||
    eligibility === undefined
  ) {
    return undefined;
  }
  return {
    windowCode,
    periodStart,
    periodEnd,
    completedUnits: optionalInteger(r.completedUnits),
    dailyRate: decimal(r.dailyRate),
    observedDays: decimal(r.observedDays),
    coverageRatio: decimal(r.coverageRatio),
    sampleSufficient: r.sampleSufficient === true,
    censored: r.censored === true,
    censoringReason: optionalText(r.censoringReason),
    outlierShare: decimal(r.outlierShare),
    eligibility,
  };
}

/**
 * Read one child risk. A child whose kind, lane or evidence state is missing is
 * dropped rather than defaulted.
 */
function parseAvailabilityChild(body: unknown): AvailabilityChild | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id);
  const childKind = text(r.childKind);
  const lane = text(r.lane);
  const evidenceState = text(r.evidenceState);
  const confidenceState = text(r.confidenceState);
  const causeCode = text(r.causeCode);
  const profitLane = text(r.profitLane);
  const demandSelectionReason = text(r.demandSelectionReason);
  const calculatedAt = text(r.calculatedAt);
  if (
    id === undefined ||
    (childKind !== 'CHANNEL' && childKind !== 'COMPANY') ||
    lane === undefined ||
    evidenceState === undefined ||
    confidenceState === undefined ||
    causeCode === undefined ||
    profitLane === undefined ||
    demandSelectionReason === undefined ||
    calculatedAt === undefined
  ) {
    return undefined;
  }
  return {
    id,
    childKind,
    platformCode: optionalText(r.platformCode),
    storeId: optionalText(r.storeId),
    platformListingVariantId: optionalText(r.platformListingVariantId),
    fulfillmentModeCode: optionalText(r.fulfillmentModeCode),
    lane,
    evidenceState,
    confidenceState,
    causeCode,
    availableUnits: optionalInteger(r.availableUnits),
    dailyDemandRate: decimal(r.dailyDemandRate),
    daysOfCover: decimal(r.daysOfCover),
    coverageHorizonDays: optionalInteger(r.coverageHorizonDays),
    projectedStockoutAt: optionalText(r.projectedStockoutAt),
    profitLane,
    profitAtRiskAmount: decimal(r.profitAtRiskAmount),
    profitAtRiskCurrency: optionalText(r.profitAtRiskCurrency),
    demandSelectionReason,
    conservativeProofTerms: textList(r.conservativeProofTerms),
    blockerCodes: textList(r.blockerCodes),
    rankFactors: members(r.rankFactors, parseRankFactor),
    demandWindows: members(r.demandWindows, parseDemandWindow),
    calculatedAt,
  };
}

/** Read one grouped card. */
export function parseAvailabilityCard(body: unknown): AvailabilityCard | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id);
  const productVariantId = text(r.productVariantId);
  const skuCode = text(r.skuCode);
  const displayName = text(r.displayName);
  const lane = text(r.lane);
  const policyVersionDigest = text(r.policyVersionDigest);
  const asOf = text(r.asOf);
  const calculatedAt = text(r.calculatedAt);
  if (
    id === undefined ||
    productVariantId === undefined ||
    skuCode === undefined ||
    displayName === undefined ||
    lane === undefined ||
    policyVersionDigest === undefined ||
    asOf === undefined ||
    calculatedAt === undefined
  ) {
    return undefined;
  }
  return {
    id,
    productVariantId,
    skuCode,
    displayName,
    lane,
    triggeringChildId: optionalText(r.triggeringChildId),
    rankScore: decimal(r.rankScore),
    policyVersionDigest,
    asOf,
    calculatedAt,
    children: members(r.children, parseAvailabilityChild),
  };
}

function parseVariantOption(body: unknown): VariantOption | undefined {
  const r = row(body);
  const productVariantId = text(r?.productVariantId);
  const skuCode = text(r?.skuCode);
  if (r === undefined || productVariantId === undefined || skuCode === undefined) return undefined;
  return {
    productVariantId,
    skuCode,
    displayName: text(r.displayName) ?? skuCode,
    colorLabel: optionalText(r.colorLabel),
    sizeLabel: optionalText(r.sizeLabel),
    status: optionalText(r.status),
  };
}

function parseSubject(body: unknown): CaseSubject | undefined {
  const r = row(body);
  const productVariantId = text(r?.productVariantId);
  if (r === undefined || productVariantId === undefined) return undefined;
  return {
    productVariantId,
    skuCode: optionalText(r.skuCode),
    displayName: optionalText(r.displayName),
    childKind: optionalText(r.childKind),
    platformCode: optionalText(r.platformCode),
    fulfillmentModeCode: optionalText(r.fulfillmentModeCode),
    storeId: optionalText(r.storeId),
    storeCode: optionalText(r.storeCode),
    storeName: optionalText(r.storeName),
    platformSkuKey: optionalText(r.platformSkuKey),
  };
}

function parseOpenException(body: unknown): OpenException | null {
  const r = row(body);
  const id = text(r?.id);
  const state = text(r?.state);
  if (r === undefined || id === undefined || state === undefined) return null;
  return {
    id,
    state,
    requiredAuthority: text(r.requiredAuthority) ?? '',
    expiresAt: optionalText(r.expiresAt),
  };
}

function parseBlockedAction(body: unknown): BlockedAction | undefined {
  const r = row(body);
  const action = text(r?.action);
  const reason = text(r?.reason);
  return action === undefined || reason === undefined ? undefined : { action, reason };
}

function parseAvailabilityCase(body: unknown): AvailabilityCase | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id);
  const cardId = text(r.cardId);
  const childId = text(r.childId);
  const causeCode = text(r.causeCode);
  const causeKey = text(r.causeKey);
  const severity = text(r.severity);
  const state = text(r.state);
  const accountableRoleCode = text(r.accountableRoleCode);
  const actionDueAt = text(r.actionDueAt);
  const firstActivatedAt = text(r.firstActivatedAt);
  if (
    id === undefined ||
    cardId === undefined ||
    childId === undefined ||
    causeCode === undefined ||
    causeKey === undefined ||
    severity === undefined ||
    state === undefined ||
    accountableRoleCode === undefined ||
    actionDueAt === undefined ||
    firstActivatedAt === undefined
  ) {
    return undefined;
  }
  return {
    id,
    cardId,
    childId,
    causeCode,
    causeKey,
    severity,
    state,
    accountableRoleCode,
    assigneeUserId: optionalText(r.assigneeUserId),
    actionDueAt,
    originalActionDueAt: optionalText(r.originalActionDueAt),
    actionSlaPausedAt: optionalText(r.actionSlaPausedAt),
    actionSlaRemainingMillis: optionalInteger(r.actionSlaRemainingMillis),
    outcomeDueAt: optionalText(r.outcomeDueAt),
    reopenCount: integer(r.reopenCount) ?? 0,
    escalationLevel: integer(r.escalationLevel) ?? 0,
    firstActivatedAt,
    lastEvidenceAt: optionalText(r.lastEvidenceAt),
    improvementFirstSeenAt: optionalText(r.improvementFirstSeenAt),
    subject: parseSubject(r.subject),
    openException: parseOpenException(r.openException),
    allowedActions: textList(r.allowedActions),
    blockedActions: members(r.blockedActions, parseBlockedAction),
  };
}

function parseJournalEntry(body: unknown): CaseJournalEntry | undefined {
  const r = row(body);
  const eventKind = text(r?.eventKind);
  const reason = text(r?.reason);
  const occurredAt = text(r?.occurredAt);
  if (
    r === undefined ||
    eventKind === undefined ||
    reason === undefined ||
    occurredAt === undefined
  )
    return undefined;
  return {
    sequenceNo: integer(r.sequenceNo) ?? 0,
    eventKind,
    fromState: optionalText(r.fromState),
    toState: optionalText(r.toState),
    actionKind: optionalText(r.actionKind),
    verificationKind: optionalText(r.verificationKind),
    verificationOutcome: optionalText(r.verificationOutcome),
    actorUserId: optionalText(r.actorUserId),
    actorName: optionalText(r.actorName),
    actorRoleCode: optionalText(r.actorRoleCode),
    reason,
    evidenceReference: optionalText(r.evidenceReference),
    observedAt: optionalText(r.observedAt),
    occurredAt,
  };
}

function parseAcceptedException(body: unknown): AcceptedException | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id);
  const causeCode = text(r.causeCode);
  const reasonCode = text(r.reasonCode);
  const state = text(r.state);
  const requiredAuthority = text(r.requiredAuthority);
  if (
    id === undefined ||
    causeCode === undefined ||
    reasonCode === undefined ||
    state === undefined ||
    requiredAuthority === undefined
  ) {
    return undefined;
  }
  return {
    id,
    caseId: optionalText(r.caseId),
    childId: optionalText(r.childId),
    causeCode,
    scopeKind: optionalText(r.scopeKind),
    scopeReference: optionalText(r.scopeReference),
    reasonCode,
    rationale: optionalText(r.rationale),
    expectedConsequence: optionalText(r.expectedConsequence),
    consequenceAmount: decimal(r.consequenceAmount),
    consequenceCurrency: optionalText(r.consequenceCurrency),
    evidenceReference: optionalText(r.evidenceReference),
    requestedByUserId: optionalText(r.requestedByUserId),
    requestedByName: optionalText(r.requestedByName),
    requestedAt: optionalText(r.requestedAt),
    decisionOwnerRoleCode: optionalText(r.decisionOwnerRoleCode),
    requiredAuthority,
    state,
    effectiveFrom: optionalText(r.effectiveFrom),
    expiresAt: optionalText(r.expiresAt),
    reviewAt: optionalText(r.reviewAt),
    invalidatedAt: optionalText(r.invalidatedAt),
    invalidationReason: optionalText(r.invalidationReason),
    occurrenceCount: optionalInteger(r.occurrenceCount),
    acceptedSeverity: optionalText(r.acceptedSeverity),
    acceptedProfitAtRiskAmount: decimal(r.acceptedProfitAtRiskAmount),
    acceptedProfitAtRiskCurrency: optionalText(r.acceptedProfitAtRiskCurrency),
    acceptedCaseReopenCount: optionalInteger(r.acceptedCaseReopenCount),
  };
}

function parseDecision(body: unknown): ExceptionDecisionRecord | undefined {
  const r = row(body);
  const id = text(r?.id);
  const decision = text(r?.decision);
  const decidedAt = text(r?.decidedAt);
  if (r === undefined || id === undefined || decision === undefined || decidedAt === undefined)
    return undefined;
  return {
    id,
    decision,
    authorityLevel: text(r.authorityLevel) ?? '',
    decidedByUserId: optionalText(r.decidedByUserId),
    decidedByName: optionalText(r.decidedByName),
    decidedByRoleCode: optionalText(r.decidedByRoleCode),
    delegationReference: optionalText(r.delegationReference),
    requesterIsApprover: r.requesterIsApprover === true,
    separationRequired: r.separationRequired === true,
    stepUpSatisfied: r.stepUpSatisfied === true,
    reason: text(r.reason) ?? '',
    grantedEffectiveFrom: optionalText(r.grantedEffectiveFrom),
    grantedExpiresAt: optionalText(r.grantedExpiresAt),
    decidedAt,
  };
}

function parseCaseSummary(body: unknown): ExceptionCaseSummary | undefined {
  const r = row(body);
  const caseId = text(r?.caseId);
  if (r === undefined || caseId === undefined) return undefined;
  return {
    caseId,
    causeCode: text(r.causeCode) ?? '',
    severity: text(r.severity) ?? '',
    state: text(r.state) ?? '',
    accountableRoleCode: text(r.accountableRoleCode) ?? '',
    escalationLevel: integer(r.escalationLevel) ?? 0,
    reopenCount: integer(r.reopenCount) ?? 0,
  };
}

function parseExceptionDetail(body: unknown): ExceptionDetail | undefined {
  const r = row(body);
  const exception = parseAcceptedException(r?.exception);
  if (r === undefined || exception === undefined) return undefined;
  const terms = row(r.decisionTerms);
  const viewer = row(r.viewer);
  const viewerId = text(viewer?.userId);
  return {
    exception,
    decisions: members(r.decisions, parseDecision),
    caseSummary: parseCaseSummary(r.caseSummary),
    subject: parseSubject(r.subject),
    decisionTerms:
      terms === undefined
        ? undefined
        : {
            policyInForce: terms.policyInForce === true,
            requiredAuthority: text(terms.requiredAuthority) ?? exception.requiredAuthority,
            separationRequired: terms.separationRequired === true,
            periodExceedsMaximum: terms.periodExceedsMaximum === true,
          },
    viewer:
      viewer === undefined || viewerId === undefined
        ? undefined
        : {
            userId: viewerId,
            decidingRole: optionalText(viewer.decidingRole),
            stepUpSatisfied: viewer.stepUpSatisfied === true,
            stepUpValidUntil: optionalText(viewer.stepUpValidUntil),
          },
    allowedActions: textList(r.allowedActions),
    blockedActions: members(r.blockedActions, parseBlockedAction),
  };
}

function parseScopeOption(body: unknown): ExceptionScopeOption | undefined {
  const r = row(body);
  const scopeKind = text(r?.scopeKind);
  const reference = text(r?.reference);
  if (r === undefined || scopeKind === undefined || reference === undefined) return undefined;
  return {
    scopeKind,
    reference,
    label: text(r.label) ?? reference,
    platformCode: optionalText(r.platformCode),
    fulfillmentModeCode: optionalText(r.fulfillmentModeCode),
    storeId: optionalText(r.storeId),
    storeCode: optionalText(r.storeCode),
    storeName: optionalText(r.storeName),
    platformSkuKey: optionalText(r.platformSkuKey),
    skuCode: optionalText(r.skuCode),
    displayName: optionalText(r.displayName),
  };
}

function parseExceptionOptions(body: unknown): ExceptionOptions | undefined {
  const r = row(body);
  const caseId = text(r?.caseId);
  if (r === undefined || caseId === undefined || !Array.isArray(r.scopes)) return undefined;
  return {
    caseId,
    causeCode: text(r.causeCode) ?? '',
    severity: text(r.severity) ?? '',
    scopes: members(r.scopes, parseScopeOption),
    reasonCodes: textList(r.reasonCodes),
    policyInForce: r.policyInForce === true,
    maxDurationDays: optionalInteger(r.maxDurationDays),
    materialDurationDays: optionalInteger(r.materialDurationDays),
    materialProfitAtRisk: decimal(r.materialProfitAtRisk),
    materialCurrency: optionalText(r.materialCurrency),
    repeatOccurrenceCount: optionalInteger(r.repeatOccurrenceCount),
    repeatLookbackDays: optionalInteger(r.repeatLookbackDays),
    occurrenceCount: optionalInteger(r.occurrenceCount),
    profitAtRiskAmount: decimal(r.profitAtRiskAmount),
    profitAtRiskCurrency: optionalText(r.profitAtRiskCurrency),
  };
}

function parseExceptionPreview(body: unknown): ExceptionPreview | undefined {
  const r = row(body);
  const requiredAuthority = text(r?.requiredAuthority);
  if (r === undefined || requiredAuthority === undefined) return undefined;
  return {
    policyInForce: r.policyInForce === true,
    requiredAuthority,
    separationRequired: r.separationRequired === true,
    occurrenceCount: optionalInteger(r.occurrenceCount),
  };
}

function parseInboundAttestation(body: unknown): InboundAttestation | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id);
  const productVariantId = text(r.productVariantId);
  const externalReference = text(r.externalReference);
  const versionId = text(r.versionId);
  const expectedArrivalFrom = text(r.expectedArrivalFrom);
  const expectedArrivalTo = text(r.expectedArrivalTo);
  const businessStatus = text(r.businessStatus);
  const evidenceReference = text(r.evidenceReference);
  const lastVerifiedAt = text(r.lastVerifiedAt);
  if (
    id === undefined ||
    productVariantId === undefined ||
    externalReference === undefined ||
    versionId === undefined ||
    expectedArrivalFrom === undefined ||
    expectedArrivalTo === undefined ||
    businessStatus === undefined ||
    evidenceReference === undefined ||
    lastVerifiedAt === undefined
  ) {
    return undefined;
  }
  return {
    id,
    productVariantId,
    externalReference,
    versionId,
    versionNo: integer(r.versionNo) ?? 0,
    quantity: integer(r.quantity) ?? 0,
    expectedArrivalFrom,
    expectedArrivalTo,
    businessStatus,
    evidenceReference,
    lastVerifiedAt,
  };
}

function parseInboundListItem(body: unknown): InboundListItem | undefined {
  const base = parseInboundAttestation(body);
  const r = row(body);
  if (base === undefined || r === undefined) return undefined;
  return {
    ...base,
    skuCode: optionalText(r.skuCode),
    displayName: optionalText(r.displayName),
    changeKind: optionalText(r.changeKind),
    sourceTime: optionalText(r.sourceTime),
    recordedAt: optionalText(r.recordedAt),
    reason: optionalText(r.reason),
    attestedByName: optionalText(r.attestedByName),
    canAttest: r.canAttest === true,
  };
}

function parseInboundPage(body: unknown): InboundPage | undefined {
  const parsed = page(parseInboundListItem)(body);
  if (parsed === undefined) return undefined;
  return { ...parsed, canAttestAny: row(body)?.canAttestAny === true };
}

function parseManagedAvailabilityPolicy(body: unknown): ManagedAvailabilityPolicy | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id);
  const kind = text(r.kind);
  const scopeReference = text(r.scopeReference);
  const effectiveFrom = text(r.effectiveFrom);
  const status = text(r.status);
  if (
    id === undefined ||
    kind === undefined ||
    scopeReference === undefined ||
    effectiveFrom === undefined ||
    status === undefined
  ) {
    return undefined;
  }
  return {
    id,
    kind,
    version: integer(r.version) ?? 0,
    scopeReference,
    effectiveFrom,
    effectiveTo: optionalText(r.effectiveTo),
    status,
  };
}

function parseLeadTimePolicy(body: unknown): LeadTimePolicy | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id);
  const scopeKind = text(r.scopeKind);
  const scopeKey = text(r.scopeKey);
  const effectiveFrom = text(r.effectiveFrom);
  const status = text(r.status);
  const leadTimeDaysMin = integer(r.leadTimeDaysMin);
  const leadTimeDaysMax = integer(r.leadTimeDaysMax);
  const safetyDays = integer(r.safetyDays);
  if (
    id === undefined ||
    scopeKind === undefined ||
    scopeKey === undefined ||
    effectiveFrom === undefined ||
    status === undefined ||
    leadTimeDaysMin === undefined ||
    leadTimeDaysMax === undefined ||
    safetyDays === undefined
  ) {
    return undefined;
  }
  return {
    id,
    version: integer(r.version) ?? 0,
    scopeKind,
    scopeKey,
    productVariantId: optionalText(r.productVariantId),
    skuCode: optionalText(r.skuCode),
    displayName: optionalText(r.displayName),
    supplierCode: optionalText(r.supplierCode),
    routeCode: optionalText(r.routeCode),
    categoryCode: optionalText(r.categoryCode),
    leadTimeDaysMin,
    leadTimeDaysMax,
    safetyDays,
    reason: optionalText(r.reason),
    evidenceReference: optionalText(r.evidenceReference),
    lastReviewedAt: optionalText(r.lastReviewedAt),
    effectiveFrom,
    effectiveTo: optionalText(r.effectiveTo),
    status,
    lifecycle: text(r.lifecycle) ?? status,
    ownerName: optionalText(r.ownerName),
    createdAt: optionalText(r.createdAt),
    canManage: r.canManage === true,
  };
}

function parseLeadTimePolicyPage(body: unknown): LeadTimePolicyPage | undefined {
  const parsed = page(parseLeadTimePolicy)(body);
  const r = row(body);
  if (parsed === undefined) return undefined;
  return {
    ...parsed,
    canPublishOrganization: r?.canPublishOrganization === true,
    canPublishVariant: r?.canPublishVariant === true,
    stepUpSatisfied: r?.stepUpSatisfied === true,
    stepUpValidUntil: optionalText(r?.stepUpValidUntil),
  };
}

/** Any successful answer, for writes whose result the page reloads anyway. */
function accepted(body: unknown): true | undefined {
  return row(body) === undefined ? undefined : true;
}

// ------------------------------------------------------------------ requests

function query(parameters: Readonly<Record<string, string | number | undefined>>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(parameters)) {
    if (value !== undefined && value !== '') search.set(key, String(value));
  }
  const encoded = search.toString();
  return encoded === '' ? '' : `?${encoded}`;
}

function post(body: unknown): RequestInit {
  return { method: 'POST', body: JSON.stringify(body) };
}

function id(value: string): string {
  return encodeURIComponent(value);
}

/**
 * One page of the stockout and availability queue, most urgent first.
 *
 * The backend orders it and the console does not re-sort: the order is a
 * deterministic figure with a published definition.
 */
export function fetchAvailabilityQueue(
  context: ConsoleRequest,
  options: {
    readonly lane?: string | undefined;
    readonly q?: string | undefined;
    readonly limit: number;
    readonly offset: number;
  },
): Promise<ConsoleOutcome<Page<AvailabilityCard>>> {
  return request(
    context,
    `${BASE}/queue${query({ lane: options.lane, q: options.q, limit: options.limit, offset: options.offset })}`,
    page(parseAvailabilityCard),
  );
}

/** One grouped card with every child, factor and window behind it. */
export function fetchAvailabilityCard(
  context: ConsoleRequest,
  productVariantId: string,
): Promise<ConsoleOutcome<AvailabilityCard>> {
  return request(context, `${BASE}/cards/${id(productVariantId)}`, parseAvailabilityCard);
}

/** Variants the person may name for one purpose, found by SKU or name. */
export function fetchAvailabilityVariants(
  context: ConsoleRequest,
  options: {
    readonly q?: string | undefined;
    readonly purpose: VariantPurpose;
    readonly limit?: number;
  },
): Promise<ConsoleOutcome<readonly VariantOption[]>> {
  return request(
    context,
    `${BASE}/variants${query({ q: options.q, purpose: options.purpose, limit: options.limit ?? 20 })}`,
    list(parseVariantOption),
  );
}

/** One page of the organization's accountable availability work. */
export function fetchAvailabilityCases(
  context: ConsoleRequest,
  options: {
    readonly view: CaseView;
    readonly productVariantId?: string | undefined;
    readonly limit: number;
    readonly offset: number;
  },
): Promise<ConsoleOutcome<Page<AvailabilityCase>>> {
  return request(
    context,
    `${BASE}/cases${query({
      view: options.view,
      productVariantId: options.productVariantId,
      limit: options.limit,
      offset: options.offset,
    })}`,
    page(parseAvailabilityCase),
  );
}

/** One case as it stands, with what it is about and what the viewer may do. */
export function fetchAvailabilityCase(
  context: ConsoleRequest,
  caseId: string,
): Promise<ConsoleOutcome<AvailabilityCase>> {
  return request(context, `${BASE}/cases/${id(caseId)}`, parseAvailabilityCase);
}

/** Everything that ever happened to one case, oldest first. */
export function fetchCaseJournal(
  context: ConsoleRequest,
  caseId: string,
): Promise<ConsoleOutcome<readonly CaseJournalEntry[]>> {
  return request(context, `${BASE}/cases/${id(caseId)}/journal`, list(parseJournalEntry));
}

/** Every acceptance ever recorded against one case, newest first. */
export function fetchCaseExceptions(
  context: ConsoleRequest,
  caseId: string,
): Promise<ConsoleOutcome<readonly AcceptedException[]>> {
  return request(context, `${BASE}/cases/${id(caseId)}/exceptions`, list(parseAcceptedException));
}

/**
 * Record accountable structured action. There is deliberately no shape this
 * accepts that means "looked at it".
 */
export function recordCaseAction(
  context: ConsoleRequest,
  caseId: string,
  body: {
    readonly actionKind: string;
    readonly evidenceReference: string;
    readonly reason: string;
  },
): Promise<ConsoleOutcome<true>> {
  return request(context, `${BASE}/cases/${id(caseId)}/action`, accepted, post(body));
}

/** Raise a case one level, recorded as this person's escalation. */
export function escalateCase(
  context: ConsoleRequest,
  caseId: string,
  reason: string,
): Promise<ConsoleOutcome<true>> {
  return request(context, `${BASE}/cases/${id(caseId)}/escalation`, accepted, post({ reason }));
}

/** The scopes, reasons and published terms a request on this case would use. */
export function fetchExceptionOptions(
  context: ConsoleRequest,
  caseId: string,
): Promise<ConsoleOutcome<ExceptionOptions>> {
  return request(context, `${BASE}/cases/${id(caseId)}/exception-options`, parseExceptionOptions);
}

/** How much authority a request would need, without recording anything. */
export function previewException(
  context: ConsoleRequest,
  caseId: string,
  sizing: ExceptionSizingDraft,
): Promise<ConsoleOutcome<ExceptionPreview>> {
  return request(
    context,
    `${BASE}/cases/${id(caseId)}/exception-preview`,
    parseExceptionPreview,
    post(sizing),
  );
}

/** Ask the business to accept a case's risk for a bounded period. */
export function requestException(
  context: ConsoleRequest,
  caseId: string,
  draft: ExceptionRequestDraft,
): Promise<ConsoleOutcome<AcceptedException>> {
  return request(
    context,
    `${BASE}/cases/${id(caseId)}/exceptions`,
    parseAcceptedException,
    post(draft),
  );
}

/** One acceptance request in full. */
export function fetchExceptionDetail(
  context: ConsoleRequest,
  exceptionId: string,
): Promise<ConsoleOutcome<ExceptionDetail>> {
  return request(context, `${BASE}/exceptions/${id(exceptionId)}`, parseExceptionDetail);
}

/** Approve or reject one acceptance request under the person's own authority. */
export function decideException(
  context: ConsoleRequest,
  exceptionId: string,
  approved: boolean,
  reason: string,
): Promise<ConsoleOutcome<AcceptedException>> {
  return request(
    context,
    `${BASE}/exceptions/${id(exceptionId)}/decision`,
    parseAcceptedException,
    post({ approved, reason }),
  );
}

/** Withdraw a request nobody has decided yet; only the requester may. */
export function withdrawException(
  context: ConsoleRequest,
  exceptionId: string,
  reason: string,
): Promise<ConsoleOutcome<AcceptedException>> {
  return request(
    context,
    `${BASE}/exceptions/${id(exceptionId)}/withdrawal`,
    parseAcceptedException,
    post({ reason }),
  );
}

/** The current version of every inbound claim the person may read. */
export function fetchInboundAttestations(
  context: ConsoleRequest,
  options: {
    readonly productVariantId?: string | undefined;
    readonly status?: string | undefined;
    readonly limit: number;
    readonly offset: number;
  },
): Promise<ConsoleOutcome<InboundPage>> {
  return request(
    context,
    `${BASE}/inbound${query({
      productVariantId: options.productVariantId,
      status: options.status,
      limit: options.limit,
      offset: options.offset,
    })}`,
    parseInboundPage,
  );
}

/** Create the first attributable version of an inbound claim. */
export function createInboundAttestation(
  context: ConsoleRequest,
  draft: InboundAttestationDraft,
): Promise<ConsoleOutcome<InboundAttestation>> {
  return request(context, `${BASE}/inbound`, parseInboundAttestation, post(draft));
}

/** Append a corrected inbound version; the external identity is immutable. */
export function amendInboundAttestation(
  context: ConsoleRequest,
  attestationId: string,
  body: Omit<InboundAttestationDraft, 'productVariantId' | 'externalReference'> & {
    readonly expectedVersion: number;
  },
): Promise<ConsoleOutcome<InboundAttestation>> {
  return request(
    context,
    `${BASE}/inbound/${id(attestationId)}/amend`,
    parseInboundAttestation,
    post(body),
  );
}

/** Append a cancelled version: the claim stops counting as supply. */
export function cancelInboundAttestation(
  context: ConsoleRequest,
  attestationId: string,
  body: {
    readonly expectedVersion: number;
    readonly evidenceReference: string;
    readonly reason: string;
  },
): Promise<ConsoleOutcome<InboundAttestation>> {
  return request(
    context,
    `${BASE}/inbound/${id(attestationId)}/cancel`,
    parseInboundAttestation,
    post(body),
  );
}

/** Append a re-verified version with unchanged quantity and window. */
export function reverifyInboundAttestation(
  context: ConsoleRequest,
  attestationId: string,
  body: {
    readonly expectedVersion: number;
    readonly evidenceReference: string;
    readonly reason: string;
  },
): Promise<ConsoleOutcome<InboundAttestation>> {
  return request(
    context,
    `${BASE}/inbound/${id(attestationId)}/reverify`,
    parseInboundAttestation,
    post(body),
  );
}

/** Lead-time and safety versions the person may read. */
export function fetchLeadTimePolicies(
  context: ConsoleRequest,
  options: {
    readonly status?: string | undefined;
    readonly limit: number;
    readonly offset: number;
  },
): Promise<ConsoleOutcome<LeadTimePolicyPage>> {
  return request(
    context,
    `${BASE}/policies${query({
      kind: 'LEAD_TIME',
      status: options.status,
      limit: options.limit,
      offset: options.offset,
    })}`,
    parseLeadTimePolicyPage,
  );
}

/** Publish or supersede an attributable lead-time and safety version. */
export function publishLeadTimePolicy(
  context: ConsoleRequest,
  draft: LeadTimePolicyDraft,
): Promise<ConsoleOutcome<ManagedAvailabilityPolicy>> {
  return request(
    context,
    `${BASE}/policies/lead-time`,
    parseManagedAvailabilityPolicy,
    post(draft),
  );
}

/** End a current version, or cancel one that never became effective. */
export function retireAvailabilityPolicy(
  context: ConsoleRequest,
  kind: string,
  policyId: string,
  body: { readonly reason: string; readonly evidenceReference: string },
): Promise<ConsoleOutcome<ManagedAvailabilityPolicy>> {
  return request(
    context,
    `${BASE}/policies/${id(kind)}/${id(policyId)}/retire`,
    parseManagedAvailabilityPolicy,
    post(body),
  );
}
