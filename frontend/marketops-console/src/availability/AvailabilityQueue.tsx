import { ReloadOutlined } from '@ant-design/icons';
import { Button, Flex, Input, Segmented, Space, Table, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import type { AvailabilityCard, AvailabilityChild, Page } from '../api/availability';
import { fetchAvailabilityQueue } from '../api/availability';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { formatDecimal } from '../format';
import { actions, codeLabel } from '../i18n';
import { EVIDENCE_LABELS, LANE_LABELS, availabilityText } from '../i18n/zh/availability';
import { riskQueueText as text } from '../i18n/zh/availabilityRisks';
import { ROUTES } from '../layout/navigation';
import {
  CodeTag,
  DateTime,
  EmptyState,
  FailureAlert,
  InfoTip,
  LoadingState,
  Money,
  SectionCard,
  VariantName,
  usePageParam,
  useSearchParam,
  useSearchParamsPatch,
} from '../ui';
import { AvailabilityCardDrawer } from './AvailabilityCardDrawer';
import { childLabel, presentEvidence } from './riskPresentation';
import { EVIDENCE_COLORS, LANE_COLORS } from './tagColors';

/** What the availability queue needs in order to load itself. */
export interface AvailabilityQueueProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
}

const LANES = ['CRITICAL', 'HIGH', 'REVIEW', 'UNRESOLVED', 'WATCH', 'HEALTHY'] as const;
const ALL = 'ALL';
const PAGE_SIZE = 20;

/** Address-bar keys of the queue. */
const LANE_PARAM = 'lane';
const QUERY_PARAM = 'q';
const PAGE_PARAM = 'page';
const VARIANT_PARAM = 'variant';

/** A lane filter read from the address bar; anything else is no filter. */
function readLane(raw: string | undefined): string | undefined {
  return (LANES as readonly string[]).includes(raw ?? '') ? raw : undefined;
}

type Queue =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly page: Page<AvailabilityCard> }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/**
 * What is about to run out, most urgent first.
 *
 * The list is ordered by the backend and is not re-sorted here: the order is a
 * deterministic figure with a published definition, and a console that
 * reordered it would present its own opinion as the product's. It is filtered,
 * searched and paged by the backend, and every choice lives in the address bar.
 *
 * Every row shows its children side by side rather than a blended state,
 * because they fail differently and are fixed by different people: a channel
 * with an empty shelf and a full warehouse is a marketplace problem, a company
 * running out is a procurement one. A row opens the card beside the list.
 */
