import { CheckCircleFilled, CloseCircleOutlined, RobotOutlined } from '@ant-design/icons';
import { Button, Collapse, Descriptions, Flex, Space, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import type { ClaimValue, ConsoleFailure, ConsoleRequest, ExplanationClaim } from '../api/console';
import type {
  DiagnosisProduct,
  ListingContent,
  RatingCondition,
  RatingGroup,
} from '../api/storeDiagnosis';
import { fetchListingContent } from '../api/storeDiagnosis';
import {
  contentText as text,
  RATING_CONDITION_BRACKETS,
  RATING_CONDITION_LABELS,
  RATING_GROUP_LABELS,
} from '../i18n/zh/contentOptimization';
import { DateTime, FailureAlert } from '../ui';
import type { ReferenceLabel } from './AiInterpretation';
import {
  payloadText,
  Provenance,
  References,
  Rejected,
  Unavailable,
  useExplanation,
} from './AiInterpretation';

type Loaded =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly content: ListingContent }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/** Russian text in one comparable form: lower case, ё as е. */
function normalized(value: string): string {
  return value.toLocaleLowerCase('ru').replaceAll('ё', 'е');
}

/**
 * Whether a text covers a search term: every word of three letters or more appears, compared by
 * its stem (the word without its last two letters, at least four), so an inflected form counts.
 * An approximation, and the page says so.
 */
function covers(content: string, term: string): boolean {
  const haystack = normalized(content);
  const stems = normalized(term)
    .split(/[^\p{L}\p{N}]+/u)
    .filter((word) => word.length >= 3)
    .map((word) => (word.length <= 4 ? word : word.slice(0, Math.max(4, word.length - 2))));
  return stems.length > 0 && stems.every((stem) => haystack.includes(stem));
}

/** One condition's name: ours when we know the key, else Ozon's own words, else the key. */
function conditionLabel(condition: RatingCondition): string {
  return (
    RATING_CONDITION_LABELS[condition.conditionKey] ?? condition.text ?? condition.conditionKey
  );
}

/** A text's length as Ozon counts it: in characters (code points), not UTF-16 units. */
function characters(value: string): number {
  return Array.from(value).length;
}

/** Groups with a gap first, then the heavier ones: where the rating can still rise comes first. */
function byGapThenWeight(left: RatingGroup, right: RatingGroup): number {
  const gap = (group: RatingGroup): number =>
    group.rating !== null && Number(group.rating) < 100 ? 0 : 1;
  const weight = (group: RatingGroup): number => (group.weight === null ? 0 : Number(group.weight));
  return gap(left) - gap(right) || weight(right) - weight(left);
}

function points(condition: RatingCondition): number {
  return condition.points === null ? 0 : Number(condition.points);
}

function Met({ met }: { readonly met: boolean | null }): React.JSX.Element {
  return met === true ? (
    <CheckCircleFilled style={{ color: '#52c41a' }} />
  ) : (
    <CloseCircleOutlined style={{ color: met === false ? '#fa8c16' : '#bfbfbf' }} />
  );
}

/**
 * One rating group: its score and share, where each bracket stands and what the next bracket
 * needs, the other conditions, and the attributes Ozon names to fill.
 */
