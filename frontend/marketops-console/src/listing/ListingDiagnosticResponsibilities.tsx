import { Alert, Card, Divider, Space } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  acknowledgeListingDiagnostic,
  type ListingDiagnosticResponsibility,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { ConfirmButton, SectionCard } from '../ui';
import { Code, ListingProblem } from './ListingCommon';
import { ResponsibilityTimes } from './ListingResponsibility';
import { ListingDeferral } from './ListingDeferral';
import { ListingDependencyHold } from './ListingDependencyHold';

export function ListingDiagnosticResponsibilities({
  context,
  listingId,
  responsibilities,
  refresh,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly responsibilities: readonly ListingDiagnosticResponsibility[];
  readonly refresh: () => void;
}): React.JSX.Element {
  const epoch = useRef(0);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    epoch.current += 1;
    setPending(false);
    setFailure(undefined);
    return () => {
      epoch.current += 1;
    };
  }, [context, listingId]);
  if (responsibilities.length === 0 && failure === undefined) {
    return <section aria-label={t('responsibilityDiagnostics')} data-state="empty" />;
  }
  return (
    <section aria-label={t('responsibilityDiagnostics')}>
      <SectionCard title={t('responsibilityDiagnostics')}>
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {failure !== undefined && <ListingProblem failure={failure} />}
          {responsibilities.map(({ causeCode, status }) => (
            <Card
              key={status.taskId}
              size="small"
              type="inner"
              title={<Code family="healthCondition" code={causeCode} />}
              extra={
                <ConfirmButton
                  type="primary"
                  size="small"
                  title="确认由你承接此必要条件责任？"
                  description="承接不表示原因已解除，风险继续计时。"
                  disabled={pending || status.acknowledgedAt !== undefined}
                  {...(status.acknowledgedAt !== undefined
                    ? { disabledReason: '已记录承接' }
                    : pending
                      ? { disabledReason: '正在处理中' }
                      : {})}
                  onConfirm={() => {
                    const ticket = ++epoch.current;
                    setPending(true);
                    setFailure(undefined);
                    void acknowledgeListingDiagnostic(context, listingId, status.taskId).then(
                      (result) => {
                        if (ticket !== epoch.current) return;
                        setPending(false);
                        if (result.ok) refresh();
                        else setFailure(result.failure);
                      },
                    );
                  }}
                >
                  {t('responsibilityAcknowledge')}
                </ConfirmButton>
              }
            >
              <Space orientation="vertical" size="small" style={{ width: '100%' }}>
                <Alert
                  type={status.clockState === 'CONTINUOUS_RISK' ? 'error' : 'warning'}
                  showIcon
                  title={t(
                    status.clockState === 'CONTINUOUS_RISK'
                      ? 'responsibilityContinuous'
                      : 'responsibilitySloUnknown',
                  )}
                />
                <ResponsibilityTimes status={status} />
                <Divider style={{ margin: '8px 0' }} />
                <ListingDeferral
                  context={context}
                  target={{ kind: 'DIAGNOSTIC', listingId, taskId: status.taskId }}
                  current={status.deferral}
                />
                <Divider style={{ margin: '8px 0' }} />
                <ListingDependencyHold
                  context={context}
                  target={{ kind: 'DIAGNOSTIC', listingId, taskId: status.taskId }}
                  current={status.dependencyHold}
                />
              </Space>
            </Card>
          ))}
        </Space>
      </SectionCard>
    </section>
  );
}
