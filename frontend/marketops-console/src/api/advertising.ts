/**
 * The advertising console's view of the backend.
 *
 * Every field the backend refuses to collapse arrives here separately, and the
 * parser refuses a body that is missing one rather than defaulting it. A
 * console that filled in a missing evidence state with a plausible one would be
 * inventing the very thing the state exists to report.
 */

/** One factor that contributed to a case's rank. */
export interface AdvertisingRankFactor {
  readonly code: string;
  readonly value: number | undefined;
  readonly weight: number | undefined;
  readonly contribution: number | undefined;
  readonly absenceReason: string | undefined;
}

export interface AdvertisingWorkflowCandidate {
  readonly id: string;
  readonly ordinal: number;
  readonly currentBidAmount: number | undefined;
  readonly targetBidAmount: number | undefined;
  readonly currency: string | undefined;
  readonly unit: string | undefined;
  readonly basis: string;
  readonly recommendationId: string;
  readonly state: string;
  readonly version: number;
  readonly makerUserId: string | undefined;
  readonly endorserUserId: string | undefined;
  readonly commandId?: string;
}

export interface AdvertisingWorkflow {
  readonly caseId: string;
  readonly taskId: string | undefined;
  readonly taskState: string;
  readonly operatingDisposition: string;
  readonly taskVersion: number | undefined;
  readonly accountableRole: string;
  readonly firstRaisedAt: string | undefined;
  readonly acknowledgementDueAt: string | undefined;
  readonly actionDueAt: string | undefined;
  readonly escalationDueAt: string | undefined;
  readonly coverageState: string;
  readonly nextStaffedResponseAt: string | undefined;
  readonly slo: Readonly<Record<string, unknown>> | undefined;
  readonly candidates: readonly AdvertisingWorkflowCandidate[];
  readonly allowedActions: readonly string[];
}

export function parseAdvertisingWorkflow(body: unknown): AdvertisingWorkflow | undefined {
  if (typeof body !== 'object' || body === null) return undefined;
  const record = body as Record<string, unknown>;
  const caseId = text(record.caseId);
  if (
    caseId === undefined ||
    !Array.isArray(record.candidates) ||
    !Array.isArray(record.allowedActions)
  )
    return undefined;
  const candidates: AdvertisingWorkflowCandidate[] = [];
  for (const item of record.candidates) {
    if (typeof item !== 'object' || item === null) return undefined;
    const row = item as Record<string, unknown>;
    const id = text(row.id),
      recommendationId = text(row.recommendationId),
      commandId = text(row.commandId);
    const state = text(row.state),
      version = decimal(row.version),
      ordinal = decimal(row.ordinal);
    if (
      id === undefined ||
      recommendationId === undefined ||
      state === undefined ||
      version === undefined ||
      !Number.isSafeInteger(version) ||
      ordinal === undefined
    )
      return undefined;
    candidates.push({
      id,
      recommendationId,
      state,
      version,
      ordinal,
      currentBidAmount: decimal(row.currentBidAmount),
      targetBidAmount: decimal(row.targetBidAmount),
      currency: text(row.currency),
      unit: text(row.unit),
      basis: text(row.basis) ?? 'UNRESOLVED',
      makerUserId: text(row.makerUserId),
      endorserUserId: text(row.endorserUserId),
      ...(commandId === undefined ? {} : { commandId }),
    });
  }
  return {
    caseId,
    taskId: text(record.taskId),
    taskState: text(record.taskState) ?? 'UNRESOLVED',
    operatingDisposition: text(record.operatingDisposition) ?? 'UNRESOLVED',
    taskVersion: decimal(record.taskVersion),
    accountableRole: text(record.accountableRole) ?? 'UNRESOLVED',
    firstRaisedAt: text(record.firstRaisedAt),
    acknowledgementDueAt: text(record.acknowledgementDueAt),
    actionDueAt: text(record.actionDueAt),
    escalationDueAt: text(record.escalationDueAt),
    coverageState: text(record.coverageState) ?? 'UNRESOLVED',
    nextStaffedResponseAt: text(record.nextStaffedResponseAt),
    slo:
      typeof record.slo === 'object' && record.slo !== null
        ? (record.slo as Record<string, unknown>)
        : undefined,
    candidates,
    allowedActions: strings(record.allowedActions),
  };
}

