import { ReloadOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  Flex,
  Row,
  Space,
  Table,
  Tooltip,
  Typography,
} from 'antd';
import type { CollapseProps, DescriptionsProps, TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import { fetchAvailabilityQueue } from '../api/console';
import type {
  AvailabilityCard,
  AvailabilityChild,
  AvailabilityDemandWindow,
  AvailabilityRankFactor,
  ConsoleFailure,
  ConsoleRequest,
} from '../api/console';
import { formatDecimal, formatPercent } from '../format';
import { actions } from '../i18n';
import {
  BLOCKER_LABELS,
  CENSORING_REASON_LABELS,
  DEMAND_WINDOW_LABELS,
  EVIDENCE_LABELS,
  LANE_LABELS,
  PROFIT_LANE_LABELS,
  RANK_FACTOR_LABELS,
  RISK_CONFIDENCE_LABELS,
  WINDOW_ELIGIBILITY_LABELS,
  availabilityText,
} from '../i18n/zh/availability';
import {
  CodeTag,
  DateTime,
  EmptyState,
  FailureAlert,
  LoadingState,
  Money,
  SectionCard,
  TechnicalDetails,
} from '../ui';
import { causeLabel, childLabel, laneIsSafe, presentEvidence } from './riskPresentation';
import {
  EVIDENCE_COLORS,
  LANE_COLORS,
  PROFIT_LANE_COLORS,
  RISK_CONFIDENCE_COLORS,
  WINDOW_ELIGIBILITY_COLORS,
} from './tagColors';

/** What the availability queue needs in order to load itself. */
export interface AvailabilityQueueProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** Narrow to one lane, or show every lane. */
  readonly lane?: string;
  /** Called when the operator opens a card. */
  readonly onSelect?: (productVariantId: string) => void;
}

/**
 * What is about to run out, and who owns it.
 *
 * The list is ordered by the backend and is not re-sorted here: the order is a
 * deterministic figure with a published definition, and a console that
 * reordered it would present its own opinion as the product's.
 *
 * Every card shows both of its children rather than a blended state, because
 * they fail differently and are fixed by different people. A channel with an
 * empty shelf and a warehouse that is full is a marketplace problem; a company
 * that is running out is a procurement one, and a single merged badge would
 * send both to the wrong person.
 */
export function AvailabilityQueue({
  context,
  lane,
  onSelect,
}: AvailabilityQueueProps): React.JSX.Element {
  const [cards, setCards] = useState<readonly AvailabilityCard[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let active = true;
    void fetchAvailabilityQueue(context, lane).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setCards(outcome.value);
        setFailure(undefined);
      } else {
        setCards(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, lane, reloadKey]);

  const refresh = (
    <Button
      icon={<ReloadOutlined />}
      aria-label="刷新风险队列"
      onClick={() => {
        setCards(undefined);
        setFailure(undefined);
        setReloadKey((key) => key + 1);
      }}
    >
      {actions.refresh}
    </Button>
  );

  const frame = (state: string, body: React.ReactNode): React.JSX.Element => (
    <section aria-label={availabilityText.queueTitle} data-state={state}>
      <SectionCard title={availabilityText.queueTitle} extra={refresh}>
        {body}
      </SectionCard>
    </section>
  );

  if (failure !== undefined) {
    // A session that has ended is one condition about the whole session rather
    // than about this panel. The session surface reports it once; every panel
    // repeating the same sentence would tell an operator nothing new three
    // times over and bury the one message that is about this panel.
    if (failure.kind === 'unauthenticated') {
      return (
        <section aria-label={availabilityText.queueTitle} data-state="signed-out">
          <SectionCard title={availabilityText.queueTitle} />
        </section>
      );
    }
    return frame('failed', <FailureAlert failure={failure} />);
  }
  if (cards === undefined) {
    return frame('loading', <LoadingState />);
  }
  if (cards.length === 0) {
    return frame('empty', <EmptyState description={availabilityText.queueEmpty} />);
  }

  return frame(
    'loaded',
    <ol
      data-testid="availability-queue"
      style={{
        listStyle: 'none',
        margin: 0,
        padding: 0,
        display: 'flex',
        flexDirection: 'column',
        gap: 16,
      }}
    >
      {cards.map((card) => (
        <VariantCard key={card.id} card={card} {...(onSelect ? { onSelect } : {})} />
      ))}
    </ol>,
  );
}

