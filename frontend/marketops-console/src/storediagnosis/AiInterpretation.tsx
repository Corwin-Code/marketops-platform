import { ReloadOutlined, RobotOutlined } from '@ant-design/icons';
import { Alert, Button, Collapse, Flex, List, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type {
  AiExplanation,
  ConsoleFailure,
  ConsoleRequest,
  ExplanationClaim,
} from '../api/console';
import {
  fetchLatestContentDraft,
  fetchLatestExplanation,
  fetchLatestPromotionReview,
  fetchLatestStoreExplanation,
  requestContentDraft,
  requestExplanation,
  requestPromotionReview,
  requestStoreExplanation,
} from '../api/console';
import { fetchLatestWeeklyReviewExplanation, requestWeeklyReview } from '../api/outcomes';
import { AI_CLAIM_LABELS_ZH, AiClaimGroups } from '../diagnosis/AiExplanationPanel';
import { codeLabel } from '../i18n';
import {
  ACTION_KIND_LABELS,
  AI_CONFIDENCE_LABELS,
  AI_FAILURE_LABELS,
  AI_REJECTION_LABELS,
  aiText,
} from '../i18n/zh/pricing';
import { storeDiagnosisText as text } from '../i18n/zh/storeDiagnosis';
import { CodeTag, DateTime, EmptyState, FailureAlert, InfoTip, SectionCard } from '../ui';

/** Names a cited identifier, or `undefined` when the page does not know it (older data). */
export type ReferenceLabel = (id: string) => string | undefined;

type Loaded =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly explanation: AiExplanation | null }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

type Source = 'store' | 'listing' | 'content' | 'promotion' | 'weekly';

/** Read the newest recorded answer of a source. */
function latest(
  context: ConsoleRequest,
  source: Source,
  storeId: string,
  listingVariantId: string | undefined,
): ReturnType<typeof fetchLatestStoreExplanation> {
  switch (source) {
    case 'store':
      return fetchLatestStoreExplanation(context, storeId, 'D7');
    case 'listing':
      return fetchLatestExplanation(context, listingVariantId ?? '', storeId, 'D7');
    case 'content':
      return fetchLatestContentDraft(context, listingVariantId ?? '', storeId, 'D7');
    case 'promotion':
      return fetchLatestPromotionReview(context, storeId, 'D7');
    case 'weekly':
      return fetchLatestWeeklyReviewExplanation(context, storeId);
  }
}

/** Ask a source for a new answer. */
function ask(
  context: ConsoleRequest,
  source: Source,
  storeId: string,
  listingVariantId: string | undefined,
): ReturnType<typeof requestStoreExplanation> {
  switch (source) {
    case 'store':
      return requestStoreExplanation(context, storeId, 'D7');
    case 'listing':
      return requestExplanation(context, listingVariantId ?? '', storeId, 'D7');
    case 'content':
      return requestContentDraft(context, listingVariantId ?? '', storeId, 'D7');
    case 'promotion':
      return requestPromotionReview(context, storeId, 'D7');
    case 'weekly':
      return requestWeeklyReview(context, storeId);
  }
}

/**
 * The recorded explanation, and the explicit request for a new one. Opening only reads what an
 * earlier request recorded; asking calls the model unless nothing changed since the last answer.
 */
