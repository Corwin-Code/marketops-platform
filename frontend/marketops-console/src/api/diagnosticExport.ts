import type { ConsoleOutcome, ConsoleRequest } from './console';
import { CORRELATION_HEADER, REQUEST_TIMEOUT_MS } from './console';

/** Limits match the database writer; they also bound a corrupt server response. */
const MAX_PART_BYTES = 4 * 1024 * 1024;
const MAX_EXPORT_BYTES = 256 * 1024 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const ROOT = '/api/v1/console/diagnosis';

interface ExportRequestInit {
  readonly method: 'POST';
  readonly headers: Record<string, string>;
}

export interface ExportJob {
  readonly id: string;
  readonly storeId: string;
  readonly window: string;
  readonly state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED';
  readonly createdAt: string;
  readonly snapshotAt: string | null;
  readonly expiresAt: string | null;
  readonly rowCount: number;
  readonly byteLength: number;
  readonly completedParts: number;
  readonly failureCode: string | null;
}

interface ExportPart {
  readonly partNumber: number;
  readonly firstOrdinal: number;
  readonly lastOrdinal: number;
  readonly rowCount: number;
  readonly byteLength: number;
  readonly sha256: string;
}

interface ExportManifest {
  readonly schemaVersion: 1;
  readonly format: 'marketops-diagnostic-ndjson-v1';
  readonly exportId: string;
  readonly storeId: string;
  readonly window: string;
  readonly snapshotAt: string;
  readonly rowCount: number;
  readonly byteLength: number;
  readonly parts: readonly ExportPart[];
}

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function integer(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max;
}

function timestamp(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 40 && Number.isFinite(Date.parse(value));
}

function parseJob(body: unknown): ExportJob | undefined {
  if (
    !object(body) ||
    typeof body.id !== 'string' ||
    !UUID.test(body.id) ||
    typeof body.storeId !== 'string' ||
    !UUID.test(body.storeId) ||
    !['D7', 'D14', 'D30'].includes(String(body.window)) ||
    !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED'].includes(String(body.state)) ||
    !timestamp(body.createdAt) ||
    !(body.snapshotAt === null || timestamp(body.snapshotAt)) ||
    !(body.expiresAt === null || timestamp(body.expiresAt)) ||
    !integer(body.rowCount, 0, 1000000) ||
    !integer(body.byteLength, 0, MAX_EXPORT_BYTES) ||
    !integer(body.completedParts, 0, 64) ||
    !(
      body.failureCode === null ||
      (typeof body.failureCode === 'string' && /^[A-Z_]{1,64}$/.test(body.failureCode))
    )
  ) {
    return undefined;
  }
  return body as unknown as ExportJob;
}

function malformed<T>(): ConsoleOutcome<T> {
  return {
    ok: false,
    failure: { kind: 'malformed', detail: 'Export integrity validation failed.' },
  };
}

/** Stream at most the declared bound; never call arrayBuffer/json on an unbounded body. */
async function boundedRequest(
  context: ConsoleRequest,
  path: string,
  maxBytes: number,
  signal: AbortSignal,
  init?: ExportRequestInit,
  acceptedStatus = 200,
): Promise<ConsoleOutcome<Uint8Array>> {
  const controller = new AbortController();
  const abort = (): void => {
    controller.abort();
  };
  signal.addEventListener('abort', abort, { once: true });
  if (signal.aborted) controller.abort();
  const timeout = setTimeout(abort, REQUEST_TIMEOUT_MS);
  try {
    const response = await (context.fetchImpl ?? fetch)(context.apiBaseUrl + path, {
      ...init,
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
      signal: controller.signal,
      headers: {
        ...init?.headers,
        Accept: 'application/json, application/octet-stream',
        Authorization: `Bearer ${context.accessToken}`,
        [CORRELATION_HEADER]: crypto.randomUUID(),
      },
    });
    if (response.status === 401) return { ok: false, failure: { kind: 'unauthenticated' } };
    if (response.status === 403) return { ok: false, failure: { kind: 'forbidden' } };
    if (response.status !== acceptedStatus) {
      return {
        ok: false,
        failure: { kind: 'refused', status: response.status, detail: 'Export request refused.' },
      };
    }
    const length = response.headers.get('Content-Length');
    if (length !== null && (!/^\d+$/.test(length) || Number(length) > maxBytes)) {
      await response.body?.cancel();
      return malformed();
    }
    const reader = response.body?.getReader();
    if (reader === undefined) return malformed();
    const chunks: Uint8Array[] = [];
    let size = 0;
    try {
      for (;;) {
        const chunk = await reader.read();
        if (chunk.done) break;
        size += chunk.value.length;
        if (size > maxBytes || controller.signal.aborted) {
          await reader.cancel();
          return malformed();
        }
        chunks.push(chunk.value);
      }
    } finally {
      reader.releaseLock();
    }
    if (length !== null && Number(length) !== size) return malformed();
    const body = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) {
      body.set(chunk, offset);
      offset += chunk.length;
    }
    return { ok: true, value: body };
  } catch {
    return {
      ok: false,
      failure: { kind: 'unreachable', detail: 'Export request did not complete.' },
    };
  } finally {
    clearTimeout(timeout);
    signal.removeEventListener('abort', abort);
  }
}

function json(bytes: Uint8Array): unknown {
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) as unknown;
}