function RatingGroupView({ group }: { readonly group: RatingGroup }): React.JSX.Element {
  const full = group.rating !== null && Number(group.rating) >= 100;
  const families = new Map<string, RatingCondition[]>();
  const plain: RatingCondition[] = [];
  for (const condition of group.conditions) {
    const family = RATING_CONDITION_BRACKETS[condition.conditionKey];
    if (family === undefined) {
      plain.push(condition);
    } else {
      families.set(family, [...(families.get(family) ?? []), condition]);
    }
  }
  const missing = group.improveAttributes.filter((attribute) => !attribute.filled);
  return (
    <Flex vertical gap={6} style={{ padding: '8px 0', borderTop: '1px solid rgba(5, 5, 5, 0.06)' }}>
      <Flex gap={8} align="center" wrap>
        <Typography.Text strong>
          {RATING_GROUP_LABELS[group.groupKey] ?? group.groupName ?? group.groupKey}
        </Typography.Text>
        {group.rating !== null && (
          <Typography.Text>{text.groupScore(group.rating)}</Typography.Text>
        )}
        {group.weight !== null && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.groupWeight(group.weight)}
          </Typography.Text>
        )}
        <Tag color={full ? 'success' : 'warning'} style={{ marginInlineEnd: 0 }}>
          {full ? text.groupFull : text.groupGap}
        </Tag>
      </Flex>
      {[...families.values()].map((conditions) => {
        const current = conditions
          .filter((condition) => condition.met === true)
          .sort((left, right) => points(right) - points(left))[0];
        const next = conditions
          .filter(
            (condition) =>
              condition.met !== true &&
              points(condition) > (current === undefined ? -1 : points(current)),
          )
          .sort((left, right) => points(left) - points(right))[0];
        return (
          <Flex
            key={conditions[0]?.conditionKey}
            vertical
            gap={2}
            style={{ paddingInlineStart: 12 }}
          >
            {current !== undefined && (
              <Typography.Text style={{ fontSize: 13 }}>
                <Met met={true} /> {text.currentBracket}：{conditionLabel(current)}
              </Typography.Text>
            )}
            {next !== undefined && (
              <Typography.Text style={{ fontSize: 13 }}>
                <Met met={false} /> {text.nextBracket(next.points ?? '—')}：{conditionLabel(next)}
              </Typography.Text>
            )}
          </Flex>
        );
      })}
      {plain.map((condition) => (
        <Flex
          key={condition.conditionKey}
          gap={6}
          align="center"
          style={{ paddingInlineStart: 12 }}
        >
          <Typography.Text style={{ fontSize: 13 }}>
            <Met met={condition.met} /> {conditionLabel(condition)}
          </Typography.Text>
          {condition.met === false && full && (
            <Tag style={{ marginInlineEnd: 0, fontSize: 12 }}>{text.optional}</Tag>
          )}
        </Flex>
      ))}
      {missing.length > 0 && (
        <Flex vertical gap={4} style={{ paddingInlineStart: 12 }}>
          <Typography.Text strong style={{ fontSize: 13 }}>
            {text.improveTitle}
            {group.improveAtLeast !== null && group.improveAtLeast > 0 && (
              <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>
                {' · '}
                {text.improveAtLeast(group.improveAtLeast)}
              </Typography.Text>
            )}
          </Typography.Text>
          <Flex gap={6} wrap>
            {group.improveAttributes.map((attribute) => (
              <Tag
                key={attribute.attributeKey}
                color={attribute.filled ? 'success' : 'warning'}
                style={{ marginInlineEnd: 0 }}
              >
                <span lang="ru">{attribute.name ?? attribute.attributeKey}</span>
                {' · '}
                {attribute.filled ? text.filled : text.notFilled}
              </Tag>
            ))}
          </Flex>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.improveHint}
          </Typography.Text>
        </Flex>
      )}
    </Flex>
  );
}

/** What the card says now: description, rich content, images and filled attributes. */
function CardStatus({ content }: { readonly content: ListingContent }): React.JSX.Element {
  const description = content.description;
  return (
    <Flex vertical gap={6}>
      <Typography.Text strong>{text.cardTitle}</Typography.Text>
      <Descriptions size="small" column={1} bordered>
        <Descriptions.Item label={text.description}>
          {description === null ? text.descriptionNone : text.descriptionLength(description.length)}
        </Descriptions.Item>
        <Descriptions.Item label={text.richContent}>
          {content.richContent === null ? text.richNo : text.richYes(content.richContent.length)}
        </Descriptions.Item>
        <Descriptions.Item label={text.images}>
          {content.imageCount === null ? '—' : text.imagesCount(content.imageCount)}
        </Descriptions.Item>
        <Descriptions.Item label={text.attributes}>
          {text.attributesCount(content.attributeCount)}
        </Descriptions.Item>
      </Descriptions>
      {description !== null && (
        <Collapse
          size="small"
          items={[
            {
              key: 'description',
              label: text.showDescription,
              children:
                description.text === null ? (
                  <Typography.Text type="secondary">{text.descriptionTooLong}</Typography.Text>
                ) : (
                  <Typography.Paragraph
                    lang="ru"
                    copyable={{ text: description.text }}
                    style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}
                  >
                    {description.text}
                  </Typography.Paragraph>
                ),
            },
          ]}
        />
      )}
    </Flex>
  );
}

interface Draft {
  readonly field: string;
  readonly text: string;
  readonly attributeName: string | null;
}

/** The draft a recommendation carries, or `null` when it carries none. */
function draftOf(claim: ExplanationClaim): Draft | null {
  const parameters: ClaimValue | undefined = claim.payload.proposedParameters;
  if (typeof parameters !== 'object' || parameters === null || Array.isArray(parameters))
    return null;
  const record = parameters as Readonly<Record<string, ClaimValue | undefined>>;
  const field = record.contentField;
  const draft = record.draftText;
  const name = record.attributeName;
  if (typeof field !== 'string' || typeof draft !== 'string') return null;
  return { field, text: draft, attributeName: typeof name === 'string' ? name : null };
}