export function useExplanation(
  context: ConsoleRequest,
  source: Source,
  storeId: string,
  listingVariantId: string | undefined,
  onAnswered?: () => void,
): {
  readonly loaded: Loaded;
  readonly waitedSeconds: number | null;
  readonly generate: () => void;
  readonly reload: () => void;
  readonly requestFailure: ConsoleFailure | null;
} {
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [waitedSeconds, setWaitedSeconds] = useState<number | null>(null);
  const [requestFailure, setRequestFailure] = useState<ConsoleFailure | null>(null);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    let live = true;
    void latest(context, source, storeId, listingVariantId).then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { kind: 'loaded', explanation: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, source, storeId, listingVariantId, generation]);

  useEffect(
    () => () => {
      if (timer.current !== null) clearInterval(timer.current);
    },
    [],
  );

  const generate = (): void => {
    if (waitedSeconds !== null) return;
    setRequestFailure(null);
    const started = Date.now();
    setWaitedSeconds(0);
    timer.current = setInterval(() => {
      setWaitedSeconds(Math.floor((Date.now() - started) / 1000));
    }, 1000);
    void ask(context, source, storeId, listingVariantId).then((outcome) => {
      if (timer.current !== null) clearInterval(timer.current);
      timer.current = null;
      setWaitedSeconds(null);
      if (outcome.ok) {
        setLoaded({ kind: 'loaded', explanation: outcome.value });
      } else {
        setRequestFailure(outcome.failure);
      }
      onAnswered?.();
    });
  };

  return {
    loaded,
    waitedSeconds,
    generate,
    reload: () => {
      setGeneration((value) => value + 1);
    },
    requestFailure,
  };
}

/** Cited identifiers as named tags; ones the page no longer shows are only counted. */
export function References({
  claim,
  referenceLabel,
}: {
  readonly claim: ExplanationClaim;
  readonly referenceLabel: ReferenceLabel;
}): React.JSX.Element | null {
  const ids = [...claim.findingRefs, ...claim.metricValueRefs];
  if (ids.length === 0) return null;
  const named = ids
    .map((id) => referenceLabel(id))
    .filter((label): label is string => label !== undefined);
  const unnamed = ids.length - named.length;
  return (
    <Flex gap={4} wrap>
      {[...new Set(named)].map((label) => (
        <Tag key={label} style={{ marginInlineEnd: 0, fontSize: 12 }}>
          {label}
        </Tag>
      ))}
      {unnamed > 0 && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {named.length === 0 ? text.aiEvidenceCount(unnamed) : text.aiOlderEvidence(unnamed)}
        </Typography.Text>
      )}
    </Flex>
  );
}

export function payloadText(value: unknown): string | null {
  if (typeof value === 'string') return value;
  if (typeof value === 'object' && value !== null && 'rationale' in value) {
    const rationale = (value as { readonly rationale?: unknown }).rationale;
    return typeof rationale === 'string' ? rationale : null;
  }
  return null;
}

/** When the answer was produced, and whether it was handed out again. */
export function Provenance({
  explanation,
}: {
  readonly explanation: AiExplanation;
}): React.JSX.Element {
  const at = explanation.completedAt ?? explanation.startedAt;
  return (
    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      {at === undefined ? null : (
        <>
          {text.aiGeneratedAt} <DateTime value={at} />
        </>
      )}
      {explanation.reused === true ? ` · ${text.aiReused}` : ''}
    </Typography.Text>
  );
}

/** Why no answer is shown: still running, or unavailable for a recorded reason. */
export function Unavailable({
  explanation,
}: {
  readonly explanation: AiExplanation;
}): React.JSX.Element {
  if (explanation.state === 'PREPARED' || explanation.state === 'DISPATCHED') {
    return <Alert type="info" showIcon title={text.aiInFlight} />;
  }
  return (
    <Alert
      type="warning"
      showIcon
      title={
        <>
          {text.aiUnavailable}
          {codeLabel(AI_FAILURE_LABELS, explanation.failureCode ?? explanation.state)}
        </>
      }
      description={text.aiUnaffected}
    />
  );
}

export function Rejected({
  claims,
}: {
  readonly claims: readonly ExplanationClaim[];
}): React.JSX.Element | null {
  if (claims.length === 0) return null;
  return (
    <Collapse
      size="small"
      items={[
        {
          key: 'rejected',
          label: text.aiRejected(claims.length),
          children: (
            <List
              size="small"
              dataSource={[...claims]}
              rowKey="claimId"
              renderItem={(claim) => (
                <List.Item>
                  <Flex gap={8} wrap align="center">
                    <CodeTag labels={AI_REJECTION_LABELS} code={claim.rejectionCode} />
                    <Typography.Text type="secondary">{claim.statement}</Typography.Text>
                  </Flex>
                </List.Item>
              )}
            />
          ),
        },
      ]}
    />
  );
}

