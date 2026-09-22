import { App, Card, Flex, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  acknowledgeListingDiagnostic,
  type ListingDiagnosticResponsibility,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { responsibilityText as text } from '../i18n/zh/listingHealth';
import { ConfirmButton, EmptyState, SectionCard } from '../ui';
import { Code, ListingProblem, codeText } from './ListingCommon';
import { ListingDeferral } from './ListingDeferral';
import { ListingDependencyHold } from './ListingDependencyHold';
import type { DependencyTaskOption } from './ListingDependencyHold';
import { ResponsibilityTimes } from './ListingResponsibility';

/**
 * Who answers for each failed necessary condition of one listing, with the
 * clock that runs until it is handled. Each card names the cause and the clock
 * state in its header and offers acknowledgement, a finite deferral and a
 * dependency hold beside it; the deadlines fold below with every warning in
 * view.
 */
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
  const { message } = App.useApp();
  const epoch = useRef(0);
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    epoch.current += 1;
    setFailure(undefined);
    return () => {
      epoch.current += 1;
    };
  }, [context, listingId]);

  const taskOptions = (own: string): DependencyTaskOption[] =>
    responsibilities
      .filter(({ status }) => status.taskId !== own)
      .map(({ causeCode, status }) => ({
        value: status.taskId,
        label: text.holdTaskOption(codeText('healthCondition', causeCode)),
      }));

  return (
    <section aria-label={t('responsibilityDiagnostics')}>
      <SectionCard title={t('responsibilityDiagnostics')}>
        <Flex vertical gap={12}>
          {failure !== undefined && <ListingProblem failure={failure} />}
          {responsibilities.length === 0 && <EmptyState description={text.none} />}
          {responsibilities.map(({ causeCode, status }) => {
            const acknowledged = status.acknowledgedAt !== undefined;
            return (
              <Card
                key={status.taskId}
                size="small"
                type="inner"
                data-task={status.taskId}
                title={
                  <Flex gap={4} wrap align="center">
                    <Code family="healthCondition" code={causeCode} />
                    <Code family="clockState" code={status.clockState} />
                    {acknowledged && <Tag color="success">{text.acknowledgedTag}</Tag>}
                  </Flex>
                }
                extra={
                  <Flex gap={8} wrap align="center" justify="flex-end">
                    <ConfirmButton
                      type={acknowledged ? 'default' : 'primary'}
                      size="small"
                      title={text.diagnosticQuestion}
                      description={text.diagnosticConsequence}
                      disabled={acknowledged}
                      {...(acknowledged ? { disabledReason: text.acknowledged } : {})}
                      onConfirm={async () => {
                        const ticket = ++epoch.current;
                        setFailure(undefined);
                        const result = await acknowledgeListingDiagnostic(
                          context,
                          listingId,
                          status.taskId,
                        );
                        if (ticket !== epoch.current) return;
                        if (result.ok) {
                          void message.success(text.acknowledged);
                          refresh();
                        } else setFailure(result.failure);
                      }}
                    >
                      {text.acknowledge}
                    </ConfirmButton>
                    <ListingDeferral
                      context={context}
                      target={{ kind: 'DIAGNOSTIC', listingId, taskId: status.taskId }}
                      current={status.deferral}
                      onChanged={refresh}
                    />
                    <ListingDependencyHold
                      context={context}
                      target={{ kind: 'DIAGNOSTIC', listingId, taskId: status.taskId }}
                      current={status.dependencyHold}
                      taskOptions={taskOptions(status.taskId)}
                      onChanged={refresh}
                    />
                  </Flex>
                }
              >
                <ResponsibilityTimes status={status} />
              </Card>
            );
          })}
        </Flex>
      </SectionCard>
    </section>
  );
}
