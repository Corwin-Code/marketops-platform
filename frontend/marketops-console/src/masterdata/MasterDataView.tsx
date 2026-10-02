import { ReloadOutlined } from '@ant-design/icons';
import {
  App,
  Button,
  Card,
  Col,
  Flex,
  Form,
  Input,
  InputNumber,
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
import type { MasterData, MasterDataRow } from '../api/masterData';
import {
  adoptMarketplaceCosts,
  confirmProposal,
  enableAutomation,
  fetchMasterData,
  retireAutomation,
  runProposals,
} from '../api/masterData';
import { actions } from '../i18n';
import {
  CONFLICT_LABELS,
  COST_ANOMALY_LABELS,
  MATCH_METHOD_LABELS,
  masterDataText as text,
} from '../i18n/zh/masterData';
import {
  ActionModal,
  DateTime,
  EmptyState,
  FailureAlert,
  failureMessage,
  InfoTip,
  LoadingState,
  Money,
  SectionCard,
  useSearchParam,
  useSearchParamsPatch,
} from '../ui';

/** What the review needs in order to load itself. */
export interface MasterDataViewProps {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}

const FILTERS = ['all', 'proposed', 'conflicts', 'unmatched', 'costToAdopt'] as const;
type Filter = (typeof FILTERS)[number];
const FILTER_PARAM = 'f';

type Loaded =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly data: MasterData }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

interface ReasonValues {
  readonly reason?: string;
}

interface EnableValues {
  readonly limitPercent?: number;
  readonly reason?: string;
}

/** A decimal ratio as a percentage for display: 0.30 -> "30". */
function percent(ratio: string): string {
  const value = Number(ratio) * 100;
  return Number.isFinite(value) ? String(Math.round(value * 100) / 100) : ratio;
}

function readFilter(raw: string | undefined): Filter {
  return (FILTERS as readonly string[]).includes(raw ?? '') ? (raw as Filter) : 'all';
}

function matches(row: MasterDataRow, filter: Filter): boolean {
  switch (filter) {
    case 'proposed':
      return row.state === 'PROPOSED';
    case 'conflicts':
      return row.state === 'CONFLICT';
    case 'unmatched':
      return row.state === 'UNMATCHED';
    case 'costToAdopt':
      return row.costState === 'TO_ADOPT';
    case 'all':
      return true;
  }
}

function StateTag({ row }: { readonly row: MasterDataRow }): React.JSX.Element {
  switch (row.state) {
    case 'MAPPED':
      return <Tag color="success">{text.stateMapped}</Tag>;
    case 'PROPOSED':
      return <Tag color="processing">{text.stateProposed}</Tag>;
    case 'CONFLICT':
      return <Tag color="error">{text.stateConflict}</Tag>;
    case 'UNMATCHED':
      return <Tag>{text.stateUnmatched}</Tag>;
  }
}

function Internal({ row }: { readonly row: MasterDataRow }): React.JSX.Element {
  if (row.mapping !== null) {
    return (
      <Flex vertical gap={2}>
        <Typography.Text code>{row.mapping.skuCode}</Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.since} <DateTime value={row.mapping.effectiveFrom} />
        </Typography.Text>
      </Flex>
    );
  }
  const proposal = row.proposals[0];
  if (proposal !== undefined) {
    const method = MATCH_METHOD_LABELS[proposal.matchMethod] ?? proposal.matchMethod;
    return (
      <Flex vertical gap={2}>
        <Typography.Text code>{proposal.skuCode}</Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {proposal.confidence === null
            ? method
            : `${method} · ${text.confidence(proposal.confidence)}`}
          {row.proposals.length > 1 ? ` · ${text.moreProposals(row.proposals.length - 1)}` : ''}
        </Typography.Text>
        {row.conflicts.length > 0 && (
          <Typography.Text type="warning" style={{ fontSize: 12 }}>
            {text.conflictBesideProposal(
              row.conflicts
                .map((conflict) => CONFLICT_LABELS[conflict.kind] ?? conflict.kind)
                .join('、'),
            )}
          </Typography.Text>
        )}
      </Flex>
    );
  }
  const conflict = row.conflicts[0];
  if (conflict !== undefined) {
    const label = CONFLICT_LABELS[conflict.kind] ?? conflict.kind;
    return conflict.detail === null ? (
      <Typography.Text type="danger">{label}</Typography.Text>
    ) : (
      <Tooltip title={conflict.detail}>
        <Typography.Text type="danger">{label}</Typography.Text>
      </Tooltip>
    );
  }
  return <Typography.Text type="secondary">{text.noInternal}</Typography.Text>;
}

