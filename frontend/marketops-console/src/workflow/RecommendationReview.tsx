import { Alert, App, Button, Descriptions, Flex, Form, Input, Space, Typography } from 'antd';
import type { DescriptionsProps } from 'antd';
import { ExperimentOutlined, FileTextOutlined } from '@ant-design/icons';
import { useState } from 'react';
import type { ReactNode } from 'react';
import {
  createCommand,
  decide,
  requestImpactPreview,
  fetchRecommendationCommand,
} from '../api/console';
import type { ConsoleFailure, ConsoleRequest, ImpactPreview, Recommendation } from '../api/console';
import { formatDecimal, formatPercent, isDecimal } from '../format';
import { codeLabel } from '../i18n';
import {
  ACTION_KIND_LABELS,
  FULFILLMENT_MODE_LABELS,
  GUARDRAIL_REASON_LABELS,
  ORIGIN_LABELS,
  PARAMETER_LABELS,
  RECOMMENDATION_STATE_COLORS,
  RECOMMENDATION_STATE_LABELS,
  RISK_COLORS,
  RISK_LABELS,
} from '../i18n/zh/pricing';
import {
  CodeTag,
  ConfirmButton,
  DateTime,
  FailureAlert,
  Money,
  SectionCard,
  TechnicalDetails,
  failureMessage,
} from '../ui';

/** What the review needs in order to decide one proposal. */
export interface RecommendationReviewProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The proposal being decided. */
  readonly recommendation: Recommendation;
  /** Called after a decision changes the proposal's state. */
  readonly onDecided: (state: string, commandId?: string) => void;
}

/** One proposed parameter, rendered by what it is rather than as a raw pair. */
function ParameterValue({
  name,
  value,
}: {
  readonly name: string;
  readonly value: string;
}): React.JSX.Element {
  if (name === 'fulfillmentModeCode') {
    return <CodeTag labels={FULFILLMENT_MODE_LABELS} code={value} />;
  }
  return (
    <Typography.Text strong={name === 'targetPrice'} style={{ fontVariantNumeric: 'tabular-nums' }}>
      {isDecimal(value) ? formatDecimal(value, { minFractionDigits: 2 }) : value}
    </Typography.Text>
  );
}

/**
 * Where a person decides whether a real price changes.
 *
 * The preview is requested rather than assumed: it runs the same deterministic
 * guardrail the write gate will run, against the same canonical values, so what
 * the reviewer sees is what will be checked. A screen built from a stale
 * projection would let somebody approve a change the gate then refuses, or
 * worse, one it would have refused.
 *
 * Approval is disabled until the preview has been taken and has passed, and
 * every refusal reason is listed in full. Refusing one condition at a time
 * turns an unfixable situation into a week of attempts and hides from the
 * reviewer that the proposal is nowhere near ready.
 *
 * The reason field is required because the decision is the record a real
 * marketplace write is later justified by, and a decision with no stated reason
 * is a decision nobody can review.
 */
