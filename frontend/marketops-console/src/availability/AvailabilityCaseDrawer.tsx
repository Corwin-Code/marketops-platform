import { ReloadOutlined } from '@ant-design/icons';
import { Button, Descriptions, Flex, Space, Table, Tabs, Tag, Timeline, Typography } from 'antd';
import type { DescriptionsProps, TableColumnsType, TimelineProps } from 'antd';
import { useEffect, useState } from 'react';
import type { AcceptedException, AvailabilityCase, CaseJournalEntry } from '../api/availability';
import { fetchAvailabilityCase, fetchCaseExceptions, fetchCaseJournal } from '../api/availability';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { codeLabel } from '../i18n';
import {
  AUTHORITY_LEVEL_LABELS,
  CASE_EVENT_LABELS,
  CASE_STATE_LABELS,
  EXCEPTION_REASON_LABELS,
  EXCEPTION_SCOPE_LABELS,
  EXCEPTION_STATE_LABELS,
  LANE_LABELS,
  ROLE_LABELS,
  VERIFICATION_KIND_LABELS,
  VERIFICATION_OUTCOME_LABELS,
} from '../i18n/zh/availability';
import { caseActionText, caseDetailText as text } from '../i18n/zh/availabilityCases';
import {
  CodeTag,
  DateTime,
  DetailDrawer,
  EmptyState,
  FailureAlert,
  LoadingState,
  TriggerButton,
  VariantName,
} from '../ui';
import type { CaseAction } from './CaseActions';
import { CaseStateTag, DueClock, ExceptionTag } from './CaseCells';
import { actionKindLabel, offerOf, readAttestationReference } from './casePresentation';
import { causeLabel, subjectChannelLabel } from './riskPresentation';
import { EXCEPTION_STATE_COLORS, LANE_COLORS, VERIFICATION_OUTCOME_COLORS } from './tagColors';

/** What the case drawer needs. */
export interface AvailabilityCaseDrawerProps {
  readonly context: ConsoleRequest;
  /** The case shown, or nothing when the drawer is closed. */
  readonly caseId: string | undefined;
  /** The open tab, kept in the address bar. */
  readonly tab: string | undefined;
  readonly onTab: (tab: string) => void;
  readonly onClose: () => void;
  /** Take one action on the case; the page owns the dialogs. */
  readonly onAct: (action: CaseAction, governed: AvailabilityCase) => void;
  /** Open one acceptance request. */
  readonly onOpenException: (exceptionId: string) => void;
  /** Changes whenever something the case shows may have changed. */
  readonly generation: number;
  readonly now: Date;
}

const TABS = ['journal', 'exceptions', 'technical'] as const;

type Loaded =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly governed: AvailabilityCase }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/**
 * One case beside the list, loaded by its identifier from the address.
 *
 * The summary says what the case is about and where it stands, the two clocks
 * separately; the history names who did what; the acceptances open their own
 * decision page. The footer offers exactly the actions the server offers this
 * person, and says why the others are withheld.
 */
export function AvailabilityCaseDrawer(props: AvailabilityCaseDrawerProps): React.JSX.Element {
  if (props.caseId === undefined) {
    return <DetailDrawer open={false} onClose={props.onClose} title={text.title} />;
  }
  return <CaseDrawerBody key={props.caseId} {...props} caseId={props.caseId} />;
}

