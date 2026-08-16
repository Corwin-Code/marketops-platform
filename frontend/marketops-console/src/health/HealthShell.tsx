import { useEffect, useRef, useState } from 'react';
import { fetchMetaStatus } from '../api/metaStatus';
import { buildInfo } from '../buildInfo';
import type { ConsoleConfig } from '../config';
import { INITIALISING, toHealthState } from './healthState';
import type { HealthState } from './healthState';

/** Normal polling interval after a successful answer or an exhausted retry burst. */
export const HEALTH_REFRESH_INTERVAL_MS = 2000;

/** Three bounded retries; the fourth failure returns to the normal polling interval. */
export const HEALTH_RETRY_DELAYS_MS: readonly number[] = [250, 500, 1000];

/**
 * The console's only screen.
 *
 * <p>One self-scheduling timer starts only after the prior request settles, so
 * refreshes never overlap. A failure receives three bounded backoff retries and
 * then returns to the normal interval. Unmount clears the timer and aborts the
 * active request, including when React StrictMode mounts the effect twice.
 */

/** Properties the shell needs. */
export interface HealthShellProps {
  /** Where to send the request and what environment this is. */
  readonly config: ConsoleConfig;
  /** Request implementation, replaced in tests. */
  readonly fetchImpl?: typeof fetch;
  /** Normal interval override used only for deterministic component tests. */
  readonly refreshIntervalMs?: number;
  /** Backoff override used only for deterministic component tests. */
  readonly retryDelaysMs?: readonly number[];
}

/** Render the console. */
export function HealthShell({
  config,
  fetchImpl,
  refreshIntervalMs = HEALTH_REFRESH_INTERVAL_MS,
  retryDelaysMs = HEALTH_RETRY_DELAYS_MS,
}: HealthShellProps): React.JSX.Element {
  const [state, setState] = useState<HealthState>(INITIALISING);
  const [checking, setChecking] = useState(false);
  const manualRefresh = useRef<() => void>(() => undefined);

  useEffect(() => {
    const lifecycle = new AbortController();
    let inFlight = false;
    let failedAttempts = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const isInactive = (): boolean => lifecycle.signal.aborted;

    const schedule = (delayMs: number): void => {
      if (isInactive()) {
        return;
      }
      timer = setTimeout(() => {
        void refresh();
      }, delayMs);
    };

    const refresh = async (): Promise<void> => {
      if (isInactive() || inFlight) {
        return;
      }
      inFlight = true;
      setChecking(true);
      const outcome = await fetchMetaStatus(
        config.apiBaseUrl,
        fetchImpl,
        undefined,
        lifecycle.signal,
      );
      inFlight = false;

      if (isInactive()) {
        return;
      }
      setState(toHealthState(outcome));
      setChecking(false);

      if (outcome.ok) {
        failedAttempts = 0;
        schedule(refreshIntervalMs);
      } else if (failedAttempts < retryDelaysMs.length) {
        schedule(retryDelaysMs[failedAttempts] ?? refreshIntervalMs);
        failedAttempts += 1;
      } else {
        failedAttempts = 0;
        schedule(refreshIntervalMs);
      }
    };

    manualRefresh.current = () => {
      if (isInactive() || inFlight) {
        return;
      }
      if (timer !== undefined) {
        clearTimeout(timer);
        timer = undefined;
      }
      void refresh();
    };

    void refresh();

    return () => {
      lifecycle.abort();
      manualRefresh.current = () => undefined;
      if (timer !== undefined) {
        clearTimeout(timer);
      }
    };
  }, [config.apiBaseUrl, fetchImpl, refreshIntervalMs, retryDelaysMs]);

  const build = buildInfo();
  const status = state.status;

  return (
    <>
      <main aria-labelledby="console-heading">
        <h1 id="console-heading">MarketOps Russia</h1>

        <section aria-label="Platform state" data-state={state.name}>
          <h2>Platform state</h2>
          <p role="status">{state.summary}</p>
          <p>{state.action}</p>
          <p>{state.usable ? 'The platform is usable.' : 'The platform is not usable yet.'}</p>
          <button
            type="button"
            onClick={() => {
              manualRefresh.current();
            }}
            disabled={checking}
          >
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
      </main>

      <footer aria-label="Console build">
        <p>
          Console {build.version} ({build.commit}), pointed at {config.apiBaseUrl} for{' '}
          {config.environment}.
        </p>
      </footer>
    </>
  );
}
