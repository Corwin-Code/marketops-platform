import { describe, expect, it, vi } from 'vitest';
import {
  CORRELATION_HEADER,
  fetchMetaStatus,
  META_STATUS_PATH,
  parseMetaStatus,
} from '../api/metaStatus';

const COMPLETE = {
  product: 'MarketOps Russia',
  application: 'marketops-server',
  environment: 'local',
  buildVersion: '0.1.0-SNAPSHOT',
  gitCommit: 'unknown',
  serverTimeUtc: '2026-08-14T10:15:30Z',
  database: { status: 'UP' },
  migration: { currentVersion: '1' },
  correlationId: '00000000-0000-4000-8000-000000000000',
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('reading the platform metadata', () => {
  it('accepts a complete payload', () => {
    const outcome = parseMetaStatus(COMPLETE);

    expect(outcome.ok).toBe(true);
    if (outcome.ok) {
      expect(outcome.value.migration.currentVersion).toBe('1');
      expect(outcome.value.database.status).toBe('UP');
    }
  });

  it('rejects a payload that is missing any field', () => {
    for (const field of Object.keys(COMPLETE)) {
      const partial: Record<string, unknown> = { ...COMPLETE };
      delete partial[field];

      const outcome = parseMetaStatus(partial);

      expect(outcome.ok, `removing ${field} must be rejected`).toBe(false);
    }
  });

  it('rejects a nested field of the wrong shape', () => {
    expect(parseMetaStatus({ ...COMPLETE, database: 'UP' }).ok).toBe(false);
    expect(parseMetaStatus({ ...COMPLETE, migration: { currentVersion: 1 } }).ok).toBe(false);
  });

  it('rejects a body that is not an object', () => {
    for (const body of [null, 'text', 42, undefined, []]) {
      expect(parseMetaStatus(body).ok, `${String(body)} must be rejected`).toBe(false);
    }
  });

  it('sends a correlation identifier the backend will accept unchanged', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(COMPLETE));

    await fetchMetaStatus('http://127.0.0.1:8080', fetchImpl as unknown as typeof fetch);

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const [url, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`http://127.0.0.1:8080${META_STATUS_PATH}`);
    const headers = init.headers as Record<string, string>;
    expect(headers[CORRELATION_HEADER]).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
    expect(init.credentials).toBe('omit');
  });

  it('reports an error status as a failing platform', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({}, 503));

    const outcome = await fetchMetaStatus(
      'http://127.0.0.1:8080',
      fetchImpl as unknown as typeof fetch,
    );

    expect(outcome.ok).toBe(false);
    if (!outcome.ok) {
      expect(outcome.failure).toEqual({ kind: 'failing', status: 503 });
    }
  });

  it('reports an unparseable body as malformed', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('not json', { status: 200 }));

    const outcome = await fetchMetaStatus(
      'http://127.0.0.1:8080',
      fetchImpl as unknown as typeof fetch,
    );

    expect(outcome.ok).toBe(false);
    if (!outcome.ok) {
      expect(outcome.failure.kind).toBe('malformed');
    }
  });

  it('reports a network failure without repeating its message', async () => {
    const fetchImpl = vi
      .fn()
      .mockRejectedValue(new TypeError('connect ECONNREFUSED 10.0.0.7:8080'));

    const outcome = await fetchMetaStatus(
      'http://127.0.0.1:8080',
      fetchImpl as unknown as typeof fetch,
    );

    expect(outcome.ok).toBe(false);
    if (!outcome.ok) {
      expect(outcome.failure.kind).toBe('unreachable');
      expect(JSON.stringify(outcome.failure)).not.toContain('10.0.0.7');
    }
  });

  it('gives up rather than waiting for a backend that never answers', async () => {
    const fetchImpl = vi.fn().mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => {
            reject(new DOMException('aborted', 'AbortError'));
          });
        }),
    );

    const outcome = await fetchMetaStatus(
      'http://127.0.0.1:8080',
      fetchImpl as unknown as typeof fetch,
      5,
    );

    expect(outcome.ok).toBe(false);
    if (!outcome.ok) {
      expect(outcome.failure.kind).toBe('unreachable');
    }
  });
});
