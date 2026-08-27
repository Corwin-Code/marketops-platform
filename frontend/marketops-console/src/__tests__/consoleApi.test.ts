import { describe, expect, it, vi } from 'vitest';
import {
  createCommand,
  decide,
  fetchCommand,
  fetchCommandsNeedingAttention,
  fetchGate,
  fetchPriorityQueue,
  fetchRecommendations,
  parseCommand,
  parseDiagnosis,
  parseMetricValue,
  parsePreview,
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
