import type { ConsoleFailure, ConsoleOutcome, ConsoleRequest } from './console';
import { AI_REQUEST_TIMEOUT_MS, request } from './console';
import { parseAiExplanation } from './console';
import type { AiExplanation } from './console';

export type ListingAssistancePurpose =
  'HYPOTHESIS_COMPARISON' | 'RUSSIAN_DESCRIPTION' | 'SIMPLE_PROMOTION' | 'REVIEW_SUMMARY';
export function requestListingAssistance(
  context: ConsoleRequest,
  listingId: string,
  window: 'D7' | 'D14' | 'D30',
  purpose: ListingAssistancePurpose,
): Promise<ConsoleOutcome<AiExplanation>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/assistance`,
    parseAiExplanation,
    post({ window, purpose }),
    AI_REQUEST_TIMEOUT_MS,
  );
}
export function fetchListingAssistance(
  context: ConsoleRequest,
  listingId: string,
  invocationId: string,
): Promise<ConsoleOutcome<AiExplanation>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/assistance/${id(invocationId)}`,
    parseAiExplanation,
  );
}

export interface ListingFeedbackItem {
  readonly id: string;
  readonly listingId: string;
  readonly sourceIdentity: string;
  readonly rawObservationId: string;
  readonly originalPointer: string;
  readonly originalDigest: string;
  readonly observedAt: string;
  readonly acquiredAt: string;
}
export interface ListingFeedbackClassification {
  readonly id: string;
  readonly itemId: string;
  readonly revision: number;
  readonly themeCode: string;
  readonly qualificationState: string;
  readonly classifierVersion: string;
  readonly reason: string;
  readonly classifiedBy: string;
  readonly classifiedAt: string;
}
export interface ListingFeedbackOverview {
  readonly asOf: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly itemLimit: number;
  readonly themes: readonly {
    readonly themeCode: string;
    readonly qualificationState: string;
    readonly mentionCount: number;
  }[];
  readonly items: readonly ListingFeedbackItem[];
}
export interface ListingFeedbackDetail {
  readonly original: ListingFeedbackItem;
  readonly classifications: readonly ListingFeedbackClassification[];
}

function parseFeedbackItem(body: unknown): ListingFeedbackItem | undefined {
  const r = row(body);
  const id = text(r?.id),
    listingId = text(r?.listingId),
    sourceIdentity = text(r?.sourceIdentity),
    rawObservationId = text(r?.rawObservationId),
    originalPointer = text(r?.originalPointer),
    originalDigest = text(r?.originalDigest),
    observedAt = text(r?.observedAt),
    acquiredAt = text(r?.acquiredAt);
  return id === undefined ||
    listingId === undefined ||
    sourceIdentity === undefined ||
    rawObservationId === undefined ||
    originalPointer === undefined ||
    originalDigest === undefined ||
    observedAt === undefined ||
    acquiredAt === undefined
    ? undefined
    : {
        id,
        listingId,
        sourceIdentity,
        rawObservationId,
        originalPointer,
        originalDigest,
        observedAt,
        acquiredAt,
      };
}
export function parseFeedbackOverview(body: unknown): ListingFeedbackOverview | undefined {
  const r = row(body);
  const asOf = text(r?.asOf),
    periodStart = text(r?.periodStart),
    periodEnd = text(r?.periodEnd),
    itemLimit = number(r?.itemLimit);
  const items = list(r?.items, parseFeedbackItem);
  const themes = list(r?.themes, (value) => {
    const theme = row(value),
      themeCode = text(theme?.themeCode),
      qualificationState = text(theme?.qualificationState),
      mentionCount = number(theme?.mentionCount);
    return themeCode === undefined ||
      qualificationState === undefined ||
      mentionCount === undefined ||
      !Number.isSafeInteger(mentionCount) ||
      mentionCount < 0
      ? undefined
      : { themeCode, qualificationState, mentionCount };
  });
  return asOf === undefined ||
    periodStart === undefined ||
    periodEnd === undefined ||
    itemLimit === undefined ||
    items === undefined ||
    themes === undefined
    ? undefined
    : { asOf, periodStart, periodEnd, itemLimit, items, themes };
}
export function parseFeedbackDetail(body: unknown): ListingFeedbackDetail | undefined {
  const r = row(body),
    original = parseFeedbackItem(r?.original);
  const classifications = list(r?.classifications, (value) => {
    const c = row(value),
      id = text(c?.id),
      itemId = text(c?.itemId),
      revision = number(c?.revision),
      themeCode = text(c?.themeCode),
      qualificationState = text(c?.qualificationState),
      classifierVersion = text(c?.classifierVersion),
      reason = text(c?.reason),
      classifiedBy = text(c?.classifiedBy),
      classifiedAt = text(c?.classifiedAt);
    return id === undefined ||
      itemId === undefined ||
      revision === undefined ||
      !Number.isSafeInteger(revision) ||
      revision < 0 ||
      themeCode === undefined ||
      qualificationState === undefined ||
      classifierVersion === undefined ||
      reason === undefined ||
      classifiedBy === undefined ||
      classifiedAt === undefined
      ? undefined
      : {
          id,
          itemId,
          revision,
          themeCode,
          qualificationState,
          classifierVersion,
          reason,
          classifiedBy,
          classifiedAt,
        };
  });
  return original === undefined ||
    classifications === undefined ||
    classifications.some((c) => c.itemId !== original.id)
    ? undefined
    : { original, classifications };
}
export function fetchFeedbackOverview(
  context: ConsoleRequest,
  listingId: string,
  from: string,
  to: string,
): Promise<ConsoleOutcome<ListingFeedbackOverview>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/feedback?${new URLSearchParams({ from, to, limit: '50' }).toString()}`,
    parseFeedbackOverview,
  );
}
export function fetchFeedbackDetail(
  context: ConsoleRequest,
  listingId: string,
  itemId: string,
): Promise<ConsoleOutcome<ListingFeedbackDetail>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/feedback/${id(itemId)}`,
    parseFeedbackDetail,
  );
}
export function captureFeedbackSource(
  context: ConsoleRequest,
  listingId: string,
  source: {
    readonly rawObservationId: string;
    readonly itemPointer: string;
    readonly identityPointer: string;
    readonly listingPointer: string;
    readonly textPointer: string;
  },
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/feedback/sources`,
    (body) => text(row(body)?.itemId),
    post(source),
  );
}
export function classifyFeedback(
  context: ConsoleRequest,
  listingId: string,
  itemId: string,
  classification: Pick<
    ListingFeedbackClassification,
    'themeCode' | 'qualificationState' | 'classifierVersion' | 'reason'
  >,
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/feedback/${id(itemId)}/classifications`,
    (body) => text(row(body)?.classificationId),
    { method: 'POST', body: JSON.stringify(classification) },
  );
}

export interface ListingReviewCommand {
  readonly id: string;
  readonly state: string;
  readonly failureCode: string | undefined;
  readonly updatedAt: string;
}
export interface ListingReviewTask {
  readonly taskId: string;
  readonly causeCode: string;
  readonly lane: string;
  readonly taskState: string;
  readonly status: NonNullable<ListingResponsibility['status']>;
}
export interface ListingReviewAction {
  readonly action: ListingAction;
  readonly responsibility: NonNullable<ListingResponsibility['status']> | undefined;
  readonly evaluation: Evaluation | undefined;
  readonly command: ListingReviewCommand | undefined;
}
export interface ListingReviewReceipt {
  readonly id: string;
  readonly triggerClass: string;
  readonly targetMinutes: number;
  readonly state: string;
  readonly sourceTime: string | undefined;
  readonly acceptedAt: string;
  readonly startedAt: string | undefined;
  readonly finishedAt: string | undefined;
  readonly healthResultId: string | undefined;
  readonly measurementResultIds: readonly string[];
  readonly bindingAssessedCount: number | undefined;
  readonly bindingInvalidatedCount: number | undefined;
  readonly failureCode: string | undefined;
}
export interface ListingReviewRow {
  readonly lane: string;
  readonly health: ListingHealth;
  readonly responsibilities: readonly ListingReviewTask[];
  readonly actions: readonly ListingReviewAction[];
  readonly recalculation: ListingReviewReceipt | undefined;
}
export interface ListingReviewReading {
  readonly kind: 'CURRENT_QUEUE' | 'DAILY_ACTION_BRIEF' | 'WEEKLY_EVIDENCE_REVIEW';
  readonly periodStart: string;
  readonly asOf: string;
  readonly rows: readonly ListingReviewRow[];
}
export interface ListingOperationsReview {
  readonly asOf: string;
  readonly storeId: string;
  readonly timezone: string;
  readonly current: ListingReviewReading;
  readonly daily: ListingReviewReading;
  readonly weekly: ListingReviewReading;
}

