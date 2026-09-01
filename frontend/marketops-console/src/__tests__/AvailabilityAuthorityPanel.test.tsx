import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AvailabilityAuthorityPanel } from '../availability/AvailabilityAuthorityPanel';
import type { ConsoleRequest } from '../api/console';

const VARIANT = '11111111-1111-4111-8111-111111111111';
const ATTESTATION = '22222222-2222-4222-8222-222222222222';
const POLICY = '33333333-3333-4333-8333-333333333333';

function inbound(versionNo: number, status = 'REQUESTED') {
  return {
    id: ATTESTATION,
    productVariantId: VARIANT,
    externalReference: 'PO-42',
    versionId: `44444444-4444-4444-8444-44444444444${String(versionNo)}`,
    versionNo,
    quantity: 20,
    expectedArrivalFrom: '2030-01-01T10:00:00Z',
    expectedArrivalTo: '2030-01-03T10:00:00Z',
    businessStatus: status,
    evidenceReference: 'ev://po/42',
    lastVerifiedAt: '2026-09-01T00:00:00Z',
  };
}

function harness(): {
  readonly context: ConsoleRequest;
  readonly calls: { url: string; init?: RequestInit }[];
} {
  const calls: { url: string; init?: RequestInit }[] = [];
  let version = 0;
  const fetchImpl = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
    calls.push(init === undefined ? { url } : { url, init });
    if (url.includes('/policies/')) {
      return Promise.resolve(
        new Response(
          JSON.stringify({
            id: POLICY,
            kind: 'LEAD_TIME',
            version: 1,
            scopeReference: 'ORGANIZATION|org',
            effectiveFrom: '2030-01-01T00:00:00Z',
            effectiveTo: null,
            status: url.endsWith('/retire') ? 'RETIRED' : 'ACTIVE',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      );
    }
    version += 1;
    const cancelled = url.endsWith('/cancel');
    return Promise.resolve(
      new Response(JSON.stringify(inbound(version, cancelled ? 'CANCELLED' : 'REQUESTED')), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  }) as unknown as typeof fetch;
  return {
    context: { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'token', fetchImpl },
    calls,
  };
}

function fillInbound(): void {
  fireEvent.change(screen.getByTestId('inbound-variant'), { target: { value: VARIANT } });
  fireEvent.change(screen.getByTestId('inbound-external-reference'), {
    target: { value: 'PO-42' },
  });
  fireEvent.change(screen.getByTestId('inbound-quantity'), { target: { value: '20' } });
  fireEvent.change(screen.getByTestId('inbound-arrival-from'), {
    target: { value: '2030-01-01T10:00' },
  });
  fireEvent.change(screen.getByTestId('inbound-arrival-to'), {
    target: { value: '2030-01-03T10:00' },
  });
  fireEvent.change(screen.getByTestId('inbound-evidence'), {
    target: { value: 'ev://po/42' },
  });
  fireEvent.change(screen.getByTestId('inbound-reason'), {
    target: { value: 'supplier evidence received' },
  });
}

describe('availability authority', () => {
  it('TC-UI-AUTH-001 creates an evidence-backed inbound version', async () => {
    const { context, calls } = harness();
    render(<AvailabilityAuthorityPanel context={context} />);
    fillInbound();
    fireEvent.click(screen.getByTestId('inbound-create'));

    await waitFor(() => {
      expect(screen.getByTestId('inbound-current')).toBeInTheDocument();
    });
    const create = calls.find((call) => call.url.endsWith('/availability/inbound'));
    expect(create?.init?.method).toBe('POST');
    const body = create?.init?.body;
    expect(typeof body).toBe('string');
    if (typeof body !== 'string') {
      throw new Error('expected a JSON request body');
    }
    expect(body).toContain('ev://po/42');
  });

  it('TC-UI-AUTH-002 exposes amend, reverify and cancel as versioned operations', async () => {
    const { context, calls } = harness();
    render(<AvailabilityAuthorityPanel context={context} />);
    fillInbound();
    fireEvent.click(screen.getByTestId('inbound-create'));
    await waitFor(() => {
      expect(screen.getByTestId('inbound-amend')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('inbound-amend'));
    await waitFor(() => {
      expect(calls.some((call) => call.url.endsWith('/amend'))).toBe(true);
    });
    fireEvent.click(screen.getByTestId('inbound-reverify'));
    await waitFor(() => {
      expect(calls.some((call) => call.url.endsWith('/reverify'))).toBe(true);
    });
    fireEvent.click(screen.getByTestId('inbound-cancel'));
    await waitFor(() => {
      expect(calls.some((call) => call.url.endsWith('/cancel'))).toBe(true);
    });
    expect(calls.filter((call) => call.init?.method === 'POST')).toHaveLength(4);
  });

  it('TC-UI-AUTH-003 publishes and retires effective-dated lead-time policy', async () => {
    const { context, calls } = harness();
    render(<AvailabilityAuthorityPanel context={context} />);
    fireEvent.change(screen.getByTestId('lead-effective-from'), {
      target: { value: '2030-01-01T00:00' },
    });
    fireEvent.change(screen.getByTestId('lead-evidence'), {
      target: { value: 'ev://policy/lead-1' },
    });
    fireEvent.change(screen.getByTestId('lead-reason'), {
      target: { value: 'approved planning policy' },
    });
    fireEvent.click(screen.getByTestId('lead-publish'));

    await waitFor(() => {
      expect(screen.getByTestId('policy-message')).toHaveTextContent('published');
    });
    expect(calls.some((call) => call.url.endsWith('/policies/lead-time'))).toBe(true);
    fireEvent.click(screen.getByTestId('policy-retire'));
    await waitFor(() => {
      expect(calls.some((call) => call.url.endsWith('/retire'))).toBe(true);
    });
  });
});
