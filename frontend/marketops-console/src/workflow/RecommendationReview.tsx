import { Alert, App, Button, Descriptions, Flex, Form, Input, List, Space, Typography } from 'antd';
import type { DescriptionsProps } from 'antd';
import { ExperimentOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  createCommand,
  decide,
  fetchRecommendation,
  fetchRecommendationCommand,
  requestImpactPreview,
} from '../api/console';
import type {
  ConsoleFailure,
  ConsoleRequest,
  GuardrailVerdict,
  ImpactPreview,
  Recommendation,
  SubjectIdentity,
} from '../api/console';
import { formatDecimal, formatMoney, formatPercent, isDecimal } from '../format';
import { codeLabel } from '../i18n';
import { dialog } from '../i18n/zh/common';
import {
  ACTION_KIND_LABELS,
  FULFILLMENT_MODE_LABELS,
  GUARDRAIL_DETAIL_LABELS,
  GUARDRAIL_REASON_HINTS,
  GUARDRAIL_REASON_LABELS,
  METRIC_LABELS,
  ORIGIN_LABELS,
  PARAMETER_LABELS,
  PLATFORM_LABELS,
  RECOMMENDATION_STATE_COLORS,
  RECOMMENDATION_STATE_LABELS,
  RISK_COLORS,
  RISK_LABELS,
  reviewText as text,
} from '../i18n/zh/pricing';
import {
  ActionModal,
  CodeTag,
  DateTime,
  DetailDrawer,
  FailureAlert,
  InfoTip,
  LoadingState,
  Money,
  SubjectName,
  TechnicalDetails,
  TriggerButton,
  WriteConfirmModal,
  failureMessage,
  subjectTitle,
} from '../ui';
import type { SubmitOutcome, WriteGuard } from '../ui';

/** States in which a command already carries the decision. */
const COMMAND_STATES: readonly string[] = [
  'COMMAND_CREATED',
  'EXECUTION_TRACKING',
  'OUTCOME_OBSERVATION',
];
/** States that authorise a command which does not exist yet. */
const AUTHORIZED_STATES: readonly string[] = ['APPROVED', 'POLICY_AUTHORIZED'];
/** States in which nothing more can be decided. */
const FINISHED_STATES: readonly string[] = ['REJECTED', 'EXPIRED', 'CANCELLED', 'CLOSED'];
/** Refusals that mean the proposal changed under the reviewer. */
const STALE_CODES: readonly string[] = ['VERSION_CONFLICT', 'RECOMMENDATION_STALE'];

function isStale(failure: ConsoleFailure): boolean {
  const code = 'code' in failure ? failure.code : undefined;
  return code !== undefined && STALE_CODES.includes(code);
}

/** How a recorded guardrail value is written. */
type ValueKind = 'money' | 'percent' | 'number' | 'duration' | 'metrics' | 'text';

/**
 * Which recorded value explains each refusal, and the threshold beside it.
 *
 * A threshold is read from the named key, or from `<actual>Threshold` when the
 * verdict records one; nothing is computed here.
 */
const REASON_VALUES: Readonly<
  Record<string, { readonly actual: string; readonly threshold?: string; readonly kind: ValueKind }>