/** One advertising case, as the queue and the case view read it. */
export interface AdvertisingCase {
  readonly id: string;
  readonly storeId: string;
  readonly platformCode: string;
  readonly adNativeObjectId: string;
  readonly nativeObjectKind: string;
  readonly allowedControlActions: readonly string[];
  readonly nativeObjectName: string | undefined;
  readonly lane: string;
  readonly protectionTier: string | undefined;
  readonly causeCode: string;
  readonly accountableRoleCode: string | undefined;
  readonly evidenceState: string;
  readonly confidenceState: string;
  readonly blockerCodes: readonly string[];
  readonly contributionProfitState: string;
  readonly contributionProfitAmount: number | undefined;
  readonly profitPerAdRubState: string;
  readonly profitPerAdRubValue: number | undefined;
  readonly profitCurrencyCode: string | undefined;
  readonly officialSpendState: string;
  readonly officialSpendAmount: number | undefined;
  readonly eligibleTrafficState: string;
  readonly eligibleTrafficCount: number | undefined;
  readonly maxCpcState: string;
  readonly maxCpcAmount: number | undefined;
  readonly currentBidState: string;
  readonly currentBidAmount: number | undefined;
  readonly rankScore: number | undefined;
  readonly storeTimezone: string | undefined;
  readonly disclosureState: string;
  readonly nativeObjectKey: string | undefined;
  readonly biddingMode: string;
  readonly controlGranularityState: string;
  readonly affectedSetDigest: string | undefined;
  readonly affectedSetResolution: string;
  readonly affectedProductVariantIds: readonly string[];
  readonly affectedListingVariantIds: readonly string[];
  readonly semanticProfile: Readonly<Record<string, unknown>> | undefined;
  readonly nativeRelationships: readonly Readonly<Record<string, unknown>>[];
  readonly asOf: string;
  readonly rankFactors: readonly AdvertisingRankFactor[];
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function decimal(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === 'string' && value.length > 0) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
}

/** One rank factor, or `undefined` when the body cannot be read as one. */
export function parseAdvertisingRankFactor(body: unknown): AdvertisingRankFactor | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const code = text(record.factorCode);
  if (code === undefined) {
    return undefined;
  }
  return {
    code,
    value: decimal(record.value),
    weight: decimal(record.weight),
    contribution: decimal(record.contribution),
    absenceReason: text(record.displayNote),
  };
}

/**
 * One case, or `undefined` when a field the console must not invent is missing.
 *
 * The required set is deliberately the identity and every state. An amount may
 * be absent — that is what a value state is for — but the state that explains
 * its absence may not.
 */