function sourceLabel(kind: string | null): string {
  if (kind === 'MARKETPLACE_RAW') return text.sourceMarketplace;
  if (kind === 'MANUAL_ENTRY') return text.sourceManual;
  if (kind === 'INTERNAL_IMPORT') return text.sourceImport;
  return '';
}

/**
 * Which internal SKU each marketplace listing is mapped to, and whether the
 * seller's marketplace cost is the internal purchase cost.
 *
 * Proposals are generated by the matcher and confirmed by a person, one
 * decision per candidate; marketplace costs are adopted in one all-or-nothing
 * request. The filter lives in the address bar.
 */
export function MasterDataView({ context, storeId }: MasterDataViewProps): React.JSX.Element {
  const { message } = App.useApp();
  const [rawFilter] = useSearchParam(FILTER_PARAM);
  const patch = useSearchParamsPatch();
  const filter = readFilter(rawFilter);
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [selected, setSelected] = useState<readonly string[]>([]);
  const [proposing, setProposing] = useState(false);

  useEffect(() => {
    if (storeId === '') return undefined;
    let live = true;
    setLoaded((current) => (current.kind === 'loaded' ? current : { kind: 'loading' }));
    void fetchMasterData(context, storeId).then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { kind: 'loaded', data: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, storeId, generation]);

  const data = loaded.kind === 'loaded' ? loaded.data : undefined;
  const rows = useMemo(
    () => (data?.rows ?? []).filter((row) => matches(row, filter)),
    [data, filter],
  );
  const selectedRows = useMemo(
    () => (data?.rows ?? []).filter((row) => selected.includes(row.listingVariantId)),
    [data, selected],
  );
  const toConfirm = selectedRows.filter(
    (row) => row.state === 'PROPOSED' && row.proposals.length > 0,
  );
  const toAdopt = selectedRows.filter(
    (row) => row.costState === 'TO_ADOPT' && row.sellerCost !== null,
  );

  if (storeId === '') {
    return <EmptyState description={text.noStore} />;
  }
  if (loaded.kind === 'failed' && loaded.failure.kind === 'unauthenticated') {
    return <SectionCard title={text.listTitle} state="signed-out" />;
  }

  const reload = (): void => {
    setGeneration((value) => value + 1);
  };
  const refresh = (
    <Button icon={<ReloadOutlined />} aria-label={text.refreshLabel} onClick={reload}>
      {actions.refresh}
    </Button>
  );

  if (loaded.kind === 'failed') {
    return <FailureAlert failure={loaded.failure} action={refresh} />;
  }
  if (data === undefined) {
    return <LoadingState rows={6} />;
  }

  const summary = data.summary;
  const reasonField = (
    <Form.Item
      name="reason"
      label={text.reasonLabel}
      rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
    >
      <Input.TextArea rows={3} maxLength={500} showCount placeholder={text.reasonPlaceholder} />
    </Form.Item>
  );

  const columns: TableColumnsType<MasterDataRow> = [
    {
      key: 'listing',
      title: text.columnListing,
      width: 320,
      render: (_, row) => (
        <Flex vertical gap={2}>
          <Typography.Text strong ellipsis={{ tooltip: row.title }} style={{ maxWidth: 300 }}>
            {row.title ?? text.untitled}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {row.nativeSkuKey === null ? '' : `${text.offerId} ${row.nativeSkuKey} · `}
            {`${text.productId} ${row.nativeListingKey}`}
          </Typography.Text>
        </Flex>
      ),
    },
    {
      key: 'state',
      title: text.columnState,
      width: 100,
      render: (_, row) => <StateTag row={row} />,
    },
    {
      key: 'internal',
      title: text.columnInternal,
      width: 240,
      render: (_, row) => <Internal row={row} />,
    },
    ...(data.costsVisible
      ? ([
          {
            key: 'sellerCost',
            title: text.columnSellerCost,
            width: 150,
            align: 'right',
            render: (_, row) =>
              row.sellerCost === null ? (
                <Typography.Text type="secondary">{text.noCost}</Typography.Text>
              ) : (
                <Flex vertical gap={2} align="flex-end">
                  <Money value={row.sellerCost.amount} currency={row.sellerCost.currencyCode} />
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {text.observedAt} <DateTime value={row.sellerCost.observedAt} />
                  </Typography.Text>
                </Flex>
              ),
          },
          {
            key: 'purchaseCost',
            title: text.columnPurchaseCost,
            width: 170,
            align: 'right',
            render: (_, row) =>
              row.purchaseCost === null ? (
                <Typography.Text type="secondary">{text.noCost}</Typography.Text>
              ) : (
                <Flex vertical gap={2} align="flex-end">
                  <Money
                    value={row.purchaseCost.unitCost}
                    currency={row.purchaseCost.currencyCode}
                  />
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {sourceLabel(row.purchaseCost.sourceKind)} · {text.since}{' '}
                    <DateTime value={row.purchaseCost.effectiveFrom} />
                  </Typography.Text>
                </Flex>
              ),
          },
          {
            key: 'costState',
            title: text.columnCostState,
            width: 100,
            render: (_, row) => {
              if (row.costState === 'ADOPTED') return <Tag color="success">{text.costAdopted}</Tag>;
              if (row.costState === 'TO_ADOPT') {
                return row.costAnomaly === null ? (
                  <Tag color="warning">{text.costToAdopt}</Tag>
                ) : (
                  <Tooltip
                    title={`${text.anomalyPrefix}${COST_ANOMALY_LABELS[row.costAnomaly] ?? row.costAnomaly}`}
                  >
                    <Tag color="error">
                      {COST_ANOMALY_LABELS[row.costAnomaly] ?? row.costAnomaly}
                    </Tag>
                  </Tooltip>
                );
              }
              return <Typography.Text type="secondary">{text.noCost}</Typography.Text>;
            },
          },
        ] satisfies TableColumnsType<MasterDataRow>)
      : []),
  ];

  const confirmSelected = async (reason: string): Promise<undefined> => {
    let done = 0;
    let failed = 0;
    for (const row of toConfirm) {
      const proposal = row.proposals[0];
      if (proposal === undefined) continue;
      const outcome = await confirmProposal(
        context,
        proposal.candidateId,
        reason,
        proposal.version,
      );
      if (outcome.ok) done += 1;
      else failed += 1;
    }
    void (failed === 0 ? message.success : message.warning)(text.confirmDone(done, failed));
    setSelected([]);
    reload();
    return undefined;
  };

  const adoptSelected = async (reason: string): Promise<ConsoleFailure | undefined> => {
    const outcome = await adoptMarketplaceCosts(
      context,
      storeId,
      toAdopt.flatMap((row) =>
        row.sellerCost === null
          ? []
          : [
              {
                listingVariantId: row.listingVariantId,
                priceObservationId: row.sellerCost.priceObservationId,
              },
            ],
      ),
      reason,
    );
    if (!outcome.ok) return outcome.failure;
    void message.success(text.adoptDone(outcome.value.adopted, outcome.value.unchanged));
    setSelected([]);
    reload();
    return undefined;
  };

  const tiles: readonly {
    readonly key: string;
    readonly title: string;
    readonly value: number | string;
    readonly hint?: string;
  }[] = [
    { key: 'listings', title: text.tileListings, value: summary.listings },
    { key: 'mapped', title: text.tileMapped, value: summary.mapped, hint: text.tileMappedHint },
    {
      key: 'proposed',
      title: text.tileProposed,
      value: summary.proposed,
      hint: text.tileProposedHint,
    },
    {
      key: 'conflicts',
      title: text.tileConflicts,
      value: summary.conflicts,
      hint: text.tileConflictsHint,
    },
    { key: 'unmatched', title: text.tileUnmatched, value: summary.unmatched },
    ...(data.costsVisible
      ? [
          { key: 'costAdopted', title: text.tileCostAdopted, value: summary.costAdopted },
          {
            key: 'costToAdopt',
            title: text.tileCostToAdopt,
            value: summary.costToAdopt,
            hint: text.tileCostToAdoptHint,
          },
        ]
      : [{ key: 'costHidden', title: text.tileCostHidden, value: '—', hint: text.costHidden }]),
  ];

  return (
    <Flex vertical gap={16}>
      <SectionCard>
        {/* Title and controls wrap on a narrow screen instead of the controls
            squeezing the title out of a card header. */}
        <Flex justify="space-between" align="center" gap={8} wrap style={{ marginBottom: 12 }}>
          <Typography.Text strong style={{ fontSize: 16 }}>
            {text.summaryTitle}
          </Typography.Text>
          <Flex gap={8} wrap align="center">
            <Space size={8}>
              <Typography.Text type="secondary">{text.generatedAt}</Typography.Text>
              <DateTime value={data.generatedAt} />
            </Space>
            {refresh}
          </Flex>
        </Flex>
        <Row gutter={[12, 12]}>
          {tiles.map((tile) => (
            <Col key={tile.key} xs={12} md={6} xl={3}>
              <Card size="small">
                <Statistic
                  title={
                    tile.hint === undefined ? (
                      tile.title
                    ) : (
                      <Space size={4}>
                        {tile.title}
                        <InfoTip title={tile.hint} />
                      </Space>
                    )
                  }
                  value={tile.value}
                />
              </Card>
            </Col>
          ))}
        </Row>
      </SectionCard>

      {data.costsVisible && (
        <SectionCard title={text.automationTitle}>
          <Flex vertical gap={8}>
            {data.automation === null ? (
              <Typography.Text>{text.automationOff}</Typography.Text>
            ) : (
              <Flex vertical gap={2}>
                <Typography.Text>
                  <Tag color="success">{text.automationEnabledTag}</Tag>
                  {text.automationOn(percent(data.automation.costChangeLimit))}
                </Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {text.automationAuthorizedAt} <DateTime value={data.automation.authorizedAt} />
                  {data.automation.reason === '' ? '' : ` · ${data.automation.reason}`}
                </Typography.Text>
              </Flex>
            )}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.automationRules}
            </Typography.Text>
            <Flex gap={8} wrap>
              <ActionModal<EnableValues>
                trigger={{
                  label: text.enableAutomation,
                  type: data.automation === null ? 'primary' : 'default',
                }}
                title={text.enableTitle}
                consequence={text.enableConsequence}
                okText={actions.confirm}
                width={560}
                initialValues={{ limitPercent: 30 }}
                onSubmit={async (values) => {
                  const limit = (values.limitPercent ?? 30) / 100;
                  const outcome = await enableAutomation(
                    context,
                    storeId,
                    limit.toFixed(4),
                    (values.reason ?? '').trim(),
                  );
                  if (!outcome.ok) return outcome.failure;
                  void message.success(
                    text.automationRunDone(
                      outcome.value.mappingsConfirmed,
                      outcome.value.costsAdopted,
                      outcome.value.costsWaiting,
                      outcome.value.listingsAwaitingReview,
                    ),
                  );
                  setSelected([]);
                  reload();
                  return undefined;
                }}
              >
                <Form.Item
                  name="limitPercent"
                  label={text.limitLabel}
                  extra={text.limitHelp}
                  rules={[
                    { required: true, message: text.limitRequired },
                    { type: 'number', min: 1, max: 100, message: text.limitRequired },
                  ]}
                >
                  <InputNumber min={1} max={100} step={5} precision={2} />
                </Form.Item>
                {reasonField}
              </ActionModal>
              {data.automation !== null && (
                <ActionModal<ReasonValues>
                  trigger={{ label: text.retireAutomation, danger: true }}
                  title={text.retireTitle}
                  consequence={text.retireConsequence}
                  okText={actions.confirm}
                  danger
                  width={520}
                  onSubmit={async (values) => {
                    if (data.automation === null) return undefined;
                    const outcome = await retireAutomation(
                      context,
                      storeId,
                      (values.reason ?? '').trim(),
                      data.automation.version,
                    );
                    if (!outcome.ok) return outcome.failure;
                    void message.success(text.automationRetired);
                    reload();
                    return undefined;
                  }}
                >
                  {reasonField}
                </ActionModal>
              )}
            </Flex>
          </Flex>
        </SectionCard>
      )}

      <SectionCard title={text.listTitle}>
        <Flex vertical gap={12}>
          <Flex gap={8} wrap align="center">
            <Segmented<string>
              aria-label={text.filterLabel}
              value={filter}
              options={[
                { value: 'all', label: text.filterAll },
                { value: 'proposed', label: text.filterProposed },
                { value: 'conflicts', label: text.filterConflicts },
                { value: 'unmatched', label: text.filterUnmatched },
                ...(data.costsVisible
                  ? [{ value: 'costToAdopt', label: text.filterCostToAdopt }]
                  : []),
              ]}
              onChange={(value) => {
                patch({ [FILTER_PARAM]: value === 'all' ? undefined : value });
              }}
            />
          </Flex>
          <Flex gap={8} wrap align="center">
            <Button
              loading={proposing}
              onClick={() => {
                setProposing(true);
                void runProposals(context, storeId).then((outcome) => {
                  setProposing(false);
                  if (outcome.ok) {
                    void message.success(
                      text.runProposalsDone(outcome.value.listingVariantsExamined),
                    );
                    reload();
                  } else {
                    void message.error(failureMessage(outcome.failure));
                  }
                });
              }}
            >
              {text.runProposals}
            </Button>
            <ActionModal<ReasonValues>
              trigger={{
                label: text.confirmSelected(toConfirm.length),
                type: 'primary',
                disabled: toConfirm.length === 0,
                disabledReason: text.confirmNothing,
              }}
              title={text.confirmTitle}
              consequence={text.confirmConsequence}
              summary={<Typography.Text>{text.confirmSummary(toConfirm.length)}</Typography.Text>}
              okText={actions.confirm}
              width={560}
              onSubmit={(values) => confirmSelected((values.reason ?? '').trim())}
            >
              {reasonField}
            </ActionModal>
            {data.costsVisible && (
              <ActionModal<ReasonValues>
                trigger={{
                  label: text.adoptSelected(toAdopt.length),
                  disabled: toAdopt.length === 0,
                  disabledReason: text.adoptNothing,
                }}
                title={text.adoptTitle}
                consequence={text.adoptConsequence}
                summary={<Typography.Text>{text.adoptSummary(toAdopt.length)}</Typography.Text>}
                okText={actions.confirm}
                width={560}
                onSubmit={(values) => adoptSelected((values.reason ?? '').trim())}
              >
                {reasonField}
              </ActionModal>
            )}
          </Flex>
          {data.rows.length === 0 ? (
            <EmptyState description={text.empty} />
          ) : (
            <Table<MasterDataRow>
              rowKey="listingVariantId"
              size="middle"
              columns={columns}
              dataSource={rows}
              rowSelection={{
                selectedRowKeys: [...selected],
                preserveSelectedRowKeys: true,
                onChange: (keys) => {
                  setSelected(keys.map(String));
                },
              }}
              pagination={{ pageSize: 50, hideOnSinglePage: true, showSizeChanger: false }}
              scroll={{ x: data.costsVisible ? 1130 : 710 }}
              locale={{ emptyText: text.emptyFiltered }}
            />
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.note}
          </Typography.Text>
        </Flex>
      </SectionCard>
    </Flex>
  );
}
