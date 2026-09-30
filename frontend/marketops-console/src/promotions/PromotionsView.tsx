import { ReloadOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import {
  App,
  Button,
  Descriptions,
  Flex,
  Form,
  Input,
  Radio,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  Promotion,
  PromotionDecision,
  PromotionItem,
  StorePromotions,
} from '../api/promotions';
import {
  fetchPromotionDecisions,
  fetchStorePromotions,
  recordPromotionDecision,
} from '../api/promotions';
import { formatPercent } from '../format';
import { codeLabel } from '../i18n';
import {
  ADD_MODE_LABELS,
  DECISION_LABELS,
  DECISION_OPTION_LABELS,
  DISCOUNT_KIND_LABELS,
  MEMBERSHIP_LABELS,
  MISSING_LABELS,
  PROMOTION_KIND_LABELS,
  promotionsText as text,
  VERDICT_LABELS,
} from '../i18n/zh/promotions';
import { DecimalField } from '../listing/ListingActionFields';
import { PromotionAiReview } from './PromotionAiReview';
import {
  ActionModal,
  DateTime,
  EmptyState,
  FailureAlert,
  InfoTip,
  LoadingState,
  Money,
  SectionCard,
} from '../ui';
import type { TagColor } from '../ui';

/** What the page needs in order to load itself. */
export interface PromotionsViewProps {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}

type Loaded =
  | { readonly kind: 'loading' }
  | {
      readonly kind: 'loaded';
      readonly data: StorePromotions;
      /** `undefined` when the decisions could not be read: the economics still show. */
      readonly decisions: ReadonlyMap<string, PromotionDecision> | undefined;
    }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

interface DecisionValues {
  readonly decision?: string;
  readonly actionPrice?: string;
  readonly note?: string;
}

const DECISION_COLORS: Readonly<Record<string, TagColor>> = {
  JOINED: 'processing',
  SKIPPED: 'default',
  LEFT: 'warning',
};

/** A candidate can be joined or skipped; a participant kept or left. */
function decisionChoices(item: PromotionItem): readonly string[] {
  return item.membership === 'PARTICIPANT' ? ['JOINED', 'LEFT'] : ['JOINED', 'SKIPPED'];
}

function decisionKey(promotionId: string, listingVariantId: string): string {
  return `${promotionId}:${listingVariantId}`;
}

/** At most four decimals and above zero, as the record requires. */
function isActionPrice(value: string): boolean {
  return /^\d+(\.\d{1,4})?$/.test(value) && Number(value) > 0;
}

const VERDICT_COLORS: Readonly<Record<string, TagColor>> = {
  JOIN_KEEPS_FLOOR: 'success',
  JOIN_BELOW_FLOOR: 'warning',
  JOIN_LOSES: 'error',
  UNKNOWN: 'default',
};

/** The order a promotion's products are listed in: the ones worth joining first. */
const VERDICT_ORDER = ['JOIN_KEEPS_FLOOR', 'JOIN_BELOW_FLOOR', 'JOIN_LOSES', 'UNKNOWN'];

/** A price with the estimated margin at it, one under the other. */
function PriceMargin({
  price,
  margin,
  currency,
}: {
  readonly price: string | null;
  readonly margin: string | null;
  readonly currency: string | null;
}): React.JSX.Element {
  return (
    <Flex vertical>
      <Money value={price} currency={currency} />
      <Typography.Text
        type="secondary"
        style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums' }}
      >
        {formatPercent(margin)}
      </Typography.Text>
    </Flex>
  );
}

/**
 * A marketplace description as text. Ozon writes it with HTML markup; it is parsed only to read its
 * text and is never rendered as HTML.
 */
function plainText(html: string): string {
  const parsed = new DOMParser().parseFromString(html, 'text/html');
  return parsed.body.textContent.replace(/\s+/g, ' ').trim();
}

function productName(item: PromotionItem): string {
  return (
    [item.title, item.size, item.color]
      .filter((part): part is string => part !== null)
      .join(' · ') || '—'
  );
}

function itemColumns(
  promotionId: string,
  decisions: ReadonlyMap<string, PromotionDecision> | undefined,
  onDecide: (item: PromotionItem) => void,
): TableColumnsType<PromotionItem> {
  return [
    {
      title: text.columnProduct,
      key: 'product',
      render: (_, item) => (
        <Flex vertical>
          <Typography.Text>{productName(item)}</Typography.Text>
          {item.offerId !== null && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {item.offerId}
            </Typography.Text>
          )}
        </Flex>
      ),
    },
    {
      title: text.columnMembership,
      key: 'membership',
      render: (_, item) => (
        <Flex vertical gap={2} align="flex-start">
          <Tag
            color={item.membership === 'PARTICIPANT' ? 'processing' : 'default'}
            style={{ marginInlineEnd: 0 }}
          >
            {codeLabel(MEMBERSHIP_LABELS, item.membership)}
          </Tag>
          {item.addMode !== null && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {codeLabel(ADD_MODE_LABELS, item.addMode)}
            </Typography.Text>
          )}
          {item.quarantined === true && (
            <Typography.Text type="warning" style={{ fontSize: 12 }}>
              {text.quarantined}
            </Typography.Text>
          )}
        </Flex>
      ),
    },
    {
      title: text.columnNow,
      key: 'now',
      render: (_, item) => (
        <PriceMargin
          price={item.buyerPriceNow}
          margin={item.marginNow}
          currency={item.currencyCode}
        />
      ),
    },
    {
      title: text.columnAction,
      key: 'action',
      render: (_, item) =>
        item.membership === 'PARTICIPANT' ? (
          <PriceMargin
            price={item.actionPrice}
            margin={item.marginAtActionPrice}
            currency={item.currencyCode}
          />
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: text.columnMaxAction,
      key: 'maxAction',
      render: (_, item) => (
        <PriceMargin
          price={item.maxActionPrice}
          margin={item.marginAtMaxActionPrice}
          currency={item.currencyCode}
        />
      ),
    },
    {
      title: text.columnRecommended,
      key: 'recommended',
      render: (_, item) => (
        <Flex vertical gap={2}>
          <PriceMargin
            price={item.recommendedActionPrice}
            margin={item.marginAtRecommendedPrice}
            currency={item.currencyCode}
          />
          {item.aboveRecommended === true && (
            <Typography.Text type="warning" style={{ fontSize: 12 }}>
              {text.aboveRecommended}
            </Typography.Text>
          )}
        </Flex>
      ),
    },
    {
      title: text.columnBoost,
      key: 'boost',
      render: (_, item) => {
        // Ozon states 0 for a boost range or a stock rule a promotion does not have.
        const boosted =
          item.minBoost !== null && item.maxBoost !== null && Number(item.maxBoost) > 0;
        const stockRule = (item.minStock ?? 0) > 0 || (item.recommendedStock ?? 0) > 0;
        if (!boosted && !stockRule) {
          return <Typography.Text type="secondary">—</Typography.Text>;
        }
        return (
          <Flex vertical gap={2}>
            {boosted && (
              <>
                <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {text.boostRange(item.minBoost, item.maxBoost)}
                </Typography.Text>
                {item.currentBoost !== null && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {text.boostCurrent(item.currentBoost)}
                  </Typography.Text>
                )}
                {item.priceForMaxBoost !== null && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {text.maxBoostPrice}：
                    <Money value={item.priceForMaxBoost} currency={item.currencyCode} /> ·{' '}
                    {formatPercent(item.marginAtMaxBoostPrice)}
                  </Typography.Text>
                )}
              </>
            )}
            {stockRule && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {text.stockRule(String(item.minStock ?? 0), String(item.recommendedStock ?? 0))}
              </Typography.Text>
            )}
          </Flex>
        );
      },
    },
    {
      title: (
        <Space size={4}>
          {text.columnVerdict}
          <InfoTip title={text.verdictHint} />
        </Space>
      ),
      key: 'verdict',
      render: (_, item) => (
        <Flex vertical gap={2} align="flex-start">
          <Tag color={VERDICT_COLORS[item.verdict] ?? 'default'} style={{ marginInlineEnd: 0 }}>
            {codeLabel(VERDICT_LABELS, item.verdict)}
          </Tag>
          {item.breakEvenPrice !== null && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.breakEven} <Money value={item.breakEvenPrice} currency={item.currencyCode} />
            </Typography.Text>
          )}
          {item.missing.length > 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.missing}
              {item.missing.map((code) => codeLabel(MISSING_LABELS, code)).join('、')}
            </Typography.Text>
          )}
        </Flex>
      ),
    },
    ...(decisions === undefined
      ? []
      : [
          {
            title: text.columnDecision,
            key: 'decision',
            render: (_: unknown, item: PromotionItem) => (
              <DecisionCell
                decision={decisions.get(decisionKey(promotionId, item.listingVariantId))}
                onDecide={() => {
                  onDecide(item);
                }}
              />
            ),
          },
        ]),
  ];
}

