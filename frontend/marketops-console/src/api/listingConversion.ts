import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/**
 * The listing conversion console's view of the backend.
 *
 * Every field the backend refuses to collapse arrives here separately and the
 * parser refuses a body that is missing one rather than defaulting it. A ratio
 * without its state, or a health row without its three layers, is not a fact
 * this console will show.
 */

export interface HealthCondition {
  readonly code: string;
  readonly state: string;
  readonly evidenceReference: string;
}

export interface ListingHealth {
  readonly id: string;
  readonly storeId: string;
  readonly platformListingId: string;
  readonly nativeListingKey: string;
  readonly healthVersion: number;
  readonly necessaryConditions: readonly HealthCondition[];
  readonly necessaryState: string;
  readonly eligibility: Readonly<Record<string, string>>;
  readonly opportunities: readonly { readonly code: string; readonly evidenceReference: string }[];
  readonly affectedSetState: string;
  readonly affectedVariantCount: number;
  readonly sourceTime: string | undefined;
  readonly acquisitionTime: string | undefined;
  readonly computedAt: string;
}

export interface ConversionMeasurement {
  readonly id: string;
  readonly platformListingId: string;
  readonly windowStart: string;
  readonly windowEnd: string;
  readonly retentionWindowDays: number;
  readonly evidencePath: string;
  readonly pathQualified: boolean;
  readonly qualificationReasonCodes: readonly string[];
  readonly visitCount: number | undefined;
  readonly retainedPurchaseVisitCount: number | undefined;
  readonly primaryRatio: string | undefined;
  readonly ratioState: string;
  readonly maturityReached: boolean;
  readonly sourceStratified: boolean;
  readonly sellableSplit: Readonly<Record<string, string>>;
  readonly excludedTransitionDays: readonly string[];
  readonly computedAt: string;
}

export interface ListingDetail {
  readonly listingId: string;
  readonly storeId: string;
  readonly platformCode: string;
  readonly nativeListingKey: string;
  readonly health: ListingHealth | undefined;
  readonly measurements: readonly ConversionMeasurement[];
}

export interface Candidate {
  readonly id: string;
  readonly storeId: string;
  readonly platformListingId: string;
  readonly candidateKind: string;
  readonly comparisonRoundKey: string;
  readonly evidenceReferences: readonly string[];
  readonly expectedEffect: Readonly<Record<string, string>>;
  readonly state: string;
  readonly version: number;
}

export interface ActionReview {
  readonly id: string;
  readonly reviewerUserId: string;
  readonly verdict: string;
  readonly reason: string;
  readonly reviewedAt: string;
}

export interface ActionOccupation {
  readonly id: string;
  readonly axisCode: string;
  readonly requestedValue: string;
  readonly occupiedValue: string;
  readonly state: string;
  readonly releaseBasis: string | undefined;
}

export interface PromotionTerms {
  readonly engagementKind: string;
  readonly nativePromotionKey: string;
  readonly terms: Readonly<Record<string, string>>;
  readonly priceFreeze: boolean;
  readonly autoParticipation: boolean;
  readonly termsEvidenceReference: string;
  readonly obligations: Readonly<Record<string, string>>;
}

export interface PromotionTermsView {
  readonly actionId: string;
  readonly digest: string | undefined;
  readonly fullDisclosure: boolean;
  readonly terms: PromotionTerms | undefined;
}

export interface ListingAction {
  readonly id: string;
  readonly storeId: string;
  readonly platformListingId: string;
  readonly nativeListingKey: string;
  readonly candidateId: string;
  readonly recommendationId: string;
  readonly recommendationVersion: number;
  readonly recommendationState: string;
  readonly affectedSetDigest: string;
  readonly affectedSetState: string;
  readonly affectedVariantCount: number;
  readonly actionKind: string;
  readonly executionPath: string;
  readonly currentTextDigest: string | undefined;
  readonly targetText: string | undefined;
  readonly targetTextDigest: string | undefined;
  readonly restoresCommandId?: string | undefined;
  readonly promotionTermsDigest?: string | undefined;
  readonly kizMarkedDeclared: boolean | undefined;
  readonly materialityRoute: string;
  readonly contentAxisMaterial: boolean | undefined;
  readonly exposureAxisMaterial: boolean | undefined;
  readonly calibrationPackageId: string | undefined;
  readonly calibrationVersion: number | undefined;
  readonly authorUserId: string;
  readonly state: string;
  readonly reviews: readonly ActionReview[];
  readonly binding:
    | {
        readonly id: string;
        readonly state: string;
        readonly expiresAt: string;
        readonly inapplicableReason: string | undefined;
      }
    | undefined;
  readonly launch:
    | { readonly id: string; readonly launchedByUserId: string; readonly launchedAt: string }
    | undefined;
  readonly occupations: readonly ActionOccupation[];
  readonly bindingGaps: readonly string[];
  readonly version: number;
}

export interface AllowanceAxis {
  readonly axisCode: string;
  readonly scopeKind: string;
  readonly limitValue: string;
  readonly reserveValue: string;
  readonly occupiedValue: string;
  readonly requestedValue: string | undefined;
  readonly headroom: string;
  readonly sufficient: boolean;
  readonly unitCode: string;
}

export interface Allowance {
  readonly platformListingId: string;
  readonly axes: readonly AllowanceAxis[];
  readonly resolved: boolean;
  readonly gaps: readonly string[];
}

export interface LaunchAnswer {
  readonly launched: boolean;
  readonly launchId: string | undefined;
  readonly occupationIds: readonly string[];
  readonly insufficientAxes: readonly string[];
}

export interface DescriptionExecutionReceipt {
  readonly id: string;
  readonly executionState: string;
  readonly readbackId: string;
  readonly mutationAttemptId: string | undefined;
  readonly nativeStatusAttemptId: string | undefined;
  readonly gaps: readonly string[];
  readonly recordedAt: string;
  readonly taskEventId: string | undefined;
  readonly taskRecordedAt: string | undefined;
}