function CaseDrawerBody({
  context,
  caseId,
  tab,
  onTab,
  onClose,
  onAct,
  onOpenException,
  generation,
  now,
}: AvailabilityCaseDrawerProps & { readonly caseId: string }): React.JSX.Element {
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let live = true;
    void fetchAvailabilityCase(context, caseId).then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { kind: 'loaded', governed: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, caseId, attempt, generation]);

  const reload = (): void => {
    setAttempt((value) => value + 1);
  };

  const governed = loaded.kind === 'loaded' ? loaded.governed : undefined;
  const activeTab = (TABS as readonly string[]).includes(tab ?? '')
    ? (tab ?? 'journal')
    : 'journal';

  const footer =
    governed === undefined ? undefined : (
      <Flex gap={8} wrap align="flex-start" justify="flex-end">
        {(['REQUEST_EXCEPTION', 'ESCALATE', 'RECORD_ACTION'] as const).map((action) => {
          const offer = offerOf(governed.allowedActions, governed.blockedActions, action);
          return (
            <TriggerButton
              key={action}
              trigger={{
                label: ACTION_LABELS[action],
                ...(action === 'RECORD_ACTION' ? { type: 'primary' as const } : {}),
                disabled: !offer.allowed,
                disabledReason: offer.reason,
                reasonPlacement: 'inline',
              }}
              onClick={() => {
                onAct(action, governed);
              }}
            />
          );
        })}
      </Flex>
    );

  return (
    <DetailDrawer
      open
      onClose={onClose}
      size="large"
      title={
        governed?.subject === undefined ? (
          text.title
        ) : (
          <VariantName
            identity={governed.subject}
            productVariantId={governed.subject.productVariantId}
            extra={subjectChannelLabel(governed.subject)}
          />
        )
      }
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
      {governed === undefined ? null : (
        <Flex vertical gap={16} data-case={governed.id} data-case-state={governed.state}>
          <CaseSummary governed={governed} now={now} onOpenException={onOpenException} />
          <Tabs
            activeKey={activeTab}
            onChange={onTab}
            items={[
              {
                key: 'journal',
                label: text.tabJournal,
                children: (
                  <CaseJournal
                    context={context}
                    caseId={governed.id}
                    generation={generation + attempt}
                  />
                ),
              },
              {
                key: 'exceptions',
                label: text.tabExceptions,
                children: (
                  <CaseExceptions
                    context={context}
                    caseId={governed.id}
                    generation={generation + attempt}
                    onOpenException={onOpenException}
                  />
                ),
              },
              {
                key: 'technical',
                label: text.tabTechnical,
                children: <CaseTechnical governed={governed} />,
              },
            ]}
          />
        </Flex>
      )}
    </DetailDrawer>
  );
}

const ACTION_LABELS: Readonly<Record<CaseAction, string>> = {
  RECORD_ACTION: caseActionText.recordAction,
  ESCALATE: caseActionText.escalate,
  REQUEST_EXCEPTION: caseActionText.requestException,
};

/** What the case is about and where it stands. */
function CaseSummary({
  governed,
  now,
  onOpenException,
}: {
  readonly governed: AvailabilityCase;
  readonly now: Date;
  readonly onOpenException: (exceptionId: string) => void;
}): React.JSX.Element {
  const items: DescriptionsProps['items'] = [
    {
      key: 'cause',
      label: text.cause,
      span: 'filled',
      children: <Typography.Text strong>{causeLabel(governed.causeCode)}</Typography.Text>,
    },
    {
      key: 'severity',
      label: text.severity,
      children: <CodeTag labels={LANE_LABELS} code={governed.severity} colors={LANE_COLORS} />,
    },
    { key: 'state', label: text.state, children: <CaseStateTag governed={governed} /> },
    {
      key: 'role',
      label: text.role,
      children: <CodeTag labels={ROLE_LABELS} code={governed.accountableRoleCode} />,
    },
    {
      key: 'escalation',
      label: text.escalation,
      children: text.escalationValue(governed.escalationLevel),
    },
    {
      key: 'actionDue',
      label: text.actionDue,
      children: <DueClock governed={governed} now={now} clock="action" />,
    },
    {
      key: 'outcomeDue',
      label: text.outcomeDue,
      children: <DueClock governed={governed} now={now} clock="outcome" />,
    },
    {
      key: 'reopens',
      label: text.reopens,
      children: (
        <Typography.Text {...(governed.reopenCount > 0 ? { type: 'danger' as const } : {})}>
          {governed.reopenCount}
        </Typography.Text>
      ),
    },
    {
      key: 'firstActivated',
      label: text.firstActivated,
      children: <DateTime value={governed.firstActivatedAt} />,
    },
    {
      key: 'exception',
      label: text.openException,
      span: 'filled',
      children:
        governed.openException === null ? (
          <Typography.Text type="secondary">{text.noOpenException}</Typography.Text>
        ) : (
          <Space size={8}>
            <ExceptionTag open={governed.openException} />
            <Button
              size="small"
              type="link"
              onClick={() => {
                if (governed.openException !== null) onOpenException(governed.openException.id);
              }}
            >
              {text.viewException}
            </Button>
          </Space>
        ),
    },
  ];
  return <Descriptions bordered size="small" column={{ xs: 1, md: 2 }} items={items} />;
}