export function parseAdvertisingCase(body: unknown): AdvertisingCase | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const id = text(record.id);
  const storeId = text(record.storeId);
  const adNativeObjectId = text(record.adNativeObjectId);
  const lane = text(record.lane);
  const causeCode = text(record.causeCode);
  const evidenceState = text(record.evidenceState);
  const confidenceState = text(record.confidenceState);
  if (
    id === undefined ||
    storeId === undefined ||
    adNativeObjectId === undefined ||
    lane === undefined ||
    causeCode === undefined ||
    evidenceState === undefined ||
    confidenceState === undefined
  ) {
    return undefined;
  }
  const factors = Array.isArray(record.rankFactors)
    ? record.rankFactors
        .map(parseAdvertisingRankFactor)
        .filter((item): item is AdvertisingRankFactor => item !== undefined)
    : [];
  if (Array.isArray(record.rankFactors) && factors.length !== record.rankFactors.length)
    return undefined;
  return {
    id,
    storeId,
    platformCode: text(record.platformCode) ?? 'UNKNOWN',
    adNativeObjectId,
    nativeObjectKind: text(record.nativeObjectKind) ?? 'UNKNOWN',
    allowedControlActions: strings(record.allowedControlActions),
    nativeObjectName: text(record.nativeObjectName),
    lane,
    protectionTier: text(record.protectionTier),
    causeCode,
    accountableRoleCode: text(record.accountableRoleCode),
    evidenceState,
    confidenceState,
    blockerCodes: Array.isArray(record.blockerCodes)
      ? record.blockerCodes.filter((item): item is string => typeof item === 'string')
      : [],
    contributionProfitState: text(record.contributionProfitState) ?? 'UNKNOWN',
    contributionProfitAmount: decimal(record.contributionProfitAmount),
    profitPerAdRubState: text(record.profitPerAdRubState) ?? 'UNKNOWN',
    profitPerAdRubValue: decimal(record.profitPerAdRubValue),
    profitCurrencyCode: text(record.profitCurrencyCode),
    officialSpendState: text(record.officialSpendState) ?? 'UNKNOWN',
    officialSpendAmount: decimal(record.officialSpendAmount),
    eligibleTrafficState: text(record.eligibleTrafficState) ?? 'UNKNOWN',
    eligibleTrafficCount: decimal(record.eligibleTrafficCount),
    maxCpcState: text(record.maxCpcState) ?? 'UNKNOWN',
    maxCpcAmount: decimal(record.maxCpcAmount),
    currentBidState: text(record.currentBidState) ?? 'UNKNOWN',
    currentBidAmount: decimal(record.currentBidAmount),
    rankScore: decimal(record.rankScore),
    storeTimezone: text(record.storeTimezone),
    disclosureState: text(record.disclosureState) ?? 'UNRESOLVED',
    nativeObjectKey: text(record.nativeObjectKey),
    biddingMode: text(record.biddingMode) ?? 'UNKNOWN',
    controlGranularityState: text(record.controlGranularityState) ?? 'UNKNOWN',
    affectedSetDigest: text(record.affectedSetDigest),
    affectedSetResolution: text(record.affectedSetResolution) ?? 'UNRESOLVED',
    affectedProductVariantIds: strings(record.affectedProductVariantIds),
    affectedListingVariantIds: strings(record.affectedListingVariantIds),
    semanticProfile:
      typeof record.semanticProfile === 'object' && record.semanticProfile !== null
        ? (record.semanticProfile as Record<string, unknown>)
        : undefined,
    nativeRelationships: Array.isArray(record.nativeRelationships)
      ? record.nativeRelationships.filter(
          (value): value is Record<string, unknown> => typeof value === 'object' && value !== null,
        )
      : [],
    asOf: text(record.asOf) ?? '',
    rankFactors: factors,
  };
}

/**
 * One reservation currently standing over a set of variants.
 *
 * Only a real intervention reserves. A proposal nobody has acted on is not one,
 * which is why this list is short even when the queue is long — and why a
 * console that showed proposals here would make the envelope look spent.
 */
export interface AdvertisingReservation {
  readonly id: string;
  readonly adNativeObjectId: string;
  readonly storeId: string;
  readonly affectedSetDigest: string | undefined;
  readonly productVariantIds: readonly string[];
  readonly interventionKind: string;
  readonly interventionReferenceId: string | undefined;
  readonly direction: string | undefined;
  readonly lane: string;
  readonly state: string;
  readonly holding: boolean;
  readonly outstandingReleaseConditions: readonly string[];
  readonly reservedAt: string;
  readonly releasedAt: string | undefined;
  readonly releaseReason: string | undefined;
}

/**
 * The aggregate envelope in force and what is consumed against it.
 *
 * Each axis carries its own limit. There is no combined figure here because the
 * product does not have one: the write gate checks every axis independently and
 * never adds one axis's slack to another's.
 */
