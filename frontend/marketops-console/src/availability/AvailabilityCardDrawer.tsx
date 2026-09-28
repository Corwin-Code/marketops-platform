import { ReloadOutlined } from '@ant-design/icons';
import { Button, Descriptions, Flex, Space, Table, Tooltip, Typography } from 'antd';
import type { DescriptionsProps, TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type {
  AvailabilityCard,
  AvailabilityChild,
  AvailabilityDemandWindow,
  AvailabilityRankFactor,
} from '../api/availability';
import { fetchAvailabilityCard } from '../api/availability';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { formatDecimal, formatPercent } from '../format';
import { codeLabel } from '../i18n';
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
} from '../i18n/zh/availability';
import { cardDetailText as text, riskQueueText } from '../i18n/zh/availabilityRisks';
import {
  CodeTag,
  DateTime,
  DetailDrawer,
  EmptyState,
  Money,
  SectionCollapse,
  TechnicalDetails,
  VariantName,
} from '../ui';
import type { SectionCollapseItem, SectionFlag } from '../ui';
import { causeLabel, childLabel, laneIsSafe, presentEvidence } from './riskPresentation';
import {
  EVIDENCE_COLORS,
  LANE_COLORS,
  PROFIT_LANE_COLORS,
  RISK_CONFIDENCE_COLORS,
  WINDOW_ELIGIBILITY_COLORS,
} from './tagColors';

/** What the card drawer needs. */
export interface AvailabilityCardDrawerProps {
  readonly context: ConsoleRequest;
  /** The variant whose card is shown, or nothing when the drawer is closed. */
  readonly productVariantId: string | undefined;
  readonly onClose: () => void;
  /** Go to the cases of this variant. */
  readonly onOpenCases: (productVariantId: string) => void;
}

type Loaded =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly card: AvailabilityCard }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/**
 * One grouped card beside the queue, loaded by its variant from the address.
 *
 * The key figures of every child are visible at once; the proof, the rank
 * factors, the demand windows and the selection reason fold away, and each
 * folded section keeps its warnings — censored windows, thin samples, blockers,
 * a lower-bound argument — in its header so folding never hides them.
 */
export function AvailabilityCardDrawer(props: AvailabilityCardDrawerProps): React.JSX.Element {
  if (props.productVariantId === undefined) {
    return <DetailDrawer open={false} onClose={props.onClose} title={text.title} />;
  }
  return (
    <CardDrawerBody
      key={props.productVariantId}
      {...props}
      productVariantId={props.productVariantId}
    />
  );
}

function CardDrawerBody({
  context,
  productVariantId,
  onClose,
  onOpenCases,
}: AvailabilityCardDrawerProps & { readonly productVariantId: string }): React.JSX.Element {
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let live = true;
    void fetchAvailabilityCard(context, productVariantId).then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { kind: 'loaded', card: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, productVariantId, attempt]);

  const reload = (): void => {
    setLoaded({ kind: 'loading' });
    setAttempt((value) => value + 1);
  };

  const card = loaded.kind === 'loaded' ? loaded.card : undefined;
  const trigger = card?.children.find((child) => child.id === card.triggeringChildId);

  return (
    <DetailDrawer
      open
      onClose={onClose}
      size="large"
      title={
        card === undefined ? (
          text.title
        ) : (
          <VariantName
            identity={{ displayName: card.displayName, skuCode: card.skuCode }}
            productVariantId={card.productVariantId}
          />
        )
      }
      extra={
        <Button icon={<ReloadOutlined />} onClick={reload}>
          {text.refresh}
        </Button>
      }
      footer={
        <Flex justify="flex-end">
          <Tooltip title={text.openCasesHelp}>
            <Button
              type="primary"
              onClick={() => {
                onOpenCases(productVariantId);
              }}
            >
              {text.openCases}
            </Button>
          </Tooltip>
        </Flex>
      }
      loading={loaded.kind === 'loading'}
      failure={loaded.kind === 'failed' ? loaded.failure : undefined}
      onRetry={reload}
    >
      {card === undefined ? null : (
        <Flex vertical gap={16} data-card={card.id} data-lane={card.lane}>
          <Flex gap={8} wrap align="center">
            <CodeTag labels={LANE_LABELS} code={card.lane} colors={LANE_COLORS} />
            {trigger === undefined ? null : (
              <Typography.Text type="secondary">
                {riskQueueText.triggeredBy(
                  childLabel(trigger.childKind, trigger.platformCode, trigger.fulfillmentModeCode),
                )}
              </Typography.Text>
            )}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.calculatedAt} <DateTime value={card.calculatedAt} relative />
            </Typography.Text>
          </Flex>
          <Flex vertical gap={4}>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {text.childrenTitle}
            </Typography.Title>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.childrenHint}
            </Typography.Text>
          </Flex>
          {card.children.length === 0 ? (
            <EmptyState description={text.noChildren} />
          ) : (
            card.children.map((child) => (
              <ChildRisk key={child.id} child={child} triggering={child.id === trigger?.id} />
            ))
          )}
          <TechnicalDetails>
            <Descriptions
              size="small"
              column={1}
              items={[
                {
                  key: 'policy',
                  label: text.policyDigest,
                  children: (
                    <Typography.Text copyable={{ text: card.policyVersionDigest }} code>
                      {card.policyVersionDigest.slice(0, 12)}
                    </Typography.Text>
                  ),
                },
                {
                  key: 'variant',
                  label: text.variantId,
                  children: (
                    <Typography.Text copyable code>
                      {card.productVariantId}
                    </Typography.Text>
                  ),
                },
                {
                  key: 'card',
                  label: text.cardId,
                  children: (
                    <Typography.Text copyable code>
                      {card.id}
                    </Typography.Text>
                  ),
                },
                { key: 'rank', label: text.rankScore, children: formatDecimal(card.rankScore) },
                { key: 'asOf', label: text.asOf, children: <DateTime value={card.asOf} /> },
              ]}
            />
          </TechnicalDetails>
        </Flex>
      )}
    </DetailDrawer>
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
 * The conservative proof is there in full when there is one: an operator asked
 * to act on a lower-bound argument is entitled to read the argument.
 */
