import { Badge, Button, Card, Flex, Popover, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { fetchCommandsNeedingAttention, fetchPriorityQueue } from '../api/console';
import type { ConsoleFailure, ConsoleRequest, PriceCommand, PrioritySubject } from '../api/console';
import { CommandSubject, commandSubjectName } from '../commands/CommandSubject';
import { formatDecimal } from '../format';
import { actions, codeLabel } from '../i18n';
import {
  COMMAND_FAILURE_LABELS,
  COMMAND_STATE_COLORS,
  COMMAND_STATE_LABELS,
  PLATFORM_LABELS,
  RULE_LABELS,
} from '../i18n/zh/pricing';
import { attentionColumns, queueText } from '../i18n/zh/pricingFollowUp';
import { commandPath } from '../layout/navigation';
import {
  CodeTag,
  EmptyState,
  FailureAlert,
  InfoTip,
  LoadingState,
  Money,
  SubjectName,
  subjectTitle,
  usePageParam,
  useSearchParam,
} from '../ui';

/** What the queue needs in order to load itself. */
export interface PriorityQueueProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** Store whose work list is being shown. */
  readonly storeId: string;
  /** Called when the operator picks a subject to look at. */
  readonly onSelect: (subjectId: string) => void;
}

/** A list read that has not answered, answered, or failed. */
type Loaded<T> =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly value: T }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

const TAB_SUBJECTS = 'subjects';
const TAB_COMMANDS = 'commands';
const PAGE_SIZE = 20;

/** A count that draws the eye only when it is not zero. */
function CountBadge({
  value,
  color,
}: {
  readonly value: number;
  readonly color: string;
}): React.JSX.Element {
  return value === 0 ? (
    <Typography.Text type="secondary">0</Typography.Text>
  ) : (
    <Badge count={value} color={color} overflowCount={999} showZero />
  );
}

/** A column heading with its once-only explanation beside it. */
function HeadingWithTip({
  label,
  tip,
}: {
  readonly label: string;
  readonly tip: string;
}): React.JSX.Element {
  return (
    <span style={{ whiteSpace: 'nowrap' }}>
      {label}
      <InfoTip title={tip} />
    </span>
  );
}

/** Keyboard activation for a clickable row, so it is not a mouse-only control. */
function rowActivation(label: string, activate: () => void): React.HTMLAttributes<HTMLElement> {
  return {
    onClick: activate,
    onKeyDown: (event) => {
      if (event.key === 'Enter' && event.target === event.currentTarget) {
        activate();
      }
    },
    tabIndex: 0,
    'aria-label': label,
    style: { cursor: 'pointer' },
  };
}

/**
 * Whether platform writes are blocked, with the blocking rules one hover away.
 *
 * The rules sit in a popover rather than in the row: a row that grows by one
 * tag per rule pushes the rest of the work list off the screen. Clicks on the
 * tag stay on the tag, so reading the rules does not open the subject.
 */
function WriteState({ subject }: { readonly subject: PrioritySubject }): React.JSX.Element {
  const codes = subject.blockingRuleCodes;
  if (codes.length === 0) {
    return <Tag color="success">{queueText.writable}</Tag>;
  }
  return (
    <Popover
      title={queueText.blockingRulesTitle}
      trigger={['hover', 'click']}
      content={
        // The popover renders in a portal, but React still bubbles its events
        // to the row, which would open the subject on any click inside it.
        <div
          role="presentation"
          onClick={(event) => {
            event.stopPropagation();
          }}
          onKeyDown={(event) => {
            event.stopPropagation();
          }}
        >
          <Space size={[4, 4]} wrap style={{ maxWidth: 320 }}>
            {codes.map((code) => (
              <span key={code} data-rule={code}>
                <CodeTag labels={RULE_LABELS} code={code} />
              </span>
            ))}
          </Space>
        </div>
      }
    >
      <Tag
        color="error"
        tabIndex={0}
        style={{ cursor: 'help' }}
        onClick={(event) => {
          event.stopPropagation();
        }}
        onKeyDown={(event) => {
          event.stopPropagation();
        }}
      >
        {queueText.blocked(codes.length)}
      </Tag>
    </Popover>
  );
}

/**
 * What to look at first.
 *
 * The list is ordered by the backend rather than re-sorted here, because the
 * priority is a deterministic figure with a definition and a console that
 * re-ordered it would be presenting its own opinion as the product's.
 *
 * A subject whose findings block a platform write says so in the row. An
 * operator who opens a subject expecting to change its price and only then
 * learns that nothing can be changed has spent their attention for nothing.
 *
 * Beside the subjects sit the price commands that need a person: a change whose
 * result is unknown is today's work as much as a subject is.
 */