> = {
  POLICY_LIMIT_NOT_CONFIGURED: { actual: 'missingLimit', kind: 'text' },
  DATA_COMPLETENESS_BELOW_MINIMUM: { actual: 'dataCompleteness', kind: 'percent' },
  INPUT_TOO_STALE: { actual: 'inputAgeSeconds', kind: 'duration' },
  INPUT_FRESHNESS_UNAVAILABLE: { actual: 'freshnessUnavailableFeeds', kind: 'text' },
  REQUIRED_METRIC_UNAVAILABLE: { actual: 'unavailableMetrics', kind: 'metrics' },
  METRIC_CONFIDENCE_INSUFFICIENT: { actual: 'lowConfidenceMetrics', kind: 'metrics' },
  PROJECTED_ECONOMICS_UNAVAILABLE: { actual: 'projectionBlockingReasons', kind: 'text' },
  CURRENCY_MISMATCH: { actual: 'currencyMismatches', kind: 'text' },
  MARGIN_BELOW_MINIMUM: { actual: 'projectedMargin', kind: 'percent' },
  UNIT_PROFIT_BELOW_MINIMUM: { actual: 'projectedUnitProfit', kind: 'money' },
  BELOW_BREAK_EVEN: { actual: 'proposedPrice', threshold: 'breakEvenPrice', kind: 'money' },
  BELOW_MINIMUM_PRICE: { actual: 'proposedPrice', threshold: 'minimumPrice', kind: 'money' },
  SINGLE_CHANGE_TOO_LARGE: { actual: 'changeRate', kind: 'percent' },
  DAILY_CHANGE_EXCEEDED: { actual: 'cumulativeDailyChangeRate', kind: 'percent' },
  COOLDOWN_ACTIVE: { actual: 'secondsSinceLastChange', kind: 'duration' },
  INVENTORY_BELOW_MINIMUM: { actual: 'availableUnits', kind: 'number' },
  CHANGE_EXCEEDS_POLICY_AUTHORIZATION: {
    actual: 'changeRate',
    threshold: 'authorizationMaxChangeRate',
    kind: 'percent',
  },
};

function formatDuration(seconds: string): string {
  if (!/^\d+$/.test(seconds)) {
    return seconds;
  }
  const value = Number.parseInt(seconds, 10);
  if (value < 3600) return `${String(Math.max(1, Math.round(value / 60)))} 分钟`;
  if (value < 172_800) return `${String(Math.round(value / 3600))} 小时`;
  return `${String(Math.round(value / 86_400))} 天`;
}

function formatValue(kind: ValueKind, value: string, currency: string | null): string {
  switch (kind) {
    case 'money':
      return formatMoney(value, currency);
    case 'percent':
      return formatPercent(value);
    case 'number':
      return isDecimal(value) ? formatDecimal(value) : value;
    case 'duration':
      return formatDuration(value);
    case 'metrics':
      return value
        .split(',')
        .filter((code) => code !== '')
        .map((code) => codeLabel(METRIC_LABELS, code))
        .join('、');
    case 'text':
      return value;
  }
}

/** The recorded values behind one refusal, e.g. `实际 55.00% / 阈值 80.00%`. */
function reasonValues(
  code: string,
  detail: Readonly<Record<string, string>>,
  currency: string | null,
): string | undefined {
  const spec = REASON_VALUES[code];
  if (spec === undefined) {
    return undefined;
  }
  const actual = detail[spec.actual];
  const threshold = detail[spec.threshold ?? `${spec.actual}Threshold`];
  if (actual !== undefined && threshold !== undefined) {
    return `${text.actual} ${formatValue(spec.kind, actual, currency)} / ${text.threshold} ${formatValue(spec.kind, threshold, currency)}`;
  }
  if (actual !== undefined) {
    return `${codeLabel(GUARDRAIL_DETAIL_LABELS, spec.actual)}：${formatValue(spec.kind, actual, currency)}`;
  }
  return undefined;
}

/** Every refusal reason, with its values and what to do about it. */
function GuardrailReasons({
  verdict,
  currency,
}: {
  readonly verdict: GuardrailVerdict;
  readonly currency: string | null;
}): React.JSX.Element {
  return (
    <List
      size="small"
      dataSource={[...verdict.reasons]}
      rowKey={(code) => code}
      renderItem={(code) => {
        const values = reasonValues(code, verdict.detail, currency);
        const hint = Object.hasOwn(GUARDRAIL_REASON_HINTS, code)
          ? GUARDRAIL_REASON_HINTS[code]
          : undefined;
        return (
          <List.Item style={{ paddingInline: 0 }}>
            <Flex vertical gap={2} data-reason={code}>
              <Typography.Text strong>{codeLabel(GUARDRAIL_REASON_LABELS, code)}</Typography.Text>
              {values !== undefined && (
                <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {values}
                </Typography.Text>
              )}
              {hint !== undefined && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {hint}
                </Typography.Text>
              )}
            </Flex>
          </List.Item>
        );
      }}
    />
  );
}

