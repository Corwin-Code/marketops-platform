import { ReloadOutlined } from '@ant-design/icons';
import { Badge, Button, Collapse, Descriptions, Flex, Result, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { fetchMetaStatus } from '../api/metaStatus';
import type { MetaStatus } from '../api/metaStatus';
import { buildInfo } from '../buildInfo';
import type { ConsoleConfig } from '../config';
import { environmentLabel, health as text } from '../i18n/zh/shell';
import { DateTime, SectionCard } from '../ui';
import { DATABASE_UP, HEALTH_TONES, INITIALISING, toHealthState } from './healthState';
import type { HealthState } from './healthState';

/** Normal polling interval after a successful answer or an exhausted retry burst. */
export const HEALTH_REFRESH_INTERVAL_MS = 2000;

/** Three bounded retries; the fourth failure returns to the normal polling interval. */
export const HEALTH_RETRY_DELAYS_MS: readonly number[] = [250, 500, 1000];

/**
 * The platform-state panel: compact on the sign-in card, full on the system
 * status page.
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
  /**
   * Compact for the sign-in card (status line, details folded away), full for
   * the system status page (result and every reported fact).
   */
  readonly variant?: 'compact' | 'full';
  /** Content rendered above the status, inside the same region. */
  readonly children?: React.ReactNode;
}

/** Render the console. */
export function HealthShell({
  config,
  fetchImpl,
  refreshIntervalMs = HEALTH_REFRESH_INTERVAL_MS,
  retryDelaysMs = HEALTH_RETRY_DELAYS_MS,
  variant = 'compact',
  children,
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
  const tone = HEALTH_TONES[state.name];
  const refreshButton = (
    <Button
      size={variant === 'compact' ? 'small' : 'middle'}
      icon={<ReloadOutlined />}
      loading={checking}
      onClick={() => {
        manualRefresh.current();
      }}
    >
      {checking ? text.checking : text.checkAgain}
    </Button>
  );
  const footer = (
    <footer aria-label={text.buildRegion}>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {text.build(build.version, build.commit.slice(0, 12))} ·{' '}
        {text.pointedAt(config.apiBaseUrl, environmentLabel(config.environment))}
      </Typography.Text>
    </footer>
  );

  if (variant === 'full') {
    return (
      <>
        {children}
        <SectionCard title={text.title} extra={refreshButton} state={state.name}>
          <section aria-label={text.region} data-state={state.name}>
            <Result
              status={tone === 'processing' ? 'info' : tone}
              title={<span role="status">{state.summary}</span>}
              subTitle={
                <>
                  {state.action}
                  <br />
                  <Badge
                    status={tone}
                    text={state.usable ? text.usable : text.notUsable}
                    data-state={state.usable ? 'usable' : 'not-usable'}
                  />
                </>
              }
            />
          </section>
        </SectionCard>
        {status !== undefined && (
          <SectionCard title={text.detailsTitle}>
            <section aria-label={text.detailsRegion}>
              <StatusDetails status={status} columns={2} />
            </section>
          </SectionCard>
        )}
        {footer}
      </>
    );
  }

  return (
    <>
      {children}
      <section aria-label={text.region} data-state={state.name}>
        <Flex justify="space-between" align="center" gap={8} wrap>
          <Badge status={tone} text={<span role="status">{state.summary}</span>} />
          {refreshButton}
        </Flex>
        <Typography.Paragraph type="secondary" style={{ margin: '4px 0 0 14px', fontSize: 13 }}>
          {state.action}
        </Typography.Paragraph>
        {status !== undefined && (
          <Collapse
            ghost
            size="small"
            aria-label={text.detailsRegion}
            items={[
              {
                key: 'details',
                label: text.detailsTitle,
                children: <StatusDetails status={status} columns={1} />,
              },
            ]}
          />
        )}
      </section>
      <div style={{ marginTop: 12 }}>{footer}</div>
    </>
  );
}

/** The facts the backend reported about itself, in Chinese. */
function StatusDetails({
  status,
  columns,
}: {
  readonly status: MetaStatus;
  readonly columns: number;
}): React.JSX.Element {
  const databaseUp = status.database.status === DATABASE_UP;
  return (
    <Descriptions
      size="small"
      column={columns === 1 ? 1 : { xs: 1, sm: 1, md: 2, lg: 2, xl: 2, xxl: 2 }}
      bordered
      styles={{ label: { whiteSpace: 'nowrap' } }}
      items={[
        { key: 'application', label: text.application, children: status.application },
        {
          key: 'environment',
          label: text.environment,
          children: environmentLabel(status.environment),
        },
        {
          key: 'database',
          label: text.database,
          children: (
            <Badge
              status={databaseUp ? 'success' : 'error'}
              text={databaseUp ? text.databaseUp : text.databaseDown(status.database.status)}
            />
          ),
        },
        {
          key: 'schema',
          label: text.schemaVersion,
          children: <Typography.Text code>{status.migration.currentVersion}</Typography.Text>,
        },
        { key: 'version', label: text.backendVersion, children: status.buildVersion },
        {
          key: 'commit',
          label: text.backendCommit,
          children: (
            <Typography.Text code copyable={{ text: status.gitCommit }}>
              {status.gitCommit.slice(0, 12)}
            </Typography.Text>
          ),
        },
        {
          key: 'time',
          label: text.serverTime,
          children: <DateTime value={status.serverTimeUtc} />,
        },
        {
          key: 'correlation',
          label: text.correlationId,
          children: (
            <Typography.Text code copyable>
              {status.correlationId}
            </Typography.Text>
          ),
        },
      ]}
    />
  );
}
