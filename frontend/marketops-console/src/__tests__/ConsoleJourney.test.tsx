import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { CommandTimeline } from '../commands/CommandTimeline';
import { SubjectDiagnosisView } from '../diagnosis/SubjectDiagnosisView';
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
