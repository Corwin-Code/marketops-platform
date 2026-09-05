import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AdvertisingQueue } from '../advertising/AdvertisingQueue';
import { AdvertisingCaseView } from '../advertising/AdvertisingCaseView';
import { EvidenceChip } from '../advertising/EvidenceChip';
import {
  EVIDENCE_STATES,
  presentEvidence,
  presentMeasure,
} from '../advertising/evidencePresentation';
import { parseAdvertisingCase, parseAdvertisingWorkflow } from '../api/advertising';
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

const PROTECTION_CASE = {
  id: '11111111-1111-4111-8111-111111111111',
  storeId: '22222222-2222-4222-8222-222222222222',
  platformCode: 'OZON',
  adNativeObjectId: '33333333-3333-4333-8333-333333333333',
  nativeObjectKind: 'KEYWORD',
  nativeObjectName: 'зимние сапоги',
  lane: 'PROTECTION',
  protectionTier: 'P2',
  causeCode: 'PROVEN_ADVERTISING_LOSS',
  accountableRoleCode: 'MARKETPLACE_OPERATOR',
  evidenceState: 'CANONICAL_CONFIRMED',
  confidenceState: 'HIGH',
  blockerCodes: [],
  contributionProfitState: 'AVAILABLE',
  contributionProfitAmount: '-1200.0000',
  profitPerAdRubState: 'UNDEFINED',
  profitPerAdRubValue: null,
  profitCurrencyCode: 'RUB',
  officialSpendState: 'AVAILABLE',
  officialSpendAmount: '4500.0000',
  eligibleTrafficState: 'NOT_AVAILABLE',
  eligibleTrafficCount: null,
  maxCpcState: 'AVAILABLE',
  maxCpcAmount: '18.0000',
  currentBidState: 'AVAILABLE',
  currentBidAmount: '30.0000',
  rankScore: '700100',
  asOf: '2026-09-04T00:00:00Z',
  rankFactors: [
    {
      factorCode: 'PROFIT_LOSS',
      value: '0.4',
      weight: '1.0',
      contribution: '0.4',
      displayNote: null,
    },
    {
      factorCode: 'CRITICAL_SALES',
      value: null,
      weight: '1.0',
      contribution: null,
      displayNote: 'NOT_AVAILABLE',
    },
  ],
};

describe('the advertising evidence vocabulary', () => {
  it('TC-UI-ADV-001 describes every state the backend can send', () => {
    // The mapping is total by construction; this asserts the list itself has
    // not been pruned to make a gap compile.
    for (const state of EVIDENCE_STATES) {
      const presentation = presentEvidence(state);
      expect(presentation, state).toBeDefined();
      expect(presentation?.label.length, state).toBeGreaterThan(0);
      expect(presentation?.explanation.length, state).toBeGreaterThan(0);
    }
  });

  it('TC-UI-ADV-002 only confirmed and operational evidence is write-grade', () => {
    // The distinction that decides whether a real bid may move. An estimate
    // reading as write-grade in the console would invite an operator to approve
    // something the gate will refuse.
    const writeGrade = EVIDENCE_STATES.filter((state) => presentEvidence(state)?.writeGrade);
    expect(writeGrade).toEqual(['CANONICAL_CONFIRMED', 'OPERATIONAL']);
  });

  it('TC-UI-ADV-003 an unrecognised state renders as unrecognised, never as nothing', () => {
    render(<EvidenceChip state="SOMETHING_NEW" of="Evidence" />);

    // A blank where a state belongs reads as "fine".
    expect(screen.getByText(/unrecognised state/u)).toBeInTheDocument();
  });

  it('TC-UI-ADV-004 an absent measure and an undefined one do not read the same', () => {
    // "Nobody could compute it" and "the arithmetic has no answer" are
    // different facts, and a dash for both would hide which one applies.
    expect(presentMeasure('NOT_AVAILABLE', undefined, String)).toBe('not available');
    expect(presentMeasure('UNDEFINED', undefined, String)).toBe('undefined');
    expect(presentMeasure('AVAILABLE', 12, (value) => value.toFixed(2))).toBe('12.00');
  });
});

describe('the advertising queue', () => {
  it('TC-UI-ADV-005 renders the backend order without re-sorting it', async () => {
    const optimization = {
      ...PROTECTION_CASE,
      id: '44444444-4444-4444-8444-444444444444',
      lane: 'OPTIMIZATION',
      protectionTier: null,
      causeCode: 'RECOVERABLE_ADVERTISING_PROFIT',
      officialSpendAmount: '99999.0000',
      rankScore: '100500',
    };
    render(
      <AdvertisingQueue
        context={context(respondWith([PROTECTION_CASE, optimization]))}
        onSelect={() => undefined}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('table')).toBeInTheDocument();
    });
    const rows = screen.getAllByRole('row').slice(1);
    // The protection case is first even though the optimization case involves
    // twenty times the money. Re-sorting by amount here would present the
    // console's opinion as the product's.
    expect(rows[0]).toHaveAttribute('data-lane', 'PROTECTION');
    expect(rows[1]).toHaveAttribute('data-lane', 'OPTIMIZATION');
  });

  it('TC-UI-ADV-006 an absent measure is never shown as a zero', async () => {
    render(
      <AdvertisingQueue
        context={context(respondWith([PROTECTION_CASE]))}
        onSelect={() => undefined}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('table')).toBeInTheDocument();
    });
    // Profit per advertising rouble is UNDEFINED here because nothing was
    // spent per the fixture's state, and the cell says so.
    const cell = screen.getByText('undefined');
    expect(cell).toHaveAttribute('data-measure', 'profit-per-ad-rub');
  });

  it('TC-UI-ADV-007 forbidden says which access is missing', async () => {
    render(<AdvertisingQueue context={context(respondWith({}, 403))} onSelect={() => undefined} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/advertising access/u);
    });
  });

  it('TC-UI-ADV-008 an empty lane says the queue is empty, not that nothing ran', async () => {
    render(<AdvertisingQueue context={context(respondWith([]))} onSelect={() => undefined} />);

    await waitFor(() => {
      expect(screen.getByText(/statement about the queue/u)).toBeInTheDocument();
    });
  });

  it('TC-UI-ADV-009 opening a case reports the case identity', async () => {
    const opened: string[] = [];
    render(
      <AdvertisingQueue
        context={context(respondWith([PROTECTION_CASE]))}
        onSelect={(caseId) => opened.push(caseId)}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Open' })).toBeInTheDocument();
    });
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    expect(opened).toEqual([PROTECTION_CASE.id]);
  });
});

