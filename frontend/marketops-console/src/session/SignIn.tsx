import { beginSignIn } from './oidc';
import type { OidcSettings } from './oidc';

/** What the sign-in screen needs. */
export interface SignInProps {
  /** Where to send the operator and what to ask for. */
  readonly settings: OidcSettings;
  /** How to leave this page, replaced in tests. */
  readonly navigate?: (url: string) => void;
  /** Why the previous attempt did not produce a session, when there was one. */
  readonly problem?: string;
}

/**
 * The way in, rendered inside the page's single landmark.
 *
 * Nothing about the operating data is shown beside it — not a store name, not a
 * count. A console that showed even the shape of that data to somebody who has
 * not signed in is telling them something.
 */
export function SignIn({ settings, navigate, problem }: SignInProps): React.JSX.Element {
  const go =
    navigate ??
    ((url: string) => {
      window.location.assign(url);
    });
  return (
    <section aria-label="Sign in" data-state="signed-out">
      <h2>Sign in</h2>
      {problem !== undefined && (
        <p role="alert" data-testid="sign-in-problem">
          {problem}
        </p>
      )}
      <p>
        You will be sent to your organisation&rsquo;s identity provider. A second factor is
        required.
      </p>
      <button
        type="button"
        onClick={() => {
          void beginSignIn(settings).then(go);
        }}
      >
        Continue to sign in
      </button>
    </section>
  );
}
