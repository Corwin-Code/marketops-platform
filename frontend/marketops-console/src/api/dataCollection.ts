/**
 * Scheduled read-only collection of one store: the Owner's standing authorization, every scheduled
 * job's state, the kept diagnosis calculations and what the scheduler recorded. Times are ISO-8601
 * instants; windows are UTC.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** One run as the scheduler sees it. */
export interface CollectionRun {
  readonly runId: string;
  /** `MANUAL`, `SCHEDULED`, `BACKFILL` or `REPLAY`. */
  readonly runKind: string;
  /** `QUEUED`, `LEASED`, `RUNNING`, `RETRY_WAIT`, `BLOCKED`, `SUCCEEDED` or `FAILED_TERMINAL`. */
  readonly state: string;
  readonly windowFrom: string | null;
  readonly windowTo: string | null;
  readonly failureCode: string | null;
  readonly createdAt: string | null;
  readonly updatedAt: string | null;
  readonly nextAttemptAt: string | null;
}

/** What a collection slot asks for. */
export interface CollectionTarget {
  /** e.g. `snapshot:2026-09-30`, `day:2026-09-28`, `week:2026-09-21`. */
  readonly key: string;
  readonly slotStart: string;
  readonly windowFrom: string | null;
  readonly windowTo: string | null;
}

/** One record of what the scheduler did. */
export interface CollectionEvent {
  readonly eventId: string;
  readonly jobId: string | null;
  readonly datasetKind: string | null;
  /** `COLLECTED`, `WAITING`, `BLOCKED`, `FAILED`, `SKIPPED`, `NORMALIZATION_STOPPED`, `RECALCULATED` or `RECALCULATION_FAILED`. */
  readonly kind: string;
  readonly targetKey: string | null;
  readonly detail: Readonly<Record<string, string>>;
  readonly occurredAt: string;
}

/** One scheduled job. */
export interface CollectionJob {
  readonly jobId: string;
  readonly datasetKind: string;
  readonly jobCode: string;
  /** `DAILY_SNAPSHOT`, `DAILY_WINDOW` or `WEEKLY_WINDOW`. */
  readonly cadence: string;
  readonly lastSucceeded: CollectionRun | null;
  readonly liveRun: CollectionRun | null;
  /** The native status of the live run's newest answer (`HTTP 403` for a withdrawn key), or `null`. */
  readonly liveRunLastAnswer: string | null;
  /** `null` when no evidence is current: nothing is collected then. */
  readonly evidenceValidUntil: string | null;
  /** What the current slot still asks for. */
  readonly due: CollectionTarget | null;
  /** Scheduled runs already made for `due`. */
  readonly attempts: number;
  /** What a later slot asks for next. */
  readonly upcoming: CollectionTarget | null;
  readonly lastEvent: CollectionEvent | null;
}

/** The policy in force. */
export interface CollectionPolicy {
  readonly policyId: string;
  readonly authorizedAt: string;
  readonly reason: string;
  readonly version: number;
}

/** The collection state of one store. */
export interface DataCollection {
  readonly storeId: string;
  /** Whether the backend's collection timer runs at all. */
  readonly schedulerEnabled: boolean;
  /** When each UTC day's collection slot starts, `HH:mm`. */
  readonly dailyAtUtc: string;
  readonly policy: CollectionPolicy | null;
  /** Until when the read credential is in force, or `null` when none is. */
  readonly credentialExpiresAt: string | null;
  readonly jobs: readonly CollectionJob[];
  readonly calculations: readonly {
    readonly window: string;
    readonly latestPeriodEnd: string | null;
  }[];
  readonly events: readonly CollectionEvent[];
}

function base(storeId: string): string {
  return `/api/v1/console/stores/${encodeURIComponent(storeId)}/data-collection`;
}

