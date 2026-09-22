import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Flex, Input, Segmented, Space, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { ListingHealth, ListingHealthPage } from '../api/listingConversion';
import { fetchHealthQueuePage } from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { healthQueueText as text } from '../i18n/zh/listingHealth';
import {
  EmptyState,
  InfoTip,
  LoadingState,
  SectionCard,
  usePageParam,
  useSearchParam,
  useSearchParamsPatch,
} from '../ui';
import { Code, Codes, ListingName, ListingProblem, When, codeText } from './ListingCommon';
import { Eligibility, ListingHealthDetail } from './ListingHealthDetail';

export interface ListingHealthPanelProps {
  readonly context: ConsoleRequest;
  readonly onPrepare: (listingId: string) => void;
}

const HEALTH_STATES = ['PASS', 'FAIL', 'UNKNOWN'] as const;
const ALL = 'ALL';
const PAGE_SIZE = 20;

/** Address-bar keys of the health tab; every tab keeps its own. */
const STATE_PARAM = 'hstate';
const QUERY_PARAM = 'hq';
const PAGE_PARAM = 'hpage';
const LISTING_PARAM = 'listing';
const TAB_PARAM = 'htab';

/** A necessary-state filter read from the address bar; anything else is no filter. */
function readState(raw: string | undefined): string | undefined {
  return (HEALTH_STATES as readonly string[]).includes(raw ?? '') ? raw : undefined;
}

/** The loaded page, or why it is not there. */
type Queue =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly page: ListingHealthPage }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/**
 * Listing Health as three layers and never as a score.
 *
 * The queue is the product's own ordering of necessary-condition state and is
 * never re-sorted here; it is filtered, searched and paged by the backend, and
 * every choice lives in the address bar. A row opens that listing's detail.
 */
