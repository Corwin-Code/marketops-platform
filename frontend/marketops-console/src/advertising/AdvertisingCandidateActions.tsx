import { Flex, Form, Input, Space, Typography } from 'antd';
import { useRef, useState } from 'react';
import { previewAdvertisingCandidate } from '../api/console';
import type {
  AdvertisingCandidateAction,
  AdvertisingDecisionPreview,
  ConsoleFailure,
  ConsoleRequest,
} from '../api/console';
import type { AdvertisingWorkflowCandidate } from '../api/advertising';
import { formatMoney } from '../format';
import { BID_UNIT_LABELS } from '../i18n/zh/advertising';
import { dialog } from '../i18n/zh/common';
import { ActionModal } from '../ui/ActionModal';
import type { SubmitOutcome } from '../ui/ActionModal';
import { CodeTag } from '../ui/CodeTag';
import { failureMessage } from '../ui/FailureAlert';
import { Money } from '../ui/Money';
import { WriteConfirmModal } from '../ui/WriteConfirmModal';
import type { WriteGuard } from '../ui/WriteConfirmModal';
import { AbsentValue, ReasonTags } from './shared';

/** The decisions that settle which candidate goes forward; neither reaches the platform. */
const DECISION_TEXT = {
  SELECT_CANDIDATE: {
    label: '选定该候选',
    title: '选定出价候选',
    consequence: '选定后进入背书与审批流程，平台上的出价不会改变。',
    ok: '确认选定',
  },
  REJECT_CANDIDATE: {
    label: '驳回候选',
    title: '驳回出价候选',
    consequence: '驳回后该候选不能再被选定。',
    ok: '确认驳回',
  },
} as const;

/** The steps that authorise the bid change, each confirmed against the current rule check. */
const WRITE_TEXT = {
  ENDORSE: {
    label: '运营背书',
    title: '为出价变更做运营背书',
    consequence:
      '背书会重新校验当前授权与范围。背书本身不会写入平台，批准并创建指令后才会发往平台。',
    confirm: (target: string) => `确认背书：出价改为 ${target}`,
    reasonLabel: '背书理由',
    reasonRequired: true,
  },
  APPROVE: {
    label: '批准精确变更',
    title: '批准精确出价变更',
    consequence:
      '批准会重新校验当前授权与范围。批准本身不会写入平台，之后创建的指令才会经写入闸门发往平台。',
    confirm: (target: string) => `确认批准：出价改为 ${target}`,
    reasonLabel: '批准理由',
    reasonRequired: true,
  },
  CREATE_COMMAND: {
    label: '创建已批准的指令',
    title: '创建出价指令',
    consequence:
      '确认后创建出价指令，经写入闸门检查后才会调用平台，随后回读并记入审计；创建时会重新校验当前授权。',
    confirm: (target: string) => `确认创建指令：出价改为 ${target}`,
    reasonLabel: '备注（可选，不会保存；理由已在背书和批准时记录）',
    reasonRequired: false,
  },
} as const;

/** The button text of a candidate action, as the success message names it. */
export function candidateActionLabel(action: AdvertisingCandidateAction): string {
  return action === 'SELECT_CANDIDATE' || action === 'REJECT_CANDIDATE'
    ? DECISION_TEXT[action].label
    : WRITE_TEXT[action].label;
}

/** A candidate's exact native bid change. */
export function BidChange({
  candidate,
}: {
  readonly candidate: AdvertisingWorkflowCandidate;
}): React.JSX.Element {
  return (
    <Space size={6} wrap>
      <Typography.Text type="secondary">平台出价</Typography.Text>
      {candidate.currentBidAmount === undefined ? (
        <AbsentValue label="未确定" />
      ) : (
        <Money value={candidate.currentBidAmount} currency={candidate.currency ?? null} />
      )}
      <Typography.Text type="secondary">→</Typography.Text>
      {candidate.targetBidAmount === undefined ? (
        <AbsentValue label="未确定" />
      ) : (
        <Money value={candidate.targetBidAmount} currency={candidate.currency ?? null} strong />
      )}
      <CodeTag labels={BID_UNIT_LABELS} code={candidate.unit ?? 'UNRESOLVED'} />
    </Space>
  );
}

