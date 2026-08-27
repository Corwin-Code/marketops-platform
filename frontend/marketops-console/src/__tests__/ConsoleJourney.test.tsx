import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DiagnosticExportPanel } from '../diagnosis/DiagnosticExportPanel';
import { MetricEvidencePanel } from '../diagnosis/MetricEvidencePanel';
import { SubjectRecommendations } from '../workflow/SubjectRecommendations';
import * as exportApi from '../api/diagnosticExport';
import type { ExportJob } from '../api/diagnosticExport';
import { CommandTimeline } from '../commands/CommandTimeline';
import { SubjectDiagnosisView } from '../diagnosis/SubjectDiagnosisView';
import { AiExplanationPanel } from '../diagnosis/AiExplanationPanel';
import { PriorityQueue } from '../queue/PriorityQueue';
import { RecommendationReview } from '../workflow/RecommendationReview';
import type { ConsoleRequest, Recommendation } from '../api/console';

/** Answer each path with a prepared body, and record what was asked for. */
function router(routes: Readonly<Record<string, unknown>>): {
  readonly fetchImpl: typeof fetch;
  readonly calls: string[];
} {
  const calls: string[] = [];
  const fetchImpl = vi.fn((url: unknown) => {
    const path = new URL(String(url)).pathname;
    calls.push(path);
    const body = routes[path];
    return Promise.resolve(
      body === undefined
        ? new Response('{}', { status: 404 })
        : new Response(JSON.stringify(body), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
    );
  }) as unknown as typeof fetch;
  return { fetchImpl, calls };
}

const context = (fetchImpl: typeof fetch): ConsoleRequest => ({
  apiBaseUrl: 'https://api.example.test',
  accessToken: 'token',
  fetchImpl,
});

describe('asynchronous export operator controls', () => {
  const job: ExportJob = {
    id: '00000000-0000-4000-8000-000000000101',
    storeId: '00000000-0000-4000-8000-000000000102',
    window: 'D30',
    state: 'SUCCEEDED',
    createdAt: '2026-08-28T00:00:00Z',
    snapshotAt: '2026-08-28T00:00:01Z',
    expiresAt: '2026-08-28T01:00:01Z',
    rowCount: 20000,
    byteLength: 8600000,
    completedParts: 3,
    failureCode: null,
  };
  const requestContext = context(vi.fn() as unknown as typeof fetch);
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('retries a lost submission with the same idempotency key', async () => {
    const submit = vi
      .spyOn(exportApi, 'submitDiagnosticExport')
      .mockResolvedValueOnce({ ok: false, failure: { kind: 'unreachable', detail: 'timeout' } })
      .mockResolvedValueOnce({ ok: true, value: job });
    render(<DiagnosticExportPanel context={requestContext} storeId={job.storeId} />);
    expect(submit).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Prepare export' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('same request key');
    fireEvent.click(screen.getByRole('button', { name: 'Prepare export' }));
    await screen.findByRole('button', { name: 'Download verified export' });
    expect(submit.mock.calls[0]?.[2]).toBe(submit.mock.calls[1]?.[2]);
  });

  it('polls queued metadata and then exposes the verified-download action', async () => {
    vi.useFakeTimers();
    vi.spyOn(exportApi, 'submitDiagnosticExport').mockResolvedValue({
      ok: true,
      value: { ...job, state: 'QUEUED', rowCount: 0 },
    });
    const poll = vi
      .spyOn(exportApi, 'fetchDiagnosticExport')
      .mockResolvedValue({ ok: true, value: job });
    render(<DiagnosticExportPanel context={requestContext} storeId={job.storeId} />);
    await act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Prepare export' }));
      return Promise.resolve();
    });
    expect(
      screen.queryByRole('button', { name: 'Download verified export' }),
    ).not.toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(poll).toHaveBeenCalledWith(requestContext, job.id, expect.any(AbortSignal));
    expect(screen.getByRole('button', { name: 'Download verified export' })).toBeEnabled();
    expect(screen.getByText(/20000/)).toBeInTheDocument();
  });

  it('never creates a downloadable URL after partial integrity failure and can refresh expiry', async () => {
    vi.spyOn(exportApi, 'submitDiagnosticExport').mockResolvedValue({ ok: true, value: job });
    vi.spyOn(exportApi, 'downloadDiagnosticExport').mockResolvedValue({
      ok: false,
      failure: { kind: 'malformed', detail: 'integrity' },
    });
    vi.spyOn(exportApi, 'fetchDiagnosticExport').mockResolvedValue({
      ok: true,
      value: { ...job, state: 'EXPIRED' },
    });
    const create = vi.fn(() => 'blob:synthetic-export');
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: create });
    render(<DiagnosticExportPanel context={requestContext} storeId={job.storeId} />);
    fireEvent.click(screen.getByRole('button', { name: 'Prepare export' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Download verified export' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No file was saved');
    expect(create).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh export status' }));
    fireEvent.click(await screen.findByRole('button', { name: 'New export' }));
    expect(screen.getByRole('button', { name: 'Prepare export' })).toBeEnabled();
  });

  it('sends only a fully verified Blob to the browser and releases its URL', async () => {
    vi.spyOn(exportApi, 'submitDiagnosticExport').mockResolvedValue({ ok: true, value: job });
    vi.spyOn(exportApi, 'downloadDiagnosticExport').mockImplementation(
      (_context, _job, _signal, progress) => {
        progress?.(3);
        return Promise.resolve({ ok: true, value: new Blob(['{}\n']) });
      },
    );
    const create = vi.fn(() => 'blob:synthetic-export');
    const revoke = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: create });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revoke });
    const click = vi.fn();
    const listener = new AbortController();
    document.addEventListener(
      'click',
      (event) => {
        if (event.target instanceof HTMLAnchorElement) {
          event.preventDefault();
          click(event.target.download);
        }
      },
      { signal: listener.signal },
    );
    render(<DiagnosticExportPanel context={requestContext} storeId={job.storeId} />);
    fireEvent.click(screen.getByRole('button', { name: 'Prepare export' }));
    const button = await screen.findByRole('button', { name: 'Download verified export' });
    vi.useFakeTimers();
    await act(() => {
      fireEvent.click(button);
      return Promise.resolve();
    });
    expect(create).toHaveBeenCalledOnce();
    expect(click).toHaveBeenCalledOnce();
    expect(click).toHaveBeenCalledWith(`diagnostic-${job.id}.ndjson`);
    listener.abort();
    expect(screen.getByRole('status')).toHaveTextContent('download manager');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(revoke).toHaveBeenCalledWith('blob:synthetic-export');
  });

  it('aborts waiting on unmount and does not claim background cancellation', () => {
    const submit = vi
      .spyOn(exportApi, 'submitDiagnosticExport')
      .mockReturnValue(new Promise(() => undefined));
    const view = render(<DiagnosticExportPanel context={requestContext} storeId={job.storeId} />);
    fireEvent.click(screen.getByRole('button', { name: 'Prepare export' }));
    const signal = submit.mock.calls[0]?.[3];
    fireEvent.click(screen.getByRole('button', { name: 'Stop waiting' }));
    expect(signal?.aborted).toBe(true);
    expect(screen.getByRole('status')).toHaveTextContent('Background work may continue');
    view.unmount();
  });
});

