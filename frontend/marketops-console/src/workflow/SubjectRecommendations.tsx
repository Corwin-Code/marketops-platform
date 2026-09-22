import { Button, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import { fetchRecommendations } from '../api/console';
import type { ConsoleFailure, ConsoleRequest, Recommendation } from '../api/console';
import { codeLabel } from '../i18n';
import {
  ACTION_KIND_LABELS,
  ORIGIN_LABELS,
  RECOMMENDATION_STATE_COLORS,
  RECOMMENDATION_STATE_LABELS,
  RISK_COLORS,
  RISK_LABELS,
} from '../i18n/zh/pricing';
import { CodeTag, DateTime, EmptyState, FailureAlert, LoadingState, SectionCard } from '../ui';

/** Server-filtered subject proposals stay reachable even in a large store queue. */
export function SubjectRecommendations({
  context,
  storeId,
  subjectId,
  onReview,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly subjectId: string;
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
      )
        setItems(result.value);
      else setFailure(result.ok ? { kind: 'malformed', detail: 'scope mismatch' } : result.failure);
    });
    return () => {
      active = false;
    };
  }, [context, storeId, subjectId]);

  const columns: TableColumnsType<Recommendation> = [
    {
      title: '建议',
      key: 'action',
      render: (_, item) => (
        <Button
          type="link"
          style={{ padding: 0 }}
          aria-label={`审核建议 ${item.id}`}
          onClick={() => {
            onReview(item);
          }}
        >
          {codeLabel(ACTION_KIND_LABELS, item.actionKind)}
        </Button>
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
  ];

  return (
    <section aria-label="调价建议">
      <SectionCard title="调价建议">
        {failure !== undefined ? (
          <FailureAlert failure={failure} />
        ) : items === undefined ? (
          <LoadingState rows={2} />
        ) : items.length === 0 ? (
          <EmptyState description="该商品暂无待处理的建议" />
        ) : (
          <Table<Recommendation>
            size="middle"
            rowKey="id"
            columns={columns}
            dataSource={[...items]}
            pagination={{ pageSize: 10, hideOnSinglePage: true, showSizeChanger: false }}
            scroll={{ x: 'max-content' }}
          />
        )}
      </SectionCard>
    </section>
  );
}
