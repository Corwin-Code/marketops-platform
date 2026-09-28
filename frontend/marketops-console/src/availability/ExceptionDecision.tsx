import { ReloadOutlined } from '@ant-design/icons';
import { App, Button, Descriptions, Flex, Form, Input, Timeline, Typography } from 'antd';
import type { DescriptionsProps, TimelineProps } from 'antd';
import { useEffect, useState } from 'react';
import type { ExceptionDetail } from '../api/availability';
import { decideException, fetchExceptionDetail, withdrawException } from '../api/availability';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { formatStoreTime } from '../format';
import { codeLabel } from '../i18n';
import {
  AUTHORITY_LEVEL_LABELS,
  CASE_STATE_LABELS,
  EXCEPTION_DECISION_LABELS,
  EXCEPTION_REASON_LABELS,
  EXCEPTION_SCOPE_LABELS,
  EXCEPTION_STATE_LABELS,
  LANE_LABELS,
  ROLE_LABELS,
} from '../i18n/zh/availability';
import { exceptionDecisionText as text } from '../i18n/zh/availabilityCases';
import {
  ActionModal,
  CodeTag,
  DateTime,
  DetailDrawer,
  InfoTip,
  Money,
  StepUpNotice,
  TechnicalDetails,
  VariantName,
} from '../ui';
import type { SubmitOutcome } from '../ui';
import { offerOf } from './casePresentation';
import { causeLabel, subjectChannelLabel } from './riskPresentation';
import { EXCEPTION_DECISION_COLORS, EXCEPTION_STATE_COLORS, LANE_COLORS } from './tagColors';

const TEXT_LIMIT = 500;

/** What the decision drawer needs. */
export interface ExceptionDecisionProps {
  readonly context: ConsoleRequest;
  /** The request shown, or nothing when the drawer is closed. */
  readonly exceptionId: string | undefined;
  readonly onClose: () => void;
  /** A decision or withdrawal changed the request and its case. */
  readonly onChanged: () => void;
}

type Loaded =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly detail: ExceptionDetail }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/**
 * One acceptance request in full, and the decision on it.
 *
 * Loaded by its identifier from the address, so an approver can be sent a
 * link. Approve, reject and withdraw are three separate dialogs, each with its
 * own required reason and its own consequence; each is offered or withheld
 * strictly as the server says, with the server's reason. A decision is never
 * offered to somebody it would block, and a stale sign-in is named before a
 * reason is typed rather than after the request is refused.
 */
export function ExceptionDecision(props: ExceptionDecisionProps): React.JSX.Element {
  if (props.exceptionId === undefined) {
    return <DetailDrawer open={false} onClose={props.onClose} title={text.title} />;
  }
  return <DecisionBody key={props.exceptionId} {...props} exceptionId={props.exceptionId} />;
}

function DecisionBody({
  context,
  exceptionId,
  onClose,
  onChanged,
}: ExceptionDecisionProps & { readonly exceptionId: string }): React.JSX.Element {
  const { message } = App.useApp();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let live = true;
    void fetchExceptionDetail(context, exceptionId).then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { kind: 'loaded', detail: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, exceptionId, attempt]);

  const reload = (): void => {
    setAttempt((value) => value + 1);
  };

  const detail = loaded.kind === 'loaded' ? loaded.detail : undefined;

  const after = (success: string): void => {
    void message.success(success);
    onChanged();
    reload();
  };

  const decide = async (approved: boolean, reason: string): Promise<SubmitOutcome> => {
    const outcome = await decideException(context, exceptionId, approved, reason);
    if (!outcome.ok) return outcome.failure;
    after(approved ? text.approved : text.rejected);
    return undefined;
  };

  const withdraw = async (reason: string): Promise<SubmitOutcome> => {
    const outcome = await withdrawException(context, exceptionId, reason);
    if (!outcome.ok) return outcome.failure;
    after(text.withdrawn);
    return undefined;
  };

  let footer: React.ReactNode;
  if (detail !== undefined) {
    const approve = offerOf(detail.allowedActions, detail.blockedActions, 'APPROVE');
    const reject = offerOf(detail.allowedActions, detail.blockedActions, 'REJECT');
    const retract = offerOf(detail.allowedActions, detail.blockedActions, 'WITHDRAW');
    const stepUp = detail.viewer !== undefined && !detail.viewer.stepUpSatisfied;
    const until = formatStoreTime(detail.exception.expiresAt);
    footer = (
      <Flex gap={8} wrap align="flex-start" justify="flex-end">
        <ActionModal<{ readonly reason?: string }>
          trigger={{
            label: text.withdraw,
            disabled: !retract.allowed,
            disabledReason: retract.reason,
            reasonPlacement: 'inline',
          }}
          title={text.withdrawTitle}
          consequence={text.withdrawConsequence}
          okText={text.withdraw}
          onSubmit={(values) => withdraw((values.reason ?? '').trim())}
        >
          <ReasonField label={text.withdrawReason} />
        </ActionModal>
        <ActionModal<{ readonly reason?: string }>
          trigger={{
            label: text.reject,
            danger: true,
            disabled: !reject.allowed,
            disabledReason: reject.reason,
            reasonPlacement: 'inline',
          }}
          title={text.rejectTitle}
          consequence={text.rejectConsequence}
          summary={stepUp ? <StepUpNotice /> : undefined}
          okText={text.reject}
          danger
          onSubmit={(values) => decide(false, (values.reason ?? '').trim())}
        >
          <ReasonField label={text.decisionReason} />
        </ActionModal>
        <ActionModal<{ readonly reason?: string }>
          trigger={{
            label: text.approve,
            type: 'primary',
            disabled: !approve.allowed,
            disabledReason: approve.reason,
            reasonPlacement: 'inline',
          }}
          title={text.approveTitle}
          consequence={text.approveConsequence(until)}
          summary={stepUp ? <StepUpNotice /> : undefined}
          okText={text.approve}
          onSubmit={(values) => decide(true, (values.reason ?? '').trim())}
        >
          <ReasonField label={text.decisionReason} />
        </ActionModal>
      </Flex>
    );
  }

  return (
    <DetailDrawer
      open
      onClose={onClose}
      size="large"
      title={text.title}
      extra={
        <Button icon={<ReloadOutlined />} onClick={reload}>
          {text.refresh}
        </Button>
      }
      {...(footer === undefined ? {} : { footer })}
      loading={loaded.kind === 'loading'}
      failure={loaded.kind === 'failed' ? loaded.failure : undefined}
      onRetry={() => {
        setLoaded({ kind: 'loading' });
        reload();
      }}
    >
      {detail === undefined ? null : <DecisionContent detail={detail} />}
    </DetailDrawer>
  );
}