/**
 * One grouped Internal Variant card.
 *
 * The parent names the child that produced its lane rather than leaving the
 * reader to infer it. Two children can share the parent's lane, and an operator
 * who opens the wrong one has spent their attention for nothing.
 */
function VariantCard({
  card,
  onSelect,
}: {
  readonly card: AvailabilityCard;
  readonly onSelect?: (productVariantId: string) => void;
}): React.JSX.Element {
  const trigger = card.children.find((child) => child.id === card.triggeringChildId);
  const title = (
    <Flex vertical gap={2} style={{ padding: '8px 0' }}>
      <Button
        type="link"
        style={{ padding: 0, height: 'auto', fontWeight: 600, whiteSpace: 'normal' }}
        onClick={() => onSelect?.(card.productVariantId)}
        disabled={onSelect === undefined}
      >
        <span lang="ru">{card.displayName}</span>
      </Button>
      <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid="card-sku">
        SKU {card.skuCode}
      </Typography.Text>
    </Flex>
  );
  const extra = (
    <Space size={4} wrap data-testid="card-lane" data-lane={card.lane}>
      <CodeTag labels={LANE_LABELS} code={card.lane} colors={LANE_COLORS} />
      {trigger === undefined ? null : (
        <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid="card-trigger">
          由{childLabel(trigger.childKind, trigger.platformCode, trigger.fulfillmentModeCode)}触发
        </Typography.Text>
      )}
    </Space>
  );
  return (
    <li data-testid="availability-card" data-lane={card.lane}>
      <Card size="small" title={title} extra={extra}>
        <Space orientation="vertical" size="small" style={{ width: '100%' }}>
          <Row gutter={[12, 12]}>
            {card.children.map((child) => (
              <Col key={child.id} xs={24} xl={12}>
                <ChildRisk child={child} />
              </Col>
            ))}
          </Row>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            计算时间 <DateTime value={card.calculatedAt} relative />
          </Typography.Text>
          <TechnicalDetails>
            <Descriptions
              size="small"
              column={1}
              items={[
                {
                  key: 'policy',
                  label: '策略集摘要',
                  children: (
                    <Typography.Text
                      copyable={{ text: card.policyVersionDigest }}
                      code
                      data-testid="card-policy-version"
                    >
                      {card.policyVersionDigest.slice(0, 12)}
                    </Typography.Text>
                  ),
                },
                {
                  key: 'variant',
                  label: '商品变体 ID',
                  children: (
                    <Typography.Text copyable code>
                      {card.productVariantId}
                    </Typography.Text>
                  ),
                },
                {
                  key: 'card',
                  label: '卡片 ID',
                  children: (
                    <Typography.Text copyable code>
                      {card.id}
                    </Typography.Text>
                  ),
                },
                {
                  key: 'rank',
                  label: '排序分值',
                  children: formatDecimal(card.rankScore),
                },
                { key: 'asOf', label: '数据截至', children: <DateTime value={card.asOf} /> },
              ]}
            />
          </TechnicalDetails>
        </Space>
      </Card>
    </li>
  );
}

/** A decimal count of days, or the given absence text. */
function days(value: string | null, absent: string): string {
  return value === null ? absent : `${formatDecimal(value, { maxFractionDigits: 2 })} 天`;
}

/**
 * One independently governed child risk.
 *
 * The evidence tone is a separate attribute from the lane so that a provisional
 * critical and a confirmed critical cannot be styled identically by accident.
 * The conservative proof is shown in full when there is one: an operator asked
 * to act on a lower-bound argument is entitled to read the argument.
 */
