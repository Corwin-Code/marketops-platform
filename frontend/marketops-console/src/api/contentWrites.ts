/**
 * Writing a listing's title and description to its marketplace (W2): what the editor starts from,
 * the Owner's one confirmation, what became of each change, and the content-write switches.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';
import type { WriteSwitch } from './priceWrites';

/** One recorded call or move of a content command. */
export interface ContentEvent {
  readonly sequence: number;
  /** CREATED, GATE_CLOSED, PRE_READ, APPLY_STARTED, APPLY, STATUS, READBACK, STATE or RESOLUTION. */
  readonly kind: string;
  readonly stateAfter: string | null;
  readonly httpStatus: number | null;
  readonly outcome: string | null;
  readonly nativeTaskKey: string | null;
  readonly taskStatus: string | null;
  readonly observedTitle: string | null;
  readonly observedDescription: string | null;
  /** MATCHES_TARGET, MATCHES_PRIOR or DIFFERENT. */
  readonly titleMatch: string | null;
  readonly descriptionMatch: string | null;
  readonly detail: string | null;
  readonly actorUserId: string | null;
  readonly recordedAt: string;
}

/** One content command and the change it carries out. */
export interface ContentCommand {
  readonly id: string;
  readonly changeId: string;
  readonly storeId: string;
  readonly platformListingVariantId: string;
  readonly nativeListingKey: string;
  /** The seller article the marketplace addresses the listing by. */
  readonly offerKey: string;
  /**
   * PENDING, AWAITING_TASK, AWAITING_READBACK, UNKNOWN_REQUIRES_READBACK, READBACK_MISMATCH,
   * SUCCEEDED, FAILED_BEFORE_WRITE, FAILED, CANCELLED or CLOSED.
   */
  readonly state: string;
  readonly gateReasons: readonly string[];
  readonly outcomeCode: string | null;
  readonly outcomeDetail: string | null;
  readonly nativeTaskKey: string | null;
  readonly nextActionAt: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly terminalAt: string | null;
  readonly priorTitle: string | null;
  readonly priorDescription: string | null;
  readonly priorObservedAt: string | null;
  readonly targetTitle: string;
  readonly targetDescription: string;
  readonly titleChanged: boolean;
  readonly descriptionChanged: boolean;
  readonly sourceInvocationId: string | null;
  readonly reason: string;
  readonly approvedByUserId: string;
  readonly approvedAt: string;
  readonly approvalExpiresAt: string;
  readonly events: readonly ContentEvent[];
}

/** What the editor starts from. */
export interface ContentCurrent {
  readonly platformListingVariantId: string;
  readonly storeId: string;
  /** The title the newest catalog facts carry, or `null`. */
  readonly title: string | null;
  /** The description they carry, or `null` when none or too long to keep. */
  readonly description: string | null;
  readonly observedAt: string | null;
  readonly titleLimit: number;
  readonly descriptionLimit: number;
  readonly latestCommand: ContentCommand | null;
}

/** Whether a store's content writes can leave the platform now. */
export interface ContentWriteStatus {
  readonly storeId: string;
  readonly platformCode: string | null;
  readonly productionWritesEnabled: boolean;
  readonly workerEnabled: boolean;
  readonly capabilityId: string | null;
  readonly capabilityVerification: string | null;
  readonly storeAvailability: string | null;
  readonly evidenceValidUntil: string | null;
  readonly capabilitySwitchEnabled: boolean;
  readonly globalSwitchEnabled: boolean;
  /** Every condition that stops a content write regardless of the listing. */
  readonly reasons: readonly string[];
}

/** The states a command ends in. */
export const TERMINAL_CONTENT_STATES: ReadonlySet<string> = new Set([
  'SUCCEEDED',
  'FAILED_BEFORE_WRITE',
  'FAILED',
  'CANCELLED',
  'CLOSED',
]);

/** Load what the editor starts from for one listing variant. */
export function fetchContentCurrent(
  context: ConsoleRequest,
  platformListingVariantId: string,
): Promise<ConsoleOutcome<ContentCurrent>> {
  return request(
    context,
    `/api/v1/console/content-changes/listings/${encodeURIComponent(platformListingVariantId)}`,
    parseCurrent,
  );
}

