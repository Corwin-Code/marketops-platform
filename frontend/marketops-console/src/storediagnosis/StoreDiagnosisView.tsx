import { ReloadOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Flex,
  Input,
  Row,
  Segmented,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { DiagnosisProduct, StoreDiagnosis } from '../api/storeDiagnosis';
import { fetchStoreDiagnosis } from '../api/storeDiagnosis';
import { formatDecimal, formatPercent } from '../format';
import { actions } from '../i18n';
import {
  FULFILLMENT_MODE_LABELS,
  PRICE_INDEX_LABELS,
  storeDiagnosisText as text,
} from '../i18n/zh/storeDiagnosis';
import {
  CodeTag,
  DateTime,
  DetailDrawer,
  EmptyState,
  FailureAlert,
  InfoTip,
  LoadingState,
  Money,
  SectionCard,
  useSearchParam,
  useSearchParamsPatch,
} from '../ui';
import type { TagColor } from '../ui';

/** What the store diagnosis needs in order to load itself. */
export interface StoreDiagnosisViewProps {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}

const FILTERS = [
  'all',
  'problems',
  'notSellable',
  'withoutStock',
  'priceRed',
  'withOrders',
] as const;
type Filter = (typeof FILTERS)[number];

/** Address-bar keys of the view. */
const FILTER_PARAM = 'f';
const QUERY_PARAM = 'q';
const LISTING_PARAM = 'listing';

const PRICE_INDEX_COLORS: Readonly<Record<string, TagColor>> = {
  SUPER: 'success',
  GREEN: 'success',
  YELLOW: 'warning',
  RED: 'error',
};

type Loaded =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly diagnosis: StoreDiagnosis }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

function readFilter(raw: string | undefined): Filter {
  return (FILTERS as readonly string[]).includes(raw ?? '') ? (raw as Filter) : 'all';
}

function notSellable(product: DiagnosisProduct): boolean {
  return product.visibility?.sellable === 'NO';
}

function withoutStock(product: DiagnosisProduct): boolean {
  return product.stock?.available === 0;
}

function priceRed(product: DiagnosisProduct): boolean {
  return product.price?.indexNative === 'RED';
}

/** A problem is something the marketplace itself stated: hidden, empty shelf or a RED price. */
function hasProblem(product: DiagnosisProduct): boolean {
  return notSellable(product) || withoutStock(product) || priceRed(product);
}

function matches(product: DiagnosisProduct, filter: Filter, query: string): boolean {
  const byFilter =
    filter === 'all' ||
    (filter === 'problems' && hasProblem(product)) ||
    (filter === 'notSellable' && notSellable(product)) ||
    (filter === 'withoutStock' && withoutStock(product)) ||
    (filter === 'priceRed' && priceRed(product)) ||
    (filter === 'withOrders' && (product.orders?.orderedUnits ?? 0) > 0);
  if (!byFilter) return false;
  if (query === '') return true;
  const needle = query.toLowerCase();
  return [product.title, product.nativeSkuKey, product.nativeListingKey].some(
    (value) => value?.toLowerCase().includes(needle) === true,
  );
}

/** A decimal string as a number, only to order rows; absent sorts first. */
function sortable(value: string | null | undefined): number {
  const parsed = value === null || value === undefined ? Number.NaN : Number(value);
  return Number.isFinite(parsed) ? parsed : Number.NEGATIVE_INFINITY;
}

/** The buyer-facing price: with the seller's promotions when there is one. */
function buyerPrice(product: DiagnosisProduct): string | null {
  return product.price?.discountPrice ?? product.price?.sellingPrice ?? null;
}

function Visibility({ product }: { readonly product: DiagnosisProduct }): React.JSX.Element {
  const visibility = product.visibility;
  if (visibility === null) {
    return <Typography.Text type="secondary">{text.notObserved}</Typography.Text>;
  }
  if (visibility.sellable === 'YES') return <Tag color="success">{text.sellableYes}</Tag>;
  if (visibility.sellable === 'NO') {
    const tag = <Tag color="error">{text.sellableNo}</Tag>;
    return visibility.blockedReason === null ? (
      tag
    ) : (
      <Tooltip title={visibility.blockedReason}>{tag}</Tooltip>
    );
  }
  return <Tag>{text.sellableUnknown}</Tag>;
}