export function AvailabilityQueue({ context }: AvailabilityQueueProps): React.JSX.Element {
  const navigate = useNavigate();
  const [rawLane] = useSearchParam(LANE_PARAM);
  const [query] = useSearchParam(QUERY_PARAM);
  const [page, setPage] = usePageParam(PAGE_PARAM);
  const [variant] = useSearchParam(VARIANT_PARAM);
  const patch = useSearchParamsPatch();
  const lane = readLane(rawLane);
  const [search, setSearch] = useState(query ?? '');
  const [queue, setQueue] = useState<Queue>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [fetching, setFetching] = useState(true);

  useEffect(() => {
    setSearch(query ?? '');
  }, [query]);

  useEffect(() => {
    let live = true;
    setFetching(true);
    void fetchAvailabilityQueue(context, {
      lane,
      q: query,
      limit: PAGE_SIZE,
      offset: (page - 1) * PAGE_SIZE,
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
  }, [context, lane, query, page, generation]);

  const loaded = queue.kind === 'loaded' ? queue.page : undefined;
  // A page past the end (a shorter queue, an old link) goes back to the last
  // page instead of showing an empty queue with no pager.
  useEffect(() => {
    const total = loaded?.total ?? 0;
    if (loaded?.items.length === 0 && page > 1 && total > 0) {
      setPage(Math.max(1, Math.ceil(total / PAGE_SIZE)));
    }
  }, [loaded, page, setPage]);

  const open = (productVariantId: string): void => {
    patch({ [VARIANT_PARAM]: productVariantId });
  };

  const toolbar = (
    <Flex gap={8} wrap align="center" justify="flex-end">
      <Segmented<string>
        aria-label={text.laneFilter}
        value={lane ?? ALL}
        options={[
          { value: ALL, label: text.allLanes },
          ...LANES.map((code) => ({ value: code, label: codeLabel(LANE_LABELS, code) })),
        ]}
        onChange={(value) => {
          patch({ [LANE_PARAM]: value === ALL ? undefined : value, [PAGE_PARAM]: undefined });
        }}
      />
      <Input.Search
        aria-label={text.searchLabel}
        placeholder={text.searchPlaceholder}
        allowClear
        style={{ width: 240 }}
        maxLength={64}
        value={search}
        onChange={(event) => {
          setSearch(event.target.value);
        }}
        onSearch={(value) => {
          const trimmed = value.trim();
          patch({ [QUERY_PARAM]: trimmed === '' ? undefined : trimmed, [PAGE_PARAM]: undefined });
        }}
      />
      <Button
        icon={<ReloadOutlined />}
        aria-label={text.refreshLabel}
        onClick={() => {
          setGeneration((value) => value + 1);
        }}
      >
        {actions.refresh}
      </Button>
    </Flex>
  );

  // A session that has ended is one condition about the whole session, reported
  // once by the session surface rather than by every panel.
  if (queue.kind === 'failed' && queue.failure.kind === 'unauthenticated') {
    return (
      <section aria-label={availabilityText.queueTitle} data-state="signed-out">
        <SectionCard title={availabilityText.queueTitle} />
      </section>
    );
  }

  const total =
    loaded === undefined
      ? 0
      : (loaded.total ??
        (page - 1) * PAGE_SIZE + loaded.items.length + (loaded.items.length >= PAGE_SIZE ? 1 : 0));

  const columns: TableColumnsType<AvailabilityCard> = [
    {
      key: 'product',
      title: text.columnProduct,
      width: 260,
      render: (_, card) => (
        <VariantName
          identity={{ displayName: card.displayName, skuCode: card.skuCode }}
          productVariantId={card.productVariantId}
        />
      ),
    },
    {
      key: 'risk',
      title: text.columnRisk,
      render: (_, card) => {
        const trigger = card.children.find((child) => child.id === card.triggeringChildId);
        return (
          <Flex vertical gap={2} align="flex-start">
            <CodeTag labels={LANE_LABELS} code={card.lane} colors={LANE_COLORS} />
            {trigger === undefined ? null : (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {text.triggeredBy(
                  childLabel(trigger.childKind, trigger.platformCode, trigger.fulfillmentModeCode),
                )}
              </Typography.Text>
            )}
          </Flex>
        );
      },
    },
    {
      key: 'children',
      title: text.columnChildren,
      render: (_, card) => (
        <Flex vertical gap={4}>
          {card.children.map((child) => (
            <ChildChip key={child.id} child={child} />
          ))}
        </Flex>
      ),
    },
    {
      key: 'stockout',
      title: text.columnStockout,
      render: (_, card) => <DateTime value={earliestStockout(card.children)} />,
    },
    {
      key: 'profit',
      title: (
        <Space size={0}>
          {text.columnProfit}
          <InfoTip title={text.columnProfitHelp} />
        </Space>
      ),
      align: 'right',
      render: (_, card) => {
        const source =
          card.children.find((child) => child.id === card.triggeringChildId) ??
          card.children.find((child) => child.profitAtRiskAmount !== null);
        return (
          <Money
            value={source?.profitAtRiskAmount ?? null}
            currency={source?.profitAtRiskCurrency ?? null}
          />
        );
      },
    },
    {
      key: 'calculated',
      title: text.columnCalculated,
      render: (_, card) => <DateTime value={card.calculatedAt} relative />,
    },
  ];

  return (
    <section aria-label={availabilityText.queueTitle} data-state={queue.kind}>
      <SectionCard title={availabilityText.queueTitle} extra={toolbar}>
        {queue.kind === 'failed' && <FailureAlert failure={queue.failure} />}
        {queue.kind === 'loading' && <LoadingState />}
        {loaded?.items.length === 0 && !(page > 1 && (loaded.total ?? 0) > 0) && (
          <EmptyState
            description={
              query === undefined && lane === undefined ? availabilityText.queueEmpty : text.noMatch
            }
          />
        )}
        {loaded !== undefined && loaded.items.length > 0 && (
          <Table<AvailabilityCard>
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
            onRow={(card) =>
              ({
                'data-card': card.id,
                'data-lane': card.lane,
                onClick: () => {
                  open(card.productVariantId);
                },
                onKeyDown: (event: React.KeyboardEvent<HTMLElement>) => {
                  if (event.key === 'Enter' && event.target === event.currentTarget) {
                    open(card.productVariantId);
                  }
                },
                tabIndex: 0,
                'aria-label': text.openCard(card.displayName),
                style: { cursor: 'pointer' },
              }) as React.HTMLAttributes<HTMLElement>
            }
          />
        )}
      </SectionCard>
      <AvailabilityCardDrawer
        context={context}
        productVariantId={variant}
        onClose={() => {
          patch({ [VARIANT_PARAM]: undefined });
        }}
        onOpenCases={(productVariantId) => {
          void navigate(
            `${ROUTES.availabilityCases}?variant=${encodeURIComponent(productVariantId)}`,
          );
        }}
      />
    </section>
  );
}

/** The soonest projected stockout among the children, or nothing. */
function earliestStockout(children: readonly AvailabilityChild[]): string | null {
  let earliest: string | null = null;
  let earliestAt = Number.POSITIVE_INFINITY;
  for (const child of children) {
    if (child.projectedStockoutAt === null) continue;
    const at = Date.parse(child.projectedStockoutAt);
    if (!Number.isNaN(at) && at < earliestAt) {
      earliestAt = at;
      earliest = child.projectedStockoutAt;
    }
  }
  return earliest;
}

/**
 * One child as a compact chip: where it sits, its lane, what its evidence
 * rests on and its days of cover. The evidence tag is never dropped, because a
 * provisional critical must not read like a confirmed one.
 */
function ChildChip({ child }: { readonly child: AvailabilityChild }): React.JSX.Element {
  const evidence = presentEvidence(child.evidenceState);
  return (
    <Flex
      gap={4}
      align="center"
      wrap
      data-child-kind={child.childKind}
      data-lane={child.lane}
      data-evidence-tone={evidence.tone}
    >
      <Typography.Text style={{ fontSize: 12 }}>
        {childLabel(child.childKind, child.platformCode, child.fulfillmentModeCode)}
      </Typography.Text>
      <CodeTag labels={LANE_LABELS} code={child.lane} colors={LANE_COLORS} />
      {evidence.establishedFact ? null : (
        <Tooltip title={evidence.explanation}>
          <span>
            <CodeTag labels={EVIDENCE_LABELS} code={child.evidenceState} colors={EVIDENCE_COLORS} />
          </span>
        </Tooltip>
      )}
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {child.daysOfCover === null
          ? text.noCover
          : text.daysOfCover(formatDecimal(child.daysOfCover, { maxFractionDigits: 1 }))}
      </Typography.Text>
    </Flex>
  );
}