/** The outcome in one line; the policy version is one hover away. */
function VerdictHeadline({ verdict }: { readonly verdict: GuardrailVerdict }): React.JSX.Element {
  return (
    <span>
      {verdict.passed ? text.verdictPassed : text.verdictFailed}
      <InfoTip title={text.policyVersion(verdict.policyVersion)} />
    </span>
  );
}

/** The verdict as a reviewer reads it: outcome, policy version, every reason. */
function VerdictContent({ preview }: { readonly preview: ImpactPreview }): React.JSX.Element {
  const { verdict } = preview;
  return (
    <Flex vertical gap={4}>
      <Typography.Text strong>
        <VerdictHeadline verdict={verdict} />
      </Typography.Text>
      {!verdict.passed && <GuardrailReasons verdict={verdict} currency={preview.currencyCode} />}
    </Flex>
  );
}

/** A value that is not known says so rather than disappearing. */
function MoneyOrUnknown({
  value,
  currency,
  strong = false,
}: {
  readonly value: string | null;
  readonly currency: string | null;
  readonly strong?: boolean;
}): React.JSX.Element {
  return isDecimal(value) ? (
    <Money value={value} currency={currency} strong={strong} />
  ) : (
    <Typography.Text type="secondary">{text.unknown}</Typography.Text>
  );
}

function percentOrUnknown(value: string | null): ReactNode {
  return isDecimal(value) ? (
    <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>
      {formatPercent(value)}
    </Typography.Text>
  ) : (
    <Typography.Text type="secondary">{text.unknown}</Typography.Text>
  );
}

/** What a price write changes, restated inside the confirmation. */
function WriteImpact({
  preview,
  identity,
  subjectId,
  extra,
}: {
  readonly preview: ImpactPreview;
  readonly identity: SubjectIdentity | undefined;
  readonly subjectId: string;
  readonly extra?: ReactNode;
}): React.JSX.Element {
  const currency = preview.currencyCode;
  const platform = identity?.platformCode ?? null;
  return (
    <Flex vertical gap={8}>
      <Descriptions
        size="small"
        bordered
        column={1}
        items={[
          { key: 'subject', label: text.subject, children: subjectTitle(identity, subjectId) },
          {
            key: 'platform',
            label: text.platform,
            children:
              platform === null ? (
                <Typography.Text type="secondary">{text.unknown}</Typography.Text>
              ) : (
                codeLabel(PLATFORM_LABELS, platform)
              ),
          },
          {
            key: 'price',
            label: text.priceChange,
            children: (
              <Space size={6} wrap>
                <MoneyOrUnknown value={preview.currentPrice} currency={currency} />
                <span aria-hidden>→</span>
                <MoneyOrUnknown value={preview.proposedPrice} currency={currency} strong />
                {currency === null && (
                  <Typography.Text type="secondary">（币种未知）</Typography.Text>
                )}
              </Space>
            ),
          },
          { key: 'change', label: text.changeRate, children: percentOrUnknown(preview.changeRate) },
          {
            key: 'margin',
            label: text.projectedMargin,
            children: percentOrUnknown(preview.projectedMargin),
          },
          {
            key: 'breakEven',
            label: text.breakEven,
            children: <MoneyOrUnknown value={preview.breakEvenPrice} currency={currency} />,
          },
        ]}
      />
      {extra}
    </Flex>
  );
}

/** One proposed parameter, rendered by what it is rather than as a raw pair. */
function ParameterValue({
  name,
  value,
  currency,
}: {
  readonly name: string;
  readonly value: string;
  readonly currency: string | null;
}): React.JSX.Element {
  if (name === 'fulfillmentModeCode') {
    return <CodeTag labels={FULFILLMENT_MODE_LABELS} code={value} />;
  }
  if (name === 'targetPrice') {
    return <Money value={value} currency={currency} strong />;
  }
  return (
    <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>
      {isDecimal(value) ? formatDecimal(value, { minFractionDigits: 2 }) : value}
    </Typography.Text>
  );
}

