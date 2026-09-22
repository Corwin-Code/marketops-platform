import { HistoryOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  App,
  Button,
  Flex,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Tooltip,
  Typography,
} from 'antd';
import type { TableColumnsType, TimelineProps } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  fetchAvailabilityCases,
  fetchCaseExceptions,
  fetchCaseJournal,
  recordCaseAction,
} from '../api/console';
import type {
  AcceptedException,
  AvailabilityCase,
  CaseJournalEntry,
  ConsoleFailure,
  ConsoleRequest,
} from '../api/console';
import { actions } from '../i18n';
import {
  AUTHORITY_LEVEL_LABELS,
  CASE_EVENT_LABELS,
  CASE_STATE_LABELS,
  EXCEPTION_REASON_LABELS,
  EXCEPTION_STATE_LABELS,
  LANE_LABELS,
  ROLE_LABELS,
  VERIFICATION_OUTCOME_LABELS,
  availabilityText,
} from '../i18n/zh/availability';
import {
  CodeTag,
  DateTime,
  EmptyState,
  FailureAlert,
  LoadingState,
  SectionCard,
  TechnicalDetails,
} from '../ui';
import type { TagColor } from '../ui';
import { causeLabel } from './riskPresentation';
import { ACTION_KINDS, actionKindLabel, dueTone, presentCaseState } from './casePresentation';
import {
  CASE_STATE_COLORS,
  EXCEPTION_STATE_COLORS,
  LANE_COLORS,
  VERIFICATION_OUTCOME_COLORS,
} from './tagColors';

/** What the case panel needs in order to load itself. */
export interface AvailabilityCasesProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The instant the deadlines are judged against. */
  readonly now?: Date;
}

/**
 * Who owns each availability failure, and what they have actually done.
 *
 * The two clocks are shown separately because they are separate obligations.
 * An action recorded on time and an outcome nobody ever verified is a specific
 * failure with a specific owner, and a single combined "overdue" badge would
 * hide exactly that case.
 *
 * There is no acknowledgement control anywhere on this panel. The action stage
 * takes a named action and the reference to the artefact behind it, and a
 * console that offered a button meaning "seen" would be offering something the
 * product refuses to accept.
 */
