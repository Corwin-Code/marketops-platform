import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AdvertisingWorkflow } from '../advertising/AdvertisingWorkflow';
import { AdvertisingTimestamp } from '../advertising/AdvertisingTimestamp';
import { parseAdvertisingDecisionPreview } from '../api/console';
import type { ConsoleRequest } from '../api/console';

const workflow = {
  caseId: 'case-1',
  taskId: 'task-1',
  taskState: 'OPEN',
  taskVersion: 3,
  accountableRole: 'MARKETPLACE_OPERATOR',
  coverageState: 'STAFFED',
  firstRaisedAt: '2026-09-04T00:00:00Z',
  acknowledgementDueAt: '2026-09-04T01:00:00Z',
  actionDueAt: '2026-09-04T03:00:00Z',
  escalationDueAt: '2026-09-04T04:00:00Z',
  nextStaffedResponseAt: '2026-09-04T00:00:00Z',
  allowedActions: ['SELECT_CANDIDATE', 'REJECT_CANDIDATE'],
  candidates: [
    {
      id: 'candidate-1',
      ordinal: 1,
      currentBidAmount: '30.00',
      targetBidAmount: '18.00',
      currency: 'RUB',
      unit: 'CURRENCY_MAJOR',
      basis: 'MAX_CPC_BOUNDED',
      recommendationId: 'rec-1',
      state: 'DRAFT',
      version: 7,
      makerUserId: null,
      endorserUserId: null,
    },
  ],
};