function Premium({ product }: { readonly product: DiagnosisProduct }): React.JSX.Element | null {
  const premium = product.price?.premiumOverPlatformCompetitor ?? null;
  if (premium === null) {
    return product.price?.platformCompetitorMinPrice === null ? (
      <Typography.Text type="secondary">{text.noCompetitor}</Typography.Text>
    ) : null;
  }
  const negative = premium.startsWith('-');
  const percent = formatPercent(negative ? premium.slice(1) : premium);
  return (
    <Typography.Text type={negative ? 'success' : 'danger'} style={{ whiteSpace: 'nowrap' }}>
      {negative ? text.cheaper(percent) : text.premium(percent)}
    </Typography.Text>
  );
}

function ConfidenceC(): React.JSX.Element {
  return (
    <Space size={4}>
      <Tag>{text.confidenceC}</Tag>
      <InfoTip title={text.confidenceCHint} long />
    </Space>
  );
}

/**
 * Why a store's listings do not sell.
 *
 * Every listing is shown with the newest signal of each kind and its time;
 * nothing is scored or blended. The counts on top are what the marketplace
 * itself stated. Filters and the open listing live in the address bar, so a
 * reload or a shared link shows the same view.
 */
export function StoreDiagnosisView({
  context,
  storeId,
}: StoreDiagnosisViewProps): React.JSX.Element {
  const [rawFilter] = useSearchParam(FILTER_PARAM);
  const [query] = useSearchParam(QUERY_PARAM);
  const [openListing] = useSearchParam(LISTING_PARAM);
  const patch = useSearchParamsPatch();
  const filter = readFilter(rawFilter);
  const [search, setSearch] = useState(query ?? '');
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    setSearch(query ?? '');
  }, [query]);

  useEffect(() => {
    if (storeId === '') return undefined;
    let live = true;
    setLoaded((current) => (current.kind === 'loaded' ? current : { kind: 'loading' }));
    void fetchStoreDiagnosis(context, storeId).then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { kind: 'loaded', diagnosis: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, storeId, generation]);

  const diagnosis = loaded.kind === 'loaded' ? loaded.diagnosis : undefined;
  const rows = useMemo(
    () =>
      (diagnosis?.products ?? []).filter((product) =>
        matches(product, filter, (query ?? '').trim()),
      ),
    [diagnosis, filter, query],
  );
  const selected = diagnosis?.products.find((product) => product.listingId === openListing);

  if (storeId === '') {
    return <EmptyState description={text.noStore} />;
  }
  // A session that has ended is reported once by the session surface.
  if (loaded.kind === 'failed' && loaded.failure.kind === 'unauthenticated') {
    return <SectionCard title={text.title} state="signed-out" />;
  }

  const refresh = (
    <Button
      icon={<ReloadOutlined />}
      aria-label={text.refreshLabel}
      onClick={() => {
        setGeneration((value) => value + 1);
      }}
    >
      {actions.refresh}
    </Button>
  );

  if (loaded.kind === 'failed') {
    return <FailureAlert failure={loaded.failure} action={refresh} />;
  }
  if (diagnosis === undefined) {
    return <LoadingState rows={6} />;
  }

  const summary = diagnosis.summary;
  const window = diagnosis.ordersWindow;
  const columns: TableColumnsType<DiagnosisProduct> = [
    {
      key: 'product',
      title: text.columnProduct,
      width: 300,
      render: (_, product) => (
        <Flex vertical gap={2}>
          <Typography.Text strong ellipsis={{ tooltip: product.title }} style={{ maxWidth: 280 }}>
            {product.title ?? text.untitled}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {product.nativeSkuKey === null ? '' : `${text.offerId} ${product.nativeSkuKey} · `}
            {`${text.productId} ${product.nativeListingKey}`}
          </Typography.Text>
        </Flex>
      ),
    },
    {
      key: 'visibility',
      title: text.columnVisibility,
      width: 96,
      render: (_, product) => <Visibility product={product} />,
    },
    {
      key: 'stock',
      title: text.columnStock,
      width: 100,
      align: 'right',
      sorter: (a, b) => (a.stock?.available ?? -1) - (b.stock?.available ?? -1),
      render: (_, product) => {
        const available = product.stock?.available ?? null;
        return available === null ? (
          <Typography.Text type="secondary">{text.notObserved}</Typography.Text>
        ) : (
          <Typography.Text {...(available === 0 ? { type: 'danger' as const } : {})}>
            {available}
          </Typography.Text>
        );
      },
    },
    {
      key: 'price',
      title: text.columnPrice,
      width: 130,
      align: 'right',
      render: (_, product) => (
        <Money value={buyerPrice(product)} currency={product.price?.currencyCode ?? null} />
      ),
    },
    {
      key: 'competitiveness',
      title: (
        <Space size={4}>
          {text.columnCompetitiveness}
          <InfoTip title={text.confidenceCHint} long />
        </Space>
      ),
      width: 230,
      sorter: (a, b) =>
        sortable(a.price?.premiumOverPlatformCompetitor) -
        sortable(b.price?.premiumOverPlatformCompetitor),
      render: (_, product) =>
        product.price === null ? (
          <Typography.Text type="secondary">{text.notObserved}</Typography.Text>
        ) : (
          <Flex vertical gap={2} align="flex-start">
            <CodeTag
              labels={PRICE_INDEX_LABELS}
              code={product.price.indexNative}
              colors={PRICE_INDEX_COLORS}
            />
            <Premium product={product} />
          </Flex>
        ),
    },
    {
      key: 'content',
      title: text.columnContent,
      width: 100,
      align: 'right',
      sorter: (a, b) => sortable(a.content?.rating) - sortable(b.content?.rating),
      render: (_, product) =>
        product.content === null ? (
          <Typography.Text type="secondary">{text.notObserved}</Typography.Text>
        ) : (
          formatDecimal(product.content.rating, { maxFractionDigits: 1 })
        ),
    },
    {
      key: 'orders',
      title: text.columnOrders,
      width: 100,
      align: 'right',
      sorter: (a, b) => (a.orders?.orderedUnits ?? -1) - (b.orders?.orderedUnits ?? -1),
      render: (_, product) =>
        product.orders === null ? (
          <Typography.Text type="secondary">{text.noRecord}</Typography.Text>
        ) : (
          product.orders.orderedUnits
        ),
    },
  ];

  return (
    <Flex vertical gap={16}>
      <SectionCard
        title={text.summaryTitle}
        extra={
          <Space size={8}>
            <Typography.Text type="secondary">{text.generatedAt}</Typography.Text>
            <DateTime value={diagnosis.generatedAt} />
            {refresh}
          </Space>
        }
      >
        <Row gutter={[12, 12]}>
          <Col xs={12} md={8} xl={4}>
            <Card size="small">
              <Statistic title={text.tileProducts} value={summary.products} />
            </Card>
          </Col>
          <Col xs={12} md={8} xl={4}>
            <Card size="small">
              <Statistic
                title={
                  <Space size={4}>
                    {text.tileNotSellable}
                    <InfoTip title={text.tileNotSellableHint} />
                  </Space>
                }
                value={summary.notSellable}
              />
              {summary.sellabilityUnknown > 0 && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {text.unknownSellability(summary.sellabilityUnknown)}
                </Typography.Text>
              )}
            </Card>
          </Col>
          <Col xs={12} md={8} xl={4}>
            <Card size="small">
              <Statistic
                title={
                  <Space size={4}>
                    {text.tileWithoutStock}
                    <InfoTip title={text.tileWithoutStockHint} />
                  </Space>
                }
                value={summary.withoutStock}
              />
            </Card>
          </Col>
          <Col xs={12} md={8} xl={4}>
            <Card size="small">
              <Statistic
                title={
                  <Space size={4}>
                    {text.tilePriceRed}
                    <InfoTip title={text.tilePriceRedHint} />
                  </Space>
                }
                value={summary.priceIndexRed}
              />
              {summary.priceIndexYellow > 0 && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {text.yellowCount(summary.priceIndexYellow)}
                </Typography.Text>
              )}
            </Card>
          </Col>
          <Col xs={12} md={8} xl={4}>
            <Card size="small">
              <Statistic title={text.tileWithOrders} value={summary.withOrders} />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {window === null
                  ? text.ordersNoWindow
                  : text.ordersWindow(window.days, window.daysCovered)}
              </Typography.Text>
            </Card>
          </Col>
          <Col xs={12} md={8} xl={4}>
            <Card size="small">
              <Statistic
                title={
                  <Space size={4}>
                    {text.tileContent}
                    <InfoTip title={text.tileContentHint} />
                  </Space>
                }
                value={summary.averageContentRating ?? '—'}
              />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {text.ratedCount(summary.rated)}
              </Typography.Text>
            </Card>
          </Col>
        </Row>
      </SectionCard>

      <SectionCard title={text.productsTitle}>
        {/* The filters sit above the table, not in the header, so a narrow pane keeps the title. */}
        <Flex vertical gap={12}>
          <Flex gap={8} wrap align="center">
            <Segmented<string>
              aria-label={text.filterLabel}
              value={filter}
              options={[
                { value: 'all', label: text.filterAll },
                { value: 'problems', label: text.filterProblems },
                { value: 'notSellable', label: text.filterNotSellable },
                { value: 'withoutStock', label: text.filterWithoutStock },
                { value: 'priceRed', label: text.filterPriceRed },
                { value: 'withOrders', label: text.filterWithOrders },
              ]}
              onChange={(value) => {
                patch({ [FILTER_PARAM]: value === 'all' ? undefined : value });
              }}
            />
            <Input.Search
              aria-label={text.searchLabel}
              placeholder={text.searchPlaceholder}
              allowClear
              style={{ width: 220 }}
              maxLength={64}
              value={search}
              onChange={(event) => {
                setSearch(event.target.value);
              }}
              onSearch={(value) => {
                const trimmed = value.trim();
                patch({ [QUERY_PARAM]: trimmed === '' ? undefined : trimmed });
              }}
            />
          </Flex>
          {diagnosis.products.length === 0 ? (
            <EmptyState description={text.empty} />
          ) : (
            <Table<DiagnosisProduct>
              rowKey="variantId"
              size="middle"
              columns={columns}
              dataSource={rows}
              pagination={{ pageSize: 50, hideOnSinglePage: true, showSizeChanger: false }}
              scroll={{ x: 1060 }}
              locale={{ emptyText: text.emptyFiltered }}
              onRow={(product) => ({
                onClick: () => {
                  patch({ [LISTING_PARAM]: product.listingId });
                },
                style: { cursor: 'pointer' },
              })}
            />
          )}
        </Flex>
      </SectionCard>

      <ProductDrawer
        product={selected}
        onClose={() => {
          patch({ [LISTING_PARAM]: undefined });
        }}
      />
    </Flex>
  );
}

