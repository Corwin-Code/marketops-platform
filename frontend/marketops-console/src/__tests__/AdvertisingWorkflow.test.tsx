import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

const establishedSlo = {
  coverageState: 'IN_COVERAGE',
  acknowledgementDueAt: '2026-09-04T01:00:00Z',
  actionDueAt: '2026-09-04T03:00:00Z',
  escalationDueAt: '2026-09-04T04:00:00Z',
  nextStaffedResponseAt: '2026-09-04T00:00:00Z',
  acknowledgedAt: null,
  firstAttributableActionAt: null,
  acknowledgementBreached: false,
  actionBreached: false,
  actionPaused: false,
  wallClockExposureAgeSeconds: 42,
};

async function showResponseTiming(slo: unknown) {
  const { context } = setup({ ...workflow, allowedActions: ['TASK_ACKNOWLEDGE'], slo });
  render(<AdvertisingWorkflow context={context} caseId="case-1" timezone="Europe/Moscow" />);
  return within(await screen.findByRole('group', { name: 'Advertising response timing' }));
}

describe('advertising response evidence stays distinct from staffed-clock evaluability', () => {
  it('keeps missing-profile false flags unresolved without hiding the Task or borrowing legacy dates', async () => {
    const timing = await showResponseTiming({
      ...establishedSlo,
      coverageState: 'PROFILE_OR_CALENDAR_MISSING',
      acknowledgementDueAt: null,
      actionDueAt: null,
      escalationDueAt: null,
      nextStaffedResponseAt: null,
    });
    expect(screen.getByText(/Task task-1/u)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Acknowledge responsibility' })).toBeInTheDocument();
    expect(screen.queryByText(/2026-09-04T01:00:00.000Z/u)).not.toBeInTheDocument();
    expect(timing.getByLabelText('Acknowledgement completion')).toHaveTextContent('UNRESOLVED');
    expect(timing.getByLabelText('Action-stage completion')).toHaveTextContent('UNRESOLVED');
    expect(timing.getByLabelText('Acknowledgement timeliness')).toHaveTextContent('UNRESOLVED');
    expect(timing.getByLabelText('Action timeliness')).toHaveTextContent('UNRESOLVED');
    expect(timing.getByLabelText('Action clock state')).toHaveTextContent(
      'Action clock: UNRESOLVED',
    );
    expect(
      timing.queryByText(/NOT_BREACHED|within current|not recorded|Action clock: active/u),
    ).not.toBeInTheDocument();
    expect(timing.getByText('Exposure age 42 seconds')).toBeInTheDocument();
  });

  it.each([
    ['missing', undefined],
    ['array', []],
    ['unrecognized coverage', { ...establishedSlo, coverageState: 'STAFFED' }],
  ] as const)(
    'preserves the Task when the live SLO is %s instead of inventing a running clock',
    async (_label, slo) => {
      const timing = await showResponseTiming(slo);
      expect(screen.getByText(/Task task-1/u)).toBeInTheDocument();
      expect(timing.getByLabelText('Acknowledgement timeliness')).toHaveTextContent('UNRESOLVED');
      expect(timing.getByLabelText('Action timeliness')).toHaveTextContent('UNRESOLVED');
      expect(timing.getByLabelText('Action clock state')).toHaveTextContent(
        'Action clock: UNRESOLVED',
      );
    },
  );

  it('shows an active staffed clock only for an established pending server response', async () => {
    const timing = await showResponseTiming(establishedSlo);
    expect(timing.getByLabelText('Acknowledgement timeliness')).toHaveTextContent(
      'NOT_BREACHED as of this response',
    );
    expect(timing.getByLabelText('Action timeliness')).toHaveTextContent(
      'NOT_BREACHED as of this response',
    );
    expect(timing.getByLabelText('Acknowledgement completion')).toHaveTextContent(
      'not recorded as of this response',
    );
    expect(timing.getByLabelText('Action-stage completion')).toHaveTextContent(
      'not recorded as of this response',
    );
    expect(timing.getByLabelText('Action clock state')).toHaveTextContent('Action clock: active');
  });

  it.each([null, '', 'not-an-instant'])(
    'does not use the ACK deadline to resolve a missing or invalid Action deadline %s',
    async (actionDueAt) => {
      const timing = await showResponseTiming({ ...establishedSlo, actionDueAt });
      expect(timing.getByLabelText('Acknowledgement timeliness')).toHaveTextContent(
        'NOT_BREACHED as of this response',
      );
      expect(timing.getByLabelText('Action timeliness')).toHaveTextContent('UNRESOLVED');
      expect(timing.getByLabelText('Action clock state')).toHaveTextContent(
        'Action clock: UNRESOLVED',
      );
    },
  );

  it.each([
    ['unknown breach', { actionBreached: null }],
    ['unknown pause', { actionPaused: null }],
    ['invalid completion evidence', { firstAttributableActionAt: 'invalid' }],
  ] as const)('does not turn %s into an active clock', async (_label, delta) => {
    const timing = await showResponseTiming({ ...establishedSlo, ...delta });
    expect(timing.getByLabelText('Action clock state')).toHaveTextContent(
      'Action clock: UNRESOLVED',
    );
  });

  it.each(['OUT_OF_COVERAGE', 'OUT_OF_COVERAGE_ACTIVE_HARM'])(
    'keeps %s exposure visible while waiting for staffing instead of claiming active coverage',
    async (coverageState) => {
      const timing = await showResponseTiming({
        ...establishedSlo,
        coverageState,
        wallClockExposureAgeSeconds: 7200,
      });
      expect(screen.getByText(coverageState)).toBeInTheDocument();
      expect(screen.getByText('Next staffed response').nextElementSibling).toHaveTextContent(
        '2026-09-04T00:00:00.000Z',
      );
      expect(timing.getByLabelText('Action clock state')).toHaveTextContent(
        'awaiting staffed coverage',
      );
      expect(timing.getByLabelText('Action timeliness')).toHaveTextContent(
        'NOT_BREACHED as of this response',
      );
      expect(timing.getByText('Exposure age 7200 seconds')).toBeInTheDocument();
    },
  );

  it('preserves reported breaches and a current pause even when deadline authority is unresolved', async () => {
    const timing = await showResponseTiming({
      ...establishedSlo,
      coverageState: 'PROFILE_OR_CALENDAR_MISSING',
      acknowledgementDueAt: null,
      actionDueAt: null,
      acknowledgementBreached: true,
      actionBreached: true,
      actionPaused: true,
    });
    expect(timing.getByLabelText('Acknowledgement timeliness')).toHaveTextContent(
      'Acknowledgement timeliness: BREACHED',
    );
    expect(timing.getByLabelText('Action timeliness')).toHaveTextContent(
      'Action timeliness: BREACHED',
    );
    expect(timing.getByLabelText('Action clock state')).toHaveTextContent('Action clock: paused');
    expect(timing.getByText(/exposure age continues/u)).toBeInTheDocument();
  });

  it('preserves historical late completion and a reported pause without restarting the completed stage', async () => {
    const timing = await showResponseTiming({
      ...establishedSlo,
      coverageState: 'ACCEPTED_EXCEPTION_ACTIVE',
      acknowledgedAt: '2026-09-04T00:30:00Z',
      firstAttributableActionAt: '2026-09-04T03:30:00Z',
      actionBreached: true,
      actionPaused: true,
    });
    expect(timing.getByLabelText('Acknowledgement completion')).toHaveTextContent(
      '2026-09-04T00:30:00.000Z',
    );
    expect(timing.getByLabelText('Acknowledgement timeliness')).toHaveTextContent(
      'NOT_BREACHED as of this response',
    );
    expect(timing.getByLabelText('Action-stage completion')).toHaveTextContent(
      '2026-09-04T03:30:00.000Z',
    );
    expect(timing.getByLabelText('Action timeliness')).toHaveTextContent(
      'Action timeliness: BREACHED',
    );
    expect(timing.getByLabelText('Action clock state')).toHaveTextContent('stage completed');
    expect(timing.getByText(/exposure age continues/u)).toBeInTheDocument();
  });

  it('does not turn an acknowledgement into an attributable Action-stage completion', async () => {
    const timing = await showResponseTiming({
      ...establishedSlo,
      acknowledgedAt: '2026-09-04T00:30:00Z',
    });
    expect(timing.getByLabelText('Acknowledgement completion')).toHaveTextContent('recorded at');
    expect(timing.getByLabelText('Action-stage completion')).toHaveTextContent(
      'not recorded as of this response',
    );
    expect(timing.getByLabelText('Action clock state')).toHaveTextContent('Action clock: active');
  });

  it('keeps a supplied completion fact separate from unknown timeliness and a missing age', async () => {
    const timing = await showResponseTiming({
      ...establishedSlo,
      coverageState: 'PROFILE_OR_CALENDAR_MISSING',
      actionDueAt: null,
      firstAttributableActionAt: '2026-09-04T03:30:00Z',
      wallClockExposureAgeSeconds: null,
    });
    expect(timing.getByLabelText('Action-stage completion')).toHaveTextContent('recorded at');
    expect(timing.getByLabelText('Action timeliness')).toHaveTextContent('UNRESOLVED');
    expect(timing.getByLabelText('Action clock state')).toHaveTextContent('stage completed');
    expect(timing.getByText('Exposure age UNRESOLVED seconds')).toBeInTheDocument();
  });

  it.each([-1, 0])(
    'does not conflate negative exposure age %s with an established zero',
    async (wallClockExposureAgeSeconds) => {
      const timing = await showResponseTiming({ ...establishedSlo, wallClockExposureAgeSeconds });
      expect(
        timing.getByText(
          `Exposure age ${wallClockExposureAgeSeconds < 0 ? 'UNRESOLVED' : '0'} seconds`,
        ),
      ).toBeInTheDocument();
    },
  );
});
