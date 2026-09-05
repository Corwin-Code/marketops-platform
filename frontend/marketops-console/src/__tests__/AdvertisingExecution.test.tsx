import { render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AdvertisingManualShadow } from '../advertising/AdvertisingManualShadow';
import { AdvertisingOperations } from '../advertising/AdvertisingOperations';
import { AdvertisingOutcomeHistory } from '../advertising/AdvertisingOutcomeHistory';
import {
  parseAdvertisingExposure,
  parseAdvertisingManualPacket,
  parseAdvertisingOutcome,
  parseAdvertisingReservation,
} from '../api/advertising';
import type { ConsoleRequest } from '../api/console';

/**
 * A transport that answers each advertising path with its own body.
 *
 * The operations surface issues three requests at once, and a single canned
 * response would let a test pass while the page read the wrong thing from the
 * wrong endpoint.
 */
/** The requested path, however the caller expressed it. */
function pathOf(input: RequestInfo | URL): string {
  if (typeof input === 'string') {
    return input;
  }
  return input instanceof URL ? input.href : input.url;
}

function routes(bodies: Record<string, unknown>): typeof fetch {
  return vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = pathOf(input);
    const match = Object.keys(bodies).find((path) => url.includes(path));
    if (match === undefined) {
      return Promise.resolve(new Response('{}', { status: 404 }));
    }
    return Promise.resolve(
      new Response(JSON.stringify(bodies[match]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  }) as unknown as typeof fetch;
}

/** The same body with one field never sent, as a partial backend would send it. */
function without<T extends Record<string, unknown>>(
  body: T,
  field: keyof T & string,
): Record<string, unknown> {
  const copy: Record<string, unknown> = { ...body };
  delete copy[field];
  return copy;
}

function context(fetchImpl: typeof fetch): ConsoleRequest {
  return { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'token', fetchImpl };
}

const MEASURED_ENVELOPE = {
  envelopeId: '11111111-1111-4111-8111-111111111111',
  policyVersion: 3,
  scopeKind: 'ORGANIZATION',
  currencyCode: 'RUB',
  measurementWindowHours: 24,
  retainedWindowDays: 30,
  axes: {
    activeInterventions: { usage: 7, limit: 10, state: 'AVAILABLE' },
    associatedOfficialSpend: { usage: null, limit: 500, state: 'UNKNOWN', unit: 'RUB_MAJOR' },
    affectedRetainedSalesShare: { usage: 0.12, limit: 0.2, state: 'AVAILABLE' },
    cumulativeBidChangeMajor: {
      usage: 120,
      limit: 500,
      state: 'AVAILABLE',
      windowHours: 24,
      unit: 'RUB_MAJOR',
    },
    unresolvedTransmittedWrites: { usage: 0, limit: 2, state: 'AVAILABLE' },
    reservedRecoveryHeadroom: { available: 3, reserved: 3, state: 'AVAILABLE' },
  },
  reasons: ['ASSOCIATED_SPEND_UNRESOLVED'],
};
const ENVELOPE = {
  measuredAt: '2026-09-04T00:00:00Z',
  envelopes: [MEASURED_ENVELOPE],
  unresolvedStoreIds: [],
  resolved: true,
  status: 'MEASURED',
};

const RESERVATION = {
  id: '22222222-2222-4222-8222-222222222222',
  adNativeObjectId: '33333333-3333-4333-8333-333333333333',
  storeId: '44444444-4444-4444-8444-444444444444',
  affectedSetDigest: 'a'.repeat(64),
  productVariantIds: ['55555555-5555-4555-8555-555555555555'],
  interventionKind: 'CONTROLLED_AD_BID_CHANGE',
  interventionReferenceId: '66666666-6666-4666-8666-666666666666',
  direction: 'PROTECTION_DECREASE',
  lane: 'PROTECTION',
  state: 'ACTIVE',
  holding: true,
  outstandingReleaseConditions: ['UNKNOWN_OR_MISMATCH_OPEN'],
  reservedAt: '2026-09-04T00:00:00Z',
  releasedAt: null,
  releaseReason: null,
};

const CONTAINMENT = {
  id: '77777777-7777-4777-8777-777777777777',
  containmentKind: 'OUTCOME_QUARANTINE',
  scopeKind: 'LINEAGE',
  causeClass: 'OUTCOME',
  reason: 'A settled reading went the wrong way and nobody has explained it.',
  evidenceReference: 'evidence://ad/outcome/1',
  activatedByUserId: null,
  activatedByTrigger: 'AD_OUTCOME_REGRESSION',
  activatedAt: '2026-09-04T00:00:00Z',
  state: 'ACTIVE',
  holding: true,
  outstandingConditions: ['ROOT_CAUSE_CLASSIFIED', 'UNKNOWNS_RESOLVED'],
  readyToLift: false,
  reenabledAt: null,
};

function operationsRoutes(over: Partial<Record<string, unknown>> = {}): typeof fetch {
  return routes({
    '/advertising/exposure': ENVELOPE,
    '/advertising/reservations': [RESERVATION],
    '/advertising/containments': [CONTAINMENT],
    ...over,
  });
}

describe('the exposure envelope surface', () => {
  it('TC-UI-ADV-015 reports every axis against its own limit and never combines them', async () => {
    render(<AdvertisingOperations context={context(operationsRoutes())} />);

    const envelope = await screen.findByLabelText('Exposure envelope');
    expect(within(envelope).getAllByRole('row')).toHaveLength(7);
    for (const code of Object.keys(MEASURED_ENVELOPE.axes)) {
      expect(envelope.querySelector(`[data-axis="${code}"]`)).not.toBeNull();
    }
    const unknown = envelope.querySelector('[data-axis="associatedOfficialSpend"]');
    expect(unknown?.textContent).toContain('not measured');
    expect(unknown?.textContent).toContain('unknown; capacity unproven');
    expect(unknown?.textContent).not.toContain('within current limit');
  });

  it('TC-UI-ADV-016 shows remaining headroom independently from current interventions', async () => {
    render(<AdvertisingOperations context={context(operationsRoutes())} />);

    const envelope = await screen.findByLabelText('Exposure envelope');
    const row = envelope.querySelector('[data-axis="activeInterventions"]');
    expect(row?.textContent).toContain('7');
    expect(row?.textContent).toContain('3 of 10 reserved for recovery');
    const headroom = envelope.querySelector('[data-axis="reservedRecoveryHeadroom"]');
    expect(headroom?.textContent).toContain('remaining intervention capacity / reserved capacity');
    expect(headroom?.querySelectorAll('td')[0]?.textContent).toBe('3');
    expect(headroom?.querySelectorAll('td')[1]?.textContent).toBe('3');
  });

  it('TC-UI-ADV-017 says plainly that no envelope means no write at all', async () => {
    render(
      <AdvertisingOperations
        context={context(
          operationsRoutes({
            '/advertising/exposure': {
              measuredAt: ENVELOPE.measuredAt,
              envelopes: [],
              unresolvedStoreIds: ['unresolved-store'],
              resolved: false,
              status: 'UNRESOLVED',
            },
          }),
        )}
      />,
    );

    const envelope = await screen.findByLabelText('Exposure envelope');
    expect(envelope.getAttribute('data-state')).toBe('unresolved');
    expect(within(envelope).getByRole('alert').textContent).toContain('No exposure envelope');
  });

  it('TC-UI-ADV-018 preserves both organization and Store envelope limits', async () => {
    render(
      <AdvertisingOperations
        context={context(
          operationsRoutes({
            '/advertising/exposure': {
              ...ENVELOPE,
              envelopes: [
                MEASURED_ENVELOPE,
                {
                  ...MEASURED_ENVELOPE,
                  envelopeId: 'other-envelope',
                  scopeKind: 'STORE',
                  storeId: 'scoped-store',
                  axes: {
                    ...MEASURED_ENVELOPE.axes,
                    activeInterventions: { usage: 2, limit: 3, state: 'AVAILABLE' },
                  },
                },
              ],
            },
          }),
        )}
      />,
    );

    const envelope = await screen.findByLabelText('Exposure envelope');
    expect(within(envelope).getAllByRole('table')).toHaveLength(2);
    expect(envelope.textContent).toContain('ORGANIZATION scope');
    expect(envelope.textContent).toContain('STORE scope / Store scoped-store');
    expect(
      envelope.querySelector('[data-envelope="other-envelope"] [data-axis="activeInterventions"]')
        ?.textContent,
    ).toContain('2');
  });
});

describe('six-axis exposure disclosure and partial-input boundaries', () => {
  it('TC-UI-ADV-042 discards all measurements and scope identities from a MASKED body', async () => {
    const body = {
      ...ENVELOPE,
      disclosureState: 'MASKED',
      unresolvedStoreIds: ['private-store'],
      envelopes: [{ ...MEASURED_ENVELOPE, envelopeId: 'private-envelope' }],
    };
    expect(parseAdvertisingExposure(body)).toEqual({
      measuredAt: undefined,
      envelopes: [],
      unresolvedStoreIds: [],
      resolved: false,
      status: 'MASKED',
    });
    render(
      <AdvertisingOperations
        context={context(operationsRoutes({ '/advertising/exposure': body }))}
      />,
    );
    const panel = await screen.findByLabelText('Exposure envelope');
    expect(panel).toHaveAttribute('data-state', 'masked');
    expect(within(panel).queryByRole('table')).not.toBeInTheDocument();
    expect(panel.textContent).toContain('not zero or spare capacity');
    expect(panel.textContent).not.toContain('private-store');
    expect(panel.textContent).not.toContain('private-envelope');
    expect(panel.textContent).not.toContain(ENVELOPE.measuredAt);
  });

  it.each([
    ['absent', undefined],
    ['null', null],
    ['empty', ''],
    ['whitespace', '  '],
    ['not a number', 'unresolved'],
    ['nonfinite', Number.POSITIVE_INFINITY],
    ['boolean', false],
  ])(
    'TC-UI-ADV-043 %s usage cannot become a measured zero or available capacity',
    (_label, usage) => {
      const result = parseAdvertisingExposure({
        ...ENVELOPE,
        envelopes: [
          {
            ...MEASURED_ENVELOPE,
            axes: {
              ...MEASURED_ENVELOPE.axes,
              activeInterventions: { usage, limit: 10, state: 'AVAILABLE' },
            },
          },
        ],
      });
      expect(result?.envelopes[0]?.axes.activeInterventions.usage).toBeUndefined();
      expect(result?.envelopes[0]?.axes.activeInterventions.limit).toBe(10);
      expect(result?.envelopes[0]?.axes.activeInterventions.state).toBe('UNKNOWN');
    },
  );

  it('TC-UI-ADV-044 preserves explicit zero and exceeded negative headroom independently', async () => {
    const body = {
      ...ENVELOPE,
      envelopes: [
        {
          ...MEASURED_ENVELOPE,
          axes: {
            ...MEASURED_ENVELOPE.axes,
            unresolvedTransmittedWrites: { usage: 0, limit: 2, state: 'AVAILABLE' },
            reservedRecoveryHeadroom: { available: -2, reserved: 3, state: 'EXCEEDED' },
          },
        },
      ],
    };
    const parsed = parseAdvertisingExposure(body);
    expect(parsed?.envelopes[0]?.axes.unresolvedTransmittedWrites.usage).toBe(0);
    expect(parsed?.envelopes[0]?.axes.unresolvedTransmittedWrites.state).toBe('AVAILABLE');
    render(
      <AdvertisingOperations
        context={context(operationsRoutes({ '/advertising/exposure': body }))}
      />,
    );
    const panel = await screen.findByLabelText('Exposure envelope');
    const row = panel.querySelector('[data-axis="reservedRecoveryHeadroom"]');
    expect(row?.querySelectorAll('td')[0]?.textContent).toBe('-2');
    expect(row?.querySelectorAll('td')[2]?.textContent).toBe('exceeded');
  });

  it('TC-UI-ADV-045 partial Store coverage stays unresolved despite a claimed resolved flag', async () => {
    const body = { ...ENVELOPE, unresolvedStoreIds: ['missing-store'], resolved: true };
    expect(parseAdvertisingExposure(body)?.resolved).toBe(false);
    render(
      <AdvertisingOperations
        context={context(operationsRoutes({ '/advertising/exposure': body }))}
      />,
    );
    const panel = await screen.findByLabelText('Exposure envelope');
    expect(panel).toHaveAttribute('data-state', 'unresolved');
    expect(within(panel).getByRole('alert').textContent).toContain(
      'cannot admit a new advertising action',
    );
    expect(panel.textContent).toContain('missing-store');
    expect(within(panel).getAllByRole('row')).toHaveLength(7);
  });

  it('TC-UI-ADV-046 rejects malformed scope lists, missing axes and invalid policy versions', () => {
    expect(parseAdvertisingExposure({ ...ENVELOPE, unresolvedStoreIds: [42] })).toBeUndefined();
    expect(parseAdvertisingExposure({ ...ENVELOPE, unresolvedStoreIds: ['  '] })).toBeUndefined();
    expect(
      parseAdvertisingExposure({
        ...ENVELOPE,
        envelopes: [{ ...MEASURED_ENVELOPE, policyVersion: 1.5 }],
      }),
    ).toBeUndefined();
    expect(
      parseAdvertisingExposure({
        ...ENVELOPE,
        envelopes: [
          {
            ...MEASURED_ENVELOPE,
            axes: without(MEASURED_ENVELOPE.axes, 'reservedRecoveryHeadroom'),
          },
        ],
      }),
    ).toBeUndefined();
    expect(parseAdvertisingExposure({ ...ENVELOPE, resolved: undefined })).toBeUndefined();
    expect(parseAdvertisingExposure({ ...ENVELOPE, status: 'unexpected' })).toBeUndefined();
  });

  it('TC-UI-ADV-047 exposes the exact Retained denominator and conservative official-report window', async () => {
    const body = {
      ...ENVELOPE,
      envelopes: [
        {
          ...MEASURED_ENVELOPE,
          axes: {
            ...MEASURED_ENVELOPE.axes,
            associatedOfficialSpend: {
              usage: 450,
              limit: 500,
              state: 'AVAILABLE',
              unit: 'RUB_MAJOR',
              aggregationBasis: 'COMPLETE_INTERSECTING_OFFICIAL_REPORT_AMOUNTS',
              conservativeBoundaryReportCount: 2,
            },
            affectedRetainedSalesShare: {
              usage: 0.12,
              limit: 0.2,
              state: 'AVAILABLE',
              affectedSales: 120,
              companySales: 1000,
            },
          },
        },
      ],
    };
    render(
      <AdvertisingOperations
        context={context(operationsRoutes({ '/advertising/exposure': body }))}
      />,
    );
    const panel = await screen.findByLabelText('Exposure envelope');
    expect(panel.querySelector('[data-axis="associatedOfficialSpend"]')?.textContent).toContain(
      '2 reports crossed the start of this window and are counted in full',
    );
    expect(panel.querySelector('[data-axis="affectedRetainedSalesShare"]')?.textContent).toContain(
      'affected sales 120 / company sales 1000',
    );
  });

  it('TC-UI-ADV-048 missing limits and recovery reserves keep explicit UNKNOWN states', () => {
    const result = parseAdvertisingExposure({
      ...ENVELOPE,
      envelopes: [
        {
          ...MEASURED_ENVELOPE,
          axes: {
            ...MEASURED_ENVELOPE.axes,
            cumulativeBidChangeMajor: {
              usage: 120,
              state: 'AVAILABLE',
              windowHours: 24,
              unit: 'RUB_MAJOR',
            },
            reservedRecoveryHeadroom: { available: 3, reserved: null, state: 'AVAILABLE' },
          },
        },
      ],
    });
    expect(result?.envelopes[0]?.axes.cumulativeBidChangeMajor.state).toBe('UNKNOWN');
    expect(result?.envelopes[0]?.axes.reservedRecoveryHeadroom.state).toBe('UNKNOWN');
    expect(result?.envelopes[0]?.axes.reservedRecoveryHeadroom.reserved).toBeUndefined();
  });
});

describe('the containment surface', () => {
  it('TC-UI-ADV-019 names the kind of stop rather than a severity', async () => {
    render(<AdvertisingOperations context={context(operationsRoutes())} />);

    const holds = await screen.findByLabelText('Containment');
    expect(holds.querySelector('[data-kind="OUTCOME_QUARANTINE"]')).not.toBeNull();
    expect(holds.textContent).toContain('OUTCOME_QUARANTINE');
  });

  it('TC-UI-ADV-020 lists the outstanding reenablement conditions individually', async () => {
    render(<AdvertisingOperations context={context(operationsRoutes())} />);

    const holds = await screen.findByLabelText('Containment');
    const conditions = within(holds).getByLabelText('Outstanding reenablement conditions');
    // Two conditions, named. A count would not tell an operator what to do next.
    expect(within(conditions).getAllByRole('listitem')).toHaveLength(2);
  });

  it('TC-UI-ADV-021 says nothing is held rather than showing an empty list', async () => {
    render(
      <AdvertisingOperations
        context={context(operationsRoutes({ '/advertising/containments': [] }))}
      />,
    );

    const holds = await screen.findByLabelText('Containment');
    expect(holds.getAttribute('data-state')).toBe('empty');
    expect(holds.textContent).toContain('Nothing is held');
  });
});

describe('the reservation surface', () => {
  it('TC-UI-ADV-022 shows what a reservation is waiting on before it can release', async () => {
    render(<AdvertisingOperations context={context(operationsRoutes())} />);

    const held = await screen.findByLabelText('Reservations');
    expect(held.textContent).toContain('UNKNOWN_OR_MISMATCH_OPEN');
  });

  it('TC-UI-ADV-023 says an empty reservation list is compatible with a full queue', async () => {
    render(
      <AdvertisingOperations
        context={context(operationsRoutes({ '/advertising/reservations': [] }))}
      />,
    );

    const held = await screen.findByLabelText('Reservations');
    // The whole point of reading rather than reserving at the proposal stage.
    expect(held.textContent).toContain('Only a real intervention takes a reservation');
  });

  it('TC-UI-ADV-024 fails the whole page when any one of the three reads fails', async () => {
    const fetchImpl = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = pathOf(input);
      if (url.includes('/advertising/containments')) {
        return Promise.resolve(new Response('{}', { status: 403 }));
      }
      return Promise.resolve(
        new Response(JSON.stringify(url.includes('exposure') ? ENVELOPE : []), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    }) as unknown as typeof fetch;

    render(<AdvertisingOperations context={context(fetchImpl)} />);

    // An empty containment list rendered because that one call failed would
    // tell an operator nothing was held. Refusing the page is the safe reading.
    await waitFor(() => {
      expect(screen.getByRole('alert')).not.toBeNull();
    });
    expect(screen.queryByLabelText('Containment')).toBeNull();
  });
});

describe('the outcome history', () => {
  const OPERATIONAL = {
    id: '88888888-8888-4888-8888-888888888888',
    commandId: '99999999-9999-4999-8999-999999999999',
    outcomeStage: 'OPERATIONAL',
    revisionNo: 1,
    supersedesObservationId: null,
    adjustmentReason: null,
    windowStartsAt: '2026-08-05T00:00:00Z',
    windowEndsAt: '2026-08-12T00:00:00Z',
    baselineMetricState: 'AVAILABLE',
    baselineMetricValue: '100.0000',
    observedMetricState: 'AVAILABLE',
    observedMetricValue: '140.0000',
    observedTrafficCount: 1000,
    settledCoverageRatio: null,
    verdict: 'IMPROVED',
    guardState: 'SUFFICIENT',
    unresolvedReasonCodes: [],
    settled: false,
    evaluatedAt: '2026-08-12T00:00:00Z',
  };
  const SETTLED = {
    ...OPERATIONAL,
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    outcomeStage: 'SETTLED',
    observedMetricValue: '90.0000',
    settledCoverageRatio: '0.92000',
    verdict: 'REGRESSED',
    settled: true,
    evaluatedAt: '2026-09-04T00:00:00Z',
  };

  function outcomeRoutes(bodies: unknown): typeof fetch {
    return routes({ '/outcomes': bodies });
  }

  it('TC-UI-ADV-025 never collapses the operational and settled readings into one', async () => {
    render(
      <AdvertisingOutcomeHistory
        context={context(outcomeRoutes([OPERATIONAL, SETTLED]))}
        commandId={OPERATIONAL.commandId}
      />,
    );

    const outcome = await screen.findByLabelText('Outcome');
    await waitFor(() => {
      expect(outcome).toHaveAttribute('data-state', 'loaded');
    });
    // Two readings, two verdicts, and the one that disagrees is still there.
    // A single "result" would show whichever was written last.
    expect(within(outcome).getAllByRole('listitem')).toHaveLength(2);
    expect(outcome.querySelector('[data-stage="OPERATIONAL"]')).not.toBeNull();
    expect(outcome.querySelector('[data-stage="SETTLED"]')).not.toBeNull();
    expect(outcome.textContent).toContain('IMPROVED');
    expect(outcome.textContent).toContain('REGRESSED');
  });

  it('TC-UI-ADV-026 shows a restatement beside what it restates, never in place of it', async () => {
    const restated = {
      ...SETTLED,
      id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      revisionNo: 2,
      supersedesObservationId: SETTLED.id,
      adjustmentReason: 'LATE_RETURN_RESTATEMENT',
      observedMetricValue: '85.0000',
    };
    render(
      <AdvertisingOutcomeHistory
        context={context(outcomeRoutes([SETTLED, restated]))}
        commandId={SETTLED.commandId}
      />,
    );

    const outcome = await screen.findByLabelText('Outcome');
    await waitFor(() => {
      expect(outcome).toHaveAttribute('data-state', 'loaded');
    });
    expect(within(outcome).getAllByRole('listitem')).toHaveLength(2);
    // That the answer changed is itself something an operator needs to see.
    expect(outcome.textContent).toContain('restatement 2');
    expect(outcome.textContent).toContain('LATE_RETURN_RESTATEMENT');
  });

  it('TC-UI-ADV-027 says an absent outcome is not a neutral one', async () => {
    render(
      <AdvertisingOutcomeHistory
        context={context(outcomeRoutes([]))}
        commandId={OPERATIONAL.commandId}
      />,
    );

    const outcome = await screen.findByLabelText('Outcome');
    await waitFor(() => {
      expect(outcome).toHaveAttribute('data-state', 'empty');
    });
    expect(outcome.getAttribute('data-state')).toBe('empty');
    expect(outcome.textContent).toContain('not a neutral one');
  });

  it('TC-UI-ADV-028 refuses an observation whose stage the body did not name', () => {
    // A reading with no stage could not be told from the other stage, which is
    // the exact confusion this surface exists to prevent.
    expect(parseAdvertisingOutcome(without(OPERATIONAL, 'outcomeStage'))).toBeUndefined();
    expect(parseAdvertisingOutcome(OPERATIONAL)).not.toBeUndefined();
  });
});

describe('the manual shadow', () => {
  const PACKET = {
    id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    caseId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
    adNativeObjectId: '33333333-3333-4333-8333-333333333333',
    actionKind: 'MANUAL_BUDGET_CHANGE',
    intendedState: '{"dailyBudget":"5000.0000"}',
    reason: 'The object outspends what its variants can return.',
    evidenceReference: 'evidence://ad/manual/1',
    blockerCodes: [],
    makerUserId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
    endorserUserId: null,
    approverUserId: null,
    state: 'MANUAL_PACKET_ISSUED',
    issuedAt: '2026-09-04T00:00:00Z',
    expiresAt: '2026-09-05T00:00:00Z',
    configurationProven: false,
    verifications: [],
  };

  it('TC-UI-ADV-029 shows a self-report as a report, never as a proof', async () => {
    const selfReported = {
      ...PACKET,
      verifications: [
        {
          id: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
          evidenceGrade: 'EXECUTOR_SELF_REPORT',
          executorUserId: PACKET.makerUserId,
          verifierUserId: null,
          observedFieldPath: 'dailyBudget',
          observedValue: '5000.0000',
          conflictState: 'NONE',
          provesConfiguration: false,
          observedAt: '2026-09-04T01:00:00Z',
        },
      ],
    };
    render(
      <AdvertisingManualShadow
        context={context(routes({ '/manual-packets': [selfReported] }))}
        objectId={PACKET.adNativeObjectId}
      />,
    );

    const manual = await screen.findByLabelText('Manual execution');
    await waitFor(() => {
      expect(manual).toHaveAttribute('data-state', 'loaded');
    });
    expect(manual.querySelector('[data-proves="false"]')?.textContent).toContain(
      'a report, not a proof',
    );
    expect(manual.textContent).toContain('Nothing has established the resulting configuration');
  });

  it('TC-UI-ADV-030 shows an official readback as establishing the configuration', async () => {
    const readback = {
      ...PACKET,
      configurationProven: true,
      state: 'MANUAL_CONFIGURATION_VERIFIED',
      currentProofId: 'ffffffff-ffff-4fff-8fff-fffffffffff1',
      verifications: [
        {
          id: 'ffffffff-ffff-4fff-8fff-fffffffffff1',
          evidenceGrade: 'OFFICIAL_API_READBACK',
          executorUserId: PACKET.makerUserId,
          verifierUserId: null,
          observedFieldPath: 'dailyBudget',
          observedValue: '5000.0000',
          conflictState: 'NONE',
          provesConfiguration: true,
          observedAt: '2026-09-04T01:00:00Z',
        },
      ],
    };
    render(
      <AdvertisingManualShadow
        context={context(routes({ '/manual-packets': [readback] }))}
        objectId={PACKET.adNativeObjectId}
      />,
    );

    const manual = await screen.findByLabelText('Manual execution');
    await waitFor(() => {
      expect(manual).toHaveAttribute('data-state', 'loaded');
    });
    expect(manual.querySelector('[data-proven="true"]')).not.toBeNull();
    expect(manual.textContent).toContain('has been established');
  });

  it('TC-UI-ADV-031 says nothing here reaches a marketplace by itself', async () => {
    render(
      <AdvertisingManualShadow
        context={context(routes({ '/manual-packets': [PACKET] }))}
        objectId={PACKET.adNativeObjectId}
      />,
    );

    const manual = await screen.findByLabelText('Manual execution');
    await waitFor(() => {
      expect(manual).toHaveAttribute('data-state', 'loaded');
    });
    // Budget and pause work lives entirely on this surface. The page has to say
    // so, because an operator who thought it queued a command would wait.
    expect(manual.textContent).toContain('Nothing here reaches a marketplace by itself');
    expect(manual.textContent).toContain('nothing here creates a command');
  });

  it('TC-UI-ADV-032 re-derives proof from the observations rather than trusting the flag', () => {
    // A body that claimed proof while carrying none would otherwise assert it.
    const lying = { ...PACKET, configurationProven: true, verifications: [] };
    expect(parseAdvertisingManualPacket(lying)?.configurationProven).toBe(false);
  });
});

describe('the operations parsers', () => {
  it('TC-UI-ADV-033 refuses a reservation with no intervention kind', () => {
    expect(parseAdvertisingReservation(without(RESERVATION, 'interventionKind'))).toBeUndefined();
  });

  it('TC-UI-ADV-034 an absent flag never clears an active reservation', () => {
    expect(parseAdvertisingReservation(without(RESERVATION, 'holding'))?.holding).toBe(true);
  });

  it('TC-UI-ADV-035 refuses an envelope body with no consumption figure', () => {
    // Without it the page could not say what is standing, and rendering zero
    // would say nothing is.
    expect(parseAdvertisingExposure({ envelopeId: null, resolved: false })).toBeUndefined();
  });
});