/**
 * Everything that happened to the case, with the people named.
 *
 * The reopens are part of it on purpose: "the fourth time this month" is the
 * question a reviewer asks, and this is where it is answered.
 */
function CaseJournal({
  context,
  caseId,
  generation,
}: {
  readonly context: ConsoleRequest;
  readonly caseId: string;
  readonly generation: number;
}): React.JSX.Element {
  const [journal, setJournal] = useState<readonly CaseJournalEntry[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let live = true;
    void fetchCaseJournal(context, caseId).then((outcome) => {
      if (!live) return;
      if (outcome.ok) {
        setJournal(outcome.value);
        setFailure(undefined);
      } else {
        setJournal([]);
        setFailure(outcome.failure);
      }
    });
    return () => {
      live = false;
    };
  }, [context, caseId, generation]);

  if (failure !== undefined) return <FailureAlert failure={failure} />;
  if (journal === undefined) return <LoadingState rows={2} />;
  if (journal.length === 0) return <EmptyState description={text.noJournal} />;

  const items: NonNullable<TimelineProps['items']> = [...journal].reverse().map((entry) => ({
    key: entry.sequenceNo,
    color: journalColor(entry),
    title: <DateTime value={entry.occurredAt} />,
    content: (
      <Flex vertical gap={2} data-event-kind={entry.eventKind}>
        <Flex gap={6} wrap align="center">
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
        </Flex>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {entry.actorUserId === null
            ? text.system
            : text.actorWithRole(
                entry.actorName ?? text.unknownPerson,
                codeLabel(ROLE_LABELS, entry.actorRoleCode),
              )}
        </Typography.Text>
        {entry.actionKind === null ? null : (
          <Typography.Text>{actionKindLabel(entry.actionKind)}</Typography.Text>
        )}
        {entry.verificationKind === null ? null : (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {codeLabel(VERIFICATION_KIND_LABELS, entry.verificationKind)}
            {entry.observedAt === null ? null : (
              <>
                {' · '}
                {text.observedAt} <DateTime value={entry.observedAt} />
              </>
            )}
          </Typography.Text>
        )}
        <Typography.Text type="secondary">{entry.reason}</Typography.Text>
        {entry.evidenceReference === null ? null : (
          <EvidenceReference reference={entry.evidenceReference} />
        )}
      </Flex>
    ),
  }));

  return <Timeline items={items} />;
}

function journalColor(entry: CaseJournalEntry): string {
  switch (entry.verificationOutcome) {
    case 'VERIFIED':
      return 'green';
    case 'FAILED':
    case 'REGRESSED':
      return 'red';
    case null:
      return entry.eventKind === 'ESCALATED' || entry.eventKind === 'REOPENED' ? 'red' : 'blue';
    default:
      return 'gray';
  }
}

/** An evidence reference; one that binds an inbound attestation says so. */
function EvidenceReference({ reference }: { readonly reference: string }): React.JSX.Element {
  const attestation = readAttestationReference(reference);
  return (
    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      {text.evidence}：
      {attestation === undefined ? null : (
        <Tag style={{ marginInlineEnd: 4 }}>
          {attestation.versionNo === undefined
            ? text.attestationReferenceUnversioned
            : text.attestationReference(attestation.versionNo)}
        </Tag>
      )}
      <Typography.Text type="secondary" style={{ fontSize: 12 }} copyable>
        {reference}
      </Typography.Text>
    </Typography.Text>
  );
}

