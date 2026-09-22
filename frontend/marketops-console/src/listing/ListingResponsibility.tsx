import { Alert, Button, Divider, Space, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  acknowledgeListingResponsibility,
  fetchListingResponsibility,
  type ListingResponsibility as Responsibility,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { ConfirmButton, TechnicalDetails } from '../ui';
import { Code, Details, IdText, ListingProblem, When, YesNo } from './ListingCommon';
import { ListingDeferral } from './ListingDeferral';
import { ListingDependencyHold } from './ListingDependencyHold';

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
  const [pending, setPending] = useState(false);
  useEffect(() => {
    epoch.current += 1;
    setAnswer(undefined);
    setFailure(undefined);
    setPending(false);
    return () => {
      epoch.current += 1;
    };
  }, [context, actionId]);
  const status = answer?.status;
  const state = status?.clockState;
  const acknowledge = async (): Promise<void> => {
    const ticket = ++epoch.current;
    setPending(true);
    setFailure(undefined);
    const accepted = await acknowledgeListingResponsibility(context, actionId);
    if (ticket !== epoch.current) return;
    if (!accepted.ok) {
      setPending(false);
      setFailure(accepted.failure);
      return;
    }
    const updated = await fetchListingResponsibility(context, actionId);
    if (ticket !== epoch.current) return;
    setPending(false);
    if (updated.ok) setAnswer(updated.value);
    else {
      setAnswer(undefined);
      setFailure(updated.failure);
    }
  };
  return (
    <section aria-label={t('responsibilityLoad')}>
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        <Button
          loading={pending && answer === undefined}
          disabled={pending}
          onClick={() => {
            const ticket = ++epoch.current;
            setPending(true);
            setAnswer(undefined);
            setFailure(undefined);
            void fetchListingResponsibility(context, actionId).then((result) => {
              if (ticket !== epoch.current) return;
              setPending(false);
              if (result.ok) setAnswer(result.value);
              else setFailure(result.failure);
            });
          }}
        >
          {t('responsibilityLoad')}
        </Button>
        {failure !== undefined && <ListingProblem failure={failure} />}
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
                    : state === 'SLO_UNRESOLVED'
                      ? 'responsibilitySloUnknown'
                      : 'responsibilityCoverageUnknown',
              )}
              description={t('responsibilityHelp')}
            />
            <div>
              <ConfirmButton
                type="primary"
                title="确认由你承接此责任？"
                description="承接会被记录，原责任起点不会重置。"
                disabled={pending || status.acknowledgedAt !== undefined}
                {...(status.acknowledgedAt !== undefined
                  ? { disabledReason: '已记录承接' }
                  : pending
                    ? { disabledReason: '正在处理中' }
                    : {})}
                onConfirm={acknowledge}
              >
                {t('responsibilityAcknowledge')}
              </ConfirmButton>
            </div>
            <ResponsibilityTimes status={status} />
            <Divider style={{ margin: '8px 0' }} />
            <ListingDeferral
              context={context}
              target={{ kind: 'ACTION', actionId }}
              current={status.deferral}
            />
            <Divider style={{ margin: '8px 0' }} />
            <ListingDependencyHold
              context={context}
              target={{ kind: 'ACTION', actionId }}
              current={status.dependencyHold}
            />
          </>
        )}
      </Space>
    </section>
  );
}

export function ResponsibilityTimes({
  status,
}: {
  readonly status: NonNullable<Responsibility['status']>;
}): React.JSX.Element {
  return (
    <div>
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
              status.calibrationVersion === undefined ? '—' : String(status.calibrationVersion)
            } · ${status.basisDigest}`}
          />
        </Space>
      </TechnicalDetails>
    </div>
  );
}
