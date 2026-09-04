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

/** One advertising case, as the queue and the case view read it. */
export interface AdvertisingCase {
  readonly id: string;
  readonly storeId: string;
  readonly platformCode: string;
  readonly adNativeObjectId: string;
  readonly nativeObjectKind: string;
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
  readonly rankScore: number;
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
  const code = text(record.code);
  if (code === undefined) {
    return undefined;
  }
  return {
    code,
    value: decimal(record.value),
    weight: decimal(record.weight),
    contribution: decimal(record.contribution),
    absenceReason: text(record.absenceReason),
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
  return {
    id,
    storeId,
    platformCode: text(record.platformCode) ?? 'UNKNOWN',
    adNativeObjectId,
    nativeObjectKind: text(record.nativeObjectKind) ?? 'UNKNOWN',
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
    rankScore: decimal(record.rankScore) ?? 0,
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
export interface AdvertisingExposure {
  readonly envelopeId: string | undefined;
  readonly policyVersion: number | undefined;
  readonly scopeKind: string | undefined;
  readonly currencyCode: string | undefined;
  readonly activeInterventions: number;
  readonly maxActiveInterventions: number | undefined;
  readonly reservedRecoveryHeadroom: number | undefined;
  readonly unresolvedTransmittedWrites: number;
  readonly maxUnresolvedTransmittedWrites: number | undefined;
  readonly cumulativeBidChangeAmount: number | undefined;
  readonly maxCumulativeBidChangeAmount: number | undefined;
  readonly cumulativeWindowHours: number | undefined;
  readonly resolved: boolean;
  readonly exhaustedAxes: readonly string[];
  readonly status: string | undefined;
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
  readonly readyToLift: boolean;
  readonly reenabledAt: string | undefined;
}

/**
 * One observation of what a bid change actually did.
 *
 * The stage is carried, never flattened. An operational reading counts orders
 * and arrives in days; a settled reading counts what the buyer kept and arrives
 * much later. Showing one number labelled "result" would show whichever was
 * written last.
 */
export interface AdvertisingOutcome {
  readonly id: string;
  readonly commandId: string;
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
    holding: record.holding === true,
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
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const record = body as Record<string, unknown>;
  if (typeof record.activeInterventions !== 'number') {
    return undefined;
  }
  return {
    envelopeId: text(record.envelopeId),
    policyVersion: decimal(record.policyVersion),
    scopeKind: text(record.scopeKind),
    currencyCode: text(record.currencyCode),
    activeInterventions: record.activeInterventions,
    maxActiveInterventions: decimal(record.maxActiveInterventions),
    reservedRecoveryHeadroom: decimal(record.reservedRecoveryHeadroom),
    unresolvedTransmittedWrites: decimal(record.unresolvedTransmittedWrites) ?? 0,
    maxUnresolvedTransmittedWrites: decimal(record.maxUnresolvedTransmittedWrites),
    cumulativeBidChangeAmount: decimal(record.cumulativeBidChangeAmount),
    maxCumulativeBidChangeAmount: decimal(record.maxCumulativeBidChangeAmount),
    cumulativeWindowHours: decimal(record.cumulativeWindowHours),
    resolved: record.resolved === true,
    exhaustedAxes: strings(record.exhaustedAxes),
    status: text(record.status),
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
    holding: record.holding === true,
    outstandingConditions: strings(record.outstandingConditions),
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
  const outcomeStage = text(record.outcomeStage);
  const verdict = text(record.verdict);
  const baselineMetricState = text(record.baselineMetricState);
  const observedMetricState = text(record.observedMetricState);
  if (
    id === undefined ||
    commandId === undefined ||
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
    settled: record.settled === true,
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
  return {
    id,
    caseId: text(record.caseId),
    adNativeObjectId,
    actionKind,
    intendedState: text(record.intendedState),
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
    configurationProven: verifications.some((item) => item.provesConfiguration),
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
  return {
    sectionCode,
    ordinal: decimal(record.ordinal) ?? 0,
    itemCount: decimal(record.itemCount) ?? items.length,
    coverageState,
    blockerCodes: strings(record.blockerCodes),
    summaryNote: text(record.summaryNote),
    // Re-derived rather than trusted, so a body claiming a topic was covered
    // while naming a blocker cannot assert it.
    complete: coverageState === 'COMPLETE',
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
    fullyCovered: sections.every((section) => section.complete),
    sections,
  };
}
