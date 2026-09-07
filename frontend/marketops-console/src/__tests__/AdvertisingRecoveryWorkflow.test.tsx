import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AdvertisingCompensation } from '../advertising/AdvertisingCompensation';
import {
  AdvertisingStopControls,
  AdvertisingRecoveryControls,
} from '../advertising/AdvertisingContainmentControls';
import { AdvertisingResponsibilityControls } from '../advertising/AdvertisingResponsibilityControls';
import { AdvertisingOrchestration } from '../advertising/AdvertisingOrchestration';
import { parseAdvertisingWorkflow } from '../api/advertising';
import type { ConsoleRequest } from '../api/console';
const reply = (body: unknown, status = 200): Response =>
  new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
function transport(read: (url: string, init: RequestInit) => Response) {
  const fetchImpl = vi.fn((url: string, init: RequestInit) =>
    Promise.resolve(read(url, init)),
  ) as unknown as typeof fetch;
  return {
    context: {
      apiBaseUrl: 'http://127.0.0.1:8080',
      accessToken: 'fixture',
      fetchImpl,
    } satisfies ConsoleRequest,
    fetchImpl,
  };
}
function bodyOf(fetchImpl: typeof fetch): unknown {
  const call = vi.mocked(fetchImpl).mock.calls.find(([, init]) => init?.method === 'POST');
  return JSON.parse(typeof call?.[1]?.body === 'string' ? call[1].body : 'null');
}
describe('scope-bound recovery and accountability', () => {
  it('a Maker stop submits exact entity scope and an accountable reviewer, with no finance input', async () => {
    const { context, fetchImpl } = transport(() => reply({ state: 'ACTIVE' }));
    render(
      <AdvertisingStopControls
        context={context}
        objectId="object"
        allowedActions={['EMERGENCY_ENTITY_HOLD']}
      />,
    );
    fireEvent.change(screen.getByRole('textbox', { name: 'Stop reason' }), {
      target: { value: 'Observed native configuration conflict' },
    });
    fireEvent.change(screen.getByRole('textbox', { name: 'Stop evidence reference' }), {
      target: { value: 'canonical:configuration' },
    });
    fireEvent.change(screen.getByRole('textbox', { name: 'Responsible Operations Lead user ID' }), {
      target: { value: 'ops-user' },
    });
    expect(screen.queryByRole('button', { name: /Stop Store/u })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Apply emergency object hold' }));
    await screen.findByRole('status');
    expect(bodyOf(fetchImpl)).toEqual({
      scopeKind: 'ENTITY',
      containmentKind: 'EMERGENCY_ENTITY_HOLD',
      causeClass: 'BUSINESS_HARM',
      reviewOwnerUserId: 'ops-user',
      reason: 'Observed native configuration conflict',
      evidenceReference: 'canonical:configuration',
    });
  });
  it.each(['ATTEST_UNKNOWNS_RESOLVED', 'REENABLE'])(
    'recovery %s retains a new canonical proof instead of a client boolean',
    async (action) => {
      const { context, fetchImpl } = transport(() => reply({ state: 'REENABLEMENT_REVIEW' }));
      const reload = vi.fn();
      render(
        <AdvertisingRecoveryControls
          context={context}
          id="hold"
          allowedActions={[action]}
          reload={reload}
        />,
      );
      fireEvent.change(screen.getByRole('textbox'), {
        target: { value: action === 'REENABLE' ? 'new-bundle' : 'canonical-proof' },
      });
      fireEvent.click(screen.getByRole('button'));
      await waitFor(() => {
        expect(reload).toHaveBeenCalledOnce();
      });
      expect(bodyOf(fetchImpl)).toEqual(
        action === 'REENABLE'
          ? { newBundleId: 'new-bundle' }
          : { condition: 'UNKNOWNS_RESOLVED', evidenceReference: 'canonical-proof' },
      );
    },
  );
  it.each([
    ['PREVIEW', 'Prepare exact prior bid recovery', '/commands/command/preview'],
    ['ENDORSE', 'Endorse exact recovery', '/preview/endorsement'],
    ['APPROVE', 'Approve exact recovery', '/preview/approval'],
  ])(
    'compensation %s is available only through the server-authorized role action',
    async (action, label, path) => {
      const { context, fetchImpl } = transport((_url, init) =>
        reply(
          init.method === 'POST'
            ? { state: 'RECORDED' }
            : {
                state: 'READY',
                allowedActions: [action],
                availableBundleIds: ['bundle'],
                existingPreviewId: 'preview',
                currentOwnerBid: 20,
                exactPriorBid: 30,
                currencyCode: 'RUB',
                bidUnitCode: 'CURRENCY_MAJOR',
              },
        ),
      );
      render(<AdvertisingCompensation context={context} commandId="command" />);
      const button = await screen.findByRole('button', { name: label });
      if (action === 'PREVIEW')
        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'bundle' } });
      fireEvent.click(button);
      await waitFor(() => {
        expect(
          vi
            .mocked(fetchImpl)
            .mock.calls.some(
              ([url, init]) =>
                typeof url === 'string' && url.endsWith(path) && init?.method === 'POST',
            ),
        ).toBe(true);
      });
      expect(bodyOf(fetchImpl)).toEqual(
        action === 'PREVIEW' ? { compensationBundleId: 'bundle' } : null,
      );
    },
  );
  it('assignment, start and journal use the task revision and retain attributed actor evidence', async () => {
    const { context, fetchImpl } = transport((url, init) =>
      init.method === 'POST'
        ? reply(null, 204)
        : reply(
            url.endsWith('/journal')
              ? [
                  {
                    id: 'event',
                    eventKind: 'ACKNOWLEDGED',
                    actorUserId: 'maker',
                    actorRoleCode: 'MARKETPLACE_OPERATOR',
                    occurredAt: '2026-09-05T00:00:00Z',
                    disclosureState: 'MASKED',
                    reason: 'MASKED',
                  },
                ]
              : [],
          ),
    );
    const workflow = parseAdvertisingWorkflow({
      caseId: 'case',
      taskId: 'task',
      taskState: 'OPEN',
      taskVersion: 7,
      accountableRole: 'MARKETPLACE_OPERATOR',
      operatingDisposition: 'ACTION_REQUIRED',
      candidates: [],
      allowedActions: ['TASK_ASSIGN', 'TASK_START'],
    });
    expect(workflow).toBeDefined();
    if (workflow === undefined) return;
    const reload = vi.fn();
    render(
      <AdvertisingResponsibilityControls
        context={context}
        workflow={workflow}
        timezone="Europe/Moscow"
        reload={reload}
      />,
    );
    fireEvent.change(screen.getByRole('textbox', { name: 'Eligible assignee user ID' }), {
      target: { value: 'maker' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Assign responsible person' }));
    await waitFor(() => {
      expect(reload).toHaveBeenCalledOnce();
    });
    expect(bodyOf(fetchImpl)).toEqual({ assigneeUserId: 'maker', expectedVersion: 7 });
    fireEvent.click(screen.getByRole('button', { name: 'Start accountable work' }));
    await waitFor(() => {
      expect(reload).toHaveBeenCalledTimes(2);
    });
    fireEvent.click(screen.getByRole('button', { name: 'Read attributable journal' }));
    expect(await screen.findByLabelText('Attributable journal')).toHaveTextContent(
      'ACKNOWLEDGED · actor maker',
    );
  });
  it('an actual SLO violation is visible without replacing missing critical samples by zero', async () => {
    const { context } = transport(() =>
      reply({
        state: 'INCIDENT',
        distributionState: 'NO_CRITICAL_OBSERVATIONS',
        criticalP95Millis: null,
        incidents: ['BACKLOG_HARD_BOUND_BREACHED'],
      }),
    );
    render(<AdvertisingOrchestration context={context} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('INCIDENT');
    expect(screen.getByText('BACKLOG_HARD_BOUND_BREACHED')).toBeInTheDocument();
    expect(
      screen.getAllByText('NO_CRITICAL_OBSERVATIONS', { exact: false }).length,
    ).toBeGreaterThan(0);
  });
});
