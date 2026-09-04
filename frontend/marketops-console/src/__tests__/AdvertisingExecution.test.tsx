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

const ENVELOPE = {
  envelopeId: '11111111-1111-4111-8111-111111111111',
  policyVersion: 3,
  scopeKind: 'ORGANIZATION',
  currencyCode: 'RUB',
  activeInterventions: 7,
  maxActiveInterventions: 10,
  reservedRecoveryHeadroom: 3,
  unresolvedTransmittedWrites: 0,
  maxUnresolvedTransmittedWrites: 2,
  cumulativeBidChangeAmount: '120.0000',
  maxCumulativeBidChangeAmount: '500.0000',
  cumulativeWindowHours: 24,
  resolved: true,
  exhaustedAxes: ['ACTIVE_INTERVENTIONS'],
  status: 'ACTIVE',
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
    // Three axes, three limits, and nothing that reads as an overall figure.
    // A single percentage would describe a quantity the product does not have.
    expect(within(envelope).getAllByRole('row')).toHaveLength(4);
    expect(envelope.querySelector('[data-axis="ACTIVE_INTERVENTIONS"]')).not.toBeNull();
    expect(envelope.querySelector('[data-axis="UNRESOLVED_TRANSMITTED_WRITES"]')).not.toBeNull();
    expect(envelope.querySelector('[data-axis="CUMULATIVE_BID_CHANGE"]')).not.toBeNull();
  });

  it('TC-UI-ADV-016 shows the intervention limit reduced by the recovery headroom', async () => {
    render(<AdvertisingOperations context={context(operationsRoutes())} />);

    const envelope = await screen.findByLabelText('Exposure envelope');
    const row = envelope.querySelector('[data-axis="ACTIVE_INTERVENTIONS"]');
    // Ten interventions with three reserved for recovery leaves seven for
    // ordinary work. Showing ten would tell an operator they had room.
    expect(row?.textContent).toContain('7');
    expect(row?.textContent).toContain('3 of 10 reserved for recovery');
    expect(row?.querySelector('[data-exhausted="true"]')).not.toBeNull();
  });

  it('TC-UI-ADV-017 says plainly that no envelope means no write at all', async () => {
    render(
      <AdvertisingOperations
        context={context(
          operationsRoutes({
            '/advertising/exposure': {
              envelopeId: null,
              activeInterventions: 2,
              unresolvedTransmittedWrites: 1,
              resolved: false,
              exhaustedAxes: ['AGGREGATE_ENVELOPE_UNRESOLVED'],
            },
          }),
        )}
      />,
    );

    const envelope = await screen.findByLabelText('Exposure envelope');
    expect(envelope.getAttribute('data-state')).toBe('unresolved');
    expect(within(envelope).getByRole('alert').textContent).toContain('No exposure envelope');
  });

  it('TC-UI-ADV-018 keeps a retired envelope visible as still binding', async () => {
    render(
      <AdvertisingOperations
        context={context(
          operationsRoutes({
            '/advertising/exposure': { ...ENVELOPE, status: 'RETIRED' },
          }),
        )}
      />,
    );

    const envelope = await screen.findByLabelText('Exposure envelope');
    // The gate treats a retired envelope as binding rather than absent, so the
    // console may not present it as expired.
    expect(envelope.textContent).toContain('retired and still binding');
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
    expect(manual.querySelector('[data-proves="false"]')?.textContent).toContain(
      'a report, not a proof',
    );
    expect(manual.textContent).toContain('Nothing has established the resulting configuration');
  });

  it('TC-UI-ADV-030 shows an official readback as establishing the configuration', async () => {
    const readback = {
      ...PACKET,
      configurationProven: true,
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

  it('TC-UI-ADV-034 never reads an absent holding flag as holding', () => {
    expect(parseAdvertisingReservation(without(RESERVATION, 'holding'))?.holding).toBe(false);
  });

  it('TC-UI-ADV-035 refuses an envelope body with no consumption figure', () => {
    // Without it the page could not say what is standing, and rendering zero
    // would say nothing is.
    expect(parseAdvertisingExposure({ envelopeId: null, resolved: false })).toBeUndefined();
  });
});
