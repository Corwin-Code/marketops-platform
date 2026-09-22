import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Collapse,
  Form,
  Input,
  Segmented,
  Space,
  Steps,
  Table,
  Typography,
} from 'antd';
import type { StepsProps, TableColumnsType } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { decide } from '../api/console';
import type {
  Allowance,
  DescriptionCommand,
  Evaluation,
  LaunchAnswer,
  ListingAction,
  MeaningReviewBasis,
} from '../api/listingConversion';
import {
  cancelAction,
  fetchAction,
  fetchActions,
  fetchDescriptionCommand,
  fetchDescriptionGate,
  fetchEvaluation,
  launchAction,
  previewAllowance,
  reviewAction,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { ConfirmButton, EmptyState, LoadingState, SectionCard, TechnicalDetails } from '../ui';
import {
  AllowanceTable,
  CommandTimeline,
  EvaluationTable,
  NodeEvaluationForm,
} from './ListingActionEvidence';
import { CandidatePreparation } from './ListingCandidatePreparation';
import {
  Code,
  Codes,
  Details,
  IdText,
  ListingProblem,
  RussianText,
  Stack,
  When,
  YesNo,
  codeText,
} from './ListingCommon';
import { ListingMeaningReview } from './ListingMeaningReview';
import { PromotionDeclaration } from './ListingPromotionTerms';
import { ListingPurposeBasisDetails } from './ListingPurposeBasisDetails';
import { ListingResponsibility } from './ListingResponsibility';

export interface ListingActionsPanelProps {
  readonly context: ConsoleRequest;
  /** A listing the operator arrived from, to prepare a candidate on. */
  readonly listingId?: string;
}

const ACTION_STATES = [
  'DRAFT',
  'REVIEWED',
  'APPROVED',
  'APPROVED_NOT_LAUNCHABLE',
  'LAUNCHED',
  'VERIFIED',
  'CLOSED',
  'CANCELLED',
  'CONTAINED',
] as const;

const ALL = 'ALL';

/** The ordinary lifecycle of an action, as steps. */
const LIFECYCLE = ['DRAFT', 'REVIEWED', 'APPROVED', 'LAUNCHED', 'VERIFIED', 'CLOSED'] as const;

function lifecycle(state: string): { current: number; status: StepsProps['status'] } {
  switch (state) {
    case 'APPROVED_NOT_LAUNCHABLE':
      return { current: 2, status: 'error' };
    case 'CANCELLED':
    case 'CONTAINED':
      return { current: 0, status: 'error' };
    case 'CLOSED':
      return { current: LIFECYCLE.length - 1, status: 'finish' };
    default: {
      const index = LIFECYCLE.indexOf(state as (typeof LIFECYCLE)[number]);
      return { current: index < 0 ? 0 : index, status: 'process' };
    }
  }
}

/** The action's position in its lifecycle; a stopped action says so in red. */
function ActionLifecycle({ state }: { readonly state: string }): React.JSX.Element {
  const { current, status } = lifecycle(state);
  const stopped = state === 'CANCELLED' || state === 'CONTAINED';
  return (
    <div data-state={state}>
      {stopped && (
        <Alert
          style={{ marginBottom: 12 }}
          type="error"
          showIcon
          title={`${t('actionStopped')}：${codeText('actionState', state)}`}
        />
      )}
      <Steps
        size="small"
        current={current}
        {...(status === undefined ? {} : { status })}
        items={LIFECYCLE.map((step, index) => ({
          title:
            index === 2 && state === 'APPROVED_NOT_LAUNCHABLE'
              ? codeText('actionState', 'APPROVED_NOT_LAUNCHABLE')
              : codeText('actionState', step),
        }))}
      />
    </div>
  );
}

/**
 * Candidates, exact actions, review, approval, allowance and launch.
 *
 * Every step is a separate button with its own answer from the backend, and
 * every refusal is shown by the backend's own reason. Nothing here decides;
 * the deterministic gates, the calibration, the independence rule and the
 * allowance are all applied on the server, and this screen shows what they
 * said.
 */
export function ListingActionsPanel({
  context,
  listingId,
}: ListingActionsPanelProps): React.JSX.Element {
  const [filter, setFilter] = useState<string | undefined>(undefined);
  const [actions, setActions] = useState<readonly ListingAction[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [selected, setSelected] = useState<string | undefined>(undefined);
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    let active = true;
    void fetchActions(context, filter).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setActions(outcome.value);
        setFailure(undefined);
      } else {
        setActions(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, filter, generation]);

  if (selected !== undefined) {
    return (
      <ActionDetail
        context={context}
        actionId={selected}
        onBack={() => {
          setSelected(undefined);
          setGeneration((value) => value + 1);
        }}
      />
    );
  }

  const columns: TableColumnsType<ListingAction> = [
    {
      key: 'listing',
      title: t('listing'),
      render: (_, action) => (
        <Button
          type="link"
          style={{ padding: 0 }}
          onClick={() => {
            setSelected(action.id);
          }}
        >
          {action.nativeListingKey}
        </Button>
      ),
    },
    {
      key: 'kind',
      title: t('actionKind'),
      render: (_, action) => codeText('actionKind', action.actionKind),
    },
    {
      key: 'state',
      title: t('state'),
      render: (_, action) => <Code family="actionState" code={action.state} />,
    },
    {
      key: 'path',
      title: t('path'),
      render: (_, action) => <Code family="executionPath" code={action.executionPath} />,
    },
    {
      key: 'materiality',
      title: t('materiality'),
      render: (_, action) => <Code family="materialityRoute" code={action.materialityRoute} />,
    },
    {
      key: 'gaps',
      title: t('bindingGaps'),
      render: (_, action) => <Codes family="bindingGap" codes={action.bindingGaps} />,
    },
  ];

  return (
    <section aria-label={t('actions')} data-state={actions === undefined ? 'loading' : 'loaded'}>
      <Stack>
        {listingId !== undefined && (
          <CandidatePreparation
            context={context}
            listingId={listingId}
            onPrepared={(actionId) => {
              setSelected(actionId);
            }}
          />
        )}
        <SectionCard
          title={t('actions')}
          extra={
            <Button
              icon={<ReloadOutlined />}
              onClick={() => {
                setGeneration((value) => value + 1);
              }}
            >
              {t('refresh')}
            </Button>
          }
        >
          <Stack>
            <div style={{ overflowX: 'auto' }}>
              <Segmented<string>
                aria-label={t('state')}
                value={filter ?? ALL}
                options={[
                  { value: ALL, label: t('all') },
                  ...ACTION_STATES.map((state) => ({
                    value: state,
                    label: codeText('actionState', state),
                  })),
                ]}
                onChange={(value) => {
                  setFilter(value === ALL ? undefined : value);
                }}
              />
            </div>
            {failure !== undefined && <ListingProblem failure={failure} />}
            {actions === undefined && failure === undefined && <LoadingState />}
            {actions?.length === 0 && <EmptyState description={t('noActions')} />}
            {actions !== undefined && actions.length > 0 && (
              <Table<ListingAction>
                size="middle"
                rowKey="id"
                columns={columns}
                dataSource={[...actions]}
                pagination={false}
                scroll={{ x: 'max-content' }}
                onRow={(action) =>
                  ({
                    'data-action': action.id,
                    'data-action-state': action.state,
                  }) as React.HTMLAttributes<HTMLElement>
                }
              />
            )}
          </Stack>
        </SectionCard>
      </Stack>
    </section>
  );
}

interface ActionDetailProps {
  readonly context: ConsoleRequest;
  readonly actionId: string;
  readonly onBack: () => void;
}

function ActionDetail({ context, actionId, onBack }: ActionDetailProps): React.JSX.Element {
  const { message } = App.useApp();
  const [action, setAction] = useState<ListingAction | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [reason, setReason] = useState('');
  const [allowance, setAllowance] = useState<Allowance | undefined>(undefined);
  const [answer, setAnswer] = useState<LaunchAnswer | undefined>(undefined);
  const [evaluation, setEvaluation] = useState<Evaluation | undefined>(undefined);
  const [command, setCommand] = useState<DescriptionCommand | undefined>(undefined);
  const [gate, setGate] = useState<readonly string[] | undefined>(undefined);
  const [busy, setBusy] = useState<string | undefined>(undefined);
  const [approvalMaterial, setApprovalMaterial] = useState<MeaningReviewBasis | undefined>(
    undefined,
  );
  const receiveApprovalMaterial = useCallback((basis: MeaningReviewBasis | undefined): void => {
    setApprovalMaterial(basis);
  }, []);

  useEffect(() => {
    let active = true;
    void fetchAction(context, actionId).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setAction(outcome.value);
        setFailure(undefined);
      } else {
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, actionId, generation]);

  const refresh = (): void => {
    setApprovalMaterial(undefined);
    setGeneration((value) => value + 1);
  };
  const settle = (outcome: { readonly ok: boolean; readonly failure?: ConsoleFailure }): void => {
    if (outcome.ok) {
      void message.success(t('done'));
      setFailure(undefined);
      refresh();
    } else if (outcome.failure !== undefined) {
      setFailure(outcome.failure);
    }
  };

  const reasonMissing = reason.trim() === '';
  const approvalBlocked =
    approvalMaterial === undefined ||
    approvalMaterial.reviewEvidence?.verdict !== 'ATTESTED' ||
    approvalMaterial.materialityEvidence.state !== 'CURRENT' ||
    reasonMissing;
  const approvalBlockedReason =
    approvalMaterial === undefined
      ? t('approveNeedsMaterial')
      : approvalMaterial.reviewEvidence?.verdict !== 'ATTESTED'
        ? t('approveNeedsAttestation')
        : approvalMaterial.materialityEvidence.state !== 'CURRENT'
          ? t('approveNeedsMateriality')
          : t('meaningNeedReason');

  return (
    <section aria-label={t('actions')} data-action={actionId}>
      <Stack>
        <Button icon={<ArrowLeftOutlined />} onClick={onBack}>
          {t('backToActions')}
        </Button>
        {failure !== undefined && <ListingProblem failure={failure} />}
        {action === undefined && failure === undefined && <LoadingState rows={8} />}
        {action !== undefined && (
          <>
            <SectionCard
              title={
                <Space wrap>
                  <span>{action.nativeListingKey}</span>
                  <Code family="actionState" code={action.state} />
                  <Code family="executionPath" code={action.executionPath} />
                  <Code family="materialityRoute" code={action.materialityRoute} />
                </Space>
              }
              extra={
                <Button icon={<ReloadOutlined />} onClick={refresh}>
                  {t('refresh')}
                </Button>
              }
            >
              <Stack>
                <ActionLifecycle state={action.state} />
                <Details
                  items={[
                    {
                      key: 'kind',
                      label: t('actionKind'),
                      children: codeText('actionKind', action.actionKind),
                    },
                    {
                      key: 'purpose',
                      label: t('actionPurpose'),
                      children:
                        action.purposeCode === undefined ? (
                          '—'
                        ) : (
                          <Code family="actionPurpose" code={action.purposeCode} />
                        ),
                    },
                    {
                      key: 'affected',
                      label: t('affectedSetShort'),
                      children: (
                        <Space size={4}>
                          <Code family="affectedSetState" code={action.affectedSetState} />
                          <Typography.Text type="secondary">
                            {action.affectedVariantCount} 个变体
                          </Typography.Text>
                        </Space>
                      ),
                    },
                    {
                      key: 'kiz',
                      label: t('kiz'),
                      children: <YesNo value={action.kizMarkedDeclared} />,
                    },
                    {
                      key: 'content',
                      label: t('contentAxis'),
                      children: <YesNo value={action.contentAxisMaterial} />,
                    },
                    {
                      key: 'exposure',
                      label: t('exposureAxis'),
                      children: <YesNo value={action.exposureAxisMaterial} />,
                    },
                    {
                      key: 'reviews',
                      label: t('reviewer'),
                      children:
                        action.reviews.length === 0 ? (
                          '—'
                        ) : (
                          <Space orientation="vertical" size={2}>
                            {action.reviews.map((review) => (
                              <Space key={review.id} size={4} wrap>
                                <Code family="reviewVerdict" code={review.verdict} />
                                <When value={review.reviewedAt} />
                              </Space>
                            ))}
                          </Space>
                        ),
                    },
                    {
                      key: 'launch',
                      label: t('launchedAt'),
                      children: <When value={action.launch?.launchedAt} />,
                    },
                    { key: 'version', label: t('version'), children: action.version },
                    ...(action.bindingGaps.length === 0
                      ? []
                      : [
                          {
                            key: 'gaps',
                            label: t('bindingGaps'),
                            span: 'filled' as const,
                            children: <Codes family="bindingGap" codes={action.bindingGaps} />,
                          },
                        ]),
                  ]}
                />
                {action.restoresCommandId !== undefined && (
                  <Alert
                    type="info"
                    showIcon
                    title={t('restorationApproval')}
                    description={
                      <IdText label={t('restoresCommandShort')} value={action.restoresCommandId} />
                    }
                  />
                )}
                {action.targetText !== undefined && (
                  <Collapse
                    size="small"
                    items={[
                      {
                        key: 'target',
                        label: t('targetText'),
                        children: <RussianText value={action.targetText} />,
                      },
                    ]}
                  />
                )}
                <TechnicalDetails>
                  <Space orientation="vertical" size={2}>
                    <IdText label={t('actionId')} value={action.id} />
                    <IdText label={t('platformListingId')} value={action.platformListingId} />
                    <IdText label={t('author')} value={action.authorUserId} />
                    {action.reviews.map((review) => (
                      <IdText key={review.id} label={t('reviewer')} value={review.reviewerUserId} />
                    ))}
                    <IdText label={t('affectedSet')} value={action.affectedSetDigest} />
                    <IdText label={t('currentDigest')} value={action.currentTextDigest} />
                    <IdText label={t('targetDigest')} value={action.targetTextDigest} />
                    <IdText
                      label={t('calibrationVersion')}
                      value={`${action.calibrationPackageId ?? '—'} · ${
                        action.calibrationVersion === undefined
                          ? '—'
                          : String(action.calibrationVersion)
                      }`}
                    />
                  </Space>
                </TechnicalDetails>
              </Stack>
            </SectionCard>

            {action.purposeBasis !== undefined && (
              <SectionCard>
                <ListingPurposeBasisDetails basis={action.purposeBasis} />
              </SectionCard>
            )}

            <SectionCard title={t('decision')}>
              <Stack>
                <Form layout="vertical" component={false}>
                  <Form.Item label={t('reason')} extra={t('reasonHelp')}>
                    <Input.TextArea
                      aria-label={t('reason')}
                      autoSize={{ minRows: 2, maxRows: 4 }}
                      value={reason}
                      onChange={(e) => {
                        setReason(e.target.value);
                      }}
                    />
                  </Form.Item>
                </Form>
                {action.state === 'DRAFT' && (
                  <>
                    <ListingMeaningReview
                      context={context}
                      actionId={actionId}
                      reason={reason}
                      onOutcome={settle}
                    />
                    <div>
                      <ConfirmButton
                        title="确认退回作者？"
                        description="操作将回到作者处修改，需重新复核。"
                        onConfirm={() =>
                          reviewAction(context, actionId, 'RETURNED', reason).then(settle)
                        }
                      >
                        {t('returnToAuthor')}
                      </ConfirmButton>
                    </div>
                  </>
                )}
                {action.state === 'REVIEWED' && (
                  <>
                    <ListingMeaningReview
                      context={context}
                      actionId={actionId}
                      reason={reason}
                      onOutcome={settle}
                      approvalMode
                      onMaterialLoaded={receiveApprovalMaterial}
                    />
                    <Space wrap>
                      <ConfirmButton
                        type="primary"
                        title="确认批准此操作？"
                        description="批准由工作流记录，之后仍需额度与启动。"
                        disabled={approvalBlocked}
                        {...(approvalBlocked ? { disabledReason: approvalBlockedReason } : {})}
                        onConfirm={() =>
                          decide(
                            context,
                            action.recommendationId,
                            'approval',
                            reason,
                            action.recommendationVersion,
                          ).then(settle)
                        }
                      >
                        {t('approve')}
                      </ConfirmButton>
                      <ConfirmButton
                        danger
                        title="确认拒绝此操作？"
                        description="拒绝后该建议不会执行。"
                        onConfirm={() =>
                          decide(
                            context,
                            action.recommendationId,
                            'rejection',
                            reason,
                            action.recommendationVersion,
                          ).then(settle)
                        }
                      >
                        {t('reject')}
                      </ConfirmButton>
                    </Space>
                  </>
                )}
                {(action.state === 'APPROVED' || action.state === 'APPROVED_NOT_LAUNCHABLE') && (
                  <Space wrap>
                    <Button
                      loading={busy === 'allowance'}
                      onClick={() => {
                        setBusy('allowance');
                        void previewAllowance(context, actionId).then((outcome) => {
                          setBusy(undefined);
                          if (outcome.ok) setAllowance(outcome.value);
                          else setFailure(outcome.failure);
                        });
                      }}
                    >
                      {t('allowancePreview')}
                    </Button>
                    <ConfirmButton
                      type="primary"
                      title="确认启动此操作？"
                      description="启动将占用额度；额度不足时不会启动。"
                      onConfirm={() =>
                        launchAction(context, actionId).then((outcome) => {
                          if (outcome.ok) {
                            setAnswer(outcome.value);
                            refresh();
                          } else setFailure(outcome.failure);
                        })
                      }
                    >
                      {t('launch')}
                    </ConfirmButton>
                  </Space>
                )}
                {['DRAFT', 'REVIEWED', 'APPROVED', 'APPROVED_NOT_LAUNCHABLE'].includes(
                  action.state,
                ) && (
                  <div>
                    <ConfirmButton
                      danger
                      title="确认取消此操作？"
                      description="取消不可撤销，需要时请重新准备。"
                      onConfirm={() => cancelAction(context, actionId, reason).then(settle)}
                    >
                      {t('cancel')}
                    </ConfirmButton>
                  </div>
                )}
                {allowance !== undefined && <AllowanceTable allowance={allowance} />}
                {answer !== undefined && (
                  <div role="status" data-launched={String(answer.launched)}>
                    {answer.launched ? (
                      <Alert type="success" showIcon title={t('launched')} />
                    ) : (
                      <Alert
                        type="warning"
                        showIcon
                        title={t('notLaunched')}
                        description={
                          <Codes family="allowanceAxis" codes={answer.insufficientAxes} />
                        }
                      />
                    )}
                  </div>
                )}
                {action.occupations.length > 0 && (
                  <Table
                    size="middle"
                    rowKey="id"
                    pagination={false}
                    dataSource={[...action.occupations]}
                    columns={[
                      {
                        key: 'axis',
                        title: t('axis'),
                        render: (_, occupation) => (
                          <Code family="allowanceAxis" code={occupation.axisCode} />
                        ),
                      },
                      {
                        key: 'occupied',
                        title: t('occupied'),
                        align: 'right',
                        dataIndex: 'occupiedValue',
                      },
                      {
                        key: 'state',
                        title: t('state'),
                        render: (_, occupation) => (
                          <Code family="occupationState" code={occupation.state} />
                        ),
                      },
                    ]}
                  />
                )}
              </Stack>
            </SectionCard>

            <SectionCard title={t('responsibilityTitle')}>
              <ListingResponsibility context={context} actionId={actionId} />
            </SectionCard>

            {action.actionKind === 'LISTING_PROMOTION_ACTION' && (
              <SectionCard title={t('promotionDeclaration')}>
                <PromotionDeclaration
                  key={actionId}
                  context={context}
                  actionId={actionId}
                  digest={action.promotionTermsDigest}
                />
              </SectionCard>
            )}

            {action.purposeCode !== 'DESCRIPTION_CORRECTION' &&
              action.purposeCode !== 'BOUNDED_EXPLORATION' && (
                <SectionCard
                  title={t('evaluation')}
                  extra={
                    <Button
                      loading={busy === 'evaluation'}
                      onClick={() => {
                        setBusy('evaluation');
                        void fetchEvaluation(context, actionId).then((outcome) => {
                          setBusy(undefined);
                          if (outcome.ok) setEvaluation(outcome.value);
                          else setFailure(outcome.failure);
                        });
                      }}
                    >
                      {t('evaluationLoad')}
                    </Button>
                  }
                >
                  {evaluation === undefined ? (
                    <EmptyState description={t('evaluationNotLoaded')} />
                  ) : (
                    <Stack>
                      {action.launch !== undefined && (
                        <NodeEvaluationForm
                          context={context}
                          actionId={actionId}
                          evaluation={evaluation}
                          onEvaluated={setEvaluation}
                          onFailure={setFailure}
                        />
                      )}
                      <EvaluationTable evaluation={evaluation} />
                    </Stack>
                  )}
                </SectionCard>
              )}

            {action.executionPath === 'API' && action.launch !== undefined && (
              <SectionCard
                title={t('descriptionCommand')}
                extra={
                  <Button
                    loading={busy === 'command'}
                    onClick={() => {
                      setBusy('command');
                      void fetchDescriptionCommand(context, actionId).then((outcome) => {
                        setBusy(undefined);
                        if (outcome.ok) {
                          setCommand(outcome.value);
                          void fetchDescriptionGate(context, outcome.value.id).then(
                            (gateOutcome) => {
                              if (gateOutcome.ok) setGate(gateOutcome.value);
                              else setFailure(gateOutcome.failure);
                            },
                          );
                        } else setFailure(outcome.failure);
                      });
                    }}
                  >
                    {t('commandLoad')}
                  </Button>
                }
              >
                {command === undefined ? (
                  <EmptyState description={t('commandNotLoaded')} />
                ) : (
                  <CommandTimeline command={command} gate={gate} />
                )}
              </SectionCard>
            )}
          </>
        )}
      </Stack>
    </section>
  );
}
