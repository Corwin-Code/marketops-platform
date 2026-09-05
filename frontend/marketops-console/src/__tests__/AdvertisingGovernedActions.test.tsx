import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  AdvertisingManualProposalControls,
  AdvertisingManualPacketControls,
} from '../advertising/AdvertisingManualControls';
import { AdvertisingManualShadow } from '../advertising/AdvertisingManualShadow';
import { AdvertisingWorkflow } from '../advertising/AdvertisingWorkflow';
import { parseAdvertisingManualPacket } from '../api/advertising';
import type { ConsoleRequest } from '../api/console';

const packetBody = {
  id: 'packet-1',
  caseId: 'case-1',
  adNativeObjectId: 'object-1',
  actionKind: 'AD_BID_CHANGE',
  intendedState: '{"currentBid":30,"targetBid":20}',
  state: 'MANUAL_EXECUTION_IN_PROGRESS',
  version: 4,
  issuedAt: '2026-09-04T00:00:00Z',
  expiresAt: '2026-09-04T01:00:00Z',
  configurationProven: false,
  currentProofId: null,
  verifications: [],
  allowedActions: ['REPORT'],
};
function client(respond: (url: string, init: RequestInit) => Response): {
  context: ConsoleRequest;
  fetchImpl: typeof fetch;
} {
  const fetchImpl = vi.fn((url: string, init: RequestInit) =>
    Promise.resolve(respond(url, init)),
  ) as unknown as typeof fetch;
  return {
    context: { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'synthetic', fetchImpl },
    fetchImpl,
  };
}
const response = (body: unknown): Response =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });

describe('governed human controls use exact server authority', () => {
  it('selects a system manual option using only its identities and human reason', async () => {
    const { context, fetchImpl } = client((_url, init) =>
      response(
        init.method === 'POST'
          ? packetBody
          : {
              options: [
                {
                  policyId: 'policy-1',
                  policyVersion: 3,
                  actionKind: 'AD_BID_CHANGE',
                  candidateId: 'candidate-1',
                  currentBid: 30,
                  targetBid: 20,
                  currencyCode: 'RUB',
                  bidUnitCode: 'CURRENCY_MAJOR',
                  verificationMode: 'INDEPENDENT_OR_OFFICIAL',
                  apiProfileState: 'UNVERIFIED',
                },
              ],
              allowedActions: ['SELECT_MANUAL_PROPOSAL'],
            },
      ),
    );
    const reload = vi.fn();
    render(<AdvertisingManualProposalControls context={context} caseId="case-1" reload={reload} />);
    const select = await screen.findByRole('button', { name: 'Select exact manual proposal' });
    fireEvent.change(screen.getByRole('textbox', { name: 'Manual selection reason' }), {
      target: { value: 'Exact manual fallback' },
    });
    fireEvent.click(select);
    await waitFor(() => {
      expect(reload).toHaveBeenCalledOnce();
    });
    const post = vi.mocked(fetchImpl).mock.calls.find(([, init]) => init?.method === 'POST');
    expect(post?.[0]).toContain('/cases/case-1/manual-selections');
    expect(JSON.parse(typeof post?.[1]?.body === 'string' ? post[1].body : '{}')).toEqual({
      policyId: 'policy-1',
      candidateId: 'candidate-1',
      reason: 'Exact manual fallback',
    });
    expect(screen.getByText(/API profile: UNVERIFIED/u)).toBeInTheDocument();
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
  });

  it('an executor report has no evidence grade, actor override or verifier input', async () => {
    const { context, fetchImpl } = client(() =>
      response({ ...packetBody, state: 'ACTION_REPORTED_CONFIGURATION_UNVERIFIED', version: 5 }),
    );
    const packet = parseAdvertisingManualPacket(packetBody);
    expect(packet).toBeDefined();
    if (packet === undefined) return;
    const reload = vi.fn();
    render(<AdvertisingManualPacketControls context={context} packet={packet} reload={reload} />);
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Report execution without proof' }));
    await waitFor(() => {
      expect(reload).toHaveBeenCalledOnce();
    });
    const post = vi.mocked(fetchImpl).mock.calls[0];
    expect(post?.[0]).toContain('/manual-packets/packet-1/report');
    expect(JSON.parse(typeof post?.[1]?.body === 'string' ? post[1].body : '{}')).toEqual({
      expectedVersion: 4,
    });
  });

  it('refreshes a new early outcome even when the verified packet version stays unchanged', async () => {
    let observed = false;
    let outcomeReads = 0;
    const packet = {
      ...packetBody,
      version: 5,
      state: 'MANUAL_CONFIGURATION_VERIFIED',
      configurationProven: true,
      currentProofId: 'proof-1',
      allowedActions: ['OBSERVE_EARLY_SAFETY'],
      verifications: [
        {
          id: 'proof-1',
          evidenceGrade: 'INDEPENDENT_HUMAN_OBSERVATION',
          conflictState: 'NONE',
          provesConfiguration: true,
          observedAt: '2026-09-04T00:00:00Z',
        },
      ],
    };
    const { context } = client((url, init) => {
      if (url.endsWith('/early-observation') && init.method === 'POST') {
        observed = true;
        return response(packet);
      }
      if (url.endsWith('/outcomes')) {
        outcomeReads += 1;
        return response(
          observed
            ? [
                {
                  id: 'observation-1',
                  manualPacketId: packet.id,
                  outcomeStage: 'OPERATIONAL',
                  verdict: 'NOT_YET_EVALUABLE',
                  baselineMetricState: 'AVAILABLE',
                  observedMetricState: 'NOT_AVAILABLE',
                  unresolvedReasonCodes: ['OBSERVATION_WINDOW_NOT_DUE'],
                },
              ]
            : [],
        );
      }
      return response([packet]);
    });
    render(<AdvertisingManualShadow context={context} objectId="object-1" />);
    await screen.findByText(/Nothing has been observed yet/u);
    fireEvent.click(screen.getByRole('button', { name: 'Observe canonical early sales safety' }));
    await screen.findByText('NOT_YET_EVALUABLE');
    expect(outcomeReads).toBe(2);
    expect(screen.getByText(/revision 5/u)).toBeInTheDocument();
    expect(screen.getByText('OBSERVATION_WINDOW_NOT_DUE')).toBeInTheDocument();
  });

  it('a historical good observation cannot override current uncertainty or another proof identity', () => {
    const observation = {
      id: 'old',
      evidenceGrade: 'OFFICIAL_API_READBACK',
      conflictState: 'NONE',
      provesConfiguration: true,
      observedAt: '2026-09-04T00:00:00Z',
    };
    expect(
      parseAdvertisingManualPacket({
        ...packetBody,
        configurationProven: true,
        currentProofId: 'old',
        state: 'MANUAL_EXECUTION_UNCERTAIN',
        verifications: [observation],
      })?.configurationProven,
    ).toBe(false);
    expect(
      parseAdvertisingManualPacket({
        ...packetBody,
        configurationProven: true,
        currentProofId: 'missing',
        state: 'MANUAL_CONFIGURATION_VERIFIED',
        verifications: [observation],
      })?.configurationProven,
    ).toBe(false);
  });

  it('live staffed SLO replaces static due dates and a 204 acknowledgement is a successful round trip', async () => {
    const { context, fetchImpl } = client((url, init) =>
      init.method === 'POST'
        ? new Response(null, { status: 204 })
        : response(
            url.endsWith('/exceptions')
              ? []
              : {
                  caseId: 'case-1',
                  taskId: 'task-1',
                  taskState: 'OPEN',
                  taskVersion: 0,
                  accountableRole: 'MARKETPLACE_OPERATOR',
                  operatingDisposition: 'ACTION_REQUIRED',
                  candidates: [],
                  allowedActions: ['TASK_ACKNOWLEDGE'],
                  acknowledgementDueAt: '2000-01-01T00:00:00Z',
                  slo: {
                    coverageState: 'OUT_OF_COVERAGE',
                    acknowledgementDueAt: '2026-09-05T09:00:00Z',
                    acknowledgementBreached: false,
                    actionBreached: true,
                    actionPaused: true,
                    wallClockExposureAgeSeconds: 7200,
                  },
                },
          ),
    );
    render(<AdvertisingWorkflow context={context} caseId="case-1" timezone="Europe/Moscow" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Acknowledge responsibility' }));
    await waitFor(() => {
      expect(
        vi
          .mocked(fetchImpl)
          .mock.calls.some(
            ([url, init]) =>
              typeof url === 'string' &&
              url.endsWith('/tasks/task-1/acknowledgement') &&
              init?.method === 'POST',
          ),
      ).toBe(true);
    });
    expect(screen.queryByText(/2000-01-01/u)).not.toBeInTheDocument();
    expect(screen.getByText(/2026-09-05T09:00:00.000Z/u)).toBeInTheDocument();
    expect(screen.getByText(/exposure age continues/u)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