function setup(
  body: unknown = workflow,
  postStatus = 200,
): { fetchImpl: typeof fetch; context: ConsoleRequest } {
  const fetchImpl = vi.fn().mockImplementation((_url: string, init: RequestInit) =>
    Promise.resolve(
      new Response(
        JSON.stringify(init.method === 'POST' ? { id: 'rec-1', state: 'READY_FOR_REVIEW' } : body),
        {
          status: init.method === 'POST' ? postStatus : 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    ),
  ) as unknown as typeof fetch;
  return {
    fetchImpl,
    context: { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'synthetic', fetchImpl },
  };
}

describe('advertising workflow through service contracts', () => {
  it('selects only an exact server candidate with reason and observed version', async () => {
    const { context, fetchImpl } = setup();
    render(<AdvertisingWorkflow context={context} caseId="case-1" timezone="Europe/Moscow" />);
    const select = await screen.findByRole('button', { name: 'Select exact candidate' });
    expect(select).toBeDisabled();
    fireEvent.change(screen.getByRole('textbox', { name: 'Reason for this decision' }), {
      target: { value: 'Проверенный кандидат' },
    });
    fireEvent.click(select);
    await screen.findByRole('status');
    const calls = vi.mocked(fetchImpl).mock.calls;
    const mutation = calls.find(([, init]) => init?.method === 'POST');
    expect(mutation?.[0]).toBe(
      'http://127.0.0.1:8080/api/v1/console/advertising/cases/case-1/candidates/candidate-1/selection',
    );
    expect(JSON.parse(typeof mutation?.[1]?.body === 'string' ? mutation[1].body : '{}')).toEqual({
      expectedVersion: 7,
      reason: 'Проверенный кандидат',
    });
    expect(calls.filter(([, init]) => init?.method !== 'POST').length).toBeGreaterThan(1);
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
  });

  it('hides mutations when the service grants no action', async () => {
    const { context } = setup({ ...workflow, allowedActions: [] });
    render(<AdvertisingWorkflow context={context} caseId="case-1" timezone={undefined} />);
    await screen.findByText(/MAX_CPC_BOUNDED/u);
    expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual([
      'Read attributable journal',
    ]);
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('exposes endorsement only after selection and exposes final approval only after endorsement', async () => {
    const { context } = setup({
      ...workflow,
      allowedActions: ['ENDORSE', 'APPROVE'],
      candidates: [{ ...workflow.candidates[0]!, makerUserId: 'maker', state: 'VALIDATED' }],
    });
    render(<AdvertisingWorkflow context={context} caseId="case-1" timezone="Europe/Moscow" />);
    await screen.findByRole('button', { name: 'Record operational endorsement' });
    expect(screen.queryByRole('button', { name: 'Approve exact change' })).not.toBeInTheDocument();
  });

  it('does not turn a stale-version rejection into a local successful selection', async () => {
    const { context } = setup(workflow, 409);
    render(<AdvertisingWorkflow context={context} caseId="case-1" timezone="Europe/Moscow" />);
    await screen.findByRole('button', { name: 'Select exact candidate' });
    fireEvent.change(screen.getByRole('textbox', { name: 'Reason for this decision' }), {
      target: { value: 'stale check' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Select exact candidate' }));
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('409');
    });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('reads the actual GuardrailVerdict record and preserves complete blocked preview evidence', async () => {
    const preview = {
      recommendationId: 'rec-1',
      verdict: {
        evaluationId: 'evaluation-1',
        purpose: 'APPROVAL',
        passed: false,
        reasons: ['ECONOMIC_BOUND_UNRESOLVED'],
        policyId: 'policy-1',
        policyVersion: 3,
        detail: { submittedUnitMaxCpc: 'UNRESOLVED' },
        inputDigest: 'a'.repeat(64),
      },
      gateReasons: ['PROVIDER_PROFILE_UNVERIFIED'],
      unresolvedReasons: ['CURRENT_BID_UNKNOWN'],
      evidence: {
        submittedConfiguration: { currency: 'RUB', unit: 'CURRENCY_MINOR', targetBid: 20 },
        risk: { status: 'UNRESOLVED' },
        frozenOutcomePlan: { baselineId: 'baseline-1' },
      },
    };
    expect(parseAdvertisingDecisionPreview({ ...preview, verdict: 'PASS' })).toBeUndefined();
    expect(
      parseAdvertisingDecisionPreview({
        ...preview,
        verdict: { ...preview.verdict, reasons: [null] },
      }),
    ).toBeUndefined();
    const fetchImpl = vi.fn().mockImplementation((url: string) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            url.endsWith('/ad-bid-impact-preview')
              ? preview
              : {
                  ...workflow,
                  allowedActions: ['APPROVE'],
                  candidates: [
                    {
                      ...workflow.candidates[0]!,
                      state: 'READY_FOR_REVIEW',
                      makerUserId: 'maker',
                      endorserUserId: 'ops',
                    },
                  ],
                },
          ),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    ) as unknown as typeof fetch;
    render(
      <AdvertisingWorkflow
        context={{ apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'synthetic', fetchImpl }}
        caseId="case-1"
        timezone="Europe/Moscow"
      />,
    );
    fireEvent.click(
      await screen.findByRole('button', { name: 'Review complete decision evidence' }),
    );
    await screen.findByRole('heading', { name: 'Current guardrail verdict: BLOCKED' });
    expect(screen.getAllByText('ECONOMIC_BOUND_UNRESOLVED').length).toBeGreaterThan(0);
    expect(screen.getAllByText('PROVIDER_PROFILE_UNVERIFIED').length).toBeGreaterThan(0);
    expect(screen.getAllByText('CURRENT_BID_UNKNOWN').length).toBeGreaterThan(0);
    expect(screen.getByText('CURRENCY_MINOR')).toBeInTheDocument();
    expect(screen.getByText('baseline-1')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows UTC and the declared Store timezone without using the browser timezone', () => {
    render(<AdvertisingTimestamp value="2026-09-04T00:00:00Z" timezone="Europe/Moscow" />);
    expect(screen.getByText(/2026-09-04T00:00:00.000Z/u)).toHaveTextContent('03:00:00');
    expect(screen.getByText(/Europe\/Moscow/u)).toBeInTheDocument();
  });

  it('recovers an existing native command and preserves unresolved readback, exact units and absent outcome', async () => {
    const fetchImpl = vi.fn().mockImplementation((url: string) => {
      const body = url.endsWith('/workflow')
        ? {
            ...workflow,
            allowedActions: [],
            candidates: [
              { ...workflow.candidates[0]!, state: 'APPROVED', commandId: 'ad-command' },
            ],
          }
        : url.endsWith('/commands/ad-command') && url.includes('/advertising/')
          ? {
              id: 'ad-command',
              state: 'UNKNOWN_REQUIRES_READBACK',
              priorBidAmount: 30,
              targetBidAmount: 20,
              currencyCode: 'RUB',
              bidUnitCode: 'CURRENCY_MINOR',
              approvalExpiresAt: '2026-09-04T01:00:00Z',
              attempts: [{ id: 'attempt', purpose: 'APPLY', outcomeClass: 'TIMEOUT' }],
              readbacks: [
                {
                  id: 'readback',
                  matchState: 'DIFFERENT',
                  observedBid: 10,
                  currencyCode: 'RUB',
                  bidUnitCode: 'CURRENCY_MAJOR',
                },
              ],
            }
          : url.includes('/ad-bid-compensations/')
            ? { allowedActions: [], availableBundleIds: [], state: 'INELIGIBLE' }
            : [];
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
    }) as unknown as typeof fetch;
    render(
      <AdvertisingWorkflow
        context={{ apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'synthetic', fetchImpl }}
        caseId="case-1"
        timezone="Europe/Moscow"
      />,
    );
    await screen.findByText(/Configuration is unresolved/u);
    expect(
      screen.getByRole('list', { name: 'Advertising native configuration readbacks' }),
    ).toHaveTextContent('CURRENCY_MAJOR');
    expect(screen.getByLabelText('Advertising command timeline')).toHaveTextContent(
      'CURRENCY_MINOR',
    );
    await screen.findByText(/An absent outcome is not a neutral one/u);
    expect(
      screen.queryByRole('button', { name: 'Create approved command' }),
    ).not.toBeInTheDocument();
    expect(
      vi
        .mocked(fetchImpl)
        .mock.calls.some(([url]) => typeof url === 'string' && url.includes('/console/commands/')),
    ).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Refresh command and outcome evidence' }));
    await waitFor(() => {
      expect(
        vi
          .mocked(fetchImpl)
          .mock.calls.filter(
            ([url]) => typeof url === 'string' && url.endsWith('/advertising/commands/ad-command'),
          ).length,
      ).toBe(2);
    });
  });
});