/** What the review drawer needs. */
export interface RecommendationReviewDrawerProps {
  readonly context: ConsoleRequest;
  /** The subject whose page opened the review; a proposal of another is refused. */
  readonly subjectId: string;
  /** The proposal under review, or nothing when the drawer is closed. */
  readonly recommendationId: string | undefined;
  /** The subject's name as the diagnosis knows it, when the proposal omits it. */
  readonly identity?: SubjectIdentity | undefined;
  readonly onClose: () => void;
  /** A decision changed the proposal; lists showing it should reload. */
  readonly onChanged: () => void;
  /** Go to the command carrying the decision. */
  readonly onOpenCommand: (commandId: string) => void;
}

/**
 * Where a person decides whether a real price changes, beside the diagnosis.
 *
 * The proposal is loaded by its identifier, so the drawer reopens from its
 * address after a reload. Opening it runs the impact preview once per proposal
 * version: the same deterministic guardrail the write gate will run, so what
 * the reviewer sees is what will be checked. Every refusal reason is listed
 * with its values at once.
 *
 * Each decision has its own dialog with its own required reason. Approval and
 * standing-policy approval restate the price change and the verdict in a
 * write confirmation that a failed verdict disables. Nothing here reads AI
 * output: the decision rests on the rules and the reviewer's own reason.
 */
export function RecommendationReviewDrawer(
  props: RecommendationReviewDrawerProps,
): React.JSX.Element {
  if (props.recommendationId === undefined) {
    return <DetailDrawer open={false} onClose={props.onClose} title={text.title} />;
  }
  return (
    <ReviewDrawerBody
      key={props.recommendationId}
      {...props}
      recommendationId={props.recommendationId}
    />
  );
}