/** The newest decision recorded for a product, and the way to record another. */
function DecisionCell({
  decision,
  onDecide,
}: {
  readonly decision: PromotionDecision | undefined;
  readonly onDecide: () => void;
}): React.JSX.Element {
  return (
    <Flex vertical gap={2} align="flex-start">
      {decision === undefined ? (
        <Typography.Text type="secondary">{text.decisionNone}</Typography.Text>
      ) : (
        <>
          <Tag
            color={DECISION_COLORS[decision.decision] ?? 'default'}
            style={{ marginInlineEnd: 0 }}
          >
            {codeLabel(DECISION_LABELS, decision.decision)}
          </Tag>
          {decision.actionPrice !== null && (
            <Money value={decision.actionPrice} currency={decision.currencyCode} />
          )}
          {decision.note !== null && (
            <Typography.Text
              type="secondary"
              style={{ fontSize: 12, maxWidth: 220 }}
              ellipsis={{ tooltip: decision.note }}
            >
              {decision.note}
            </Typography.Text>
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.decidedAt} <DateTime value={decision.decidedAt} />
          </Typography.Text>
        </>
      )}
      <Button type="link" size="small" style={{ padding: 0 }} onClick={onDecide}>
        {text.decisionRecord}
      </Button>
    </Flex>
  );
}

/** What the person is deciding about, as the economics estimated it. */
function DecisionSummary({ item }: { readonly item: PromotionItem }): React.JSX.Element {
  return (
    <Descriptions
      size="small"
      column={1}
      bordered
      items={[
        { key: 'product', label: text.columnProduct, children: productName(item) },
        {
          key: 'membership',
          label: text.columnMembership,
          children: codeLabel(MEMBERSHIP_LABELS, item.membership),
        },
        {
          key: 'now',
          label: text.summaryNow,
          children: (
            <PriceMargin
              price={item.buyerPriceNow}
              margin={item.marginNow}
              currency={item.currencyCode}
            />
          ),
        },
        ...(item.membership === 'PARTICIPANT'
          ? [
              {
                key: 'action',
                label: text.summaryAction,
                children: (
                  <PriceMargin
                    price={item.actionPrice}
                    margin={item.marginAtActionPrice}
                    currency={item.currencyCode}
                  />
                ),
              },
            ]
          : []),
        {
          key: 'maxAction',
          label: text.summaryMaxAction,
          children: (
            <PriceMargin
              price={item.maxActionPrice}
              margin={item.marginAtMaxActionPrice}
              currency={item.currencyCode}
            />
          ),
        },
        {
          key: 'recommended',
          label: text.summaryRecommended,
          children: (
            <PriceMargin
              price={item.recommendedActionPrice}
              margin={item.marginAtRecommendedPrice}
              currency={item.currencyCode}
            />
          ),
        },
        {
          key: 'verdict',
          label: text.columnVerdict,
          children: (
            <Space size={8} wrap>
              <Tag color={VERDICT_COLORS[item.verdict] ?? 'default'} style={{ marginInlineEnd: 0 }}>
                {codeLabel(VERDICT_LABELS, item.verdict)}
              </Tag>
              {item.breakEvenPrice !== null && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {text.breakEven}{' '}
                  <Money value={item.breakEvenPrice} currency={item.currencyCode} />
                </Typography.Text>
              )}
            </Space>
          ),
        },
      ]}
    />
  );
}

