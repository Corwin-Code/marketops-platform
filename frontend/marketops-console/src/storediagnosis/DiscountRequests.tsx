import { Alert, Button, Flex, Space, Statistic, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  DiscountRequest,
  DiscountRequestItem,
  DiscountRequests,
} from '../api/discountRequests';
import { fetchDiscountRequests } from '../api/discountRequests';
import { formatDecimal, formatStoreDate } from '../format';
import { codeLabel } from '../i18n';
import {
  DISCOUNT_REQUEST_STATUS_LABELS,
  discountRequestText as text,
} from '../i18n/zh/storeDiagnosis';
import { DateTime, EmptyState, FailureAlert, InfoTip, Money, SectionCard } from '../ui';
import type { TagColor } from '../ui';

type Load =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure }
  | { readonly kind: 'loaded'; readonly requests: DiscountRequests };

const STATUS_COLORS: Readonly<Record<string, TagColor>> = {
  NEW: 'warning',
  APPROVED: 'success',
  DECLINED: 'default',
};

/** A percentage stated in percent, e.g. `4.97` → `4.97%`. */
function percent(value: string | null): string {
  const shown = formatDecimal(value, { maxFractionDigits: 2 });
  return shown === '—' ? shown : `${shown}%`;
}

function useDiscountRequests(context: ConsoleRequest, storeId: string, subjectId?: string): Load {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  useEffect(() => {
    let live = true;
    void fetchDiscountRequests(context, storeId, subjectId).then((outcome) => {
      if (!live) return;
      setLoad(
        outcome.ok
          ? { kind: 'loaded', requests: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, storeId, subjectId]);
  return load;
}

function StatusTag({ status }: { readonly status: string | null }): React.JSX.Element {
  return (
    <Tag color={STATUS_COLORS[status ?? ''] ?? 'default'}>
      {codeLabel(DISCOUNT_REQUEST_STATUS_LABELS, status)}
    </Tag>
  );
}

/** New requests wait for a decision in the Ozon back office, within a deadline. */
function PendingAlert({
  requests,
}: {
  readonly requests: DiscountRequests;
}): React.JSX.Element | null {
  const { pending, nextDeadline } = requests.summary;
  if (pending === 0) return null;
  return (
    <Alert
      type="warning"
      showIcon
      title={text.pendingTitle(pending)}
      description={
        <Space size={4} wrap>
          {nextDeadline === null ? text.pendingNoDeadline : text.pendingDeadline}
          {nextDeadline !== null && <DateTime value={nextDeadline} />}
        </Space>
      }
    />
  );
}

const REQUEST_COLUMNS: TableColumnsType<DiscountRequest> = [
  {
    key: 'requestedAt',
    title: text.requestedAt,
    render: (_, request) => formatStoreDate(request.requestedAt),
  },
  {
    key: 'status',
    title: text.status,
    render: (_, request) => <StatusTag status={request.status} />,
  },
  {
    key: 'original',
    title: text.originalPrice,
    align: 'right',
    render: (_, request) => <Money value={request.originalPrice} currency={request.currencyCode} />,
  },
  {
    key: 'requested',
    title: text.requestedPrice,
    align: 'right',
    render: (_, request) => (
      <Space size={4}>
        <Money value={request.requestedPrice} currency={request.currencyCode} />
        <Typography.Text type="secondary">
          {text.discountOf(percent(request.requestedDiscountPercent))}
        </Typography.Text>
      </Space>
    ),
  },
  {
    key: 'approved',
    title: text.approvedPrice,
    align: 'right',
    render: (_, request) =>
      request.status === 'APPROVED' ? (
        <Money value={request.approvedPrice} currency={request.currencyCode} />
      ) : (
        '—'
      ),
  },
  {
    key: 'handled',
    title: text.handled,
    render: (_, request) =>
      request.status === 'NEW' ? (
        <Space size={4}>
          {text.decideBy}
          <DateTime value={request.expiresAt} />
        </Space>
      ) : (
        <Space size={4}>
          {request.autoModerated === true ? text.automatic : text.byPerson}
          <Typography.Text type="secondary">{formatStoreDate(request.moderatedAt)}</Typography.Text>
        </Space>
      ),
  },
];

/**
 * One product's discount requests, in its detail drawer: what buyers asked to pay, what was
 * decided and when. Nothing shows before the store's requests were first collected.
 */
export function ListingDiscountRequests({
  context,
  storeId,
  subjectId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly subjectId: string;
}): React.JSX.Element | null {
  const load = useDiscountRequests(context, storeId, subjectId);
  if (load.kind === 'loading') return null;
  if (load.kind === 'failed') return <FailureAlert failure={load.failure} />;
  const { requests } = load;
  if (requests.observedAt === null) return null;
  const { summary } = requests;
  return (
    <Flex vertical gap={8}>
      <Space size={4}>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {text.title}
        </Typography.Title>
        <InfoTip title={text.hint} long />
      </Space>
      {summary.total === 0 ? (
        <Typography.Text type="secondary">
          {text.noneForListing(formatStoreDate(requests.observedAt))}
        </Typography.Text>
      ) : (
        <>
          <Typography.Text>
            {text.listingSummary(
              summary.total,
              summary.approved,
              summary.declined,
              summary.pending,
              percent(summary.medianDiscountPercent),
              formatStoreDate(summary.latestRequestedAt),
            )}
          </Typography.Text>
          <PendingAlert requests={requests} />
          <Table<DiscountRequest>
            rowKey="requestKey"
            size="small"
            columns={REQUEST_COLUMNS}
            dataSource={[...requests.requests]}
            pagination={false}
            scroll={{ x: 'max-content' }}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.priceNote} {text.collectedAt} <DateTime value={requests.observedAt} />
          </Typography.Text>
        </>
      )}
    </Flex>
  );
}

/**
 * The store's discount requests: how many buyers asked to pay less, what was decided, the discount
 * they asked for, how the requests spread over the months and which products they were about.
 * Products the catalogue still lists open their detail drawer.
 */
export function StoreDiscountRequestsSection({
  context,
  storeId,
  onOpenListing,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  /** Open a listing variant's detail drawer. */
  readonly onOpenListing: (subjectId: string) => void;
}): React.JSX.Element {
  const load = useDiscountRequests(context, storeId);
  let body: React.JSX.Element;
  if (load.kind === 'loading') {
    body = <Typography.Text type="secondary">…</Typography.Text>;
  } else if (load.kind === 'failed') {
    body = <FailureAlert failure={load.failure} />;
  } else if (load.requests.observedAt === null) {
    body = <EmptyState description={text.notCollected} />;
  } else if (load.requests.summary.total === 0) {
    body = <EmptyState description={text.noneForStore} />;
  } else {
    const { summary, months, items, observedAt } = load.requests;
    const itemColumns: TableColumnsType<DiscountRequestItem> = [
      {
        key: 'product',
        title: text.product,
        render: (_, item) => {
          const name = item.productName ?? item.nativeItemKey;
          const subjectId = item.subjectId;
          return subjectId === null ? (
            <Space size={4} wrap>
              <Typography.Text>{name}</Typography.Text>
              <Tag>{text.notInCatalog}</Tag>
            </Space>
          ) : (
            <Button
              type="link"
              size="small"
              style={{ padding: 0, height: 'auto', whiteSpace: 'normal', textAlign: 'left' }}
              onClick={() => {
                onOpenListing(subjectId);
              }}
            >
              {name}
            </Button>
          );
        },
      },
      { key: 'sku', title: text.sku, render: (_, item) => item.nativeItemKey },
      { key: 'requests', title: text.requests, align: 'right', dataIndex: 'requests' },
      {
        key: 'median',
        title: text.medianDiscount,
        align: 'right',
        render: (_, item) => percent(item.medianDiscountPercent),
      },
      {
        key: 'latest',
        title: text.latestRequestedAt,
        render: (_, item) => formatStoreDate(item.latestRequestedAt),
      },
    ];
    body = (
      <Flex vertical gap={16}>
        <Flex gap={32} wrap>
          <Statistic title={text.total} value={summary.total} />
          <Statistic
            title={text.decisions}
            value={text.decisionCounts(summary.approved, summary.declined, summary.pending)}
          />
          <Statistic
            title={text.itemsTile}
            value={text.itemCounts(summary.items, summary.itemsInCatalog)}
          />
          <Statistic title={text.requestsInCatalog} value={summary.requestsInCatalog} />
          <Statistic
            title={
              <Space size={4}>
                {text.medianDiscount}
                <InfoTip title={text.medianHint} />
              </Space>
            }
            value={percent(summary.medianDiscountPercent)}
          />
          <Statistic
            title={text.latestRequestedAt}
            value={formatStoreDate(summary.latestRequestedAt)}
          />
        </Flex>
        <PendingAlert requests={load.requests} />
        {months.length > 0 && (
          <Flex vertical gap={8}>
            <Space size={4}>
              <Typography.Text strong>{text.byMonth}</Typography.Text>
              <InfoTip title={text.byMonthHint} />
            </Space>
            <Space size={[8, 8]} wrap>
              {months.map((month) => (
                <Tag key={month.month}>{text.monthCount(month.month, month.requests)}</Tag>
              ))}
            </Space>
          </Flex>
        )}
        <Flex vertical gap={8}>
          <Space size={4}>
            <Typography.Text strong>{text.topItems}</Typography.Text>
            <InfoTip title={text.topItemsHint} />
          </Space>
          <Table<DiscountRequestItem>
            rowKey="nativeItemKey"
            size="small"
            columns={itemColumns}
            dataSource={[...items]}
            pagination={false}
            scroll={{ x: 'max-content' }}
          />
        </Flex>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.storeNote} {text.collectedAt} <DateTime value={observedAt} />
        </Typography.Text>
      </Flex>
    );
  }
  return (
    <SectionCard
      title={
        <Space size={4}>
          {text.title}
          <InfoTip title={text.hint} long />
        </Space>
      }
    >
      {body}
    </SectionCard>
  );
}
