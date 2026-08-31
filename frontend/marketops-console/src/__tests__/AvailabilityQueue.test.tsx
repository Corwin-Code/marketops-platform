import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AvailabilityQueue } from '../availability/AvailabilityQueue';
import { presentEvidence, laneSeverity, causeLabel } from '../availability/riskPresentation';
import type { ConsoleRequest } from '../api/console';

function respondWith(body: unknown, status = 200): typeof fetch {
  return vi.fn().mockImplementation(() =>
    Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  ) as unknown as typeof fetch;
}

function context(fetchImpl: typeof fetch): ConsoleRequest {
  return { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'token', fetchImpl };
}

const CHANNEL_CHILD = {
  id: '11111111-1111-4111-8111-111111111111',
  childKind: 'CHANNEL',
  platformCode: 'OZON',
  storeId: '22222222-2222-4222-8222-222222222222',
  platformListingVariantId: '33333333-3333-4333-8333-333333333333',
  fulfillmentModeCode: 'MARKETPLACE_FULFILLED',
  lane: 'CRITICAL',
  evidenceState: 'CONFIRMED',
  confidenceState: 'HIGH',
  causeCode: 'CHANNEL_OUT_OF_STOCK',
  availableUnits: 0,
  dailyDemandRate: '6.0000',
  daysOfCover: '0.00',
  coverageHorizonDays: 21,
  projectedStockoutAt: '2026-08-31T12:00:00Z',
  profitLane: 'CONFIRMED_ELIGIBLE',
  profitAtRiskAmount: '120.0000',
  profitAtRiskCurrency: 'RUB',
  demandSelectionReason: 'stable baseline: longest eligible window D30',
  conservativeProofTerms: ['the source reports zero available units for this mode'],
  blockerCodes: [],
  rankFactors: [
    {
      factorCode: 'TIME_TO_STOCKOUT',
      value: '0.00',
      weight: '400',
      contribution: '400',
      displayNote: 'runs out in 0.0 days',
    },
  ],
  demandWindows: [
    {
      windowCode: 'D30',
      periodStart: '2026-08-01T12:00:00Z',
      periodEnd: '2026-08-31T12:00:00Z',
      completedUnits: 180,
      dailyRate: '6.0000',
      observedDays: '30.00',
      coverageRatio: '1.000',
      sampleSufficient: true,
      censored: false,
      censoringReason: null,
      outlierShare: '0.100',
      eligibility: 'ELIGIBLE',
    },
  ],
  calculatedAt: '2026-08-31T12:00:00Z',
};

const COMPANY_CHILD = {
  ...CHANNEL_CHILD,
  id: '44444444-4444-4444-8444-444444444444',
  childKind: 'COMPANY',
  platformCode: null,
  storeId: null,
  platformListingVariantId: null,
  fulfillmentModeCode: null,
  lane: 'UNRESOLVED',
  evidenceState: 'DATA_BLOCKED',
  confidenceState: 'UNUSABLE',
  causeCode: 'OWNERSHIP_UNDECLARED',
  availableUnits: 40,
  conservativeProofTerms: [],
  blockerCodes: ['COMPANY_SUPPLY_OWNERSHIP_NOT_DECLARED'],
};

const CARD = {
  id: '55555555-5555-4555-8555-555555555555',
  productVariantId: '66666666-6666-4666-8666-666666666666',
  skuCode: 'kettle-1l',
  displayName: 'Чайник 1 л',
  lane: 'CRITICAL',
  triggeringChildId: CHANNEL_CHILD.id,
  rankScore: '300400.0000',
  policyVersionDigest: 'a'.repeat(64),
  asOf: '2026-08-31T12:00:00Z',
  calculatedAt: '2026-08-31T12:00:00Z',
  children: [CHANNEL_CHILD, COMPANY_CHILD],
};