/** What a summary card says around the model's answer. */
export interface AiSummaryLabels {
  readonly title: string;
  readonly hint: string;
  readonly none: string;
  readonly actions: string;
}

/**
 * The model's summary of the store above its conclusions: one sentence, at most three actions,
 * the facts they rest on and what the data cannot tell. Advisory only; the deterministic
 * conclusions stay the official answer.
 */
export function StoreAiSummary({
  context,
  storeId,
  referenceLabel,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly referenceLabel: ReferenceLabel;
}): React.JSX.Element {
  return (
    <AiSummaryCard
      context={context}
      storeId={storeId}
      source="store"
      labels={{
        title: text.aiSummaryTitle,
        hint: text.aiSummaryHint,
        none: text.aiNone,
        actions: text.aiActions,
      }}
      referenceLabel={referenceLabel}
    />
  );
}

/**
 * A store-level answer as one card: the conclusion, at most three actions, the facts they rest on
 * and what the data cannot tell, with the explicit request for a new answer.
 */
export function AiSummaryCard({
  context,
  storeId,
  source,
  labels,
  referenceLabel,
  onAnswered,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly source: 'store' | 'promotion' | 'weekly';
  readonly labels: AiSummaryLabels;
  readonly referenceLabel: ReferenceLabel;
  /** Called after an explicit request finished, answered or not. */
  readonly onAnswered?: () => void;
}): React.JSX.Element {
  const { loaded, waitedSeconds, generate, reload, requestFailure } = useExplanation(
    context,
    source,
    storeId,
    undefined,
    onAnswered,
  );
  const explanation = loaded.kind === 'loaded' ? loaded.explanation : null;
  const accepted = explanation?.claims.filter((claim) => claim.accepted) ?? [];
  const of = (kind: ExplanationClaim['kind']): ExplanationClaim[] =>
    accepted
      .filter((claim) => claim.kind === kind)
      .sort((left, right) => left.ordinal - right.ordinal);
  const headline = of('INFERENCE')[0];
  const actions = of('RECOMMENDATION').slice(0, 3);
  const facts = of('FACT');
  const unknowns = of('UNKNOWN');

  const extra = (
    <Space size={8} wrap>
      {waitedSeconds !== null && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.aiWaiting(waitedSeconds)}
        </Typography.Text>
      )}
      <Button icon={<ReloadOutlined />} onClick={reload} disabled={waitedSeconds !== null}>
        {text.aiReload}
      </Button>
      <Button
        type={explanation === null ? 'primary' : 'default'}
        icon={<RobotOutlined />}
        loading={waitedSeconds !== null}
        onClick={generate}
      >
        {explanation === null ? text.aiGenerate : text.aiRegenerate}
      </Button>
      <InfoTip title={labels.hint} />
    </Space>
  );

  let body: React.JSX.Element;
  if (loaded.kind === 'loading') {
    body = <Typography.Text type="secondary">…</Typography.Text>;
  } else if (loaded.kind === 'failed') {
    body = <FailureAlert failure={loaded.failure} />;
  } else if (explanation === null) {
    body = <EmptyState description={labels.none} />;
  } else if (accepted.length === 0) {
    body = <Unavailable explanation={explanation} />;
  } else {
    body = (
      <Flex vertical gap={12}>
        {headline !== undefined && (
          <Flex vertical gap={4}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.aiHeadline}
              {headline.confidenceLabel !== null &&
                ` · ${AI_CLAIM_LABELS_ZH.confidence}：${codeLabel(AI_CONFIDENCE_LABELS, headline.confidenceLabel)}`}
            </Typography.Text>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {headline.statement}
            </Typography.Title>
          </Flex>
        )}
        {actions.length > 0 && (
          <Flex vertical gap={6}>
            <Typography.Text strong>{labels.actions}</Typography.Text>
            {actions.map((action, index) => {
              const capability = action.payload.actionCapability;
              const effect = payloadText(action.payload.expectedEffect);
              return (
                <Flex key={action.claimId} vertical gap={2}>
                  <Flex gap={8} align="baseline">
                    <Typography.Text strong>{index + 1}.</Typography.Text>
                    <Typography.Text>{action.statement}</Typography.Text>
                    {typeof capability === 'string' && (
                      <Tag color="processing" style={{ marginInlineEnd: 0 }}>
                        {codeLabel(ACTION_KIND_LABELS, capability)}
                      </Tag>
                    )}
                  </Flex>
                  {effect !== null && (
                    <Typography.Text
                      type="secondary"
                      style={{ fontSize: 12, paddingInlineStart: 18 }}
                    >
                      {text.aiExpectedEffect}
                      {effect}
                    </Typography.Text>
                  )}
                </Flex>
              );
            })}
          </Flex>
        )}
        {facts.length > 0 && (
          <Flex vertical gap={6}>
            <Typography.Text strong>{text.aiFacts}</Typography.Text>
            {facts.map((fact) => (
              <Flex key={fact.claimId} vertical gap={2}>
                <Typography.Text>{fact.statement}</Typography.Text>
                <References claim={fact} referenceLabel={referenceLabel} />
              </Flex>
            ))}
          </Flex>
        )}
        {unknowns.length > 0 && (
          <Flex vertical gap={4}>
            <Typography.Text strong>{text.aiUnknowns}</Typography.Text>
            {unknowns.map((unknown) => (
              <Typography.Text key={unknown.claimId} type="secondary">
                {unknown.statement}
              </Typography.Text>
            ))}
          </Flex>
        )}
        <Rejected claims={explanation.claims.filter((claim) => !claim.accepted)} />
        <Provenance explanation={explanation} />
      </Flex>
    );
  }

  return (
    <SectionCard title={labels.title} extra={extra}>
      <Flex vertical gap={10}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {aiText.notice}
        </Typography.Text>
        {requestFailure !== null && <FailureAlert failure={requestFailure} />}
        {body}
      </Flex>
    </SectionCard>
  );
}