describe('the advertising case view', () => {
  it('TC-UI-ADV-010 shows every measure with its own state', async () => {
    render(
      <AdvertisingCaseView
        context={context(respondWith(PROTECTION_CASE))}
        caseId={PROTECTION_CASE.id}
        onBack={() => undefined}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent(/зимние сапоги/u);
    });
    const traffic = screen.getByText('not available');
    expect(traffic).toHaveAttribute('data-measure-state', 'NOT_AVAILABLE');
    expect(screen.getByText(/-1200.00 RUB/u)).toBeInTheDocument();
  });

  it('TC-UI-ADV-011 shows a rank factor whose value is absent with its reason', async () => {
    render(
      <AdvertisingCaseView
        context={context(respondWith(PROTECTION_CASE))}
        caseId={PROTECTION_CASE.id}
        onBack={() => undefined}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('CRITICAL_SALES')).toBeInTheDocument();
    });
    // The factor is listed even though it contributed nothing, so the shape of
    // the ranking is the same for every case and a missing term is visibly
    // missing rather than quietly absent.
    expect(screen.getByText('NOT_AVAILABLE')).toBeInTheDocument();
  });

  it('TC-UI-ADV-012 offers no approval control', async () => {
    render(
      <AdvertisingCaseView
        context={context(respondWith(PROTECTION_CASE))}
        caseId={PROTECTION_CASE.id}
        onBack={() => undefined}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 2 })).toBeInTheDocument();
    });
    // Approving a bid change is a step-up action on its own route. Offering it
    // from a read-only view would invite a decision made without the approval
    // path's checks.
    const buttons = screen.getAllByRole('button').map((button) => button.textContent);
    expect(buttons).toEqual(['Back to the advertising queue']);
  });
});

describe('the advertising parser', () => {
  it('TC-UI-ADV-013 refuses a body missing a state it must not invent', () => {
    const { evidenceState, ...withoutState } = PROTECTION_CASE;
    void evidenceState;

    expect(parseAdvertisingCase(withoutState)).toBeUndefined();
  });

  it('TC-UI-ADV-014 keeps an absent amount absent rather than defaulting it', () => {
    const parsed = parseAdvertisingCase(PROTECTION_CASE);

    expect(parsed?.profitPerAdRubValue).toBeUndefined();
    expect(parsed?.profitPerAdRubState).toBe('UNDEFINED');
    expect(parsed?.officialSpendAmount).toBe(4500);
  });
});

describe('advertising disclosure and real wire vocabulary', () => {
  it('keeps the exact native minor denomination visible when financial currency is masked', async () => {
    render(
      <AdvertisingCaseView
        context={context(
          respondWith({
            ...PROTECTION_CASE,
            platformCode: 'WILDBERRIES',
            profitCurrencyCode: null,
            semanticProfile: {
              bidUnitCode: 'CURRENCY_MINOR',
              nativeRules: { currencyCode: 'RUB' },
            },
          }),
        )}
        caseId={PROTECTION_CASE.id}
        onBack={() => undefined}
      />,
    );
    await waitFor(() => {
      expect(screen.getByText('30.0000 RUB (CURRENCY_MINOR)')).toHaveAttribute(
        'data-measure',
        'current-bid',
      );
    });
  });

  it('keeps permission masking distinct from a missing value and unknown state', () => {
    expect(presentMeasure('MASKED', undefined, String)).toBe('masked');
    expect(presentMeasure('AVAILABLE', undefined, String)).toBe('not available');
    expect(presentMeasure('FUTURE_STATE', undefined, String)).toContain('FUTURE_STATE');
    expect(presentMeasure('AVAILABLE', Number.NaN, String)).toBe('not available');
  });

  it('consumes factorCode and displayNote from the Java record and refuses malformed factors', () => {
    expect(parseAdvertisingCase(PROTECTION_CASE)?.rankFactors[1]?.absenceReason).toBe(
      'NOT_AVAILABLE',
    );
    expect(
      parseAdvertisingCase({ ...PROTECTION_CASE, rankFactors: [{ code: 'legacy-fiction' }] }),
    ).toBeUndefined();
  });

  it('rejects malformed rows rather than silently showing a partial finite candidate set', () => {
    expect(
      parseAdvertisingWorkflow({
        caseId: PROTECTION_CASE.id,
        candidates: [{ id: 'bad' }],
        allowedActions: [],
      }),
    ).toBeUndefined();
  });
});