/** One promotion: what it is, and each product's economics at its prices. */
function PromotionCard({
  context,
  storeId,
  promotion,
  decisions,
  onRecorded,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly promotion: Promotion;
  readonly decisions: ReadonlyMap<string, PromotionDecision> | undefined;
  readonly onRecorded: (decision: PromotionDecision) => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [deciding, setDeciding] = useState<PromotionItem | undefined>(undefined);
  const items = [...promotion.items].sort(
    (left, right) =>
      VERDICT_ORDER.indexOf(left.verdict) - VERDICT_ORDER.indexOf(right.verdict) ||
      productName(left).localeCompare(productName(right), 'ru'),
  );
  const count = (verdict: string): number =>
    items.filter((item) => item.verdict === verdict).length;
  const title = promotion.title ?? promotion.promotionKind ?? promotion.nativePromotionKey;
  return (
    <SectionCard
      title={
        <Space size={8} wrap>
          <span lang="ru">{title}</span>
          {promotion.promotionKind !== null && (
            <Tag style={{ marginInlineEnd: 0 }}>
              {codeLabel(PROMOTION_KIND_LABELS, promotion.promotionKind)}
            </Tag>
          )}
          <Tag
            color={promotion.participating === true ? 'processing' : 'default'}
            style={{ marginInlineEnd: 0 }}
          >
            {promotion.participating === true ? text.participating : text.notParticipating}
          </Tag>
          {promotion.voucher === true && <Tag style={{ marginInlineEnd: 0 }}>{text.voucher}</Tag>}
          {promotion.targeted === true && <Tag style={{ marginInlineEnd: 0 }}>{text.targeted}</Tag>}
        </Space>
      }
    >
      <Flex vertical gap={10}>
        <Flex gap={16} wrap>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.period}：
            {promotion.startsAt === null ? '—' : <DateTime value={promotion.startsAt} />} –{' '}
            {promotion.endsAt === null ? '—' : <DateTime value={promotion.endsAt} />}
          </Typography.Text>
          {promotion.freezesAt !== null && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.freezes} <DateTime value={promotion.freezesAt} />{' '}
              <InfoTip title={text.freezesHint} />
            </Typography.Text>
          )}
          {promotion.discountValue !== null && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.discount}：{promotion.discountValue}
              {promotion.discountKind === null
                ? ''
                : `（${codeLabel(DISCOUNT_KIND_LABELS, promotion.discountKind)}）`}
            </Typography.Text>
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.counts(
              promotion.candidateCount === null ? '—' : String(promotion.candidateCount),
              promotion.participantCount === null ? '—' : String(promotion.participantCount),
            )}
          </Typography.Text>
        </Flex>
        {promotion.description !== null && (
          <Typography.Paragraph
            type="secondary"
            lang="ru"
            style={{ fontSize: 12, marginBottom: 0 }}
            ellipsis={{
              rows: 2,
              expandable: 'collapsible',
              symbol: (expanded) => (expanded ? text.descriptionCollapse : text.descriptionExpand),
            }}
          >
            {plainText(promotion.description)}
          </Typography.Paragraph>
        )}
        {items.length === 0 ? (
          <Typography.Text type="secondary">{text.itemsNone}</Typography.Text>
        ) : (
          <>
            <Typography.Text>
              {text.summary(
                count('JOIN_KEEPS_FLOOR'),
                count('JOIN_BELOW_FLOOR'),
                count('JOIN_LOSES'),
                count('UNKNOWN'),
              )}
            </Typography.Text>
            <Table<PromotionItem>
              size="small"
              rowKey={(item) => `${item.membership}:${item.listingVariantId}`}
              columns={itemColumns(promotion.promotionId, decisions, setDeciding)}
              dataSource={items}
              pagination={items.length > 20 ? { pageSize: 20 } : false}
              scroll={{ x: 'max-content' }}
            />
          </>
        )}
      </Flex>
      <ActionModal<DecisionValues>
        open={deciding !== undefined}
        onClose={() => {
          setDeciding(undefined);
        }}
        title={text.decisionTitle}
        consequence={text.decisionConsequence}
        summary={deciding === undefined ? undefined : <DecisionSummary item={deciding} />}
        okText={text.decisionConfirm}
        width={600}
        onSubmit={async (values) => {
          if (deciding === undefined) return undefined;
          const price = (values.actionPrice ?? '').trim();
          const priced =
            values.decision === 'JOINED' && price !== '' && deciding.currencyCode !== null;
          const note = (values.note ?? '').trim();
          const outcome = await recordPromotionDecision(context, storeId, promotion.promotionId, {
            listingVariantId: deciding.listingVariantId,
            decision: values.decision ?? '',
            actionPrice: priced ? price : null,
            currencyCode: priced ? deciding.currencyCode : null,
            note: note === '' ? null : note,
          });
          if (!outcome.ok) return outcome.failure;
          void message.success(text.decisionRecorded);
          onRecorded(outcome.value);
          return undefined;
        }}
      >
        {(form) => (
          <>
            <Form.Item<DecisionValues>
              name="decision"
              label={text.decisionField}
              rules={[{ required: true, message: text.decisionField }]}
            >
              <Radio.Group
                options={(deciding === undefined ? [] : decisionChoices(deciding)).map(
                  (choice) => ({
                    value: choice,
                    label: codeLabel(DECISION_OPTION_LABELS, choice),
                  }),
                )}
              />
            </Form.Item>
            {form.getFieldValue('decision') === 'JOINED' &&
              (deciding?.currencyCode === null ? (
                <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                  {text.decisionPriceNoCurrency}
                </Typography.Paragraph>
              ) : (
                <Form.Item<DecisionValues>
                  name="actionPrice"
                  label={`${text.decisionPrice}（${deciding?.currencyCode ?? ''}）`}
                  extra={text.decisionPriceHint}
                  rules={[
                    {
                      validator: (_: unknown, value: string | undefined) =>
                        value === undefined || value.trim() === '' || isActionPrice(value.trim())
                          ? Promise.resolve()
                          : Promise.reject(new Error(text.decisionPriceInvalid)),
                    },
                  ]}
                >
                  <DecimalField ariaLabel={text.decisionPrice} />
                </Form.Item>
              ))}
            <Form.Item<DecisionValues> name="note" label={text.decisionNote}>
              <Input.TextArea
                rows={2}
                maxLength={500}
                showCount
                placeholder={text.decisionNotePlaceholder}
              />
            </Form.Item>
          </>
        )}
      </ActionModal>
    </SectionCard>
  );
}