export interface DescriptionCommand {
  readonly executionReceipts: readonly DescriptionExecutionReceipt[];
  readonly id: string;
  readonly actionId: string;
  readonly state: string;
  readonly priorTextCaptured: boolean;
  readonly kizMarkedDeclared: boolean;
  readonly equivalenceRule: string;
  readonly attemptNo: number;
  readonly retryBudgetRemaining: number;
  readonly failureCode: string | undefined;
  readonly approvalExpiresAt: string;
  readonly attempts: readonly {
    readonly id: string;
    readonly attemptNo: number;
    readonly purpose: string;
    readonly outcomeClass: string;
    readonly errorCode: string | undefined;
  }[];
  readonly readbacks: readonly {
    readonly id: string;
    readonly matchState: string;
    readonly observedAt: string;
  }[];
}

export interface EvaluationNodeResult {
  readonly id: string;
  readonly nodeCode: string;
  readonly stage: string;
  readonly revisionNo: number;
  readonly primaryRatio: string | undefined;
  readonly conservativeBound: string | undefined;
  readonly acceptedThreshold: string | undefined;
  readonly verdict: string;
  readonly protectionVector: Readonly<Record<string, string>>;
  readonly protectionVerdict: string;
  readonly stopVerdict: string | undefined;
}

export interface Evaluation {
  readonly planId: string;
  readonly actionId: string;
  readonly formalNodes: readonly {
    readonly nodeCode: string;
    readonly maturityDays: number;
    readonly method: string;
    readonly threshold: string | undefined;
  }[];
  readonly stopRule: Readonly<Record<string, string>>;
  readonly comparisonBasis: string;
  readonly crossPeriodWindowDays: number;
  readonly results: readonly EvaluationNodeResult[];
}

export interface ManualPacket {
  readonly id: string;
  readonly actionId: string;
  readonly executorUserId: string;
  readonly issuedAt: string;
  readonly expiresAt: string;
  readonly nativeListingKey: string;
  readonly targetText: string | undefined;
  readonly state: string;
  readonly reports: readonly {
    readonly id: string;
    readonly reporterUserId: string;
    readonly reportState: string;
    readonly note: string;
  }[];
  readonly verifications: readonly {
    readonly id: string;
    readonly verificationBasis: string;
    readonly managementMatch: string;
    readonly displayState: string;
  }[];
  readonly version: number;
}

export interface PromotionEngagement {
  readonly id: string;
  readonly platformListingId: string;
  readonly actionId: string | undefined;
  readonly engagementKind: string;
  readonly nativePromotionKey: string | undefined;
  readonly priceFreeze: boolean;
  readonly autoParticipation: boolean;
  readonly adopted: boolean;
  readonly exitReasonCode: string | undefined;
  readonly state: string;
  readonly version: number;
}

export interface Batch {
  readonly id: string;
  readonly storeId: string;
  readonly batchCode: string;
  readonly state: string;
  readonly members: readonly {
    readonly actionId: string;
    readonly membershipState: string;
    readonly sequenceNo: number;
    readonly actionState: string;
  }[];
  readonly version: number;
}

export interface Containment {
  readonly id: string;
  readonly scopeKind: string;
  readonly platformListingId: string | undefined;
  readonly causeClass: string;
  readonly causeOwnerRoleCode: string;
  readonly stoppedByUserId: string;
  readonly stoppedAt: string;
  readonly reason: string;
  readonly state: string;
  readonly attestations: readonly {
    readonly attestationKind: string;
    readonly actorUserId: string;
  }[];
}

export interface RecalculationEntry {
  readonly id: string;
  readonly triggerClass: string;
  readonly targetMinutes: number;
  readonly platformListingId: string;
  readonly state: string;
  readonly acceptedAt: string;
  readonly latencySeconds: number | undefined;
  readonly withinTarget: boolean;
}

type Row = Record<string, unknown>;

function row(body: unknown): Row | undefined {
  return typeof body === 'object' && body !== null ? (body as Row) : undefined;
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function number(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value !== '' && Number.isFinite(Number(value)))
    return Number(value);
  return undefined;
}

/** A decimal kept as text, so no money or ratio is rounded on the way to the screen. */
function decimalText(value: unknown): string | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  return text(value);
}

function bool(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined;
}

function strings(value: unknown): readonly string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
}

function stringMap(value: unknown): Readonly<Record<string, string>> {
  const source = row(value);
  const out: Record<string, string> = {};
  if (source === undefined) return out;
  for (const [key, entry] of Object.entries(source)) {
    if (typeof entry === 'string') out[key] = entry;
    else if (typeof entry === 'number' || typeof entry === 'boolean') out[key] = String(entry);
  }
  return out;
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

export function parseListingHealth(body: unknown): ListingHealth | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id),
    storeId = text(r.storeId),
    platformListingId = text(r.platformListingId),
    nativeListingKey = text(r.nativeListingKey),
    healthVersion = number(r.healthVersion),
    necessaryState = text(r.necessaryState),
    affectedSetState = text(r.affectedSetState),
    affectedVariantCount = number(r.affectedVariantCount),
    computedAt = text(r.computedAt);
  const necessaryConditions = list(r.necessaryConditions, (item) => {
    const c = row(item);
    const code = text(c?.code),
      state = text(c?.state),
      evidenceReference = text(c?.evidenceReference);
    return code === undefined || state === undefined || evidenceReference === undefined
      ? undefined
      : { code, state, evidenceReference };
  });
  const opportunities = list(r.opportunities, (item) => {
    const c = row(item);
    const code = text(c?.code),
      evidenceReference = text(c?.evidenceReference);
    return code === undefined || evidenceReference === undefined
      ? undefined
      : { code, evidenceReference };
  });
  const eligibility = stringMap(r.eligibility);
  if (
    id === undefined ||
    storeId === undefined ||
    platformListingId === undefined ||
    nativeListingKey === undefined ||
    healthVersion === undefined ||
    necessaryState === undefined ||
    affectedSetState === undefined ||
    affectedVariantCount === undefined ||
    computedAt === undefined ||
    necessaryConditions === undefined ||
    opportunities === undefined ||
    !('MEASUREMENT' in eligibility && 'PROTECTION' in eligibility && 'EVALUATION' in eligibility)
  )
    return undefined;
  return {
    id,
    storeId,
    platformListingId,
    nativeListingKey,
    healthVersion,
    necessaryConditions,
    necessaryState,
    eligibility,
    opportunities,
    affectedSetState,
    affectedVariantCount,
    sourceTime: text(r.sourceTime),
    acquisitionTime: text(r.acquisitionTime),
    computedAt,
  };
}

