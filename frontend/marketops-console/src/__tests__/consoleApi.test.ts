import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createHash, webcrypto } from 'node:crypto';
import {
  downloadDiagnosticExport,
  fetchDiagnosticExport,
  submitDiagnosticExport,
} from '../api/diagnosticExport';
import type { ExportJob } from '../api/diagnosticExport';
import {
  createCommand,
  decide,
  fetchCommand,
  fetchCommandsNeedingAttention,
  fetchGate,
  fetchPriorityQueue,
  fetchRecommendations,
  fetchMetricInputs,
  fetchEvidenceSource,
  parseCommand,
  parseDiagnosis,
  parseMetricValue,
  parsePreview,
  parseAiExplanation,
} from '../api/console';
import type { ConsoleRequest } from '../api/console';

const context = (fetchImpl: typeof fetch): ConsoleRequest => ({
  apiBaseUrl: 'https://api.example.test',
  accessToken: 'token-value',
  fetchImpl,
});

function respond(body: unknown, status = 200): typeof fetch {
  return vi.fn(() =>
    Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  ) as unknown as typeof fetch;
}

describe('typed evidence edges and subject recommendation transport', () => {
  const id = '00000000-0000-4000-8000-000000000103';
  const page = {
    metricValueId: id,
    references: [{ kind: 'FACT_PROVENANCE', id }],
    truncated: false,
  };
  it('retains typed edges and an explicit truncation flag', async () => {
    const fetchImpl = respond({ ...page, truncated: true });
    expect(await fetchMetricInputs(context(fetchImpl), id, id, id)).toEqual({
      ok: true,
      value: { ...page, truncated: true },
    });
    const recommendations = respond([]);
    await fetchRecommendations(context(recommendations), id, id);
    expect(vi.mocked(recommendations).mock.calls[0]?.[0]).toContain(`?subjectId=${id}`);
  });
  it.each([
    null,
    { ...page, metricValueId: 'other' },
    { ...page, truncated: null },
    { ...page, references: {} },
    { ...page, references: Array.from({ length: 201 }, () => page.references[0]) },
    { ...page, references: [{ kind: 'URL', id }] },
    { ...page, references: [{ kind: 'METRIC_VALUE', id: 'bad' }] },
  ])('refuses malformed or wrongly bound input metadata %j', async (body) => {
    expect(await fetchMetricInputs(context(respond(body)), id, id, id)).toMatchObject({
      ok: false,
      failure: { kind: 'malformed' },
    });
  });
  it('reads only allowlisted source metadata, never raw payloads or locators', async () => {
    const source = {
      provenanceId: id,
      sourceKind: 'MANUAL_ENTRY',
      sourceTime: null,
      ingestionTime: '2026-08-28T00:00:00Z',
      contentSha256: null,
    };
    expect(
      await fetchEvidenceSource(
        context(respond({ ...source, objectRef: 'not-rendered', evidenceNote: 'not-rendered' })),
        id,
      ),
    ).toEqual({ ok: true, value: source });
    for (const altered of [
      { ...source, provenanceId: 'other' },
      { ...source, sourceTime: 'bad' },
      { ...source, ingestionTime: null },
      { ...source, contentSha256: 'bad' },
    ]) {
      expect(await fetchEvidenceSource(context(respond(altered)), id)).toMatchObject({ ok: false });
    }
  });
});

