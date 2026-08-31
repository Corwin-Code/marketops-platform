import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AvailabilityCases } from '../availability/AvailabilityCases';
import {
  ACTION_KINDS,
  actionKindLabel,
  dueTone,
  exceptionStateLabel,
  presentCaseState,
} from '../availability/casePresentation';
import type { ConsoleRequest } from '../api/console';

const NOW = new Date('2026-08-31T12:00:00Z');

const OPEN_CASE = {
  id: 'aaaaaaaa-1111-4111-8111-111111111111',
  organizationId: 'bbbbbbbb-1111-4111-8111-111111111111',
  cardId: 'cccccccc-1111-4111-8111-111111111111',
  childId: 'dddddddd-1111-4111-8111-111111111111',
  causeCode: 'COMPANY_SUPPLY_SHORT',
  causeKey: 'COMPANY:variant:COMPANY_SUPPLY_SHORT',
  severity: 'CRITICAL',
  state: 'OPEN',
  accountableRoleCode: 'PRODUCT_PROCUREMENT',
  assigneeUserId: null,
  actionDueAt: '2026-08-31T11:00:00Z',
  outcomeDueAt: '2026-09-02T11:00:00Z',
  reopenCount: 3,
  escalationLevel: 1,
  firstActivatedAt: '2026-08-30T12:00:00Z',
  lastEvidenceAt: '2026-08-31T10:00:00Z',
};

function context(fetchImpl: typeof fetch): ConsoleRequest {
  return { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'token', fetchImpl };
}