export function parseConversionMeasurement(body: unknown): ConversionMeasurement | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id),
    platformListingId = text(r.platformListingId),
    windowStart = text(r.windowStart),
    windowEnd = text(r.windowEnd),
    retentionWindowDays = number(r.retentionWindowDays),
    evidencePath = text(r.evidencePath),
    pathQualified = bool(r.pathQualified),
    ratioState = text(r.ratioState),
    maturityReached = bool(r.maturityReached),
    sourceStratified = bool(r.sourceStratified),
    computedAt = text(r.computedAt);
  if (
    id === undefined ||
    platformListingId === undefined ||
    windowStart === undefined ||
    windowEnd === undefined ||
    retentionWindowDays === undefined ||
    evidencePath === undefined ||
    pathQualified === undefined ||
    ratioState === undefined ||
    maturityReached === undefined ||
    sourceStratified === undefined ||
    computedAt === undefined
  )
    return undefined;
  return {
    id,
    platformListingId,
    windowStart,
    windowEnd,
    retentionWindowDays,
    evidencePath,
    pathQualified,
    qualificationReasonCodes: strings(r.qualificationReasonCodes),
    visitCount: number(r.visitCount),
    retainedPurchaseVisitCount: number(r.retainedPurchaseVisitCount),
    primaryRatio: decimalText(r.primaryRatio),
    ratioState,
    maturityReached,
    sourceStratified,
    sellableSplit: stringMap(r.sellableSplit),
    excludedTransitionDays: strings(r.excludedTransitionDays),
    computedAt,
  };
}

export function parseListingDetail(body: unknown): ListingDetail | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const listingId = text(r.listingId),
    storeId = text(r.storeId),
    platformCode = text(r.platformCode),
    nativeListingKey = text(r.nativeListingKey);
  const measurements = list(r.measurements, parseConversionMeasurement);
  const health =
    r.health === null || r.health === undefined ? undefined : parseListingHealth(r.health);
  if (
    listingId === undefined ||
    storeId === undefined ||
    platformCode === undefined ||
    nativeListingKey === undefined ||
    measurements === undefined ||
    (r.health !== null && r.health !== undefined && health === undefined)
  )
    return undefined;
  return { listingId, storeId, platformCode, nativeListingKey, health, measurements };
}

export function parseCandidate(body: unknown): Candidate | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id),
    storeId = text(r.storeId),
    platformListingId = text(r.platformListingId),
    candidateKind = text(r.candidateKind),
    comparisonRoundKey = text(r.comparisonRoundKey),
    state = text(r.state),
    version = number(r.version);
  if (
    id === undefined ||
    storeId === undefined ||
    platformListingId === undefined ||
    candidateKind === undefined ||
    comparisonRoundKey === undefined ||
    state === undefined ||
    version === undefined
  )
    return undefined;
  return {
    id,
    storeId,
    platformListingId,
    candidateKind,
    comparisonRoundKey,
    evidenceReferences: strings(r.evidenceReferences),
    expectedEffect: stringMap(r.expectedEffect),
    state,
    version,
  };
}