export const ADVERTISING_EXPOSURE_AXES = [
  'activeInterventions',
  'associatedOfficialSpend',
  'affectedRetainedSalesShare',
  'cumulativeBidChangeMajor',
  'unresolvedTransmittedWrites',
  'reservedRecoveryHeadroom',
] as const;
export type AdvertisingExposureAxisCode = (typeof ADVERTISING_EXPOSURE_AXES)[number];
export interface AdvertisingExposureAxis {
  readonly usage: number | undefined;
  readonly limit: number | undefined;
  readonly available: number | undefined;
  readonly reserved: number | undefined;
  readonly state: string;
  readonly unit: string | undefined;
  readonly windowHours: number | undefined;
  readonly companySales: number | undefined;
  readonly affectedSales: number | undefined;
  readonly aggregationBasis: string | undefined;
  readonly conservativeBoundaryReportCount: number | undefined;
}
export interface AdvertisingExposureEnvelope {
  readonly envelopeId: string;
  readonly policyVersion: number;
  readonly scopeKind: string;
  readonly platformCode: string | undefined;
  readonly storeId: string | undefined;
  readonly currencyCode: string | undefined;
  readonly measurementWindowHours: number | undefined;
  readonly retainedWindowDays: number | undefined;
  readonly axes: Readonly<Record<AdvertisingExposureAxisCode, AdvertisingExposureAxis>>;
  readonly reasons: readonly string[];
}
export interface AdvertisingExposure {
  readonly measuredAt: string | undefined;
  readonly envelopes: readonly AdvertisingExposureEnvelope[];
  readonly unresolvedStoreIds: readonly string[];
  readonly resolved: boolean;
  readonly status: string;
}

/** One hold, quarantine or kill standing over advertising. */
export interface AdvertisingContainment {
  readonly id: string;
  readonly containmentKind: string;
  readonly scopeKind: string | undefined;
  readonly causeClass: string | undefined;
  readonly reason: string | undefined;
  readonly evidenceReference: string | undefined;
  readonly activatedByUserId: string | undefined;
  readonly activatedByTrigger: string | undefined;
  readonly activatedAt: string;
  readonly state: string;
  readonly holding: boolean;
  readonly outstandingConditions: readonly string[];
  readonly allowedActions: readonly string[];
  readonly readyToLift: boolean;
  readonly reenabledAt: string | undefined;
}

/**
 * One observation of what a bid change actually did.
 *
 * Completed, retained over 30 days, and settled stages retain their own
 * independent observations and revisions.
 */
export interface AdvertisingOutcome {
  readonly id: string;
  readonly commandId: string | undefined;
  readonly manualPacketId: string | undefined;
  readonly outcomeStage: string;
  readonly revisionNo: number;
  readonly supersedesObservationId: string | undefined;
  readonly adjustmentReason: string | undefined;
  readonly windowStartsAt: string;
  readonly windowEndsAt: string;
  readonly baselineMetricState: string;
  readonly baselineMetricValue: number | undefined;
  readonly observedMetricState: string;
  readonly observedMetricValue: number | undefined;
  readonly observedTrafficCount: number | undefined;
  readonly settledCoverageRatio: number | undefined;
  readonly verdict: string;
  readonly guardState: string | undefined;
  readonly unresolvedReasonCodes: readonly string[];
  readonly settled: boolean;
  readonly axes: Readonly<Record<string, unknown>> | undefined;
  readonly evaluatedAt: string;
}

function strings(value: unknown): readonly string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
}

/** One reservation, or `undefined` when the body is not one. */
export function parseAdvertisingReservation(body: unknown): AdvertisingReservation | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const id = text(record.id);
  const adNativeObjectId = text(record.adNativeObjectId);
  const interventionKind = text(record.interventionKind);
  const state = text(record.state);
  if (
    id === undefined ||
    adNativeObjectId === undefined ||
    interventionKind === undefined ||
    state === undefined
  ) {
    return undefined;
  }
  return {
    id,
    adNativeObjectId,
    storeId: text(record.storeId) ?? '',
    affectedSetDigest: text(record.affectedSetDigest),
    productVariantIds: strings(record.productVariantIds),
    interventionKind,
    interventionReferenceId: text(record.interventionReferenceId),
    direction: text(record.direction),
    lane: text(record.lane) ?? 'UNKNOWN',
    state,
    holding: record.holding === true || !['RELEASED', 'REENABLED'].includes(state),
    outstandingReleaseConditions: strings(record.outstandingReleaseConditions),
    reservedAt: text(record.reservedAt) ?? '',
    releasedAt: text(record.releasedAt),
    releaseReason: text(record.releaseReason),
  };
}

/**
 * The envelope reading, or `undefined` when the body is not one.
 *
 * An unresolved envelope is a legitimate answer, not a parse failure: it means
 * nothing may be written at all, and the console has to be able to say so.
 */