/** Confirm the final title and description; needs a recent sign-in. */
export function confirmContentChange(
  context: ConsoleRequest,
  input: {
    readonly platformListingVariantId: string;
    readonly title: string;
    readonly description: string;
    readonly sourceInvocationId: string | null;
    readonly reason: string | null;
  },
): Promise<ConsoleOutcome<ContentCommand>> {
  return request(context, '/api/v1/console/content-changes', parseCommand, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

/** Load the newest content commands of a store. */
export function fetchContentCommands(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<readonly ContentCommand[]>> {
  return request(
    context,
    `/api/v1/console/content-commands/stores/${encodeURIComponent(storeId)}`,
    (body) => listOf(body, parseCommand),
  );
}

/** Load one content command. */
export function fetchContentCommand(
  context: ConsoleRequest,
  commandId: string,
): Promise<ConsoleOutcome<ContentCommand>> {
  return request(
    context,
    `/api/v1/console/content-commands/${encodeURIComponent(commandId)}`,
    parseCommand,
  );
}

/** Why a command may not write now; empty when it may. */
export function fetchContentGate(
  context: ConsoleRequest,
  commandId: string,
): Promise<ConsoleOutcome<readonly string[]>> {
  return request(
    context,
    `/api/v1/console/content-commands/${encodeURIComponent(commandId)}/gate`,
    (body) =>
      Array.isArray(body)
        ? body.filter((item): item is string => typeof item === 'string')
        : undefined,
  );
}

/** Read the card again (unknown or not matching), or close the command; both need a recent sign-in. */
export function resolveContentCommand(
  context: ConsoleRequest,
  commandId: string,
  action: 'readback' | 'closure',
  reason?: string,
): Promise<ConsoleOutcome<ContentCommand>> {
  return request(
    context,
    `/api/v1/console/content-commands/${encodeURIComponent(commandId)}/${action}`,
    parseCommand,
    { method: 'POST', body: action === 'closure' ? JSON.stringify({ reason }) : '{}' },
  );
}

/** Load a store's content write status. */
export function fetchContentWriteStatus(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<ContentWriteStatus>> {
  return request(
    context,
    `/api/v1/console/content-commands/stores/${encodeURIComponent(storeId)}/write-status`,
    parseStatus,
  );
}

/** Load the content-write switches. */
export function fetchContentSwitches(
  context: ConsoleRequest,
): Promise<ConsoleOutcome<readonly WriteSwitch[]>> {
  return request(context, '/api/v1/console/content-commands/kill-switch', (body) =>
    listOf(body, parseSwitch),
  );
}

/** Turn content writes off (never gated) or on (needs a recent sign-in) at one scope. */
export function moveContentSwitch(
  context: ConsoleRequest,
  direction: 'enable' | 'disable',
  input: {
    readonly scopeKind: string;
    readonly scopeReference: string | null;
    readonly storeId: string | null;
    readonly reason: string;
  },
): Promise<ConsoleOutcome<true>> {
  return request(context, `/api/v1/console/content-commands/kill-switch/${direction}`, () => true, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

function parseEvent(value: unknown): ContentEvent | undefined {
  if (!isRecord(value)) return undefined;
  const kind = text(value.kind);
  const recordedAt = text(value.recordedAt);
  if (kind === undefined || recordedAt === undefined || typeof value.sequence !== 'number') {
    return undefined;
  }
  return {
    sequence: value.sequence,
    kind,
    stateAfter: text(value.stateAfter) ?? null,
    httpStatus: typeof value.httpStatus === 'number' ? value.httpStatus : null,
    outcome: text(value.outcome) ?? null,
    nativeTaskKey: text(value.nativeTaskKey) ?? null,
    taskStatus: text(value.taskStatus) ?? null,
    observedTitle: typeof value.observedTitle === 'string' ? value.observedTitle : null,
    observedDescription:
      typeof value.observedDescription === 'string' ? value.observedDescription : null,
    titleMatch: text(value.titleMatch) ?? null,
    descriptionMatch: text(value.descriptionMatch) ?? null,
    detail: text(value.detail) ?? null,
    actorUserId: text(value.actorUserId) ?? null,
    recordedAt,
  };
}

/** Validate a command; anything that does not match the contract is `undefined`. */
export function parseCommand(value: unknown): ContentCommand | undefined {
  if (!isRecord(value) || !Array.isArray(value.events) || !Array.isArray(value.gateReasons)) {
    return undefined;
  }
  const required = {
    id: text(value.id),
    changeId: text(value.changeId),
    storeId: text(value.storeId),
    platformListingVariantId: text(value.platformListingVariantId),
    nativeListingKey: text(value.nativeListingKey),
    offerKey: text(value.offerKey),
    state: text(value.state),
    createdAt: text(value.createdAt),
    updatedAt: text(value.updatedAt),
    targetTitle: text(value.targetTitle),
    targetDescription: text(value.targetDescription),
    reason: text(value.reason),
    approvedByUserId: text(value.approvedByUserId),
    approvedAt: text(value.approvedAt),
    approvalExpiresAt: text(value.approvalExpiresAt),
  };
  if (
    Object.values(required).some((field) => field === undefined) ||
    typeof value.titleChanged !== 'boolean' ||
    typeof value.descriptionChanged !== 'boolean'
  ) {
    return undefined;
  }
  const events = value.events.map(parseEvent);
  if (events.some((event) => event === undefined)) return undefined;
  return {
    id: required.id ?? '',
    changeId: required.changeId ?? '',
    storeId: required.storeId ?? '',
    platformListingVariantId: required.platformListingVariantId ?? '',
    nativeListingKey: required.nativeListingKey ?? '',
    offerKey: required.offerKey ?? '',
    state: required.state ?? '',
    gateReasons: value.gateReasons.filter((item): item is string => typeof item === 'string'),
    outcomeCode: text(value.outcomeCode) ?? null,
    outcomeDetail: text(value.outcomeDetail) ?? null,
    nativeTaskKey: text(value.nativeTaskKey) ?? null,
    nextActionAt: text(value.nextActionAt) ?? null,
    createdAt: required.createdAt ?? '',
    updatedAt: required.updatedAt ?? '',
    terminalAt: text(value.terminalAt) ?? null,
    priorTitle: typeof value.priorTitle === 'string' ? value.priorTitle : null,
    priorDescription: typeof value.priorDescription === 'string' ? value.priorDescription : null,
    priorObservedAt: text(value.priorObservedAt) ?? null,
    targetTitle: required.targetTitle ?? '',
    targetDescription: required.targetDescription ?? '',
    titleChanged: value.titleChanged,
    descriptionChanged: value.descriptionChanged,
    sourceInvocationId: text(value.sourceInvocationId) ?? null,
    reason: required.reason ?? '',
    approvedByUserId: required.approvedByUserId ?? '',
    approvedAt: required.approvedAt ?? '',
    approvalExpiresAt: required.approvalExpiresAt ?? '',
    events: events.filter((event): event is ContentEvent => event !== undefined),
  };
}

function parseCurrent(value: unknown): ContentCurrent | undefined {
  if (!isRecord(value)) return undefined;
  const platformListingVariantId = text(value.platformListingVariantId);
  const storeId = text(value.storeId);
  if (
    platformListingVariantId === undefined ||
    storeId === undefined ||
    typeof value.titleLimit !== 'number' ||
    typeof value.descriptionLimit !== 'number'
  ) {
    return undefined;
  }
  let latestCommand: ContentCommand | null = null;
  if (value.latestCommand !== null && value.latestCommand !== undefined) {
    const parsed = parseCommand(value.latestCommand);
    if (parsed === undefined) return undefined;
    latestCommand = parsed;
  }
  return {
    platformListingVariantId,
    storeId,
    title: typeof value.title === 'string' ? value.title : null,
    description: typeof value.description === 'string' ? value.description : null,
    observedAt: text(value.observedAt) ?? null,
    titleLimit: value.titleLimit,
    descriptionLimit: value.descriptionLimit,
    latestCommand,
  };
}

function parseStatus(value: unknown): ContentWriteStatus | undefined {
  if (!isRecord(value) || !Array.isArray(value.reasons)) return undefined;
  const storeId = text(value.storeId);
  if (
    storeId === undefined ||
    typeof value.productionWritesEnabled !== 'boolean' ||
    typeof value.workerEnabled !== 'boolean' ||
    typeof value.capabilitySwitchEnabled !== 'boolean' ||
    typeof value.globalSwitchEnabled !== 'boolean'
  ) {
    return undefined;
  }
  return {
    storeId,
    platformCode: text(value.platformCode) ?? null,
    productionWritesEnabled: value.productionWritesEnabled,
    workerEnabled: value.workerEnabled,
    capabilityId: text(value.capabilityId) ?? null,
    capabilityVerification: text(value.capabilityVerification) ?? null,
    storeAvailability: text(value.storeAvailability) ?? null,
    evidenceValidUntil: text(value.evidenceValidUntil) ?? null,
    capabilitySwitchEnabled: value.capabilitySwitchEnabled,
    globalSwitchEnabled: value.globalSwitchEnabled,
    reasons: value.reasons.filter((reason): reason is string => typeof reason === 'string'),
  };
}

function parseSwitch(value: unknown): WriteSwitch | undefined {
  if (!isRecord(value)) return undefined;
  const id = text(value.id);
  const scopeKind = text(value.scopeKind);
  const state = text(value.state);
  const status = text(value.status);
  const updatedAt = text(value.updatedAt);
  if (
    id === undefined ||
    scopeKind === undefined ||
    state === undefined ||
    status === undefined ||
    updatedAt === undefined
  ) {
    return undefined;
  }
  return {
    id,
    scopeKind,
    platformCode: text(value.platformCode) ?? null,
    marketplaceAccountId: text(value.marketplaceAccountId) ?? null,
    storeId: text(value.storeId) ?? null,
    capabilityId: text(value.capabilityId) ?? null,
    state,
    status,
    updatedAt,
  };
}

function listOf<T>(
  body: unknown,
  parse: (value: unknown) => T | undefined,
): readonly T[] | undefined {
  if (!Array.isArray(body)) return undefined;
  const items = body.map(parse);
  return items.some((item) => item === undefined)
    ? undefined
    : items.filter((item): item is T => item !== undefined);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}