/** Which of the search terms a draft covers, beside how many the current text covers. */
function Coverage({
  terms,
  draft,
  current,
}: {
  readonly terms: readonly string[];
  readonly draft: string;
  readonly current: string;
}): React.JSX.Element | null {
  if (terms.length === 0) return null;
  const now = terms.filter((term) => covers(current, term)).length;
  const covered = terms.filter((term) => covers(draft, term));
  return (
    <Flex vertical gap={4}>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {text.coverage} · {text.coverageNow(now, terms.length)} →{' '}
        {text.coverageDraft(covered.length, terms.length)}
      </Typography.Text>
      <Flex gap={4} wrap>
        {terms.map((term) => (
          <Tag
            key={term}
            color={covered.includes(term) ? 'success' : 'default'}
            style={{ marginInlineEnd: 0, fontSize: 12 }}
          >
            <span lang="ru">{term}</span>
          </Tag>
        ))}
      </Flex>
    </Flex>
  );
}

/** One draft: the Russian text to copy, its length, what it covers and why it was proposed. */
function DraftCard({
  claim,
  draft,
  terms,
  currentTitle,
  currentDescription,
}: {
  readonly claim: ExplanationClaim;
  readonly draft: Draft;
  readonly terms: readonly string[];
  readonly currentTitle: string;
  readonly currentDescription: string | null;
}): React.JSX.Element {
  const label =
    draft.field === 'ATTRIBUTE' && draft.attributeName !== null
      ? text.draftAttribute(draft.attributeName)
      : (text.draftFields[draft.field] ?? draft.field);
  const current =
    draft.field === 'TITLE'
      ? currentTitle
      : draft.field === 'DESCRIPTION'
        ? currentDescription
        : null;
  const effect = payloadText(claim.payload.expectedEffect);
  const risk = payloadText(claim.payload.risk);
  return (
    <Flex
      vertical
      gap={6}
      style={{ border: '1px solid rgba(5, 5, 5, 0.1)', borderRadius: 8, padding: 12 }}
    >
      <Flex justify="space-between" align="center" gap={8} wrap>
        <Typography.Text strong>{label}</Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.draftLength(characters(draft.text))}
          {current !== null && ` · ${text.draftCurrentLength(characters(current))}`}
        </Typography.Text>
      </Flex>
      <Typography.Paragraph
        lang="ru"
        copyable={{ text: draft.text }}
        style={{
          whiteSpace: 'pre-wrap',
          marginBottom: 0,
          background: 'rgba(0, 0, 0, 0.02)',
          padding: 8,
        }}
      >
        {draft.text}
      </Typography.Paragraph>
      {draft.field !== 'ATTRIBUTE' && (
        <Coverage terms={terms} draft={draft.text} current={current ?? ''} />
      )}
      <Typography.Text>{claim.statement}</Typography.Text>
      {effect !== null && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.expectedEffect}
          {effect}
        </Typography.Text>
      )}
      {risk !== null && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.risk}
          {risk}
        </Typography.Text>
      )}
    </Flex>
  );
}