describe('AI partial output remains advisory and preserves the complete explanation', () => {
  const accepted = {
    claimId: 'claim-1',
    kind: 'RECOMMENDATION',
    ordinal: 1,
    statement: 'Review demand sensitivity.',
    confidenceLabel: 'LOW',
    metricValueRefs: [],
    findingRefs: [],
    accepted: true,
    rejectionCode: null,
    payload: {
      proposedParameters: { targetPrice: '99999999999999.9999', currencyCode: 'RUB' },
      expectedEffect: {
        metric: 'CONTRIBUTION_PROFIT',
        direction: 'INCREASE',
        rationale: 'Observe demand.',
      },
      risk: { level: 'HIGH', description: 'Demand is unknown.' },
      validationWindowDays: 14,
    },
  };
  const partial = {
    invocationId: 'invocation-1',
    subjectId: 'variant-1',
    outputSchemaVersion: 2,
    state: 'PARTIAL_OUTPUT_REJECTED',
    degraded: true,
    failureCode: 'SCHEMA_INVALID',
    claims: [
      accepted,
      {
        ...accepted,
        claimId: 'claim-2',
        accepted: false,
        rejectionCode: 'SCHEMA_INVALID',
        statement: 'Malformed suggestion',
        payload: {},
      },
    ],
  };

  it('waits for an explicit request and shows partial rejection beside exact nested parameters', async () => {
    const { fetchImpl, calls } = router({
      '/api/v1/console/explanations/listing-variants/variant-1': partial,
    });
    render(
      <AiExplanationPanel context={context(fetchImpl)} subjectId="variant-1" storeId="store-1" />,
    );
    expect(calls).toEqual([]);
    fireEvent.click(screen.getByRole('button', { name: 'Request explanation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Partial explanation');
    expect(screen.getByText(/99999999999999.9999/)).toHaveTextContent('Observe demand.');
    expect(screen.getByText('Rejected model claims (1)')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /approve|execute|create command/i }),
    ).not.toBeInTheDocument();
    expect(calls).toHaveLength(1);
  });

  it.each([
    ['REFUSED', true, 'NO_ELIGIBLE_PROVIDER', 'Explanation unavailable'],
    ['PROVIDER_OUTCOME_UNKNOWN', true, null, 'Explanation unavailable'],
    ['DISPATCHED', false, null, 'still pending'],
  ])(
    'presents %s without inventing a successful explanation',
    async (state, degraded, failureCode, message) => {
      const { fetchImpl } = router({
        '/api/v1/console/explanations/listing-variants/variant-1': {
          ...partial,
          state,
          degraded,
          failureCode,
          claims: [],
        },
      });
      render(
        <AiExplanationPanel context={context(fetchImpl)} subjectId="variant-1" storeId="store-1" />,
      );
      fireEvent.click(screen.getByRole('button', { name: 'Request explanation' }));
      expect(await screen.findByText(new RegExp(message))).toBeInTheDocument();
    },
  );

  it('distinguishes complete validated output from partial output', async () => {
    const { fetchImpl } = router({
      '/api/v1/console/explanations/listing-variants/variant-1': {
        ...partial,
        state: 'SUCCEEDED',
        degraded: false,
        failureCode: null,
        claims: [accepted],
      },
    });
    render(
      <AiExplanationPanel context={context(fetchImpl)} subjectId="variant-1" storeId="store-1" />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Request explanation' }));
    expect(await screen.findByText(/All displayed claims passed/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('refuses a response for a different subject and never automatically retries', async () => {
    const { fetchImpl, calls } = router({
      '/api/v1/console/explanations/listing-variants/variant-1': {
        ...partial,
        subjectId: 'foreign-subject',
      },
    });
    render(
      <AiExplanationPanel context={context(fetchImpl)} subjectId="variant-1" storeId="store-1" />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Request explanation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('malformed');
    expect(screen.queryByText('Review demand sensitivity.')).not.toBeInTheDocument();
    expect(calls).toHaveLength(1);
  });

  it('shows backend refusal without treating it as empty successful output', async () => {
    const { fetchImpl, calls } = router({});
    render(
      <AiExplanationPanel context={context(fetchImpl)} subjectId="variant-1" storeId="store-1" />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Request explanation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('refused');
    expect(calls).toHaveLength(1);
  });
});

describe('TC-UI-030 the work list says what can and cannot be acted on', () => {
  it('shows why a subject is blocked before the operator opens it', async () => {
    const { fetchImpl } = router({
      '/api/v1/console/diagnosis/stores/store-1/queue': [
        {
          subjectId: 'variant-1',
          storeId: 'store-1',
          priorityScore: '820.0000',
          criticalFindingCount: 2,
          warningFindingCount: 1,
          declinedRuleCount: 0,
          netSales: '12000.0000',
          contributionProfit: '-400.0000',
          currencyCode: 'RUB',
          blockingRuleCodes: ['DATA_BLOCKED'],
        },
      ],
    });

    render(<PriorityQueue context={context(fetchImpl)} storeId="store-1" onSelect={vi.fn()} />);

    const cell = await screen.findByText(/Blocked: DATA_BLOCKED/);
    expect(cell).toHaveAttribute('data-write-blocked', 'true');
  });

  it('renders a missing amount as an absence rather than as zero', async () => {
    const { fetchImpl } = router({
      '/api/v1/console/diagnosis/stores/store-1/queue': [
        {
          subjectId: 'variant-2',
          storeId: 'store-1',
          priorityScore: '10.0000',
          criticalFindingCount: 0,
          warningFindingCount: 0,
          declinedRuleCount: 3,
          netSales: null,
          contributionProfit: null,
          currencyCode: null,
          blockingRuleCodes: [],
        },
      ],
    });

    render(<PriorityQueue context={context(fetchImpl)} storeId="store-1" onSelect={vi.fn()} />);

    await screen.findByText('variant-2');
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2);
  });

  it('says what an operator should do about each kind of refusal', async () => {
    const send = vi.fn(() =>
      Promise.resolve(new Response('{}', { status: 401 })),
    ) as unknown as typeof fetch;

    render(<PriorityQueue context={context(send)} storeId="store-1" onSelect={vi.fn()} />);

    expect(await screen.findByRole('alert')).toHaveTextContent(/Sign in again/);
  });
});

describe('TC-UI-031 a rule that could not answer is as visible as one that fired', () => {
  it('shows a declined rule with its reason', async () => {
    const { fetchImpl } = router({
      '/api/v1/console/diagnosis/listing-variants/variant-1': {
        subjectId: 'variant-1',
        storeId: 'store-1',
        window: 'D30',
        metrics: {
          UNIT_COST: {
            metricValueId: 'm1',
            metricCode: 'UNIT_COST',
            valueState: 'NOT_AVAILABLE',
            numericValue: null,
            currencyCode: null,
            confidenceState: 'INCOMPLETE',
            estimated: false,
            freshnessSeconds: null,
            evidenceRefs: [],
          },
        },
        findings: [
          {
            findingId: 'f1',
            ruleCode: 'NEGATIVE_MARGIN',
            outcome: 'DECLINED',
            severity: 'INFO',
            declineReason: 'UNIT_COST is unavailable',
            detail: {},
            blocksExecution: true,
            metricValueIds: ['m1'],
          },
        ],
      },
    });

    render(
      <SubjectDiagnosisView
        context={context(fetchImpl)}
        subjectId="variant-1"
        storeId="store-1"
        onBack={vi.fn()}
      />,
    );

    const finding = await screen.findByText('NEGATIVE_MARGIN');
    expect(finding.closest('li')).toHaveAttribute('data-outcome', 'DECLINED');
    expect(screen.getByText(/UNIT_COST is unavailable/)).toBeInTheDocument();
    expect(screen.getByTestId('blocks-write')).toBeInTheDocument();
  });

  it('never presents an unavailable value as confirmed', async () => {
    const { fetchImpl } = router({
      '/api/v1/console/diagnosis/listing-variants/variant-1': {
        subjectId: 'variant-1',
        storeId: 'store-1',
        window: 'D30',
        metrics: {
          UNIT_COST: {
            metricValueId: 'm1',
            metricCode: 'UNIT_COST',
            valueState: 'NOT_AVAILABLE',
            numericValue: null,
            currencyCode: null,
            confidenceState: 'CANONICAL_CONFIRMED',
            estimated: false,
            freshnessSeconds: null,
            evidenceRefs: [],
          },
        },
        findings: [],
      },
    });

    render(
      <SubjectDiagnosisView
        context={context(fetchImpl)}
        subjectId="variant-1"
        storeId="store-1"
        onBack={vi.fn()}
      />,
    );

    const cell = await screen.findByText('Not available');
    expect(cell.closest('.value-cell')).toHaveAttribute('data-tone', 'absent');
  });
});

const recommendation: Recommendation = {
  id: 'rec-1',
  storeId: 'store-1',
  subjectId: 'variant-1',
  actionKind: 'PRICE_CHANGE',
  origin: 'DETERMINISTIC',
  state: 'READY_FOR_REVIEW',
  priorityScore: '820.0000',
  proposedParameters: { targetPrice: '105.0000' },
  expectedEffect: { marginDelta: '0.02' },
  riskLabel: 'LOW',
  validUntil: '2026-08-30T09:00:00Z',
  terminalReason: null,
  version: 3,
};

describe('reachable subject evidence and recommendations', () => {
  const id = '00000000-0000-4000-8000-000000000103';
  it('loads source metadata only after an explicit provenance selection', async () => {
    const { fetchImpl, calls } = router({
      [`/api/v1/console/diagnosis/listing-variants/variant-1/metrics/${id}/inputs`]: {
        metricValueId: id,
        references: [
          { kind: 'FACT_PROVENANCE', id },
          { kind: 'METRIC_VALUE', id },
        ],
        truncated: true,
      },
      [`/api/v1/console/evidence/${id}`]: {
        provenanceId: id,
        sourceKind: 'MANUAL_ENTRY',
        sourceTime: null,
        ingestionTime: '2026-08-28T00:00:00Z',
        contentSha256: null,
      },
    });
    render(
      <MetricEvidencePanel
        context={context(fetchImpl)}
        subjectId="variant-1"
        storeId="store-1"
        metricValueId={id}
      />,
    );
    const button = await screen.findByRole('button', { name: `View source ${id}` });
    expect(calls).toHaveLength(1);
    expect(screen.getByText(/Only the first 200/)).toBeInTheDocument();
    fireEvent.click(button);
    expect(await screen.findByText('MANUAL_ENTRY')).toBeInTheDocument();
    expect(screen.getByText('No stored source bytes')).toBeInTheDocument();
  });
  it('reports evidence denial and never leaves source metadata visible', async () => {
    const { fetchImpl } = router({});
    render(
      <MetricEvidencePanel
        context={context(fetchImpl)}
        subjectId="variant-1"
        storeId="store-1"
        metricValueId={id}
      />,
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('Evidence could not be read');
    expect(screen.queryByLabelText('Source provenance')).toBeNull();
  });
  it('opens the exact subject recommendation and rejects mismatched scope', async () => {
    const onReview = vi.fn();
    const { fetchImpl } = router({
      '/api/v1/console/workflow/stores/store-1/recommendations': [recommendation],
    });
    const view = render(
      <SubjectRecommendations
        context={context(fetchImpl)}
        subjectId="variant-1"
        storeId="store-1"
        onReview={onReview}
      />,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Review recommendation rec-1' }));
    expect(onReview).toHaveBeenCalledWith(recommendation);
    view.unmount();
    render(
      <SubjectRecommendations
        context={context(fetchImpl)}
        subjectId="different"
        storeId="store-1"
        onReview={onReview}
      />,
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('scope mismatch');
    expect(screen.queryByRole('button', { name: 'Review recommendation rec-1' })).toBeNull();
  });
  it('shows an empty subject queue explicitly', async () => {
    const { fetchImpl } = router({ '/api/v1/console/workflow/stores/store-1/recommendations': [] });
    render(
      <SubjectRecommendations
        context={context(fetchImpl)}
        subjectId="variant-1"
        storeId="store-1"
        onReview={vi.fn()}
      />,
    );
    expect(await screen.findByText('No open recommendation for this subject.')).toBeInTheDocument();
  });
  it.each(['COMMAND_CREATED', 'EXECUTION_TRACKING', 'OUTCOME_OBSERVATION'])(
    'reopens an existing command from %s without a decision or another write',
    async (state) => {
      const onDecided = vi.fn();
      const { fetchImpl } = router({
        '/api/v1/console/commands/recommendations/rec-1': {
          id: 'existing-command',
          recommendationId: 'rec-1',
          storeId: 'store-1',
          platformCode: 'OZON',
          currencyCode: 'RUB',
          priorPrice: '100.0000',
          targetPrice: '105.0000',
          state: 'SUCCEEDED',
          attemptNo: 2,
          failureCode: null,
          attempts: [],
          readbacks: [],
        },
      });
      render(
        <RecommendationReview
          context={context(fetchImpl)}
          recommendation={{ ...recommendation, state }}
          onDecided={onDecided}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: 'Open existing command' }));
      await waitFor(() => {
        expect(onDecided).toHaveBeenCalledWith(state, 'existing-command');
      });
      expect(fetchImpl).toHaveBeenCalledTimes(1);
      expect(screen.getByRole('button', { name: 'Approve this change' })).toBeDisabled();
      expect(screen.queryByRole('button', { name: 'Create authorized command' })).toBeNull();
    },
  );
  it.each(['mismatch', 'unreachable'])(
    'refuses an existing command response that is %s',
    async (failure) => {
      const onDecided = vi.fn();
      const fetchImpl = vi.fn().mockImplementation(() => {
        if (failure === 'unreachable') return Promise.reject(new Error('synthetic offline'));
        return Promise.resolve(
          new Response(
            JSON.stringify({
              id: 'existing-command',
              recommendationId: 'other-rec',
              storeId: 'store-1',
              platformCode: 'OZON',
              currencyCode: 'RUB',
              priorPrice: '100.0000',
              targetPrice: '105.0000',
              state: 'SUCCEEDED',
              attemptNo: 2,
              failureCode: null,
              attempts: [],
              readbacks: [],
            }),
          ),
        );
      });
      render(
        <RecommendationReview
          context={context(fetchImpl)}
          recommendation={{ ...recommendation, state: 'COMMAND_CREATED' }}
          onDecided={onDecided}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: 'Open existing command' }));
      expect(await screen.findByRole('alert')).toBeInTheDocument();
      expect(onDecided).not.toHaveBeenCalled();
    },
  );
  it('retains an approved decision when command creation fails and retries only the command', async () => {
    const onDecided = vi.fn();
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(new Response('{}', { status: 409 }))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ commandId: 'cmd-retried' }), { status: 200 }),
      );
    render(
      <RecommendationReview
        context={context(fetchImpl)}
        recommendation={{ ...recommendation, state: 'APPROVED', version: 4 }}
        onDecided={onDecided}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Create authorized command' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('refused');
    expect(onDecided).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Approve this change' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Create authorized command' }));
    await waitFor(() => {
      expect(onDecided).toHaveBeenCalledWith('APPROVED', 'cmd-retried');
    });
    for (const call of fetchImpl.mock.calls) expect(call[0]).toContain('/command');
  });
});

