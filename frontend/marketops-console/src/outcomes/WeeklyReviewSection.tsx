import { Collapse, Descriptions, Flex, List, Space, Tag, Typography } from 'antd';
import type { DescriptionsProps } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { WeeklyReview } from '../api/outcomes';
import { fetchWeeklyReviews } from '../api/outcomes';
import { codeLabel } from '../i18n';
import { ACTION_LABELS, VERDICT_LABELS, outcomesText } from '../i18n/zh/outcomes';
import { AiSummaryCard } from '../storediagnosis/AiInterpretation';
import { EmptyState, FailureAlert, LoadingState, SectionCard } from '../ui';
import { beforeAfter } from './OutcomeParts';

const text = outcomesText.weekly;

type Load =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure }
  | { readonly kind: 'loaded'; readonly reviews: readonly WeeklyReview[] };

/** One week's snapshot as a short description list. */
function WeekSnapshot({ review }: { readonly review: WeeklyReview }): React.JSX.Element {
  const items: DescriptionsProps['items'] = [
    {
      key: 'orders',
      label:
        review.ordersFrom === null || review.ordersTo === null
          ? text.orders('—', '—')
          : text.orders(review.ordersFrom, review.ordersTo),
      children:
        review.orderedUnits === null
          ? text.noOrdersData
          : text.ordersValue(
              review.orderedUnits,
              review.listingsWithOrders,
              review.ordersDaysCovered,
            ),
    },
    {
      key: 'activity',
      label: text.activity,
      children: text.activityValue(
        review.actionsActed,
        review.readingsRecorded,
        review.actionsObserving,
      ),
    },
    {
      key: 'verdicts',
      label: text.verdicts,
      children: text.verdictsValue(
        review.improvedCount,
        review.unchangedCount,
        review.regressedCount,
        review.indeterminateCount,
      ),
    },
  ];
  return (
    <Flex vertical gap={8}>
      <Space size={8} wrap>
        <Typography.Text strong>{text.week(review.weekStart, review.weekEnd)}</Typography.Text>
        <Tag color={review.weekComplete ? 'success' : 'processing'}>
          {review.weekComplete ? text.final : text.provisional}
        </Tag>
      </Space>
      <Descriptions bordered size="small" column={{ xs: 1, md: 3 }} items={items} />
      {review.actions.length > 0 ? (
        <List
          size="small"
          header={<Typography.Text type="secondary">{text.actionsLabel}</Typography.Text>}
          dataSource={[...review.actions]}
          renderItem={(action) => (
            <List.Item key={action.actionRef}>
              <Space size={8} wrap>
                <Typography.Text>{action.offerId ?? action.listingVariantId}</Typography.Text>
                <Typography.Text type="secondary">
                  {codeLabel(ACTION_LABELS, action.actionKind)} · {action.actedOn}
                </Typography.Text>
                <Tag>
                  {action.verdict === 'OBSERVING'
                    ? text.observingShort
                    : codeLabel(VERDICT_LABELS, action.verdict)}
                </Tag>
                <Typography.Text type="secondary">
                  {outcomesText.columns.orders}：
                  {beforeAfter(action.ordersBefore, action.ordersAfter)}
                </Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      ) : null}
    </Flex>
  );
}

/**
 * The weekly review (P10): the newest weekly snapshot of the store's followed actions and Qwen's review
 * of it, with the earlier weeks below.
 */
export function WeeklyReviewSection({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    let live = true;
    void fetchWeeklyReviews(context, storeId).then((outcome) => {
      if (!live) return;
      setLoad(
        outcome.ok
          ? { kind: 'loaded', reviews: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, storeId, generation]);

  const newest = load.kind === 'loaded' ? load.reviews[0] : undefined;
  const earlier = load.kind === 'loaded' ? load.reviews.slice(1) : [];

  return (
    <Flex vertical gap={16}>
      <SectionCard title={text.title}>
        {load.kind === 'loading' ? <LoadingState /> : null}
        {load.kind === 'failed' ? <FailureAlert failure={load.failure} /> : null}
        {load.kind === 'loaded' && newest === undefined ? (
          <EmptyState description={text.noReview} />
        ) : null}
        {newest !== undefined ? <WeekSnapshot review={newest} /> : null}
        {earlier.length > 0 ? (
          <Collapse
            ghost
            style={{ marginTop: 12 }}
            items={[
              {
                key: 'history',
                label: text.history,
                children: (
                  <Flex vertical gap={16}>
                    {earlier.map((review) => (
                      <WeekSnapshot key={review.id} review={review} />
                    ))}
                  </Flex>
                ),
              },
            ]}
          />
        ) : null}
      </SectionCard>
      <AiSummaryCard
        context={context}
        storeId={storeId}
        source="weekly"
        labels={{
          title: text.aiTitle,
          hint: text.hint,
          none: text.aiNone,
          actions: text.aiActions,
        }}
        referenceLabel={() => undefined}
        onAnswered={() => {
          setGeneration((value) => value + 1);
        }}
      />
    </Flex>
  );
}