/**
 * The model's explanation of one listing inside its drawer, over the same seven days as the
 * conclusions above it. Advisory only.
 */
export function ListingAiExplanation({
  context,
  storeId,
  listingVariantId,
  referenceLabel,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly listingVariantId: string;
  readonly referenceLabel: ReferenceLabel;
}): React.JSX.Element {
  const { loaded, waitedSeconds, generate, requestFailure } = useExplanation(
    context,
    'listing',
    storeId,
    listingVariantId,
  );
  const explanation = loaded.kind === 'loaded' ? loaded.explanation : null;
  const accepted = explanation?.claims.some((claim) => claim.accepted) ?? false;
  return (
    <Flex vertical gap={8}>
      <Flex justify="space-between" align="center" gap={8} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {text.aiDrawerTitle}
        </Typography.Title>
        <Space size={8}>
          {waitedSeconds !== null && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.aiWaiting(waitedSeconds)}
            </Typography.Text>
          )}
          <Button
            size="small"
            icon={<RobotOutlined />}
            loading={waitedSeconds !== null}
            onClick={generate}
          >
            {explanation === null ? text.aiGenerate : text.aiRegenerate}
          </Button>
        </Space>
      </Flex>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {aiText.notice}
      </Typography.Text>
      {requestFailure !== null && <FailureAlert failure={requestFailure} />}
      {loaded.kind === 'loading' && <Typography.Text type="secondary">…</Typography.Text>}
      {loaded.kind === 'failed' && <FailureAlert failure={loaded.failure} />}
      {loaded.kind === 'loaded' && explanation === null && (
        <Typography.Text type="secondary">{text.aiDrawerNone}</Typography.Text>
      )}
      {explanation !== null && !accepted && <Unavailable explanation={explanation} />}
      {explanation !== null && accepted && (
        <>
          <AiClaimGroups
            output={explanation}
            groupNotice={false}
            renderReference={(id) => {
              const label = referenceLabel(id);
              return label === undefined ? null : (
                <Tag style={{ marginInlineEnd: 0, fontSize: 12 }}>{label}</Tag>
              );
            }}
          />
          <Provenance explanation={explanation} />
        </>
      )}
    </Flex>
  );
}