export function parseListingAction(body: unknown): ListingAction | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id),
    storeId = text(r.storeId),
    platformListingId = text(r.platformListingId),
    nativeListingKey = text(r.nativeListingKey),
    candidateId = text(r.candidateId),
    recommendationId = text(r.recommendationId),
    recommendationVersion = number(r.recommendationVersion),
    recommendationState = text(r.recommendationState),
    affectedSetDigest = text(r.affectedSetDigest),
    affectedSetState = text(r.affectedSetState),
    affectedVariantCount = number(r.affectedVariantCount),
    actionKind = text(r.actionKind),
    executionPath = text(r.executionPath),
    materialityRoute = text(r.materialityRoute),
    authorUserId = text(r.authorUserId),
    state = text(r.state),
    version = number(r.version);
  const reviews = list(r.reviews, (item) => {
    const c = row(item);
    const reviewId = text(c?.id),
      reviewerUserId = text(c?.reviewerUserId),
      verdict = text(c?.verdict),
      reason = text(c?.reason),
      reviewedAt = text(c?.reviewedAt);
    return reviewId === undefined ||
      reviewerUserId === undefined ||
      verdict === undefined ||
      reason === undefined ||
      reviewedAt === undefined
      ? undefined
      : { id: reviewId, reviewerUserId, verdict, reason, reviewedAt };
  });
  const occupations = list(r.occupations, (item) => {
    const c = row(item);
    const occupationId = text(c?.id),
      axisCode = text(c?.axisCode),
      requestedValue = decimalText(c?.requestedValue),
      occupiedValue = decimalText(c?.occupiedValue),
      occupationState = text(c?.state);
    return occupationId === undefined ||
      axisCode === undefined ||
      requestedValue === undefined ||
      occupiedValue === undefined ||
      occupationState === undefined
      ? undefined
      : {
          id: occupationId,
          axisCode,
          requestedValue,
          occupiedValue,
          state: occupationState,
          releaseBasis: text(c?.releaseBasis),
        };
  });
  const bindingRow = row(r.binding);
  const binding =
    bindingRow === undefined
      ? undefined
      : (() => {
          const bindingId = text(bindingRow.id),
            bindingState = text(bindingRow.state),
            expiresAt = text(bindingRow.expiresAt);
          return bindingId === undefined || bindingState === undefined || expiresAt === undefined
            ? undefined
            : {
                id: bindingId,
                state: bindingState,
                expiresAt,
                inapplicableReason: text(bindingRow.inapplicableReason),
              };
        })();
  const launchRow = row(r.launch);
  const launch =
    launchRow === undefined
      ? undefined
      : (() => {
          const launchId = text(launchRow.id),
            launchedByUserId = text(launchRow.launchedByUserId),
            launchedAt = text(launchRow.launchedAt);
          return launchId === undefined ||
            launchedByUserId === undefined ||
            launchedAt === undefined
            ? undefined
            : { id: launchId, launchedByUserId, launchedAt };
        })();
  if (
    id === undefined ||
    storeId === undefined ||
    platformListingId === undefined ||
    nativeListingKey === undefined ||
    candidateId === undefined ||
    recommendationId === undefined ||
    recommendationVersion === undefined ||
    recommendationState === undefined ||
    affectedSetDigest === undefined ||
    affectedSetState === undefined ||
    affectedVariantCount === undefined ||
    actionKind === undefined ||
    executionPath === undefined ||
    materialityRoute === undefined ||
    authorUserId === undefined ||
    state === undefined ||
    version === undefined ||
    reviews === undefined ||
    occupations === undefined ||
    (bindingRow !== undefined && binding === undefined) ||
    (launchRow !== undefined && launch === undefined)
  )
    return undefined;
  return {
    id,
    storeId,
    platformListingId,
    nativeListingKey,
    candidateId,
    recommendationId,
    recommendationVersion,
    recommendationState,
    affectedSetDigest,
    affectedSetState,
    affectedVariantCount,
    actionKind,
    executionPath,
    currentTextDigest: text(r.currentTextDigest),
    targetText: text(r.targetText),
    targetTextDigest: text(r.targetTextDigest),
    restoresCommandId: text(r.restoresCommandId),
    promotionTermsDigest: text(r.promotionTermsDigest),
    kizMarkedDeclared: bool(r.kizMarkedDeclared),
    materialityRoute,
    contentAxisMaterial: bool(r.contentAxisMaterial),
    exposureAxisMaterial: bool(r.exposureAxisMaterial),
    calibrationPackageId: text(r.calibrationPackageId),
    calibrationVersion: number(r.calibrationVersion),
    authorUserId,
    state,
    reviews,
    binding,
    launch,
    occupations,
    bindingGaps: strings(r.bindingGaps),
    version,
  };
}

export function parseAllowance(body: unknown): Allowance | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const platformListingId = text(r.platformListingId),
    resolved = bool(r.resolved);
  const axes = list(r.axes, (item) => {
    const c = row(item);
    const axisCode = text(c?.axisCode),
      scopeKind = text(c?.scopeKind),
      limitValue = decimalText(c?.limitValue),
      reserveValue = decimalText(c?.reserveValue),
      occupiedValue = decimalText(c?.occupiedValue),
      headroom = decimalText(c?.headroom),
      sufficient = bool(c?.sufficient),
      unitCode = text(c?.unitCode);
    return axisCode === undefined ||
      scopeKind === undefined ||
      limitValue === undefined ||
      reserveValue === undefined ||
      occupiedValue === undefined ||
      headroom === undefined ||
      sufficient === undefined ||
      unitCode === undefined
      ? undefined
      : {
          axisCode,
          scopeKind,
          limitValue,
          reserveValue,
          occupiedValue,
          requestedValue: decimalText(c?.requestedValue),
          headroom,
          sufficient,
          unitCode,
        };
  });
  if (platformListingId === undefined || resolved === undefined || axes === undefined)
    return undefined;
  return { platformListingId, axes, resolved, gaps: strings(r.gaps) };
}

export function parseLaunchAnswer(body: unknown): LaunchAnswer | undefined {
  const r = row(body);
  const launched = bool(r?.launched);
  if (r === undefined || launched === undefined) return undefined;
  return {
    launched,
    launchId: text(r.launchId),
    occupationIds: strings(r.occupationIds),
    insufficientAxes: strings(r.insufficientAxes),
  };
}

export function parseDescriptionCommand(body: unknown): DescriptionCommand | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id),
    actionId = text(r.actionId),
    state = text(r.state),
    priorTextCaptured = bool(r.priorTextCaptured),
    kizMarkedDeclared = bool(r.kizMarkedDeclared),
    equivalenceRule = text(r.equivalenceRule),
    attemptNo = number(r.attemptNo),
    retryBudgetRemaining = number(r.retryBudgetRemaining),
    approvalExpiresAt = text(r.approvalExpiresAt);
  const attempts = list(r.attempts, (item) => {
    const c = row(item);
    const attemptId = text(c?.id),
      no = number(c?.attemptNo),
      purpose = text(c?.purpose),
      outcomeClass = text(c?.outcomeClass);
    return attemptId === undefined ||
      no === undefined ||
      purpose === undefined ||
      outcomeClass === undefined
      ? undefined
      : { id: attemptId, attemptNo: no, purpose, outcomeClass, errorCode: text(c?.errorCode) };
  });
  const executionReceipts = list(r.executionReceipts, (item) => {
    const c = row(item);
    const receiptId = text(c?.id),
      executionState = text(c?.executionState),
      readbackId = text(c?.readbackId),
      recordedAt = text(c?.recordedAt),
      gaps = list(c?.gaps, text);
    if (
      receiptId === undefined ||
      readbackId === undefined ||
      recordedAt === undefined ||
      gaps === undefined ||
      (executionState !== 'MANAGEMENT_VERIFIED' && executionState !== 'NATIVE_COMPLETION_UNPROVEN')
    )
      return undefined;
    return {
      id: receiptId,
      executionState,
      readbackId,
      recordedAt,
      gaps,
      mutationAttemptId: text(c?.mutationAttemptId),
      nativeStatusAttemptId: text(c?.nativeStatusAttemptId),
      taskEventId: text(c?.taskEventId),
      taskRecordedAt: text(c?.taskRecordedAt),
    };
  });
  const readbacks = list(r.readbacks, (item) => {
    const c = row(item);
    const readbackId = text(c?.id),
      matchState = text(c?.matchState),
      observedAt = text(c?.observedAt);
    return readbackId === undefined || matchState === undefined || observedAt === undefined
      ? undefined
      : { id: readbackId, matchState, observedAt };
  });
  if (
    id === undefined ||
    actionId === undefined ||
    state === undefined ||
    priorTextCaptured === undefined ||
    kizMarkedDeclared === undefined ||
    equivalenceRule === undefined ||
    attemptNo === undefined ||
    retryBudgetRemaining === undefined ||
    approvalExpiresAt === undefined ||
    attempts === undefined ||
    readbacks === undefined ||
    executionReceipts === undefined
  )
    return undefined;
  return {
    id,
    actionId,
    state,
    priorTextCaptured,
    kizMarkedDeclared,
    equivalenceRule,
    attemptNo,
    retryBudgetRemaining,
    failureCode: text(r.failureCode),
    approvalExpiresAt,
    attempts,
    readbacks,
    executionReceipts,
  };
}

