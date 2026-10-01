import { App, Button, Descriptions, Flex, Form, Input, Space, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import type { ConsoleFailure, ConsoleRequest, Recommendation } from '../api/console';
import { fetchRecommendations } from '../api/console';
import type {
  GuardrailEvaluation,
  PriceDecision,
  PriceDecisionKind,
} from '../api/priceSuggestions';
import {
  fetchGuardrailEvaluations,
  fetchPriceDecisions,
  recordPriceDecision,
} from '../api/priceSuggestions';
import { REVIEW_PARAM } from '../diagnosis/SubjectDiagnosisView';
import { formatDecimal, formatMoney, formatPercent } from '../format';
import { codeLabel } from '../i18n';
import { GUARDRAIL_REASON_LABELS, RULE_LABELS } from '../i18n/zh/pricing';
import { priceSuggestionText as text } from '../i18n/zh/storeDiagnosis';
import { subjectPath } from '../layout/navigation';
import { DecimalField } from '../listing/ListingActionFields';
import { ActionModal, DateTime, FailureAlert, InfoTip } from '../ui';

type Load =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure }
  | {
      readonly kind: 'loaded';
      readonly suggestion: Recommendation | undefined;
      readonly evaluation: GuardrailEvaluation | undefined;
      readonly decision: PriceDecision | undefined;
    };

interface DecisionValues {
  readonly appliedPrice?: string;
  readonly note?: string;
}

/** At most four decimals and above zero, as the record requires. */
function isPrice(value: string): boolean {
  return /^\d+(\.\d{1,4})?$/.test(value) && Number(value) > 0;
}

/** A signed share as a percentage, e.g. `-0.1` → `-10%`. */
function signedPercent(rate: string | undefined): string {
  if (rate === undefined) return '—';
  const shown = formatPercent(rate);
  return rate.startsWith('-') || shown === '—' ? shown : `+${shown}`;
}

/** The open price suggestion of one product, its newest guardrail verdict and the newest decision. */
async function loadSuggestion(
  context: ConsoleRequest,
  storeId: string,
  subjectId: string,
): Promise<Load> {
  const [recommendations, decisions] = await Promise.all([
    fetchRecommendations(context, storeId, subjectId),
    fetchPriceDecisions(context, storeId, subjectId),
  ]);
  if (!recommendations.ok) return { kind: 'failed', failure: recommendations.failure };
  const suggestion = recommendations.value.find(
    (item) => item.actionKind === 'PRICE_CHANGE' && item.subjectId === subjectId,
  );
  const evaluations =
    suggestion === undefined ? undefined : await fetchGuardrailEvaluations(context, suggestion.id);
  return {
    kind: 'loaded',
    suggestion,
    evaluation: evaluations?.ok === true ? evaluations.value[0] : undefined,
    decision: decisions.ok ? decisions.value[0] : undefined,
  };
}

/**
 * The price suggestion of one product: what the deterministic rules propose, the bounds it keeps, the
 * guardrail's verdict, and what a person did with it. While the platform's own writes are off, a
 * person who agrees changes the price in the Ozon back office and records it here.
 */
