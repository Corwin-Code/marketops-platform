import { ReloadOutlined } from '@ant-design/icons';
import { Button, Card, Col, Descriptions, Flex, Row, Space, Tag, Typography } from 'antd';
import type { ConsoleFailure } from '../api/console';
import type { DiagnosisProduct } from '../api/storeDiagnosis';
import type { ListingFinding, ListingFindings, StoreFindings } from '../api/storeFindings';
import { formatDecimal, formatMoney, formatPercent, shiftDecimal } from '../format';
import { RULE_LABELS } from '../i18n/zh/pricing';
import {
  CONCLUSION_TEXT,
  FINDING_DETAIL_LABELS,
  storeDiagnosisText as text,
} from '../i18n/zh/storeDiagnosis';
import { DateTime, EmptyState, FailureAlert, InfoTip, Money, SectionCard } from '../ui';
import type { TagColor } from '../ui';

/** The conclusion the page counts from stock facts rather than from a rule. */
export const WITHOUT_STOCK = 'WITHOUT_STOCK';

/** The order conclusions are shown in: what stops a sale first, then why buyers do not buy. */
export const CONCLUSION_ORDER: readonly string[] = [
  WITHOUT_STOCK,
  'LISTING_NOT_SELLABLE',
  'BUYER_PRICE_JUMP',
  'DEMAND_NOT_CONVERTING',
  'PRICE_GAP_STRUCTURAL',
  'PRICE_GAP_PARTIAL',
  'PRICE_GAP_REDUCIBLE',
  'PRICE_HEADROOM',
  'LOW_SEARCH_EXPOSURE',
  'CONTENT_BELOW_TARGET',
  'PROMOTION_OPPORTUNITY',
];

/**
 * Findings the page does not show per product: a block on the realized-profit
 * rules, which the section note explains once.
 */
const HIDDEN_RULES: ReadonlySet<string> = new Set(['DATA_BLOCKED']);

/** The findings worth showing on a product. */
function shownFindings(findings: readonly ListingFinding[]): readonly ListingFinding[] {
  return findings.filter(
    (finding) =>
      !HIDDEN_RULES.has(finding.ruleCode) &&
      // No stock at all is the WITHOUT_STOCK conclusion, counted from stock facts.
      !(finding.ruleCode === 'STOCKOUT_RISK' && finding.detail.condition === 'NO_PLATFORM_STOCK'),
  );
}

const SEVERITY_COLORS: Readonly<Record<string, TagColor>> = {
  CRITICAL: 'error',
  WARNING: 'warning',
  INFO: 'processing',
};

/** The newest conclusions, as far as they have loaded. */
export type FindingsLoad =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly findings: StoreFindings }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/** A conclusion's title, falling back on the rule's label and then its code. */
export function conclusionTitle(code: string): string {
  return CONCLUSION_TEXT[code]?.title ?? RULE_LABELS[code] ?? code;
}

/**
 * What is wrong across the store, as conclusions rather than products: each
 * with how many products it covers, what it means and what to do next.
 */