describe('asynchronous export transport and complete download integrity', () => {
  const id = '00000000-0000-4000-8000-000000000101';
  const store = '00000000-0000-4000-8000-000000000102';
  const job: ExportJob = {
    id,
    storeId: store,
    window: 'D30',
    state: 'SUCCEEDED',
    createdAt: '2026-08-28T00:00:00Z',
    snapshotAt: '2026-08-28T00:00:01Z',
    expiresAt: '2026-08-28T01:00:01Z',
    rowCount: 2,
    byteLength: 8,
    completedParts: 2,
    failureCode: null,
  };
  const digest = (text: string): string => createHash('sha256').update(text).digest('hex');
  const signal = (): AbortSignal => new AbortController().signal;
  const manifest = {
    schemaVersion: 1,
    format: 'marketops-diagnostic-ndjson-v1',
    exportId: id,
    storeId: store,
    window: 'D30',
    snapshotAt: job.snapshotAt,
    rowCount: 2,
    byteLength: 8,
    parts: [1, 2].map((n) => ({
      partNumber: n,
      firstOrdinal: n,
      lastOrdinal: n,
      rowCount: 1,
      byteLength: 4,
      sha256: digest('{} \n'),
    })),
  };
  beforeEach(() => {
    vi.stubGlobal('crypto', webcrypto);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function replies(document: unknown = manifest, bodies = ['{} \n', '{} \n']): typeof fetch {
    let index = 0;
    const text = JSON.stringify(document);
    return vi.fn(() =>
      Promise.resolve(
        index++ === 0
          ? new Response(JSON.stringify({ document: text, sha256: digest(text) }))
          : new Response(bodies[index - 2]),
      ),
    ) as unknown as typeof fetch;
  }

  it('requires 202 and preserves the request key with bearer-only transport', async () => {
    const send = respond({ ...job, state: 'QUEUED' }, 202);
    const result = await submitDiagnosticExport(
      context(send),
      store,
      'idempotent-export-key',
      signal(),
    );
    expect(result.ok).toBe(true);
    expect(send).toHaveBeenCalledWith(
      expect.stringContaining(`/stores/${store}/exports`),
      expect.objectContaining({
        method: 'POST',
        credentials: 'omit',
        redirect: 'error',
        cache: 'no-store',
        headers: expect.objectContaining({
          'Idempotency-Key': 'idempotent-export-key',
          Authorization: 'Bearer token-value',
        }),
      }),
    );
    expect(
      (
        await submitDiagnosticExport(
          context(respond(job)),
          store,
          'idempotent-export-key',
          signal(),
        )
      ).ok,
    ).toBe(false);
    expect(
      (await submitDiagnosticExport(context(send), '../store', 'idempotent-export-key', signal()))
        .ok,
    ).toBe(false);
    expect((await submitDiagnosticExport(context(send), store, 'short', signal())).ok).toBe(false);
  });

  it('accepts only status for the exact requested job with bounded counters', async () => {
    expect((await fetchDiagnosticExport(context(respond(job)), id, signal())).ok).toBe(true);
    for (const body of [
      null,
      { ...job, id: store },
      { ...job, rowCount: 1000001 },
      { ...job, byteLength: 268435457 },
      { ...job, completedParts: 65 },
      { ...job, state: 'OK' },
      { ...job, createdAt: 'invalid' },
      { ...job, failureCode: '<script>' },
    ]) {
      expect((await fetchDiagnosticExport(context(respond(body)), id, signal())).ok).toBe(false);
    }
    expect((await fetchDiagnosticExport(context(respond(job)), '../bad', signal())).ok).toBe(false);
  });

  it.each([
    [401, 'unauthenticated'],
    [403, 'forbidden'],
    [429, 'refused'],
  ] as const)('classifies HTTP %s without reflecting a response body', async (status, kind) => {
    const result = await fetchDiagnosticExport(
      context(respond({ unsafe: 'private-canary' }, status)),
      id,
      signal(),
    );
    expect(result).toMatchObject({ ok: false, failure: { kind } });
    expect(JSON.stringify(result)).not.toContain('private-canary');
  });

  it('constructs the Blob only after verifying every part and reports bounded progress', async () => {
    const progress = vi.fn();
    const send = replies();
    const result = await downloadDiagnosticExport(context(send), job, signal(), progress);
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.size).toBe(8);
      expect(result.value.type).toBe('application/x-ndjson');
    }
    expect(progress.mock.calls).toEqual([[1], [2]]);
    expect(send).toHaveBeenCalledTimes(3);
  });

  it.each([
    { ...manifest, schemaVersion: 2 },
    { ...manifest, exportId: store },
    { ...manifest, storeId: id },
    { ...manifest, window: 'D7' },
    { ...manifest, snapshotAt: '2026-08-28T00:10:00Z' },
    { ...manifest, byteLength: 9 },
    { ...manifest, parts: [] },
    { ...manifest, parts: [...manifest.parts].reverse() },
    { ...manifest, parts: manifest.parts.map((part) => ({ ...part, firstOrdinal: 2 })) },
    { ...manifest, parts: manifest.parts.map((part) => ({ ...part, sha256: 'bad' })) },
    { ...manifest, parts: manifest.parts.map((part) => ({ ...part, byteLength: 4194305 })) },
  ])('refuses a structurally inconsistent manifest before reading parts', async (invalid) => {
    const send = replies(invalid);
    expect((await downloadDiagnosticExport(context(send), job, signal())).ok).toBe(false);
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('refuses a wrong manifest digest and a corrupt late part without publishing a Blob', async () => {
    expect(
      (
        await downloadDiagnosticExport(
          context(respond({ document: JSON.stringify(manifest), sha256: 'a'.repeat(64) })),
          job,
          signal(),
        )
      ).ok,
    ).toBe(false);
    const progress = vi.fn();
    expect(
      (
        await downloadDiagnosticExport(
          context(replies(manifest, ['{} \n', 'bad\n'])),
          job,
          signal(),
          progress,
        )
      ).ok,
    ).toBe(false);
    expect(progress.mock.calls).toEqual([[1]]);
  });

  it('refuses valid hashes that describe missing newlines or incorrect row counts', async () => {
    for (const body of ['{}  ', '{}\n\n']) {
      const bad = {
        ...manifest,
        parts: manifest.parts.map((part) => ({ ...part, sha256: digest(body) })),
      };
      expect(
        (await downloadDiagnosticExport(context(replies(bad, [body, body])), job, signal())).ok,
      ).toBe(false);
    }
  });

  it('bounds streaming bytes even when Content-Length is missing or dishonest', async () => {
    for (const response of [
      new Response('x'.repeat(4097)),
      new Response('{}', { headers: { 'Content-Length': '999999999' } }),
      new Response('{}', { headers: { 'Content-Length': '1' } }),
      new Response('{}', { headers: { 'Content-Length': '-1' } }),
      new Response(new Uint8Array([255, 254])),
      new Response('not json'),
      new Response(null),
    ]) {
      const send = vi.fn(() => Promise.resolve(response)) as unknown as typeof fetch;
      expect((await fetchDiagnosticExport(context(send), id, signal())).ok).toBe(false);
    }
  });

  it('does not download an unfinished job, and an aborted download cannot return a Blob', async () => {
    const send = replies();
    expect(
      (await downloadDiagnosticExport(context(send), { ...job, state: 'RUNNING' }, signal())).ok,
    ).toBe(false);
    expect(send).not.toHaveBeenCalled();
    const abort = new AbortController();
    abort.abort();
    expect((await downloadDiagnosticExport(context(send), job, abort.signal)).ok).toBe(false);
    const failing = vi.fn(() =>
      Promise.reject(new Error('private-canary')),
    ) as unknown as typeof fetch;
    expect(await fetchDiagnosticExport(context(failing), id, signal())).toMatchObject({
      ok: false,
      failure: { kind: 'unreachable' },
    });
  });

  it('supports an explicitly empty snapshot with a zero-part manifest', async () => {
    const emptyJob = { ...job, rowCount: 0, byteLength: 0, completedParts: 0 };
    const send = replies({ ...manifest, rowCount: 0, byteLength: 0, parts: [] });
    const result = await downloadDiagnosticExport(context(send), emptyJob, signal());
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.value.size).toBe(0);
    expect(send).toHaveBeenCalledTimes(1);
  });
});

describe('AI response schema refuses silent data loss and false complete success', () => {
  const claim = {
    claimId: 'claim-1',
    kind: 'INFERENCE',
    ordinal: 1,
    statement: 'Stock may limit demand.',
    confidenceLabel: 'LOW',
    metricValueRefs: [],
    findingRefs: [],
    payload: { counterEvidence: ['Traffic may have changed.'] },
    accepted: true,
    rejectionCode: null,
  };
  const valid = {
    invocationId: 'invocation-1',
    subjectId: 'variant-1',
    outputSchemaVersion: 2,
    state: 'SUCCEEDED',
    degraded: false,
    failureCode: null,
    claims: [claim],
  };

  it('retains nested arrays and objects after parsing', () => {
    expect(parseAiExplanation(valid)?.claims[0]?.payload).toEqual(claim.payload);
  });

  it.each([
    null,
    [],
    { ...valid, outputSchemaVersion: 3 },
    { ...valid, state: 'INVENTED' },
    { ...valid, claims: [claim, null] },
    { ...valid, claims: [] },
    { ...valid, degraded: true },
    { ...valid, state: 'PARTIAL_OUTPUT_REJECTED', degraded: true },
    { ...valid, state: 'OUTPUT_REJECTED', degraded: true },
    { ...valid, claims: [{ ...claim, ordinal: 1.5 }] },
    { ...valid, claims: [{ ...claim, confidenceLabel: ['LOW'] }] },
    { ...valid, claims: [{ ...claim, metricValueRefs: [7] }] },
    { ...valid, claims: [{ ...claim, accepted: false, rejectionCode: null }] },
    { ...valid, claims: [{ ...claim, payload: { proposedParameters: { targetPrice: 123 } } }] },
    { ...valid, claims: [{ ...claim, payload: { value: 'x'.repeat(2001) } }] },
    { ...valid, claims: [{ ...claim, payload: [] }] },
    { ...valid, claims: [{ ...claim, payload: { counterEvidence: [true, null, 123.5] } }] },
    { ...valid, claims: Array.from({ length: 81 }, () => claim) },
  ])('rejects malformed or inconsistent output %#', (body) => {
    expect(parseAiExplanation(body)).toBeUndefined();
  });
});

describe('TC-UI-020 a request carries who is asking and can be traced', () => {
  it('sends the token and a correlation identifier', async () => {
    let seen: RequestInit | undefined;
    const send = vi.fn((_url: unknown, init?: RequestInit) => {
      seen = init;
      return Promise.resolve(
        new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      );
    }) as unknown as typeof fetch;

    await fetchPriorityQueue(context(send), 'store-1');

    const headers = seen?.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer token-value');
    expect(headers['X-Correlation-ID']).toMatch(/^[0-9a-f-]{36}$/);
    expect(seen?.credentials).toBe('omit');
  });

  it('tells an ended session apart from a missing grant', async () => {
    const signedOut = await fetchPriorityQueue(context(respond({}, 401)), 'store-1');
    const notGranted = await fetchPriorityQueue(context(respond({}, 403)), 'store-1');

    expect(signedOut.ok ? undefined : signedOut.failure.kind).toBe('unauthenticated');
    expect(notGranted.ok ? undefined : notGranted.failure.kind).toBe('forbidden');
  });

  it('tells a step-up requirement apart from a missing grant', async () => {
    const outcome = await fetchPriorityQueue(
      context(respond({ type: 'https://marketops/problems/step-up-required' }, 403)),
      'store-1',
    );

    expect(outcome.ok ? undefined : outcome.failure.kind).toBe('step-up-required');
  });

  it('reports a body it cannot read rather than rendering it', async () => {
    const send = vi.fn(() =>
      Promise.resolve(
        new Response('not json', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      ),
    ) as unknown as typeof fetch;

    const outcome = await fetchCommand(context(send), 'command-1');

    expect(outcome.ok ? undefined : outcome.failure.kind).toBe('malformed');
  });
});

describe('TC-UI-021 an amount survives the trip to the screen exactly', () => {
  it('keeps a decimal as text rather than parsing it', () => {
    const metric = parseMetricValue({
      metricValueId: 'value-1',
      metricCode: 'UNIT_COST',
      valueState: 'AVAILABLE',
      confidenceState: 'CANONICAL_CONFIRMED',
      numericValue: '1234.5678',
      currencyCode: 'RUB',
    });

    expect(metric?.numericValue).toBe('1234.5678');
  });

  it('keeps a numeric amount without losing its digits', () => {
    const preview = parsePreview({
      recommendationId: 'rec-1',
      proposedPrice: 105.25,
      verdict: { evaluationId: 'eval-1', passed: true, reasons: [] },
    });

    expect(preview?.proposedPrice).toBe('105.25');
  });

  it('refuses a value whose state is missing rather than assuming it', () => {
    expect(parseMetricValue({ metricValueId: 'value-1', metricCode: 'UNIT_COST' })).toBeUndefined();
  });
});

describe('TC-UI-022 a partial answer is refused rather than half-rendered', () => {
  it('refuses a command with no state', () => {
    expect(parseCommand({ id: 'c1', priorPrice: '1', targetPrice: '2' })).toBeUndefined();
  });

  it('refuses a preview whose verdict is missing', () => {
    expect(parsePreview({ recommendationId: 'r1', proposedPrice: '1' })).toBeUndefined();
  });

  it('drops an unreadable finding rather than the whole diagnosis', () => {
    const diagnosis = parseDiagnosis({
      subjectId: 's1',
      storeId: 'store-1',
      metrics: {},
      findings: [
        {
          findingId: 'f1',
          ruleCode: 'NEGATIVE_MARGIN',
          outcome: 'TRIGGERED',
          severity: 'CRITICAL',
        },
        { ruleCode: 'BROKEN' },
      ],
    });

    expect(diagnosis?.findings).toHaveLength(1);
    expect(diagnosis?.findings[0]?.ruleCode).toBe('NEGATIVE_MARGIN');
  });

  it('keeps every readback a command carries', () => {
    const command = parseCommand({
      id: 'c1',
      state: 'READBACK_MISMATCH',
      fulfillmentModeCode: 'SELLER_FULFILLED',
      priorPrice: '100.0000',
      targetPrice: '105.0000',
      readbacks: [
        {
          id: 'r1',
          observedAt: '2026-08-27T09:00:00Z',
          matchState: 'DIFFERENT',
          observedPrice: '140.0000',
        },
      ],
      attempts: [],
    });

    expect(command?.readbacks).toHaveLength(1);
    expect(command?.fulfillmentModeCode).toBe('SELLER_FULFILLED');
    expect(command?.readbacks[0]?.observedPrice).toBe('140.0000');
  });
});

describe('TC-UI-023 every console route is reachable and refuses honestly', () => {
  it('asks for the store queue, the diagnosis and the proposals', async () => {
    const seen: string[] = [];
    const send = vi.fn((url: unknown) => {
      seen.push(new URL(String(url)).pathname + new URL(String(url)).search);
      return Promise.resolve(
        new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      );
    }) as unknown as typeof fetch;

    await fetchPriorityQueue(context(send), 'store-1', 'D7');
    await fetchRecommendations(context(send), 'store-1');
    await fetchCommandsNeedingAttention(context(send), 'store-1');

    expect(seen).toEqual([
      '/api/v1/console/diagnosis/stores/store-1/queue?window=D7',
      '/api/v1/console/workflow/stores/store-1/recommendations',
      '/api/v1/console/commands/stores/store-1/needing-attention',
    ]);
  });

  it('sends a decision with its reason and the version it read', async () => {
    let sent = '';
    const send = vi.fn((_url: unknown, init?: RequestInit) => {
      sent = typeof init?.body === 'string' ? init.body : '';
      return Promise.resolve(
        new Response(JSON.stringify({ decisionId: 'd1', state: 'APPROVED' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    }) as unknown as typeof fetch;

    const outcome = await decide(context(send), 'rec-1', 'approval', 'margin recovery', 4);

    expect(outcome.ok ? outcome.value.state : undefined).toBe('APPROVED');
    expect(JSON.parse(sent)).toEqual({ reason: 'margin recovery', expectedVersion: 4 });
  });

  it('creates a command and reports its identifier', async () => {
    const send = respond({ commandId: 'cmd-1' });

    const outcome = await createCommand(context(send), 'rec-1', 5);

    expect(outcome.ok ? outcome.value.commandId : undefined).toBe('cmd-1');
  });

  it('reads the gate as a list of reasons rather than as a single flag', async () => {
    const send = respond({ open: false, blockingReasons: ['GLOBAL_SWITCH_DISABLED'] });

    const outcome = await fetchGate(context(send), 'cmd-1');

    expect(outcome.ok ? outcome.value.blockingReasons : undefined).toEqual([
      'GLOBAL_SWITCH_DISABLED',
    ]);
  });

  it('reports an unreachable platform without naming an internal host', async () => {
    const send = vi.fn(() =>
      Promise.reject(new TypeError('failed to fetch https://internal.example')),
    ) as unknown as typeof fetch;

    const outcome = await fetchCommand(context(send), 'cmd-1');

    expect(outcome.ok).toBe(false);
    if (outcome.ok) {
      return;
    }
    expect(outcome.failure.kind).toBe('unreachable');
    expect(JSON.stringify(outcome.failure)).not.toContain('internal.example');
  });

  it('reports a refusal by status rather than by the body it was given', async () => {
    const send = respond({ detail: 'internal-host-name' }, 500);

    const outcome = await fetchCommand(context(send), 'cmd-1');

    expect(outcome.ok).toBe(false);
    if (outcome.ok) {
      return;
    }
    expect(outcome.failure.kind).toBe('refused');
    expect(JSON.stringify(outcome.failure)).not.toContain('internal-host-name');
  });
});
