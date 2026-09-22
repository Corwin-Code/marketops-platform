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
import { CodeTag } from '../ui/CodeTag';
import { ConfirmButton } from '../ui/ConfirmButton';
import { DateTime } from '../ui/DateTime';
import { EmptyState } from '../ui/EmptyState';
import { FailureAlert } from '../ui/FailureAlert';
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

const EXCEPTION_ACTIONS = {
  ENDORSE: {
    route: 'endorsement',
    label: '背书例外',
    confirm: '确认为该例外背书？',
    danger: false,
  },
  APPROVE: {
    route: 'approval',
    label: '批准例外',
    confirm: '确认批准该例外？批准后在到期前接受该风险。',
    danger: false,
  },
  END: {
    route: 'end',
    label: '结束例外并重建决策',
    confirm: '确认结束该例外？相关决策将被重新计算。',
    danger: true,
  },
} as const;

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
  const [reason, setReason] = useState('');
  // The repair evidence and the exception evidence are two different
  // references; one shared field made typing in either fill the other.
  const [repairEvidence, setRepairEvidence] = useState('');
  const [exceptionEvidence, setExceptionEvidence] = useState('');
  const [assignee, setAssignee] = useState('');
  const [expires, setExpires] = useState<Dayjs | null>(null);
  const [reviewDue, setReviewDue] = useState<Dayjs | null>(null);
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
  async function perform(operation: Promise<ConsoleOutcome<Row>>, success: string): Promise<void> {
    setBusy(true);
    setFailure(undefined);
    const result = await operation;
    setBusy(false);
    if (result.ok) {
      void message.success(success);
      setReason('');
      setReview(undefined);
      reload();
    } else setFailure(result.failure);
  }
  const task =
    workflow.taskId === undefined ? undefined : `tasks/${encodeURIComponent(workflow.taskId)}`;
  const showReason =
    allowed.some((action) => action.startsWith('TASK_') || action === 'EXCEPTION_REQUEST') ||
    exceptions?.some((row) => allowedOf(row).length > 0) === true;
  const noReason = reason.trim().length === 0;
  const busyReason = '正在处理…';

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
              <ConfirmButton
                key={action}
                size="small"
                danger={EXCEPTION_ACTIONS[action].danger}
                title={EXCEPTION_ACTIONS[action].confirm}
                disabled={busy || noReason}
                disabledReason={busy ? busyReason : '请先填写操作理由'}
                onConfirm={() =>
                  perform(
                    advertisingControl(
                      context,
                      `exceptions/${encodeURIComponent(text(row.id))}/${EXCEPTION_ACTIONS[action].route}`,
                      { expectedVersion: row.version, reason },
                    ),
                    `「${EXCEPTION_ACTIONS[action].label}」已记录`,
                  )
                }
              >
                {EXCEPTION_ACTIONS[action].label}
              </ConfirmButton>
            ))}
        </Space>
      ),
    },
  ];

  return (
    <section aria-label="责任与例外">
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        {failure !== undefined && <FailureAlert failure={failure} />}
        {showReason && (
          <Form layout="vertical" style={{ maxWidth: 720 }}>
            <Form.Item label="操作理由" style={{ marginBottom: 0 }}>
              <Input.TextArea
                aria-label="操作理由"
                value={reason}
                maxLength={2000}
                showCount
                rows={2}
                onChange={(event) => {
                  setReason(event.target.value);
                }}
              />
            </Form.Item>
          </Form>
        )}
        {task !== undefined && (
          <Space orientation="vertical" size="small" style={{ width: '100%' }}>
            <Space size={[8, 8]} wrap>
              {allowed.includes('TASK_ACKNOWLEDGE') && (
                <Button
                  type="primary"
                  loading={busy}
                  onClick={() => {
                    void perform(
                      advertisingControl(context, `${task}/acknowledgement`),
                      '已确认接手',
                    );
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
                      void perform(
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
              {allowed.includes('TASK_REOPEN') && (
                <ConfirmButton
                  title="确认重新打开该任务？"
                  disabled={busy || noReason}
                  disabledReason={busy ? busyReason : '请先填写操作理由'}
                  onConfirm={() =>
                    perform(
                      advertisingControl(context, `${task}/reopen`, { escalated: false, reason }),
                      '已重新打开',
                    )
                  }
                >
                  重新打开任务
                </ConfirmButton>
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
            {allowed.includes('TASK_ASSIGN') && (
              <Form layout="vertical" style={{ maxWidth: 720 }}>
                <Form.Item label="指派负责人（符合条件的用户编号）" style={{ marginBottom: 0 }}>
                  <Space.Compact style={{ width: '100%' }}>
                    <Input
                      aria-label="负责人用户编号"
                      value={assignee}
                      onChange={(event) => {
                        setAssignee(event.target.value);
                      }}
                    />
                    <Button
                      disabled={
                        busy || assignee.trim().length === 0 || workflow.taskVersion === undefined
                      }
                      onClick={() => {
                        if (workflow.taskId !== undefined)
                          void perform(
                            actOnAdvertisingTask(context, workflow.taskId, 'assignment', {
                              assigneeUserId: assignee,
                              expectedVersion: workflow.taskVersion,
                            }),
                            '已指派负责人',
                          );
                      }}
                    >
                      指派
                    </Button>
                  </Space.Compact>
                </Form.Item>
              </Form>
            )}
            {allowed.includes('TASK_ACTION') && (
              <Form layout="vertical" style={{ maxWidth: 720 }}>
                <Form.Item
                  label="数据或映射修复的证据编号"
                  help="需同时填写操作理由"
                  style={{ marginBottom: 0 }}
                >
                  <Space.Compact style={{ width: '100%' }}>
                    <Input
                      aria-label="修复证据编号"
                      value={repairEvidence}
                      onChange={(event) => {
                        setRepairEvidence(event.target.value);
                      }}
                    />
                    <Button
                      type="primary"
                      disabled={busy || noReason || repairEvidence.trim().length === 0}
                      onClick={() => {
                        void perform(
                          advertisingControl(context, `${task}/action`, {
                            actionKind: 'DATA_OR_MAPPING_REPAIR',
                            evidenceReference: repairEvidence,
                            reason,
                          }),
                          '已记录修复',
                        );
                      }}
                    >
                      记录已完成的修复
                    </Button>
                  </Space.Compact>
                </Form.Item>
              </Form>
            )}
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

        <Typography.Title level={5} style={{ margin: 0 }}>
          限时风险接受（例外）
        </Typography.Title>
        {allowed.includes('EXCEPTION_REQUEST') && (
          <Form layout="vertical" style={{ maxWidth: 720 }}>
            <Flex gap={16} wrap>
              <Form.Item label={`例外到期时间（${STORE_TIMEZONE_LABEL}）`} required>
                <DatePicker
                  aria-label="例外到期时间"
                  showTime
                  value={expires}
                  onChange={(value: Dayjs | null) => {
                    setExpires(value);
                  }}
                />
              </Form.Item>
              <Form.Item label={`必须复核时间（${STORE_TIMEZONE_LABEL}）`} required>
                <DatePicker
                  aria-label="必须复核时间"
                  showTime
                  value={reviewDue}
                  onChange={(value: Dayjs | null) => {
                    setReviewDue(value);
                  }}
                />
              </Form.Item>
            </Flex>
            <Form.Item label="例外证据引用" required>
              <Input
                aria-label="例外证据引用"
                value={exceptionEvidence}
                onChange={(event) => {
                  setExceptionEvidence(event.target.value);
                }}
              />
            </Form.Item>
            <ConfirmButton
              type="primary"
              title="确认为该事项申请限时例外？"
              description="例外需要背书与批准后才会生效，到期后自动失效。"
              disabled={
                busy ||
                noReason ||
                !exceptionEvidence.trim() ||
                expires === null ||
                reviewDue === null
              }
              disabledReason={busy ? busyReason : '请填写操作理由、到期时间、复核时间和证据引用'}
              onConfirm={() => {
                if (expires === null || reviewDue === null) return;
                return perform(
                  advertisingControl(
                    context,
                    `cases/${encodeURIComponent(workflow.caseId)}/exceptions`,
                    {
                      expiresAt: storeLocalToIso(expires),
                      reviewDueAt: storeLocalToIso(reviewDue),
                      reason,
                      evidenceReference: exceptionEvidence,
                    },
                  ),
                  '已提交例外申请',
                );
              }}
            >
              申请事项例外
            </ConfirmButton>
          </Form>
        )}
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