async function jobRequest(
  context: ConsoleRequest,
  path: string,
  signal: AbortSignal,
  init?: ExportRequestInit,
  acceptedStatus = 200,
): Promise<ConsoleOutcome<ExportJob>> {
  const result = await boundedRequest(context, path, 4096, signal, init, acceptedStatus);
  if (!result.ok) return result;
  try {
    const job = parseJob(json(result.value));
    return job === undefined ? malformed() : { ok: true, value: job };
  } catch {
    return malformed();
  }
}

/** The caller retains its idempotency key when retrying a lost 202 response. */
export async function submitDiagnosticExport(
  context: ConsoleRequest,
  storeId: string,
  key: string,
  signal: AbortSignal,
): Promise<ConsoleOutcome<ExportJob>> {
  if (!UUID.test(storeId) || !/^[A-Za-z0-9][A-Za-z0-9._-]{15,127}$/.test(key)) return malformed();
  const result = await jobRequest(
    context,
    `${ROOT}/stores/${storeId}/exports?window=D30`,
    signal,
    { method: 'POST', headers: { 'Idempotency-Key': key } },
    202,
  );
  return result.ok && (result.value.storeId !== storeId || result.value.window !== 'D30')
    ? malformed()
    : result;
}

/** Polling returns metadata only; no metric rows are loaded by this operation. */
export async function fetchDiagnosticExport(
  context: ConsoleRequest,
  id: string,
  signal: AbortSignal,
): Promise<ConsoleOutcome<ExportJob>> {
  if (!UUID.test(id)) return malformed();
  const result = await jobRequest(context, `${ROOT}/exports/${id}`, signal);
  return result.ok && result.value.id !== id ? malformed() : result;
}

async function hash(bytes: Uint8Array): Promise<string> {
  const result = await crypto.subtle.digest('SHA-256', new Uint8Array(bytes).buffer);
  return Array.from(new Uint8Array(result), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function parseManifest(value: unknown, job: ExportJob): ExportManifest | undefined {
  if (
    !object(value) ||
    value.schemaVersion !== 1 ||
    value.format !== 'marketops-diagnostic-ndjson-v1' ||
    value.exportId !== job.id ||
    value.storeId !== job.storeId ||
    value.window !== job.window ||
    !timestamp(value.snapshotAt) ||
    Date.parse(value.snapshotAt) !== Date.parse(job.snapshotAt ?? '') ||
    value.rowCount !== job.rowCount ||
    value.byteLength !== job.byteLength ||
    !Array.isArray(value.parts) ||
    value.parts.length !== job.completedParts ||
    value.parts.length > 64
  )
    return undefined;
  let last = 0;
  let bytes = 0;
  let number = 0;
  for (const candidate of value.parts as unknown[]) {
    number++;
    if (
      !object(candidate) ||
      candidate.partNumber !== number ||
      candidate.firstOrdinal !== last + 1 ||
      !integer(candidate.lastOrdinal, last + 1, 1000000) ||
      candidate.rowCount !== candidate.lastOrdinal - last ||
      !integer(candidate.byteLength, 1, MAX_PART_BYTES) ||
      typeof candidate.sha256 !== 'string' ||
      !SHA256.test(candidate.sha256)
    )
      return undefined;
    last = candidate.lastOrdinal;
    bytes += candidate.byteLength;
  }
  return last === job.rowCount && bytes === job.byteLength
    ? (value as unknown as ExportManifest)
    : undefined;
}

/** A Blob exists only after every bounded part matches its digest and ordinal range. */
export async function downloadDiagnosticExport(
  context: ConsoleRequest,
  job: ExportJob,
  signal: AbortSignal,
  progress: (parts: number) => void = () => undefined,
): Promise<ConsoleOutcome<Blob>> {
  if (parseJob(job) === undefined || job.state !== 'SUCCEEDED') return malformed();
  const envelope = await boundedRequest(
    context,
    `${ROOT}/exports/${job.id}/manifest`,
    131072,
    signal,
  );
  if (!envelope.ok) return envelope;
  try {
    const value = json(envelope.value);
    if (
      !object(value) ||
      typeof value.document !== 'string' ||
      typeof value.sha256 !== 'string' ||
      !SHA256.test(value.sha256)
    )
      return malformed();
    const manifestBytes = new TextEncoder().encode(value.document);
    if (manifestBytes.length > 65536 || (await hash(manifestBytes)) !== value.sha256)
      return malformed();
    const manifest = parseManifest(JSON.parse(value.document) as unknown, job);
    if (manifest === undefined) return malformed();
    const parts: ArrayBuffer[] = [];
    for (const part of manifest.parts) {
      if (signal.aborted) return malformed();
      const received = await boundedRequest(
        context,
        `${ROOT}/exports/${job.id}/parts/${String(part.partNumber)}`,
        MAX_PART_BYTES,
        signal,
      );
      if (!received.ok) return received;
      const bytes = received.value;
      if (
        bytes.length !== part.byteLength ||
        bytes[bytes.length - 1] !== 10 ||
        (await hash(bytes)) !== part.sha256 ||
        bytes.reduce((count, byte) => count + (byte === 10 ? 1 : 0), 0) !== part.rowCount
      )
        return malformed();
      // Invalid UTF-8 is refused before creating a downloadable artifact.
      new TextDecoder('utf-8', { fatal: true }).decode(bytes);
      parts.push(new Uint8Array(bytes).buffer);
      progress(part.partNumber);
    }
    if (signal.aborted) return malformed();
    return { ok: true, value: new Blob(parts, { type: 'application/x-ndjson' }) };
  } catch {
    return malformed();
  }
}