export function ConclusionsSection({
  load,
  withoutStockCount,
  premiumPlus,
  activeRule,
  onSelectRule,
  onRecalculate,
  recalculating,
}: {
  readonly load: FindingsLoad;
  readonly withoutStockCount: number;
  /** Whether the store has Premium Plus, `null` until the rating summary is collected. */
  readonly premiumPlus: boolean | null;
  readonly activeRule: string | undefined;
  readonly onSelectRule: (code: string | undefined) => void;
  readonly onRecalculate: () => void;
  readonly recalculating: boolean;
}): React.JSX.Element {
  const run = load.kind === 'loaded' ? load.findings.run : null;
  const counts = new Map<string, number>();
  if (load.kind === 'loaded') {
    for (const count of load.findings.summary) counts.set(count.ruleCode, count.listings);
  }
  counts.set(WITHOUT_STOCK, withoutStockCount);
  const shown = CONCLUSION_ORDER.filter((code) => (counts.get(code) ?? 0) > 0);

  const extra = (
    <Space size={8} wrap>
      {run !== null && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.conclusionsBasis} <DateTime value={run.periodEnd} /> ·{' '}
          {text.conclusionsCalculatedAt} <DateTime value={run.completedAt ?? run.periodEnd} />
        </Typography.Text>
      )}
      <Button icon={<ReloadOutlined />} loading={recalculating} onClick={onRecalculate}>
        {text.recalculate}
      </Button>
      <InfoTip title={text.recalculateHint} />
    </Space>
  );

  let body: React.JSX.Element;
  if (load.kind === 'loading') {
    body = <Typography.Text type="secondary">…</Typography.Text>;
  } else if (load.kind === 'failed') {
    body = <FailureAlert failure={load.failure} />;
  } else if (run === null) {
    body = <EmptyState description={text.conclusionsNone} />;
  } else if (shown.length === 0) {
    body = <EmptyState description={text.conclusionsEmpty} />;
  } else {
    body = (
      <Row gutter={[12, 12]}>
        {shown.map((code) => {
          const conclusion = CONCLUSION_TEXT[code];
          const active = activeRule === code;
          return (
            <Col key={code} xs={24} md={12} xl={8}>
              <Card
                size="small"
                style={active ? { borderColor: 'var(--ant-color-primary, #1677ff)' } : {}}
                title={
                  <Space size={8}>
                    <span>{conclusionTitle(code)}</span>
                    <Tag>{text.affectedProducts(counts.get(code) ?? 0)}</Tag>
                  </Space>
                }
                extra={
                  <Button
                    type="link"
                    size="small"
                    onClick={() => {
                      onSelectRule(active ? undefined : code);
                    }}
                  >
                    {active ? text.clearRuleFilter : text.showAffected}
                  </Button>
                }
              >
                <Flex vertical gap={4}>
                  <Typography.Text>{conclusion?.meaning}</Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {text.nextStep}
                    {conclusion?.next}
                  </Typography.Text>
                </Flex>
              </Card>
            </Col>
          );
        })}
      </Row>
    );
  }

  return (
    <SectionCard title={text.conclusionsTitle} extra={extra}>
      <Flex vertical gap={12}>
        {body}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.conclusionsNote(premiumPlus)}
        </Typography.Text>
      </Flex>
    </SectionCard>
  );
}

/** The conclusions of one product, as short tags. */
export function FindingTags({
  findings,
  withoutStock,
}: {
  readonly findings: readonly ListingFinding[];
  readonly withoutStock: boolean;
}): React.JSX.Element {
  const tags = [
    ...(withoutStock ? [{ key: WITHOUT_STOCK, severity: 'CRITICAL' }] : []),
    ...shownFindings(findings).map((finding) => ({
      key: finding.ruleCode,
      severity: finding.severity,
    })),
  ];
  if (tags.length === 0) {
    return <Typography.Text type="secondary">{text.noFindings}</Typography.Text>;
  }
  return (
    <Flex gap={4} wrap>
      {tags.map((tag) => (
        <Tag
          key={tag.key}
          color={SEVERITY_COLORS[tag.severity] ?? 'default'}
          style={{ marginInlineEnd: 0 }}
        >
          {conclusionTitle(tag.key)}
        </Tag>
      ))}
    </Flex>
  );
}

/** One compared value, in the unit it was compared in. */
function detailValue(key: string, value: string, currency: string | null): string {
  switch (key) {
    case 'buyerPrice':
    case 'platformCompetitorMinPrice':
    case 'breakEvenPrice':
    case 'targetMarginPrice':
    case 'recentLowBuyerPrice':
      return formatMoney(value, currency);
    case 'premiumOverCompetitor':
    case 'minimumUnitMarginRate':
    case 'promotionMargin':
    case 'projectedUnitMargin':
    case 'priceRoom':
    case 'riseOverRecentLow':
    case 'buyerPriceJumpMinimumRate':
      return formatPercent(value);
    case 'contentRating':
    case 'contentRatingFloor':
      return formatDecimal(shiftDecimal(value, 2), { maxFractionDigits: 1 });
    default:
      return formatDecimal(value, { maxFractionDigits: 2 });
  }
}

/** The metric value as decimal text when the run had one. */
function metricValue(listing: ListingFindings | undefined, code: string): string | null {
  const metric = listing?.metrics[code];
  return metric?.valueState === 'AVAILABLE' ? metric.value : null;
}

/**
 * Everything the run concluded about one product: its conclusions with the
 * values they compared, and its estimated unit economics.
 */
