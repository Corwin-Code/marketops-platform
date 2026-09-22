import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Flex,
  Space,
  Steps,
  Tabs,
  Timeline,
  Tooltip,
  Typography,
} from 'antd';
import type { DescriptionsProps, StepsProps, TimelineItemProps } from 'antd';
import { ReloadOutlined, SyncOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { fetchCommand, fetchGate } from '../api/console';
import type { CommandReadback, ConsoleFailure, ConsoleRequest, PriceCommand } from '../api/console';
import { STORE_TIMEZONE_LABEL, toStoreDayjs } from '../format';
import { actions, codeLabel } from '../i18n';
import {
  ATTEMPT_OUTCOME_COLORS,
  ATTEMPT_OUTCOME_LABELS,
  ATTEMPT_PURPOSE_LABELS,
  COMMAND_FAILURE_LABELS,
  COMMAND_STATE_COLORS,
  COMMAND_STATE_DESCRIPTIONS,
  COMMAND_STATE_LABELS,
  FULFILLMENT_MODE_LABELS,
  GATE_REASON_LABELS,
  PLATFORM_LABELS,
  READBACK_MATCH_COLORS,
  READBACK_MATCH_LABELS,
} from '../i18n/zh/pricing';
import { commandNotice, commandText } from '../i18n/zh/pricingFollowUp';
import { subjectPath } from '../layout/navigation';
import {
  CodeTag,
  DateTime,
  EmptyState,
  FailureAlert,
  LoadingState,
  Money,
  SectionCard,
  TechnicalDetails,
} from '../ui';
import { CommandSubject } from './CommandSubject';

/** What the timeline needs in order to load itself. */
export interface CommandTimelineProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The command being followed. */
  readonly commandId: string;
}

/** How often a command that is still moving is read again. */
const POLL_INTERVAL_MS = 4000;

/**
 * States a worker will still move on its own (PriceCommandState: not terminal,
 * not waiting for an operator). {@code UNKNOWN_REQUIRES_READBACK} is here
 * because the worker leases it for a readback without anyone asking.
 */
const IN_PROGRESS = new Set([
  'PENDING',
  'LEASED',
  'EXECUTING',
  'PLATFORM_PENDING',
  'READBACK_PENDING',
  'RETRY_WAIT',
  'COMPENSATION_PENDING',
]);

/**
 * An unknown outcome waits for a person, but the worker may still read it back
 * on its own. The page watches it for a bounded while and then stops, so it
 * never shows an operator's task as endless background processing.
 */
const WATCHED_WHILE_UNKNOWN = 'UNKNOWN_REQUIRES_READBACK';
const UNKNOWN_WATCH_MS = 2 * 60_000;

/** States that end automatic handling (PriceCommandState.terminal()). */
const TERMINAL = new Set(['SUCCEEDED', 'FAILED_FINAL', 'COMPENSATED', 'COMPENSATION_FAILED']);

/** States a person has to look at, whether or not the worker is still busy. */
const ABNORMAL = new Set([
  'UNKNOWN_REQUIRES_READBACK',
  'READBACK_MISMATCH',
  'MANUAL_RESOLUTION',
  'FAILED_FINAL',
  'COMPENSATION_FAILED',
]);

/** States in which the console must not suggest the change has taken effect. */
const UNRESOLVED = new Set([
  'UNKNOWN_REQUIRES_READBACK',
  'READBACK_MISMATCH',
  'MANUAL_RESOLUTION',
  'COMPENSATION_FAILED',
]);

/** Timeline dot colours for a tag colour. */
const DOT_COLORS: Readonly<Record<string, string>> = {
  success: 'green',
  error: 'red',
  warning: 'orange',
  processing: 'blue',
};