function ChildRisk({
  child,
  triggering,
}: {
  readonly child: AvailabilityChild;
  readonly triggering: boolean;
}): React.JSX.Element {
  const evidence = presentEvidence(child.evidenceState);

  const figures: DescriptionsProps['items'] = [
    {
      key: 'available',
      label: text.available,
      children:
        child.availableUnits === null
          ? text.notReported
          : text.availableUnits(child.availableUnits),
    },
    {
      key: 'demand',
      label: text.demand,
      children:
        child.dailyDemandRate === null
          ? text.unobservable
          : text.demandRate(formatDecimal(child.dailyDemandRate, { maxFractionDigits: 2 })),
    },
    { key: 'cover', label: text.cover, children: days(child.daysOfCover, text.notProjected) },
    {
      key: 'horizon',
      label: text.horizon,
      children:
        child.coverageHorizonDays === null
          ? text.noPolicy
          : text.horizonDays(child.coverageHorizonDays),
    },
    {
      key: 'stockout',
      label: text.stockout,
      children: <DateTime value={child.projectedStockoutAt} />,
    },
    {
      key: 'profit',
      label: text.profitLane,
      children: (
        <CodeTag labels={PROFIT_LANE_LABELS} code={child.profitLane} colors={PROFIT_LANE_COLORS} />
      ),
    },
    {
      key: 'profitAtRisk',
      label: text.profitAtRisk,
      children: <Money value={child.profitAtRiskAmount} currency={child.profitAtRiskCurrency} />,
    },
    {
      key: 'confidence',
      label: text.confidence,
      children: (
        <CodeTag
          labels={RISK_CONFIDENCE_LABELS}
          code={child.confidenceState}
          colors={RISK_CONFIDENCE_COLORS}
        />
      ),
    },
  ];

  const censored = child.demandWindows.filter((window) => window.censored).length;
  const lowSample = child.demandWindows.filter((window) => !window.sampleSufficient).length;
  const blockerFlags: SectionFlag[] = child.blockerCodes.map((code) => ({
    key: `blocker:${code}`,
    label: codeLabel(BLOCKER_LABELS, code),
    color: 'error',
  }));
  const evidenceFlags: SectionFlag[] = evidence.establishedFact
    ? []
    : [
        {
          key: 'evidence',
          label: Object.hasOwn(EVIDENCE_LABELS, child.evidenceState)
            ? codeLabel(EVIDENCE_LABELS, child.evidenceState)
            : text.blockedFlag,
          color: evidence.tone === 'blocked' ? 'error' : 'warning',
        },
      ];

  const sections: SectionCollapseItem[] = [];
  if (child.conservativeProofTerms.length > 0) {
    sections.push({
      key: 'proof',
      title: text.proof,
      summary: text.items(child.conservativeProofTerms.length),
      flags: [{ key: 'provisional', label: text.provisionalFlag, color: 'warning' }],
      children: (
        <div data-testid="child-proof">
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.proofHint}
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
    sections.push({
      key: 'factors',
      title: text.factors,
      summary: text.items(child.rankFactors.length),
      children: (
        <Table<AvailabilityRankFactor>
          size="small"
          pagination={false}
          rowKey="factorCode"
          dataSource={[...child.rankFactors]}
          columns={RANK_FACTOR_COLUMNS}
          scroll={{ x: 'max-content' }}
        />
      ),
    });
  }
  if (child.demandWindows.length > 0) {
    sections.push({
      key: 'windows',
      title: text.windows,
      summary: text.items(child.demandWindows.length),
      flags: [
        ...(censored > 0
          ? [{ key: 'censored', label: text.censoredFlag(censored), color: 'warning' as const }]
          : []),
        ...(lowSample > 0
          ? [{ key: 'low-sample', label: text.lowSampleFlag(lowSample), color: 'warning' as const }]
          : []),
      ],
      children: (
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
      ),
    });
  }
  sections.push({
    key: 'reason',
    title: text.reason,
    flags: [...evidenceFlags, ...blockerFlags],
    children: (
      <Flex vertical gap={2}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.reasonHint}
        </Typography.Text>
        <Typography.Text lang="en">{child.demandSelectionReason}</Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.calculatedAt} <DateTime value={child.calculatedAt} />
        </Typography.Text>
      </Flex>
    ),
  });

  return (
    <section
      aria-label={childLabel(child.childKind, child.platformCode, child.fulfillmentModeCode)}
      data-child-kind={child.childKind}
      data-lane={child.lane}
      data-evidence-tone={evidence.tone}
      data-established-fact={String(evidence.establishedFact)}
      data-triggering={String(triggering)}
      style={{
        border: '1px solid var(--ant-color-border-secondary, #f0f0f0)',
        borderRadius: 8,
        padding: 12,
      }}
    >
      <Flex vertical gap={8}>
        <Flex justify="space-between" align="center" gap={8} wrap>
          <Typography.Text strong>
            {childLabel(child.childKind, child.platformCode, child.fulfillmentModeCode)}
          </Typography.Text>
          <Space size={4}>
            <CodeTag labels={LANE_LABELS} code={child.lane} colors={LANE_COLORS} />
            <Tooltip title={evidence.explanation}>
              <span data-evidence-tone={evidence.tone}>
                <CodeTag
                  labels={EVIDENCE_LABELS}
                  code={child.evidenceState}
                  colors={EVIDENCE_COLORS}
                />
              </span>
            </Tooltip>
          </Space>
        </Flex>
        <Typography.Text type="secondary">{evidence.explanation}</Typography.Text>
        {laneIsSafe(child.lane) ? null : (
          <Typography.Text strong>{causeLabel(child.causeCode)}</Typography.Text>
        )}
        <Descriptions bordered size="small" column={{ xs: 1, md: 2 }} items={figures} />
        {child.blockerCodes.length === 0 ? null : (
          <Flex wrap gap={4} align="center">
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.blockers}
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
        <SectionCollapse size="small" items={sections} />
      </Flex>
    </section>
  );
}

const RANK_FACTOR_COLUMNS: TableColumnsType<AvailabilityRankFactor> = [
  {
    title: text.factor,
    dataIndex: 'factorCode',
    render: (code: string) => <CodeTag labels={RANK_FACTOR_LABELS} code={code} />,
  },
  {
    title: text.value,
    dataIndex: 'value',
    align: 'right',
    render: (value: string | null) => formatDecimal(value, { maxFractionDigits: 4 }),
  },
  {
    title: text.weight,
    dataIndex: 'weight',
    align: 'right',
    render: (value: string | null) => formatDecimal(value, { maxFractionDigits: 4 }),
  },
  {
    title: text.contribution,
    dataIndex: 'contribution',
    align: 'right',
    render: (value: string | null) => formatDecimal(value, { maxFractionDigits: 4 }),
  },
  {
    title: text.note,
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
    title: text.window,
    dataIndex: 'windowCode',
    render: (code: string) => <CodeTag labels={DEMAND_WINDOW_LABELS} code={code} />,
  },
  {
    title: text.completedUnits,
    dataIndex: 'completedUnits',
    align: 'right',
    render: (units: number | null) => (units === null ? text.notObserved : text.unitsValue(units)),
  },
  {
    title: text.dailyRate,
    dataIndex: 'dailyRate',
    align: 'right',
    render: (rate: string | null) => formatDecimal(rate, { maxFractionDigits: 2 }),
  },
  {
    title: text.observedDays,
    dataIndex: 'observedDays',
    align: 'right',
    render: (value: string | null) => formatDecimal(value, { maxFractionDigits: 2 }),
  },
  {
    title: text.coverage,
    dataIndex: 'coverageRatio',
    align: 'right',
    render: (ratio: string | null) => formatPercent(ratio),
  },
  {
    title: text.eligibility,
    dataIndex: 'eligibility',
    render: (code: string) => (
      <CodeTag labels={WINDOW_ELIGIBILITY_LABELS} code={code} colors={WINDOW_ELIGIBILITY_COLORS} />
    ),
  },
  {
    title: text.censoring,
    key: 'censoring',
    render: (_: unknown, window: AvailabilityDemandWindow) =>
      window.censored ? (
        <CodeTag
          labels={CENSORING_REASON_LABELS}
          code={window.censoringReason ?? 'UNKNOWN'}
          colors={{ [window.censoringReason ?? 'UNKNOWN']: 'warning' }}
        />
      ) : (
        <Typography.Text type="secondary">{text.notCensored}</Typography.Text>
      ),
  },
  {
    title: text.period,
    key: 'period',
    render: (_: unknown, window: AvailabilityDemandWindow) => (
      <Flex vertical>
        <DateTime value={window.periodStart} />
        <DateTime value={window.periodEnd} />
      </Flex>
    ),
  },
];