function parseReviewStatus(
  value: unknown,
): NonNullable<ListingResponsibility['status']> | undefined {
  return parseListingResponsibility({ bound: true, status: value })?.status;
}
function parseReviewCommand(value: unknown): ListingReviewCommand | undefined {
  const r = row(value),
    commandId = text(r?.id),
    state = text(r?.state),
    updatedAt = text(r?.updatedAt);
  return commandId === undefined || state === undefined || updatedAt === undefined
    ? undefined
    : { id: commandId, state, failureCode: text(r?.failureCode), updatedAt };
}
function parseReviewReceipt(value: unknown): ListingReviewReceipt | undefined {
  const r = row(value),
    receiptId = text(r?.id),
    triggerClass = text(r?.triggerClass),
    targetMinutes = number(r?.targetMinutes),
    state = text(r?.state),
    acceptedAt = text(r?.acceptedAt),
    measurementResultIds = list(r?.measurementResultIds, text);
  if (
    receiptId === undefined ||
    triggerClass === undefined ||
    targetMinutes === undefined ||
    state === undefined ||
    acceptedAt === undefined ||
    measurementResultIds === undefined
  )
    return undefined;
  return {
    id: receiptId,
    triggerClass,
    targetMinutes,
    state,
    acceptedAt,
    measurementResultIds,
    sourceTime: text(r?.sourceTime),
    startedAt: text(r?.startedAt),
    finishedAt: text(r?.finishedAt),
    healthResultId: text(r?.healthResultId),
    bindingAssessedCount: number(r?.bindingAssessedCount),
    bindingInvalidatedCount: number(r?.bindingInvalidatedCount),
    failureCode: text(r?.failureCode),
  };
}
function parseReviewRow(value: unknown): ListingReviewRow | undefined {
  const r = row(value);
  if (r === undefined) return undefined;
  const lane = text(r.lane),
    health = parseListingHealth(r.health);
  const responsibilities = list<ListingReviewTask>(r.responsibilities, (entry) => {
    const task = row(entry),
      taskId = text(task?.taskId),
      causeCode = text(task?.causeCode),
      taskLane = text(task?.lane),
      taskState = text(task?.taskState),
      status = parseReviewStatus(task?.status);
    return taskId === undefined ||
      causeCode === undefined ||
      taskLane === undefined ||
      taskState === undefined ||
      status?.taskId !== taskId
      ? undefined
      : { taskId, causeCode, lane: taskLane, taskState, status };
  });
  const actions = list<ListingReviewAction>(r.actions, (entry) => {
    const item = row(entry);
    if (item === undefined) return undefined;
    const action = parseListingAction(item.action);
    const responsibility =
      item.responsibility === null || item.responsibility === undefined
        ? undefined
        : parseReviewStatus(item.responsibility);
    const evaluation =
      item.evaluation === null || item.evaluation === undefined
        ? undefined
        : parseEvaluation(item.evaluation);
    const command =
      item.command === null || item.command === undefined
        ? undefined
        : parseReviewCommand(item.command);
    if (
      action === undefined ||
      (item.responsibility !== null &&
        item.responsibility !== undefined &&
        responsibility === undefined) ||
      (item.evaluation !== null && item.evaluation !== undefined && evaluation === undefined) ||
      (item.command !== null && item.command !== undefined && command === undefined)
    )
      return undefined;
    return { action, responsibility, evaluation, command };
  });
  const recalculation =
    r.recalculation === null || r.recalculation === undefined
      ? undefined
      : parseReviewReceipt(r.recalculation);
  if (
    lane === undefined ||
    health === undefined ||
    responsibilities === undefined ||
    actions === undefined ||
    (r.recalculation !== null && r.recalculation !== undefined && recalculation === undefined) ||
    actions.some((a) => a.action.platformListingId !== health.platformListingId)
  )
    return undefined;
  return { lane, health, responsibilities, actions, recalculation };
}
function parseReviewReading(value: unknown): ListingReviewReading | undefined {
  const r = row(value),
    kind = text(r?.kind),
    periodStart = text(r?.periodStart),
    asOf = text(r?.asOf),
    rows = list(r?.rows, parseReviewRow);
  if (
    !['CURRENT_QUEUE', 'DAILY_ACTION_BRIEF', 'WEEKLY_EVIDENCE_REVIEW'].includes(kind ?? '') ||
    periodStart === undefined ||
    asOf === undefined ||
    rows === undefined
  )
    return undefined;
  return { kind: kind as ListingReviewReading['kind'], periodStart, asOf, rows };
}
export function parseListingOperationsReview(value: unknown): ListingOperationsReview | undefined {
  const r = row(value),
    asOf = text(r?.asOf),
    storeId = text(r?.storeId),
    timezone = text(r?.timezone),
    current = parseReviewReading(r?.current),
    daily = parseReviewReading(r?.daily),
    weekly = parseReviewReading(r?.weekly);
  if (
    asOf === undefined ||
    storeId === undefined ||
    timezone === undefined ||
    current === undefined ||
    daily === undefined ||
    weekly === undefined ||
    current.kind !== 'CURRENT_QUEUE' ||
    daily.kind !== 'DAILY_ACTION_BRIEF' ||
    weekly.kind !== 'WEEKLY_EVIDENCE_REVIEW' ||
    [current, daily, weekly].some((reading) => reading.asOf !== asOf)
  )
    return undefined;
  const identity = (reading: ListingReviewReading) =>
    reading.rows.map((item) => `${item.health.id}:${String(item.health.healthVersion)}`).join('|');
  if (identity(current) !== identity(daily) || identity(current) !== identity(weekly))
    return undefined;
  return { asOf, storeId, timezone, current, daily, weekly };
}
export function fetchListingOperationsReview(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<ListingOperationsReview>> {
  return request(
    context,
    `/api/v1/console/listing/operations-review?storeId=${id(storeId)}&limit=100`,
    parseListingOperationsReview,
  );
}

export interface ListingExperienceApplication {
  readonly id: string;
  readonly sourceActionId: string;
  readonly sourceResultId: string;
  readonly sourceListingId: string;
  readonly sourceNodeCode: string;
  readonly sourceStage: string;
  readonly sourceRevision: number;
  readonly sourceVerdict: string;
  readonly sourceProtectionVerdict: string;
  readonly sourceEvaluatedAt: string;
  readonly targetListingId: string;
  readonly targetAffectedSetDigest: string;
  readonly candidateKind: string;
  readonly applicabilityEvidenceReference: string;
  readonly recordedByUserId: string;
  readonly recordedAt: string;
  readonly applicabilityState: string;
}
export function parseListingExperienceApplication(
  value: unknown,
): ListingExperienceApplication | undefined {
  const r = row(value),
    idValue = text(r?.id),
    sourceActionId = text(r?.sourceActionId),
    sourceResultId = text(r?.sourceResultId),
    sourceListingId = text(r?.sourceListingId),
    sourceNodeCode = text(r?.sourceNodeCode),
    sourceStage = text(r?.sourceStage),
    sourceRevision = number(r?.sourceRevision),
    sourceVerdict = text(r?.sourceVerdict),
    sourceProtectionVerdict = text(r?.sourceProtectionVerdict),
    sourceEvaluatedAt = text(r?.sourceEvaluatedAt),
    targetListingId = text(r?.targetListingId),
    targetAffectedSetDigest = text(r?.targetAffectedSetDigest),
    candidateKind = text(r?.candidateKind),
    applicabilityEvidenceReference = text(r?.applicabilityEvidenceReference),
    recordedByUserId = text(r?.recordedByUserId),
    recordedAt = text(r?.recordedAt),
    applicabilityState = text(r?.applicabilityState);
  if (
    idValue === undefined ||
    sourceActionId === undefined ||
    sourceResultId === undefined ||
    sourceListingId === undefined ||
    sourceNodeCode === undefined ||
    sourceStage === undefined ||
    sourceRevision === undefined ||
    sourceVerdict === undefined ||
    sourceProtectionVerdict === undefined ||
    sourceEvaluatedAt === undefined ||
    targetListingId === undefined ||
    targetAffectedSetDigest === undefined ||
    candidateKind === undefined ||
    applicabilityEvidenceReference === undefined ||
    recordedByUserId === undefined ||
    recordedAt === undefined ||
    applicabilityState === undefined
  )
    return undefined;
  return {
    id: idValue,
    sourceActionId,
    sourceResultId,
    sourceListingId,
    sourceNodeCode,
    sourceStage,
    sourceRevision,
    sourceVerdict,
    sourceProtectionVerdict,
    sourceEvaluatedAt,
    targetListingId,
    targetAffectedSetDigest,
    candidateKind,
    applicabilityEvidenceReference,
    recordedByUserId,
    recordedAt,
    applicabilityState,
  };
}
export function fetchListingExperience(
  context: ConsoleRequest,
  targetListingId: string,
): Promise<ConsoleOutcome<readonly ListingExperienceApplication[]>> {
  return request(
    context,
    `/api/v1/console/listing/experience?targetListingId=${id(targetListingId)}`,
    (body) => list(body, parseListingExperienceApplication),
  );
}
export function recordListingExperience(
  context: ConsoleRequest,
  input: {
    readonly sourceActionId: string;
    readonly sourceResultId: string;
    readonly targetListingId: string;
    readonly candidateKind: string;
    readonly applicabilityEvidenceReference: string;
  },
): Promise<ConsoleOutcome<ListingExperienceApplication>> {
  return request(
    context,
    '/api/v1/console/listing/experience',
    parseListingExperienceApplication,
    post(input),
  );
}

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

/**
 * What an operator calls a listing: our product name when every mapped variant
 * agrees, the marketplace title otherwise, and the marketplace keys. Every
 * member may be absent; the console falls back to the native key then.
 */
export interface ListingIdentity {
  readonly platformCode: string | null;
  readonly nativeListingKey: string | null;
  readonly nativeProductKey: string | null;
  /** The marketplace title, Russian data. */
  readonly title: string | null;
  /** Our product, when every mapped variant belongs to the same one. */
  readonly productName: string | null;
  /** Distinct products mapped; 0 means unmapped. */
  readonly productCount: number;
}

export interface ListingHealth {
  /** Absent until the backend supplies it. */
  readonly identity?: ListingIdentity | undefined;
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
  readonly sourceTime?: string;
  readonly acquisitionTime?: string;
}

export interface ListingDiagnosticResponsibility {
  readonly causeCode: string;
  readonly status: NonNullable<ListingResponsibility['status']>;
}

export interface ListingDetail {
  readonly identity?: ListingIdentity | undefined;
  readonly listingId: string;
  readonly storeId: string;
  readonly platformCode: string;
  readonly nativeListingKey: string;
  readonly health: ListingHealth | undefined;
  readonly diagnosticResponsibilities?: readonly ListingDiagnosticResponsibility[];
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

export interface PromotionContextObservationInput {
  readonly coverageStart: string;
  readonly coverageEnd: string;
  readonly verificationExpiresAt: string;
  readonly records: readonly {
    readonly declaration: PromotionTerms;
    readonly participationState: string;
    readonly effectiveFrom: string;
    readonly effectiveTo: string;
    readonly newTransactionsState: string;
    readonly residualObligationState: string;
    readonly originalAuthorityReference: string | null;
    readonly originalAuthorityValidUntil: string | null;
    readonly axisDemands: Readonly<
      Record<
        string,
        {
          readonly value: string;
          readonly unitCode: string;
          readonly evidenceReference: string;
        }
      >
    >;
  }[];
}

export interface PromotionTermsView {
  readonly actionId: string;
  readonly digest: string | undefined;
  readonly fullDisclosure: boolean;
  readonly terms: PromotionTerms | undefined;
}

export type ListingActionPurpose =
  'LISTING_CONVERSION' | 'DESCRIPTION_CORRECTION' | 'BOUNDED_EXPLORATION' | 'PROMOTION';

export interface ListingPurposeBasis {
  readonly evidenceReference: string;
  readonly useConditions: readonly string[];
  readonly endConditions: readonly string[];
  readonly useUntil?: string | undefined;
}

function parsePurposeBasis(body: unknown): ListingPurposeBasis | undefined {
  const r = row(body),
    evidenceReference = text(r?.evidenceReference);
  const useConditions = list(r?.useConditions, text),
    endConditions = list(r?.endConditions, text);
  if (
    evidenceReference === undefined ||
    useConditions === undefined ||
    useConditions.length === 0 ||
    endConditions === undefined ||
    endConditions.length === 0
  )
    return undefined;
  return { evidenceReference, useConditions, endConditions, useUntil: text(r?.useUntil) };
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
  readonly purposeCode?: string | undefined;
  readonly purposeBasis?: ListingPurposeBasis | undefined;
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
  /** The description command created by an API-path launch, when there is one. */
  readonly commandId?: string | undefined;
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
  readonly qualificationGaps?: readonly string[];
  readonly measurementAcquiredAt?: string;
  readonly measurementComputedAt?: string;
  readonly fixedTrafficObservation?: {
    readonly state: string;
    readonly referenceStandardized: string | undefined;
    readonly targetStandardized: string | undefined;
    readonly observedDifference: string | undefined;
  };
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
  readonly planDigest?: string;
  readonly frozenAt?: string;
  readonly latestBoundary?: string;
  readonly revisions?: readonly {
    readonly id: string;
    readonly originalNodeResultId: string;
    readonly revisedNodeResultId: string;
    readonly revisionReason: string;
    readonly lateFactReference: string;
    readonly recordedAt: string;
  }[];
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
    /** When the reporter says the change was made; the evidence floor follows it. */
    readonly operationTime?: string;
    readonly reportedAt?: string;
    /** WITHIN_PACKET_AUTHORITY, LAWFUL_LATE_REPORT, UNAUTHORISED_DEVIATION or HISTORICAL_UNQUALIFIED. */
    readonly operationQualification?: string;
    readonly deviationReason?: string;
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
  readonly terms: Readonly<Record<string, string>>;
  readonly termsEvidenceReference: string | undefined;
  readonly priceFreeze: boolean;
  readonly autoParticipation: boolean;
  readonly adopted: boolean;
  readonly obligations: Readonly<Record<string, string>>;
  readonly fullDisclosure: boolean;
  readonly sourceContextObservationId: string | undefined;
  readonly originalAuthorityReference: string | undefined;
  readonly originalAuthorityValidUntil: string | undefined;
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

/** Read a listing identity leniently: anything malformed is simply absent. */
export function parseListingIdentity(body: unknown): ListingIdentity | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const optional = (value: unknown): string | null => text(value) ?? null;
  return {
    platformCode: optional(r.platformCode),
    nativeListingKey: optional(r.nativeListingKey),
    nativeProductKey: optional(r.nativeProductKey),
    title: optional(r.title),
    productName: optional(r.productName),
    productCount: number(r.productCount) ?? 0,
  };
}

function withListingIdentity(r: Row): { identity?: ListingIdentity } {
  const identity = parseListingIdentity(r.identity);
  return identity === undefined ? {} : { identity };
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
    ...withListingIdentity(r),
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
    computedAt = text(r.computedAt),
    sourceTime = text(r.sourceTime),
    acquisitionTime = text(r.acquisitionTime);
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
    ...(sourceTime === undefined ? {} : { sourceTime }),
    ...(acquisitionTime === undefined ? {} : { acquisitionTime }),
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
  const diagnosticResponsibilities =
    r.diagnosticResponsibilities === undefined
      ? []
      : list(r.diagnosticResponsibilities, (value): ListingDiagnosticResponsibility | undefined => {
          const entry = row(value),
            causeCode = text(entry?.causeCode);
          const parsed = parseListingResponsibility({ bound: true, status: entry?.status });
          return causeCode === undefined || parsed?.status === undefined
            ? undefined
            : { causeCode, status: parsed.status };
        });
  const measurements = list(r.measurements, parseConversionMeasurement);
  const health =
    r.health === null || r.health === undefined ? undefined : parseListingHealth(r.health);
  if (
    listingId === undefined ||
    storeId === undefined ||
    platformCode === undefined ||
    nativeListingKey === undefined ||
    measurements === undefined ||
    diagnosticResponsibilities === undefined ||
    (r.health !== null && r.health !== undefined && health === undefined)
  )
    return undefined;
  return {
    listingId,
    storeId,
    platformCode,
    nativeListingKey,
    health,
    measurements,
    diagnosticResponsibilities,
    ...withListingIdentity(row(body) ?? {}),
  };
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
  const purposeBasis = parsePurposeBasis(r.purposeBasis);
  if (r.purposeBasis !== undefined && r.purposeBasis !== null && purposeBasis === undefined)
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
    purposeCode: text(r.purposeCode),
    purposeBasis,
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
  const commandId = text(r.commandId);
  return {
    launched,
    launchId: text(r.launchId),
    occupationIds: strings(r.occupationIds),
    insufficientAxes: strings(r.insufficientAxes),
    ...(commandId === undefined ? {} : { commandId }),
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
  const planDigest = text(r.planDigest),
    frozenAt = text(r.frozenAt),
    latestBoundary = text(r.latestBoundary);
  const revisions =
    r.revisions === undefined
      ? []
      : list(r.revisions, (item) => {
          const revision = row(item);
          const id = text(revision?.id),
            originalNodeResultId = text(revision?.originalNodeResultId),
            revisedNodeResultId = text(revision?.revisedNodeResultId),
            revisionReason = text(revision?.revisionReason),
            lateFactReference = text(revision?.lateFactReference),
            recordedAt = text(revision?.recordedAt);
          return id === undefined ||
            originalNodeResultId === undefined ||
            revisedNodeResultId === undefined ||
            revisionReason === undefined ||
            lateFactReference === undefined ||
            recordedAt === undefined
            ? undefined
            : {
                id,
                originalNodeResultId,
                revisedNodeResultId,
                revisionReason,
                lateFactReference,
                recordedAt,
              };
        });
  if (revisions === undefined) return undefined;
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
    const evidence = row(c?.evaluationEvidence);
    const observation = row(evidence?.fixedTrafficObservation);
    const observationState = text(observation?.state);
    const acquiredAt = text(evidence?.measurementAcquiredAt);
    const computedAt = text(evidence?.measurementComputedAt);
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
          qualificationGaps: list(evidence?.qualificationGaps, text) ?? [],
          ...(acquiredAt === undefined ? {} : { measurementAcquiredAt: acquiredAt }),
          ...(computedAt === undefined ? {} : { measurementComputedAt: computedAt }),
          ...(observationState === undefined
            ? {}
            : {
                fixedTrafficObservation: {
                  state: observationState,
                  referenceStandardized: decimalText(observation?.referenceStandardized),
                  targetStandardized: decimalText(observation?.targetStandardized),
                  observedDifference: decimalText(observation?.observedDifference),
                },
              }),
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
    revisions,
    ...(planDigest === undefined ? {} : { planDigest }),
    ...(frozenAt === undefined ? {} : { frozenAt }),
    ...(latestBoundary === undefined ? {} : { latestBoundary }),
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
      note = text(c?.note),
      operationTime = text(c?.operationTime),
      reportedAt = text(c?.reportedAt),
      operationQualification = text(c?.operationQualification),
      deviationReason = text(c?.deviationReason);
    return reportId === undefined ||
      reporterUserId === undefined ||
      reportState === undefined ||
      note === undefined
      ? undefined
      : {
          id: reportId,
          reporterUserId,
          reportState,
          note,
          ...(operationTime === undefined ? {} : { operationTime }),
          ...(reportedAt === undefined ? {} : { reportedAt }),
          ...(operationQualification === undefined ? {} : { operationQualification }),
          ...(deviationReason === undefined ? {} : { deviationReason }),
        };
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
    fullDisclosure = bool(r.fullDisclosure),
    state = text(r.state),
    version = number(r.version);
  if (
    id === undefined ||
    platformListingId === undefined ||
    engagementKind === undefined ||
    priceFreeze === undefined ||
    autoParticipation === undefined ||
    adopted === undefined ||
    fullDisclosure === undefined ||
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
    terms: stringMap(r.terms),
    termsEvidenceReference: text(r.termsEvidenceReference),
    priceFreeze,
    autoParticipation,
    adopted,
    obligations: stringMap(r.obligations),
    fullDisclosure,
    sourceContextObservationId: text(r.sourceContextObservationId),
    originalAuthorityReference: text(r.originalAuthorityReference),
    originalAuthorityValidUntil: text(r.originalAuthorityValidUntil),
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

export interface NativeScopeCapture {
  readonly scopeKind: string;
  readonly nativeScopeKey: string;
  readonly nativeVariantKeys: readonly string[];
  readonly coverageState: string;
  readonly expectedMemberCount: number | null;
  readonly continuationReference: string | null;
  readonly sourceReference: string;
  readonly scopeBasisReference: string;
  readonly observedAt: string;
  readonly verificationExpiresAt: string;
}

export function recordNativeScope(
  context: ConsoleRequest,
  listingId: string,
  capture: NativeScopeCapture,
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/facts/native-scope`,
    parseIdentifier('observationId'),
    post(capture),
  );
}

export function recordPromotionFact(
  context: ConsoleRequest,
  listingId: string,
  declaration: PromotionTerms | null,
  participationState: string,
  observedAt: string,
  evidenceReference: string,
  nativeIdentity?: { engagementKind: string; nativePromotionKey: string },
  promotionContext?: PromotionContextObservationInput,
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/facts/promotion`,
    parseIdentifier('observationId'),
    post({
      declaration,
      engagementKind: declaration?.engagementKind ?? nativeIdentity?.engagementKind,
      nativePromotionKey: declaration?.nativePromotionKey ?? nativeIdentity?.nativePromotionKey,
      participationState,
      observedAt,
      evidenceReference,
      context: promotionContext,
    }),
  );
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
  return fetchActionsBy(context, state === undefined ? {} : { state });
}

/** Narrowing of the action list; the server's order is kept. */
export interface ActionQuery {
  readonly state?: string;
  readonly listingId?: string;
  readonly executionPath?: 'API' | 'MANUAL';
  readonly limit?: number;
}

/**
 * Actions filtered by state, listing and execution path. The rows are also
 * narrowed here, because an older backend ignores parameters it does not know.
 */
export async function fetchActionsBy(
  context: ConsoleRequest,
  query: ActionQuery,
): Promise<ConsoleOutcome<readonly ListingAction[]>> {
  const params = new URLSearchParams({ limit: String(query.limit ?? 50) });
  if (query.state !== undefined) params.set('state', query.state);
  if (query.listingId !== undefined) params.set('listingId', query.listingId);
  if (query.executionPath !== undefined) params.set('executionPath', query.executionPath);
  const outcome = await request(context, `${ACTIONS}?${params.toString()}`, (body) =>
    list(body, parseListingAction),
  );
  if (!outcome.ok) return outcome;
  return {
    ok: true,
    value: outcome.value.filter(
      (action) =>
        (query.state === undefined || action.state === query.state) &&
        (query.listingId === undefined || action.platformListingId === query.listingId) &&
        (query.executionPath === undefined || action.executionPath === query.executionPath),
    ),
  };
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

export interface PromotionSimulation {
  readonly id: string;
  readonly candidateId: string;
  readonly inputsDigest: string | undefined;
  readonly computedAt: string;
  readonly inverseState: string;
  readonly qualificationState: string;
  readonly purposeCode: string | undefined;
  readonly scenarios: readonly {
    readonly scenarioCode: string;
    readonly state: string;
    readonly quantity: string | undefined;
    readonly netRevenue: string | undefined;
    readonly contributionProfit: string | undefined;
    readonly missingInputs: readonly string[];
  }[];
}
export function parsePromotionSimulation(body: unknown): PromotionSimulation | undefined {
  const r = row(body),
    simulationId = text(r?.id),
    candidateId = text(r?.candidateId),
    computedAt = text(r?.computedAt),
    inverseState = text(r?.inverseState),
    snapshot = row(r?.inputSnapshot);
  const scenarios = list<PromotionSimulation['scenarios'][number]>(r?.scenarios, (item) => {
    const s = row(item),
      scenarioCode = text(s?.scenarioCode),
      state = text(s?.state);
    return scenarioCode === undefined || state === undefined
      ? undefined
      : {
          scenarioCode,
          state,
          quantity: decimalText(s?.quantity),
          netRevenue: decimalText(s?.netRevenue),
          contributionProfit: decimalText(s?.contributionProfit),
          missingInputs: strings(s?.missingInputs),
        };
  });
  if (
    simulationId === undefined ||
    candidateId === undefined ||
    computedAt === undefined ||
    inverseState === undefined ||
    scenarios === undefined
  )
    return undefined;
  return {
    id: simulationId,
    candidateId,
    computedAt,
    inverseState,
    inputsDigest: text(r?.inputsDigest),
    scenarios,
    qualificationState:
      text(snapshot?.qualificationState) ?? text(r?.qualificationState) ?? 'UNQUALIFIED',
    purposeCode: text(snapshot?.purposeCode),
  };
}
export function fetchCandidateSimulations(
  context: ConsoleRequest,
  candidateId: string,
): Promise<ConsoleOutcome<readonly PromotionSimulation[]>> {
  return request(context, `${ACTIONS}/candidates/${id(candidateId)}/simulations`, (body) =>
    list(body, parsePromotionSimulation),
  );
}
export interface PromotionSimulationRequest {
  readonly listPrice: string;
  readonly sellerDiscountRate: string | null;
  readonly discountAlreadyInNetRevenue: boolean;
  readonly unitCost: string | null;
  readonly stepFees: readonly { readonly priceFloor: string; readonly feePerUnit: string }[];
  readonly feesKnown: boolean;
  readonly scenarios: readonly {
    readonly code: string;
    readonly quantity: string;
    readonly necessary: boolean;
    readonly conservative: boolean;
  }[];
  readonly referenceProfitLine: string | null;
  readonly currencyCode: string;
  readonly expenses: null | {
    readonly fixedPromotionFee: { readonly amount: string; readonly currencyCode: string } | null;
    readonly returnLossPerUnit: { readonly amount: string; readonly currencyCode: string } | null;
    readonly advertisingPerUnit: { readonly amount: string; readonly currencyCode: string } | null;
    readonly variableTaxPerUnit: { readonly amount: string; readonly currencyCode: string } | null;
  };
  readonly context: {
    readonly periodStart: string;
    readonly periodEnd: string;
    readonly sourceReferences: Readonly<Record<string, string>>;
    readonly assumptions: string;
    readonly commercialDeclaration: PromotionTerms;
  };
}
export function simulatePromotionCandidate(
  context: ConsoleRequest,
  candidateId: string,
  requestBody: PromotionSimulationRequest,
  purpose: 'PROMOTION' | 'BOUNDED_EXPLORATION',
): Promise<ConsoleOutcome<PromotionSimulation>> {
  return request(
    context,
    `${ACTIONS}/candidates/${id(candidateId)}/simulate`,
    parsePromotionSimulation,
    post({ ...requestBody, purpose }),
  );
}

export function prepareAction(
  context: ConsoleRequest,
  candidateId: string,
  executionPath: string,
  targetText: string,
  kizMarkedDeclared: boolean | undefined,
  restoresCommandId?: string,
  promotionTerms?: PromotionTerms,
  purpose?: ListingActionPurpose,
  purposeBasis?: ListingPurposeBasis,
  simulationId?: string,
): Promise<ConsoleOutcome<ListingAction>> {
  return request(
    context,
    `${ACTIONS}/candidates/${id(candidateId)}/prepare`,
    parseListingAction,
    post({
      executionPath,
      ...(promotionTerms === undefined ? {} : { promotionTerms }),
      ...(purpose === undefined ? {} : { purpose }),
      ...(purposeBasis === undefined ? {} : { purposeBasis }),
      ...(simulationId === undefined ? {} : { simulationId }),
      targetText: restoresCommandId || promotionTerms ? null : targetText,
      restoresCommandId: restoresCommandId === '' ? null : (restoresCommandId ?? null),
      kizMarkedDeclared: kizMarkedDeclared ?? null,
      expectedEffect: {},
      riskLabel: 'LOW',
    }),
  );
}

export interface MeaningAssessment {
  readonly model: 'LC_MEANING_REVIEW_1';
  readonly basisDigest: string;
  readonly complete: boolean;
  readonly evidenceReference: string;
  readonly answers: readonly {
    readonly code: string;
    readonly state: 'APPLIES' | 'DOES_NOT_APPLY' | 'UNKNOWN';
    readonly reason: string;
  }[];
}

export interface ReviewSimulation {
  readonly id: string;
  readonly inputsDigest: string;
  readonly computedAt: string;
  readonly currency: string | undefined;
  readonly periodStart: string | undefined;
  readonly periodEnd: string | undefined;
  readonly referenceProfitLine: string | undefined;
  readonly inverseMinimumQuantity: string | undefined;
  readonly inverseState: string;
  readonly qualificationState: string;
  readonly conditionalScenariosPassed: boolean | undefined;
  readonly inputEvidence: string;
  readonly scenarios: readonly {
    readonly code: string;
    readonly state: string;
    readonly quantity: string | undefined;
    readonly netRevenue: string | undefined;
    readonly contributionProfit: string | undefined;
    readonly missingInputs: readonly string[];
  }[];
}

function parseReviewSimulation(body: unknown): ReviewSimulation | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id),
    inputsDigest = text(r.inputsDigest),
    computedAt = text(r.computedAt),
    inverseState = text(r.inverseState),
    qualificationState = text(r.qualificationState),
    inputEvidence = text(r.inputEvidence);
  const scenarios = list<ReviewSimulation['scenarios'][number]>(r.scenarios, (item) => {
    const value = row(item),
      code = text(value?.code),
      state = text(value?.state);
    if (
      value === undefined ||
      code === undefined ||
      state === undefined ||
      !Array.isArray(value.missingInputs) ||
      value.missingInputs.some((gap) => typeof gap !== 'string')
    )
      return undefined;
    for (const field of ['quantity', 'netRevenue', 'contributionProfit'])
      if (
        value[field] !== null &&
        value[field] !== undefined &&
        (typeof value[field] !== 'string' || !/^-?\d+(?:\.\d+)?$/.test(value[field]))
      )
        return undefined;
    return {
      code,
      state,
      quantity: text(value.quantity),
      netRevenue: text(value.netRevenue),
      contributionProfit: text(value.contributionProfit),
      missingInputs: strings(value.missingInputs),
    };
  });
  if (
    id === undefined ||
    inputsDigest === undefined ||
    computedAt === undefined ||
    inverseState === undefined ||
    qualificationState === undefined ||
    inputEvidence === undefined ||
    scenarios === undefined
  )
    return undefined;
  for (const field of ['referenceProfitLine', 'inverseMinimumQuantity'])
    if (
      r[field] !== null &&
      r[field] !== undefined &&
      (typeof r[field] !== 'string' || !/^-?\d+(?:\.\d+)?$/.test(r[field]))
    )
      return undefined;
  return {
    id,
    inputsDigest,
    computedAt,
    inverseState,
    qualificationState,
    inputEvidence,
    scenarios,
    currency: text(r.currency),
    periodStart: text(r.periodStart),
    periodEnd: text(r.periodEnd),
    referenceProfitLine: text(r.referenceProfitLine),
    inverseMinimumQuantity: text(r.inverseMinimumQuantity),
    conditionalScenariosPassed: bool(r.conditionalScenariosPassed),
  };
}

export interface MeaningReviewBasis {
  readonly selectedSimulation?: ReviewSimulation | undefined;
  readonly purposeBasis?: ListingPurposeBasis | undefined;
  readonly currentDescription?:
    | {
        readonly observationId: string;
        readonly textDigest: string;
        readonly text: string;
        readonly languageCode: string;
        readonly kizMarkedDeclared?: boolean | undefined;
        readonly observedAt: string;
        readonly acquiredAt: string;
        readonly sourceKind: string;
      }
    | undefined;
  readonly affectedSet: {
    readonly affectedSetId: string;
    readonly digest: string;
    readonly state: string;
    readonly listingVariantIds: readonly string[];
    readonly productVariantIds: readonly string[];
    readonly nativeScopeObservationId?: string | undefined;
    readonly identityLineage?: string | undefined;
  };
  readonly reviewEvidence?:
    | {
        readonly reviewerUserId: string;
        readonly verdict: string;
        readonly reason: string;
        readonly reviewedAt: string;
        readonly factsDigest: string;
        readonly evaluationPlanDigest?: string | undefined;
        readonly purposeBasisDigest?: string | undefined;
        readonly meaningAssessment?: string | undefined;
        readonly exposureEvidence?: string | undefined;
        readonly contentAxisMaterial?: boolean | undefined;
        readonly exposureAxisMaterial?: boolean | undefined;
        readonly materialityRoute?: string | undefined;
      }
    | undefined;
  readonly calibrationEvidence: Readonly<Record<string, string>>;
  readonly materialityEvidence: Readonly<Record<string, string>>;
  readonly businessProtectionEvidence: Readonly<Record<string, string>>;
  readonly authorityDocument: string;
  readonly applicableExperience: readonly {
    readonly experienceApplicationId: string;
    readonly sourceActionId: string;
    readonly sourceResultId: string;
    readonly sourceListingId: string;
    readonly sourceNodeCode: string;
    readonly sourceStage: string;
    readonly sourceRevision: number;
    readonly targetAffectedSetDigest: string;
    readonly candidateKind: string;
    readonly applicabilityEvidenceReference: string;
  }[];
  readonly actionId: string;
  readonly basisDigest: string;
  readonly ruleState: string;
  readonly currentText: string | undefined;
  readonly targetText: string | undefined;
  readonly promotionTerms: PromotionTerms | undefined;
  readonly conditions: readonly {
    readonly code: string;
    readonly condition: string;
    readonly axis: 'ORDINARY' | 'MATERIAL';
  }[];
}

export function parseMeaningReviewBasis(body: unknown): MeaningReviewBasis | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const actionId = text(r.actionId),
    basisDigest = text(r.basisDigest),
    ruleState = text(r.ruleState);
  const conditions = list<MeaningReviewBasis['conditions'][number]>(r.conditions, (item) => {
    const c = row(item);
    const code = text(c?.code),
      condition = text(c?.condition),
      axis = text(c?.axis);
    if (
      code === undefined ||
      condition === undefined ||
      (axis !== 'ORDINARY' && axis !== 'MATERIAL')
    )
      return undefined;
    return { code, condition, axis };
  });
  if (
    actionId === undefined ||
    basisDigest === undefined ||
    ruleState === undefined ||
    conditions === undefined
  )
    return undefined;
  if (new Set(conditions.map((c) => c.code)).size !== conditions.length) return undefined;
  let promotionTerms: PromotionTerms | undefined;
  if (r.promotionTerms !== undefined && r.promotionTerms !== null) {
    const parsed = parsePromotionTermsView({
      actionId,
      digest: basisDigest,
      fullDisclosure: true,
      terms: r.promotionTerms,
    });
    if (parsed?.terms === undefined) return undefined;
    promotionTerms = parsed.terms;
  }
  const selectedSimulation =
    r.selectedSimulation === null || r.selectedSimulation === undefined
      ? undefined
      : parseReviewSimulation(r.selectedSimulation);
  if (
    r.selectedSimulation !== null &&
    r.selectedSimulation !== undefined &&
    selectedSimulation === undefined
  )
    return undefined;
  const purposeBasis = parsePurposeBasis(r.purposeBasis);
  if (r.purposeBasis !== undefined && r.purposeBasis !== null && purposeBasis === undefined)
    return undefined;
  const descriptionRow = row(r.currentDescription);
  let currentDescription: MeaningReviewBasis['currentDescription'];
  if (r.currentDescription !== undefined && r.currentDescription !== null) {
    const observationId = text(descriptionRow?.observationId),
      textDigest = text(descriptionRow?.textDigest),
      descriptionText = text(descriptionRow?.text),
      languageCode = text(descriptionRow?.languageCode),
      observedAt = text(descriptionRow?.observedAt),
      acquiredAt = text(descriptionRow?.acquiredAt),
      sourceKind = text(descriptionRow?.sourceKind),
      kizMarkedDeclared = bool(descriptionRow?.kizMarkedDeclared);
    if (
      observationId === undefined ||
      textDigest === undefined ||
      descriptionText === undefined ||
      languageCode === undefined ||
      observedAt === undefined ||
      acquiredAt === undefined ||
      sourceKind === undefined ||
      (descriptionRow?.kizMarkedDeclared !== null &&
        descriptionRow?.kizMarkedDeclared !== undefined &&
        kizMarkedDeclared === undefined)
    )
      return undefined;
    currentDescription = {
      observationId,
      textDigest,
      text: descriptionText,
      languageCode,
      observedAt,
      acquiredAt,
      sourceKind,
      kizMarkedDeclared,
    };
  }
  const affectedRow = row(r.affectedSet),
    affectedSetId = text(affectedRow?.affectedSetId),
    affectedDigest = text(affectedRow?.digest),
    affectedState = text(affectedRow?.state),
    listingVariantIds = list(affectedRow?.listingVariantIds, text),
    productVariantIds = list(affectedRow?.productVariantIds, text),
    nativeScopeObservationId = text(affectedRow?.nativeScopeObservationId),
    identityLineage = text(affectedRow?.identityLineage);
  if (
    affectedSetId === undefined ||
    affectedDigest === undefined ||
    affectedState === undefined ||
    listingVariantIds === undefined ||
    productVariantIds === undefined ||
    listingVariantIds.length === 0 ||
    productVariantIds.length === 0
  )
    return undefined;
  const reviewRow = row(r.reviewEvidence);
  let reviewEvidence: MeaningReviewBasis['reviewEvidence'];
  if (r.reviewEvidence !== undefined && r.reviewEvidence !== null) {
    const reviewerUserId = text(reviewRow?.reviewerUserId),
      verdict = text(reviewRow?.verdict),
      reviewReason = text(reviewRow?.reason),
      reviewedAt = text(reviewRow?.reviewedAt),
      factsDigest = text(reviewRow?.factsDigest);
    if (
      reviewerUserId === undefined ||
      verdict === undefined ||
      reviewReason === undefined ||
      reviewedAt === undefined ||
      factsDigest === undefined
    )
      return undefined;
    reviewEvidence = {
      reviewerUserId,
      verdict,
      reason: reviewReason,
      reviewedAt,
      factsDigest,
      evaluationPlanDigest: text(reviewRow?.evaluationPlanDigest),
      purposeBasisDigest: text(reviewRow?.purposeBasisDigest),
      meaningAssessment: text(reviewRow?.meaningAssessment),
      exposureEvidence: text(reviewRow?.exposureEvidence),
      contentAxisMaterial: bool(reviewRow?.contentAxisMaterial),
      exposureAxisMaterial: bool(reviewRow?.exposureAxisMaterial),
      materialityRoute: text(reviewRow?.materialityRoute),
    };
  }
  const applicableExperience = list(r.applicableExperience, (value) => {
    const item = row(value),
      experienceApplicationId = text(item?.experienceApplicationId),
      sourceActionId = text(item?.sourceActionId),
      sourceResultId = text(item?.sourceResultId),
      sourceListingId = text(item?.sourceListingId),
      sourceNodeCode = text(item?.sourceNodeCode),
      sourceStage = text(item?.sourceStage),
      sourceRevision = number(item?.sourceRevision),
      targetAffectedSetDigest = text(item?.targetAffectedSetDigest),
      candidateKind = text(item?.candidateKind),
      applicabilityEvidenceReference = text(item?.applicabilityEvidenceReference);
    return experienceApplicationId === undefined ||
      sourceActionId === undefined ||
      sourceResultId === undefined ||
      sourceListingId === undefined ||
      sourceNodeCode === undefined ||
      sourceStage === undefined ||
      sourceRevision === undefined ||
      !Number.isSafeInteger(sourceRevision) ||
      sourceRevision < 0 ||
      targetAffectedSetDigest === undefined ||
      candidateKind === undefined ||
      applicabilityEvidenceReference === undefined
      ? undefined
      : {
          experienceApplicationId,
          sourceActionId,
          sourceResultId,
          sourceListingId,
          sourceNodeCode,
          sourceStage,
          sourceRevision,
          targetAffectedSetDigest,
          candidateKind,
          applicabilityEvidenceReference,
        };
  });
  const authorityDocument = text(r.authorityDocument);
  if (
    applicableExperience === undefined ||
    authorityDocument === undefined ||
    row(r.calibrationEvidence) === undefined ||
    row(r.materialityEvidence) === undefined ||
    row(r.businessProtectionEvidence) === undefined
  )
    return undefined;
  return {
    selectedSimulation,
    purposeBasis,
    currentDescription,
    affectedSet: {
      affectedSetId,
      digest: affectedDigest,
      state: affectedState,
      listingVariantIds,
      productVariantIds,
      nativeScopeObservationId,
      identityLineage,
    },
    reviewEvidence,
    calibrationEvidence: stringMap(r.calibrationEvidence),
    materialityEvidence: stringMap(r.materialityEvidence),
    businessProtectionEvidence: stringMap(r.businessProtectionEvidence),
    authorityDocument,
    applicableExperience,
    actionId,
    basisDigest,
    ruleState,
    currentText: text(r.currentText),
    targetText: text(r.targetText),
    promotionTerms,
    conditions,
  };
}

export function fetchMeaningReviewBasis(
  context: ConsoleRequest,
  actionId: string,
): Promise<ConsoleOutcome<MeaningReviewBasis>> {
  return request(context, `${ACTIONS}/${id(actionId)}/review-basis`, parseMeaningReviewBasis);
}

export interface ListingTaskDeferral {
  readonly id: string;
  readonly minutes: number;
  readonly reason: string;
  readonly requestedAt: string;
  readonly expiresAt: string;
  readonly state: 'ACTIVE' | 'REVIEW_DUE' | 'EXPIRED' | 'INVALIDATED';
  readonly reviewHealthId: string | undefined;
}

export type ListingDeferralTarget =
  | { readonly kind: 'ACTION'; readonly actionId: string }
  | { readonly kind: 'DIAGNOSTIC'; readonly listingId: string; readonly taskId: string };

export function parseListingTaskDeferral(body: unknown): ListingTaskDeferral | undefined {
  const r = row(body),
    deferralId = text(r?.id),
    minutes = number(r?.minutes),
    reason = text(r?.reason),
    requestedAt = text(r?.requestedAt),
    expiresAt = text(r?.expiresAt),
    state = text(r?.state);
  if (
    deferralId === undefined ||
    minutes === undefined ||
    !Number.isInteger(minutes) ||
    minutes < 1 ||
    reason === undefined ||
    requestedAt === undefined ||
    expiresAt === undefined ||
    (state !== 'ACTIVE' && state !== 'REVIEW_DUE' && state !== 'EXPIRED' && state !== 'INVALIDATED')
  )
    return undefined;
  return {
    id: deferralId,
    minutes,
    reason,
    requestedAt,
    expiresAt,
    state,
    reviewHealthId: text(r?.reviewHealthId),
  };
}

export function deferListingTask(
  context: ConsoleRequest,
  target: ListingDeferralTarget,
  minutes: number,
  reason: string,
): Promise<ConsoleOutcome<ListingTaskDeferral>> {
  const url =
    target.kind === 'ACTION'
      ? `${ACTIONS}/${id(target.actionId)}/responsibility/deferrals`
      : `${HEALTH}/listings/${id(target.listingId)}/responsibilities/${id(target.taskId)}/deferrals`;
  return request(context, url, parseListingTaskDeferral, {
    method: 'POST',
    body: JSON.stringify({ minutes, reason }),
  });
}

export interface ListingResponsibility {
  readonly bound: boolean;
  readonly status?: {
    readonly taskId: string;
    readonly clockState: string;
    readonly basisDigest: string;
    readonly calibrationPackageId: string | undefined;
    readonly calibrationVersion: number | undefined;
    readonly firstRaisedAt: string;
    readonly acknowledgementDueAt: string | undefined;
    readonly originalActionDueAt: string | undefined;
    readonly actionDueAt: string | undefined;
    readonly outcomeMaturityDueAt: string | undefined;
    readonly nextCoveredAt: string | undefined;
    readonly acknowledgedAt: string | undefined;
    readonly firstAttributableActionAt: string | undefined;
    readonly acknowledgementBreached: boolean | undefined;
    readonly actionBreached: boolean | undefined;
    readonly wallClockAgeSeconds: number;
    readonly dependencyHoldElapsedSeconds: number;
    readonly deferral?: ListingTaskDeferral | undefined;
    readonly dependencyHold?: ListingTaskDependencyHold | undefined;
  };
}

export interface ListingTaskDependencyHold {
  readonly id: string;
  readonly dependencyTaskId: string;
  readonly minutes: number;
  readonly evidenceReference: string;
  readonly startedAt: string;
  readonly expiresAt: string;
  readonly state: 'ACTIVE' | 'RESUMED' | 'EXPIRED' | 'INVALIDATED';
  readonly endedAt: string | undefined;
  readonly endReason: string | undefined;
}

export type ListingDependencyHoldTarget =
  | { readonly kind: 'ACTION'; readonly actionId: string }
  | { readonly kind: 'DIAGNOSTIC'; readonly listingId: string; readonly taskId: string };

export function parseListingTaskDependencyHold(
  body: unknown,
): ListingTaskDependencyHold | undefined {
  const r = row(body),
    holdId = text(r?.id),
    dependencyTaskId = text(r?.dependencyTaskId),
    minutes = number(r?.minutes),
    evidenceReference = text(r?.evidenceReference),
    startedAt = text(r?.startedAt),
    expiresAt = text(r?.expiresAt),
    state = text(r?.state);
  if (
    holdId === undefined ||
    dependencyTaskId === undefined ||
    minutes === undefined ||
    !Number.isSafeInteger(minutes) ||
    minutes < 1 ||
    evidenceReference === undefined ||
    startedAt === undefined ||
    expiresAt === undefined ||
    !['ACTIVE', 'RESUMED', 'EXPIRED', 'INVALIDATED'].includes(state ?? '')
  )
    return undefined;
  return {
    id: holdId,
    dependencyTaskId,
    minutes,
    evidenceReference,
    startedAt,
    expiresAt,
    state: state as ListingTaskDependencyHold['state'],
    endedAt: text(r?.endedAt),
    endReason: text(r?.endReason),
  };
}

export function holdListingTaskForDependency(
  context: ConsoleRequest,
  target: ListingDependencyHoldTarget,
  dependencyTaskId: string,
  minutes: number,
  evidenceReference: string,
): Promise<ConsoleOutcome<ListingTaskDependencyHold>> {
  const url =
    target.kind === 'ACTION'
      ? `${ACTIONS}/${id(target.actionId)}/responsibility/dependency-holds`
      : `${HEALTH}/listings/${id(target.listingId)}/responsibilities/${id(target.taskId)}/dependency-holds`;
  return request(
    context,
    url,
    parseListingTaskDependencyHold,
    post({ dependencyTaskId, minutes, evidenceReference }),
  );
}

export function parseListingResponsibility(body: unknown): ListingResponsibility | undefined {
  const r = row(body);
  if (r?.bound === false) return { bound: false };
  if (r?.bound !== true) return undefined;
  const s = row(r.status),
    taskId = text(s?.taskId),
    clockState = text(s?.clockState),
    basisDigest = text(s?.basisDigest),
    firstRaisedAt = text(s?.firstRaisedAt),
    age = number(s?.wallClockAgeSeconds);
  if (
    s === undefined ||
    taskId === undefined ||
    clockState === undefined ||
    basisDigest === undefined ||
    firstRaisedAt === undefined ||
    age === undefined
  )
    return undefined;
  const deferral =
    s.deferral === null || s.deferral === undefined
      ? undefined
      : parseListingTaskDeferral(s.deferral);
  if (s.deferral !== null && s.deferral !== undefined && deferral === undefined) return undefined;
  const dependencyHold =
    s.dependencyHold === null || s.dependencyHold === undefined
      ? undefined
      : parseListingTaskDependencyHold(s.dependencyHold);
  if (s.dependencyHold !== null && s.dependencyHold !== undefined && dependencyHold === undefined)
    return undefined;
  return {
    bound: true,
    status: {
      deferral,
      dependencyHold,
      taskId,
      clockState,
      basisDigest,
      firstRaisedAt,
      wallClockAgeSeconds: age,
      dependencyHoldElapsedSeconds: number(s.dependencyHoldElapsedSeconds) ?? 0,
      calibrationPackageId: text(s.calibrationPackageId),
      calibrationVersion: number(s.calibrationVersion),
      acknowledgementDueAt: text(s.acknowledgementDueAt),
      originalActionDueAt: text(s.originalActionDueAt),
      actionDueAt: text(s.actionDueAt),
      outcomeMaturityDueAt: text(s.outcomeMaturityDueAt),
      nextCoveredAt: text(s.nextCoveredAt),
      acknowledgedAt: text(s.acknowledgedAt),
      firstAttributableActionAt: text(s.firstAttributableActionAt),
      acknowledgementBreached:
        typeof s.acknowledgementBreached === 'boolean' ? s.acknowledgementBreached : undefined,
      actionBreached: typeof s.actionBreached === 'boolean' ? s.actionBreached : undefined,
    },
  };
}

export function fetchListingResponsibility(
  context: ConsoleRequest,
  actionId: string,
): Promise<ConsoleOutcome<ListingResponsibility>> {
  return request(context, `${ACTIONS}/${id(actionId)}/responsibility`, parseListingResponsibility);
}

export function acknowledgeListingResponsibility(
  context: ConsoleRequest,
  actionId: string,
): Promise<ConsoleOutcome<true>> {
  return request(
    context,
    `${ACTIONS}/${id(actionId)}/responsibility/acknowledgement`,
    (): true => true,
    { method: 'POST' },
  );
}

export function reviewAction(
  context: ConsoleRequest,
  actionId: string,
  verdict: 'ATTESTED' | 'RETURNED',
  reason: string,
  meaningAssessment?: MeaningAssessment,
): Promise<ConsoleOutcome<ListingAction>> {
  return request(
    context,
    `${ACTIONS}/${id(actionId)}/review`,
    parseListingAction,
    post({ verdict, reason, ...(meaningAssessment === undefined ? {} : { meaningAssessment }) }),
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
  reason?: string,
): Promise<ConsoleOutcome<LaunchAnswer>> {
  return request(
    context,
    `${ACTIONS}/${id(actionId)}/launch`,
    parseLaunchAnswer,
    post({ axes: {}, ...(reason === undefined ? {} : { reason }) }),
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

export function evaluateNode(
  context: ConsoleRequest,
  actionId: string,
  node: {
    readonly nodeCode: string;
    readonly stage: 'OPERATIONAL' | 'SETTLED';
    readonly measurementId?: string;
    readonly lateFactReference?: string;
  },
): Promise<ConsoleOutcome<Evaluation>> {
  return request(
    context,
    `${ACTIONS}/${id(actionId)}/evaluation/nodes`,
    parseEvaluation,
    post(node),
  );
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
  evidence: {
    managementObservationId?: string;
    displayObservationId?: string;
    promotionObservationId?: string;
  } = {},
): Promise<ConsoleOutcome<ManualPacket>> {
  return request(
    context,
    `${MANUAL}/packets/${id(packetId)}/verify`,
    parseManualPacket,
    post({ basis, managementMatch, displayState, note, ...evidence }),
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

export function adoptEngagement(
  context: ConsoleRequest,
  listingId: string,
  declaration: PromotionTerms,
  contextObservationId: string,
  originalAuthorityReference: string,
  originalAuthorityValidUntil: string,
  responsibleUserId: string,
): Promise<ConsoleOutcome<PromotionEngagement>> {
  return request(
    context,
    `${MANUAL}/listings/${id(listingId)}/engagements`,
    parsePromotionEngagement,
    post({
      ...declaration,
      contextObservationId: id(contextObservationId),
      originalAuthorityReference,
      originalAuthorityValidUntil,
      responsibleUserId: id(responsibleUserId),
    }),
  );
}

export function authorizeExit(
  context: ConsoleRequest,
  engagementId: string,
  reasonCode: string,
  authorityReference: string,
  evidenceId: string,
): Promise<ConsoleOutcome<PromotionEngagement>> {
  return request(
    context,
    `${MANUAL}/engagements/${id(engagementId)}/exit`,
    parsePromotionEngagement,
    post({ reasonCode, authorityReference, evidenceId: id(evidenceId) }),
  );
}

export function releaseEngagement(
  context: ConsoleRequest,
  engagementId: string,
  releaseKind: string,
  observationId: string,
  evidenceReference: string,
): Promise<ConsoleOutcome<PromotionEngagement>> {
  return request(
    context,
    `${MANUAL}/engagements/${id(engagementId)}/release`,
    parsePromotionEngagement,
    post({ releaseKind, observationId: id(observationId), evidenceReference }),
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

export function acknowledgeListingDiagnostic(
  context: ConsoleRequest,
  listingId: string,
  taskId: string,
): Promise<ConsoleOutcome<true>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/responsibilities/${id(taskId)}/acknowledgement`,
    (): true => true,
    { method: 'POST' },
  );
}

/**
 * Whether a failure only says the endpoint does not exist on this backend yet.
 * Such a failure is a signal to fall back, never an error to show.
 */
export function endpointUnavailable(failure: ConsoleFailure): boolean {
  return failure.kind === 'refused' && (failure.status === 404 || failure.status === 405);
}

/** One page of the health queue. */
export interface HealthQueueQuery {
  readonly necessaryState?: string;
  /** Free text matched against listing, product and SKU keys and names. */
  readonly q?: string;
  readonly offset?: number;
  readonly limit?: number;
}

export interface ListingHealthPage {
  readonly items: readonly ListingHealth[];
  /** Matching rows in total; undefined when the backend did not count them. */
  readonly total: number | undefined;
  readonly offset: number;
  readonly limit: number;
  /** The keyword matched more listings than are searched; items and total are partial. */
  readonly truncated?: boolean | undefined;
}

/** Read the page envelope, or a bare array from an older backend. */
export function parseListingHealthPage(body: unknown): ListingHealthPage | undefined {
  if (Array.isArray(body)) {
    const items = list(body, parseListingHealth);
    return items === undefined
      ? undefined
      : { items, total: undefined, offset: 0, limit: items.length };
  }
  const r = row(body);
  const items = list(r?.items, parseListingHealth);
  if (r === undefined || items === undefined) return undefined;
  return {
    items,
    total: number(r.total),
    offset: number(r.offset) ?? 0,
    limit: number(r.limit) ?? items.length,
    ...(bool(r.truncated) === true ? { truncated: true } : {}),
  };
}

function matchesListingText(item: ListingHealth, q: string): boolean {
  const needle = q.toLowerCase();
  return [item.nativeListingKey, item.identity?.title, item.identity?.productName].some(
    (value) => value?.toLowerCase().includes(needle) === true,
  );
}

/**
 * One page of the health queue, in the backend's order. When this backend has
 * no page endpoint yet, the plain queue is read and narrowed here instead; the
 * order is never changed.
 */
export async function fetchHealthQueuePage(
  context: ConsoleRequest,
  query: HealthQueueQuery,
): Promise<ConsoleOutcome<ListingHealthPage>> {
  const offset = query.offset ?? 0;
  const limit = query.limit ?? 20;
  const q = query.q?.trim() === '' ? undefined : query.q?.trim();
  const params = new URLSearchParams({ offset: String(offset), limit: String(limit) });
  if (query.necessaryState !== undefined) params.set('necessaryState', query.necessaryState);
  if (q !== undefined) params.set('q', q);
  const paged = await request(
    context,
    `${HEALTH}/queue/page?${params.toString()}`,
    parseListingHealthPage,
  );
  if (paged.ok || !endpointUnavailable(paged.failure)) return paged;
  const legacy = new URLSearchParams({ limit: '200' });
  if (query.necessaryState !== undefined) legacy.set('necessaryState', query.necessaryState);
  const all = await request(context, `${HEALTH}/queue?${legacy.toString()}`, (body) =>
    list(body, parseListingHealth),
  );
  if (!all.ok) return all;
  const narrowed =
    q === undefined ? all.value : all.value.filter((item) => matchesListingText(item, q));
  return {
    ok: true,
    value: {
      items: narrowed.slice(offset, offset + limit),
      total: all.value.length >= 200 ? undefined : narrowed.length,
      offset,
      limit,
    },
  };
}

export interface DescriptionObservationSummary {
  readonly observationId: string;
  readonly observedAt: string;
  readonly acquiredAt: string;
  readonly languageCode: string;
  readonly kizMarkedDeclared: boolean | undefined;
  readonly textDigest: string;
  /** At most 160 characters of the observed text. */
  readonly textPreview: string;
  readonly sourceKind: string;
  readonly recordedByUserId: string | undefined;
}

export interface DisplayObservationSummary {
  readonly observationId: string;
  readonly observedAt: string;
  readonly acquiredAt: string;
  readonly displayState: string;
  readonly evidenceGrade: string;
  readonly observerUserId: string | undefined;
  readonly displayedTextDigest: string | undefined;
  readonly evidenceReference: string;
  readonly sourceKind: string;
  readonly recordedByUserId: string | undefined;
}

export interface PromotionContextRecordSummary {
  readonly engagementKind: string;
  readonly nativePromotionKey: string;
  readonly participationState: string;
  readonly newTransactionsState: string;
  readonly residualObligationState: string;
}

export interface PromotionObservationSummary {
  readonly observationId: string;
  readonly observedAt: string;
  readonly acquiredAt: string;
  readonly engagementKind: string;
  readonly nativePromotionKey: string;
  readonly participationState: string;
  readonly contextCoverage: string;
  readonly verificationExpiresAt: string | undefined;
  readonly independentCurrent: boolean;
  readonly evidenceReference: string;
  readonly sourceKind: string;
  readonly recordedByUserId: string | undefined;
  readonly contextRecords: readonly PromotionContextRecordSummary[];
}

/** The recent observations of one listing, newest first. */
export interface ListingObservations {
  readonly description: readonly DescriptionObservationSummary[];
  readonly display: readonly DisplayObservationSummary[];
  readonly promotion: readonly PromotionObservationSummary[];
}

function required<K extends string>(r: Row, keys: readonly K[]): Record<K, string> | undefined {
  const out = {} as Record<K, string>;
  for (const key of keys) {
    const value = text(r[key]);
    if (value === undefined) return undefined;
    out[key] = value;
  }
  return out;
}

function parseDescriptionObservation(body: unknown): DescriptionObservationSummary | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const base = required(r, [
    'observationId',
    'observedAt',
    'acquiredAt',
    'languageCode',
    'textDigest',
    'textPreview',
    'sourceKind',
  ] as const);
  if (base === undefined) return undefined;
  return {
    ...base,
    kizMarkedDeclared: bool(r.kizMarkedDeclared),
    recordedByUserId: text(r.recordedByUserId),
  };
}

function parseDisplayObservation(body: unknown): DisplayObservationSummary | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const base = required(r, [
    'observationId',
    'observedAt',
    'acquiredAt',
    'displayState',
    'evidenceGrade',
    'evidenceReference',
    'sourceKind',
  ] as const);
  if (base === undefined) return undefined;
  return {
    ...base,
    observerUserId: text(r.observerUserId),
    displayedTextDigest: text(r.displayedTextDigest),
    recordedByUserId: text(r.recordedByUserId),
  };
}

function parsePromotionContextRecord(body: unknown): PromotionContextRecordSummary | undefined {
  const r = row(body);
  return r === undefined
    ? undefined
    : required(r, [
        'engagementKind',
        'nativePromotionKey',
        'participationState',
        'newTransactionsState',
        'residualObligationState',
      ] as const);
}

function parsePromotionObservation(body: unknown): PromotionObservationSummary | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const base = required(r, [
    'observationId',
    'observedAt',
    'acquiredAt',
    'engagementKind',
    'nativePromotionKey',
    'participationState',
    'contextCoverage',
    'evidenceReference',
    'sourceKind',
  ] as const);
  const independentCurrent = bool(r.independentCurrent);
  const contextRecords =
    r.contextRecords === undefined || r.contextRecords === null
      ? []
      : list(r.contextRecords, parsePromotionContextRecord);
  if (base === undefined || independentCurrent === undefined || contextRecords === undefined)
    return undefined;
  return {
    ...base,
    independentCurrent,
    verificationExpiresAt: text(r.verificationExpiresAt),
    recordedByUserId: text(r.recordedByUserId),
    contextRecords,
  };
}

export function parseListingObservations(body: unknown): ListingObservations | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const description = list(r.description ?? [], parseDescriptionObservation);
  const display = list(r.display ?? [], parseDisplayObservation);
  const promotion = list(r.promotion ?? [], parsePromotionObservation);
  return description === undefined || display === undefined || promotion === undefined
    ? undefined
    : { description, display, promotion };
}

/** Recent observations of one listing, so a recorded observation is picked, not retyped. */
export function fetchListingObservations(
  context: ConsoleRequest,
  listingId: string,
  limit = 10,
): Promise<ConsoleOutcome<ListingObservations>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/observations?limit=${String(limit)}`,
    parseListingObservations,
  );
}

export type ListingPersonRole = 'MANUAL_EXECUTOR' | 'PROMOTION_STEWARD';

/** A colleague who may take a listing role. Staff names only, never contact details. */
export interface ListingPerson {
  readonly userId: string;
  readonly displayName: string;
  /** The signed-in operator. */
  readonly self: boolean;
}

export function parseListingPerson(body: unknown): ListingPerson | undefined {
  const r = row(body);
  const userId = text(r?.userId);
  const displayName = text(r?.displayName);
  if (userId === undefined || displayName === undefined) return undefined;
  return { userId, displayName, self: bool(r?.self) ?? false };
}

/** People who may execute one action, or steward one listing's promotions. */
export function fetchListingPeople(
  context: ConsoleRequest,
  role: ListingPersonRole,
  target: { readonly actionId: string } | { readonly listingId: string },
): Promise<ConsoleOutcome<readonly ListingPerson[]>> {
  const params = new URLSearchParams({ role });
  if ('actionId' in target) params.set('actionId', target.actionId);
  else params.set('listingId', target.listingId);
  return request(context, `${MANUAL}/people?${params.toString()}`, (body) =>
    list(body, parseListingPerson),
  );
}

/** A completed description command whose prior text could be restored. */
export interface RestorationSource {
  readonly commandId: string;
  readonly actionId: string;
  readonly completedAt: string | undefined;
  readonly priorText: string;
  /** Whether it restores over the listing's current text. */
  readonly appliesToCurrent: boolean;
}

function parseRestorationSource(body: unknown): RestorationSource | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const base = required(r, ['commandId', 'actionId', 'priorText'] as const);
  const appliesToCurrent = bool(r.appliesToCurrent);
  return base === undefined || appliesToCurrent === undefined
    ? undefined
    : { ...base, appliesToCurrent, completedAt: text(r.completedAt) };
}

export function fetchRestorationSources(
  context: ConsoleRequest,
  listingId: string,
): Promise<ConsoleOutcome<readonly RestorationSource[]>> {
  return request(context, `${ACTIONS}/restoration-sources?listingId=${id(listingId)}`, (body) =>
    list(body, parseRestorationSource),
  );
}

/** Close a candidate that will not be prepared, with the reason it is dropped. */
export function dismissCandidate(
  context: ConsoleRequest,
  candidateId: string,
  expectedVersion: number,
  reason: string,
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `${ACTIONS}/candidates/${id(candidateId)}/dismiss`,
    parseIdentifier('state'),
    post({ expectedVersion, reason }),
  );
}

/** One earlier listing-assistance request, without its content. */
export interface ListingAssistanceRecord {
  readonly invocationId: string;
  readonly windowCode: string;
  readonly state: string;
  readonly startedAt: string;
  readonly completedAt: string | undefined;
}

export function fetchListingAssistanceHistory(
  context: ConsoleRequest,
  listingId: string,
  limit = 10,
): Promise<ConsoleOutcome<readonly ListingAssistanceRecord[]>> {
  return request(
    context,
    `${HEALTH}/listings/${id(listingId)}/assistance?limit=${String(limit)}`,
    (body) =>
      list(body, (item) => {
        const r = row(item);
        if (r === undefined) return undefined;
        const base = required(r, ['invocationId', 'windowCode', 'state', 'startedAt'] as const);
        return base === undefined ? undefined : { ...base, completedAt: text(r.completedAt) };
      }),
  );
}
