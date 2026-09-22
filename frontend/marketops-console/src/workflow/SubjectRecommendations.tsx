import { Button, Table, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import { fetchRecommendations } from '../api/console';
import type { ConsoleFailure, ConsoleRequest, Recommendation } from '../api/console';
import { codeLabel } from '../i18n';
import {
  ACTION_KIND_LABELS,
  diagnosisText,
  ORIGIN_LABELS,
  RECOMMENDATION_STATE_COLORS,
  RECOMMENDATION_STATE_LABELS,
  RISK_COLORS,
  RISK_LABELS,
} from '../i18n/zh/pricing';
import {
  CodeTag,
  DateTime,
  EmptyState,
  FailureAlert,
  LoadingState,
  Money,
  SectionCard,
} from '../ui';

/**
 * The proposals waiting on one subject, first on its page because they are
 * what moves the work forward.
 *
 * The list is filtered by the server and kept in the order it returns; the
 * review itself opens beside the page, so the diagnosis stays in view.
 */
export function SubjectRecommendations({
  context,
  storeId,
  subjectId,
  refreshKey = 0,
  onReview,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly subjectId: string;
  /** Changing it reloads the list, e.g. after a decision. */
  readonly refreshKey?: number;
  readonly onReview: (recommendation: Recommendation) => void;
}): React.JSX.Element {
  const [items, setItems] = useState<readonly Recommendation[]>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    let active = true;
    void fetchRecommendations(context, storeId, subjectId).then((result) => {
      if (!active) return;
      if (
        result.ok &&
        result.value.every((item) => item.storeId === storeId && item.subjectId === subjectId)
      ) {
        setItems(result.value);
        setFailure(undefined);
      } else
        setFailure(result.ok ? { kind: 'malformed', detail: 'scope mismatch' } : result.failure);
    });
    return () => {
      active = false;
    };
  }, [context, storeId, subjectId, refreshKey]);

  const columns: TableColumnsType<Recommendation> = [
    {
      title: '建议',
      key: 'action',
      render: (_, item) => (
        <Typography.Text strong>{codeLabel(ACTION_KIND_LABELS, item.actionKind)}</Typography.Text>
      ),
    },
    {
      title: '目标价格',
      key: 'targetPrice',
      align: 'right',
      render: (_, item) => (
        <Money
          value={item.proposedParameters.targetPrice}
          currency={item.proposedParameters.currencyCode ?? null}
          strong
        />
      ),
    },
    {
      title: '状态',
      key: 'state',
      render: (_, item) => (
        <CodeTag
          labels={RECOMMENDATION_STATE_LABELS}
          code={item.state}
          colors={RECOMMENDATION_STATE_COLORS}
        />
      ),
    },
    {
      title: '风险',
      key: 'risk',
      render: (_, item) => (
        <CodeTag labels={RISK_LABELS} code={item.riskLabel} colors={RISK_COLORS} />
      ),
    },
    {
      title: '来源',
      key: 'origin',
      render: (_, item) => codeLabel(ORIGIN_LABELS, item.origin),
    },
    {
      title: '有效期至',
      key: 'validUntil',
      render: (_, item) => <DateTime value={item.validUntil} />,
    },
    {
      title: '操作',
      key: 'review',
      fixed: 'right',
      render: (_, item) => {
        const pending = item.state === 'READY_FOR_REVIEW';
        return (
          <Button
            type={pending ? 'primary' : 'default'}
            size="small"
            aria-label={`审核建议 ${item.id}`}
            onClick={() => {
              onReview(item);
            }}
          >
            {pending ? diagnosisText.review : diagnosisText.view}
          </Button>
        );
      },
    },
  ];

  return (
    <section aria-label={diagnosisText.recommendations}>
      <SectionCard title={diagnosisText.recommendations}>
        {failure !== undefined ? (
          <FailureAlert failure={failure} />
        ) : items === undefined ? (
          <LoadingState rows={2} />
        ) : items.length === 0 ? (
          <EmptyState description={diagnosisText.recommendationsEmpty} />
        ) : (
          <Table<Recommendation>
            size="middle"
            rowKey="id"
            columns={columns}
            dataSource={[...items]}
            pagination={{ pageSize: 10, hideOnSinglePage: true, showSizeChanger: false }}
            scroll={{ x: 'max-content' }}
            onRow={(item) =>
              ({
                'data-recommendation': item.id,
                'data-state': item.state,
              }) as React.HTMLAttributes<HTMLElement>
            }
          />
        )}
      </SectionCard>
    </section>
  );
}
