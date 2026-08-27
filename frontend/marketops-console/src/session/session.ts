/**
 * Who is signed in, and the token that proves it.
 *
 * The access token is held in memory for the life of the tab and nowhere else.
 * A token in local storage survives the tab, is readable by anything that can
 * run script on this origin, and is the single most useful thing an attacker
 * could take from a console that can change prices on a real marketplace.
 * Losing the session on refresh is the price, and it is the right price.
 *
 * Expiry is treated as a fact about the token rather than as something to
 * discover from a rejected request. A console that only learns it is signed out
 * when an action fails will have shown an operator a screen of data they had no
 * right to see.
 */

/** A signed-in operator, as the console knows them. */
export interface Session {
  /** The token every request carries. */
  readonly accessToken: string;
  /** When the token stops being accepted. */
  readonly expiresAt: number;
  /** Operator-facing name, when the provider supplied one. */
  readonly displayName: string | undefined;
  /** When the person actually authenticated, for step-up decisions. */
  readonly authenticatedAt: number | undefined;
}

/** How close to expiry a token is treated as already expired. */
export const EXPIRY_MARGIN_MS = 30_000;

/** Whether a session may still be used at an instant. */
export function isUsable(session: Session | undefined, now: number): session is Session {
  return session !== undefined && session.expiresAt - EXPIRY_MARGIN_MS > now;
}

/**
 * Read the claims the console needs from an identity token.
 *
 * Only two claims are read, and neither is trusted for authorization: the name
 * is for display and the authentication time is for telling an operator why a
 * step-up prompt appeared. Every real decision is made by the backend against
 * the token's signature, which this code deliberately does not verify — a
 * browser that checked its own token would be checking a value it was handed.
 */
export function readIdentityClaims(idToken: string): {
  readonly displayName: string | undefined;
  readonly authenticatedAt: number | undefined;
} {
  const payload = idToken.split('.')[1];
  if (payload === undefined) {
    return { displayName: undefined, authenticatedAt: undefined };
  }
  try {
    const normalised = payload.replace(/-/g, '+').replace(/_/g, '/');
    const decoded: unknown = JSON.parse(atob(normalised));
    if (typeof decoded !== 'object' || decoded === null) {
      return { displayName: undefined, authenticatedAt: undefined };
    }
    const claims = decoded as Record<string, unknown>;
    const name = claims.name;
    const authTime = claims.auth_time;
    return {
      displayName: typeof name === 'string' ? name : undefined,
      authenticatedAt: typeof authTime === 'number' ? authTime * 1000 : undefined,
    };
  } catch {
    // A token this console cannot read is still a token the backend may
    // accept. The session continues without a name rather than failing.
    return { displayName: undefined, authenticatedAt: undefined };
  }
}