/** The model's Russian drafts for the card, asked for on purpose and read back afterwards. */
function ContentDrafts({
  context,
  storeId,
  product,
  content,
  referenceLabel,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly product: DiagnosisProduct;
  readonly content: ListingContent | null;
  readonly referenceLabel: ReferenceLabel;
}): React.JSX.Element {
  const { loaded, waitedSeconds, generate, requestFailure } = useExplanation(
    context,
    'content',
    storeId,
    product.variantId,
  );
  const explanation = loaded.kind === 'loaded' ? loaded.explanation : null;
  const accepted = explanation?.claims.filter((claim) => claim.accepted) ?? [];
  const ordered = (kind: ExplanationClaim['kind']): ExplanationClaim[] =>
    accepted
      .filter((claim) => claim.kind === kind)
      .sort((left, right) => left.ordinal - right.ordinal);
  const recommendations = ordered('RECOMMENDATION');
  const drafts = recommendations.flatMap((claim) => {
    const draft = draftOf(claim);
    return draft === null ? [] : [{ claim, draft }];
  });
  const advice = recommendations.filter((claim) => draftOf(claim) === null);
  const facts = ordered('FACT');
  const unknowns = ordered('UNKNOWN');
  const terms = (product.search?.terms ?? []).map((term) => term.term);

  return (
    <Flex vertical gap={8}>
      <Flex justify="space-between" align="center" gap={8} wrap>
        <Typography.Text strong>{text.draftsTitle}</Typography.Text>
        <Space size={8}>
          {waitedSeconds !== null && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {`${String(waitedSeconds)} s`}
            </Typography.Text>
          )}
          <Button
            size="small"
            type={explanation === null ? 'primary' : 'default'}
            icon={<RobotOutlined />}
            loading={waitedSeconds !== null}
            onClick={generate}
          >
            {explanation === null ? text.draftsGenerate : text.draftsRegenerate}
          </Button>
        </Space>
      </Flex>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {text.draftsHint}
      </Typography.Text>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {text.draftsNotice}
      </Typography.Text>
      {terms.length === 0 && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.coverageNone}
        </Typography.Text>
      )}
      {requestFailure !== null && <FailureAlert failure={requestFailure} />}
      {loaded.kind === 'loading' && <Typography.Text type="secondary">…</Typography.Text>}
      {loaded.kind === 'failed' && <FailureAlert failure={loaded.failure} />}
      {loaded.kind === 'loaded' && explanation === null && (
        <Typography.Text type="secondary">{text.draftsNone}</Typography.Text>
      )}
      {explanation !== null && accepted.length === 0 && <Unavailable explanation={explanation} />}
      {explanation !== null && accepted.length > 0 && (
        <Flex vertical gap={10}>
          {drafts.map(({ claim, draft }) => (
            <DraftCard
              key={claim.claimId}
              claim={claim}
              draft={draft}
              terms={terms}
              currentTitle={product.title ?? ''}
              currentDescription={content?.description?.text ?? null}
            />
          ))}
          {advice.length > 0 && (
            <Flex vertical gap={4}>
              <Typography.Text strong>{text.draftAdvice}</Typography.Text>
              {advice.map((claim) => (
                <Typography.Text key={claim.claimId}>{claim.statement}</Typography.Text>
              ))}
            </Flex>
          )}
          {unknowns.length > 0 && (
            <Flex vertical gap={4}>
              <Typography.Text strong>{text.unknowns}</Typography.Text>
              {unknowns.map((unknown) => {
                const where = payloadText(unknown.payload.nextEvidence);
                return (
                  <Flex key={unknown.claimId} vertical gap={2}>
                    <Typography.Text>{unknown.statement}</Typography.Text>
                    {where !== null && (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {text.nextEvidence}
                        {where}
                      </Typography.Text>
                    )}
                  </Flex>
                );
              })}
            </Flex>
          )}
          {facts.length > 0 && (
            <Flex vertical gap={4}>
              <Typography.Text strong>{text.facts}</Typography.Text>
              {facts.map((fact) => (
                <Flex key={fact.claimId} vertical gap={2}>
                  <Typography.Text>{fact.statement}</Typography.Text>
                  <References claim={fact} referenceLabel={referenceLabel} />
                </Flex>
              ))}
            </Flex>
          )}
          <Rejected claims={explanation.claims.filter((claim) => !claim.accepted)} />
          <Provenance explanation={explanation} />
        </Flex>
      )}
    </Flex>
  );
}

/**
 * The content optimization of one listing inside its drawer: what Ozon's content rating finds
 * missing, what the card says now, and Russian drafts to review and copy into the seller back
 * office. Nothing here changes the listing.
 */
export function ListingContentOptimization({
  context,
  storeId,
  product,
  referenceLabel,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly product: DiagnosisProduct;
  readonly referenceLabel: ReferenceLabel;
}): React.JSX.Element {
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  useEffect(() => {
    let live = true;
    void fetchListingContent(context, storeId, product.variantId).then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { kind: 'loaded', content: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, storeId, product.variantId]);

  const content = loaded.kind === 'loaded' ? loaded.content : null;
  const observed = content?.catalogObservedAt ?? content?.ratingGroups[0]?.since ?? null;
  return (
    <Flex vertical gap={10}>
      <Flex justify="space-between" align="center" gap={8} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {text.title}
        </Typography.Title>
        {observed !== null && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.observedAt} <DateTime value={observed} />
          </Typography.Text>
        )}
      </Flex>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {text.hint}
      </Typography.Text>
      {loaded.kind === 'loading' && <Typography.Text type="secondary">…</Typography.Text>}
      {loaded.kind === 'failed' && <FailureAlert failure={loaded.failure} />}
      {content !== null &&
        content.catalogObservedAt === null &&
        content.ratingGroups.length === 0 && (
          <Typography.Text type="secondary">{text.none}</Typography.Text>
        )}
      {content !== null && content.ratingGroups.length > 0 && (
        <Flex vertical gap={0}>
          <Typography.Text strong>{text.ratingTitle}</Typography.Text>
          {[...content.ratingGroups].sort(byGapThenWeight).map((group) => (
            <RatingGroupView key={group.groupKey} group={group} />
          ))}
        </Flex>
      )}
      {typeof content?.catalogObservedAt === 'string' && <CardStatus content={content} />}
      <ContentDrafts
        key={product.variantId}
        context={context}
        storeId={storeId}
        product={product}
        content={content}
        referenceLabel={referenceLabel}
      />
    </Flex>
  );
}
