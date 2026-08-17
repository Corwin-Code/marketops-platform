import { HealthShell } from './health/HealthShell';
import { resolveConfig } from './config';
import type { ConsoleConfigKey, ConsoleEnvironment } from './config';

/** Inputs that make configuration and requests observable in component tests. */
export interface AppProps {
  /** Public environment supplied by Vite. */
  readonly env?: ConsoleEnvironment;
  /** Request implementation passed to the health shell. */
  readonly fetchImpl?: typeof fetch;
}

/**
 * Wires the resolved settings into the console's single screen.
 *
 * Settings are resolved once, here, so every other module receives values
 * rather than reading the environment for itself.
 */
export function App({ env = import.meta.env, fetchImpl }: AppProps = {}): React.JSX.Element {
  const resolution = resolveConfig(env);
  if (!resolution.ok) {
    return <ConfigurationError missingKeys={resolution.missingKeys} />;
  }
  return fetchImpl === undefined ? (
    <HealthShell config={resolution.value} />
  ) : (
    <HealthShell config={resolution.value} fetchImpl={fetchImpl} />
  );
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
