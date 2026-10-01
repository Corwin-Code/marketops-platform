import { Alert, Flex, Table, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { FollowedAction } from '../api/outcomes';
import { fetchStoreOutcomes } from '../api/outcomes';
import { outcomesText as text } from '../i18n/zh/outcomes';
import { subjectPath } from '../layout/navigation';
import { DateTime, EmptyState, FailureAlert, LoadingState, SectionCard } from '../ui';
import {
  ReadingDetails,
  SignalTag,
  VerdictTag,
  actionText,
  beforeAfter,
  latestReading,
  progressText,
} from './OutcomeParts';

type Load =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure }
  | { readonly kind: 'loaded'; readonly actions: readonly FollowedAction[] };

/** Every followed action of the store with its before/after readings (P10). */
export function OutcomeTable({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });

  useEffect(() => {
    let live = true;
    void fetchStoreOutcomes(context, storeId).then((outcome) => {
      if (!live) return;
      setLoad(
        outcome.ok
          ? { kind: 'loaded', actions: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, storeId]);

  const columns: TableColumnsType<FollowedAction> = [
    {
      key: 'listing',
      title: text.columns.listing,
      render: (_, action) => (
        <Flex vertical>
          <Link to={subjectPath(action.listingVariantId)}>
            {action.offerId ?? action.listingVariantId}
          </Link>
          {action.title === null ? null : (
            <Typography.Text type="secondary" ellipsis style={{ maxWidth: 280 }}>
              {action.title}
            </Typography.Text>
          )}
        </Flex>
      ),
    },
    { key: 'action', title: text.columns.action, render: (_, action) => actionText(action) },
    {
      key: 'actedAt',
      title: text.columns.actedAt,
      render: (_, action) => <DateTime value={action.actedAt} />,
    },
    { key: 'progress', title: text.columns.progress, render: (_, action) => progressText(action) },
    {
      key: 'verdict',
      title: text.columns.verdict,
      render: (_, action) => {
        const reading = latestReading(action);
        return reading === null ? text.missing : <VerdictTag reading={reading} />;
      },
    },
    {
      key: 'orders',
      title: text.columns.orders,
      render: (_, action) => {
        const reading = latestReading(action);
        return reading === null
          ? text.missing
          : beforeAfter(reading.baselineOrderedUnits, reading.observationOrderedUnits);
      },
    },
    {
      key: 'signal',
      title: text.columns.signal,
      render: (_, action) => {
        const reading = latestReading(action);
        return reading === null ? text.missing : <SignalTag signal={reading.leadingSignal} />;
      },
    },
  ];

  return (
    <SectionCard title={text.tableTitle}>
      <Alert type="info" showIcon style={{ marginBottom: 16 }} title={text.rules} />
      {load.kind === 'loading' ? <LoadingState /> : null}
      {load.kind === 'failed' ? <FailureAlert failure={load.failure} /> : null}
      {load.kind === 'loaded' && load.actions.length === 0 ? (
        <EmptyState description={text.empty} />
      ) : null}
      {load.kind === 'loaded' && load.actions.length > 0 ? (
        <Table<FollowedAction>
          rowKey="id"
          size="middle"
          columns={columns}
          dataSource={[...load.actions]}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          scroll={{ x: 'max-content' }}
          expandable={{
            rowExpandable: (action) => latestReading(action) !== null,
            expandedRowRender: (action) => (
              <Flex vertical gap={12}>
                {[action.finalReading, action.preliminary]
                  .filter((reading) => reading !== null)
                  .map((reading) => (
                    <ReadingDetails
                      key={reading.stage}
                      reading={reading}
                      currencyCode={action.currencyCode}
                    />
                  ))}
              </Flex>
            ),
          }}
        />
      ) : null}
    </SectionCard>
  );
}
