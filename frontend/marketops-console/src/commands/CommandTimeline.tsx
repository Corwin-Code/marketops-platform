import { Alert, Button, Descriptions, Flex, Space, Steps, Timeline, Typography } from 'antd';
import type { DescriptionsProps, StepsProps, TimelineItemProps } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { fetchCommand, fetchGate } from '../api/console';
import type { ConsoleFailure, ConsoleRequest, PriceCommand } from '../api/console';
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

/** What the timeline needs in order to load itself. */
export interface CommandTimelineProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The command being followed. */
  readonly commandId: string;
}

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
 * reader assume it took effect.
 */
export function CommandTimeline({ context, commandId }: CommandTimelineProps): React.JSX.Element {
  const [command, setCommand] = useState<PriceCommand | undefined>(undefined);
  const [gate, setGate] = useState<readonly string[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    let active = true;
    void fetchCommand(context, commandId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setCommand(outcome.value);
        setFailure(undefined);
      } else {
        setFailure(outcome.failure);
      }
    });
    void fetchGate(context, commandId).then((outcome) => {
      if (active && outcome.ok) {
        setGate(outcome.value.blockingReasons);
      }
    });
    return () => {
      active = false;
    };
  }, [context, commandId, refresh]);

  const refreshButton = (
    <Button
      icon={<ReloadOutlined />}
      aria-label="刷新指令"
      onClick={() => {
        setRefresh((value) => value + 1);
      }}
    >
      {actions.refresh}
    </Button>
  );

  if (failure !== undefined) {
    return (
      <section aria-label="调价指令" data-state="error">
        <SectionCard title="调价指令" extra={refreshButton}>
          <FailureAlert failure={failure} />
        </SectionCard>
      </section>
    );
  }
  if (command === undefined) {
    return (
      <section aria-label="调价指令" data-state="loading">
        <SectionCard title="调价指令">
          <LoadingState rows={4} />
        </SectionCard>
      </section>
    );
  }

  const unresolved = UNRESOLVED.has(command.state);
  const stateColor = COMMAND_STATE_COLORS[command.state];
  const steps = lifecycle(command.state);
  const lastStepTitle =
    steps.current === 4 ? codeLabel(COMMAND_STATE_LABELS, command.state) : '结果';

  const details: DescriptionsProps['items'] = [
    {
      key: 'prior',
      label: '调价前价格',
      children: <Money value={command.priorPrice} currency={command.currencyCode} />,
    },
    {
      key: 'target',
      label: '目标价格',
      children: <Money value={command.targetPrice} currency={command.currencyCode} strong />,
    },
    ...(command.fulfillmentModeCode === null
      ? []
      : [
          {
            key: 'mode',
            label: '履约模式',
            children: (
              <CodeTag labels={FULFILLMENT_MODE_LABELS} code={command.fulfillmentModeCode} />
            ),
          },
        ]),
    { key: 'attempts', label: '已调用次数', children: String(command.attemptNo) },
    ...(command.failureCode === null
      ? []
      : [
          {
            key: 'failure',
            label: '失败原因',
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
            第 {attempt.attemptNo} 次 · {codeLabel(ATTEMPT_PURPOSE_LABELS, attempt.purpose)}
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
          {attempt.nativeStatus !== null && `平台状态码：${attempt.nativeStatus} · `}
          完成时间：
          {attempt.completedAt === null ? '尚未完成' : <DateTime value={attempt.completedAt} />}
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

  return (
    <section aria-label="调价指令" data-command={command.id} data-state={command.state}>
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <SectionCard
          title={
            <Space size="small" wrap>
              <span>调价指令 · {codeLabel(PLATFORM_LABELS, command.platformCode)}</span>
              <CodeTag
                labels={COMMAND_STATE_LABELS}
                code={command.state}
                colors={COMMAND_STATE_COLORS}
              />
            </Space>
          }
          extra={refreshButton}
        >
          <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
            <Steps
              size="small"
              current={steps.current}
              status={steps.status}
              items={[
                { title: '等待执行' },
                { title: '调用平台' },
                { title: '平台处理' },
                { title: '回读确认' },
                { title: lastStepTitle },
              ]}
            />
            <div data-testid="command-state" role="status">
              <Alert
                type={
                  stateColor === 'success'
                    ? 'success'
                    : stateColor === 'error'
                      ? 'error'
                      : stateColor === 'warning'
                        ? 'warning'
                        : 'info'
                }
                showIcon
                title={describeState(command.state)}
              />
            </div>
            {unresolved && (
              <div data-testid="command-unresolved" role="alert">
                <Alert
                  type="warning"
                  showIcon
                  title="此变更尚未确认"
                  description="在回读确认之前，不要假定平台已是新价格。"
                />
              </div>
            )}
            <Descriptions bordered size="small" column={{ xs: 1, md: 2, xl: 3 }} items={details} />
            {gate !== undefined && gate.length > 0 && (
              <section aria-label="写入闸门" data-testid="gate-closed">
                <Alert
                  type="error"
                  showIcon
                  title="写入闸门关闭，指令当前不能发往平台"
                  description={
                    <Flex gap={6} wrap style={{ marginTop: 4 }}>
                      {gate.map((reason) => (
                        <span key={reason} data-reason={reason}>
                          <CodeTag
                            labels={GATE_REASON_LABELS}
                            code={reason}
                            colors={{ [reason]: 'error' }}
                          />
                        </span>
                      ))}
                    </Flex>
                  }
                />
              </section>
            )}
            <TechnicalDetails
              data={{ commandId: command.id, recommendationId: command.recommendationId }}
            />
          </Space>
        </SectionCard>

        <section aria-label="平台调用记录">
          <SectionCard title="平台调用记录">
            {command.attempts.length === 0 ? (
              <EmptyState description="尚未调用平台" />
            ) : (
              <Timeline items={attemptItems} />
            )}
          </SectionCard>
        </section>

        <section aria-label="平台当前价格">
          <SectionCard title="平台当前价格（回读）">
            {command.readbacks.length === 0 ? (
              <div data-testid="no-readback">
                <EmptyState description="尚未回读，无法确认平台当前价格" />
              </div>
            ) : (
              <Timeline items={readbackItems} />
            )}
          </SectionCard>
        </section>
      </Space>
    </section>
  );
}

/** Say what a command's state means, rather than showing the code alone. */
export function describeState(state: string): string {
  return Object.hasOwn(COMMAND_STATE_DESCRIPTIONS, state)
    ? (COMMAND_STATE_DESCRIPTIONS[state] ?? state)
    : `指令处于未识别的状态（${state}）。`;
}

/** Say what a readback observed, rather than showing the code alone. */
export function describeMatch(matchState: string): string {
  return Object.hasOwn(READBACK_MATCH_LABELS, matchState)
    ? (READBACK_MATCH_LABELS[matchState] ?? matchState)
    : '无法读取平台回复';
}