export function AvailabilityCases({
  context,
  now = new Date(),
}: AvailabilityCasesProps): React.JSX.Element {
  const [cases, setCases] = useState<readonly AvailabilityCase[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [acting, setActing] = useState<AvailabilityCase | undefined>(undefined);
  const [expanded, setExpanded] = useState<readonly string[]>([]);

  const load = useCallback(() => {
    void fetchAvailabilityCases(context).then((outcome) => {
      if (outcome.ok) {
        setCases(outcome.value);
        setFailure(undefined);
      } else {
        setCases(undefined);
        setFailure(outcome.failure);
      }
    });
  }, [context]);

  useEffect(load, [load]);

  const refresh = (
    <Button
      icon={<ReloadOutlined />}
      aria-label="刷新工单"
      onClick={() => {
        setCases(undefined);
        setFailure(undefined);
        load();
      }}
    >
      {actions.refresh}
    </Button>
  );

  const frame = (state: string, body: React.ReactNode): React.JSX.Element => (
    <section aria-label={availabilityText.casesTitle} data-state={state}>
      <SectionCard title={availabilityText.casesTitle} extra={refresh}>
        {body}
      </SectionCard>
    </section>
  );

  if (failure !== undefined) {
    // A session that has ended is one condition about the whole session rather
    // than about this panel. The session surface reports it once; every panel
    // repeating the same sentence would tell an operator nothing new three
    // times over and bury the one message that is about this panel.
    if (failure.kind === 'unauthenticated') {
      return (
        <section aria-label={availabilityText.casesTitle} data-state="signed-out">
          <SectionCard title={availabilityText.casesTitle} />
        </section>
      );
    }
    return frame('failed', <FailureAlert failure={failure} />);
  }
  if (cases === undefined) {
    return frame('loading', <LoadingState />);
  }
  if (cases.length === 0) {
    return frame('empty', <EmptyState description={availabilityText.casesEmpty} />);
  }

  const toggle = (id: string): void => {
    setExpanded((keys) => (keys.includes(id) ? keys.filter((key) => key !== id) : [...keys, id]));
  };

  const columns: TableColumnsType<AvailabilityCase> = [
    {
      title: '原因',
      key: 'cause',
      render: (_: unknown, one: AvailabilityCase) => (
        <Button
          type="link"
          style={{ padding: 0, height: 'auto', whiteSpace: 'normal', textAlign: 'start' }}
          onClick={() => {
            toggle(one.id);
          }}
        >
          {causeLabel(one.causeCode)}
        </Button>
      ),
    },
    {
      title: '严重程度',
      dataIndex: 'severity',
      render: (severity: string) => (
        <span data-testid="case-severity">
          <CodeTag labels={LANE_LABELS} code={severity} colors={LANE_COLORS} />
        </span>
      ),
    },
    {
      title: '状态',
      dataIndex: 'state',
      render: (code: string) => {
        const state = presentCaseState(code);
        return (
          <Tooltip title={<span data-testid="case-state-explanation">{state.explanation}</span>}>
            <span data-testid="case-state">
              <CodeTag labels={CASE_STATE_LABELS} code={code} colors={CASE_STATE_COLORS} />
            </span>
          </Tooltip>
        );
      },
    },
    {
      title: '责任角色',
      dataIndex: 'accountableRoleCode',
      render: (role: string) => (
        <span data-testid="case-owner">
          <CodeTag labels={ROLE_LABELS} code={role} />
        </span>
      ),
    },
    {
      title: '行动截止',
      dataIndex: 'actionDueAt',
      render: (dueAt: string) => (
        <DueClock testId="case-action-due" dueAt={dueAt} now={now} pending="—" />
      ),
    },
    {
      title: '结果截止',
      dataIndex: 'outcomeDueAt',
      render: (dueAt: string | null) => (
        <DueClock testId="case-outcome-due" dueAt={dueAt} now={now} pending="记录行动后开始计时" />
      ),
    },
    {
      title: '重开次数',
      dataIndex: 'reopenCount',
      align: 'right',
      render: (count: number) => (
        <Typography.Text
          data-testid="case-reopens"
          {...(count > 0 ? { type: 'danger' as const } : {})}
        >
          {count}
        </Typography.Text>
      ),
    },
    {
      title: '升级级别',
      dataIndex: 'escalationLevel',
      align: 'right',
      render: (level: number) => <span data-testid="case-escalation">{level}</span>,
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: unknown, one: AvailabilityCase) => (
        <Space size={4}>
          <Button
            type="primary"
            size="small"
            onClick={() => {
              setActing(one);
            }}
          >
            记录行动
          </Button>
          <Button
            size="small"
            icon={<HistoryOutlined />}
            onClick={() => {
              toggle(one.id);
            }}
          >
            {expanded.includes(one.id) ? '收起历史' : '查看历史'}
          </Button>
        </Space>
      ),
    },
  ];

  return frame(
    'loaded',
    <>
      <div data-testid="availability-cases">
        <Table<AvailabilityCase>
          size="middle"
          rowKey="id"
          dataSource={[...cases]}
          columns={columns}
          pagination={false}
          scroll={{ x: 'max-content' }}
          onRow={(one) =>
            ({
              'data-testid': 'availability-case',
              'data-case-state': one.state,
              'data-case-tone': presentCaseState(one.state).tone,
              'data-severity': one.severity,
            }) as React.HTMLAttributes<HTMLElement>
          }
          expandable={{
            expandedRowKeys: [...expanded],
            onExpand: (_, one) => {
              toggle(one.id);
            },
            expandedRowRender: (one) => <CaseHistory context={context} governed={one} />,
          }}
        />
      </div>
      <ActionModal
        context={context}
        governed={acting}
        onClose={() => {
          setActing(undefined);
        }}
        onChanged={load}
      />
    </>,
  );
}

const DUE_COLORS: Readonly<Record<'overdue' | 'soon' | 'ok', TagColor>> = {
  overdue: 'error',
  soon: 'warning',
  ok: 'default',
};

const DUE_PREFIX: Readonly<Record<'overdue' | 'soon' | 'ok', string>> = {
  overdue: '已逾期',
  soon: '即将到期',
  ok: '截止',
};

/**
 * One deadline as a coloured tag.
 *
 * The two clocks of a case each get their own tag, never a merged badge: a late
 * action and a late outcome are different failures with different owners.
 */
function DueClock({
  testId,
  dueAt,
  now,
  pending,
}: {
  readonly testId: string;
  readonly dueAt: string | null;
  readonly now: Date;
  readonly pending: string;
}): React.JSX.Element {
  const tone = dueTone(dueAt, now);
  if (tone === 'none') {
    return (
      <Typography.Text type="secondary" data-testid={testId} data-due={tone}>
        {dueAt === null ? pending : <DateTime value={dueAt} />}
      </Typography.Text>
    );
  }
  return (
    <Tag color={DUE_COLORS[tone]} data-testid={testId} data-due={tone}>
      {DUE_PREFIX[tone]} <DateTime value={dueAt} />
    </Tag>
  );
}

/**
 * One case's history and acceptances, loaded when the row is opened.
 *
 * The reopen count is shown in the row rather than hidden behind the current
 * state. "This is the fourth time this month" is the question a reviewer
 * actually asks, and the history below is where it is answered.
 */