describe('TC-UI-032 nothing is approved without a passing preview and a reason', () => {
  it('leaves approval unavailable until the preview has been taken', () => {
    const { fetchImpl } = router({});

    render(
      <RecommendationReview
        context={context(fetchImpl)}
        recommendation={recommendation}
        onDecided={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: /Approve this change/ })).toBeDisabled();
  });

  it('lists every blocking reason at once and keeps approval unavailable', async () => {
    const { fetchImpl } = router({
      '/api/v1/console/workflow/recommendations/rec-1/impact-preview': {
        recommendationId: 'rec-1',
        currencyCode: 'RUB',
        currentPrice: '100.0000',
        proposedPrice: '105.0000',
        changeRate: '0.050000',
        breakEvenPrice: '110.0000',
        currentUnitProfit: '25.0000',
        projectedUnitProfit: '-5.0000',
        currentMargin: '0.250000',
        projectedMargin: '-0.047619',
        verdict: {
          evaluationId: 'eval-1',
          purpose: 'IMPACT_PREVIEW',
          passed: false,
          reasons: ['BELOW_BREAK_EVEN', 'COOLDOWN_ACTIVE'],
          policyVersion: 1,
          detail: {},
        },
      },
    });

    render(
      <RecommendationReview
        context={context(fetchImpl)}
        recommendation={recommendation}
        onDecided={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /Check what this would do/ }));

    await screen.findByTestId('guardrail-blocked');
    expect(screen.getByText('BELOW_BREAK_EVEN')).toBeInTheDocument();
    expect(screen.getByText('COOLDOWN_ACTIVE')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Approve this change/ })).toBeDisabled();
  });

  it('requires a stated reason even when the guardrails pass', async () => {
    const { fetchImpl } = router({
      '/api/v1/console/workflow/recommendations/rec-1/impact-preview': {
        recommendationId: 'rec-1',
        currencyCode: 'RUB',
        currentPrice: '100.0000',
        proposedPrice: '105.0000',
        changeRate: '0.050000',
        breakEvenPrice: '75.0000',
        currentUnitProfit: '25.0000',
        projectedUnitProfit: '30.0000',
        currentMargin: '0.250000',
        projectedMargin: '0.285714',
        verdict: {
          evaluationId: 'eval-1',
          purpose: 'IMPACT_PREVIEW',
          passed: true,
          reasons: [],
          policyVersion: 1,
          detail: {},
        },
      },
    });

    render(
      <RecommendationReview
        context={context(fetchImpl)}
        recommendation={recommendation}
        onDecided={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /Check what this would do/ }));
    await screen.findByTestId('guardrail-verdict');

    expect(screen.getByRole('button', { name: /Approve this change/ })).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Why are you deciding this/), {
      target: { value: 'margin recovery' },
    });
    expect(screen.getByRole('button', { name: /Approve this change/ })).toBeEnabled();
  });

  it('says a step-up is needed rather than that the action is not permitted', async () => {
    const send = vi.fn((url: unknown) =>
      Promise.resolve(
        String(url).endsWith('impact-preview')
          ? new Response(
              JSON.stringify({
                recommendationId: 'rec-1',
                proposedPrice: '105.0000',
                verdict: { evaluationId: 'e1', passed: true, reasons: [], detail: {} },
              }),
              { status: 200, headers: { 'Content-Type': 'application/json' } },
            )
          : new Response(JSON.stringify({ type: 'https://marketops/problems/step-up-required' }), {
              status: 403,
              headers: { 'Content-Type': 'application/json' },
            }),
      ),
    ) as unknown as typeof fetch;

    render(
      <RecommendationReview
        context={context(send)}
        recommendation={recommendation}
        onDecided={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /Check what this would do/ }));
    await screen.findByTestId('guardrail-verdict');
    fireEvent.change(screen.getByLabelText(/Why are you deciding this/), {
      target: { value: 'margin recovery' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Approve this change/ }));

    await waitFor(() => {
      expect(screen.getByTestId('review-failure')).toHaveTextContent(/recent sign-in/);
    });
  });
});