export function parseAdvertisingExposure(body: unknown): AdvertisingExposure | undefined {
  if (typeof body !== 'object' || body === null) return undefined;
  const record = body as Record<string, unknown>;
  if (record.disclosureState === 'MASKED') {
    return {
      measuredAt: undefined,
      envelopes: [],
      unresolvedStoreIds: [],
      resolved: false,
      status: 'MASKED',
    };
  }
  if (
    !Array.isArray(record.envelopes) ||
    !Array.isArray(record.unresolvedStoreIds) ||
    !record.unresolvedStoreIds.every((id) => typeof id === 'string' && id.trim().length > 0) ||
    typeof record.resolved !== 'boolean' ||
    !['MEASURED', 'UNRESOLVED'].includes(String(record.status)) ||
    text(record.measuredAt) === undefined
  )
    return undefined;
  // Whitespace carries no measurement. Number(' ') would otherwise invent a zero.
  const number = (value: unknown): number | undefined =>
    typeof value === 'string' && value.trim().length === 0 ? undefined : decimal(value);
  const envelopes: AdvertisingExposureEnvelope[] = [];
  for (const value of record.envelopes as unknown[]) {
    if (typeof value !== 'object' || value === null) return undefined;
    const entry = value as Record<string, unknown>;
    const envelopeId = text(entry.envelopeId),
      policyVersion = number(entry.policyVersion),
      scopeKind = text(entry.scopeKind);
    if (
      envelopeId === undefined ||
      policyVersion === undefined ||
      !Number.isInteger(policyVersion) ||
      policyVersion < 1 ||
      scopeKind === undefined ||
      typeof entry.axes !== 'object' ||
      entry.axes === null
    )
      return undefined;
    const rawAxes = entry.axes as Record<string, unknown>;
    const axes = {} as Record<AdvertisingExposureAxisCode, AdvertisingExposureAxis>;
    for (const code of ADVERTISING_EXPOSURE_AXES) {
      const raw = rawAxes[code];
      if (typeof raw !== 'object' || raw === null) return undefined;
      const axis = raw as Record<string, unknown>;
      const state = text(axis.state);
      if (state === undefined || !['AVAILABLE', 'EXCEEDED', 'UNKNOWN'].includes(state))
        return undefined;
      const usage = number(axis.usage),
        limit = number(axis.limit);
      const available = number(axis.available),
        reserved = number(axis.reserved);
      const measured =
        code === 'reservedRecoveryHeadroom'
          ? available !== undefined && reserved !== undefined
          : usage !== undefined && limit !== undefined;
      axes[code] = {
        usage,
        limit,
        available,
        reserved,
        state: measured ? state : 'UNKNOWN',
        unit: text(axis.unit),
        windowHours: number(axis.windowHours),
        companySales: number(axis.companySales),
        affectedSales: number(axis.affectedSales),
        aggregationBasis: text(axis.aggregationBasis),
        conservativeBoundaryReportCount: number(axis.conservativeBoundaryReportCount),
      };
    }
    envelopes.push({
      envelopeId,
      policyVersion,
      scopeKind,
      platformCode: text(entry.platformCode),
      storeId: text(entry.storeId),
      currencyCode: text(entry.currencyCode),
      measurementWindowHours: number(entry.measurementWindowHours),
      retainedWindowDays: number(entry.retainedWindowDays),
      axes,
      reasons: strings(entry.reasons),
    });
  }
  const unresolvedStoreIds = strings(record.unresolvedStoreIds);
  const resolved =
    envelopes.length > 0 &&
    unresolvedStoreIds.length === 0 &&
    record.resolved &&
    record.status === 'MEASURED';
  return {
    measuredAt: text(record.measuredAt),
    envelopes,
    unresolvedStoreIds,
    resolved,
    status: resolved ? 'MEASURED' : 'UNRESOLVED',
  };
}