export function parseEvaluation(body: unknown): Evaluation | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const planId = text(r.planId),
    actionId = text(r.actionId),
    comparisonBasis = text(r.comparisonBasis),
    crossPeriodWindowDays = number(r.crossPeriodWindowDays);
  const formalNodes = list(r.formalNodes, (item) => {
    const c = row(item);
    const nodeCode = text(c?.nodeCode),
      maturityDays = number(c?.maturityDays),
      method = text(c?.method);
    return nodeCode === undefined || maturityDays === undefined || method === undefined
      ? undefined
      : { nodeCode, maturityDays, method, threshold: decimalText(c?.threshold) };
  });
  const results = list(r.results, (item) => {
    const c = row(item);
    const resultId = text(c?.id),
      nodeCode = text(c?.nodeCode),
      stage = text(c?.stage),
      revisionNo = number(c?.revisionNo),
      verdict = text(c?.verdict),
      protectionVerdict = text(c?.protectionVerdict);
    return resultId === undefined ||
      nodeCode === undefined ||
      stage === undefined ||
      revisionNo === undefined ||
      verdict === undefined ||
      protectionVerdict === undefined
      ? undefined
      : {
          id: resultId,
          nodeCode,
          stage,
          revisionNo,
          primaryRatio: decimalText(c?.primaryRatio),
          conservativeBound: decimalText(c?.conservativeBound),
          acceptedThreshold: decimalText(c?.acceptedThreshold),
          verdict,
          protectionVector: stringMap(c?.protectionVector),
          protectionVerdict,
          stopVerdict: text(c?.stopVerdict),
        };
  });
  if (
    planId === undefined ||
    actionId === undefined ||
    comparisonBasis === undefined ||
    crossPeriodWindowDays === undefined ||
    formalNodes === undefined ||
    results === undefined
  )
    return undefined;
  return {
    planId,
    actionId,
    formalNodes,
    stopRule: stringMap(r.stopRule),
    comparisonBasis,
    crossPeriodWindowDays,
    results,
  };
}

export function parseManualPacket(body: unknown): ManualPacket | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id),
    actionId = text(r.actionId),
    executorUserId = text(r.executorUserId),
    issuedAt = text(r.issuedAt),
    expiresAt = text(r.expiresAt),
    nativeListingKey = text(r.nativeListingKey),
    state = text(r.state),
    version = number(r.version);
  const reports = list(r.reports, (item) => {
    const c = row(item);
    const reportId = text(c?.id),
      reporterUserId = text(c?.reporterUserId),
      reportState = text(c?.reportState),
      note = text(c?.note);
    return reportId === undefined ||
      reporterUserId === undefined ||
      reportState === undefined ||
      note === undefined
      ? undefined
      : { id: reportId, reporterUserId, reportState, note };
  });
  const verifications = list(r.verifications, (item) => {
    const c = row(item);
    const verificationId = text(c?.id),
      verificationBasis = text(c?.verificationBasis),
      managementMatch = text(c?.managementMatch),
      displayState = text(c?.displayState);
    return verificationId === undefined ||
      verificationBasis === undefined ||
      managementMatch === undefined ||
      displayState === undefined
      ? undefined
      : { id: verificationId, verificationBasis, managementMatch, displayState };
  });
  if (
    id === undefined ||
    actionId === undefined ||
    executorUserId === undefined ||
    issuedAt === undefined ||
    expiresAt === undefined ||
    nativeListingKey === undefined ||
    state === undefined ||
    version === undefined ||
    reports === undefined ||
    verifications === undefined
  )
    return undefined;
  return {
    id,
    actionId,
    executorUserId,
    issuedAt,
    expiresAt,
    nativeListingKey,
    targetText: text(r.targetText),
    state,
    reports,
    verifications,
    version,
  };
}

export function parsePromotionEngagement(body: unknown): PromotionEngagement | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id),
    platformListingId = text(r.platformListingId),
    engagementKind = text(r.engagementKind),
    priceFreeze = bool(r.priceFreeze),
    autoParticipation = bool(r.autoParticipation),
    adopted = bool(r.adopted),
    state = text(r.state),
    version = number(r.version);
  if (
    id === undefined ||
    platformListingId === undefined ||
    engagementKind === undefined ||
    priceFreeze === undefined ||
    autoParticipation === undefined ||
    adopted === undefined ||
    state === undefined ||
    version === undefined
  )
    return undefined;
  return {
    id,
    platformListingId,
    actionId: text(r.actionId),
    engagementKind,
    nativePromotionKey: text(r.nativePromotionKey),
    priceFreeze,
    autoParticipation,
    adopted,
    exitReasonCode: text(r.exitReasonCode),
    state,
    version,
  };
}

