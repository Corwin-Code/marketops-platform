import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { App, describeSignInFailure } from '../App';
import { ConsoleShell } from '../ConsoleShell';
import { SignIn } from '../session/SignIn';
import { readIdentityClaims } from '../session/session';
import type { Session } from '../session/session';
import type { ConsoleEnvironment } from '../config';

const configured: ConsoleEnvironment = {
  VITE_MARKETOPS_API_BASE_URL: 'https://api.example.test',
  VITE_MARKETOPS_ENVIRONMENT: 'test',
  VITE_MARKETOPS_OIDC_AUTHORIZATION_ENDPOINT: 'https://id.example.test/authorize',
  VITE_MARKETOPS_OIDC_TOKEN_ENDPOINT: 'https://id.example.test/token',
  VITE_MARKETOPS_OIDC_CLIENT_ID: 'marketops-console',
  VITE_MARKETOPS_OIDC_AUDIENCE: 'marketops',
  VITE_MARKETOPS_STORE_ID: 'store-1',
};

const withoutIdentity: ConsoleEnvironment = {
  VITE_MARKETOPS_API_BASE_URL: 'https://api.example.test',
  VITE_MARKETOPS_ENVIRONMENT: 'test',
};

const session: Session = {
  accessToken: 'token',
  expiresAt: Date.now() + 900_000,
  displayName: 'Operator',
  authenticatedAt: Date.now(),
};

function silent(): typeof fetch {
  return vi.fn(() =>
    Promise.resolve(
      new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    ),
  ) as unknown as typeof fetch;
}

describe('TC-UI-040 nothing operational is shown before a session exists', () => {
  it('offers sign-in and the platform panel, and no operating data', () => {
    render(<App env={configured} fetchImpl={silent()} location={{ pathname: '/', search: '' }} />);

    expect(screen.getByRole('button', { name: /Continue to sign in/ })).toBeInTheDocument();
    expect(screen.getByLabelText('Platform state')).toBeInTheDocument();
    expect(screen.queryByLabelText('Priority queue')).toBeNull();
  });

  it('says the operating console is not configured rather than offering a dead button', () => {
    render(
      <App env={withoutIdentity} fetchImpl={silent()} location={{ pathname: '/', search: '' }} />,
    );

    expect(screen.getByLabelText('Operating console')).toHaveAttribute(
      'data-state',
      'not-configured',
    );
    expect(screen.queryByRole('button', { name: /Continue to sign in/ })).toBeNull();
  });

  it('refuses to start at all when a required setting is missing', () => {
    render(<App env={{}} location={{ pathname: '/', search: '' }} />);

    expect(screen.getByLabelText('Configuration error')).toBeInTheDocument();
    expect(screen.getByText('VITE_MARKETOPS_API_BASE_URL')).toBeInTheDocument();
  });

  it('shows the operating console once a session exists', () => {
    render(
      <App
        env={configured}
        fetchImpl={silent()}
        location={{ pathname: '/', search: '' }}
        initialSession={session}
      />,
    );

    expect(screen.getByLabelText('Priority queue')).toBeInTheDocument();
    expect(screen.getByText(/Signed in as Operator/)).toBeInTheDocument();
  });

  it('treats an expired session as no session', () => {
    render(
      <App
        env={configured}
        fetchImpl={silent()}
        location={{ pathname: '/', search: '' }}
        initialSession={{ ...session, expiresAt: Date.now() - 1 }}
      />,
    );

    expect(screen.getByRole('button', { name: /Continue to sign in/ })).toBeInTheDocument();
    expect(screen.queryByLabelText('Priority queue')).toBeNull();
  });

  it('reports a refused sign-in without leaving the visitor on a blank page', async () => {
    render(
      <App
        env={configured}
        fetchImpl={silent()}
        location={{ pathname: '/signed-in', search: '?error=access_denied' }}
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('sign-in-problem')).toHaveTextContent(/refused the sign-in/);
    });
  });

  it('names each sign-in failure in terms of what to do next', () => {
    expect(describeSignInFailure('denied')).toMatch(/refused/);
    expect(describeSignInFailure('state-mismatch')).toMatch(/this tab/);
    expect(describeSignInFailure('exchange-failed')).toMatch(/Try again/);
    expect(describeSignInFailure('anything-else')).toMatch(/cannot read/);
  });
});