export function PriceSuggestionSection({
  context,
  storeId,
  subjectId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly subjectId: string;
}): React.JSX.Element | null {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [deciding, setDeciding] = useState<PriceDecisionKind | undefined>(undefined);

  useEffect(() => {
    let live = true;
    void loadSuggestion(context, storeId, subjectId).then((loaded) => {
      if (live) setLoad(loaded);
    });
    return () => {
      live = false;
    };
  }, [context, storeId, subjectId, generation]);

  if (load.kind === 'loading') return null;
  if (load.kind === 'failed') return <FailureAlert failure={load.failure} />;
  const { suggestion, evaluation, decision } = load;

  const title = (
    <Space size={4}>
      <Typography.Title level={5} style={{ margin: 0 }}>
        {text.title}
      </Typography.Title>
      <InfoTip title={text.hint} />
    </Space>
  );

  if (suggestion === undefined) {
    if (decision === undefined) return null;
    // Nothing open: say what was decided about the last one.
    const recorded = decision;
    return (
      <Flex vertical gap={8}>
        {title}
        <Typography.Text>
          {recorded.decision === 'APPLIED_IN_SELLER_OFFICE'
            ? text.decidedApplied(formatMoney(recorded.appliedPrice, recorded.currencyCode))
            : text.decidedNotApplied}
          {recorded.note === null ? '' : `：${recorded.note}`}
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.decidedAt} <DateTime value={recorded.decidedAt} />
        </Typography.Text>
      </Flex>
    );
  }

  const effect = suggestion.expectedEffect;
  const currency = effect.currencyCode ?? null;
  const money = (value: string | undefined): string => formatMoney(value ?? null, currency);
  return (
    <Flex vertical gap={8}>
      {title}
      <Descriptions size="small" column={1} bordered>
        {effect.basis !== undefined && (
          <Descriptions.Item label={text.basis}>
            {codeLabel(RULE_LABELS, effect.basis)}
          </Descriptions.Item>
        )}
        <Descriptions.Item label={text.priceChange}>
          <Space size={4} wrap>
            <span>{money(effect.currentPrice)}</span>
            <span>→</span>
            <Typography.Text strong>
              {money(suggestion.proposedParameters.targetPrice)}
            </Typography.Text>
            <Typography.Text type="secondary">
              {text.changeRate(signedPercent(effect.changeRate))}
            </Typography.Text>
          </Space>
        </Descriptions.Item>
        {effect.rangeLower !== undefined && effect.rangeUpper !== undefined && (
          <Descriptions.Item
            label={
              <Space size={4}>
                {text.range}
                <InfoTip title={text.rangeHint} />
              </Space>
            }
          >
            {text.rangeValue(money(effect.rangeLower), money(effect.rangeUpper))}
          </Descriptions.Item>
        )}
        {effect.marginNow !== undefined && effect.marginAtTarget !== undefined && (
          <Descriptions.Item label={text.margin}>
            {text.marginChange(
              formatPercent(effect.marginNow),
              formatPercent(effect.marginAtTarget),
            )}
          </Descriptions.Item>
        )}
        {effect.competitorMinPrice !== undefined && (
          <Descriptions.Item label={text.competitor}>
            {money(effect.competitorMinPrice)}
          </Descriptions.Item>
        )}
        {effect.searchUsers !== undefined && (
          <Descriptions.Item label={text.searchUsers}>
            {formatDecimal(effect.searchUsers, { maxFractionDigits: 0 })}
          </Descriptions.Item>
        )}
        <Descriptions.Item label={text.guardrail}>
          {evaluation === undefined ? (
            <Typography.Text type="secondary">{text.guardrailNotYet}</Typography.Text>
          ) : evaluation.passed ? (
            <Tag color="success">{text.guardrailPassed}</Tag>
          ) : (
            <Flex vertical gap={4}>
              <Typography.Text>
                <Tag color="warning">{text.guardrailFailed}</Tag>
                {evaluation.reasonCodes
                  .map((code) => codeLabel(GUARDRAIL_REASON_LABELS, code))
                  .join('、')}
              </Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {text.guardrailHint}
              </Typography.Text>
            </Flex>
          )}
        </Descriptions.Item>
      </Descriptions>
      <Space size={8} wrap>
        <Button
          type="primary"
          onClick={() => {
            setDeciding('APPLIED_IN_SELLER_OFFICE');
          }}
        >
          {text.applied}
        </Button>
        <Button
          onClick={() => {
            setDeciding('NOT_APPLIED');
          }}
        >
          {text.notApplied}
        </Button>
        <Button
          type="link"
          onClick={() => {
            void navigate(
              `${subjectPath(subjectId)}?${REVIEW_PARAM}=${encodeURIComponent(suggestion.id)}`,
            );
          }}
        >
          {text.openReview}
        </Button>
      </Space>
      <ActionModal<DecisionValues>
        open={deciding !== undefined}
        onClose={() => {
          setDeciding(undefined);
        }}
        title={deciding === 'APPLIED_IN_SELLER_OFFICE' ? text.appliedTitle : text.notAppliedTitle}
        initialValues={
          deciding === 'APPLIED_IN_SELLER_OFFICE'
            ? { appliedPrice: suggestion.proposedParameters.targetPrice ?? '' }
            : {}
        }
        width={520}
        onSubmit={async (values) => {
          if (deciding === undefined) return undefined;
          const price = (values.appliedPrice ?? '').trim();
          const note = (values.note ?? '').trim();
          const outcome = await recordPriceDecision(context, suggestion.id, {
            decision: deciding,
            appliedPrice: deciding === 'APPLIED_IN_SELLER_OFFICE' ? price : null,
            note: note === '' ? null : note,
            expectedVersion: suggestion.version,
          });
          if (!outcome.ok) return outcome.failure;
          void message.success(text.saved);
          setGeneration((value) => value + 1);
          return undefined;
        }}
      >
        {deciding === 'APPLIED_IN_SELLER_OFFICE' && (
          <Form.Item<DecisionValues>
            name="appliedPrice"
            label={`${text.appliedPrice}${currency === null ? '' : `（${currency}）`}`}
            rules={[
              {
                validator: (_: unknown, value: string | undefined) =>
                  value !== undefined && isPrice(value.trim())
                    ? Promise.resolve()
                    : Promise.reject(new Error(text.appliedPriceRequired)),
              },
            ]}
          >
            <DecimalField ariaLabel={text.appliedPrice} />
          </Form.Item>
        )}
        <Form.Item<DecisionValues>
          name="note"
          label={deciding === 'NOT_APPLIED' ? text.reason : text.note}
        >
          <Input.TextArea rows={2} maxLength={500} showCount />
        </Form.Item>
      </ActionModal>
    </Flex>
  );
}