function CaseHistory({
  context,
  governed,
}: {
  readonly context: ConsoleRequest;
  readonly governed: AvailabilityCase;
}): React.JSX.Element {
  const [journal, setJournal] = useState<readonly CaseJournalEntry[] | undefined>(undefined);
  const [exceptions, setExceptions] = useState<readonly AcceptedException[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let active = true;
    void fetchCaseJournal(context, governed.id).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setJournal(outcome.value);
      } else {
        setJournal([]);
        setFailure(outcome.failure);
      }
    });
    void fetchCaseExceptions(context, governed.id).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setExceptions(outcome.value);
      } else {
        setExceptions([]);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, governed.id, reloadKey]);

  const timeline: NonNullable<TimelineProps['items']> = (journal ?? []).map((entry) => ({
    key: entry.sequenceNo,
    color: entry.verificationOutcome === null ? 'blue' : verificationColor(entry),
    title: <DateTime value={entry.occurredAt} />,
    content: (
      <Flex vertical gap={2} data-event-kind={entry.eventKind}>
        <Space size={4} wrap>
          <Typography.Text strong>
            <CodeTag labels={CASE_EVENT_LABELS} code={entry.eventKind} />
          </Typography.Text>
          {entry.fromState === null && entry.toState === null ? null : (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              <CodeTag labels={CASE_STATE_LABELS} code={entry.fromState} /> →{' '}
              <CodeTag labels={CASE_STATE_LABELS} code={entry.toState} />
            </Typography.Text>
          )}
          {entry.verificationOutcome === null ? null : (
            <CodeTag
              labels={VERIFICATION_OUTCOME_LABELS}
              code={entry.verificationOutcome}
              colors={VERIFICATION_OUTCOME_COLORS}
            />
          )}
        </Space>
        {entry.actionKind === null ? null : (
          <Typography.Text>{actionKindLabel(entry.actionKind)}</Typography.Text>
        )}
        <Typography.Text type="secondary">{entry.reason}</Typography.Text>
        {entry.evidenceReference === null ? null : (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            证据引用：
            <Typography.Text type="secondary" style={{ fontSize: 12 }} copyable>
              {entry.evidenceReference}
            </Typography.Text>
          </Typography.Text>
        )}
      </Flex>
    ),
  }));

  return (
    <Space orientation="vertical" size="small" style={{ width: '100%' }}>
      <Flex justify="space-between" align="center">
        <Typography.Text strong>处理历史</Typography.Text>
        <Button
          size="small"
          icon={<ReloadOutlined />}
          data-testid="case-load-journal"
          onClick={() => {
            setJournal(undefined);
            setExceptions(undefined);
            setFailure(undefined);
            setReloadKey((key) => key + 1);
          }}
        >
          刷新历史
        </Button>
      </Flex>
      {failure === undefined ? null : <FailureAlert failure={failure} />}
      {journal === undefined ? (
        <LoadingState rows={2} />
      ) : journal.length === 0 ? (
        failure === undefined ? (
          <EmptyState description="暂无处理记录" />
        ) : null
      ) : (
        <div data-testid="case-journal">
          <Timeline items={timeline} />
        </div>
      )}
      {exceptions === undefined || exceptions.length === 0 ? null : (
        <div data-testid="case-exceptions">
          <Typography.Text strong>风险接受</Typography.Text>
          <Table<AcceptedException>
            size="small"
            rowKey="id"
            pagination={false}
            dataSource={[...exceptions]}
            columns={EXCEPTION_COLUMNS}
            onRow={(accepted) =>
              ({ 'data-exception-state': accepted.state }) as React.HTMLAttributes<HTMLElement>
            }
            scroll={{ x: 'max-content' }}
          />
        </div>
      )}
      <TechnicalDetails>
        <Flex vertical gap={2}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            工单 ID：
            <Typography.Text type="secondary" style={{ fontSize: 12 }} copyable code>
              {governed.id}
            </Typography.Text>
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            风险卡片 ID：
            <Typography.Text type="secondary" style={{ fontSize: 12 }} copyable code>
              {governed.cardId}
            </Typography.Text>
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            子风险 ID：
            <Typography.Text type="secondary" style={{ fontSize: 12 }} copyable code>
              {governed.childId}
            </Typography.Text>
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            去重键：
            <Typography.Text type="secondary" style={{ fontSize: 12 }} copyable code>
              {governed.causeKey}
            </Typography.Text>
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            首次触发：
            <DateTime value={governed.firstActivatedAt} />
          </Typography.Text>
        </Flex>
      </TechnicalDetails>
    </Space>
  );
}