describe('TC-UI-041 sign-in sends the operator to the provider and nowhere else', () => {
  it('navigates to the authorization endpoint it was configured with', async () => {
    const navigate = vi.fn();
    render(
      <SignIn
        settings={{
          authorizationEndpoint: 'https://id.example.test/authorize',
          tokenEndpoint: 'https://id.example.test/token',
          clientId: 'marketops-console',
          redirectUri: 'https://console.example.test/signed-in',
          audience: 'marketops',
        }}
        navigate={navigate}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /Continue to sign in/ }));

    await waitFor(() => {
      expect(navigate).toHaveBeenCalledOnce();
    });
    expect(String(navigate.mock.calls[0]?.[0])).toContain('https://id.example.test/authorize?');
  });
});

describe('TC-UI-042 the journey runs in the order the work happens', () => {
  it('moves from the work list to the subject a person picked', async () => {
    const send = vi.fn((url: unknown) => {
      const path = new URL(String(url)).pathname;
      const body = path.endsWith('/queue')
        ? [
            {
              subjectId: 'variant-1',
              storeId: 'store-1',
              priorityScore: '900.0000',
              criticalFindingCount: 1,
              warningFindingCount: 0,
              declinedRuleCount: 0,
              netSales: null,
              contributionProfit: null,
              currencyCode: null,
              blockingRuleCodes: [],
            },
          ]
        : { subjectId: 'variant-1', storeId: 'store-1', window: 'D30', metrics: {}, findings: [] };
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    }) as unknown as typeof fetch;

    render(
      <ConsoleShell
        apiBaseUrl="https://api.example.test"
        session={session}
        storeId="store-1"
        fetchImpl={send}
        onSignOut={vi.fn()}
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'variant-1' }));

    expect(await screen.findByLabelText('Subject diagnosis')).toHaveAttribute(
      'data-subject',
      'variant-1',
    );
  });

  it('opens straight on a proposal when one was handed in', () => {
    const send = silent();
    render(
      <ConsoleShell
        apiBaseUrl="https://api.example.test"
        session={session}
        storeId="store-1"
        fetchImpl={send}
        initialRecommendation={{
          id: 'rec-1',
          storeId: 'store-1',
          subjectId: 'variant-1',
          actionKind: 'PRICE_CHANGE',
          origin: 'DETERMINISTIC',
          state: 'READY_FOR_REVIEW',
          priorityScore: '1',
          proposedParameters: {},
          expectedEffect: {},
          riskLabel: 'LOW',
          validUntil: '2026-08-30T00:00:00Z',
          terminalReason: null,
          version: 1,
        }}
        onSignOut={vi.fn()}
      />,
    );

    expect(screen.getByLabelText('Recommendation review')).toBeInTheDocument();
  });

  it('lets an operator sign out', () => {
    const onSignOut = vi.fn();
    render(
      <ConsoleShell
        apiBaseUrl="https://api.example.test"
        session={session}
        storeId="store-1"
        fetchImpl={silent()}
        onSignOut={onSignOut}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(onSignOut).toHaveBeenCalledOnce();
  });
});

describe('TC-UI-043 identity claims are read for display and nothing else', () => {
  it('reads a name and an authentication time', () => {
    const payload = btoa(JSON.stringify({ name: 'Operator', auth_time: 1_700_000_000 }))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');

    expect(readIdentityClaims(`header.${payload}.signature`)).toEqual({
      displayName: 'Operator',
      authenticatedAt: 1_700_000_000_000,
    });
  });

  it('continues without a name when the token cannot be read', () => {
    expect(readIdentityClaims('not-a-token')).toEqual({
      displayName: undefined,
      authenticatedAt: undefined,
    });
    expect(readIdentityClaims('header.@@@.signature')).toEqual({
      displayName: undefined,
      authenticatedAt: undefined,
    });
  });

  it('ignores claims of the wrong shape rather than trusting them', () => {
    const payload = btoa(JSON.stringify({ name: 42, auth_time: 'yesterday' }))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');

    expect(readIdentityClaims(`header.${payload}.signature`)).toEqual({
      displayName: undefined,
      authenticatedAt: undefined,
    });
  });
});