/** One containment, or `undefined` when the body is not one. */
export function parseAdvertisingContainment(body: unknown): AdvertisingContainment | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const id = text(record.id);
  const containmentKind = text(record.containmentKind);
  const state = text(record.state);
  if (id === undefined || containmentKind === undefined || state === undefined) {
    return undefined;
  }
  return {
    id,
    containmentKind,
    scopeKind: text(record.scopeKind),
    causeClass: text(record.causeClass),
    reason: text(record.reason),
    evidenceReference: text(record.evidenceReference),
    activatedByUserId: text(record.activatedByUserId),
    activatedByTrigger: text(record.activatedByTrigger),
    activatedAt: text(record.activatedAt) ?? '',
    state,
    holding: record.holding === true || !['RELEASED', 'REENABLED'].includes(state),
    outstandingConditions: strings(record.outstandingConditions),
    allowedActions: strings(record.allowedActions),
    readyToLift: record.readyToLift === true,
    reenabledAt: text(record.reenabledAt),
  };
}

/**
 * One outcome observation, or `undefined` when the body is not one.
 *
 * The stage, the verdict and both metric states are required. A reading whose
 * stage was missing could not be told from the other stage, which is the exact
 * confusion this surface exists to prevent.
 */
export function parseAdvertisingOutcome(body: unknown): AdvertisingOutcome | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const id = text(record.id);
  const commandId = text(record.commandId);
  const manualPacketId = text(record.manualPacketId);
  const outcomeStage = text(record.outcomeStage);
  const verdict = text(record.verdict);
  const baselineMetricState = text(record.baselineMetricState);
  const observedMetricState = text(record.observedMetricState);
  if (
    id === undefined ||
    (commandId === undefined && manualPacketId === undefined) ||
    outcomeStage === undefined ||
    verdict === undefined ||
    baselineMetricState === undefined ||
    observedMetricState === undefined
  ) {
    return undefined;
  }
  return {
    id,
    commandId,
    manualPacketId,
    outcomeStage,
    revisionNo: decimal(record.revisionNo) ?? 1,
    supersedesObservationId: text(record.supersedesObservationId),
    adjustmentReason: text(record.adjustmentReason),
    windowStartsAt: text(record.windowStartsAt) ?? '',
    windowEndsAt: text(record.windowEndsAt) ?? '',
    baselineMetricState,
    baselineMetricValue: decimal(record.baselineMetricValue),
    observedMetricState,
    observedMetricValue: decimal(record.observedMetricValue),
    observedTrafficCount: decimal(record.observedTrafficCount),
    settledCoverageRatio: decimal(record.settledCoverageRatio),
    verdict,
    guardState: text(record.guardState),
    unresolvedReasonCodes: strings(record.unresolvedReasonCodes),
    settled: outcomeStage.startsWith('SETTLED'),
    axes:
      typeof record.axes === 'object' && record.axes !== null
        ? (record.axes as Record<string, unknown>)
        : undefined,
    evaluatedAt: text(record.evaluatedAt) ?? '',
  };
}

/**
 * One observation about whether a manual change actually landed.
 *
 * `provesConfiguration` is the whole point. An executor saying they did it is a
 * report; only an official readback, an official export or a second person's
 * independent look proves anything, and the console must never render the two
 * the same way.
 */
export interface AdvertisingManualVerification {
  readonly id: string;
  readonly evidenceGrade: string;
  readonly executorUserId: string | undefined;
  readonly verifierUserId: string | undefined;
  readonly observedFieldPath: string | undefined;
  readonly observedValue: string | undefined;
  readonly conflictState: string | undefined;
  readonly provesConfiguration: boolean;
  readonly observedAt: string;
}

/**
 * One manual execution packet: work a person was asked to do by hand.
 *
 * Nothing here can produce a command, an outbox row, an attempt or a provider
 * call. A packet is a written instruction and a place to record what was
 * observed afterwards, which is exactly why the verification evidence grade
 * matters more than the executor's own report.
 */
export interface AdvertisingManualPacket {
  readonly id: string;
  readonly caseId: string | undefined;
  readonly adNativeObjectId: string;
  readonly actionKind: string;
  readonly intendedState: string | undefined;
  readonly packetDetails: Readonly<Record<string, unknown>> | undefined;
  readonly reason: string | undefined;
  readonly evidenceReference: string | undefined;
  readonly blockerCodes: readonly string[];
  readonly makerUserId: string | undefined;
  readonly endorserUserId: string | undefined;
  readonly approverUserId: string | undefined;
  readonly state: string;
  readonly issuedAt: string;
  readonly expiresAt: string;
  readonly configurationProven: boolean;
  readonly version: number | undefined;
  readonly currentProofId: string | undefined;
  readonly allowedActions: readonly string[];
  readonly verifications: readonly AdvertisingManualVerification[];
}

