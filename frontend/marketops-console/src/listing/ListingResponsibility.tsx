import { Alert, Flex, Space, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  acknowledgeListingResponsibility,
  fetchListingResponsibility,
  type ListingResponsibility as Responsibility,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { responsibilityText as text } from '../i18n/zh/listingHealth';
import { ConfirmButton, LoadingState, SectionCollapse, TechnicalDetails } from '../ui';
import type { SectionFlag } from '../ui';
import { Code, Details, IdText, ListingProblem, When, YesNo } from './ListingCommon';
import { ListingDeferral } from './ListingDeferral';
import { ListingDependencyHold } from './ListingDependencyHold';

/** The status of one responsibility clock. */
export type ResponsibilityStatus = NonNullable<Responsibility['status']>;

/** A breach answer: red when breached, so a late clock is never read as fine. */
function Breach({ value }: { readonly value: boolean | undefined }): React.JSX.Element {
  return value === true ? (
    <Typography.Text type="danger" strong data-state="breached">
      {t('yes')}
    </Typography.Text>
  ) : (
    <YesNo value={value} />
  );
}

/** Whether either deadline of the clock has been missed. */
export function responsibilityBreached(status: ResponsibilityStatus): boolean {
  return status.acknowledgementBreached === true || status.actionBreached === true;
}

/**
 * Warnings that stay visible on a folded clock: missed deadlines in red,
 * continuous risk, and an undeclared policy or calendar.
 */
export function responsibilityFlags(status: ResponsibilityStatus): SectionFlag[] {
  const flags: SectionFlag[] = [];
  if (status.acknowledgementBreached === true) {
    flags.push({ key: 'ackLate', label: text.ackBreached, color: 'error' });
  }
  if (status.actionBreached === true) {
    flags.push({ key: 'actionLate', label: text.actionBreached, color: 'error' });
  }
  if (status.clockState === 'CONTINUOUS_RISK') {
    flags.push({ key: 'continuous', label: text.continuousRisk, color: 'error' });
  }
  if (status.clockState === 'SLO_UNRESOLVED') {
    flags.push({ key: 'slo', label: text.sloUndeclared });
  }
  if (status.clockState === 'COVERAGE_UNRESOLVED') {
    flags.push({ key: 'coverage', label: text.coverageUndeclared });
  }
  return flags;
}

/** 「确认截止 … · 行动截止 …」, with 未声明 where a deadline is not set. */
export function ResponsibilityDue({
  status,
}: {
  readonly status: ResponsibilityStatus;
}): React.JSX.Element {
  const due = (value: string | undefined): React.JSX.Element =>
    value === undefined ? (
      <Typography.Text type="secondary">{text.undeclared}</Typography.Text>
    ) : (
      <When value={value} />
    );
  return (
    <Flex gap={4} wrap align="center" component="span">
      <Typography.Text type="secondary">{text.dueSummary}</Typography.Text>
      {due(status.acknowledgementDueAt)}
      <Typography.Text type="secondary">·</Typography.Text>
      <Typography.Text type="secondary">{text.actionDueSummary}</Typography.Text>
      {due(status.actionDueAt)}
    </Flex>
  );
}

/**
 * The clock of one responsibility: loaded on sight, with acknowledgement,
 * deferral and dependency hold beside it.
 */
export function ListingResponsibility({
  context,
  actionId,
}: {
  readonly context: ConsoleRequest;
  readonly actionId: string;
}): React.JSX.Element {
  const epoch = useRef(0);
  const [answer, setAnswer] = useState<Responsibility>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [loading, setLoading] = useState(true);

  const load = useCallback(async (): Promise<void> => {
    const ticket = ++epoch.current;
    setLoading(true);
    const result = await fetchListingResponsibility(context, actionId);
    if (ticket !== epoch.current) return;
    setLoading(false);
    if (result.ok) {
      setAnswer(result.value);
      setFailure(undefined);
    } else {
      setAnswer(undefined);
      setFailure(result.failure);
    }
  }, [context, actionId]);

  useEffect(() => {
    setAnswer(undefined);
    setFailure(undefined);
    void load();
    return () => {
      epoch.current += 1;
    };
  }, [load]);

  const reload = (): void => {
    void load();
  };
  const status = answer?.status;
  const state = status?.clockState;

  const acknowledge = async (): Promise<void> => {
    const ticket = ++epoch.current;
    setFailure(undefined);
    const accepted = await acknowledgeListingResponsibility(context, actionId);
    if (ticket !== epoch.current) return;
    if (!accepted.ok) {
      setFailure(accepted.failure);
      return;
    }
    await load();
  };

  return (
    <section aria-label={t('responsibilityTitle')} data-state={loading ? 'loading' : 'loaded'}>
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        {failure !== undefined && <ListingProblem failure={failure} />}
        {loading && answer === undefined && failure === undefined && <LoadingState rows={2} />}
        {answer?.bound === false && (
          <Alert type="warning" showIcon title={t('responsibilityUnbound')} />
        )}
        {status !== undefined && (
          <>
            <Alert
              type={state === 'IN_COVERAGE' ? 'info' : 'warning'}
              showIcon
              title={t(
                state === 'IN_COVERAGE'
                  ? 'responsibilityIn'
                  : state === 'OUT_OF_COVERAGE'
                    ? 'responsibilityOut'
                    : state === 'CONTINUOUS_RISK'
                      ? 'responsibilityContinuous'
                      : state === 'SLO_UNRESOLVED'
                        ? 'responsibilitySloUnknown'
                        : 'responsibilityCoverageUnknown',
              )}
              description={t('responsibilityHelp')}
            />
            <Flex gap={8} wrap align="center">
              <ConfirmButton
                type="primary"
                size="small"
                title={text.actionQuestion}
                description={text.actionConsequence}
                disabled={status.acknowledgedAt !== undefined}
                {...(status.acknowledgedAt !== undefined
                  ? { disabledReason: text.acknowledged }
                  : {})}
                onConfirm={acknowledge}
              >
                {text.acknowledge}
              </ConfirmButton>
              <ListingDeferral
                context={context}
                target={{ kind: 'ACTION', actionId }}
                current={status.deferral}
                onChanged={reload}
              />
              <ListingDependencyHold
                context={context}
                target={{ kind: 'ACTION', actionId }}
                current={status.dependencyHold}
                onChanged={reload}
              />
            </Flex>
            <ResponsibilityTimes status={status} />
          </>
        )}
      </Space>
    </section>
  );
}

/**
 * Every deadline and time of one clock, folded, with the two deadlines and
 * every warning in the header. It opens by itself when a deadline was missed.
 */
export function ResponsibilityTimes({
  status,
}: {
  readonly status: ResponsibilityStatus;
}): React.JSX.Element {
  return (
    <div data-task={status.taskId}>
      <SectionCollapse
        size="small"
        items={[
          {
            key: 'clock',
            title: text.clockDetails,
            summary: <ResponsibilityDue status={status} />,
            flags: responsibilityFlags(status),
            defaultOpen: responsibilityBreached(status),
            children: (
              <>
                <Details
                  items={[
                    {
                      key: 'clock',
                      label: t('responsibilityClock'),
                      children: <Code family="clockState" code={status.clockState} />,
                    },
                    {
                      key: 'origin',
                      label: t('responsibilityOrigin'),
                      children: <When value={status.firstRaisedAt} />,
                    },
                    {
                      key: 'ackDue',
                      label: t('responsibilityAckDue'),
                      children: <When value={status.acknowledgementDueAt} />,
                    },
                    {
                      key: 'actionDue',
                      label: t('responsibilityActionDue'),
                      children: <When value={status.actionDueAt} />,
                    },
                    {
                      key: 'originalActionDue',
                      label: t('responsibilityOriginalActionDue'),
                      children: <When value={status.originalActionDueAt} />,
                    },
                    {
                      key: 'dependencyElapsed',
                      label: t('responsibilityDependencyElapsed'),
                      children: status.dependencyHoldElapsedSeconds,
                    },
                    {
                      key: 'outcomeDue',
                      label: t('responsibilityOutcomeDue'),
                      children: <When value={status.outcomeMaturityDueAt} />,
                    },
                    {
                      key: 'next',
                      label: t('responsibilityNext'),
                      children: <When value={status.nextCoveredAt} />,
                    },
                    {
                      key: 'ack',
                      label: t('responsibilityAck'),
                      children: <When value={status.acknowledgedAt} />,
                    },
                    {
                      key: 'action',
                      label: t('responsibilityAction'),
                      children: <When value={status.firstAttributableActionAt} />,
                    },
                    {
                      key: 'ackLate',
                      label: t('responsibilityAckLate'),
                      children: <Breach value={status.acknowledgementBreached} />,
                    },
                    {
                      key: 'actionLate',
                      label: t('responsibilityActionLate'),
                      children: <Breach value={status.actionBreached} />,
                    },
                  ]}
                />
                <TechnicalDetails>
                  <Space orientation="vertical" size={2}>
                    <IdText label={t('responsibilityTask')} value={status.taskId} />
                    <IdText
                      label={t('responsibilityBasis')}
                      value={`${status.calibrationPackageId ?? '—'} · ${
                        status.calibrationVersion === undefined
                          ? '—'
                          : String(status.calibrationVersion)
                      } · ${status.basisDigest}`}
                    />
                  </Space>
                </TechnicalDetails>
              </>
            ),
          },
        ]}
      />
    </div>
  );
}