function verificationColor(entry: CaseJournalEntry): string {
  switch (entry.verificationOutcome) {
    case 'VERIFIED':
      return 'green';
    case 'FAILED':
    case 'REGRESSED':
      return 'red';
    case 'CONTINUING':
    case null:
    default:
      return 'gray';
  }
}

const EXCEPTION_COLUMNS: TableColumnsType<AcceptedException> = [
  {
    title: '状态',
    dataIndex: 'state',
    render: (state: string) => (
      <CodeTag labels={EXCEPTION_STATE_LABELS} code={state} colors={EXCEPTION_STATE_COLORS} />
    ),
  },
  {
    title: '原因',
    dataIndex: 'reasonCode',
    render: (code: string) => <CodeTag labels={EXCEPTION_REASON_LABELS} code={code} />,
  },
  {
    title: '所需权限',
    dataIndex: 'requiredAuthority',
    render: (authority: string) => (
      <span data-testid="exception-authority">
        <CodeTag labels={AUTHORITY_LEVEL_LABELS} code={authority} />
      </span>
    ),
  },
  {
    title: '生效',
    dataIndex: 'effectiveFrom',
    render: (at: string | null) => <DateTime value={at} />,
  },
  {
    title: '到期',
    dataIndex: 'expiresAt',
    render: (at: string | null) => <DateTime value={at} />,
  },
  {
    title: '复核',
    dataIndex: 'reviewAt',
    render: (at: string | null) => <DateTime value={at} />,
  },
];

interface ActionValues {
  readonly actionKind: string;
  readonly evidenceReference: string;
  readonly reason: string;
}

/**
 * Record accountable structured action.
 *
 * The evidence reference is required by the control itself rather than only by
 * the backend, so the refusal an operator meets is immediate and legible
 * instead of arriving as a validation failure from a server round trip.
 */
function ActionModal({
  context,
  governed,
  onClose,
  onChanged,
}: {
  readonly context: ConsoleRequest;
  readonly governed: AvailabilityCase | undefined;
  readonly onClose: () => void;
  readonly onChanged: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [form] = Form.useForm<ActionValues>();
  const [submitting, setSubmitting] = useState(false);
  const [problem, setProblem] = useState<ConsoleFailure | undefined>(undefined);

  const close = (): void => {
    setProblem(undefined);
    form.resetFields();
    onClose();
  };

  const submit = (values: ActionValues): void => {
    if (governed === undefined) {
      return;
    }
    setProblem(undefined);
    setSubmitting(true);
    void recordCaseAction(
      context,
      governed.id,
      values.actionKind,
      values.evidenceReference,
      values.reason,
    ).then((outcome) => {
      setSubmitting(false);
      if (outcome.ok) {
        void message.success('行动已记录');
        close();
        onChanged();
      } else {
        setProblem(outcome.failure);
      }
    });
  };

  return (
    <Modal
      open={governed !== undefined}
      title="记录行动"
      onCancel={close}
      destroyOnHidden
      footer={[
        <Button key="cancel" onClick={close}>
          {actions.cancel}
        </Button>,
        <Button
          key="submit"
          type="primary"
          loading={submitting}
          data-testid="action-submit"
          onClick={() => {
            form.submit();
          }}
        >
          记录行动
        </Button>,
      ]}
    >
      {governed === undefined ? null : (
        <Typography.Paragraph type="secondary">
          {causeLabel(governed.causeCode)}。请选择已采取的行动，并填写支撑该行动的证据引用。
        </Typography.Paragraph>
      )}
      <Form<ActionValues>
        form={form}
        layout="vertical"
        data-testid="case-action-form"
        initialValues={{ actionKind: ACTION_KINDS[0], evidenceReference: '', reason: '' }}
        onFinish={submit}
      >
        <Form.Item
          name="actionKind"
          label="已采取的行动"
          rules={[{ required: true, message: '请选择已采取的行动' }]}
        >
          <Select
            data-testid="action-kind"
            options={ACTION_KINDS.map((kind) => ({ value: kind, label: actionKindLabel(kind) }))}
          />
        </Form.Item>
        <Form.Item
          name="evidenceReference"
          label="证据引用"
          tooltip="支撑该行动的单据、链接或编号"
          rules={[{ required: true, whitespace: true, message: '请填写证据引用' }]}
        >
          <Input data-testid="action-evidence" />
        </Form.Item>
        <Form.Item
          name="reason"
          label="原因说明"
          rules={[{ required: true, whitespace: true, message: '请填写原因说明' }]}
        >
          <Input.TextArea data-testid="action-reason" autoSize={{ minRows: 2, maxRows: 5 }} />
        </Form.Item>
      </Form>
      {problem === undefined ? null : (
        <div data-testid="action-problem">
          <FailureAlert failure={problem} />
        </div>
      )}
    </Modal>
  );
}