function ChildRisk({ child }: { readonly child: AvailabilityChild }): React.JSX.Element {
  const evidence = presentEvidence(child.evidenceState);

  const figures: DescriptionsProps['items'] = [
    {
      key: 'available',
      label: '可用库存',
      children: (
        <span data-testid="child-available">
          {child.availableUnits === null ? '未上报' : `${String(child.availableUnits)} 件`}
        </span>
      ),
    },
    {
      key: 'demand',
      label: '观测日需求',
      children: (
        <span data-testid="child-demand">
          {child.dailyDemandRate === null
            ? '无法观测'
            : `${formatDecimal(child.dailyDemandRate, { maxFractionDigits: 2 })} 件/天`}
        </span>
      ),
    },
    {
      key: 'cover',
      label: '可售天数',
      children: <span data-testid="child-cover">{days(child.daysOfCover, '未预测')}</span>,
    },
    {
      key: 'horizon',
      label: '覆盖周期',
      children: (
        <span data-testid="child-horizon">
          {child.coverageHorizonDays === null
            ? '无适用策略'
            : `${String(child.coverageHorizonDays)} 天`}
        </span>
      ),
    },
    {
      key: 'stockout',
      label: '预计断货',
      children: <DateTime value={child.projectedStockoutAt} />,
    },
    {
      key: 'profit',
      label: '利润分层',
      children: (
        <span data-testid="child-profit">
          <CodeTag
            labels={PROFIT_LANE_LABELS}
            code={child.profitLane}
            colors={PROFIT_LANE_COLORS}
          />
        </span>
      ),
    },
    {
      key: 'profitAtRisk',
      label: '受威胁利润',
      children: <Money value={child.profitAtRiskAmount} currency={child.profitAtRiskCurrency} />,
    },
    {
      key: 'confidence',
      label: '置信度',
      children: (
        <CodeTag
          labels={RISK_CONFIDENCE_LABELS}
          code={child.confidenceState}
          colors={RISK_CONFIDENCE_COLORS}
        />
      ),
    },
  ];

  const details: NonNullable<CollapseProps['items']> = [];
  if (child.conservativeProofTerms.length > 0) {
    details.push({
      key: 'proof',
      label: '为何已可认定风险',
      forceRender: true,
      children: (
        <div data-testid="child-proof">
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            以下为系统计算原文
          </Typography.Text>
          <ul style={{ margin: '4px 0 0', paddingInlineStart: 20 }}>
            {child.conservativeProofTerms.map((term) => (
              <li key={term} lang="en">
                {term}
              </li>
            ))}
          </ul>
        </div>
      ),
    });
  }
  if (child.rankFactors.length > 0) {
    details.push({
      key: 'factors',
      label: '排序依据',
      forceRender: true,
      children: (
        <div data-testid="child-factors">
          <Table<AvailabilityRankFactor>
            size="small"
            pagination={false}
            rowKey="factorCode"
            dataSource={[...child.rankFactors]}
            columns={RANK_FACTOR_COLUMNS}
            scroll={{ x: 'max-content' }}
          />
        </div>
      ),
    });
  }
  if (child.demandWindows.length > 0) {
    details.push({
      key: 'windows',
      label: '需求观测窗口',
      forceRender: true,
      children: (
        <div data-testid="child-windows">
          <Table<AvailabilityDemandWindow>
            size="small"
            pagination={false}
            rowKey="windowCode"
            dataSource={[...child.demandWindows]}
            columns={DEMAND_WINDOW_COLUMNS}
            onRow={(window) =>
              ({ 'data-eligibility': window.eligibility }) as React.HTMLAttributes<HTMLElement>
            }
            scroll={{ x: 'max-content' }}
          />
        </div>
      ),
    });
  }
  details.push({
    key: 'reason',
    label: '需求选择依据',
    forceRender: true,
    children: (
      <Flex vertical gap={2}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          系统计算原文
        </Typography.Text>
        <Typography.Text data-testid="child-demand-reason" lang="en">
          {child.demandSelectionReason}
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          计算时间 <DateTime value={child.calculatedAt} />
        </Typography.Text>
      </Flex>
    ),
  });

  const heading = (
    <Typography.Text strong>
      {childLabel(child.childKind, child.platformCode, child.fulfillmentModeCode)}
    </Typography.Text>
  );
  const tags = (
    <Space size={4} data-testid="child-lane">
      <CodeTag labels={LANE_LABELS} code={child.lane} colors={LANE_COLORS} />
      <Tooltip title={evidence.explanation}>
        <span data-testid="child-evidence" data-evidence-tone={evidence.tone}>
          <CodeTag labels={EVIDENCE_LABELS} code={child.evidenceState} colors={EVIDENCE_COLORS} />
        </span>
      </Tooltip>
    </Space>
  );

  return (
    <div
      data-testid="availability-child"
      data-child-kind={child.childKind}
      data-lane={child.lane}
      data-evidence-tone={evidence.tone}
      data-established-fact={String(evidence.establishedFact)}
    >
      <Card type="inner" size="small" title={heading} extra={tags}>
        <Space orientation="vertical" size="small" style={{ width: '100%' }}>
          <Typography.Text type="secondary" data-testid="child-evidence-explanation">
            {evidence.explanation}
          </Typography.Text>
          {laneIsSafe(child.lane) ? null : (
            <Typography.Text strong data-testid="child-cause">
              {causeLabel(child.causeCode)}
            </Typography.Text>
          )}
          <Descriptions bordered size="small" column={{ xs: 1, md: 2 }} items={figures} />
          {child.blockerCodes.length === 0 ? null : (
            <Flex wrap gap={4} align="center" data-testid="child-blockers">
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                阻断因素
              </Typography.Text>
              {child.blockerCodes.map((code) => (
                <CodeTag
                  key={code}
                  labels={BLOCKER_LABELS}
                  code={code}
                  colors={{ [code]: 'error' }}
                />
              ))}
            </Flex>
          )}
          <Collapse size="small" items={details} />
        </Space>
      </Card>
    </div>
  );
}