function ReviewDrawerBody({
  context,
  subjectId,
  recommendationId,
  identity: pageIdentity,
  onClose,
  onChanged,
  onOpenCommand,
}: RecommendationReviewDrawerProps & { readonly recommendationId: string }): React.JSX.Element {
  const { message } = App.useApp();
  const [recommendation, setRecommendation] = useState<Recommendation | undefined>(undefined);
  const [loadFailure, setLoadFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [preview, setPreview] = useState<ImpactPreview | undefined>(undefined);
  const [previewFailure, setPreviewFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [previewing, setPreviewing] = useState(false);
  const [actionFailure, setActionFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [commandFailedAfterDecision, setCommandFailedAfterDecision] = useState(false);
  const [busy, setBusy] = useState(false);
  const [reloading, setReloading] = useState(false);
  /** Proposal versions already previewed, so a re-run effect never evaluates twice. */
  const previewed = useRef(new Set<string>());
  const previewSeq = useRef(0);

  useEffect(() => {
    let active = true;
    void fetchRecommendation(context, recommendationId).then((outcome) => {
      if (!active) return;
      if (outcome.ok && outcome.value.subjectId === subjectId) {
        setRecommendation(outcome.value);
        setLoadFailure(undefined);
      } else {
        setLoadFailure(
          outcome.ok ? { kind: 'malformed', detail: 'subject mismatch' } : outcome.failure,
        );
      }
    });
    return () => {
      active = false;
    };
  }, [context, recommendationId, subjectId, loadAttempt]);

  const runPreview = async (): Promise<void> => {
    const seq = ++previewSeq.current;
    setPreviewing(true);
    setPreviewFailure(undefined);
    const outcome = await requestImpactPreview(context, recommendationId);
    // Only a newer preview supersedes this one; unmounting is harmless.
    if (seq !== previewSeq.current) return;
    setPreviewing(false);
    if (outcome.ok && outcome.value.recommendationId === recommendationId) {
      setPreview(outcome.value);
    } else {
      setPreview(undefined);
      setPreviewFailure(
        outcome.ok ? { kind: 'malformed', detail: 'preview mismatch' } : outcome.failure,
      );
    }
  };
  const runPreviewRef = useRef(runPreview);
  useEffect(() => {
    runPreviewRef.current = runPreview;
  });

  const state = recommendation?.state;
  const version = recommendation?.version;
  const actionKind = recommendation?.actionKind;
  // Opening the review checks the impact once per proposal version. The ref
  // survives React's development double effect, so one opening makes one
  // guardrail evaluation.
  useEffect(() => {
    if (state === undefined || version === undefined || actionKind !== 'PRICE_CHANGE') {
      return;
    }
    const decidable =
      state === 'READY_FOR_REVIEW' ||
      (AUTHORIZED_STATES.includes(state) && !COMMAND_STATES.includes(state));
    const key = `${recommendationId}:${String(version)}`;
    if (!decidable || previewed.current.has(key)) {
      return;
    }
    previewed.current.add(key);
    void runPreviewRef.current();
  }, [recommendationId, state, version, actionKind]);

  const reload = async (): Promise<void> => {
    setReloading(true);
    const outcome = await fetchRecommendation(context, recommendationId);
    setReloading(false);
    if (!outcome.ok) {
      setActionFailure(outcome.failure);
      return;
    }
    if (outcome.value.subjectId !== subjectId) {
      setActionFailure({ kind: 'malformed', detail: 'subject mismatch' });
      return;
    }
    previewed.current.add(`${recommendationId}:${String(outcome.value.version)}`);
    setRecommendation(outcome.value);
    setActionFailure(undefined);
    setCommandFailedAfterDecision(false);
    onChanged();
    await runPreview();
  };

  /** A stale proposal is reported in the drawer, where it can be reloaded. */
  const route = (failure: ConsoleFailure): SubmitOutcome => {
    if (isStale(failure)) {
      setActionFailure(failure);
      return undefined;
    }
    return failure;
  };

  if (recommendation === undefined) {
    return (
      <DetailDrawer
        open
        onClose={onClose}
        title={text.title}
        size="large"
        loading={loadFailure === undefined}
        failure={loadFailure}
        onRetry={() => {
          setLoadFailure(undefined);
          setLoadAttempt((n) => n + 1);
        }}
      />
    );
  }

  const identity = recommendation.identity ?? pageIdentity;
  const currency = preview?.currencyCode ?? recommendation.proposedParameters.currencyCode ?? null;
  const decisionState = recommendation.state;
  const commandExists = COMMAND_STATES.includes(decisionState);
  const authorized = AUTHORIZED_STATES.includes(decisionState);
  const finished = FINISHED_STATES.includes(decisionState);

  const decideAndCreate = async (
    kind: 'approval' | 'policy-authorization',
    reason: string,
  ): Promise<SubmitOutcome> => {
    const decided = await decide(context, recommendation.id, kind, reason, recommendation.version);
    if (!decided.ok) {
      return route(decided.failure);
    }
    const nextVersion = recommendation.version + 1;
    // The current preview already covers this proposal; do not re-evaluate it
    // merely because the decision bumped the version.
    previewed.current.add(`${recommendation.id}:${String(nextVersion)}`);
    setRecommendation({ ...recommendation, state: decided.value.state, version: nextVersion });
    onChanged();
    const created = await createCommand(context, recommendation.id, nextVersion);
    if (created.ok) {
      void message.success(text.commandCreated);
      onOpenCommand(created.value.commandId);
    } else {
      setActionFailure(created.failure);
      setCommandFailedAfterDecision(true);
    }
    return undefined;
  };

  const createAuthorized = async (): Promise<SubmitOutcome> => {
    const created = await createCommand(context, recommendation.id, recommendation.version);
    if (!created.ok) {
      return route(created.failure);
    }
    void message.success(text.commandCreated);
    onChanged();
    onOpenCommand(created.value.commandId);
    return undefined;
  };

  const reject = async (reason: string): Promise<SubmitOutcome> => {
    const decided = await decide(
      context,
      recommendation.id,
      'rejection',
      reason,
      recommendation.version,
    );
    if (!decided.ok) {
      return route(decided.failure);
    }
    void message.success(text.rejected);
    onChanged();
    onClose();
    return undefined;
  };

  const openCommand = async (): Promise<void> => {
    setBusy(true);
    const result = await fetchRecommendationCommand(context, recommendation.id);
    setBusy(false);
    if (result.ok && result.value.recommendationId === recommendation.id) {
      onOpenCommand(result.value.id);
    } else {
      setActionFailure(
        result.ok ? { kind: 'malformed', detail: 'command identity mismatch' } : result.failure,
      );
    }
  };

  const guard: WriteGuard | undefined =
    preview === undefined
      ? undefined
      : { passed: preview.verdict.passed, content: <VerdictContent preview={preview} /> };
  const writeBlockedReason: ReactNode = previewing
    ? text.previewRunning
    : preview === undefined
      ? text.previewMissing
      : !preview.verdict.passed
        ? text.verdictBlocked
        : undefined;
  const approveBlockedReason: ReactNode =
    decisionState !== 'READY_FOR_REVIEW'
      ? text.notReviewable(codeLabel(RECOMMENDATION_STATE_LABELS, decisionState))
      : writeBlockedReason;
  const proposedPrice = formatMoney(preview?.proposedPrice, preview?.currencyCode ?? null);
  const maxRate = preview?.verdict.detail.authorizationMaxChangeRate;

  let footer: ReactNode;
  if (commandExists) {
    footer = (
      <TriggerButton
        trigger={{ label: text.openCommand, type: 'primary' }}
        loading={busy}
        onClick={() => {
          void openCommand();
        }}
      />
    );
  } else if (authorized) {
    footer =
      preview === undefined ? (
        <TriggerButton
          trigger={{
            label: text.createCommand,
            type: 'primary',
            disabled: true,
            disabledReason: writeBlockedReason,
            reasonPlacement: 'inline',
          }}
          onClick={() => undefined}
        />
      ) : (
        <WriteConfirmModal
          trigger={{
            label: text.createCommand,
            type: 'primary',
            disabled: writeBlockedReason !== undefined,
            disabledReason: writeBlockedReason,
            reasonPlacement: 'inline',
          }}
          title={text.createTitle}
          impact={<WriteImpact preview={preview} identity={identity} subjectId={subjectId} />}
          {...(guard === undefined ? {} : { guard })}
          consequence={text.createConsequence}
          confirmText={text.confirmPrice(proposedPrice)}
          reasonLabel={text.createNote}
          reasonRequired={false}
          onConfirm={() => createAuthorized()}
        />
      );
  } else if (finished) {
    footer = <Typography.Text type="secondary">{text.noAction}</Typography.Text>;
  } else {
    const approveTrigger = {
      disabled: approveBlockedReason !== undefined,
      disabledReason: approveBlockedReason,
      reasonPlacement: 'inline' as const,
    };
    footer = (
      <Flex gap={8} wrap align="flex-start" className="decision-actions">
        {preview === undefined ? (
          <>
            <TriggerButton
              trigger={{ label: text.approve, type: 'primary', ...approveTrigger }}
              onClick={() => undefined}
            />
            <TriggerButton
              trigger={{ label: text.policyApprove, ...approveTrigger }}
              onClick={() => undefined}
            />
          </>
        ) : (
          <>
            <WriteConfirmModal
              trigger={{ label: text.approve, type: 'primary', ...approveTrigger }}
              title={text.approve}
              impact={<WriteImpact preview={preview} identity={identity} subjectId={subjectId} />}
              {...(guard === undefined ? {} : { guard })}
              consequence={text.consequence}
              confirmText={text.confirmPrice(proposedPrice)}
              reasonLabel={text.decisionReason}
              onConfirm={(reason) => decideAndCreate('approval', reason)}
            />
            <WriteConfirmModal
              trigger={{ label: text.policyApprove, ...approveTrigger }}
              title={text.policyTitle}
              impact={
                <WriteImpact
                  preview={preview}
                  identity={identity}
                  subjectId={subjectId}
                  extra={
                    <Typography.Text>
                      {text.policyScope(
                        preview.verdict.policyVersion,
                        maxRate === undefined ? '未记录' : formatPercent(maxRate),
                      )}
                    </Typography.Text>
                  }
                />
              }
              {...(guard === undefined ? {} : { guard })}
              consequence={text.consequence}
              confirmText={text.confirmPolicyPrice(proposedPrice)}
              reasonLabel={text.decisionReason}
              onConfirm={(reason) => decideAndCreate('policy-authorization', reason)}
            />
          </>
        )}
        <ActionModal<{ readonly reason?: string }>
          trigger={{ label: text.reject, danger: true }}
          title={text.rejectTitle}
          consequence={text.rejectConsequence}
          okText={text.reject}
          danger
          onSubmit={(values) => reject((values.reason ?? '').trim())}
        >
          <Form.Item
            name="reason"
            label={text.decisionReason}
            rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
          >
            <Input.TextArea rows={3} maxLength={500} showCount autoFocus />
          </Form.Item>
        </ActionModal>
      </Flex>
    );
  }

  const parameters = Object.entries(recommendation.proposedParameters);
  const summaryItems: DescriptionsProps['items'] = [
    {
      key: 'subject',
      label: text.subject,
      span: 'filled',
      children: <SubjectName identity={identity} subjectId={subjectId} />,
    },
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
          children: <ParameterValue name={name} value={value} currency={currency} />,
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

  const reloadAction = (
    <Button
      size="small"
      loading={reloading}
      onClick={() => {
        void reload();
      }}
    >
      {text.reload}
    </Button>
  );

  return (
    <DetailDrawer
      open
      onClose={onClose}
      size="large"
      title={`${text.title}：${codeLabel(ACTION_KIND_LABELS, recommendation.actionKind)}`}
      extra={
        <Button
          icon={<ExperimentOutlined />}
          loading={previewing}
          disabled={previewing || busy}
          onClick={() => {
            void runPreview();
          }}
        >
          {preview === undefined && previewFailure === undefined ? text.check : text.recheck}
        </Button>
      }
      footer={footer}
    >
      <section aria-label="建议审核" data-recommendation={recommendation.id}>
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {actionFailure !== undefined && (
            <div data-testid="review-failure">
              <Flex vertical gap={4}>
                <FailureAlert
                  failure={actionFailure}
                  {...(isStale(actionFailure) ? { action: reloadAction } : {})}
                />
                {commandFailedAfterDecision && (
                  <Typography.Text type="secondary">{text.commandFailed}</Typography.Text>
                )}
              </Flex>
            </div>
          )}
          <Descriptions bordered size="small" column={{ xs: 1, md: 2 }} items={summaryItems} />

          <section
            aria-label={text.impact}
            {...(preview === undefined ? {} : { 'data-passed': preview.verdict.passed })}
          >
            <Typography.Title level={5} style={{ marginTop: 0 }}>
              {text.impact}
            </Typography.Title>
            {previewing ? (
              <Flex vertical gap={4}>
                <Typography.Text type="secondary">{text.checking}</Typography.Text>
                <LoadingState rows={3} />
              </Flex>
            ) : previewFailure !== undefined ? (
              <FailureAlert
                failure={previewFailure}
                action={
                  isStale(previewFailure) ? (
                    reloadAction
                  ) : (
                    <Button
                      size="small"
                      onClick={() => {
                        void runPreview();
                      }}
                    >
                      {text.recheck}
                    </Button>
                  )
                }
              />
            ) : preview === undefined ? (
              <Button
                type="primary"
                icon={<ExperimentOutlined />}
                onClick={() => {
                  void runPreview();
                }}
              >
                {text.check}
              </Button>
            ) : (
              <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
                <Descriptions
                  bordered
                  size="small"
                  column={{ xs: 1, md: 2 }}
                  items={previewItems}
                />
                <div
                  data-testid={preview.verdict.passed ? 'guardrail-verdict' : 'guardrail-blocked'}
                >
                  <Alert
                    type={preview.verdict.passed ? 'success' : 'error'}
                    showIcon
                    role={preview.verdict.passed ? 'status' : 'alert'}
                    title={<VerdictHeadline verdict={preview.verdict} />}
                    {...(preview.verdict.passed
                      ? {}
                      : {
                          description: (
                            <GuardrailReasons
                              verdict={preview.verdict}
                              currency={preview.currencyCode}
                            />
                          ),
                        })}
                  />
                </div>
                <TechnicalDetails
                  data={{
                    evaluationId: preview.verdict.evaluationId,
                    purpose: preview.verdict.purpose,
                    policyVersion: preview.verdict.policyVersion,
                    detail: preview.verdict.detail,
                  }}
                />
              </Space>
            )}
          </section>
        </Space>
      </section>
    </DetailDrawer>
  );
}

/** Say what went wrong in terms of what the operator can do about it. */
export function describeFailure(failure: ConsoleFailure): string {
  return failureMessage(failure);
}