export function ListingConclusions({
  listing,
  product,
  withoutStock,
}: {
  readonly listing: ListingFindings | undefined;
  readonly product: DiagnosisProduct;
  readonly withoutStock: boolean;
}): React.JSX.Element {
  const findings = shownFindings(listing?.findings ?? []);
  const currency =
    listing?.metrics.OBSERVED_SELLING_PRICE?.currencyCode ?? product.price?.currencyCode ?? null;
  const profit = metricValue(listing, 'PROJECTED_UNIT_PROFIT');
  const tariffs = product.price?.tariffs;
  const margin = metricValue(listing, 'PROJECTED_UNIT_MARGIN');
  const minimumRate = findings.find((finding) => finding.detail.minimumUnitMarginRate !== undefined)
    ?.detail.minimumUnitMarginRate;
  return (
    <Flex vertical gap={16}>
      <Flex vertical gap={8}>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {text.sectionFindings}
        </Typography.Title>
        {findings.length === 0 && !withoutStock ? (
          <Typography.Text type="secondary">{text.noFindings}</Typography.Text>
        ) : (
          <>
            {withoutStock && (
              <Card size="small" title={conclusionTitle(WITHOUT_STOCK)}>
                <Typography.Text>{CONCLUSION_TEXT[WITHOUT_STOCK]?.meaning}</Typography.Text>
              </Card>
            )}
            {findings.map((finding) => (
              <Card
                key={finding.findingId}
                size="small"
                title={
                  <Space size={8}>
                    <Tag color={SEVERITY_COLORS[finding.severity] ?? 'default'}>
                      {conclusionTitle(finding.ruleCode)}
                    </Tag>
                  </Space>
                }
              >
                <Flex vertical gap={6}>
                  <Typography.Text>{CONCLUSION_TEXT[finding.ruleCode]?.meaning}</Typography.Text>
                  <Descriptions size="small" column={1}>
                    {Object.entries(finding.detail)
                      .filter(([key]) => FINDING_DETAIL_LABELS[key] !== undefined)
                      .map(([key, value]) => (
                        <Descriptions.Item key={key} label={FINDING_DETAIL_LABELS[key]}>
                          {detailValue(key, value, finding.detail.currencyCode ?? currency)}
                        </Descriptions.Item>
                      ))}
                  </Descriptions>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {text.nextStep}
                    {CONCLUSION_TEXT[finding.ruleCode]?.next}
                  </Typography.Text>
                </Flex>
              </Card>
            ))}
          </>
        )}
      </Flex>

      <Descriptions size="small" column={1} bordered title={text.sectionEconomics}>
        {profit === null ? (
          <Descriptions.Item label={text.economicsProfit}>
            {text.economicsUnavailable}
          </Descriptions.Item>
        ) : (
          <>
            <Descriptions.Item label={text.economicsBuyerPrice}>
              <Money value={metricValue(listing, 'OBSERVED_SELLING_PRICE')} currency={currency} />
            </Descriptions.Item>
            <Descriptions.Item label={text.economicsUnitCost}>
              <Money value={metricValue(listing, 'UNIT_COST')} currency={currency} />
            </Descriptions.Item>
            <Descriptions.Item label={text.economicsProfit}>
              <Money value={profit} currency={currency} strong />
            </Descriptions.Item>
            <Descriptions.Item label={text.economicsMargin}>
              {formatPercent(margin)}
            </Descriptions.Item>
            <Descriptions.Item label={text.economicsBreakEven}>
              <Money
                value={metricValue(listing, 'PROJECTED_BREAK_EVEN_PRICE')}
                currency={currency}
              />
            </Descriptions.Item>
            <Descriptions.Item
              label={
                <Space size={4}>
                  {text.economicsTarget}
                  {minimumRate !== undefined && (
                    <InfoTip title={text.targetMarginHint(formatPercent(minimumRate))} />
                  )}
                </Space>
              }
            >
              <Money value={metricValue(listing, 'TARGET_MARGIN_PRICE')} currency={currency} />
            </Descriptions.Item>
            <Descriptions.Item label={text.economicsCompetitor}>
              <Money
                value={metricValue(listing, 'PLATFORM_COMPETITOR_MIN_PRICE')}
                currency={currency}
              />
            </Descriptions.Item>
            {tariffs !== undefined && (
              <Descriptions.Item label={text.economicsTerms}>
                {text.economicsTermsValue(
                  formatDecimal(tariffs.salesCommissionPercentFbs, { maxFractionDigits: 2 }),
                  formatDecimal(tariffs.fbsLogisticsMax, { maxFractionDigits: 2 }),
                  formatDecimal(tariffs.acquiringMax, { maxFractionDigits: 2 }),
                  formatDecimal(shiftDecimal(tariffs.vatRate, 2), { maxFractionDigits: 2 }),
                )}
              </Descriptions.Item>
            )}
          </>
        )}
      </Descriptions>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {text.economicsNote}
      </Typography.Text>
    </Flex>
  );
}
