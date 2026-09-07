import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  AdvertisingManualProposalControls,
  AdvertisingManualPacketControls,
} from '../advertising/AdvertisingManualControls';
import { AdvertisingManualShadow } from '../advertising/AdvertisingManualShadow';
import { AdvertisingWorkflow } from '../advertising/AdvertisingWorkflow';
import { parseAdvertisingManualPacket } from '../api/advertising';
import { actOnAdvertisingManualPacket, fetchAdvertisingManualOptions } from '../api/console';
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
                  blockerCodes: [],
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

  it('keeps conflicting and unresolved manual options visible without granting another option authority', async () => {
    const option = (policyId: string, blockerCodes: readonly string[]): unknown => ({
      policyId,
      policyVersion: 1,
      actionKind: 'AD_BID_CHANGE',
      candidateId: `candidate-${policyId}`,
      targetBid: 20,
      blockerCodes,
    });
    const { context, fetchImpl } = client((_url, init) =>
      response(
        init.method === 'POST'
          ? packetBody
          : {
              options: [
                option('conflicted', ['OUTCOME_POLICY_CONFLICTED']),
                option('unresolved', ['OUTCOME_POLICY_UNRESOLVED']),
                option('resolved', []),
              ],
              blockerCodes: ['OUTCOME_POLICY_CONFLICTED', 'OUTCOME_POLICY_UNRESOLVED'],
              allowedActions: ['SELECT_MANUAL_PROPOSAL'],
            },
      ),
    );
    const reload = vi.fn();
    render(<AdvertisingManualProposalControls context={context} caseId="case-1" reload={reload} />);
    const selects = await screen.findAllByRole('button', { name: 'Select exact manual proposal' });
    expect(selects).toHaveLength(3);
    const [conflictedSelect, unresolvedSelect, resolvedSelect] = selects;
    if (
      conflictedSelect === undefined ||
      unresolvedSelect === undefined ||
      resolvedSelect === undefined
    )
      throw new Error('All three exact manual proposals must be rendered');
    fireEvent.change(screen.getByRole('textbox', { name: 'Manual selection reason' }), {
      target: { value: 'Select the independently resolved policy' },
    });
    expect(
      screen.getByText(/This proposal is unavailable: OUTCOME_POLICY_CONFLICTED/u),
    ).toBeVisible();
    expect(
      screen.getByText(/This proposal is unavailable: OUTCOME_POLICY_UNRESOLVED/u),
    ).toBeVisible();
    expect(conflictedSelect).toBeDisabled();
    expect(unresolvedSelect).toBeDisabled();
    expect(resolvedSelect).toBeEnabled();
    fireEvent.click(conflictedSelect);
    fireEvent.click(unresolvedSelect);
    expect(
      vi.mocked(fetchImpl).mock.calls.filter(([, init]) => init?.method === 'POST'),
    ).toHaveLength(0);
    fireEvent.click(resolvedSelect);
    await waitFor(() => {
      expect(reload).toHaveBeenCalledOnce();
    });
    const posts = vi.mocked(fetchImpl).mock.calls.filter(([, init]) => init?.method === 'POST');
    expect(posts).toHaveLength(1);
    const body = posts[0]?.[1]?.body;
    if (typeof body !== 'string') {
      throw new Error('Manual selection must send a JSON string body');
    }
    expect(JSON.parse(body)).toEqual({
      policyId: 'resolved',
      candidateId: 'candidate-resolved',
      reason: 'Select the independently resolved policy',
    });
  });

  it('explains policy repair when every manual option is blocked and the server grants no selection', async () => {
    const { context, fetchImpl } = client(() =>
      response({
        options: [
          {
            policyId: 'missing',
            policyVersion: 1,
            actionKind: 'AD_STATUS_CHANGE',
            blockerCodes: ['OUTCOME_POLICY_UNRESOLVED'],
          },
        ],
        blockerCodes: ['OUTCOME_POLICY_UNRESOLVED'],
        allowedActions: [],
      }),
    );
    render(
      <AdvertisingManualProposalControls context={context} caseId="case-1" reload={vi.fn()} />,
    );
    await screen.findByText(/This proposal is unavailable: OUTCOME_POLICY_UNRESOLVED/u);
    expect(
      screen.queryByRole('button', { name: 'Select exact manual proposal' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('textbox', { name: 'Manual selection reason' }),
    ).not.toBeInTheDocument();
    expect(
      vi.mocked(fetchImpl).mock.calls.filter(([, init]) => init?.method === 'POST'),
    ).toHaveLength(0);
  });

  it('treats a legacy manual option without policy resolution as unresolved even with a global selection action', async () => {
    const { context, fetchImpl } = client(() =>
      response({
        options: [{ policyId: 'legacy', policyVersion: 1, actionKind: 'AD_BID_CHANGE' }],
        allowedActions: ['SELECT_MANUAL_PROPOSAL'],
      }),
    );
    render(
      <AdvertisingManualProposalControls context={context} caseId="case-1" reload={vi.fn()} />,
    );
    const select = await screen.findByRole('button', { name: 'Select exact manual proposal' });
    fireEvent.change(screen.getByRole('textbox', { name: 'Manual selection reason' }), {
      target: { value: 'A human reason cannot invent missing policy resolution' },
    });
    expect(select).toBeDisabled();
    expect(
      screen.getByText(/This proposal is unavailable: OUTCOME_POLICY_UNRESOLVED/u),
    ).toBeVisible();
    fireEvent.click(select);
    expect(
      vi.mocked(fetchImpl).mock.calls.filter(([, init]) => init?.method === 'POST'),
    ).toHaveLength(0);
  });

  it.each([null, 'OUTCOME_POLICY_CONFLICTED', [1]])(
    'rejects malformed manual policy blockers %j without silently dropping them',
    async (blockerCodes) => {
      const { context } = client(() =>
        response({
          options: [
            { policyId: 'malformed', policyVersion: 1, actionKind: 'AD_BID_CHANGE', blockerCodes },
          ],
          allowedActions: ['SELECT_MANUAL_PROPOSAL'],
        }),
      );
      const result = await fetchAdvertisingManualOptions(context, 'case-1');
      expect(result).toEqual({
        ok: false,
        failure: { kind: 'malformed', detail: 'the body did not match the contract' },
      });
    },
  );

  it('preserves an unfamiliar manual blocker as a refusal instead of inferring an empty policy result', async () => {
    const { context } = client(() =>
      response({
        options: [
          {
            policyId: 'future',
            policyVersion: 1,
            actionKind: 'AD_BID_CHANGE',
            blockerCodes: ['POLICY_REVIEW_REQUIRED'],
          },
        ],
        blockerCodes: [],
        allowedActions: ['SELECT_MANUAL_PROPOSAL'],
      }),
    );
    const result = await fetchAdvertisingManualOptions(context, 'case-1');
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.options[0]?.blockerCodes).toEqual(['POLICY_REVIEW_REQUIRED']);
      expect(result.value.blockerCodes).toEqual(['POLICY_REVIEW_REQUIRED']);
    }
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

  it('does not invent independent observation details for a value-only request', async () => {
    const { context, fetchImpl } = client(() => response(packetBody));
    const packet = parseAdvertisingManualPacket(packetBody);
    if (packet === undefined) throw new Error('packet fixture must parse');
    const result = await actOnAdvertisingManualPacket(context, packet, 'INDEPENDENT_VERIFY', '20');
    expect(result.ok).toBe(false);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it.each([
    ['SCREENSHOT', 'INCOMPLETE', false],
    ['DIRECT_OFFICIAL_CONSOLE', 'COMPLETE', true],
  ] as const)(
    'submits explicit %s %s evidence with the actual observation time and exact packet scope',
    async (source, completeness, attested) => {
      const { context, fetchImpl } = client(() => response({ ...packetBody, version: 5 }));
      const packet = parseAdvertisingManualPacket({
        ...packetBody,
        packetDetails: {
          semanticProfileId: 'profile-1',
          verificationFieldPath: 'targetBid',
          nativeObjectKey: 'native-key-1',
        },
        allowedActions: ['INDEPENDENT_VERIFY'],
      });
      if (packet === undefined) throw new Error('packet fixture must parse');
      const reload = vi.fn();
      render(<AdvertisingManualPacketControls context={context} packet={packet} reload={reload} />);
      const submit = screen.getByRole('button', {
        name: 'Record independent configuration observation',
      });
      expect(submit).toBeDisabled();
      expect(screen.getByLabelText('Observation source')).toHaveValue('');
      expect(screen.getByLabelText('Observation completeness')).toHaveValue('');
      expect(screen.getByLabelText('Time actually observed (your local time)')).toHaveValue('');
      fireEvent.change(screen.getByLabelText('Independently observed exact native value'), {
        target: { value: '20' },
      });
      fireEvent.change(screen.getByLabelText('Observation source'), { target: { value: source } });
      fireEvent.change(screen.getByLabelText('Observation completeness'), {
        target: { value: completeness },
      });
      fireEvent.change(screen.getByLabelText('Observation evidence reference'), {
        target: { value: 'fixture://observation-1' },
      });
      if (attested) {
        const checkbox = screen.getByRole('checkbox', {
          name: 'I observed this object and field directly in the official console',
        });
        expect(checkbox).not.toBeChecked();
        fireEvent.click(checkbox);
      } else {
        expect(
          screen.getByText(/does not establish the configuration or release a held reservation/u),
        ).toBeInTheDocument();
      }
      expect(submit).toBeDisabled(); // Unknown actual observation time cannot be replaced by receipt time.
      const localTime = '2026-09-04T00:15:23.456';
      fireEvent.change(screen.getByLabelText('Time actually observed (your local time)'), {
        target: { value: localTime },
      });
      if (attested) {
        const checkbox = screen.getByRole('checkbox');
        fireEvent.click(checkbox);
        expect(submit).toBeDisabled();
        fireEvent.click(checkbox);
      }
      expect(submit).toBeEnabled();
      fireEvent.click(submit);
      await waitFor(() => {
        expect(reload).toHaveBeenCalledOnce();
      });
      const post = vi.mocked(fetchImpl).mock.calls[0];
      expect(post?.[0]).toContain('/manual-packets/packet-1/independent-verification');
      expect(JSON.parse(typeof post?.[1]?.body === 'string' ? post[1].body : '{}')).toEqual({
        expectedVersion: 4,
        observedValue: '20',
        observedAt: new Date(localTime).toISOString(),
        evidenceSource: source,
        completeness,
        exactNativeObjectId: 'object-1',
        exactFieldPath: 'targetBid',
        semanticProfileId: 'profile-1',
        evidenceReference: 'fixture://observation-1',
        directObservationAttested: attested,
      });
    },
  );

  it('does not infer missing packet verification scope or retain a direct attestation after changing source', () => {
    const { context } = client(() => response(packetBody));
    const packet = parseAdvertisingManualPacket({
      ...packetBody,
      allowedActions: ['INDEPENDENT_VERIFY'],
    });
    if (packet === undefined) throw new Error('packet fixture must parse');
    render(<AdvertisingManualPacketControls context={context} packet={packet} reload={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Independently observed exact native value'), {
      target: { value: '20' },
    });
    fireEvent.change(screen.getByLabelText('Observation source'), {
      target: { value: 'DIRECT_OFFICIAL_CONSOLE' },
    });
    fireEvent.change(screen.getByLabelText('Observation completeness'), {
      target: { value: 'COMPLETE' },
    });
    fireEvent.change(screen.getByLabelText('Observation evidence reference'), {
      target: { value: 'fixture://direct' },
    });
    fireEvent.change(screen.getByLabelText('Time actually observed (your local time)'), {
      target: { value: '2026-09-04T00:15' },
    });
    fireEvent.click(screen.getByRole('checkbox'));
    expect(
      screen.getByRole('button', { name: 'Record independent configuration observation' }),
    ).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Observation source'), {
      target: { value: 'SCREENSHOT' },
    });
    fireEvent.change(screen.getByLabelText('Observation source'), {
      target: { value: 'DIRECT_OFFICIAL_CONSOLE' },
    });
    expect(screen.getByRole('checkbox')).not.toBeChecked();
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
      packetDetails: { semanticProfileId: 'profile-1', verificationFieldPath: 'targetBid' },
      allowedActions: ['OBSERVE_EARLY_SAFETY'],
      verifications: [
        {
          id: 'proof-1',
          evidenceGrade: 'INDEPENDENT_MANUAL_VERIFICATION',
          qualifiedForCurrentProof: true,
          observedFieldPath: 'targetBid',
          observedValue: '20',
          independentObservation: {
            observedValue: '20',
            observedAt: '2026-09-04T00:00:00Z',
            evidenceSource: 'DIRECT_OFFICIAL_CONSOLE',
            completeness: 'COMPLETE',
            exactNativeObjectId: 'object-1',
            exactFieldPath: 'targetBid',
            semanticProfileId: 'profile-1',
            evidenceReference: 'fixture://direct-observation',
            directObservationAttested: true,
          },
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