/** What is known about the write gate: not yet asked, open or closed, or unknown. */
type GateState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'known'; readonly open: boolean; readonly reasons: readonly string[] }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/** Where a command sits on its lifecycle: queue, call, platform, readback, result. */
function lifecycle(state: string): {
  readonly current: number;
  readonly status: NonNullable<StepsProps['status']>;
} {
  switch (state) {
    case 'PENDING':
      return { current: 0, status: 'process' };
    case 'LEASED':
    case 'EXECUTING':
    case 'RETRY_WAIT':
      return { current: 1, status: 'process' };
    case 'PLATFORM_PENDING':
      return { current: 2, status: 'process' };
    case 'READBACK_PENDING':
      return { current: 3, status: 'process' };
    case 'UNKNOWN_REQUIRES_READBACK':
    case 'READBACK_MISMATCH':
      return { current: 3, status: 'error' };
    case 'SUCCEEDED':
    case 'COMPENSATED':
      return { current: 4, status: 'finish' };
    case 'COMPENSATION_PENDING':
      return { current: 4, status: 'process' };
    case 'MANUAL_RESOLUTION':
    case 'FAILED_FINAL':
    case 'COMPENSATION_FAILED':
      return { current: 4, status: 'error' };
    default:
      return { current: 0, status: 'error' };
  }
}

/** The readback observed last, by observation time; unreadable times sort first. */
function latestReadback(readbacks: readonly CommandReadback[]): CommandReadback | undefined {
  let latest: CommandReadback | undefined;
  let latestAt = Number.NEGATIVE_INFINITY;
  for (const readback of readbacks) {
    const at = Date.parse(readback.observedAt);
    const comparable = Number.isNaN(at) ? Number.NEGATIVE_INFINITY : at;
    if (latest === undefined || comparable >= latestAt) {
      latest = readback;
      latestAt = comparable;
    }
  }
  return latest;
}

/** Wall-clock time of a local read, in the store zone like every other time on screen. */
function clockTime(iso: string): string {
  return toStoreDayjs(iso)?.format('HH:mm:ss') ?? '—';
}

/** The alert type for a command state's tag colour. */
function alertType(state: string, unresolved: boolean): 'success' | 'info' | 'warning' | 'error' {
  const color = COMMAND_STATE_COLORS[state];
  if (color === 'error') {
    return 'error';
  }
  if (unresolved || color === 'warning') {
    return 'warning';
  }
  return color === 'success' ? 'success' : 'info';
}

/**
 * What happened to a price change, in the order it happened.
 *
 * Every call and every observation is shown, because the question an operator
 * asks about an unresolved change is never just what state it is in. A command
 * sitting in {@code UNKNOWN_REQUIRES_READBACK} is only actionable next to what
 * was actually called and what the marketplace actually answered.
 *
 * An unresolved command says so in plain words at the top. The most dangerous
 * screen this product could render is one that shows a price change and lets a
 * reader assume it took effect. For the same reason a gate whose state could
 * not be read is shown as unknown, never as open.
 *
 * While the command is still moving the page reads it again every few seconds
 * and says so; it stops once the command settles or needs a person, and tells
 * the operator when that happens while they are watching.
 */