export function parseBatch(body: unknown): Batch | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id),
    storeId = text(r.storeId),
    batchCode = text(r.batchCode),
    state = text(r.state),
    version = number(r.version);
  const members = list(r.members, (item) => {
    const c = row(item);
    const actionId = text(c?.actionId),
      membershipState = text(c?.membershipState),
      sequenceNo = number(c?.sequenceNo),
      actionState = text(c?.actionState);
    return actionId === undefined ||
      membershipState === undefined ||
      sequenceNo === undefined ||
      actionState === undefined
      ? undefined
      : { actionId, membershipState, sequenceNo, actionState };
  });
  if (
    id === undefined ||
    storeId === undefined ||
    batchCode === undefined ||
    state === undefined ||
    version === undefined ||
    members === undefined
  )
    return undefined;
  return { id, storeId, batchCode, state, members, version };
}

export function parseContainment(body: unknown): Containment | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id),
    scopeKind = text(r.scopeKind),
    causeClass = text(r.causeClass),
    causeOwnerRoleCode = text(r.causeOwnerRoleCode),
    stoppedByUserId = text(r.stoppedByUserId),
    stoppedAt = text(r.stoppedAt),
    reason = text(r.reason),
    state = text(r.state);
  const attestations = list(r.attestations, (item) => {
    const c = row(item);
    const attestationKind = text(c?.attestationKind),
      actorUserId = text(c?.actorUserId);
    return attestationKind === undefined || actorUserId === undefined
      ? undefined
      : { attestationKind, actorUserId };
  });
  if (
    id === undefined ||
    scopeKind === undefined ||
    causeClass === undefined ||
    causeOwnerRoleCode === undefined ||
    stoppedByUserId === undefined ||
    stoppedAt === undefined ||
    reason === undefined ||
    state === undefined ||
    attestations === undefined
  )
    return undefined;
  return {
    id,
    scopeKind,
    platformListingId: text(r.platformListingId),
    causeClass,
    causeOwnerRoleCode,
    stoppedByUserId,
    stoppedAt,
    reason,
    state,
    attestations,
  };
}

export function parseRecalculationEntry(body: unknown): RecalculationEntry | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id),
    triggerClass = text(r.triggerClass),
    targetMinutes = number(r.targetMinutes),
    platformListingId = text(r.platformListingId),
    state = text(r.state),
    acceptedAt = text(r.acceptedAt),
    withinTarget = bool(r.withinTarget);
  if (
    id === undefined ||
    triggerClass === undefined ||
    targetMinutes === undefined ||
    platformListingId === undefined ||
    state === undefined ||
    acceptedAt === undefined ||
    withinTarget === undefined
  )
    return undefined;
  return {
    id,
    triggerClass,
    targetMinutes,
    platformListingId,
    state,
    acceptedAt,
    latencySeconds: number(r.latencySeconds),
    withinTarget,
  };
}

const HEALTH = '/api/v1/console/listing/health';
const ACTIONS = '/api/v1/console/listing/actions';
const MANUAL = '/api/v1/console/listing/manual';
const GOVERNANCE = '/api/v1/console/listing/governance';
const COMMANDS = '/api/v1/console/listing-description-commands';

function post(body: unknown): RequestInit {
  return { method: 'POST', body: JSON.stringify(body) };
}

function id(value: string): string {
  return encodeURIComponent(value);
}

export function fetchHealthQueue(
  context: ConsoleRequest,
  necessaryState?: string,
): Promise<ConsoleOutcome<readonly ListingHealth[]>> {
  const filter = necessaryState === undefined ? '' : `&necessaryState=${id(necessaryState)}`;
  return request(context, `${HEALTH}/queue?limit=50${filter}`, (body) =>
    list(body, parseListingHealth),
  );
}

export function fetchListingDetail(
  context: ConsoleRequest,
  listingId: string,
): Promise<ConsoleOutcome<ListingDetail>> {
  return request(context, `${HEALTH}/listings/${id(listingId)}`, parseListingDetail);
}

export function recomputeHealth(
  context: ConsoleRequest,
  listingId: string,
): Promise<ConsoleOutcome<ListingHealth>> {
  return request(context, `${HEALTH}/listings/${id(listingId)}/recompute`, parseListingHealth, {
    method: 'POST',
  });
}

export function measureConversion(
  context: ConsoleRequest,
  listingId: string,
  windowStart: string,
  windowEnd: string,
  retentionDays: number,
  evidencePath: string,
): Promise<ConsoleOutcome<ConversionMeasurement>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/measurements`,
    parseConversionMeasurement,
    post({ windowStart, windowEnd, retentionDays, evidencePath }),
  );
}

function parseIdentifier(key: string): (body: unknown) => string | undefined {
  return (body) => text(row(body)?.[key]);
}

export function recordDescriptionFact(
  context: ConsoleRequest,
  listingId: string,
  descriptionText: string,
  kizMarkedDeclared: boolean | undefined,
  note: string,
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/facts/description`,
    parseIdentifier('observationId'),
    post({
      text: descriptionText,
      languageCode: 'ru',
      kizMarkedDeclared: kizMarkedDeclared ?? null,
      note,
    }),
  );
}