const RANK_FACTOR_COLUMNS: TableColumnsType<AvailabilityRankFactor> = [
  {
    title: '因素',
    dataIndex: 'factorCode',
    render: (code: string) => <CodeTag labels={RANK_FACTOR_LABELS} code={code} />,
  },
  {
    title: '取值',
    dataIndex: 'value',
    align: 'right',
    render: (value: string | null) => formatDecimal(value, { maxFractionDigits: 4 }),
  },
  {
    title: '权重',
    dataIndex: 'weight',
    align: 'right',
    render: (value: string | null) => formatDecimal(value, { maxFractionDigits: 4 }),
  },
  {
    title: '贡献',
    dataIndex: 'contribution',
    align: 'right',
    render: (value: string | null) => formatDecimal(value, { maxFractionDigits: 4 }),
  },
  {
    title: '系统说明',
    dataIndex: 'displayNote',
    render: (note: string) => (
      <Typography.Text type="secondary" style={{ fontSize: 12 }} lang="en">
        {note}
      </Typography.Text>
    ),
  },
];

const DEMAND_WINDOW_COLUMNS: TableColumnsType<AvailabilityDemandWindow> = [
  {
    title: '窗口',
    dataIndex: 'windowCode',
    render: (code: string) => <CodeTag labels={DEMAND_WINDOW_LABELS} code={code} />,
  },
  {
    title: '完成件数',
    dataIndex: 'completedUnits',
    align: 'right',
    render: (units: number | null) => (units === null ? '未观测' : `${String(units)} 件`),
  },
  {
    title: '日均',
    dataIndex: 'dailyRate',
    align: 'right',
    render: (rate: string | null) => formatDecimal(rate, { maxFractionDigits: 2 }),
  },
  {
    title: '观测天数',
    dataIndex: 'observedDays',
    align: 'right',
    render: (value: string | null) => formatDecimal(value, { maxFractionDigits: 2 }),
  },
  {
    title: '覆盖率',
    dataIndex: 'coverageRatio',
    align: 'right',
    render: (ratio: string | null) => formatPercent(ratio),
  },
  {
    title: '可用性',
    dataIndex: 'eligibility',
    render: (code: string) => (
      <CodeTag labels={WINDOW_ELIGIBILITY_LABELS} code={code} colors={WINDOW_ELIGIBILITY_COLORS} />
    ),
  },
  {
    title: '删失',
    key: 'censoring',
    render: (_: unknown, window: AvailabilityDemandWindow) =>
      window.censored ? (
        <CodeTag
          labels={CENSORING_REASON_LABELS}
          code={window.censoringReason ?? 'UNKNOWN'}
          colors={{ [window.censoringReason ?? 'UNKNOWN']: 'warning' }}
        />
      ) : (
        <Typography.Text type="secondary">否</Typography.Text>
      ),
  },
  {
    title: '期间',
    key: 'period',
    render: (_: unknown, window: AvailabilityDemandWindow) => (
      <Flex vertical>
        <DateTime value={window.periodStart} />
        <DateTime value={window.periodEnd} />
      </Flex>
    ),
  },
];