/** The rule check behind a bid decision: the verdict and every reason that holds it. */
export function DecisionVerdict({
  preview,
}: {
  readonly preview: AdvertisingDecisionPreview;
}): React.JSX.Element {
  return (
    <Flex vertical gap={6}>
      <Typography.Text>
        {preview.verdict.passed ? '当前规则校验通过。' : '当前规则校验未通过。'}
      </Typography.Text>
      {preview.verdict.reasons.length > 0 && <ReasonTags codes={preview.verdict.reasons} />}
      {preview.gateReasons.length > 0 && (
        <Flex vertical gap={4}>
          <Typography.Text type="secondary">发往平台前的写入闸门目前不放行：</Typography.Text>
          <ReasonTags codes={preview.gateReasons} color="warning" />
        </Flex>
      )}
      {preview.unresolvedReasons.length > 0 && (
        <Flex vertical gap={4}>
          <Typography.Text type="secondary">尚未确定：</Typography.Text>
          <ReasonTags codes={preview.unresolvedReasons} color="warning" />
        </Flex>
      )}
    </Flex>
  );
}

/**
 * One action on one candidate, in its own dialog.
 *
 * Selecting and rejecting ask for their own reason. Endorsing, approving and
 * creating the command restate the exact bid change and run the current rule
 * check when the dialog opens, so nothing is confirmed against a verdict the
 * person has not seen.
 */
export function AdvertisingCandidateAction({
  context,
  candidate,
  action,
  onAct,
}: {
  readonly context: ConsoleRequest;
  readonly candidate: AdvertisingWorkflowCandidate;
  readonly action: AdvertisingCandidateAction;
  readonly onAct: (reason: string) => Promise<SubmitOutcome>;
}): React.JSX.Element {
  if (action === 'SELECT_CANDIDATE' || action === 'REJECT_CANDIDATE') {
    const text = DECISION_TEXT[action];
    const reject = action === 'REJECT_CANDIDATE';
    return (
      <ActionModal<{ readonly reason?: string }>
        trigger={{ label: text.label, type: reject ? 'default' : 'primary', danger: reject }}
        title={text.title}
        consequence={text.consequence}
        summary={<BidChange candidate={candidate} />}
        okText={text.ok}
        danger={reject}
        onSubmit={(values) => onAct((values.reason ?? '').trim())}
      >
        <Form.Item
          name="reason"
          label="决策理由"
          rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
        >
          <Input.TextArea
            rows={3}
            maxLength={2000}
            showCount
            placeholder={dialog.reasonPlaceholder}
            autoFocus
          />
        </Form.Item>
      </ActionModal>
    );
  }
  return <CandidateWrite context={context} candidate={candidate} action={action} onAct={onAct} />;
}

/** The rule check of the newest opening: still running while neither is set. */
interface Check {
  readonly preview?: AdvertisingDecisionPreview;
  readonly failure?: ConsoleFailure;
}

function CandidateWrite({
  context,
  candidate,
  action,
  onAct,
}: {
  readonly context: ConsoleRequest;
  readonly candidate: AdvertisingWorkflowCandidate;
  readonly action: keyof typeof WRITE_TEXT;
  readonly onAct: (reason: string) => Promise<SubmitOutcome>;
}): React.JSX.Element {
  const text = WRITE_TEXT[action];
  const [check, setCheck] = useState<Check>({});
  // Only the newest opening's answer counts: a slow check from an earlier
  // opening must not replace the verdict the dialog now shows.
  const opening = useRef(0);
  const runCheck = (): void => {
    const current = ++opening.current;
    setCheck({});
    void previewAdvertisingCandidate(context, candidate.recommendationId).then((result) => {
      if (current !== opening.current) return;
      setCheck(result.ok ? { preview: result.value } : { failure: result.failure });
    });
  };
  const guard: WriteGuard | undefined =
    check.preview === undefined
      ? undefined
      : {
          passed: check.preview.verdict.passed,
          content: <DecisionVerdict preview={check.preview} />,
        };
  const blockedReason =
    check.failure !== undefined
      ? `规则校验未能完成：${failureMessage(check.failure)}`
      : check.preview === undefined
        ? '正在校验当前授权与范围…'
        : undefined;
  return (
    <WriteConfirmModal
      trigger={{ label: text.label, type: 'primary' }}
      title={text.title}
      impact={<BidChange candidate={candidate} />}
      {...(guard === undefined ? {} : { guard })}
      {...(blockedReason === undefined ? {} : { blockedReason })}
      consequence={text.consequence}
      confirmText={text.confirm(formatMoney(candidate.targetBidAmount, candidate.currency))}
      reasonLabel={text.reasonLabel}
      {...(text.reasonRequired ? {} : { reasonPlaceholder: dialog.notePlaceholder })}
      reasonRequired={text.reasonRequired}
      onOpen={runCheck}
      onConfirm={(reason) => onAct(reason)}
    />
  );
}
