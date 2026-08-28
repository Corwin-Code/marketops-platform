/**
 * Signing in against the organisation's identity provider.
 *
 * The console never sees a password and never holds a client secret. It sends
 * the operator to the provider, receives a code back, and redeems that code
 * with a proof only this tab holds. Nothing about the exchange is reusable by
 * anything that captured the redirect.
 *
 * The transient values — the verifier, the state, the nonce — live in session
 * storage because they must survive a full-page navigation to the provider and
 * back, and they are useless afterwards. They are removed the moment the code
 * is redeemed, whether or not the redemption succeeded.
 */

import { createPkcePair, randomValue } from './pkce';
import { readIdentityClaims } from './session';
import type { Session } from './session';

/** Where the transient exchange values are kept between navigations. */
const VERIFIER_KEY = 'marketops.oidc.verifier';
const STATE_KEY = 'marketops.oidc.state';
const NONCE_KEY = 'marketops.oidc.nonce';

/** Everything the console needs in order to sign somebody in. */
export interface OidcSettings {
  /** Where the provider's authorization endpoint lives. */
  readonly authorizationEndpoint: string;
  /** Where codes are redeemed. */
  readonly tokenEndpoint: string;
  /** This console's registered public client identifier. */
  readonly clientId: string;
  /** Where the provider sends the operator back to. */
  readonly redirectUri: string;
  /** What the token must be usable against. */
  readonly audience: string;
}

/** Why a sign-in did not produce a session. */
export type SignInFailure =
  | { readonly kind: 'denied'; readonly detail: string }
  | { readonly kind: 'state-mismatch' }
  | { readonly kind: 'exchange-failed'; readonly status: number }
  | { readonly kind: 'malformed'; readonly detail: string };

/** Outcome of redeeming a code. */
export type SignInOutcome =
  | { readonly ok: true; readonly session: Session }
  | { readonly ok: false; readonly failure: SignInFailure };

/** The scopes the console asks for, and no others. */
const SCOPES = 'openid profile marketops.console';

/**
 * Send the operator to the provider.
 *
 * Returns the URL rather than navigating, so a test can assert what was asked
 * for without a real redirect.
 */
export async function beginSignIn(
  settings: OidcSettings,
  storage: Storage = sessionStorage,
): Promise<string> {
  const pair = await createPkcePair();
  const state = randomValue();
  const nonce = randomValue();
  storage.setItem(VERIFIER_KEY, pair.verifier);
  storage.setItem(STATE_KEY, state);
  storage.setItem(NONCE_KEY, nonce);

  const query = new URLSearchParams({
    response_type: 'code',
    client_id: settings.clientId,
    redirect_uri: settings.redirectUri,
    scope: SCOPES,
    state,
    nonce,
    audience: settings.audience,
    code_challenge: pair.challenge,
    code_challenge_method: 'S256',
    // The provider decides how, but the console states that a second factor is
    // required rather than hoping the provider's default policy demands one.
    acr_values: 'mfa',
  });
  return `${settings.authorizationEndpoint}?${query.toString()}`;
}

/**
 * Redeem the code the provider sent back.
 *
 * The state is compared before anything else happens. A response whose state
 * does not match the value this tab generated is not an answer to this tab's
 * question, and redeeming it would complete a sign-in somebody else started.
 */
export async function completeSignIn(
  settings: OidcSettings,
  search: URLSearchParams,
  fetchImpl: typeof fetch = fetch,
  storage: Storage = sessionStorage,
  now: number = Date.now(),
): Promise<SignInOutcome> {
  const expectedState = storage.getItem(STATE_KEY);
  const verifier = storage.getItem(VERIFIER_KEY);
  const clear = (): void => {
    storage.removeItem(STATE_KEY);
    storage.removeItem(VERIFIER_KEY);
    storage.removeItem(NONCE_KEY);
  };

  const error = search.get('error');
  if (error !== null) {
    clear();
    return { ok: false, failure: { kind: 'denied', detail: error } };
  }

  const state = search.get('state');
  const code = search.get('code');
  if (expectedState === null || state !== expectedState || verifier === null) {
    clear();
    return { ok: false, failure: { kind: 'state-mismatch' } };
  }
  if (code === null) {
    clear();
    return { ok: false, failure: { kind: 'malformed', detail: 'no code was returned' } };
  }

  let response: Response;
  try {
    response = await fetchImpl(settings.tokenEndpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      credentials: 'omit',
      cache: 'no-store',
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        code,
        client_id: settings.clientId,
        redirect_uri: settings.redirectUri,
        code_verifier: verifier,
      }).toString(),
    });
  } catch {
    clear();
    return { ok: false, failure: { kind: 'exchange-failed', status: 0 } };
  } finally {
    clear();
  }

  if (!response.ok) {
    return { ok: false, failure: { kind: 'exchange-failed', status: response.status } };
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    return { ok: false, failure: { kind: 'malformed', detail: 'the body was not valid JSON' } };
  }
  return toSession(body, now);
}

/**
 * Turn a token response into a session, or say why it is not one.
 *
 * A response missing its expiry is refused rather than defaulted. Guessing a
 * lifetime would let the console keep using a token the provider has already
 * stopped accepting, and present a signed-out operator with live data.
 */
export function toSession(body: unknown, now: number): SignInOutcome {
  if (typeof body !== 'object' || body === null) {
    return { ok: false, failure: { kind: 'malformed', detail: 'the body is not an object' } };
  }
  const payload = body as Record<string, unknown>;
  const accessToken = payload.access_token;
  const expiresIn = payload.expires_in;
  const idToken = payload.id_token;

  if (typeof accessToken !== 'string' || accessToken === '') {
    return { ok: false, failure: { kind: 'malformed', detail: 'no access token' } };
  }
  if (typeof expiresIn !== 'number' || expiresIn <= 0) {
    return { ok: false, failure: { kind: 'malformed', detail: 'no usable expiry' } };
  }

  const claims =
    typeof idToken === 'string'
      ? readIdentityClaims(idToken)
      : { displayName: undefined, authenticatedAt: undefined };

  return {
    ok: true,
    session: {
      accessToken,
      expiresAt: now + expiresIn * 1000,
      displayName: claims.displayName,
      authenticatedAt: claims.authenticatedAt,
    },
  };
}
