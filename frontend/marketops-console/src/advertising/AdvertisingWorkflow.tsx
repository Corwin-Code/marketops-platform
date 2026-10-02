import {
  App,
  Button,
  Card,
  Descriptions,
  Divider,
  Drawer,
  Flex,
  Space,
  Steps,
  Typography,
} from 'antd';
import type { DescriptionsProps, StepsProps } from 'antd';
import { FileSearchOutlined, ReloadOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import {
  actOnAdvertisingCandidate,
  fetchAdvertisingWorkflow,
  previewAdvertisingCandidate,
} from '../api/console';
import type {
  AdvertisingCandidateAction as AdvertisingCandidateActionCode,
  AdvertisingDecisionPreview,
  ConsoleFailure,
  ConsoleRequest,
} from '../api/console';
import type {
  AdvertisingWorkflow as Workflow,
  AdvertisingWorkflowCandidate,
} from '../api/advertising';
import { actions as commonActions } from '../i18n';
import {
  ACTION_CLOCK_COLORS,
  ACTION_CLOCK_LABELS,
  CANDIDATE_BASIS_LABELS,
  CANDIDATE_STATE_COLORS,
  CANDIDATE_STATE_LABELS,
  CANDIDATE_STEPS,
  COVERAGE_COLORS,
  COVERAGE_LABELS,
  DISPOSITION_COLORS,
  DISPOSITION_LABELS,
  ROLE_LABELS,
  TASK_STATE_COLORS,
  TASK_STATE_LABELS,
  TIMELINESS_COLORS,
  TIMELINESS_LABELS,
} from '../i18n/zh/advertising';
import type { SubmitOutcome } from '../ui/ActionModal';
import { CodeTag } from '../ui/CodeTag';
import { EmptyState } from '../ui/EmptyState';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import {
  AdvertisingCandidateAction,
  BidChange,
  candidateActionLabel,
} from './AdvertisingCandidateActions';
import { AdvertisingCommandTimeline } from './AdvertisingCommandTimeline';
import { AdvertisingEvidenceDetails } from './AdvertisingEvidenceDetails';
import { AdvertisingResponsibilityControls } from './AdvertisingResponsibilityControls';
import { AdvertisingTimestamp } from './AdvertisingTimestamp';
import { AbsentValue, IdText, ReasonTags } from './shared';

function candidateActions(
  candidate: AdvertisingWorkflowCandidate,
  allowed: readonly string[],
): AdvertisingCandidateActionCode[] {
  const result: AdvertisingCandidateActionCode[] = [];
  if (candidate.makerUserId === undefined && candidate.state === 'DRAFT')
    result.push('SELECT_CANDIDATE');
  if (candidate.state === 'DRAFT') result.push('REJECT_CANDIDATE');
  if (
    candidate.state === 'VALIDATED' &&
    candidate.makerUserId !== undefined &&
    candidate.endorserUserId === undefined
  )
    result.push('ENDORSE');
  if (candidate.state === 'READY_FOR_REVIEW' && candidate.endorserUserId !== undefined)
    result.push('APPROVE');
  if (candidate.state === 'APPROVED' && candidate.commandId === undefined)
    result.push('CREATE_COMMAND');
  return result.filter((action) => allowed.includes(action));
}

const EXECUTION_STATES = new Set([
  'COMMAND_CREATED',
  'EXECUTION_TRACKING',
  'OUTCOME_OBSERVATION',
  'CLOSED',
]);
const STOPPED_STATES = new Set(['REJECTED', 'EXPIRED', 'CANCELLED']);

/** Where a candidate stands in its lifecycle, for display only. */
function candidateStep(candidate: AdvertisingWorkflowCandidate): {
  readonly current: number;
  readonly status: NonNullable<StepsProps['status']>;
} {
  let current = 0;
  if (candidate.commandId !== undefined || EXECUTION_STATES.has(candidate.state)) current = 4;
  else if (candidate.state === 'APPROVED' || candidate.state === 'POLICY_AUTHORIZED') current = 3;
  else if (candidate.state === 'READY_FOR_REVIEW') current = 2;
  else if (candidate.state === 'VALIDATED') current = 1;
  const status = STOPPED_STATES.has(candidate.state)
    ? 'error'
    : candidate.state === 'CLOSED'
      ? 'finish'
      : 'process';
  return { current, status };
}

const KNOWN_STAFFED_COVERAGE = new Set([
  'IN_COVERAGE',
  'OUT_OF_COVERAGE',
  'OUT_OF_COVERAGE_ACTIVE_HARM',
  'ACCEPTED_EXCEPTION_ACTIVE',
]);

function responseInstant(value: unknown): string | undefined {
  return typeof value === 'string' &&
    value.trim().length > 0 &&
    Number.isFinite(new Date(value).getTime())
    ? value
    : undefined;
}

/** Display the server snapshot; never derive a staffed deadline from browser time. */
function AdvertisingResponseTiming({ slo }: { readonly slo: Workflow['slo'] }): React.JSX.Element {
  const current = Array.isArray(slo) ? undefined : slo;
  const coverage = current?.coverageState;
  const knownCoverage = typeof coverage === 'string' && KNOWN_STAFFED_COVERAGE.has(coverage);
  const ackDue = responseInstant(current?.acknowledgementDueAt);
  const actionDue = responseInstant(current?.actionDueAt);
  const acknowledgedAt = responseInstant(current?.acknowledgedAt);
  const actedAt = responseInstant(current?.firstAttributableActionAt);
  const timeliness = (breached: unknown, due: string | undefined): string =>
    breached === true
      ? 'BREACHED'
      : breached === false && knownCoverage && due !== undefined
        ? 'NOT_BREACHED'
        : 'UNRESOLVED';
  const completion = (
    value: string | undefined,
    raw: unknown,
    due: string | undefined,
  ): React.JSX.Element =>
    value !== undefined ? (
      <Space size={4}>
        <Typography.Text>已记录于</Typography.Text>
        <AdvertisingTimestamp value={value} />
      </Space>
    ) : raw === null && knownCoverage && due !== undefined ? (
      <Typography.Text type="warning">截至本次响应尚未记录</Typography.Text>
    ) : (
      <AbsentValue label="未确定" />
    );
  let actionClock = 'UNRESOLVED';
  if (actedAt !== undefined) actionClock = 'STAGE_COMPLETED';
  else if (current?.actionPaused === true) actionClock = 'PAUSED';
  else if (
    knownCoverage &&
    actionDue !== undefined &&
    typeof current?.actionBreached === 'boolean' &&
    current.actionPaused === false &&
    current.firstAttributableActionAt === null
  ) {
    if (coverage === 'IN_COVERAGE') actionClock = 'ACTIVE';
    else if (coverage === 'OUT_OF_COVERAGE' || coverage === 'OUT_OF_COVERAGE_ACTIVE_HARM')
      actionClock = 'AWAITING_COVERAGE';
  }
  const age = current?.wallClockExposureAgeSeconds;

  const items: DescriptionsProps['items'] = [
    {
      key: 'ack',
      label: <span aria-label="确认完成情况">确认完成</span>,
      children: completion(acknowledgedAt, current?.acknowledgedAt, ackDue),
    },
    {
      key: 'act',
      label: <span aria-label="处理阶段完成情况">处理阶段完成</span>,
      children: completion(actedAt, current?.firstAttributableActionAt, actionDue),
    },
    {
      key: 'ackTimeliness',
      label: <span aria-label="确认时效">确认时效</span>,
      children: (
        <CodeTag
          labels={TIMELINESS_LABELS}
          code={timeliness(current?.acknowledgementBreached, ackDue)}
          colors={TIMELINESS_COLORS}
        />
      ),
    },
    {
      key: 'actTimeliness',
      label: <span aria-label="处理时效">处理时效</span>,
      children: (
        <CodeTag
          labels={TIMELINESS_LABELS}
          code={timeliness(current?.actionBreached, actionDue)}
          colors={TIMELINESS_COLORS}
        />
      ),
    },
    {
      key: 'clock',
      label: <span aria-label="处理计时状态">处理计时</span>,
      children: (
        <CodeTag labels={ACTION_CLOCK_LABELS} code={actionClock} colors={ACTION_CLOCK_COLORS} />
      ),
    },
    {
      key: 'age',
      label: '暴露时长',
      children:
        typeof age === 'number' && Number.isSafeInteger(age) && age >= 0 ? (
          `${String(age)} 秒`
        ) : (
          <AbsentValue label="未确定" />
        ),
    },
  ];

  return (
    <div role="group" aria-label="广告响应时效">
      <Descriptions bordered size="small" column={{ xs: 1, md: 2, xl: 3 }} items={items} />
      {current?.actionPaused === true && (
        <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
          当前处理计时已暂停，暴露时长仍在累计。
        </Typography.Paragraph>
      )}
    </div>
  );
}

/** Each interaction round-trips through the existing workflow authority. */
export function AdvertisingWorkflow({
  context,
  caseId,
  timezone,
}: {
  readonly context: ConsoleRequest;
  readonly caseId: string;
  readonly timezone: string | undefined;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [workflow, setWorkflow] = useState<Workflow>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const [preview, setPreview] = useState<AdvertisingDecisionPreview>();

  useEffect(() => {
    let active = true;
    void fetchAdvertisingWorkflow(context, caseId).then((result) => {
      if (!active) return;
      if (result.ok) {
        setWorkflow(result.value);
        setFailure(undefined);
      } else {
        setWorkflow(undefined);
        setFailure(result.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, caseId, revision]);

  async function act(
    candidate: AdvertisingWorkflowCandidate,
    action: AdvertisingCandidateActionCode,
    reason: string,
  ): Promise<SubmitOutcome> {
    const result = await actOnAdvertisingCandidate(
      context,
      caseId,
      candidate.id,
      candidate.recommendationId,
      action,
      candidate.version,
      reason,
    );
    if (!result.ok) return result.failure;
    void message.success(`「${candidateActionLabel(action)}」已记录，已重新加载当前授权`);
    setRevision((value) => value + 1);
    return undefined;
  }

  async function review(candidate: AdvertisingWorkflowCandidate): Promise<void> {
    setBusy(true);
    const result = await previewAdvertisingCandidate(context, candidate.recommendationId);
    setBusy(false);
    if (result.ok) {
      setPreview(result.value);
      setFailure(undefined);
    } else {
      setFailure(result.failure);
    }
  }

  const liveTime = (key: string, legacy: string | undefined): string | undefined =>
    workflow?.slo === undefined
      ? legacy
      : typeof workflow.slo[key] === 'string'
        ? workflow.slo[key]
        : undefined;

  const reload = (): void => {
    setRevision((value) => value + 1);
  };

  const canReview =
    workflow?.allowedActions.some((action) => action === 'ENDORSE' || action === 'APPROVE') ===
    true;

  return (
    <section aria-label="广告工作流" data-state={workflow === undefined ? 'loading' : 'loaded'}>
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <Flex justify="space-between" align="center" wrap gap={8}>
          <Typography.Title level={5} style={{ margin: 0 }}>
            处理与责任
          </Typography.Title>
          <Button icon={<ReloadOutlined />} onClick={reload}>
            {commonActions.refresh}
          </Button>
        </Flex>
        {failure !== undefined && <FailureAlert failure={failure} />}
        {workflow === undefined ? (
          failure === undefined ? (
            <LoadingState rows={4} />
          ) : (
            <Typography.Text type="secondary">服务响应前无法获取工作流授权。</Typography.Text>
          )
        ) : (
          <>
            <Descriptions
              bordered
              size="small"
              column={{ xs: 1, md: 2, xl: 3 }}
              items={[
                {
                  key: 'disposition',
                  label: '处置状态',
                  children: (
                    <CodeTag
                      labels={DISPOSITION_LABELS}
                      code={workflow.operatingDisposition}
                      colors={DISPOSITION_COLORS}
                    />
                  ),
                },
                {
                  key: 'task',
                  label: '任务',
                  children: (
                    <Space size={4} wrap>
                      <CodeTag
                        labels={TASK_STATE_LABELS}
                        code={workflow.taskState}
                        colors={TASK_STATE_COLORS}
                      />
                      {workflow.taskId === undefined ? (
                        <AbsentValue label="任务未确定" />
                      ) : (
                        <IdText value={workflow.taskId} />
                      )}
                    </Space>
                  ),
                },
                {
                  key: 'role',
                  label: '负责角色',
                  children: <CodeTag labels={ROLE_LABELS} code={workflow.accountableRole} />,
                },
                {
                  key: 'raised',
                  label: '首次提出',
                  children: (
                    <AdvertisingTimestamp value={workflow.firstRaisedAt} timezone={timezone} />
                  ),
                },
                {
                  key: 'ackDue',
                  label: '确认截止',
                  children: (
                    <AdvertisingTimestamp
                      value={liveTime('acknowledgementDueAt', workflow.acknowledgementDueAt)}
                      timezone={timezone}
                    />
                  ),
                },
                {
                  key: 'actDue',
                  label: '处理截止',
                  children: (
                    <AdvertisingTimestamp
                      value={liveTime('actionDueAt', workflow.actionDueAt)}
                      timezone={timezone}
                    />
                  ),
                },
                {
                  key: 'escalateDue',
                  label: '升级截止',
                  children: (
                    <AdvertisingTimestamp
                      value={liveTime('escalationDueAt', workflow.escalationDueAt)}
                      timezone={timezone}
                    />
                  ),
                },
                {
                  key: 'coverage',
                  label: '值班覆盖',
                  children: (
                    <CodeTag
                      labels={COVERAGE_LABELS}
                      code={liveTime('coverageState', workflow.coverageState) ?? 'UNRESOLVED'}
                      colors={COVERAGE_COLORS}
                    />
                  ),
                },
                {
                  key: 'nextStaffed',
                  label: '下次值班响应',
                  children: (
                    <AdvertisingTimestamp
                      value={liveTime('nextStaffedResponseAt', workflow.nextStaffedResponseAt)}
                      timezone={timezone}
                    />
                  ),
                },
              ]}
            />
            <AdvertisingResponseTiming slo={workflow.slo} />

            <Divider titlePlacement="start" style={{ margin: '8px 0' }}>
              责任与例外
            </Divider>
            <AdvertisingResponsibilityControls
              context={context}
              workflow={workflow}
              timezone={timezone}
              reload={reload}
            />

            <Divider titlePlacement="start" style={{ margin: '8px 0' }}>
              出价候选
            </Divider>
            {workflow.candidates.length === 0 ? (
              <EmptyState description="尚未形成有限的出价候选。缺失的证据不能变成手工输入的目标值。" />
            ) : (
              <Flex vertical gap={12}>
                {workflow.candidates.map((candidate) => {
                  const step = candidateStep(candidate);
                  const available = candidateActions(candidate, workflow.allowedActions);
                  return (
                    <div key={candidate.id} data-candidate-id={candidate.id}>
                      <Card
                        size="small"
                        title={
                          <Space size={6} wrap>
                            <span>候选 {candidate.ordinal}</span>
                            <CodeTag labels={CANDIDATE_BASIS_LABELS} code={candidate.basis} />
                            <CodeTag
                              labels={CANDIDATE_STATE_LABELS}
                              code={candidate.state}
                              colors={CANDIDATE_STATE_COLORS}
                            />
                          </Space>
                        }
                        extra={
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            版本 {candidate.version}
                          </Typography.Text>
                        }
                      >
                        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
                          <Steps
                            size="small"
                            current={step.current}
                            status={step.status}
                            items={CANDIDATE_STEPS.map((item) => ({ title: item.title }))}
                          />
                          <BidChange candidate={candidate} />
                          <Space size={[8, 8]} wrap>
                            {available.map((action) => (
                              <AdvertisingCandidateAction
                                key={action}
                                context={context}
                                candidate={candidate}
                                action={action}
                                onAct={(reason) => act(candidate, action, reason)}
                              />
                            ))}
                            {canReview && (
                              <Button
                                icon={<FileSearchOutlined />}
                                disabled={busy}
                                onClick={() => {
                                  void review(candidate);
                                }}
                              >
                                查看完整决策证据
                              </Button>
                            )}
                          </Space>
                          {candidate.commandId !== undefined && (
                            <AdvertisingCommandTimeline
                              context={context}
                              commandId={candidate.commandId}
                              timezone={timezone}
                            />
                          )}
                        </Space>
                      </Card>
                    </div>
                  );
                })}
              </Flex>
            )}
          </>
        )}
      </Space>
      <Drawer
        title="完整决策证据"
        open={preview !== undefined}
        size="large"
        onClose={() => {
          setPreview(undefined);
        }}
      >
        {preview !== undefined && (
          <section aria-label="广告决策证据预览">
            <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
              <Flex align="center" gap={8}>
                <Typography.Text strong>当前规则校验结论：</Typography.Text>
                <CodeTag
                  labels={{ PASS: '通过', BLOCKED: '已阻断' }}
                  code={preview.verdict.passed ? 'PASS' : 'BLOCKED'}
                  colors={{ PASS: 'success', BLOCKED: 'error' }}
                />
              </Flex>
              <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
                背书、批准和创建指令时都会重新校验当前授权与范围。
              </Typography.Paragraph>
              <ReasonTags
                codes={[
                  ...preview.verdict.reasons,
                  ...preview.gateReasons,
                  ...preview.unresolvedReasons,
                ]}
                empty={<Typography.Text type="secondary">没有阻断或未解决的原因。</Typography.Text>}
              />
              <AdvertisingEvidenceDetails value={preview} label="完整决策证据" inline />
            </Space>
          </section>
        )}
      </Drawer>
    </section>
  );
}