function ReasonField({ label }: { readonly label: string }): React.JSX.Element {
  return (
    <Form.Item
      name="reason"
      label={label}
      rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
    >
      <Input.TextArea rows={3} maxLength={TEXT_LIMIT} showCount autoFocus />
    </Form.Item>
  );
}

/** Everything recorded about the request, the terms a decision would use now, and its history. */
function DecisionContent({ detail }: { readonly detail: ExceptionDetail }): React.JSX.Element {
  const exception = detail.exception;
  const viewerId = detail.viewer?.userId;
  const person = (name: string | null, userId: string | null): string => {
    const shown = name ?? (userId === null ? '—' : userId.slice(0, 8));
    return userId !== null && userId === viewerId ? `${shown}${text.you}` : shown;
  };
  const terms = detail.decisionTerms;

  const items: DescriptionsProps['items'] = [
    {
      key: 'state',
      label: text.state,
      children: (
        <CodeTag
          labels={EXCEPTION_STATE_LABELS}
          code={exception.state}
          colors={EXCEPTION_STATE_COLORS}
        />
      ),
    },
    ...(detail.subject === undefined
      ? []
      : [
          {
            key: 'product',
            label: text.product,
            span: 'filled' as const,
            children: (
              <VariantName
                identity={detail.subject}
                productVariantId={detail.subject.productVariantId}
                extra={subjectChannelLabel(detail.subject)}
              />
            ),
          },
        ]),
    ...(detail.caseSummary === undefined
      ? []
      : [
          {
            key: 'cause',
            label: text.cause,
            children: causeLabel(detail.caseSummary.causeCode),
          },
          {
            key: 'severity',
            label: text.severity,
            children: (
              <CodeTag
                labels={LANE_LABELS}
                code={detail.caseSummary.severity}
                colors={LANE_COLORS}
              />
            ),
          },
          {
            key: 'caseState',
            label: text.caseState,
            children: <CodeTag labels={CASE_STATE_LABELS} code={detail.caseSummary.state} />,
          },
        ]),
    {
      key: 'scope',
      label: text.scope,
      children: codeLabel(EXCEPTION_SCOPE_LABELS, exception.scopeKind),
    },
    {
      key: 'reason',
      label: text.reasonCode,
      children: <CodeTag labels={EXCEPTION_REASON_LABELS} code={exception.reasonCode} />,
    },
    {
      key: 'rationale',
      label: text.rationale,
      span: 'filled',
      children: (
        <Typography.Text style={{ whiteSpace: 'pre-wrap' }}>{exception.rationale}</Typography.Text>
      ),
    },
    {
      key: 'consequence',
      label: text.consequence,
      span: 'filled',
      children: (
        <Typography.Text style={{ whiteSpace: 'pre-wrap' }}>
          {exception.expectedConsequence}
        </Typography.Text>
      ),
    },
    {
      key: 'amount',
      label: text.amount,
      children: (
        <Money value={exception.consequenceAmount} currency={exception.consequenceCurrency} />
      ),
    },
    {
      key: 'period',
      label: text.period,
      children: (
        <Flex vertical>
          <DateTime value={exception.effectiveFrom} />
          <DateTime value={exception.expiresAt} />
        </Flex>
      ),
    },
    { key: 'review', label: text.review, children: <DateTime value={exception.reviewAt} /> },
    {
      key: 'evidence',
      label: text.evidence,
      children:
        exception.evidenceReference === null ? (
          '—'
        ) : (
          <Typography.Text copyable>{exception.evidenceReference}</Typography.Text>
        ),
    },
    {
      key: 'requester',
      label: text.requester,
      children: person(exception.requestedByName, exception.requestedByUserId),
    },
    {
      key: 'requestedAt',
      label: text.requestedAt,
      children: <DateTime value={exception.requestedAt} relative />,
    },
    {
      key: 'occurrence',
      label: text.occurrence,
      children:
        exception.occurrenceCount === null ? '—' : text.occurrenceValue(exception.occurrenceCount),
    },
    {
      key: 'recorded',
      label: text.recordedAuthority,
      children: <CodeTag labels={AUTHORITY_LEVEL_LABELS} code={exception.requiredAuthority} />,
    },
    ...(terms === undefined || exception.state !== 'REQUESTED'
      ? []
      : [
          {
            key: 'current',
            label: (
              <span>
                {text.currentAuthority}
                <InfoTip title={text.currentAuthorityHelp} />
              </span>
            ),
            children: terms.policyInForce ? (
              <CodeTag labels={AUTHORITY_LEVEL_LABELS} code={terms.requiredAuthority} />
            ) : (
              <Typography.Text type="warning">{text.noPolicy}</Typography.Text>
            ),
          },
          {
            key: 'separation',
            label: text.separation,
            children: terms.separationRequired
              ? text.separationRequired
              : text.separationNotRequired,
          },
          ...(terms.periodExceedsMaximum
            ? [
                {
                  key: 'tooLong',
                  label: text.period,
                  children: <Typography.Text type="danger">{text.periodTooLong}</Typography.Text>,
                },
              ]
            : []),
        ]),
    ...(exception.invalidationReason === null
      ? []
      : [
          {
            key: 'invalidation',
            label: text.invalidation,
            span: 'filled' as const,
            children: exception.invalidationReason,
          },
          {
            key: 'invalidatedAt',
            label: text.invalidatedAt,
            children: <DateTime value={exception.invalidatedAt} />,
          },
        ]),
  ];

  const timeline: NonNullable<TimelineProps['items']> = detail.decisions.map((decision) => ({
    key: decision.id,
    color:
      decision.decision === 'APPROVED'
        ? 'green'
        : decision.decision === 'REJECTED'
          ? 'red'
          : 'orange',
    title: <DateTime value={decision.decidedAt} />,
    content: (
      <Flex vertical gap={2} data-decision={decision.decision}>
        <Flex gap={6} wrap align="center">
          <CodeTag
            labels={EXCEPTION_DECISION_LABELS}
            code={decision.decision}
            colors={EXCEPTION_DECISION_COLORS}
          />
          <Typography.Text>
            {text.decisionBy(
              person(decision.decidedByName, decision.decidedByUserId),
              codeLabel(ROLE_LABELS, decision.decidedByRoleCode),
            )}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.decisionAuthority(codeLabel(AUTHORITY_LEVEL_LABELS, decision.authorityLevel))}
          </Typography.Text>
        </Flex>
        <Typography.Text type="secondary">{decision.reason}</Typography.Text>
        {decision.grantedExpiresAt === null ? null : (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.decisionGranted} <DateTime value={decision.grantedEffectiveFrom} /> –{' '}
            <DateTime value={decision.grantedExpiresAt} />
          </Typography.Text>
        )}
      </Flex>
    ),
  }));

  return (
    <Flex vertical gap={16} data-exception={exception.id} data-state={exception.state}>
      <Descriptions bordered size="small" column={{ xs: 1, md: 2 }} items={items} />
      <Flex vertical gap={8}>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {text.decisionsTitle}
        </Typography.Title>
        {timeline.length === 0 ? (
          <Typography.Text type="secondary">{text.noDecisions}</Typography.Text>
        ) : (
          <Timeline items={timeline} />
        )}
      </Flex>
      <TechnicalDetails
        data={{
          exceptionId: exception.id,
          caseId: exception.caseId,
          childId: exception.childId,
          scopeReference: exception.scopeReference,
          decisionOwnerRoleCode: exception.decisionOwnerRoleCode,
        }}
      />
    </Flex>
  );
}
