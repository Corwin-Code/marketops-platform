/**
 * The one request the console makes.
 *
 * The response is validated field by field before it is used. It arrives from a
 * resource that answers without authentication, and a console that rendered
 * whatever it received would turn a backend defect into a rendering defect that
 * looks like something else entirely.
 */

/** Path of the metadata resource. */
export const META_STATUS_PATH = '/api/v1/meta/status';

/** Header carrying the correlation identifier in both directions. */
export const CORRELATION_HEADER = 'X-Correlation-ID';

/** How long the console waits before treating the backend as unreachable. */
export const REQUEST_TIMEOUT_MS = 5000;

/** Metadata the backend publishes about itself. */
export interface MetaStatus {
  readonly product: string;
  readonly application: string;
  readonly environment: string;
  readonly buildVersion: string;
  readonly gitCommit: string;
  readonly serverTimeUtc: string;
  readonly database: { readonly status: string };
  readonly migration: { readonly currentVersion: string };
  readonly correlationId: string;
}

/** Why a request did not produce metadata. */
export type MetaStatusFailure =
  | { readonly kind: 'unreachable'; readonly detail: string }
  | { readonly kind: 'failing'; readonly status: number }
  | { readonly kind: 'malformed'; readonly detail: string };

/** Outcome of one request. */
export type MetaStatusOutcome =
  | { readonly ok: true; readonly value: MetaStatus }
  | { readonly ok: false; readonly failure: MetaStatusFailure };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function stringAt(source: Record<string, unknown>, key: string): string | undefined {
  const value = source[key];
  return typeof value === 'string' ? value : undefined;
}

function nestedString(
  source: Record<string, unknown>,
  key: string,
  nested: string,
): string | undefined {
  const value = source[key];
  return isRecord(value) ? stringAt(value, nested) : undefined;
}

/**
 * Convert a decoded body into metadata, or report why it is not metadata.
 *
 * Every field is required. A partially populated payload would let the console
 * display a confident answer built from values that were never sent.
 */
export function parseMetaStatus(body: unknown): MetaStatusOutcome {
  if (!isRecord(body)) {
    return { ok: false, failure: { kind: 'malformed', detail: 'the body is not an object' } };
  }

  const product = stringAt(body, 'product');
  const application = stringAt(body, 'application');
  const environment = stringAt(body, 'environment');
  const buildVersion = stringAt(body, 'buildVersion');
  const gitCommit = stringAt(body, 'gitCommit');
  const serverTimeUtc = stringAt(body, 'serverTimeUtc');
  const databaseStatus = nestedString(body, 'database', 'status');
  const currentVersion = nestedString(body, 'migration', 'currentVersion');
  const correlationId = stringAt(body, 'correlationId');

  const missing = Object.entries({
    product,
    application,
    environment,
    buildVersion,
    gitCommit,
    serverTimeUtc,
    'database.status': databaseStatus,
    'migration.currentVersion': currentVersion,
    correlationId,
  })
    .filter(([, value]) => value === undefined)
    .map(([name]) => name);

  if (
    missing.length > 0 ||
    product === undefined ||
    application === undefined ||
    environment === undefined ||
    buildVersion === undefined ||
    gitCommit === undefined ||
    serverTimeUtc === undefined ||
    databaseStatus === undefined ||
    currentVersion === undefined ||
    correlationId === undefined
  ) {
    return {
      ok: false,
      failure: { kind: 'malformed', detail: `missing or non-textual: ${missing.join(', ')}` },
    };
  }

  return {
    ok: true,
    value: {
      product,
      application,
      environment,
      buildVersion,
      gitCommit,
      serverTimeUtc,
      database: { status: databaseStatus },
      migration: { currentVersion },
      correlationId,
    },
  };
}

/** Produce an identifier the backend accepts without replacing it. */
export function newCorrelationId(): string {
  return crypto.randomUUID();
}

/**
 * Ask the backend what it is and how it is doing.
 *
 * The request carries a generated correlation identifier so a console report
 * can be matched against the server's own records, and it is abandoned after a
 * fixed interval so an unreachable backend is reported rather than waited for.
 */
export async function fetchMetaStatus(
  apiBaseUrl: string,
  fetchImpl: typeof fetch = fetch,
  timeoutMs: number = REQUEST_TIMEOUT_MS,
  cancellation?: AbortSignal,
): Promise<MetaStatusOutcome> {
  const controller = new AbortController();
  const cancelFromCaller = (): void => {
    controller.abort(cancellation?.reason);
  };
  if (cancellation?.aborted === true) {
    cancelFromCaller();
  } else {
    cancellation?.addEventListener('abort', cancelFromCaller, { once: true });
  }
  const timer = setTimeout(() => {
    controller.abort();
  }, timeoutMs);

  try {
    const response = await fetchImpl(`${apiBaseUrl}${META_STATUS_PATH}`, {
      method: 'GET',
      headers: { Accept: 'application/json', [CORRELATION_HEADER]: newCorrelationId() },
      signal: controller.signal,
      credentials: 'omit',
      cache: 'no-store',
    });

    if (!response.ok) {
      return { ok: false, failure: { kind: 'failing', status: response.status } };
    }

    let body: unknown;
    try {
      body = await response.json();
    } catch {
      return {
        ok: false,
        failure: { kind: 'malformed', detail: 'the body was not valid JSON' },
      };
    }

    return parseMetaStatus(body);
  } catch (error) {
    // The message of a network error can name an internal host. Only the class
    // of failure is kept, which is what the operator acts on.
    const detail = error instanceof Error || error instanceof DOMException ? error.name : 'unknown';
    return { ok: false, failure: { kind: 'unreachable', detail } };
  } finally {
    clearTimeout(timer);
    cancellation?.removeEventListener('abort', cancelFromCaller);
  }
}
