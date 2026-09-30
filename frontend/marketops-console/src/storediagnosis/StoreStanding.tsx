import { Alert, Descriptions, Flex, Space, Table, Tag, Typography } from 'antd';
import type { ConsoleFailure } from '../api/console';
import type { StandingRating, StandingWarehouse, StoreStanding } from '../api/storeStanding';
import { formatDecimal, formatPercent } from '../format';
import { codeLabel } from '../i18n';
import {
  FIRST_MILE_LABELS,
  RATING_LABELS,
  RATING_ORDER,
  RATING_STATUS_LABELS,
  storeStandingText as text,
  WAREHOUSE_STATUS_LABELS,
} from '../i18n/zh/storeDiagnosis';
import { DateTime, EmptyState, FailureAlert, InfoTip, SectionCard } from '../ui';
import type { TagColor } from '../ui';

/** The store's standing, as far as it has loaded. */
export type StandingLoad =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly standing: StoreStanding }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/** A warehouse Ozon calls active. */
const ACTIVE_WAREHOUSE = 'created';

const STATUS_COLORS: Readonly<Record<string, TagColor>> = {
  OK: 'success',
  WARNING: 'warning',
  CRITICAL: 'error',
};

function ratingLabel(rating: StandingRating): string {
  return RATING_LABELS[rating.ratingKey] ?? rating.ratingName ?? rating.ratingKey;
}

/** Known ratings in the page's order, then any other in the order Ozon's group names sort. */
function ordered(ratings: readonly StandingRating[]): readonly StandingRating[] {
  const rank = (rating: StandingRating): number => {
    const found = RATING_ORDER.indexOf(rating.ratingKey);
    return found === -1 ? RATING_ORDER.length : found;
  };
  return [...ratings].sort((left, right) => rank(left) - rank(right));
}

/**
 * A rating's value in the unit its type states. A rating without a verdict has no data yet: its
 * value is not shown as a zero. Ozon states a RATIO as a share (the pilot's red price-index share is
 * 1 while all 14 products with an index are red), so it is shown as a percentage; a PERCENT is one
 * already.
 */
function ratingValue(rating: StandingRating, value: string | null): string {
  if (rating.status === 'UNKNOWN_STATUS' || rating.status === null) return text.noRatingData;
  if (rating.valueType === 'RATIO') return formatPercent(value);
  const shown = formatDecimal(value, { maxFractionDigits: 4 });
  return rating.valueType === 'PERCENT' && shown !== '—' ? `${shown}%` : shown;
}

/** What on the store's standing stops or may soon stop sales, most severe first. */
export function StandingAlerts({
  load,
}: {
  readonly load: StandingLoad;
}): React.JSX.Element | null {
  if (load.kind !== 'loaded') return null;
  const { summary, ratings, warehouses } = load.standing;
  const errors: string[] = [];
  const warnings: string[] = [];
  if (summary?.penaltyScoreExceeded === true) errors.push(text.alertPenalty);
  for (const warehouse of warehouses) {
    if (warehouse.status !== null && warehouse.status !== ACTIVE_WAREHOUSE) {
      errors.push(
        text.alertWarehouse(
          warehouse.nativeWarehouseKey,
          codeLabel(WAREHOUSE_STATUS_LABELS, warehouse.status),
        ),
      );
    }
  }
  const critical = ratings.filter((rating) => rating.status === 'CRITICAL');
  if (critical.length > 0) errors.push(text.alertRating(critical.map(ratingLabel).join('、')));
  const attention = ratings.filter((rating) => rating.status === 'WARNING');
  if (attention.length > 0)
    warnings.push(text.warningRating(attention.map(ratingLabel).join('、')));
  if (errors.length === 0 && warnings.length === 0) return null;
  return (
    <Alert
      type={errors.length > 0 ? 'error' : 'warning'}
      showIcon
      title={text.alertTitle}
      description={
        <Flex vertical gap={4}>
          {[...errors, ...warnings].map((message) => (
            <span key={message}>{message}</span>
          ))}
        </Flex>
      }
    />
  );
}

function yesNo(value: boolean | null, yes: string, no: string): string {
  return value === null ? text.unknown : value ? yes : no;
}

function warehouseDetail(warehouse: StandingWarehouse): string {
  const parts = [
    warehouse.rfbs === true ? 'rFBS' : (warehouse.warehouseType?.toUpperCase() ?? 'FBS'),
    `${text.firstMile}：${codeLabel(FIRST_MILE_LABELS, warehouse.firstMileKind)}`,
  ];
  if (warehouse.workingDayCount !== null) parts.push(text.workingDays(warehouse.workingDayCount));
  if (warehouse.postingsLimit !== null) {
    parts.push(
      warehouse.postingsLimit < 0
        ? text.noPostingsLimit
        : text.postingsLimit(warehouse.postingsLimit),
    );
  }
  return parts.join(' · ');
}

/**
 * The store's own standing on Ozon: whether it subscribes to Premium or Premium Plus, whether its
 * penalty balance is exceeded, its localization index, every rating with Ozon's own verdict, and
 * its warehouses. It explains what the listings cannot: a store that cannot ship sells nothing.
 */