describe('AvailabilityQueue', () => {
  it('TC-UI-001 shows a grouped card with both of its children', async () => {
    render(<AvailabilityQueue context={context(respondWith([CARD]))} />);

    await waitFor(() => {
      expect(screen.getByTestId('availability-queue')).toBeTruthy();
    });
    expect(screen.getAllByTestId('availability-card')).toHaveLength(1);
    expect(screen.getAllByTestId('availability-child')).toHaveLength(2);
  });

  it('TC-UI-002 renders Russian text without mangling it', async () => {
    render(<AvailabilityQueue context={context(respondWith([CARD]))} />);

    await waitFor(() => {
      expect(screen.getByText('Чайник 1 л')).toBeTruthy();
    });
  });

  it('TC-UI-003 names the child that produced the card lane', async () => {
    render(<AvailabilityQueue context={context(respondWith([CARD]))} />);

    await waitFor(() => {
      expect(screen.getByTestId('card-trigger').textContent).toContain('OZON');
    });
  });

  it('TC-UI-004 gives blocked evidence a different tone from confirmed evidence', async () => {
    render(<AvailabilityQueue context={context(respondWith([CARD]))} />);

    await waitFor(() => {
      expect(screen.getAllByTestId('availability-child')).toHaveLength(2);
    });
    const children = screen.getAllByTestId('availability-child');
    const tones = children.map((child) => child.getAttribute('data-evidence-tone'));
    expect(tones).toContain('confirmed');
    expect(tones).toContain('blocked');
    const established = children.map((child) => child.getAttribute('data-established-fact'));
    expect(established).toContain('true');
    expect(established).toContain('false');
  });

  it('TC-UI-005 shows the conservative proof when one was established', async () => {
    render(<AvailabilityQueue context={context(respondWith([CARD]))} />);

    await waitFor(() => {
      expect(screen.getAllByTestId('child-proof')).toHaveLength(1);
    });
  });

  it('TC-UI-006 shows the blockers a child is waiting on', async () => {
    render(<AvailabilityQueue context={context(respondWith([CARD]))} />);

    await waitFor(() => {
      expect(screen.getByTestId('child-blockers').textContent).toContain(
        'COMPANY_SUPPLY_OWNERSHIP_NOT_DECLARED',
      );
    });
  });

  it('TC-UI-007 says an empty queue is not an unmonitored one', async () => {
    render(<AvailabilityQueue context={context(respondWith([]))} />);

    await waitFor(() => {
      expect(screen.getByText(/not the same as an unmonitored one/)).toBeTruthy();
    });
  });

  it('TC-UI-008 refuses a card whose evidence state is missing', async () => {
    const damaged = { ...CARD, children: [{ ...CHANNEL_CHILD, evidenceState: undefined }] };
    render(<AvailabilityQueue context={context(respondWith([damaged]))} />);

    await waitFor(() => {
      expect(screen.getAllByTestId('availability-card')).toHaveLength(1);
    });
    // The child is dropped rather than rendered with a guessed evidence state.
    expect(screen.queryAllByTestId('availability-child')).toHaveLength(0);
  });

  it('TC-UI-009 reports a refusal instead of an empty queue', async () => {
    render(<AvailabilityQueue context={context(respondWith({}, 403))} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeTruthy();
    });
    expect(screen.queryByTestId('availability-queue')).toBeNull();
  });
});

describe('riskPresentation', () => {
  it('TC-UI-010 only confirmed and operational evidence is an established fact', () => {
    expect(presentEvidence('CONFIRMED').establishedFact).toBe(true);
    expect(presentEvidence('OPERATIONAL').establishedFact).toBe(true);
    for (const state of [
      'PROVISIONAL',
      'CARRIED_FORWARD',
      'DATA_BLOCKED',
      'POLICY_BLOCKED',
      'CONFLICTED',
      'STALE',
      'UNKNOWN',
    ]) {
      expect(presentEvidence(state).establishedFact).toBe(false);
    }
  });

  it('TC-UI-011 an unrecognised evidence state is never presented as confirmed', () => {
    const unknown = presentEvidence('SOMETHING_NEW');
    expect(unknown.establishedFact).toBe(false);
    expect(unknown.tone).toBe('blocked');
  });

  it('TC-UI-012 evidence-limited lanes rank with high, not below watch', () => {
    expect(laneSeverity('REVIEW')).toBe(laneSeverity('HIGH'));
    expect(laneSeverity('UNRESOLVED')).toBe(laneSeverity('HIGH'));
    expect(laneSeverity('REVIEW')).toBeGreaterThan(laneSeverity('WATCH'));
  });

  it('TC-UI-013 a cause reads as a sentence rather than a code', () => {
    expect(causeLabel('COMPANY_SUPPLY_SHORT')).toContain('lead time');
    expect(causeLabel('OWNERSHIP_UNDECLARED')).toContain('distinct');
  });
});