export function recordDisplayFact(
  context: ConsoleRequest,
  listingId: string,
  displayState: string,
  displayedText: string,
  evidenceReference: string,
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/facts/display`,
    parseIdentifier('observationId'),
    post({
      displayState,
      displayedText: displayedText === '' ? null : displayedText,
      evidenceReference,
    }),
  );
}

export function fetchActions(
  context: ConsoleRequest,
  state?: string,
): Promise<ConsoleOutcome<readonly ListingAction[]>> {
  const filter = state === undefined ? '' : `&state=${id(state)}`;
  return request(context, `${ACTIONS}?limit=50${filter}`, (body) => list(body, parseListingAction));
}

export function fetchAction(
  context: ConsoleRequest,
  actionId: string,
): Promise<ConsoleOutcome<ListingAction>> {
  return request(context, `${ACTIONS}/${id(actionId)}`, parseListingAction);
}

export function fetchCandidates(
  context: ConsoleRequest,
  listingId: string,
): Promise<ConsoleOutcome<readonly Candidate[]>> {
  return request(context, `${ACTIONS}/candidates?listingId=${id(listingId)}`, (body) =>
    list(body, parseCandidate),
  );
}

export function prepareCandidate(
  context: ConsoleRequest,
  listingId: string,
  candidateKind: string,
  roundKey: string,
  evidenceReferences: readonly string[],
): Promise<ConsoleOutcome<Candidate>> {
  return request(
    context,
    `${ACTIONS}/candidates`,
    parseCandidate,
    post({ listingId, candidateKind, roundKey, evidenceReferences, expectedEffect: {} }),
  );
}

export function prepareAction(
  context: ConsoleRequest,
  candidateId: string,
  executionPath: string,
  targetText: string,
  kizMarkedDeclared: boolean | undefined,
  exposureShare: string,
  restoresCommandId?: string,
  promotionTerms?: PromotionTerms,
): Promise<ConsoleOutcome<ListingAction>> {
  return request(
    context,
    `${ACTIONS}/candidates/${id(candidateId)}/prepare`,
    parseListingAction,
    post({
      executionPath,
      ...(promotionTerms === undefined ? {} : { promotionTerms }),
      targetText: restoresCommandId || promotionTerms ? null : targetText,
      restoresCommandId: restoresCommandId === '' ? null : (restoresCommandId ?? null),
      kizMarkedDeclared: kizMarkedDeclared ?? null,
      exposureShare: exposureShare === '' ? null : exposureShare,
      expectedEffect: {},
      riskLabel: 'LOW',
    }),
  );
}

export function reviewAction(
  context: ConsoleRequest,
  actionId: string,
  verdict: 'ATTESTED' | 'RETURNED',
  reason: string,
): Promise<ConsoleOutcome<ListingAction>> {
  return request(
    context,
    `${ACTIONS}/${id(actionId)}/review`,
    parseListingAction,
    post({ verdict, reason }),
  );
}

export function cancelAction(
  context: ConsoleRequest,
  actionId: string,
  reason: string,
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `${ACTIONS}/${id(actionId)}/cancel`,
    parseIdentifier('state'),
    post({ reason }),
  );
}

export function previewAllowance(
  context: ConsoleRequest,
  actionId: string,
): Promise<ConsoleOutcome<Allowance>> {
  return request(
    context,
    `${ACTIONS}/${id(actionId)}/allowance-preview`,
    parseAllowance,
    post({ axes: {} }),
  );
}

export function launchAction(
  context: ConsoleRequest,
  actionId: string,
): Promise<ConsoleOutcome<LaunchAnswer>> {
  return request(
    context,
    `${ACTIONS}/${id(actionId)}/launch`,
    parseLaunchAnswer,
    post({ axes: {} }),
  );
}

export function releaseOccupation(
  context: ConsoleRequest,
  occupationId: string,
  basis: string,
  evidenceId: string,
  evidenceReference: string,
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `${ACTIONS}/occupations/${id(occupationId)}/release`,
    parseIdentifier('state'),
    post({ basis, evidenceId, evidenceReference }),
  );
}

export function fetchEvaluation(
  context: ConsoleRequest,
  actionId: string,
): Promise<ConsoleOutcome<Evaluation>> {
  return request(context, `${ACTIONS}/${id(actionId)}/evaluation`, parseEvaluation);
}

export function fetchDescriptionCommand(
  context: ConsoleRequest,
  actionId: string,
): Promise<ConsoleOutcome<DescriptionCommand>> {
  return request(context, `${COMMANDS}/actions/${id(actionId)}`, parseDescriptionCommand);
}

export function fetchDescriptionGate(
  context: ConsoleRequest,
  commandId: string,
): Promise<ConsoleOutcome<readonly string[]>> {
  return request(context, `${COMMANDS}/${id(commandId)}/gate`, (body) => {
    const reasons = row(body)?.reasons;
    return Array.isArray(reasons) ? strings(reasons) : undefined;
  });
}

export function fetchMyPackets(
  context: ConsoleRequest,
): Promise<ConsoleOutcome<readonly ManualPacket[]>> {
  return request(context, `${MANUAL}/packets?limit=50`, (body) => list(body, parseManualPacket));
}

export function fetchActionPackets(
  context: ConsoleRequest,
  actionId: string,
): Promise<ConsoleOutcome<readonly ManualPacket[]>> {
  return request(context, `${MANUAL}/actions/${id(actionId)}/packets`, (body) =>
    list(body, parseManualPacket),
  );
}

export function issuePacket(
  context: ConsoleRequest,
  actionId: string,
  executorUserId: string,
): Promise<ConsoleOutcome<ManualPacket>> {
  return request(
    context,
    `${MANUAL}/actions/${id(actionId)}/packets`,
    parseManualPacket,
    post({ executorUserId }),
  );
}

export function reportPacket(
  context: ConsoleRequest,
  packetId: string,
  operationTime: string,
  reportState: string,
  note: string,
): Promise<ConsoleOutcome<ManualPacket>> {
  return request(
    context,
    `${MANUAL}/packets/${id(packetId)}/report`,
    parseManualPacket,
    post({ operationTime, reportState, note }),
  );
}

export function verifyPacket(
  context: ConsoleRequest,
  packetId: string,
  basis: string,
  managementMatch: string,
  displayState: string,
  note: string,
): Promise<ConsoleOutcome<ManualPacket>> {
  return request(
    context,
    `${MANUAL}/packets/${id(packetId)}/verify`,
    parseManualPacket,
    post({ basis, managementMatch, displayState, note }),
  );
}

export function fetchEngagements(
  context: ConsoleRequest,
  listingId: string,
): Promise<ConsoleOutcome<readonly PromotionEngagement[]>> {
  return request(context, `${MANUAL}/engagements?listingId=${id(listingId)}`, (body) =>
    list(body, parsePromotionEngagement),
  );
}

export function authorizeExit(
  context: ConsoleRequest,
  engagementId: string,
  reasonCode: string,
): Promise<ConsoleOutcome<PromotionEngagement>> {
  return request(
    context,
    `${MANUAL}/engagements/${id(engagementId)}/exit`,
    parsePromotionEngagement,
    post({ reasonCode }),
  );
}

export function releaseEngagement(
  context: ConsoleRequest,
  engagementId: string,
  releaseKind: string,
): Promise<ConsoleOutcome<PromotionEngagement>> {
  return request(
    context,
    `${MANUAL}/engagements/${id(engagementId)}/release`,
    parsePromotionEngagement,
    post({ releaseKind }),
  );
}

export function fetchBatches(context: ConsoleRequest): Promise<ConsoleOutcome<readonly Batch[]>> {
  return request(context, `${GOVERNANCE}/batches?limit=50`, (body) => list(body, parseBatch));
}

export function createBatch(
  context: ConsoleRequest,
  storeId: string,
  code: string,
): Promise<ConsoleOutcome<Batch>> {
  return request(context, `${GOVERNANCE}/batches`, parseBatch, post({ storeId, code }));
}

export function addBatchMember(
  context: ConsoleRequest,
  batchId: string,
  actionId: string,
): Promise<ConsoleOutcome<Batch>> {
  return request(
    context,
    `${GOVERNANCE}/batches/${id(batchId)}/members`,
    parseBatch,
    post({ actionId }),
  );
}

export function closeBatch(
  context: ConsoleRequest,
  batchId: string,
): Promise<ConsoleOutcome<Batch>> {
  return request(context, `${GOVERNANCE}/batches/${id(batchId)}/close`, parseBatch, {
    method: 'POST',
  });
}

export function fetchContainments(
  context: ConsoleRequest,
  activeOnly: boolean,
): Promise<ConsoleOutcome<readonly Containment[]>> {
  return request(
    context,
    `${GOVERNANCE}/containments?activeOnly=${String(activeOnly)}&limit=50`,
    (body) => list(body, parseContainment),
  );
}

export function stopScope(
  context: ConsoleRequest,
  scopeKind: 'LISTING' | 'ORGANIZATION',
  platformListingId: string | undefined,
  causeClass: string,
  causeOwnerRoleCode: string,
  reason: string,
  evidenceReference: string,
): Promise<ConsoleOutcome<Containment>> {
  return request(
    context,
    `${GOVERNANCE}/containments/stop`,
    parseContainment,
    post({
      scopeKind,
      platformListingId: platformListingId ?? null,
      causeClass,
      causeOwnerRoleCode,
      reason,
      evidenceReference,
    }),
  );
}

export function attestContainment(
  context: ConsoleRequest,
  containmentId: string,
  kind: string,
  evidenceReference: string,
): Promise<ConsoleOutcome<Containment>> {
  return request(
    context,
    `${GOVERNANCE}/containments/${id(containmentId)}/attest`,
    parseContainment,
    post({ kind, evidenceReference }),
  );
}

export function reenableContainment(
  context: ConsoleRequest,
  containmentId: string,
): Promise<ConsoleOutcome<Containment>> {
  return request(
    context,
    `${GOVERNANCE}/containments/${id(containmentId)}/reenable`,
    parseContainment,
    { method: 'POST' },
  );
}

export function fetchRecalculationQueue(
  context: ConsoleRequest,
): Promise<ConsoleOutcome<readonly RecalculationEntry[]>> {
  return request(context, `${GOVERNANCE}/recalculation-queue?limit=100`, (body) =>
    list(body, parseRecalculationEntry),
  );
}

/** Financial details are returned only through the current all-member scope check. */
export function fetchPromotionTerms(
  context: ConsoleRequest,
  actionId: string,
): Promise<ConsoleOutcome<PromotionTermsView>> {
  return request(context, `${ACTIONS}/${id(actionId)}/promotion-terms`, parsePromotionTermsView);
}

export function parsePromotionTermsView(body: unknown): PromotionTermsView | undefined {
  const r = row(body);
  const actionId = text(r?.actionId),
    digest = text(r?.digest),
    fullDisclosure = bool(r?.fullDisclosure);
  if (actionId === undefined || fullDisclosure === undefined) return undefined;
  if (!fullDisclosure) return { actionId, digest, fullDisclosure, terms: undefined };
  const p = row(r?.terms);
  // Historical actions have no invented declaration.
  if (p === undefined && digest === undefined)
    return { actionId, digest, fullDisclosure, terms: undefined };
  const engagementKind = text(p?.engagementKind),
    nativePromotionKey = text(p?.nativePromotionKey),
    termsEvidenceReference = text(p?.termsEvidenceReference),
    priceFreeze = bool(p?.priceFreeze),
    autoParticipation = bool(p?.autoParticipation);
  const exactMap = (value: unknown): Record<string, string> | undefined => {
    const object = row(value);
    if (object === undefined || Object.values(object).some((v) => typeof v !== 'string'))
      return undefined;
    return Object.fromEntries(Object.entries(object).map(([key, value]) => [key, value as string]));
  };
  const terms = exactMap(p?.terms),
    obligations = exactMap(p?.obligations);
  if (
    digest === undefined ||
    engagementKind === undefined ||
    nativePromotionKey === undefined ||
    termsEvidenceReference === undefined ||
    priceFreeze === undefined ||
    autoParticipation === undefined ||
    terms === undefined ||
    obligations === undefined
  )
    return undefined;
  return {
    actionId,
    digest,
    fullDisclosure,
    terms: {
      engagementKind,
      nativePromotionKey,
      termsEvidenceReference,
      priceFreeze,
      autoParticipation,
      terms,
      obligations,
    },
  };
}