export function StoreStandingSection({ load }: { readonly load: StandingLoad }): React.JSX.Element {
  let body: React.JSX.Element;
  if (load.kind === 'loading') {
    body = <Typography.Text type="secondary">…</Typography.Text>;
  } else if (load.kind === 'failed') {
    body = <FailureAlert failure={load.failure} />;
  } else if (
    load.standing.summary === null &&
    load.standing.ratings.length === 0 &&
    load.standing.warehouses.length === 0
  ) {
    body = <EmptyState description={text.notCollected} />;
  } else {
    const { summary, ratings, ratingsAt, warehouses, warehousesAt } = load.standing;
    body = (
      <Flex vertical gap={16}>
        {summary !== null && (
          <Descriptions
            size="small"
            column={{ xs: 1, md: 2 }}
            bordered
            title={text.account}
            extra={
              <Space size={4}>
                <Typography.Text type="secondary">{text.collectedAt}</Typography.Text>
                <DateTime value={summary.observedAt} />
              </Space>
            }
          >
            <Descriptions.Item label={text.premium}>
              {yesNo(summary.premium, text.subscribed, text.notSubscribed)}
            </Descriptions.Item>
            <Descriptions.Item label={text.premiumPlus}>
              {yesNo(summary.premiumPlus, text.subscribed, text.notSubscribed)}
            </Descriptions.Item>
            <Descriptions.Item label={text.penalty}>
              {summary.penaltyScoreExceeded === true ? (
                <Tag color="error">{text.penaltyExceeded}</Tag>
              ) : (
                yesNo(summary.penaltyScoreExceeded, text.penaltyExceeded, text.penaltyOk)
              )}
            </Descriptions.Item>
            <Descriptions.Item label={text.localization}>
              {summary.localizationCalculatedAt === null ||
              summary.localizationPercentage === null ? (
                <Typography.Text type="secondary">{text.localizationEmpty}</Typography.Text>
              ) : (
                <Space size={4} wrap>
                  {text.localizationValue(
                    formatDecimal(summary.localizationPercentage, { maxFractionDigits: 2 }),
                  )}
                  <Typography.Text type="secondary">
                    {/* Ozon states a calculation date as midnight UTC: the date is what it means. */}
                    {text.localizationAt} {summary.localizationCalculatedAt.slice(0, 10)}
                  </Typography.Text>
                </Space>
              )}
            </Descriptions.Item>
          </Descriptions>
        )}
        {ratings.length > 0 && (
          <Flex vertical gap={8}>
            <Space size={8} wrap>
              <Typography.Title level={5} style={{ margin: 0 }}>
                {text.ratings}
              </Typography.Title>
              <InfoTip title={text.valueAsStated} />
              {ratingsAt !== null && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {text.collectedAt} <DateTime value={ratingsAt} />
                </Typography.Text>
              )}
            </Space>
            <Table<StandingRating>
              size="small"
              rowKey="ratingKey"
              pagination={false}
              scroll={{ x: 'max-content' }}
              dataSource={ordered(ratings)}
              columns={[
                {
                  title: text.ratingColumn,
                  key: 'rating',
                  render: (_, rating) => ratingLabel(rating),
                },
                {
                  title: text.statusColumn,
                  key: 'status',
                  render: (_, rating) => (
                    <Tag color={STATUS_COLORS[rating.status ?? ''] ?? 'default'}>
                      {codeLabel(RATING_STATUS_LABELS, rating.status)}
                    </Tag>
                  ),
                },
                {
                  title: text.valueColumn,
                  key: 'current',
                  render: (_, rating) => ratingValue(rating, rating.currentValue),
                },
                {
                  title: text.pastColumn,
                  key: 'past',
                  render: (_, rating) => ratingValue(rating, rating.pastValue),
                },
              ]}
            />
          </Flex>
        )}
        {warehouses.length > 0 && (
          <Flex vertical gap={8}>
            <Space size={8} wrap>
              <Typography.Title level={5} style={{ margin: 0 }}>
                {text.warehouses}
              </Typography.Title>
              {warehousesAt !== null && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {text.collectedAt} <DateTime value={warehousesAt} />
                </Typography.Text>
              )}
            </Space>
            {warehouses.map((warehouse) => (
              <Flex key={warehouse.nativeWarehouseKey} gap={8} wrap align="center">
                <Typography.Text>
                  {text.warehouseColumn} {warehouse.nativeWarehouseKey}
                </Typography.Text>
                <Tag color={warehouse.status === ACTIVE_WAREHOUSE ? 'success' : 'error'}>
                  {codeLabel(WAREHOUSE_STATUS_LABELS, warehouse.status)}
                </Tag>
                <Typography.Text type="secondary">{warehouseDetail(warehouse)}</Typography.Text>
                {warehouse.pausedAt !== null && (
                  <Typography.Text type="warning">
                    {text.paused} <DateTime value={warehouse.pausedAt} />
                  </Typography.Text>
                )}
              </Flex>
            ))}
          </Flex>
        )}
      </Flex>
    );
  }
  return (
    <SectionCard
      title={
        <Space size={4}>
          {text.title}
          <InfoTip title={text.hint} />
        </Space>
      }
    >
      {body}
    </SectionCard>
  );
}