export function ListingHealthPanel({
  context,
  onPrepare,
}: ListingHealthPanelProps): React.JSX.Element {
  const [rawState] = useSearchParam(STATE_PARAM);
  const [query] = useSearchParam(QUERY_PARAM);
  const [page, setPage] = usePageParam(PAGE_PARAM);
  const [listingId] = useSearchParam(LISTING_PARAM);
  const patch = useSearchParamsPatch();
  const necessaryState = readState(rawState);
  const [search, setSearch] = useState(query ?? '');
  const [queue, setQueue] = useState<Queue>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [fetching, setFetching] = useState(true);

  useEffect(() => {
    setSearch(query ?? '');
  }, [query]);

  useEffect(() => {
    if (listingId !== undefined) return;
    let live = true;
    setFetching(true);
    setQueue((current) => (current.kind === 'loaded' ? current : { kind: 'loading' }));
    void fetchHealthQueuePage(context, {
      ...(necessaryState === undefined ? {} : { necessaryState }),
      ...(query === undefined ? {} : { q: query }),
      offset: (page - 1) * PAGE_SIZE,
      limit: PAGE_SIZE,
    }).then((outcome) => {
      if (!live) return;
      setFetching(false);
      setQueue(
        outcome.ok
          ? { kind: 'loaded', page: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, necessaryState, query, page, generation, listingId]);

  if (listingId !== undefined) {
    return (
      <ListingHealthDetail
        key={listingId}
        context={context}
        listingId={listingId}
        onBack={() => {
          patch({ [LISTING_PARAM]: undefined, [TAB_PARAM]: undefined });
        }}
        onPrepare={onPrepare}
      />
    );
  }

  const open = (id: string): void => {
    patch({ [LISTING_PARAM]: id, [TAB_PARAM]: undefined });
  };

  const columns: TableColumnsType<ListingHealth> = [
    {
      key: 'listing',
      title: t('listing'),
      width: 320,
      render: (_, health) => (
        <ListingName
          identity={health.identity}
          nativeListingKey={health.nativeListingKey}
          listingId={health.platformListingId}
        />
      ),
    },
    {
      key: 'necessary',
      title: t('healthNecessary'),
      render: (_, health) => <Code family="healthState" code={health.necessaryState} />,
    },
    {
      key: 'eligibility',
      title: t('healthEligibility'),
      render: (_, health) => <Eligibility eligibility={health.eligibility} />,
    },
    {
      key: 'opportunities',
      title: t('healthOpportunities'),
      render: (_, health) => (
        <Codes family="opportunity" codes={health.opportunities.map((entry) => entry.code)} />
      ),
    },
    {
      key: 'computedAt',
      title: t('computedAt'),
      render: (_, health) => <When value={health.computedAt} />,
    },
  ];

  const loaded = queue.kind === 'loaded' ? queue.page : undefined;
  // A page past the end (a shorter queue, an old link) goes back to the last
  // page instead of showing an empty queue with no pager.
  useEffect(() => {
    const total = loaded?.total ?? 0;
    if (loaded?.items.length === 0 && page > 1 && total > 0) {
      setPage(Math.max(1, Math.ceil(total / PAGE_SIZE)));
    }
  }, [loaded, page, setPage]);
  // Without a count, one more page is offered while this one came back full.
  const total =
    loaded === undefined
      ? 0
      : (loaded.total ??
        (page - 1) * PAGE_SIZE + loaded.items.length + (loaded.items.length >= PAGE_SIZE ? 1 : 0));

  return (
    <section aria-label={text.title} data-state={queue.kind}>
      <SectionCard
        title={
          <Space size={0}>
            {text.title}
            <InfoTip title={t('noScore')} />
          </Space>
        }
        extra={
          <Flex gap={8} wrap align="center" justify="flex-end">
            <Segmented<string>
              aria-label={t('healthNecessary')}
              value={necessaryState ?? ALL}
              options={[
                { value: ALL, label: t('all') },
                ...HEALTH_STATES.map((state) => ({
                  value: state,
                  label: codeText('healthState', state),
                })),
              ]}
              onChange={(value) => {
                patch({
                  [STATE_PARAM]: value === ALL ? undefined : value,
                  [PAGE_PARAM]: undefined,
                });
              }}
            />
            <Input.Search
              aria-label={text.searchLabel}
              placeholder={text.searchPlaceholder}
              allowClear
              style={{ width: 280 }}
              maxLength={64}
              value={search}
              onChange={(event) => {
                setSearch(event.target.value);
              }}
              onSearch={(value) => {
                const trimmed = value.trim();
                patch({
                  [QUERY_PARAM]: trimmed === '' ? undefined : trimmed,
                  [PAGE_PARAM]: undefined,
                });
              }}
            />
            <Button
              icon={<ReloadOutlined />}
              aria-label={text.refreshLabel}
              onClick={() => {
                setGeneration((value) => value + 1);
              }}
            >
              {t('refresh')}
            </Button>
          </Flex>
        }
      >
        {queue.kind === 'failed' && <ListingProblem failure={queue.failure} />}
        {loaded?.truncated === true && (
          <Alert
            type="warning"
            showIcon
            title={text.searchTruncated}
            style={{ marginBottom: 12 }}
          />
        )}
        {queue.kind === 'loading' && <LoadingState />}
        {loaded?.items.length === 0 && page > 1 && (loaded.total ?? 0) > 0 ? (
          <LoadingState />
        ) : (
          loaded?.items.length === 0 && (
            <EmptyState description={query === undefined ? t('noHealthRows') : text.noMatch} />
          )
        )}
        {loaded !== undefined && loaded.items.length > 0 && (
          <Table<ListingHealth>
            size="middle"
            rowKey="id"
            columns={columns}
            dataSource={[...loaded.items]}
            scroll={{ x: 'max-content' }}
            loading={fetching}
            pagination={{
              current: page,
              pageSize: PAGE_SIZE,
              total,
              showSizeChanger: false,
              hideOnSinglePage: true,
              showTotal: () =>
                loaded.total === undefined ? text.totalUnknown : text.total(loaded.total),
              onChange: (next) => {
                setPage(next);
              },
            }}
            onRow={(health) =>
              ({
                'data-listing': health.platformListingId,
                onClick: () => {
                  open(health.platformListingId);
                },
                onKeyDown: (event: React.KeyboardEvent<HTMLElement>) => {
                  if (event.key === 'Enter' && event.target === event.currentTarget) {
                    open(health.platformListingId);
                  }
                },
                tabIndex: 0,
                'aria-label': text.openListing(
                  health.identity?.productName ?? health.identity?.title ?? health.nativeListingKey,
                ),
                style: { cursor: 'pointer' },
              }) as React.HTMLAttributes<HTMLElement>
            }
          />
        )}
      </SectionCard>
    </section>
  );
}