/** Every acceptance recorded against the case; each opens its decision page. */
function CaseExceptions({
  context,
  caseId,
  generation,
  onOpenException,
}: {
  readonly context: ConsoleRequest;
  readonly caseId: string;
  readonly generation: number;
  readonly onOpenException: (exceptionId: string) => void;
}): React.JSX.Element {
  const [exceptions, setExceptions] = useState<readonly AcceptedException[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let live = true;
    void fetchCaseExceptions(context, caseId).then((outcome) => {
      if (!live) return;
      if (outcome.ok) {
        setExceptions(outcome.value);
        setFailure(undefined);
      } else {
        setExceptions([]);
        setFailure(outcome.failure);
      }
    });
    return () => {
      live = false;
    };
  }, [context, caseId, generation]);

  if (failure !== undefined) return <FailureAlert failure={failure} />;
  if (exceptions === undefined) return <LoadingState rows={2} />;
  if (exceptions.length === 0) return <EmptyState description={text.noExceptions} />;

  const columns: TableColumnsType<AcceptedException> = [
    {
      key: 'state',
      title: text.exceptionState,
      render: (_, accepted) => (
        <CodeTag
          labels={EXCEPTION_STATE_LABELS}
          code={accepted.state}
          colors={EXCEPTION_STATE_COLORS}
        />
      ),
    },
    {
      key: 'reason',
      title: text.exceptionReason,
      render: (_, accepted) => (
        <CodeTag labels={EXCEPTION_REASON_LABELS} code={accepted.reasonCode} />
      ),
    },
    {
      key: 'scope',
      title: text.exceptionScope,
      render: (_, accepted) => codeLabel(EXCEPTION_SCOPE_LABELS, accepted.scopeKind),
    },
    {
      key: 'authority',
      title: text.exceptionAuthority,
      render: (_, accepted) => (
        <CodeTag labels={AUTHORITY_LEVEL_LABELS} code={accepted.requiredAuthority} />
      ),
    },
    {
      key: 'requester',
      title: text.exceptionRequester,
      render: (_, accepted) => accepted.requestedByName ?? '—',
    },
    {
      key: 'period',
      title: text.exceptionPeriod,
      render: (_, accepted) => (
        <Flex vertical>
          <DateTime value={accepted.effectiveFrom} />
          <DateTime value={accepted.expiresAt} />
        </Flex>
      ),
    },
    {
      key: 'review',
      title: text.exceptionReview,
      render: (_, accepted) => <DateTime value={accepted.reviewAt} />,
    },
    {
      key: 'open',
      title: '',
      render: (_, accepted) => (
        <Button
          size="small"
          type="link"
          onClick={() => {
            onOpenException(accepted.id);
          }}
        >
          {text.viewException}
        </Button>
      ),
    },
  ];

  return (
    <Table<AcceptedException>
      size="small"
      rowKey="id"
      pagination={false}
      dataSource={[...exceptions]}
      columns={columns}
      scroll={{ x: 'max-content' }}
      onRow={(accepted) =>
        ({ 'data-exception-state': accepted.state }) as React.HTMLAttributes<HTMLElement>
      }
    />
  );
}

/** Identifiers for support and audit, out of the way. */
function CaseTechnical({ governed }: { readonly governed: AvailabilityCase }): React.JSX.Element {
  const id = (value: string | null): React.ReactNode =>
    value === null ? (
      '—'
    ) : (
      <Typography.Text copyable code style={{ fontSize: 12 }}>
        {value}
      </Typography.Text>
    );
  return (
    <Descriptions
      size="small"
      column={1}
      items={[
        { key: 'case', label: text.caseId, children: id(governed.id) },
        { key: 'card', label: text.cardId, children: id(governed.cardId) },
        { key: 'child', label: text.childId, children: id(governed.childId) },
        { key: 'key', label: text.causeKey, children: id(governed.causeKey) },
        {
          key: 'original',
          label: text.originalActionDue,
          children: <DateTime value={governed.originalActionDueAt} />,
        },
        {
          key: 'lastEvidence',
          label: text.lastEvidence,
          children: <DateTime value={governed.lastEvidenceAt} />,
        },
        {
          key: 'improvement',
          label: text.improvementFirstSeen,
          children: <DateTime value={governed.improvementFirstSeenAt} />,
        },
      ]}
    />
  );
}