describe('TC-UI-033 an unresolved change is never shown as done', () => {
  it('says plainly that an unknown result is not a confirmed change', async () => {
    const { fetchImpl } = router({
      '/api/v1/console/commands/cmd-1': {
        id: 'cmd-1',
        recommendationId: 'rec-1',
        storeId: 'store-1',
        platformCode: 'OZON',
        currencyCode: 'RUB',
        priorPrice: '100.0000',
        targetPrice: '105.0000',
        state: 'UNKNOWN_REQUIRES_READBACK',
        attemptNo: 1,
        failureCode: null,
        attempts: [
          {
            id: 'a1',
            attemptNo: 1,
            purpose: 'APPLY',
            outcomeClass: 'UNKNOWN_STATE',
            nativeStatus: null,
            errorCode: 'platform_did_not_answer_a_write',
            startedAt: '2026-08-27T09:00:00Z',
            completedAt: '2026-08-27T09:00:10Z',
          },
        ],
        readbacks: [],
      },
      '/api/v1/console/commands/cmd-1/gate': { open: true, blockingReasons: [] },
    });

    render(<CommandTimeline context={context(fetchImpl)} commandId="cmd-1" />);

    expect(await screen.findByTestId('command-unresolved')).toBeInTheDocument();
    expect(screen.getByTestId('command-state')).toHaveTextContent(
      /neither confirmed nor ruled out/,
    );
    expect(screen.getByTestId('no-readback')).toBeInTheDocument();
  });

  it('shows every reason the write gate is closed', async () => {
    const { fetchImpl } = router({
      '/api/v1/console/commands/cmd-2': {
        id: 'cmd-2',
        recommendationId: 'rec-2',
        storeId: 'store-1',
        platformCode: 'OZON',
        currencyCode: 'RUB',
        priorPrice: '100.0000',
        targetPrice: '105.0000',
        state: 'PENDING',
        attemptNo: 0,
        failureCode: null,
        attempts: [],
        readbacks: [],
      },
      '/api/v1/console/commands/cmd-2/gate': {
        open: false,
        blockingReasons: ['GLOBAL_SWITCH_DISABLED', 'ENTITY_NOT_ALLOWLISTED'],
      },
    });

    render(<CommandTimeline context={context(fetchImpl)} commandId="cmd-2" />);

    await screen.findByTestId('gate-closed');
    expect(screen.getByText('GLOBAL_SWITCH_DISABLED')).toBeInTheDocument();
    expect(screen.getByText('ENTITY_NOT_ALLOWLISTED')).toBeInTheDocument();
  });

  it('describes a matching readback as the confirmation it is', async () => {
    const { fetchImpl } = router({
      '/api/v1/console/commands/cmd-3': {
        id: 'cmd-3',
        recommendationId: 'rec-3',
        storeId: 'store-1',
        platformCode: 'OZON',
        currencyCode: 'RUB',
        priorPrice: '100.0000',
        targetPrice: '105.0000',
        state: 'SUCCEEDED',
        attemptNo: 2,
        failureCode: null,
        attempts: [],
        readbacks: [
          {
            id: 'r1',
            observedAt: '2026-08-27T09:05:00Z',
            observedPrice: '105.0000',
            currencyCode: 'RUB',
            matchState: 'MATCHES_TARGET',
          },
        ],
      },
      '/api/v1/console/commands/cmd-3/gate': { open: true, blockingReasons: [] },
    });

    render(<CommandTimeline context={context(fetchImpl)} commandId="cmd-3" />);

    expect(await screen.findByTestId('command-state')).toHaveTextContent(/change is confirmed/);
    expect(screen.getByText(/this is the price that was intended/)).toBeInTheDocument();
    expect(screen.queryByTestId('command-unresolved')).toBeNull();
  });
});
