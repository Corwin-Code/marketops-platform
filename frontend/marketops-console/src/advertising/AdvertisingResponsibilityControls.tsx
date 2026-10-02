import {
  App,
  Button,
  DatePicker,
  Drawer,
  Flex,
  Form,
  Input,
  Space,
  Table,
  Timeline,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import type { Dayjs } from 'dayjs';
import { useEffect, useState } from 'react';
import {
  actOnAdvertisingTask,
  advertisingControl,
  fetchAdvertisingJournal,
  fetchAdvertisingExceptions,
  fetchAdvertisingExceptionEvidence,
} from '../api/console';
import type { ConsoleFailure, ConsoleOutcome, ConsoleRequest } from '../api/console';
import type { AdvertisingWorkflow } from '../api/advertising';
import { STORE_TIMEZONE_LABEL, storeLocalToIso } from '../format';
import {
  DISCLOSURE_LABELS,
  EXCEPTION_STATE_COLORS,
  EXCEPTION_STATE_LABELS,
  JOURNAL_EVENT_LABELS,
  ROLE_LABELS,
} from '../i18n/zh/advertising';
import { dialog } from '../i18n/zh/common';
import { ActionModal } from '../ui/ActionModal';
import type { SubmitOutcome } from '../ui/ActionModal';
import { CodeTag } from '../ui/CodeTag';
import { DateTime } from '../ui/DateTime';
import { EmptyState } from '../ui/EmptyState';
import { FailureAlert } from '../ui/FailureAlert';
import { idRules } from '../ui/PickOrType';
import { AdvertisingEvidenceDetails } from './AdvertisingEvidenceDetails';
import { IdText, field, fieldText } from './shared';
import type { AdRow } from './shared';

type Row = AdRow;
const text = (value: unknown): string =>
  typeof value === 'string' ? value : typeof value === 'number' ? value.toString() : 'UNRESOLVED';
const allowedOf = (row: Row): readonly string[] =>
  Array.isArray(row.allowedActions)
    ? row.allowedActions.filter((item): item is string => typeof item === 'string')
    : [];

/** The longest reason or reference the console sends. */
const TEXT_LIMIT = 2000;
const EVIDENCE_LIMIT = 512;
const PICKER_FORMAT = 'YYYY-MM-DD HH:mm';

const EXCEPTION_ACTIONS = {
  ENDORSE: {
    route: 'endorsement',
    label: '背书例外',
    title: '为例外背书',
    consequence: '背书人必须独立于申请人；背书后还需批准，例外才会生效。',
    danger: false,
  },
  APPROVE: {
    route: 'approval',
    label: '批准例外',
    title: '批准例外',
    consequence: '批准后，在到期前接受该风险。',
    danger: false,
  },
  END: {
    route: 'end',
    label: '结束例外并重建决策',
    title: '结束例外',
    consequence: '结束后不再接受该风险，相关决策将被重新计算。',
    danger: true,
  },
} as const;

interface ReasonValues {
  readonly reason?: string;
}

interface RepairValues {
  readonly evidenceReference?: string;
  readonly reason?: string;
}

interface AssignValues {
  readonly assigneeUserId?: string;
}

interface ExceptionValues {
  readonly expiresAt?: Dayjs | null;
  readonly reviewDueAt?: Dayjs | null;
  readonly evidenceReference?: string;
  readonly reason?: string;
}

/** Whether a store-local time the operator picked is still ahead of now. */
function isFuture(value: Dayjs): boolean {
  return new Date(storeLocalToIso(value)).getTime() > Date.now();
}

/** The reason a dialog records; it takes the focus only when it is the dialog's first field. */
function ReasonField({
  label,
  first = true,
}: {
  readonly label: string;
  readonly first?: boolean;
}): React.JSX.Element {
  return (
    <Form.Item
      name="reason"
      label={label}
      rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
    >
      <Input.TextArea
        rows={3}
        maxLength={TEXT_LIMIT}
        showCount
        placeholder={dialog.reasonPlaceholder}
        autoFocus={first}
      />
    </Form.Item>
  );
}

export function AdvertisingResponsibilityControls({
  context,
  workflow,
  reload,
}: {
  readonly context: ConsoleRequest;
  readonly workflow: AdvertisingWorkflow;
  readonly timezone: string | undefined;
  readonly reload: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [journal, setJournal] = useState<readonly Row[]>();
  const [exceptions, setExceptions] = useState<readonly Row[]>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [busy, setBusy] = useState(false);
  const [review, setReview] = useState<Row>();
  const allowed = workflow.allowedActions;
  useEffect(() => {
    let active = true;
    void fetchAdvertisingExceptions(context, workflow.caseId).then((result) => {
      if (active && result.ok) setExceptions(result.value);
    });
    return () => {
      active = false;
    };
  }, [context, workflow]);

  /** A dialog's action: a failure stays in the dialog, success reloads the authority. */
  async function submit(
    operation: Promise<ConsoleOutcome<Row>>,
    success: string,
  ): Promise<SubmitOutcome> {
    const result = await operation;
    if (!result.ok) return result.failure;
    void message.success(success);
    setFailure(undefined);
    setReview(undefined);
    reload();
    return undefined;
  }

  /** A one-click action without inputs; its failure is shown above the controls. */
  async function run(operation: Promise<ConsoleOutcome<Row>>, success: string): Promise<void> {
    setBusy(true);
    setFailure(undefined);
    const result = await operation;
    setBusy(false);
    if (result.ok) {
      void message.success(success);
      reload();
    } else setFailure(result.failure);
  }

  const task =
    workflow.taskId === undefined ? undefined : `tasks/${encodeURIComponent(workflow.taskId)}`;
  const versionMissing =
    workflow.taskVersion === undefined ? '任务版本未确定，请刷新后重试' : undefined;

  const exceptionColumns: TableColumnsType<Row> = [
    {
      title: '状态',
      key: 'state',
      render: (_, row) => (
        <CodeTag
          labels={EXCEPTION_STATE_LABELS}
          code={field(row, 'state')}
          colors={EXCEPTION_STATE_COLORS}
        />
      ),
    },
    {
      title: '版本',
      key: 'version',
      render: (_, row) => fieldText(row, 'version') ?? '—',
    },
    {
      title: '到期时间',
      key: 'expiresAt',
      render: (_, row) => <DateTime value={field(row, 'expiresAt')} />,
    },
    {
      title: '操作',
      key: 'actions',
      render: (_, row) => (
        <Space size={[8, 8]} wrap>
          <Button
            type="link"
            style={{ padding: 0 }}
            onClick={() => {
              void fetchAdvertisingExceptionEvidence(context, text(row.id)).then((result) => {
                if (result.ok) setReview(result.value);
                else setFailure(result.failure);
              });
            }}
          >
            查看冻结的例外证据
          </Button>
          {(['ENDORSE', 'APPROVE', 'END'] as const)
            .filter((action) => allowedOf(row).includes(action))
            .map((action) => (
              <ActionModal<ReasonValues>
                key={action}
                trigger={{
                  label: EXCEPTION_ACTIONS[action].label,
                  size: 'small',
                  danger: EXCEPTION_ACTIONS[action].danger,
                }}
                title={EXCEPTION_ACTIONS[action].title}
                consequence={EXCEPTION_ACTIONS[action].consequence}
                summary={
                  <Space size={6} wrap>
                    <CodeTag
                      labels={EXCEPTION_STATE_LABELS}
                      code={field(row, 'state')}
                      colors={EXCEPTION_STATE_COLORS}
                    />
                    <Typography.Text type="secondary">到期</Typography.Text>
                    <DateTime value={field(row, 'expiresAt')} />
                  </Space>
                }
                okText={EXCEPTION_ACTIONS[action].label}
                danger={EXCEPTION_ACTIONS[action].danger}
                onSubmit={(values) =>
                  submit(
                    advertisingControl(
                      context,
                      `exceptions/${encodeURIComponent(text(row.id))}/${EXCEPTION_ACTIONS[action].route}`,
                      { expectedVersion: row.version, reason: (values.reason ?? '').trim() },
                    ),
                    `「${EXCEPTION_ACTIONS[action].label}」已记录`,
                  )
                }
              >
                <ReasonField label="操作理由" />
              </ActionModal>
            ))}
        </Space>
      ),
    },
  ];

  return (
    <section aria-label="责任与例外">
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        {failure !== undefined && <FailureAlert failure={failure} />}
        {task !== undefined && (
          <Space orientation="vertical" size="small" style={{ width: '100%' }}>
            <Space size={[8, 8]} wrap>
              {allowed.includes('TASK_ACKNOWLEDGE') && (
                <Button
                  type="primary"
                  loading={busy}
                  onClick={() => {
                    void run(advertisingControl(context, `${task}/acknowledgement`), '已确认接手');
                  }}
                >
                  确认接手
                </Button>
              )}
              {allowed.includes('TASK_START') && (
                <Button
                  disabled={busy || workflow.taskVersion === undefined}
                  onClick={() => {
                    if (workflow.taskId !== undefined)
                      void run(
                        actOnAdvertisingTask(context, workflow.taskId, 'start', {
                          expectedVersion: workflow.taskVersion,
                        }),
                        '已开始处理',
                      );
                  }}
                >
                  开始处理
                </Button>
              )}
              {allowed.includes('TASK_ASSIGN') && (
                <ActionModal<AssignValues>
                  trigger={{
                    label: '指派负责人',
                    disabled: versionMissing !== undefined,
                    disabledReason: versionMissing,
                  }}
                  title="指派负责人"
                  consequence="指派后由该用户负责处理此事项。负责人必须符合该任务的角色与范围，否则会被拒绝。"
                  okText="指派"
                  onSubmit={(values) => {
                    if (workflow.taskId === undefined) return Promise.resolve(undefined);
                    return submit(
                      actOnAdvertisingTask(context, workflow.taskId, 'assignment', {
                        assigneeUserId: (values.assigneeUserId ?? '').trim(),
                        expectedVersion: workflow.taskVersion,
                      }),
                      '已指派负责人',
                    );
                  }}
                >
                  <Form.Item
                    name="assigneeUserId"
                    label="负责人用户编号"
                    extra="暂时没有可选的人员列表，请填写对方的用户编号。"
                    rules={idRules('负责人用户编号')}
                  >
                    <Input placeholder="输入编号（UUID）" autoFocus />
                  </Form.Item>
                </ActionModal>
              )}
              {allowed.includes('TASK_ACTION') && (
                <ActionModal<RepairValues>
                  trigger={{ label: '记录已完成的修复', type: 'primary' }}
                  title="记录已完成的数据或映射修复"
                  consequence="记录一次已经完成的数据或映射修复，作为该任务的处理证据。"
                  okText="记录修复"
                  onSubmit={(values) =>
                    submit(
                      advertisingControl(context, `${task}/action`, {
                        actionKind: 'DATA_OR_MAPPING_REPAIR',
                        evidenceReference: (values.evidenceReference ?? '').trim(),
                        reason: (values.reason ?? '').trim(),
                      }),
                      '已记录修复',
                    )
                  }
                >
                  <Form.Item
                    name="evidenceReference"
                    label="修复证据编号"
                    rules={[{ required: true, whitespace: true, message: '请填写修复证据编号' }]}
                  >
                    <Input maxLength={EVIDENCE_LIMIT} autoFocus />
                  </Form.Item>
                  <ReasonField label="操作理由" first={false} />
                </ActionModal>
              )}
              {allowed.includes('TASK_REOPEN') && (
                <ActionModal<ReasonValues>
                  trigger={{ label: '重新打开任务' }}
                  title="重新打开任务"
                  consequence="任务回到待处理，不会升级。"
                  okText="重新打开"
                  onSubmit={(values) =>
                    submit(
                      advertisingControl(context, `${task}/reopen`, {
                        escalated: false,
                        reason: (values.reason ?? '').trim(),
                      }),
                      '已重新打开',
                    )
                  }
                >
                  <ReasonField label="操作理由" />
                </ActionModal>
              )}
              <Button
                disabled={busy}
                onClick={() => {
                  if (workflow.taskId !== undefined)
                    void fetchAdvertisingJournal(context, workflow.taskId).then((result) => {
                      if (result.ok) setJournal(result.value);
                      else setFailure(result.failure);
                    });
                }}
              >
                查看责任日志
              </Button>
            </Space>
            {journal !== undefined &&
              (journal.length === 0 ? (
                <EmptyState description="责任日志暂无记录" />
              ) : (
                <Timeline
                  aria-label="责任日志"
                  items={journal.map((row, index) => ({
                    key: text(row.id) + index.toString(),
                    content: (
                      <Flex vertical gap={2}>
                        <Space size={6} wrap>
                          <CodeTag labels={JOURNAL_EVENT_LABELS} code={field(row, 'eventKind')} />
                          <CodeTag labels={ROLE_LABELS} code={field(row, 'actorRoleCode')} />
                          <DateTime value={field(row, 'occurredAt')} />
                          {field(row, 'disclosureState') === 'MASKED' && (
                            <CodeTag labels={DISCLOSURE_LABELS} code="MASKED" />
                          )}
                        </Space>
                        <IdText value={field(row, 'actorUserId')} prefix="操作人" />
                        {typeof row.reason === 'string' && (
                          <Typography.Text>{row.reason}</Typography.Text>
                        )}
                      </Flex>
                    ),
                  }))}
                />
              ))}
          </Space>
        )}

        <Flex justify="space-between" align="center" wrap gap={8}>
          <Typography.Title level={5} style={{ margin: 0 }}>
            限时风险接受（例外）
          </Typography.Title>
          {allowed.includes('EXCEPTION_REQUEST') && (
            <ActionModal<ExceptionValues>
              trigger={{ label: '申请事项例外', type: 'primary' }}
              title="申请限时例外"
              consequence="例外需要背书与批准后才会生效，到期后自动失效。"
              okText="提交申请"
              width={560}
              onSubmit={(values) => {
                const { expiresAt, reviewDueAt } = values;
                if (!expiresAt || !reviewDueAt) return Promise.resolve(undefined);
                return submit(
                  advertisingControl(
                    context,
                    `cases/${encodeURIComponent(workflow.caseId)}/exceptions`,
                    {
                      expiresAt: storeLocalToIso(expiresAt),
                      reviewDueAt: storeLocalToIso(reviewDueAt),
                      reason: (values.reason ?? '').trim(),
                      evidenceReference: (values.evidenceReference ?? '').trim(),
                    },
                  ),
                  '已提交例外申请',
                );
              }}
            >
              <Flex gap={12} wrap>
                <Form.Item
                  name="expiresAt"
                  label={`例外到期时间（${STORE_TIMEZONE_LABEL}）`}
                  rules={[
                    { required: true, message: '请选择到期时间' },
                    {
                      validator: (_: unknown, value: Dayjs | null | undefined) =>
                        !value || isFuture(value)
                          ? Promise.resolve()
                          : Promise.reject(new Error('到期时间必须晚于现在')),
                    },
                  ]}
                >
                  <DatePicker showTime={{ format: 'HH:mm' }} format={PICKER_FORMAT} />
                </Form.Item>
                <Form.Item
                  name="reviewDueAt"
                  label={`必须复核时间（${STORE_TIMEZONE_LABEL}）`}
                  dependencies={['expiresAt']}
                  rules={[
                    { required: true, message: '请选择复核时间' },
                    ({ getFieldValue }) => ({
                      validator: (_: unknown, value: Dayjs | null | undefined) => {
                        if (!value) return Promise.resolve();
                        if (!isFuture(value))
                          return Promise.reject(new Error('复核时间必须晚于现在'));
                        const until = getFieldValue('expiresAt') as Dayjs | null | undefined;
                        return until && value.isAfter(until)
                          ? Promise.reject(new Error('复核时间不能晚于到期时间'))
                          : Promise.resolve();
                      },
                    }),
                  ]}
                >
                  <DatePicker showTime={{ format: 'HH:mm' }} format={PICKER_FORMAT} />
                </Form.Item>
              </Flex>
              <Form.Item
                name="evidenceReference"
                label="例外证据引用"
                rules={[{ required: true, whitespace: true, message: '请填写例外证据引用' }]}
              >
                <Input maxLength={EVIDENCE_LIMIT} />
              </Form.Item>
              <ReasonField label="申请理由" first={false} />
            </ActionModal>
          )}
        </Flex>
        {exceptions !== undefined && exceptions.length > 0 && (
          <Table<Row>
            size="middle"
            rowKey={(row) => text(row.id)}
            columns={exceptionColumns}
            dataSource={[...exceptions]}
            pagination={false}
            scroll={{ x: 'max-content' }}
          />
        )}
        {exceptions?.length === 0 && (
          <Typography.Text type="secondary">该事项暂无例外。</Typography.Text>
        )}
      </Space>
      <Drawer
        title="冻结的例外证据"
        open={review !== undefined}
        size="large"
        onClose={() => {
          setReview(undefined);
        }}
      >
        {review !== undefined && (
          <AdvertisingEvidenceDetails value={review} label="冻结的例外证据" inline />
        )}
      </Drawer>
    </section>
  );
}