/** The store's current promotions and what joining each would mean for each product. */
export function PromotionsView({ context, storeId }: PromotionsViewProps): React.JSX.Element {
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  useEffect(() => {
    let live = true;
    void Promise.all([
      fetchStorePromotions(context, storeId),
      fetchPromotionDecisions(context, storeId),
    ]).then(([promotions, decisions]) => {
      if (!live) return;
      if (!promotions.ok) {
        setLoaded({ kind: 'failed', failure: promotions.failure });
        return;
      }
      setLoaded({
        kind: 'loaded',
        data: promotions.value,
        decisions: decisions.ok
          ? new Map(
              decisions.value.map((decision) => [
                decisionKey(decision.promotionId, decision.listingVariantId),
                decision,
              ]),
            )
          : undefined,
      });
    });
    return () => {
      live = false;
    };
  }, [context, storeId, generation]);

  if (loaded.kind === 'loading') return <LoadingState />;
  if (loaded.kind === 'failed') return <FailureAlert failure={loaded.failure} />;
  const { data, decisions } = loaded;
  const observed = data.promotions[0]?.observedAt ?? null;
  const recorded = (decision: PromotionDecision): void => {
    setLoaded((current) => {
      if (current.kind !== 'loaded' || current.decisions === undefined) return current;
      const next = new Map(current.decisions);
      next.set(decisionKey(decision.promotionId, decision.listingVariantId), decision);
      return { ...current, decisions: next };
    });
  };
  return (
    <Flex vertical gap={16}>
      <Flex justify="space-between" align="center" gap={8} wrap>
        <Flex vertical gap={2}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {data.minimumMarginRate === null
              ? text.floorUnset
              : text.floor(formatPercent(data.minimumMarginRate))}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.basis}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {observed !== null && (
              <>
                {text.observedAt} <DateTime value={observed} /> ·{' '}
              </>
            )}
            {text.generatedAt} <DateTime value={data.generatedAt} />
          </Typography.Text>
        </Flex>
        <Button
          icon={<ReloadOutlined />}
          onClick={() => {
            setGeneration((value) => value + 1);
          }}
        >
          {text.reload}
        </Button>
      </Flex>
      {data.promotions.some((promotion) => promotion.items.length > 0) && (
        <PromotionAiReview context={context} storeId={storeId} />
      )}
      {decisions === undefined && data.promotions.length > 0 && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.decisionsUnavailable}
        </Typography.Text>
      )}
      {data.promotions.length === 0 ? (
        <EmptyState description={text.none} />
      ) : (
        data.promotions.map((promotion) => (
          <PromotionCard
            key={promotion.promotionId}
            context={context}
            storeId={storeId}
            promotion={promotion}
            decisions={decisions}
            onRecorded={recorded}
          />
        ))
      )}
    </Flex>
  );
}