export function PriorityQueue({
  context,
  storeId,
  onSelect,
}: PriorityQueueProps): React.JSX.Element {
  const navigate = useNavigate();
  const [subjects, setSubjects] = useState<Loaded<readonly PrioritySubject[]>>({
    kind: 'loading',
  });
  const [commands, setCommands] = useState<Loaded<readonly PriceCommand[]>>({
    kind: 'loading',
  });
  const [reload, setReload] = useState(0);
  const [page, setPage] = usePageParam();
  const [tabParam, setTab] = useSearchParam('tab');
  const tab = tabParam === TAB_COMMANDS ? TAB_COMMANDS : TAB_SUBJECTS;

  useEffect(() => {
    let active = true;
    void fetchPriorityQueue(context, storeId).then((outcome) => {
      if (active) {
        setSubjects(
          outcome.ok
            ? { kind: 'loaded', value: outcome.value }
            : { kind: 'failed', failure: outcome.failure },
        );
      }
    });
    // Loaded with the queue rather than when its tab opens, so the tab can
    // show how many commands are waiting before anyone clicks it.
    void fetchCommandsNeedingAttention(context, storeId).then((outcome) => {
      if (active) {
        setCommands(
          outcome.ok
            ? { kind: 'loaded', value: outcome.value }
            : { kind: 'failed', failure: outcome.failure },
        );
      }
    });
    return () => {
      active = false;
    };
  }, [context, storeId, reload]);

  const refresh = (
    <Button
      icon={<ReloadOutlined />}
      aria-label={queueText.refreshLabel}
      onClick={() => {
        setSubjects({ kind: 'loading' });
        setCommands({ kind: 'loading' });
        setReload((value) => value + 1);
      }}
    >
      {actions.refresh}
    </Button>
  );

  const subjectColumns: TableColumnsType<PrioritySubject> = [
    {
      title: queueText.subjectColumn,
      key: 'subject',
      render: (_, subject) => (
        <SubjectName identity={subject.identity} subjectId={subject.subjectId} />
      ),
    },
    {
      title: <HeadingWithTip label={queueText.priorityColumn} tip={queueText.priorityHelp} />,
      key: 'priority',
      render: (_, subject) => (
        <Typography.Text strong style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatDecimal(subject.priorityScore, { maxFractionDigits: 2 })}
        </Typography.Text>
      ),
    },
    {
      title: queueText.criticalColumn,
      key: 'critical',
      align: 'center',
      render: (_, subject) => <CountBadge value={subject.criticalFindingCount} color="red" />,
    },
    {
      title: queueText.warningColumn,
      key: 'warning',
      align: 'center',
      render: (_, subject) => <CountBadge value={subject.warningFindingCount} color="gold" />,
    },
    {
      title: <HeadingWithTip label={queueText.declinedColumn} tip={queueText.declinedHelp} />,
      key: 'declined',
      align: 'center',
      render: (_, subject) => <CountBadge value={subject.declinedRuleCount} color="orange" />,
    },
    {
      title: queueText.netSalesColumn,
      key: 'netSales',
      align: 'right',
      render: (_, subject) => <Money value={subject.netSales} currency={subject.currencyCode} />,
    },
    {
      title: queueText.profitColumn,
      key: 'profit',
      align: 'right',
      render: (_, subject) => (
        <Money value={subject.contributionProfit} currency={subject.currencyCode} />
      ),
    },
    {
      title: <HeadingWithTip label={queueText.writeColumn} tip={queueText.writeHelp} />,
      key: 'write',
      onCell: (subject) =>
        ({
          'data-write-blocked': subject.blockingRuleCodes.length > 0,
        }) as React.TdHTMLAttributes<HTMLTableCellElement>,
      render: (_, subject): ReactNode => <WriteState subject={subject} />,
    },
  ];

  const commandColumns: TableColumnsType<PriceCommand> = [
    {
      title: attentionColumns.subject,
      key: 'subject',
      render: (_, command) => <CommandSubject command={command} />,
    },
    {
      title: attentionColumns.platform,
      key: 'platform',
      render: (_, command) => codeLabel(PLATFORM_LABELS, command.platformCode),
    },
    {
      title: attentionColumns.price,
      key: 'price',
      render: (_, command) => (
        <Flex gap={6} align="center" wrap={false}>
          <Money value={command.priorPrice} currency={command.currencyCode} />
          <Typography.Text type="secondary">→</Typography.Text>
          <Money value={command.targetPrice} currency={command.currencyCode} strong />
        </Flex>
      ),
    },
    {
      title: attentionColumns.state,
      key: 'state',
      render: (_, command) => (
        <CodeTag labels={COMMAND_STATE_LABELS} code={command.state} colors={COMMAND_STATE_COLORS} />
      ),
    },
    {
      title: attentionColumns.failure,
      key: 'failure',
      render: (_, command) => (
        <CodeTag
          labels={COMMAND_FAILURE_LABELS}
          code={command.failureCode}
          {...(command.failureCode === null ? {} : { colors: { [command.failureCode]: 'error' } })}
        />
      ),
    },
    {
      title: attentionColumns.open,
      key: 'open',
      render: (_, command) => (
        <Link
          to={commandPath(command.id)}
          aria-label={attentionColumns.openLabel(commandSubjectName(command))}
          onClick={(event) => {
            event.stopPropagation();
          }}
        >
          {attentionColumns.openLink}
        </Link>
      ),
    },
  ];

  let subjectsBody: ReactNode;
  let state: string;
  switch (subjects.kind) {
    case 'failed':
      state = 'error';
      subjectsBody = <FailureAlert failure={subjects.failure} />;
      break;
    case 'loading':
      state = 'loading';
      subjectsBody = <LoadingState rows={5} />;
      break;
    case 'loaded':
      if (subjects.value.length === 0) {
        state = 'empty';
        subjectsBody = <EmptyState description={queueText.emptySubjects} />;
      } else {
        state = 'loaded';
        subjectsBody = (
          <Table<PrioritySubject>
            size="middle"
            rowKey="subjectId"
            columns={subjectColumns}
            dataSource={[...subjects.value]}
            pagination={{
              current: page,
              pageSize: PAGE_SIZE,
              hideOnSinglePage: true,
              showSizeChanger: false,
              onChange: (next) => {
                setPage(next);
              },
            }}
            scroll={{ x: 'max-content' }}
            onRow={(subject) =>
              ({
                'data-subject': subject.subjectId,
                ...rowActivation(
                  queueText.openSubject(subjectTitle(subject.identity, subject.subjectId)),
                  () => {
                    onSelect(subject.subjectId);
                  },
                ),
              }) as React.HTMLAttributes<HTMLElement>
            }
          />
        );
      }
      break;
  }

  let commandsBody: ReactNode;
  switch (commands.kind) {
    case 'failed':
      commandsBody = (
        <section aria-label={queueText.commandsFailed} data-state="error">
          <FailureAlert failure={commands.failure} />
        </section>
      );
      break;
    case 'loading':
      commandsBody = <LoadingState rows={3} />;
      break;
    case 'loaded':
      commandsBody =
        commands.value.length === 0 ? (
          <EmptyState description={queueText.emptyCommands} />
        ) : (
          <Table<PriceCommand>
            size="middle"
            rowKey="id"
            columns={commandColumns}
            dataSource={[...commands.value]}
            pagination={{ pageSize: PAGE_SIZE, hideOnSinglePage: true, showSizeChanger: false }}
            scroll={{ x: 'max-content' }}
            onRow={(command) =>
              ({
                'data-command': command.id,
                ...rowActivation(attentionColumns.openLabel(commandSubjectName(command)), () => {
                  void navigate(commandPath(command.id));
                }),
              }) as React.HTMLAttributes<HTMLElement>
            }
          />
        );
      break;
  }

  const attentionCount = commands.kind === 'loaded' ? commands.value.length : 0;

  return (
    <section aria-label={queueText.regionLabel} data-state={state} data-tab={tab}>
      <Card
        style={{ marginBottom: 16 }}
        activeTabKey={tab}
        onTabChange={(key) => {
          setTab(key === TAB_SUBJECTS ? undefined : key);
        }}
        tabBarExtraContent={refresh}
        tabList={[
          { key: TAB_SUBJECTS, label: queueText.subjectsTab },
          {
            key: TAB_COMMANDS,
            label: (
              <Space size={6}>
                <span>{queueText.commandsTab}</span>
                <Badge
                  count={attentionCount}
                  overflowCount={99}
                  {...(commands.kind === 'failed' ? { status: 'warning' as const } : {})}
                />
              </Space>
            ),
          },
        ]}
      >
        {tab === TAB_SUBJECTS ? (
          subjectsBody
        ) : (
          <Flex vertical gap={12} data-state={commands.kind}>
            <Typography.Text type="secondary">{queueText.commandsHelp}</Typography.Text>
            {commandsBody}
          </Flex>
        )}
      </Card>
    </section>
  );
}

/** Optional framing for a failed list read. */
export interface QueueProblemProps {
  readonly failure: ConsoleFailure;
  /** Accessible name of the region; defaults to 加载失败. */
  readonly label?: string;
}

/**
 * Say what went wrong in terms of what the operator can do about it.
 *
 * Shared by other work lists, so it carries no heading of its own: the caller's
 * section already names the list, and a fixed title would mislabel theirs.
 */
export function QueueProblem({
  failure,
  label = '加载失败',
}: QueueProblemProps): React.JSX.Element {
  return (
    <section aria-label={label} data-state="error">
      <FailureAlert failure={failure} />
    </section>
  );
}