export function CommandTimeline({ context, commandId }: CommandTimelineProps): React.JSX.Element {
  const { notification } = App.useApp();
  const [command, setCommand] = useState<PriceCommand | undefined>(undefined);
  const [gate, setGate] = useState<GateState>({ kind: 'loading' });
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [updatedAt, setUpdatedAt] = useState<string | undefined>(undefined);
  const [tick, setTick] = useState(0);
  const observedState = useRef<string | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchCommand(context, commandId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setCommand(outcome.value);
        setFailure(undefined);
        setUpdatedAt(new Date().toISOString());
      } else {
        setFailure(outcome.failure);
      }
    });
    void fetchGate(context, commandId).then((outcome) => {
      if (!active) {
        return;
      }
      setGate(
        outcome.ok
          ? { kind: 'known', open: outcome.value.open, reasons: outcome.value.blockingReasons }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      active = false;
    };
  }, [context, commandId, tick]);

  // When the command entered the unknown state on this page, for the bounded watch.
  const unknownSince = useRef<number | undefined>(undefined);
  const [unknownExpired, setUnknownExpired] = useState(false);
  const unknownState = command?.state === WATCHED_WHILE_UNKNOWN;
  useEffect(() => {
    if (!unknownState) {
      unknownSince.current = undefined;
      setUnknownExpired(false);
      return;
    }
    unknownSince.current ??= Date.now();
    const remaining = unknownSince.current + UNKNOWN_WATCH_MS - Date.now();
    if (remaining <= 0) {
      setUnknownExpired(true);
      return;
    }
    const timer = window.setTimeout(() => {
      setUnknownExpired(true);
    }, remaining);
    return () => {
      window.clearTimeout(timer);
    };
  }, [unknownState]);
  const watchingUnknown = unknownState && !unknownExpired;
  const polling =
    command !== undefined &&
    failure === undefined &&
    (IN_PROGRESS.has(command.state) || watchingUnknown);

  useEffect(() => {
    if (!polling) {
      return;
    }
    // Scheduled after each answer rather than on a fixed interval, so a slow
    // backend is never asked again before it has replied.
    const timer = window.setTimeout(() => {
      setTick((value) => value + 1);
    }, POLL_INTERVAL_MS);
    return () => {
      window.clearTimeout(timer);
    };
  }, [polling, updatedAt]);

  useEffect(() => {
    if (command === undefined) {
      return;
    }
    const previous = observedState.current;
    observedState.current = command.state;
    // Only a change seen on this page is announced; opening a page on a
    // command that settled yesterday is not news.
    if (previous === undefined || previous === command.state) {
      return;
    }
    const settled = TERMINAL.has(command.state);
    const abnormal =
      ABNORMAL.has(command.state) || !Object.hasOwn(COMMAND_STATE_LABELS, command.state);
    if (!settled && !abnormal) {
      return;
    }
    const color = COMMAND_STATE_COLORS[command.state];
    const type =
      color === 'success' ? 'success' : color === 'error' ? 'error' : abnormal ? 'warning' : 'info';
    notification[type]({
      key: `command-state:${command.id}`,
      title: commandNotice.changedTitle(codeLabel(COMMAND_STATE_LABELS, command.state)),
      description: describeState(command.state),
    });
  }, [command, notification]);

  const refreshButton = (
    <Button
      icon={<ReloadOutlined />}
      aria-label={commandText.refreshLabel}
      onClick={() => {
        setTick((value) => value + 1);
      }}
    >
      {actions.refresh}
    </Button>
  );

  if (command === undefined) {
    return (
      <section
        aria-label={commandText.regionLabel}
        data-state={failure === undefined ? 'loading' : 'error'}
      >
        <SectionCard
          title={commandText.title}
          {...(failure === undefined ? {} : { extra: refreshButton })}
        >
          {failure === undefined ? <LoadingState rows={4} /> : <FailureAlert failure={failure} />}
        </SectionCard>
      </section>
    );
  }

  const unresolved = UNRESOLVED.has(command.state);
  const steps = lifecycle(command.state);
  const lastStepTitle =
    steps.current === 4
      ? codeLabel(COMMAND_STATE_LABELS, command.state)
      : commandText.lifecycleResult;
  const latest = latestReadback(command.readbacks);

  const details: DescriptionsProps['items'] = [
    {
      key: 'prior',
      label: commandText.priorPrice,
      children: <Money value={command.priorPrice} currency={command.currencyCode} />,
    },
    {
      key: 'target',
      label: commandText.targetPrice,
      children: <Money value={command.targetPrice} currency={command.currencyCode} strong />,
    },
    {
      key: 'readback',
      label: commandText.latestReadback,
      children:
        latest === undefined ? (
          <Typography.Text type="secondary" data-testid="no-readback">
            {commandText.noReadback}
          </Typography.Text>
        ) : (
          <Flex gap={8} wrap align="center" data-match={latest.matchState}>
            <Money value={latest.observedPrice} currency={latest.currencyCode} strong />
            <CodeTag
              labels={READBACK_MATCH_LABELS}
              code={latest.matchState}
              colors={READBACK_MATCH_COLORS}
            />
          </Flex>
        ),
    },
    ...(command.fulfillmentModeCode === null
      ? []
      : [
          {
            key: 'mode',
            label: commandText.fulfillmentMode,
            children: (
              <CodeTag labels={FULFILLMENT_MODE_LABELS} code={command.fulfillmentModeCode} />
            ),
          },
        ]),
    { key: 'attempts', label: commandText.attempts, children: String(command.attemptNo) },
    ...(command.failureCode === null
      ? []
      : [
          {
            key: 'failure',
            label: commandText.failure,
            children: (
              <CodeTag
                labels={COMMAND_FAILURE_LABELS}
                code={command.failureCode}
                colors={{ [command.failureCode]: 'error' }}
              />
            ),
          },
        ]),
  ];

  const attemptItems: TimelineItemProps[] = command.attempts.map((attempt) => ({
    key: attempt.id,
    color: DOT_COLORS[ATTEMPT_OUTCOME_COLORS[attempt.outcomeClass] ?? ''] ?? 'gray',
    title: <DateTime value={attempt.startedAt} />,
    content: (
      <Flex vertical gap={4} data-purpose={attempt.purpose}>
        <Flex gap={8} wrap align="center">
          <Typography.Text strong>
            {commandText.attemptTitle(
              attempt.attemptNo,
              codeLabel(ATTEMPT_PURPOSE_LABELS, attempt.purpose),
            )}
          </Typography.Text>
          <CodeTag
            labels={ATTEMPT_OUTCOME_LABELS}
            code={attempt.outcomeClass}
            colors={ATTEMPT_OUTCOME_COLORS}
          />
          {attempt.errorCode !== null && (
            <CodeTag
              labels={COMMAND_FAILURE_LABELS}
              code={attempt.errorCode}
              colors={{ [attempt.errorCode]: 'error' }}
            />
          )}
        </Flex>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {attempt.nativeStatus !== null && commandText.nativeStatus(attempt.nativeStatus)}
          {commandText.completedAt}
          {attempt.completedAt === null ? (
            commandText.notCompleted
          ) : (
            <DateTime value={attempt.completedAt} />
          )}
        </Typography.Text>
      </Flex>
    ),
  }));

  const readbackItems: TimelineItemProps[] = command.readbacks.map((readback) => ({
    key: readback.id,
    color: DOT_COLORS[READBACK_MATCH_COLORS[readback.matchState] ?? ''] ?? 'gray',
    title: <DateTime value={readback.observedAt} />,
    content: (
      <Flex gap={8} wrap align="center" data-match={readback.matchState}>
        <Money value={readback.observedPrice} currency={readback.currencyCode} strong />
        <CodeTag
          labels={READBACK_MATCH_LABELS}
          code={readback.matchState}
          colors={READBACK_MATCH_COLORS}
        />
      </Flex>
    ),
  }));

  let gateNotice: ReactNode = null;
  if (gate.kind === 'failed') {
    gateNotice = (
      <section aria-label={commandText.gateLabel} data-testid="gate-unknown" data-state="unknown">
        <Flex vertical gap={6}>
          <Typography.Text strong type="danger">
            {commandText.gateUnknown}
          </Typography.Text>
          <FailureAlert
            failure={gate.failure}
            action={
              <Button
                size="small"
                onClick={() => {
                  setTick((value) => value + 1);
                }}
              >
                {actions.retry}
              </Button>
            }
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {commandText.gateUnknownDescription}
          </Typography.Text>
        </Flex>
      </section>
    );
  } else if (gate.kind === 'known' && (!gate.open || gate.reasons.length > 0)) {
    gateNotice = (
      <section aria-label={commandText.gateLabel} data-testid="gate-closed">
        <Alert
          type="error"
          showIcon
          title={commandText.gateClosed}
          description={
            gate.reasons.length === 0 ? (
              commandText.gateClosedNoReason
            ) : (
              <Flex gap={6} wrap style={{ marginTop: 4 }}>
                {gate.reasons.map((reason) => (
                  <span key={reason} data-reason={reason}>
                    <CodeTag
                      labels={GATE_REASON_LABELS}
                      code={reason}
                      colors={{ [reason]: 'error' }}
                    />
                  </span>
                ))}
              </Flex>
            )
          }
        />
      </section>
    );
  }

  const freshness =
    updatedAt === undefined ? null : polling ? (
      <Tooltip title={`${commandText.autoRefreshHelp}（${STORE_TIMEZONE_LABEL}）`}>
        <Typography.Text type="secondary" data-state="polling" style={{ fontSize: 12 }}>
          <SyncOutlined spin style={{ marginInlineEnd: 4 }} />
          {watchingUnknown
            ? commandText.watchingUnknown(clockTime(updatedAt))
            : commandText.autoRefreshing(clockTime(updatedAt))}
        </Typography.Text>
      </Tooltip>
    ) : (
      <Tooltip title={STORE_TIMEZONE_LABEL}>
        <Typography.Text type="secondary" data-state="idle" style={{ fontSize: 12 }}>
          {commandText.updatedAt(clockTime(updatedAt))}
        </Typography.Text>
      </Tooltip>
    );

  return (
    <section
      aria-label={commandText.regionLabel}
      data-command={command.id}
      data-state={command.state}
      data-polling={polling}
    >
      <SectionCard
        title={
          <Space size="small" wrap>
            <span>
              {commandText.title} · {codeLabel(PLATFORM_LABELS, command.platformCode)}
            </span>
            <CodeTag
              labels={COMMAND_STATE_LABELS}
              code={command.state}
              colors={COMMAND_STATE_COLORS}
            />
          </Space>
        }
        extra={
          <Space size="middle" wrap>
            {freshness}
            {refreshButton}
          </Space>
        }
      >
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          <Flex gap={16} wrap align="center" justify="space-between">
            <CommandSubject command={command} />
            {command.subjectId !== undefined && (
              <Link to={subjectPath(command.subjectId)}>{commandText.backToDiagnosis}</Link>
            )}
          </Flex>
          {failure !== undefined && (
            <Flex vertical gap={4} data-state="refresh-failed">
              <Typography.Text type="warning">{commandText.refreshFailed}</Typography.Text>
              <FailureAlert failure={failure} />
            </Flex>
          )}
          <Steps
            size="small"
            current={steps.current}
            status={steps.status}
            items={[...commandText.lifecycle.map((title) => ({ title })), { title: lastStepTitle }]}
          />
          <div
            data-testid="command-state"
            data-unresolved={unresolved}
            role={unresolved ? 'alert' : 'status'}
          >
            <Alert
              type={alertType(command.state, unresolved)}
              showIcon
              title={unresolved ? commandText.unresolvedTitle : describeState(command.state)}
              {...(unresolved
                ? {
                    description: (
                      <Flex vertical gap={2}>
                        <span>{describeState(command.state)}</span>
                        <span>{commandText.unresolvedDescription}</span>
                      </Flex>
                    ),
                  }
                : {})}
            />
          </div>
          <Descriptions bordered size="small" column={{ xs: 1, md: 2, xl: 3 }} items={details} />
          {gateNotice}
          <TechnicalDetails
            data={{
              commandId: command.id,
              recommendationId: command.recommendationId,
              ...(command.subjectId === undefined ? {} : { subjectId: command.subjectId }),
            }}
          />
        </Space>
      </SectionCard>

      {/*
        The effect of a change is not a tab here: the outcome records the
        console can read belong to advertising commands, and none are kept
        against price commands.
      */}
      <Card style={{ marginBottom: 16 }}>
        <Tabs
          items={[
            {
              key: 'attempts',
              label: commandText.attemptsTab(command.attempts.length),
              children: (
                <section aria-label="平台调用记录">
                  {command.attempts.length === 0 ? (
                    <EmptyState description={commandText.noAttempts} />
                  ) : (
                    <Timeline items={attemptItems} />
                  )}
                </section>
              ),
            },
            {
              key: 'readbacks',
              label: commandText.readbacksTab(command.readbacks.length),
              children: (
                <section aria-label="回读记录">
                  {command.readbacks.length === 0 ? (
                    <EmptyState description={commandText.noReadbacks} />
                  ) : (
                    <Timeline items={readbackItems} />
                  )}
                </section>
              ),
            },
          ]}
        />
      </Card>
    </section>
  );
}

/** Say what a command's state means, rather than showing the code alone. */
export function describeState(state: string): string {
  return Object.hasOwn(COMMAND_STATE_DESCRIPTIONS, state)
    ? (COMMAND_STATE_DESCRIPTIONS[state] ?? state)
    : commandText.unrecognizedState(state);
}

/** Say what a readback observed, rather than showing the code alone. */
export function describeMatch(matchState: string): string {
  return Object.hasOwn(READBACK_MATCH_LABELS, matchState)
    ? (READBACK_MATCH_LABELS[matchState] ?? matchState)
    : commandText.unreadableReadback;
}
