import { useEffect, useMemo, useRef, useState } from 'react';
import { BrowserRouter, MemoryRouter, useLocation, useNavigate } from 'react-router';
import { ConsoleShell } from './ConsoleShell';
import { resolveConfig, resolveOperatingConfig } from './config';
import type { ConsoleConfig, ConsoleEnvironment } from './config';
import { signInFailures } from './i18n/zh/shell';
import { ConfigurationError } from './layout/ConfigurationError';
import { SignedOutScreen } from './layout/SignedOutScreen';
import { completeSignIn } from './session/oidc';
import type { OidcSettings, SignInOutcome } from './session/oidc';
import { isUsable } from './session/session';
import type { Session } from './session/session';

/** Path the identity provider returns the operator to. */
export const CALLBACK_PATH = '/signed-in';

/** How often the session's expiry is re-checked while signed in. */
export const SESSION_CHECK_INTERVAL_MS = 30_000;

/** Inputs that make configuration and requests observable in component tests. */
export interface AppProps {
  /** Public environment supplied by Vite. */
  readonly env?: ConsoleEnvironment;
  /** Request implementation passed to the shells. */
  readonly fetchImpl?: typeof fetch;
  /** Current location, replaced in tests (an in-memory router is used then). */
  readonly location?: { readonly pathname: string; readonly search: string };
  /** A session already in hand, used only by tests. */
  readonly initialSession?: Session;
}

/**
 * Wires the resolved settings into whichever surface the visitor should see.
 *
 * Three states, in the order a visitor meets them. Missing required settings
 * means the console cannot start at all. No session means the platform-state
 * panel and a way to sign in — never any operating data. A session means the
 * operating console, with every page at its own address.
 *
 * Nothing about a store, a listing or a price is rendered before a session
 * exists. A console that showed even the shape of that data to somebody who has
 * not signed in would be telling them something.
 */
export function App({
  env = import.meta.env,
  fetchImpl,
  location,
  initialSession,
}: AppProps = {}): React.JSX.Element {
  const resolution = resolveConfig(env);
  if (!resolution.ok) {
    return <ConfigurationError missingKeys={resolution.missingKeys} />;
  }
  const root = (
    <ConsoleRoot
      env={env}
      config={resolution.value}
      fetchImpl={fetchImpl}
      initialSession={initialSession}
    />
  );
  return location === undefined ? (
    <BrowserRouter>{root}</BrowserRouter>
  ) : (
    <MemoryRouter initialEntries={[`${location.pathname}${location.search}`]}>{root}</MemoryRouter>
  );
}

/** The session and the sign-in round trip, inside the router. */
function ConsoleRoot({
  env,
  config,
  fetchImpl,
  initialSession,
}: {
  readonly env: ConsoleEnvironment;
  readonly config: ConsoleConfig;
  readonly fetchImpl: typeof fetch | undefined;
  readonly initialSession: Session | undefined;
}): React.JSX.Element {
  // Resolved once per environment so the settings below keep a stable
  // identity across renders.
  const operating = useMemo(() => resolveOperatingConfig(env), [env]);
  const here = useLocation();
  const navigate = useNavigate();

  const [session, setSession] = useState<Session | undefined>(initialSession);
  const [problem, setProblem] = useState<string | undefined>(undefined);
  const [now, setNow] = useState(() => Date.now());

  const redirectUri =
    typeof window === 'undefined' ? CALLBACK_PATH : `${window.location.origin}${CALLBACK_PATH}`;

  // Memoised because an authorization code may be redeemed exactly once. An
  // object rebuilt on every render would re-run the exchange effect, and the
  // second attempt would find the verifier already spent and report a
  // mismatch over a session that had just succeeded.
  const settings: OidcSettings | undefined = useMemo(
    () =>
      operating === undefined
        ? undefined
        : {
            authorizationEndpoint: operating.authorizationEndpoint,
            tokenEndpoint: operating.tokenEndpoint,
            clientId: operating.clientId,
            audience: operating.audience,
            redirectUri,
          },
    [operating, redirectUri],
  );

  const returningFromProvider = here.pathname === CALLBACK_PATH;

  // A second guard for the same reason, against a re-render this component
  // cannot see coming: the code in this URL is redeemed at most once. The
  // redemption is kept, not just marked, because React may run this effect,
  // clean it up and run it again for the same URL (StrictMode does so in
  // development); the later run must still receive the one exchange's result.
  // Its result is applied once, so signing out on this URL does not reuse it.
  const redemption = useRef<
    { search: string; outcome: Promise<SignInOutcome>; applied: boolean } | undefined
  >(undefined);

  useEffect(() => {
    if (!returningFromProvider || settings === undefined || session !== undefined) {
      return;
    }
    if (redemption.current?.search !== here.search) {
      redemption.current = {
        search: here.search,
        outcome: completeSignIn(settings, new URLSearchParams(here.search), fetchImpl),
        applied: false,
      };
    }
    const current = redemption.current;
    if (current.applied) {
      return;
    }
    let active = true;
    void current.outcome.then((outcome) => {
      if (!active || current.applied) {
        return;
      }
      current.applied = true;
      if (outcome.ok) {
        setSession(outcome.session);
        setProblem(undefined);
        setNow(Date.now());
        // The spent code leaves the address bar and the history entry.
        void navigate('/', { replace: true });
      } else {
        setProblem(describeSignInFailure(outcome.failure.kind));
      }
    });
    return () => {
      active = false;
    };
  }, [returningFromProvider, settings, session, here.search, fetchImpl, navigate]);

  // Expiry is a fact about the token: the clock is re-read on an interval so
  // an idle console signs itself out instead of waiting for a refused request.
  useEffect(() => {
    if (session === undefined) {
      return;
    }
    setNow(Date.now());
    const timer = setInterval(() => {
      setNow(Date.now());
    }, SESSION_CHECK_INTERVAL_MS);
    return () => {
      clearInterval(timer);
    };
  }, [session]);

  useEffect(() => {
    if (session !== undefined && !isUsable(session, now)) {
      setSession(undefined);
    }
  }, [session, now]);

  if (isUsable(session, now) && operating !== undefined) {
    return (
      <ConsoleShell
        config={config}
        session={session}
        storeId={operating.storeId}
        fetchImpl={fetchImpl}
        now={now}
        onSignOut={() => {
          setSession(undefined);
          setProblem(undefined);
          void navigate('/', { replace: true });
        }}
      />
    );
  }

  return (
    <SignedOutScreen
      config={config}
      fetchImpl={fetchImpl}
      settings={settings}
      problem={problem}
      completing={
        returningFromProvider &&
        settings !== undefined &&
        problem === undefined &&
        session === undefined
      }
    />
  );
}

/** Say why a sign-in did not produce a session, in words an operator can act on. */
export function describeSignInFailure(kind: string): string {
  switch (kind) {
    case 'denied':
      return signInFailures.denied;
    case 'state-mismatch':
      return signInFailures.stateMismatch;
    case 'exchange-failed':
      return signInFailures.exchangeFailed;
    default:
      return signInFailures.unreadable;
  }
}