/** One verification, or `undefined` when the body is not one. */
export function parseAdvertisingManualVerification(
  body: unknown,
): AdvertisingManualVerification | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const id = text(record.id);
  const evidenceGrade = text(record.evidenceGrade);
  if (id === undefined || evidenceGrade === undefined) {
    return undefined;
  }
  return {
    id,
    evidenceGrade,
    executorUserId: text(record.executorUserId),
    verifierUserId: text(record.verifierUserId),
    observedFieldPath: text(record.observedFieldPath),
    observedValue: text(record.observedValue),
    conflictState: text(record.conflictState),
    // Never defaulted true. An absent flag is not a proof.
    provesConfiguration: record.provesConfiguration === true,
    observedAt: text(record.observedAt) ?? '',
  };
}

/** One manual packet, or `undefined` when the body is not one. */
export function parseAdvertisingManualPacket(body: unknown): AdvertisingManualPacket | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const id = text(record.id);
  const adNativeObjectId = text(record.adNativeObjectId);
  const actionKind = text(record.actionKind);
  const state = text(record.state);
  if (
    id === undefined ||
    adNativeObjectId === undefined ||
    actionKind === undefined ||
    state === undefined
  ) {
    return undefined;
  }
  const verifications = Array.isArray(record.verifications)
    ? record.verifications
        .map(parseAdvertisingManualVerification)
        .filter((item): item is AdvertisingManualVerification => item !== undefined)
    : [];
  if (Array.isArray(record.verifications) && verifications.length !== record.verifications.length)
    return undefined;
  return {
    id,
    caseId: text(record.caseId),
    adNativeObjectId,
    actionKind,
    intendedState: text(record.intendedState),
    packetDetails:
      typeof record.packetDetails === 'object' && record.packetDetails !== null
        ? (record.packetDetails as Record<string, unknown>)
        : undefined,
    reason: text(record.reason),
    evidenceReference: text(record.evidenceReference),
    blockerCodes: strings(record.blockerCodes),
    makerUserId: text(record.makerUserId),
    endorserUserId: text(record.endorserUserId),
    approverUserId: text(record.approverUserId),
    state,
    issuedAt: text(record.issuedAt) ?? '',
    expiresAt: text(record.expiresAt) ?? '',
    // Derived from the verifications by the backend, and re-derived here rather
    // than trusted, so a body that claimed proof with none could not assert it.
    configurationProven:
      state === 'MANUAL_CONFIGURATION_VERIFIED' &&
      record.configurationProven === true &&
      verifications.some(
        (item) =>
          item.id === text(record.currentProofId) &&
          item.provesConfiguration &&
          item.conflictState === 'NONE',
      ),
    version: decimal(record.version),
    currentProofId: text(record.currentProofId),
    allowedActions: strings(record.allowedActions),
    verifications,
  };
}

/**
 * One published brief, with the sections it covered.
 *
 * A report, not an authority. Every item names one canonical row, and the
 * revision fields are what let a reader see both the reading published on the
 * day and the reading that supersedes it — a decision taken on the earlier one
 * cannot be understood from the later one alone.
 */
export interface AdvertisingBrief {
  readonly id: string;
  readonly briefKind: string;
  readonly periodKey: string;
  readonly asOf: string;
  readonly revisionNo: number;
  readonly revisionKind: string;
  readonly supersedesPublicationId: string | undefined;
  readonly adjustmentReason: string | undefined;
  readonly lateFactReference: string | undefined;
  readonly gapCodes: readonly string[];
  readonly contentDigest: string;
  readonly publishedAt: string;
  readonly restatement: boolean;
  readonly fullyCovered: boolean;
  readonly sections: readonly AdvertisingBriefSection[];
}

/** One named topic of a brief, emitted whether or not it found anything. */
export interface AdvertisingBriefSection {
  readonly sectionCode: string;
  readonly ordinal: number;
  readonly itemCount: number;
  readonly coverageState: string;
  readonly blockerCodes: readonly string[];
  readonly summaryNote: string | undefined;
  readonly complete: boolean;
  readonly items: readonly AdvertisingBriefItem[];
}

