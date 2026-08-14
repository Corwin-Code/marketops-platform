import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchMetaStatus } from '../api/metaStatus';
import { buildInfo } from '../buildInfo';
import type { ConsoleConfig } from '../config';
import { INITIALISING, toHealthState } from './healthState';
import type { HealthState } from './healthState';

/**
 * The console's only screen.
 *
 * It reports what the platform says about itself and what an operator should do
 * about it. Nothing is rendered from a value the backend did not send, and no
 * value is rendered without the state that explains it, so the screen cannot
 * show a reassuring version number next to an unreachable backend.
 */

/** Properties the shell needs. */
export interface HealthShellProps {
  /** Where to send the request and what environment this is. */
  readonly config: ConsoleConfig;
  /** Request implementation, replaced in tests. */
  readonly fetchImpl?: typeof fetch;
}

/** Render the console. */
export function HealthShell({ config, fetchImpl }: HealthShellProps): React.JSX.Element {
  const [state, setState] = useState<HealthState>(INITIALISING);
  const [checking, setChecking] = useState(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const refresh = useCallback(async () => {
    setChecking(true);
    const outcome = await fetchMetaStatus(config.apiBaseUrl, fetchImpl);
    if (mounted.current) {
      setState(toHealthState(outcome));
      setChecking(false);
    }
  }, [config.apiBaseUrl, fetchImpl]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const build = buildInfo();
  const status = state.status;

  return (
    <main aria-labelledby="console-heading">
      <h1 id="console-heading">MarketOps Russia</h1>

      <section aria-label="Platform state" data-state={state.name}>
        <h2>Platform state</h2>
        <p role="status">{state.summary}</p>
        <p>{state.action}</p>
        <p>{state.usable ? 'The platform is usable.' : 'The platform is not usable yet.'}</p>
        <button type="button" onClick={() => void refresh()} disabled={checking}>
          {checking ? 'Checking…' : 'Check again'}
        </button>
      </section>

      {status !== undefined && (
        <section aria-label="Platform details">
          <h2>What the platform reports</h2>
          <dl>
            <dt>Application</dt>
            <dd>{status.application}</dd>
            <dt>Environment</dt>
            <dd>{status.environment}</dd>
            <dt>Backend version</dt>
            <dd>{status.buildVersion}</dd>
            <dt>Backend commit</dt>
            <dd>{status.gitCommit}</dd>
            <dt>Schema version</dt>
            <dd>{status.migration.currentVersion}</dd>
            <dt>Database</dt>
            <dd>{status.database.status}</dd>
            <dt>Server time</dt>
            <dd>{status.serverTimeUtc}</dd>
            <dt>Correlation identifier</dt>
            <dd>{status.correlationId}</dd>
          </dl>
        </section>
      )}

      <footer aria-label="Console build">
        <p>
          Console {build.version} ({build.commit}), pointed at {config.apiBaseUrl} for{' '}
          {config.environment}.
        </p>
      </footer>
    </main>
  );
}