/** A fetch that answers each path with its own body, and records what was sent. */
function router(routes: Readonly<Record<string, unknown>>): {
  readonly fetchImpl: typeof fetch;
  readonly calls: { url: string; init?: RequestInit }[];
} {
  const calls: { url: string; init?: RequestInit }[] = [];
  const fetchImpl = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
    calls.push(init === undefined ? { url } : { url, init });
    const match = Object.keys(routes).find((path) => url.includes(path));
    return Promise.resolve(
      new Response(JSON.stringify(match === undefined ? [] : routes[match]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  }) as unknown as typeof fetch;
  return { fetchImpl, calls };
}

describe('availability cases', () => {
  it('TC-UI-CASE-001 shows the two clocks separately rather than one merged badge', async () => {
    const { fetchImpl } = router({ '/cases?': [OPEN_CASE] });
    render(<AvailabilityCases context={context(fetchImpl)} now={NOW} />);

    await waitFor(() => {
      expect(screen.getByTestId('availability-case')).toBeInTheDocument();
    });
    expect(screen.getByTestId('case-action-due')).toHaveAttribute('data-due', 'overdue');
    expect(screen.getByTestId('case-outcome-due')).toHaveAttribute('data-due', 'ok');
  });

  it('TC-UI-CASE-002 shows how many times the same cause has returned', async () => {
    const { fetchImpl } = router({ '/cases?': [OPEN_CASE] });
    render(<AvailabilityCases context={context(fetchImpl)} now={NOW} />);

    await waitFor(() => {
      expect(screen.getByTestId('case-reopens')).toHaveTextContent('3');
    });
    expect(screen.getByTestId('case-escalation')).toHaveTextContent('1');
  });

  it('TC-UI-CASE-003 offers only structured actions and no acknowledgement', async () => {
    const { fetchImpl } = router({ '/cases?': [OPEN_CASE] });
    render(<AvailabilityCases context={context(fetchImpl)} now={NOW} />);

    await waitFor(() => {
      expect(screen.getByTestId('action-kind')).toBeInTheDocument();
    });
    const options = Array.from(screen.getByTestId('action-kind').querySelectorAll('option')).map(
      (option) => option.getAttribute('value'),
    );
    expect(options).toEqual([...ACTION_KINDS]);
    expect(screen.queryByText(/acknowledge/i)).toBeNull();
  });

  it('TC-UI-CASE-004 refuses an action with no artefact behind it before sending', async () => {
    const { fetchImpl, calls } = router({ '/cases?': [OPEN_CASE] });
    render(<AvailabilityCases context={context(fetchImpl)} now={NOW} />);

    await waitFor(() => {
      expect(screen.getByTestId('action-submit')).toBeInTheDocument();
    });
    fireEvent.change(screen.getByTestId('action-reason'), { target: { value: 'looked at it' } });
    fireEvent.click(screen.getByTestId('action-submit'));

    expect(screen.getByTestId('action-problem')).toHaveTextContent('artefact');
    expect(calls.filter((call) => call.init?.method === 'POST')).toHaveLength(0);
  });

  it('TC-UI-CASE-005 sends a structured action with its evidence reference', async () => {
    const { fetchImpl, calls } = router({
      '/cases?': [OPEN_CASE],
      '/action': { ...OPEN_CASE, state: 'VERIFYING' },
    });
    render(<AvailabilityCases context={context(fetchImpl)} now={NOW} />);

    await waitFor(() => {
      expect(screen.getByTestId('action-submit')).toBeInTheDocument();
    });
    fireEvent.change(screen.getByTestId('action-evidence'), {
      target: { value: 'ev://purchase-order/1' },
    });
    fireEvent.change(screen.getByTestId('action-reason'), {
      target: { value: 'inbound bound to the shortfall' },
    });
    fireEvent.click(screen.getByTestId('action-submit'));

    await waitFor(() => {
      expect(calls.some((call) => call.url.includes('/action'))).toBe(true);
    });
    const sent = calls.find((call) => call.url.includes('/action'));
    expect(sent?.init?.method).toBe('POST');
    const body = sent?.init?.body;
    expect(typeof body === 'string' ? body : '').toContain('ev://purchase-order/1');
  });

  it('TC-UI-CASE-006 verification is automatic and has no public mutation control', async () => {
    const { fetchImpl } = router({ '/cases?': [OPEN_CASE] });
    render(<AvailabilityCases context={context(fetchImpl)} now={NOW} />);

    await waitFor(() => {
      expect(screen.getByTestId('case-action-form')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('case-verification-form')).toBeNull();
    expect(screen.queryByText(/^close$/i)).toBeNull();
  });

  it('TC-UI-CASE-007 an accepted risk is shown beside the calculated risk, never instead', async () => {
    const accepted = { ...OPEN_CASE, state: 'ACCEPTED_RISK' };
    const { fetchImpl } = router({
      '/cases?': [accepted],
      '/exceptions': [
        {
          id: 'eeeeeeee-1111-4111-8111-111111111111',
          causeCode: 'COMPANY_SUPPLY_SHORT',
          reasonCode: 'SEASONAL_PAUSE',
          state: 'ACTIVE',
          requiredAuthority: 'RISK_AUTHORITY',
          effectiveFrom: '2026-08-31T00:00:00Z',
          expiresAt: '2026-09-07T00:00:00Z',
          reviewAt: '2026-09-03T00:00:00Z',
          invalidationReason: null,
        },
      ],
      '/journal': [],
    });
    render(<AvailabilityCases context={context(fetchImpl)} now={NOW} />);

    await waitFor(() => {
      expect(screen.getByTestId('case-load-journal')).toBeInTheDocument();
    });
    expect(screen.getByTestId('case-severity')).toHaveTextContent('Critical');
    fireEvent.click(screen.getByTestId('case-load-journal'));

    await waitFor(() => {
      expect(screen.getByTestId('case-exceptions')).toBeInTheDocument();
    });
    expect(screen.getByTestId('case-exceptions')).toHaveTextContent('Accepted');
    expect(screen.getByTestId('case-exceptions')).toHaveTextContent('2026-09-07T00:00:00Z');
    expect(screen.getByTestId('case-state')).toHaveTextContent('Accepted risk');
  });

  it('TC-UI-CASE-013 a session that has ended is reported once, not once per panel', async () => {
    const fetchImpl = vi
      .fn()
      .mockImplementation(() =>
        Promise.resolve(new Response('{}', { status: 401 })),
      ) as unknown as typeof fetch;
    render(<AvailabilityCases context={context(fetchImpl)} now={NOW} />);

    await waitFor(() => {
      expect(screen.getByLabelText('Availability cases')).toHaveAttribute(
        'data-state',
        'signed-out',
      );
    });
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('TC-UI-CASE-014 a refusal about this panel is still reported on this panel', async () => {
    const fetchImpl = vi
      .fn()
      .mockImplementation(() =>
        Promise.resolve(new Response('{}', { status: 403 })),
      ) as unknown as typeof fetch;
    render(<AvailabilityCases context={context(fetchImpl)} now={NOW} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeTruthy();
    });
  });

  it('TC-UI-CASE-008 an empty case list is not presented as an empty queue', async () => {
    const { fetchImpl } = router({ '/cases?': [] });
    render(<AvailabilityCases context={context(fetchImpl)} now={NOW} />);

    await waitFor(() => {
      expect(screen.getByLabelText('Availability cases')).toHaveAttribute('data-state', 'empty');
    });
    expect(screen.getByText(/not an empty queue/i)).toBeInTheDocument();
  });
});

describe('case presentation', () => {
  it('TC-UI-CASE-009 keeps recording, verifying, accepting and succeeding distinct', () => {
    const tones = ['ACTION_RECORDED', 'VERIFYING', 'ACCEPTED_RISK', 'VERIFIED_SUCCESS'].map(
      (state) => presentCaseState(state).tone,
    );

    expect(new Set(tones).size).toBe(4);
    expect(presentCaseState('VERIFIED_SUCCESS').explanation).toContain('only success state');
  });

  it('TC-UI-CASE-010 an unrecognised state is never presented as a success', () => {
    expect(presentCaseState('SOMETHING_NEW').tone).not.toBe('succeeded');
  });

  it('TC-UI-CASE-011 a deadline that has passed is distinguishable from one that is close', () => {
    expect(dueTone('2026-08-31T11:00:00Z', NOW)).toBe('overdue');
    expect(dueTone('2026-08-31T12:30:00Z', NOW)).toBe('soon');
    expect(dueTone('2026-09-01T12:00:00Z', NOW)).toBe('ok');
    expect(dueTone(null, NOW)).toBe('none');
    expect(dueTone('not a date', NOW)).toBe('none');
  });

  it('TC-UI-CASE-012 every structured action and acceptance state reads as a sentence', () => {
    for (const kind of ACTION_KINDS) {
      expect(actionKindLabel(kind)).not.toBe(kind);
    }
    expect(exceptionStateLabel('AUTHORITY_BLOCKED')).toBe('Authority blocked');
    expect(exceptionStateLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});
