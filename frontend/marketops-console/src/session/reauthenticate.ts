import { createContext, useContext } from 'react';
import { beginSignIn } from './oidc';
import type { OidcSettings } from './oidc';
import { currentInAppPath, rememberReturnPath } from './returnPath';

/**
 * Start the normal sign-in round trip from wherever the operator is.
 *
 * The current page and its query are kept first so the callback can bring the
 * operator back to the same place. Resolves once the browser has been sent on
 * its way; rejects when the request could not be prepared.
 */
export async function startSignIn(
  settings: OidcSettings,
  go: (url: string) => void = (url) => {
    window.location.assign(url);
  },
  returnPath: string = currentInAppPath(),
): Promise<void> {
  rememberReturnPath(returnPath);
  const url = await beginSignIn(settings);
  go(url);
}

/**
 * How a signed-in page asks for a fresh sign-in, for example from the
 * session-expiry warning. Undefined when this deployment has no identity
 * provider, in which case no such action is offered.
 */
export const ReauthenticateContext = createContext<(() => Promise<void>) | undefined>(undefined);

/** The way to start a fresh sign-in, when one is available. */
export function useReauthenticate(): (() => Promise<void>) | undefined {
  return useContext(ReauthenticateContext);
}