export function RecommendationReview({
  context,
  recommendation,
  onDecided,
}: RecommendationReviewProps): React.JSX.Element {
  const { message } = App.useApp();
  const [preview, setPreview] = useState<ImpactPreview | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [reason, setReason] = useState('');
  const [reasonTouched, setReasonTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [decisionState, setDecisionState] = useState(recommendation.state);
  const [version, setVersion] = useState(recommendation.version);
  const authorized = decisionState === 'APPROVED' || decisionState === 'POLICY_AUTHORIZED';
  const commandExists = ['COMMAND_CREATED', 'EXECUTION_TRACKING', 'OUTCOME_OBSERVATION'].includes(
    decisionState,
  );

  const openCommand = async (): Promise<void> => {
    setBusy(true);
    const result = await fetchRecommendationCommand(context, recommendation.id);
    setBusy(false);
    if (result.ok && result.value.recommendationId === recommendation.id)
      onDecided(decisionState, result.value.id);
    else
      setFailure(
        result.ok ? { kind: 'malformed', detail: 'command identity mismatch' } : result.failure,
      );
  };

  const finishCommand = async (state: string, currentVersion: number): Promise<void> => {
    setBusy(true);
    const created = await createCommand(context, recommendation.id, currentVersion);
    setBusy(false);
    if (created.ok) {
      void message.success('已创建调价指令');
      onDecided(state, created.value.commandId);
    } else setFailure(created.failure);
  };

  const runPreview = async (): Promise<void> => {
    setBusy(true);
    const outcome = await requestImpactPreview(context, recommendation.id);
    setBusy(false);
    if (outcome.ok) {
      setPreview(outcome.value);
      setFailure(undefined);
    } else {
      setPreview(undefined);
      setFailure(outcome.failure);
    }
  };

  const record = async (kind: 'approval' | 'rejection' | 'policy-authorization'): Promise<void> => {
    setBusy(true);
    const outcome = await decide(context, recommendation.id, kind, reason, version);
    if (!outcome.ok) {
      setBusy(false);
      setFailure(outcome.failure);
      return;
    }
    if (kind === 'rejection') {
      setBusy(false);
      void message.success('已驳回该建议');
      onDecided(outcome.value.state);
      return;
    }
    setDecisionState(outcome.value.state);
    setVersion(version + 1);
    await finishCommand(outcome.value.state, version + 1);
  };

  const reasonMissing = reason.trim().length === 0;
  const canDecide =
    decisionState === 'READY_FOR_REVIEW' &&
    preview?.verdict.passed === true &&
    !reasonMissing &&
    !busy;
  const decideBlockedReason: ReactNode =
    decisionState !== 'READY_FOR_REVIEW'
      ? `当前状态为“${codeLabel(RECOMMENDATION_STATE_LABELS, decisionState)}”，不可审批`
      : preview === undefined
        ? '请先检查调价影响'
        : !preview.verdict.passed
          ? '护栏校验未通过，不能批准'
          : reasonMissing
            ? '请先填写决策理由'
            : '正在处理，请稍候';
  const rejectDisabled = busy || reasonMissing || authorized || commandExists;
  const rejectBlockedReason: ReactNode = authorized
    ? '建议已获授权，不能再驳回'
    : commandExists
      ? '已创建指令，不能再驳回'
      : reasonMissing
        ? '请先填写决策理由'
        : '正在处理，请稍候';

  const parameters = Object.entries(recommendation.proposedParameters);
  const summaryItems: DescriptionsProps['items'] = [
    {
      key: 'state',
      label: '状态',
      children: (
        <span data-testid="recommendation-state" data-state={decisionState}>
          <CodeTag
            labels={RECOMMENDATION_STATE_LABELS}
            code={decisionState}
            colors={RECOMMENDATION_STATE_COLORS}
          />
        </span>
      ),
    },
    {
      key: 'risk',
      label: '风险',
      children: (
        <CodeTag labels={RISK_LABELS} code={recommendation.riskLabel} colors={RISK_COLORS} />
      ),
    },
    { key: 'origin', label: '来源', children: codeLabel(ORIGIN_LABELS, recommendation.origin) },
    {
      key: 'validUntil',
      label: '有效期至',
      children: <DateTime value={recommendation.validUntil} relative />,
    },
    {
      key: 'subject',
      label: '商品编号',
      children: (
        <Typography.Text
          type="secondary"
          style={{ fontSize: 12 }}
          copyable={{ text: recommendation.subjectId }}
        >
          {recommendation.subjectId.slice(0, 8)}
        </Typography.Text>
      ),
    },
    ...(parameters.length === 0
      ? [
          {
            key: 'parameters',
            label: '建议参数',
            children: <Typography.Text type="secondary">未提供</Typography.Text>,
          },
        ]
      : parameters.map(([name, value]) => ({
          key: `param:${name}`,
          label: codeLabel(PARAMETER_LABELS, name),
          children: <ParameterValue name={name} value={value} />,
        }))),
  ];

  const previewItems: DescriptionsProps['items'] =
    preview === undefined
      ? []
      : [
          {
            key: 'current',
            label: '当前价格',
            children: <Money value={preview.currentPrice} currency={preview.currencyCode} />,
          },
          {
            key: 'proposed',
            label: '建议价格',
            children: (
              <Money value={preview.proposedPrice} currency={preview.currencyCode} strong />
            ),
          },
          { key: 'change', label: '变动幅度', children: formatPercent(preview.changeRate) },
          {
            key: 'breakEven',
            label: '保本价',
            children: <Money value={preview.breakEvenPrice} currency={preview.currencyCode} />,
          },
          {
            key: 'profitNow',
            label: '当前单件利润',
            children: <Money value={preview.currentUnitProfit} currency={preview.currencyCode} />,
          },
          {
            key: 'profitAfter',
            label: '调价后单件利润',
            children: (
              <Money value={preview.projectedUnitProfit} currency={preview.currencyCode} strong />
            ),
          },
          { key: 'marginNow', label: '当前利润率', children: formatPercent(preview.currentMargin) },
          {
            key: 'marginAfter',
            label: '调价后利润率',
            children: formatPercent(preview.projectedMargin),
          },
        ];

  return (
    <section aria-label="建议审核" data-recommendation={recommendation.id}>
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <SectionCard
          title={`审核建议：${codeLabel(ACTION_KIND_LABELS, recommendation.actionKind)}`}
          extra={
            <Button
              icon={<ExperimentOutlined />}
              loading={busy}
              disabled={busy}
              onClick={() => void runPreview()}
            >
              {preview === undefined ? '检查调价影响' : '重新检查'}
            </Button>
          }
        >
          <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions
              bordered
              size="small"
              column={{ xs: 1, md: 2, xl: 3 }}
              items={summaryItems}
            />
            {failure !== undefined && (
              <div data-testid="review-failure">
                <FailureAlert failure={failure} />
              </div>
            )}
          </Space>
        </SectionCard>

        {preview !== undefined && (
          <section aria-label="调价影响预览" data-passed={preview.verdict.passed}>
            <SectionCard title="调价影响预览">
              <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
                <Descriptions
                  bordered
                  size="small"
                  column={{ xs: 1, md: 2, xl: 3 }}
                  items={previewItems}
                />
                {preview.verdict.passed ? (
                  <div data-testid="guardrail-verdict">
                    <Alert
                      type="success"
                      showIcon
                      role="status"
                      title={`护栏校验通过（策略版本 ${
                        preview.verdict.policyVersion === null
                          ? '未记录'
                          : String(preview.verdict.policyVersion)
                      }）`}
                    />
                  </div>
                ) : (
                  <div data-testid="guardrail-blocked">
                    <Alert
                      type="error"
                      showIcon
                      role="alert"
                      title="护栏拒绝此次调价，以下列出全部原因，可一次性处理"
                      description={
                        <Flex gap={6} wrap style={{ marginTop: 4 }}>
                          {preview.verdict.reasons.map((code) => (
                            <span key={code} data-reason={code}>
                              <CodeTag
                                labels={GUARDRAIL_REASON_LABELS}
                                code={code}
                                colors={{ [code]: 'error' }}
                              />
                            </span>
                          ))}
                        </Flex>
                      }
                    />
                  </div>
                )}
                <TechnicalDetails
                  data={{
                    evaluationId: preview.verdict.evaluationId,
                    purpose: preview.verdict.purpose,
                    detail: preview.verdict.detail,
                  }}
                />
              </Space>
            </SectionCard>
          </section>
        )}

        <SectionCard title="作出决定">
          <Form layout="vertical" requiredMark>
            <Form.Item
              label="决策理由"
              htmlFor="decision-reason"
              required
              {...(reasonTouched && reasonMissing
                ? { validateStatus: 'error' as const, help: '请填写决策理由' }
                : {
                    extra: '理由会作为审计记录保存，是后续平台写入的依据。',
                  })}
            >
              <Input.TextArea
                id="decision-reason"
                value={reason}
                autoSize={{ minRows: 3, maxRows: 8 }}
                placeholder="说明为什么批准或驳回此建议"
                onChange={(event) => {
                  setReason(event.target.value);
                  setReasonTouched(true);
                }}
                onBlur={() => {
                  setReasonTouched(true);
                }}
                required
              />
            </Form.Item>
            <Flex gap={8} wrap className="decision-actions">
              {commandExists && (
                <Button
                  icon={<FileTextOutlined />}
                  disabled={busy}
                  onClick={() => void openCommand()}
                >
                  打开已有指令
                </Button>
              )}
              {authorized && (
                <ConfirmButton
                  type="primary"
                  title="确认创建调价指令？"
                  description="指令创建后将进入执行流程，经写入闸门检查后调用平台。"
                  disabled={busy}
                  disabledReason="正在处理，请稍候"
                  onConfirm={() => finishCommand(decisionState, version)}
                >
                  创建已授权指令
                </ConfirmButton>
              )}
              <ConfirmButton
                type="primary"
                title="确认批准此次调价？"
                description="批准后将立即创建调价指令。"
                disabled={!canDecide}
                disabledReason={decideBlockedReason}
                onConfirm={() => record('approval')}
              >
                批准调价
              </ConfirmButton>
              <ConfirmButton
                title="确认使用常设授权？"
                description="按预设的常设授权策略放行，并立即创建调价指令。"
                disabled={!canDecide}
                disabledReason={decideBlockedReason}
                onConfirm={() => record('policy-authorization')}
              >
                使用常设授权
              </ConfirmButton>
              <ConfirmButton
                danger
                title="确认驳回此建议？"
                description="驳回后该建议将关闭，不可撤销。"
                disabled={rejectDisabled}
                disabledReason={rejectBlockedReason}
                onConfirm={() => record('rejection')}
              >
                驳回
              </ConfirmButton>
            </Flex>
          </Form>
        </SectionCard>
      </Space>
    </section>
  );
}

/** Say what went wrong in terms of what the operator can do about it. */
export function describeFailure(failure: ConsoleFailure): string {
  return failureMessage(failure);
}