/** Load a store's collection state. */
export function fetchDataCollection(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<DataCollection>> {
  return request(context, base(storeId), parseDataCollection);
}

/** Put scheduled collection in force for the store. */
export function enableDataCollection(
  context: ConsoleRequest,
  storeId: string,
  reason: string,
): Promise<ConsoleOutcome<DataCollection>> {
  return request(context, `${base(storeId)}/policy`, parseDataCollection, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

/** Take scheduled collection out of force. */
export function retireDataCollection(
  context: ConsoleRequest,
  storeId: string,
  reason: string,
  expectedVersion: number,
): Promise<ConsoleOutcome<DataCollection>> {
  return request(context, `${base(storeId)}/policy/retirement`, parseDataCollection, {
    method: 'POST',
    body: JSON.stringify({ reason, expectedVersion }),
  });
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseDataCollection(body: unknown): DataCollection | undefined {
  if (!isRecord(body)) return undefined;
  const storeId = text(body.storeId);
  const dailyAtUtc = text(body.dailyAtUtc);
  if (
    storeId === undefined ||
    dailyAtUtc === undefined ||
    typeof body.schedulerEnabled !== 'boolean'
  ) {
    return undefined;
  }
  if (
    !Array.isArray(body.jobs) ||
    !Array.isArray(body.calculations) ||
    !Array.isArray(body.events)
  ) {
    return undefined;
  }
  return {
    storeId,
    schedulerEnabled: body.schedulerEnabled,
    dailyAtUtc,
    policy: parsePolicy(body.policy),
    credentialExpiresAt: text(body.credentialExpiresAt) ?? null,
    jobs: body.jobs.map(parseJob).filter((job): job is CollectionJob => job !== undefined),
    calculations: body.calculations.flatMap((value: unknown) => {
      if (!isRecord(value)) return [];
      const window = text(value.window);
      return window === undefined
        ? []
        : [{ window, latestPeriodEnd: text(value.latestPeriodEnd) ?? null }];
    }),
    events: body.events
      .map(parseEvent)
      .filter((event): event is CollectionEvent => event !== undefined),
  };
}

function parsePolicy(value: unknown): CollectionPolicy | null {
  if (!isRecord(value)) return null;
  const policyId = text(value.policyId);
  const authorizedAt = text(value.authorizedAt);
  if (policyId === undefined || authorizedAt === undefined || typeof value.version !== 'number') {
    return null;
  }
  return { policyId, authorizedAt, reason: text(value.reason) ?? '', version: value.version };
}

function parseJob(value: unknown): CollectionJob | undefined {
  if (!isRecord(value)) return undefined;
  const jobId = text(value.jobId);
  const datasetKind = text(value.datasetKind);
  const cadence = text(value.cadence);
  if (jobId === undefined || datasetKind === undefined || cadence === undefined) return undefined;
  return {
    jobId,
    datasetKind,
    jobCode: text(value.jobCode) ?? '',
    cadence,
    lastSucceeded: parseRun(value.lastSucceeded),
    liveRun: parseRun(value.liveRun),
    liveRunLastAnswer: text(value.liveRunLastAnswer) ?? null,
    evidenceValidUntil: text(value.evidenceValidUntil) ?? null,
    due: parseTarget(value.due),
    attempts: typeof value.attempts === 'number' ? value.attempts : 0,
    upcoming: parseTarget(value.upcoming),
    lastEvent: parseEvent(value.lastEvent) ?? null,
  };
}

function parseRun(value: unknown): CollectionRun | null {
  if (!isRecord(value)) return null;
  const runId = text(value.runId);
  const state = text(value.state);
  if (runId === undefined || state === undefined) return null;
  return {
    runId,
    runKind: text(value.runKind) ?? '',
    state,
    windowFrom: text(value.windowFrom) ?? null,
    windowTo: text(value.windowTo) ?? null,
    failureCode: text(value.failureCode) ?? null,
    createdAt: text(value.createdAt) ?? null,
    updatedAt: text(value.updatedAt) ?? null,
    nextAttemptAt: text(value.nextAttemptAt) ?? null,
  };
}

function parseTarget(value: unknown): CollectionTarget | null {
  if (!isRecord(value)) return null;
  const key = text(value.key);
  const slotStart = text(value.slotStart);
  if (key === undefined || slotStart === undefined) return null;
  return {
    key,
    slotStart,
    windowFrom: text(value.windowFrom) ?? null,
    windowTo: text(value.windowTo) ?? null,
  };
}

function parseEvent(value: unknown): CollectionEvent | undefined {
  if (!isRecord(value)) return undefined;
  const eventId = text(value.eventId);
  const kind = text(value.kind);
  const occurredAt = text(value.occurredAt);
  if (eventId === undefined || kind === undefined || occurredAt === undefined) return undefined;
  const detail: Record<string, string> = {};
  if (isRecord(value.detail)) {
    for (const [key, entry] of Object.entries(value.detail)) {
      if (typeof entry === 'string') detail[key] = entry;
      else if (typeof entry === 'number' && Number.isFinite(entry)) detail[key] = String(entry);
      else if (typeof entry === 'boolean') detail[key] = String(entry);
    }
  }
  return {
    eventId,
    jobId: text(value.jobId) ?? null,
    datasetKind: text(value.datasetKind) ?? null,
    kind,
    targetKey: text(value.targetKey) ?? null,
    detail,
    occurredAt,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}
