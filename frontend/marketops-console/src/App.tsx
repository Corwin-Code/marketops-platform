import { useEffect, useMemo, useRef, useState } from 'react';
import { ConsoleShell } from './ConsoleShell';
import { HealthShell } from './health/HealthShell';
import { resolveConfig, resolveOperatingConfig } from './config';
import type { ConsoleConfig, ConsoleConfigKey, ConsoleEnvironment } from './config';
import { completeSignIn } from './session/oidc';
import type { OidcSettings } from './session/oidc';
import { SignIn } from './session/SignIn';
import { isUsable } from './session/session';
import type { Session } from './session/session';

/** Path the identity provider returns the operator to. */
export const CALLBACK_PATH = '/signed-in';

/** Inputs that make configuration and requests observable in component tests. */
export interface AppProps {
  /** Public environment supplied by Vite. */
  readonly env?: ConsoleEnvironment;
  /** Request implementation passed to the shells. */
  readonly fetchImpl?: typeof fetch;
  /** Current location, replaced in tests. */
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
 * operating console.
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
  // Resolved once per environment so the settings below keep a stable
  // identity across renders.
  const operating = useMemo(() => resolveOperatingConfig(env), [env]);
  const here = location ?? {
    pathname: window.location.pathname,
    search: window.location.search,
  };

  const [session, setSession] = useState<Session | undefined>(initialSession);
  const [problem, setProblem] = useState<string | undefined>(undefined);

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
  // cannot see coming: the code in this URL is redeemed at most once.
  const redeemed = useRef<string | undefined>(undefined);

  useEffect(() => {
    if (
      !returningFromProvider ||
      settings === undefined ||
      session !== undefined ||
      redeemed.current === here.search
    ) {
      return;
    }
    redeemed.current = here.search;
    let active = true;
    void completeSignIn(settings, new URLSearchParams(here.search), fetchImpl).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setSession(outcome.session);
        setProblem(undefined);
      } else {
        setProblem(describeSignInFailure(outcome.failure.kind));
      }
    });
    return () => {
      active = false;
    };
  }, [returningFromProvider, settings, session, here.search, fetchImpl]);

  if (!resolution.ok) {
    return <ConfigurationError missingKeys={resolution.missingKeys} />;
  }

  if (isUsable(session, Date.now()) && operating !== undefined) {
    return (
      <ConsoleShell
        apiBaseUrl={resolution.value.apiBaseUrl}
        session={session}
        storeId={operating.storeId}
        {...(fetchImpl === undefined ? {} : { fetchImpl })}
        onSignOut={() => {
          setSession(undefined);
        }}
      />
    );
  }

  return (
    <SignedOut
      config={resolution.value}
      {...(fetchImpl === undefined ? {} : { fetchImpl })}
      {...(settings === undefined ? {} : { settings })}
      {...(problem === undefined ? {} : { problem })}
    />
  );
}

/**
 * What an unauthenticated visitor sees.
 *
 * The platform-state panel is deliberately available without a session: it says
 * only what the platform is and whether it is up, which is what an operator
 * needs before they can tell a sign-in problem from an outage.
 */
function SignedOut({
  config,
  fetchImpl,
  settings,
  problem,
}: {
  readonly config: ConsoleConfig;
  readonly fetchImpl?: typeof fetch;
  readonly settings?: OidcSettings;
  readonly problem?: string;
}): React.JSX.Element {
  const entry =
    settings === undefined ? (
      <section aria-label="Operating console" data-state="not-configured">
        <h2>Operating console</h2>
        <p>
          This deployment has no identity provider configured, so the operating console cannot sign
          anybody in here. The platform state below is still reported.
        </p>
      </section>
    ) : (
      <SignIn settings={settings} {...(problem === undefined ? {} : { problem })} />
    );

  return fetchImpl === undefined ? (
    <HealthShell config={config}>{entry}</HealthShell>
  ) : (
    <HealthShell config={config} fetchImpl={fetchImpl}>
      {entry}
    </HealthShell>
  );
}

/** Say why a sign-in did not produce a session, in words an operator can act on. */
export function describeSignInFailure(kind: string): string {
  switch (kind) {
    case 'denied':
      return 'The identity provider refused the sign-in. Nothing was changed.';
    case 'state-mismatch':
      return 'That sign-in did not start in this tab. Start again from this page.';
    case 'exchange-failed':
      return 'The identity provider did not complete the sign-in. Try again.';
    default:
      return 'The identity provider answered with something this console cannot read.';
  }
}

/** Names missing settings without rendering or logging any configured value. */
function ConfigurationError({
  missingKeys,
}: {
  readonly missingKeys: readonly ConsoleConfigKey[];
}): React.JSX.Element {
  return (
    <main aria-labelledby="console-heading">
      <h1 id="console-heading">MarketOps Russia</h1>
      <section aria-label="Configuration error" data-state="configuration-error">
        <h2>Configuration error</h2>
        <p role="alert">The console cannot start until these settings are provided:</p>
        <ul>
          {missingKeys.map((key) => (
            <li key={key}>{key}</li>
          ))}
        </ul>
        <p>Generate the local environment file, then restart the console.</p>
      </section>
    </main>
  );
}