/** One line of a brief and the canonical row it points at. */
export interface AdvertisingBriefItem {
  readonly subjectKind: string;
  readonly referenceId: string;
  readonly lane: string | undefined;
  readonly causeCode: string | undefined;
  readonly valueState: string;
  readonly numericValue: number | undefined;
  readonly currencyCode: string | undefined;
  readonly evidenceState: string | undefined;
  readonly blockerCodes: readonly string[];
  readonly observedAt: string | undefined;
}

/** One item, or `undefined` when the body is not one. */
export function parseAdvertisingBriefItem(body: unknown): AdvertisingBriefItem | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const subjectKind = text(record.subjectKind);
  const referenceId = text(record.referenceId);
  const valueState = text(record.valueState);
  if (subjectKind === undefined || referenceId === undefined || valueState === undefined) {
    return undefined;
  }
  return {
    subjectKind,
    referenceId,
    lane: text(record.lane),
    causeCode: text(record.causeCode),
    valueState,
    numericValue: decimal(record.numericValue),
    currencyCode: text(record.currencyCode),
    evidenceState: text(record.evidenceState),
    blockerCodes: strings(record.blockerCodes),
    observedAt: text(record.observedAt),
  };
}

/** One section, or `undefined` when the body is not one. */
export function parseAdvertisingBriefSection(body: unknown): AdvertisingBriefSection | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const sectionCode = text(record.sectionCode);
  const coverageState = text(record.coverageState);
  if (sectionCode === undefined || coverageState === undefined) {
    return undefined;
  }
  const items = Array.isArray(record.items)
    ? record.items
        .map(parseAdvertisingBriefItem)
        .filter((item): item is AdvertisingBriefItem => item !== undefined)
    : [];
  if (Array.isArray(record.items) && items.length !== record.items.length) return undefined;
  return {
    sectionCode,
    ordinal: decimal(record.ordinal) ?? 0,
    itemCount: decimal(record.itemCount) ?? items.length,
    coverageState,
    blockerCodes: strings(record.blockerCodes),
    summaryNote: text(record.summaryNote),
    // Re-derived rather than trusted, so a body claiming a topic was covered
    // while naming a blocker cannot assert it.
    complete: coverageState === 'COMPLETE' && strings(record.blockerCodes).length === 0,
    items,
  };
}

/**
 * One brief, or `undefined` when the body is not one.
 *
 * The period, the fact cut and the revision are required. A reading that could
 * not say which period it covered or which cut it read would be unusable for the
 * one thing a brief is for: knowing what was believed, and when.
 */
export function parseAdvertisingBrief(body: unknown): AdvertisingBrief | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  const id = text(record.id);
  const briefKind = text(record.briefKind);
  const periodKey = text(record.periodKey);
  const asOf = text(record.asOf);
  const revisionKind = text(record.revisionKind);
  if (
    id === undefined ||
    briefKind === undefined ||
    periodKey === undefined ||
    asOf === undefined ||
    revisionKind === undefined
  ) {
    return undefined;
  }
  const sections = Array.isArray(record.sections)
    ? record.sections
        .map(parseAdvertisingBriefSection)
        .filter((section): section is AdvertisingBriefSection => section !== undefined)
    : [];
  if (Array.isArray(record.sections) && sections.length !== record.sections.length)
    return undefined;
  const supersedesPublicationId = text(record.supersedesPublicationId);
  return {
    id,
    briefKind,
    periodKey,
    asOf,
    revisionNo: decimal(record.revisionNo) ?? 1,
    revisionKind,
    supersedesPublicationId,
    adjustmentReason: text(record.adjustmentReason),
    lateFactReference: text(record.lateFactReference),
    gapCodes: strings(record.gapCodes),
    contentDigest: text(record.contentDigest) ?? '',
    publishedAt: text(record.publishedAt) ?? '',
    // Derived from what the body actually carries. A reading that named no
    // predecessor is not a restatement however it labelled itself.
    restatement: supersedesPublicationId !== undefined,
    fullyCovered:
      sections.length > 0 &&
      strings(record.gapCodes).length === 0 &&
      sections.every((section) => section.complete),
    sections,
  };
}
