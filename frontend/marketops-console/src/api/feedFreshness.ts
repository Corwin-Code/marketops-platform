/**
 * How fresh each feed of the price guardrail is for a store, and the Owner's statements that the
 * store has no data in a feed it does not collect. Times are ISO-8601 instants.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** One feed's newest freshness watermark. */
export interface FeedState {
  /** PRICE, STOCK, SALES, RETURNS, FINANCE_FEES, ADVERTISING, INTERNAL_COST or COMMERCIAL_INPUTS. */
  readonly feedCode: string;
  /** Whether an attestation may cover the feed. */
  readonly attestable: boolean;
  /** The instant the guardrail measures the feed's age from; `null` without a watermark. */
  readonly effectiveAt: string | null;
  readonly recordedAt: string | null;
  /** What the watermark rests on, e.g. `ingestion-run:…` or `attestation:…`. */
  readonly evidence: string | null;
  readonly ageSeconds: number | null;
}

/** One attestation. */
export interface FeedAttestation {
  readonly attestationId: string;
  readonly feedCode: string;
  readonly statement: string;
  readonly attestedAt: string;
  readonly expiresAt: string;
  /** STANDING, LAPSED, REVOKED or EXPIRED. */
  readonly state: string;
  readonly endedAt: string | null;
  readonly endReason: string | null;
}

/** A store's feed freshness. */
export interface FeedFreshness {
  readonly storeId: string;
  readonly generatedAt: string;
  readonly feeds: readonly FeedState[];
  /** Newest first. */
  readonly attestations: readonly FeedAttestation[];
}

/** Load a store's feed freshness. */
export function fetchFeedFreshness(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<FeedFreshness>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/feed-freshness`,
    parseFeedFreshness,
  );
}

/** Attest that the store has no data in the feeds named. */
export function attestFeedAbsence(
  context: ConsoleRequest,
  storeId: string,
  input: {
    readonly feedCodes: readonly string[];
    readonly statement: string;
    readonly validDays: number;
  },
): Promise<ConsoleOutcome<readonly string[]>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/feed-attestations`,
    (body) => {
      if (!isRecord(body) || !Array.isArray(body.attestationIds)) return undefined;
      const ids = body.attestationIds.filter((id): id is string => typeof id === 'string');
      return ids.length === body.attestationIds.length ? ids : undefined;
    },
    { method: 'POST', body: JSON.stringify(input) },
  );
}

/** Revoke a standing attestation. */
export function revokeFeedAttestation(
  context: ConsoleRequest,
  storeId: string,
  attestationId: string,
  reason: string,
): Promise<ConsoleOutcome<true>> {
  return request(
    context,
    `/api/v1/console/stores/${encodeURIComponent(storeId)}/feed-attestations/${encodeURIComponent(attestationId)}/revocation`,
    () => true,
    { method: 'POST', body: JSON.stringify({ reason }) },
  );
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseFeedFreshness(body: unknown): FeedFreshness | undefined {
  if (!isRecord(body) || !Array.isArray(body.feeds) || !Array.isArray(body.attestations)) {
    return undefined;
  }
  const storeId = text(body.storeId);
  const generatedAt = text(body.generatedAt);
  if (storeId === undefined || generatedAt === undefined) return undefined;
  const feeds: FeedState[] = [];
  for (const value of body.feeds) {
    if (!isRecord(value)) return undefined;
    const feedCode = text(value.feedCode);
    if (feedCode === undefined || typeof value.attestable !== 'boolean') return undefined;
    feeds.push({
      feedCode,
      attestable: value.attestable,
      effectiveAt: optionalText(value.effectiveAt),
      recordedAt: optionalText(value.recordedAt),
      evidence: optionalText(value.evidence),
      ageSeconds:
        typeof value.ageSeconds === 'number' && Number.isFinite(value.ageSeconds)
          ? value.ageSeconds
          : null,
    });
  }
  const attestations: FeedAttestation[] = [];
  for (const value of body.attestations) {
    if (!isRecord(value)) return undefined;
    const attestationId = text(value.attestationId);
    const feedCode = text(value.feedCode);
    const statement = text(value.statement);
    const attestedAt = text(value.attestedAt);
    const expiresAt = text(value.expiresAt);
    const state = text(value.state);
    if (
      attestationId === undefined ||
      feedCode === undefined ||
      statement === undefined ||
      attestedAt === undefined ||
      expiresAt === undefined ||
      state === undefined
    ) {
      return undefined;
    }
    attestations.push({
      attestationId,
      feedCode,
      statement,
      attestedAt,
      expiresAt,
      state,
      endedAt: optionalText(value.endedAt),
      endReason: optionalText(value.endReason),
    });
  }
  return { storeId, generatedAt, feeds, attestations };
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
