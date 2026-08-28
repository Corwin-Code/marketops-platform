import { beforeEach, describe, expect, it, vi } from 'vitest';
import { beginSignIn, completeSignIn, toSession } from '../session/oidc';
import type { OidcSettings } from '../session/oidc';
import { EXPIRY_MARGIN_MS, isUsable } from '../session/session';

const settings: OidcSettings = {
  authorizationEndpoint: 'https://id.example.test/authorize',
  tokenEndpoint: 'https://id.example.test/token',
  clientId: 'marketops-console',
  redirectUri: 'https://console.example.test/signed-in',
  audience: 'marketops',
};

/** A storage that behaves like session storage without needing a browser. */
function memoryStorage(): Storage {
  const entries = new Map<string, string>();
  return {
    get length() {
      return entries.size;
    },
    clear: () => {
      entries.clear();
    },
    getItem: (key: string) => entries.get(key) ?? null,
    key: (index: number) => [...entries.keys()][index] ?? null,
    removeItem: (key: string) => {
      entries.delete(key);
    },
    setItem: (key: string, value: string) => {
      entries.set(key, value);
    },
  };
}

describe('TC-UI-010 a code is useless without the proof this tab holds', () => {
  let storage: Storage;

  beforeEach(() => {
    storage = memoryStorage();
  });

  it('asks for a code with a challenge and a second factor', async () => {
    const url = new URL(await beginSignIn(settings, storage));

    expect(url.searchParams.get('response_type')).toBe('code');
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(url.searchParams.get('acr_values')).toBe('mfa');
  });

  it('keeps the verifier out of the request it sends', async () => {
    const url = new URL(await beginSignIn(settings, storage));

    expect(url.toString()).not.toContain(storage.getItem('marketops.oidc.verifier') ?? '');
  });

  it('generates a different state and verifier every time', async () => {
    const first = new URL(await beginSignIn(settings, storage));
    const firstVerifier = storage.getItem('marketops.oidc.verifier');
    const second = new URL(await beginSignIn(settings, storage));

    expect(second.searchParams.get('state')).not.toBe(first.searchParams.get('state'));
    expect(storage.getItem('marketops.oidc.verifier')).not.toBe(firstVerifier);
  });
});

describe('TC-UI-011 a redirect this tab did not start is refused', () => {
  it('refuses a response whose state does not match', async () => {
    const storage = memoryStorage();
    await beginSignIn(settings, storage);
    const outcome = await completeSignIn(
      settings,
      new URLSearchParams({ code: 'abc', state: 'somebody-elses-state' }),
      vi.fn(),
      storage,
    );

    expect(outcome).toEqual({ ok: false, failure: { kind: 'state-mismatch' } });
  });

  it('refuses a response when this tab never started a sign-in', async () => {
    const outcome = await completeSignIn(
      settings,
      new URLSearchParams({ code: 'abc', state: 'anything' }),
      vi.fn(),
      memoryStorage(),
    );

    expect(outcome).toEqual({ ok: false, failure: { kind: 'state-mismatch' } });
  });

  it('reports a refusal from the provider without redeeming anything', async () => {
    const storage = memoryStorage();
    await beginSignIn(settings, storage);
    const exchange = vi.fn();

    const outcome = await completeSignIn(
      settings,
      new URLSearchParams({ error: 'access_denied' }),
      exchange,
      storage,
    );

    expect(outcome).toEqual({ ok: false, failure: { kind: 'denied', detail: 'access_denied' } });
    expect(exchange).not.toHaveBeenCalled();
  });

  it('clears the transient values whether or not the exchange succeeded', async () => {
    const storage = memoryStorage();
    await beginSignIn(settings, storage);
    const exchange = vi
      .fn()
      .mockResolvedValue(new Response('{}', { status: 500 })) as unknown as typeof fetch;

    await completeSignIn(
      settings,
      new URLSearchParams({
        code: 'abc',
        state: storage.getItem('marketops.oidc.state') ?? '',
      }),
      exchange,
      storage,
    );

    expect(storage.getItem('marketops.oidc.verifier')).toBeNull();
    expect(storage.getItem('marketops.oidc.state')).toBeNull();
  });

  it('sends the verifier and never a client secret', async () => {
    const storage = memoryStorage();
    await beginSignIn(settings, storage);
    const verifier = storage.getItem('marketops.oidc.verifier') ?? '';
    let sentBody = '';
    const exchange = vi.fn((_url: unknown, init?: RequestInit) => {
      sentBody = typeof init?.body === 'string' ? init.body : '';
      return Promise.resolve(
        new Response(JSON.stringify({ access_token: 'token', expires_in: 900 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    }) as unknown as typeof fetch;

    const outcome = await completeSignIn(
      settings,
      new URLSearchParams({
        code: 'abc',
        state: storage.getItem('marketops.oidc.state') ?? '',
      }),
      exchange,
      storage,
    );

    expect(outcome.ok).toBe(true);
    expect(sentBody).toContain(`code_verifier=${encodeURIComponent(verifier)}`);
    expect(sentBody).not.toContain('client_secret');
  });
});

describe('TC-UI-012 a session is only as long as the provider said', () => {
  it('refuses a token response with no usable expiry', () => {
    expect(toSession({ access_token: 'token' }, 0)).toEqual({
      ok: false,
      failure: { kind: 'malformed', detail: 'no usable expiry' },
    });
    expect(toSession({ access_token: 'token', expires_in: 0 }, 0)).toEqual({
      ok: false,
      failure: { kind: 'malformed', detail: 'no usable expiry' },
    });
  });

  it('refuses a token response with no token', () => {
    expect(toSession({ expires_in: 900 }, 0)).toEqual({
      ok: false,
      failure: { kind: 'malformed', detail: 'no access token' },
    });
  });

  it('treats a token as expired before the provider actually stops accepting it', () => {
    const outcome = toSession({ access_token: 'token', expires_in: 900 }, 0);
    expect(outcome.ok).toBe(true);
    if (!outcome.ok) {
      return;
    }

    expect(isUsable(outcome.session, 0)).toBe(true);
    expect(isUsable(outcome.session, 900_000 - EXPIRY_MARGIN_MS)).toBe(false);
  });

  it('treats an absent session as unusable', () => {
    expect(isUsable(undefined, 0)).toBe(false);
  });
});