/** Every signal of one listing with its time and source. */
function ProductDrawer({
  product,
  onClose,
}: {
  readonly product: DiagnosisProduct | undefined;
  readonly onClose: () => void;
}): React.JSX.Element {
  return (
    <DetailDrawer
      open={product !== undefined}
      onClose={onClose}
      title={product?.title ?? text.drawerTitle}
      size="large"
    >
      {product === undefined ? null : (
        <Flex vertical gap={16}>
          <Descriptions size="small" column={1} bordered title={text.sectionIdentity}>
            <Descriptions.Item label={text.productId}>{product.nativeListingKey}</Descriptions.Item>
            <Descriptions.Item label={text.offerId}>
              {product.nativeSkuKey ?? '—'}
            </Descriptions.Item>
          </Descriptions>

          <Descriptions size="small" column={1} bordered title={text.sectionVisibility}>
            {product.visibility === null ? (
              <Descriptions.Item label={text.columnVisibility}>
                {text.notObserved}
              </Descriptions.Item>
            ) : (
              <>
                <Descriptions.Item label={text.columnVisibility}>
                  <Visibility product={product} />
                </Descriptions.Item>
                <Descriptions.Item label={text.nativeStatus}>
                  {product.visibility.nativeStatus ?? '—'}
                </Descriptions.Item>
                <Descriptions.Item label={text.blockedReason}>
                  {product.visibility.blockedReason ?? '—'}
                </Descriptions.Item>
                <Descriptions.Item label={text.observedAt}>
                  <DateTime value={product.visibility.observedAt} />
                </Descriptions.Item>
              </>
            )}
          </Descriptions>

          <Descriptions size="small" column={1} bordered title={text.sectionStock}>
            {product.stock === null ? (
              <Descriptions.Item label={text.available}>{text.notObserved}</Descriptions.Item>
            ) : (
              <>
                <Descriptions.Item label={text.available}>
                  {product.stock.available ?? '—'}
                </Descriptions.Item>
                <Descriptions.Item label={text.reserved}>
                  {product.stock.reserved ?? '—'}
                </Descriptions.Item>
                <Descriptions.Item label={text.fulfillmentModes}>
                  {product.stock.fulfillmentModes.length === 0
                    ? '—'
                    : product.stock.fulfillmentModes
                        .map((mode) => FULFILLMENT_MODE_LABELS[mode] ?? mode)
                        .join('、')}
                </Descriptions.Item>
                <Descriptions.Item label={text.observedAt}>
                  <DateTime value={product.stock.observedAt} />
                </Descriptions.Item>
              </>
            )}
          </Descriptions>

          <Descriptions
            size="small"
            column={1}
            bordered
            title={text.sectionPrice}
            extra={product.price === null ? null : <ConfidenceC />}
          >
            {product.price === null ? (
              <Descriptions.Item label={text.sellingPrice}>{text.notObserved}</Descriptions.Item>
            ) : (
              <>
                <Descriptions.Item label={text.sellingPrice}>
                  <Money value={product.price.sellingPrice} currency={product.price.currencyCode} />
                </Descriptions.Item>
                <Descriptions.Item label={text.discountPrice}>
                  <Money
                    value={product.price.discountPrice}
                    currency={product.price.currencyCode}
                  />
                </Descriptions.Item>
                <Descriptions.Item label={text.listPrice}>
                  <Money value={product.price.listPrice} currency={product.price.currencyCode} />
                </Descriptions.Item>
                <Descriptions.Item label={text.priceIndex}>
                  <CodeTag
                    labels={PRICE_INDEX_LABELS}
                    code={product.price.indexNative}
                    colors={PRICE_INDEX_COLORS}
                  />
                </Descriptions.Item>
                <Descriptions.Item label={text.platformCompetitor}>
                  <Space size={8} wrap>
                    <Money
                      value={product.price.platformCompetitorMinPrice}
                      currency={product.price.platformCompetitorCurrencyCode}
                    />
                    <Premium product={product} />
                  </Space>
                </Descriptions.Item>
                <Descriptions.Item label={text.externalCompetitor}>
                  <Money
                    value={product.price.externalCompetitorMinPrice}
                    currency={product.price.externalCompetitorCurrencyCode}
                  />
                </Descriptions.Item>
                <Descriptions.Item label={text.observedAt}>
                  <DateTime value={product.price.observedAt} />
                </Descriptions.Item>
              </>
            )}
          </Descriptions>
          {product.price !== null && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.premiumBasis}
            </Typography.Text>
          )}

          <Descriptions size="small" column={1} bordered title={text.sectionContent}>
            {product.content === null ? (
              <Descriptions.Item label={text.rating}>{text.notObserved}</Descriptions.Item>
            ) : (
              <>
                <Descriptions.Item label={text.rating}>
                  {formatDecimal(product.content.rating, { maxFractionDigits: 2 })}
                </Descriptions.Item>
                <Descriptions.Item label={text.observedAt}>
                  <DateTime value={product.content.observedAt} />
                </Descriptions.Item>
              </>
            )}
          </Descriptions>

          <Descriptions size="small" column={1} bordered title={text.sectionOrders}>
            <Descriptions.Item label={text.orderedUnits}>
              {product.orders === null ? text.noRecord : product.orders.orderedUnits}
            </Descriptions.Item>
            <Descriptions.Item label={text.daysWithRecords}>
              {product.orders === null ? '—' : product.orders.daysWithRecords}
            </Descriptions.Item>
          </Descriptions>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.ordersHint}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.sourceNote}
          </Typography.Text>
        </Flex>
      )}
    </DetailDrawer>
  );
}
