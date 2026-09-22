import {
  Alert,
  App,
  Card,
  Checkbox,
  Col,
  Form,
  Input,
  Row,
  Segmented,
  Space,
  Table,
  Typography,
} from 'antd';
import { useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  fetchMeaningReviewBasis,
  reviewAction,
  type MeaningAssessment,
  type MeaningReviewBasis,
} from '../api/listingConversion';
import { formatDecimal } from '../format';
import { dialog } from '../i18n/zh/common';
import { reviewText } from '../i18n/zh/listingActions';
import { t } from '../i18n/zh/listing';
import { FormDrawer, LoadingState, Money, TechnicalDetails } from '../ui';
import type { FormDrawerStep, SubmitOutcome } from '../ui';
import {
  Code,
  Details,
  Hint,
  IdText,
  ListingProblem,
  RussianText,
  SubTitle,
  When,
  YesNo,
  codeText,
} from './ListingCommon';
import { ListingPurposeBasisDetails } from './ListingPurposeBasisDetails';
import { PromotionTermsDetails } from './ListingPromotionTerms';

type AnswerState = MeaningAssessment['answers'][number]['state'];

/** A decimal count or quantity without rounding. */
function Quantity({ value }: { readonly value: string | undefined }): React.JSX.Element {
  return (
    <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>
      {value === undefined ? '—' : formatDecimal(value)}
    </Typography.Text>
  );
}

/** The frozen current and target text, side by side, or unknown when not given. */
function TextComparison({
  basis,
}: {
  readonly basis: MeaningReviewBasis;
}): React.JSX.Element | null {
  if (basis.currentText === undefined && basis.targetText === undefined) {
    return null;
  }
  return (
    <Row gutter={[16, 16]}>
      <Col xs={24} lg={12}>
        <SubTitle>{t('meaningCurrent')}</SubTitle>
        {basis.currentText === undefined ? (
          <Typography.Text type="secondary">{reviewText.textUnknown}</Typography.Text>
        ) : (
          <RussianText value={basis.currentText} />
        )}
      </Col>
      <Col xs={24} lg={12}>
        <SubTitle>{t('meaningTarget')}</SubTitle>
        {basis.targetText === undefined ? (
          <Typography.Text type="secondary">{reviewText.textUnknown}</Typography.Text>
        ) : (
          <RussianText value={basis.targetText} />
        )}
      </Col>
    </Row>
  );
}

/**
 * Everything a review or an approval rests on, read-only: the frozen texts or
 * terms, the purpose, the full affected set, the review evidence, the current
 * calibration, materiality and protection, and applicable experience.
 */
export function MeaningBasisView({
  basis,
  approvalMode = false,
}: {
  readonly basis: MeaningReviewBasis;
  readonly approvalMode?: boolean;
}): React.JSX.Element {
  const qualified = basis.ruleState === 'QUALIFIED' && basis.conditions.length > 0;
  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <TextComparison basis={basis} />
      {basis.purposeBasis !== undefined && (
        <ListingPurposeBasisDetails basis={basis.purposeBasis} />
      )}
      {basis.currentDescription !== undefined && (
        <section aria-label={t('frozenPriorEvidence')}>
          <SubTitle>{t('frozenPriorEvidence')}</SubTitle>
          <Details
            items={[
              {
                key: 'observed',
                label: t('sourceTime'),
                children: <When value={basis.currentDescription.observedAt} />,
              },
              {
                key: 'acquired',
                label: t('acquisitionTime'),
                children: <When value={basis.currentDescription.acquiredAt} />,
              },
              {
                key: 'kiz',
                label: t('kiz'),
                children: <YesNo value={basis.currentDescription.kizMarkedDeclared} />,
              },
              {
                key: 'language',
                label: t('languageCode'),
                children: basis.currentDescription.languageCode,
              },
            ]}
          />
          <TechnicalDetails>
            <Space orientation="vertical" size={2}>
              <IdText label={t('observationId')} value={basis.currentDescription.observationId} />
              <IdText label={t('currentDigest')} value={basis.currentDescription.textDigest} />
              <IdText label={t('sourceKind')} value={basis.currentDescription.sourceKind} />
            </Space>
          </TechnicalDetails>
        </section>
      )}
      <section aria-label={t('fullAffectedSet')}>
        <SubTitle>{t('fullAffectedSet')}</SubTitle>
        <Details
          items={[
            {
              key: 'state',
              label: t('state'),
              children: <Code family="affectedSetState" code={basis.affectedSet.state} />,
            },
            {
              key: 'listingVariants',
              label: t('listingVariantMembers'),
              children: `${String(basis.affectedSet.listingVariantIds.length)} 个`,
            },
            {
              key: 'productVariants',
              label: t('productVariantMembers'),
              children: `${String(basis.affectedSet.productVariantIds.length)} 个`,
            },
          ]}
        />
        <TechnicalDetails>
          <Space orientation="vertical" size={2}>
            <IdText label={t('affectedSet')} value={basis.affectedSet.digest} />
            <IdText label={t('affectedSetId')} value={basis.affectedSet.affectedSetId} />
            <IdText
              label={t('nativeScopeObservation')}
              value={basis.affectedSet.nativeScopeObservationId}
            />
            <Typography.Text type="secondary">{t('listingVariantMembers')}</Typography.Text>
            {basis.affectedSet.listingVariantIds.map((id) => (
              <IdText key={id} value={id} />
            ))}
            <Typography.Text type="secondary">{t('productVariantMembers')}</Typography.Text>
            {basis.affectedSet.productVariantIds.map((id) => (
              <IdText key={id} value={id} />
            ))}
            {basis.affectedSet.identityLineage !== undefined && (
              <>
                <Typography.Text type="secondary">{t('identityLineage')}</Typography.Text>
                <pre style={{ margin: 0, fontSize: 12, whiteSpace: 'pre-wrap' }}>
                  {basis.affectedSet.identityLineage}
                </pre>
              </>
            )}
          </Space>
        </TechnicalDetails>
      </section>
      {basis.reviewEvidence !== undefined && (
        <section aria-label={t('independentReview')}>
          <SubTitle>{t('independentReview')}</SubTitle>
          <Details
            items={[
              {
                key: 'verdict',
                label: t('reviewVerdictLabel'),
                children: <Code family="reviewVerdict" code={basis.reviewEvidence.verdict} />,
              },
              {
                key: 'at',
                label: t('reviewedAt'),
                children: <When value={basis.reviewEvidence.reviewedAt} />,
              },
              {
                key: 'route',
                label: t('materiality'),
                children: (
                  <Code family="materialityRoute" code={basis.reviewEvidence.materialityRoute} />
                ),
              },
              {
                key: 'content',
                label: t('contentAxis'),
                children: <YesNo value={basis.reviewEvidence.contentAxisMaterial} />,
              },
              {
                key: 'exposure',
                label: t('exposureAxis'),
                children: <YesNo value={basis.reviewEvidence.exposureAxisMaterial} />,
              },
              {
                key: 'reason',
                label: t('reason'),
                span: 'filled',
                children: basis.reviewEvidence.reason,
              },
            ]}
          />
          <TechnicalDetails>
            <Space orientation="vertical" size={2} style={{ width: '100%' }}>
              <IdText label={t('reviewer')} value={basis.reviewEvidence.reviewerUserId} />
              <IdText label={t('factsDigest')} value={basis.reviewEvidence.factsDigest} />
              <IdText
                label={t('planOrPurposeDigest')}
                value={
                  basis.reviewEvidence.evaluationPlanDigest ??
                  basis.reviewEvidence.purposeBasisDigest
                }
              />
              {basis.reviewEvidence.meaningAssessment !== undefined && (
                <pre style={{ margin: 0, fontSize: 12, whiteSpace: 'pre-wrap' }}>
                  {basis.reviewEvidence.meaningAssessment}
                </pre>
              )}
              {basis.reviewEvidence.exposureEvidence !== undefined && (
                <pre style={{ margin: 0, fontSize: 12, whiteSpace: 'pre-wrap' }}>
                  {basis.reviewEvidence.exposureEvidence}
                </pre>
              )}
            </Space>
          </TechnicalDetails>
        </section>
      )}
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8}>
          <EvidenceCard title={t('currentCalibration')} evidence={basis.calibrationEvidence} />
        </Col>
        <Col xs={24} lg={8}>
          <EvidenceCard title={t('currentMateriality')} evidence={basis.materialityEvidence} />
        </Col>
        <Col xs={24} lg={8}>
          <EvidenceCard
            title={t('currentProtection')}
            evidence={basis.businessProtectionEvidence}
          />
        </Col>
      </Row>
      <TechnicalDetails label={t('authorityBasis')}>
        <pre style={{ margin: 0, fontSize: 12, whiteSpace: 'pre-wrap' }}>
          {basis.authorityDocument}
        </pre>
      </TechnicalDetails>
      {basis.applicableExperience.length > 0 && (
        <section aria-label={t('applicableExperience')}>
          <SubTitle>{t('applicableExperience')}</SubTitle>
          <Table
            size="middle"
            rowKey="experienceApplicationId"
            pagination={false}
            dataSource={[...basis.applicableExperience]}
            columns={[
              {
                key: 'node',
                title: t('nodes'),
                render: (_, item) => (
                  <Space size={4}>
                    <span>{item.sourceNodeCode}</span>
                    <Code family="evaluationStage" code={item.sourceStage} />
                    <Typography.Text type="secondary">
                      {t('revisionShort')} {item.sourceRevision}
                    </Typography.Text>
                  </Space>
                ),
              },
              {
                key: 'kind',
                title: t('experienceCandidateKind'),
                render: (_, item) => <Code family="candidateKind" code={item.candidateKind} />,
              },
              {
                key: 'evidence',
                title: t('experienceEvidence'),
                render: (_, item) => item.applicabilityEvidenceReference,
              },
              {
                key: 'ids',
                title: t('technicalIds'),
                render: (_, item) => (
                  <Space orientation="vertical" size={0}>
                    <IdText label={t('sourceAction')} value={item.sourceActionId} />
                    <IdText label={t('sourceResult')} value={item.sourceResultId} />
                  </Space>
                ),
              },
            ]}
          />
        </section>
      )}
      {basis.promotionTerms !== undefined && <PromotionTermsDetails terms={basis.promotionTerms} />}
      {basis.selectedSimulation !== undefined && (
        <section aria-label={t('selectedSimulation')}>
          <SubTitle>{t('selectedSimulation')}</SubTitle>
          <Alert type="info" showIcon title={t('selectedSimulationBoundary')} />
          <div style={{ height: 8 }} />
          <Details
            items={[
              {
                key: 'start',
                label: t('periodStart'),
                children: <When value={basis.selectedSimulation.periodStart} />,
              },
              {
                key: 'end',
                label: t('periodEnd'),
                children: <When value={basis.selectedSimulation.periodEnd} />,
              },
              {
                key: 'currency',
                label: t('simulationCurrency'),
                children: basis.selectedSimulation.currency ?? '—',
              },
              {
                key: 'reference',
                label: t('simulationReferenceProfit'),
                children: (
                  <Money
                    value={basis.selectedSimulation.referenceProfitLine}
                    currency={basis.selectedSimulation.currency ?? null}
                  />
                ),
              },
              {
                key: 'minimum',
                label: t('minimumQuantity'),
                children:
                  basis.selectedSimulation.inverseMinimumQuantity !== undefined ? (
                    <Quantity value={basis.selectedSimulation.inverseMinimumQuantity} />
                  ) : (
                    <Code family="simulationState" code={basis.selectedSimulation.inverseState} />
                  ),
              },
            ]}
          />
          <div style={{ height: 8 }} />
          <Table
            size="middle"
            rowKey="code"
            pagination={false}
            dataSource={[...basis.selectedSimulation.scenarios]}
            columns={[
              { key: 'code', title: t('scenario'), dataIndex: 'code' },
              {
                key: 'quantity',
                title: t('quantity'),
                align: 'right',
                render: (_, scenario) => <Quantity value={scenario.quantity} />,
              },
              {
                key: 'revenue',
                title: t('netRevenue'),
                align: 'right',
                render: (_, scenario) => (
                  <Money
                    value={scenario.netRevenue}
                    currency={basis.selectedSimulation?.currency ?? null}
                  />
                ),
              },
              {
                key: 'profit',
                title: t('contributionProfit'),
                align: 'right',
                render: (_, scenario) => (
                  <Money
                    value={scenario.contributionProfit}
                    currency={basis.selectedSimulation?.currency ?? null}
                  />
                ),
              },
              {
                key: 'missing',
                title: t('missingInputs'),
                render: (_, scenario) =>
                  scenario.missingInputs.length === 0 ? '—' : scenario.missingInputs.join('、'),
              },
            ]}
          />
          <TechnicalDetails label={t('simulationInputs')}>
            <Space orientation="vertical" size={2} style={{ width: '100%' }}>
              <IdText label={t('simulationId')} value={basis.selectedSimulation.id} />
              <Typography.Text type="secondary">
                {t('computedAt')} <When value={basis.selectedSimulation.computedAt} />
              </Typography.Text>
              <pre style={{ margin: 0, fontSize: 12, whiteSpace: 'pre-wrap' }}>
                {basis.selectedSimulation.inputEvidence}
              </pre>
            </Space>
          </TechnicalDetails>
        </section>
      )}
      {!qualified && <Alert type="warning" showIcon title={t('meaningUnqualified')} />}
      {approvalMode && basis.conditions.length > 0 && (
        <Table
          size="middle"
          rowKey="code"
          pagination={false}
          dataSource={[...basis.conditions]}
          columns={[
            { key: 'condition', title: t('meaningCondition'), dataIndex: 'condition' },
            {
              key: 'axis',
              title: t('axis'),
              render: (_, condition) => codeText('conditionAxis', condition.axis),
            },
          ]}
        />
      )}
    </Space>
  );
}

interface ReviewValues {
  readonly basisReady?: unknown;
  readonly answers?: readonly { readonly state?: AnswerState; readonly reason?: string }[];
  readonly reference?: string;
  readonly complete?: boolean;
  readonly reason?: string;
}

/** The basis as the drawer knows it. */
type BasisState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly basis: MeaningReviewBasis }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/** A form item that holds no value and only shows its rule's verdict. */
function Verdict(): null {
  return null;
}

/**
 * The independent meaning review of a draft action, in three steps: compare
 * the basis, judge every condition with its reason, then attest with evidence.
 *
 * The basis is read when the drawer opens and the attestation is bound to its
 * digest, so what is attested is exactly what was shown. An unknown answer or a
 * missing reason cannot be submitted.
 */
export function MeaningReviewDrawer({
  context,
  actionId,
  onReviewed,
}: {
  readonly context: ConsoleRequest;
  readonly actionId: string;
  readonly onReviewed: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [state, setState] = useState<BasisState>({ kind: 'loading' });
  const epoch = useRef(0);

  const load = (): void => {
    const ticket = ++epoch.current;
    setState({ kind: 'loading' });
    void fetchMeaningReviewBasis(context, actionId).then((outcome) => {
      if (ticket !== epoch.current) return;
      setState(
        outcome.ok
          ? { kind: 'loaded', basis: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
  };

  const basis = state.kind === 'loaded' ? state.basis : undefined;
  const qualified = basis?.ruleState === 'QUALIFIED' && basis.conditions.length > 0;
  const notReady =
    state.kind === 'loading'
      ? reviewText.basisLoading
      : state.kind === 'failed'
        ? reviewText.basisFailed
        : t('meaningUnqualified');

  const steps: readonly FormDrawerStep[] = [
    {
      key: 'basis',
      title: reviewText.stepBasis,
      fields: ['basisReady'],
      content: (
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {state.kind === 'loading' && <LoadingState rows={6} />}
          {state.kind === 'failed' && <ListingProblem failure={state.failure} />}
          {basis !== undefined && <MeaningBasisView basis={basis} />}
          <Form.Item
            name="basisReady"
            rules={[
              {
                validator: () =>
                  qualified ? Promise.resolve() : Promise.reject(new Error(notReady)),
              },
            ]}
          >
            <Verdict />
          </Form.Item>
        </Space>
      ),
    },
    {
      key: 'conditions',
      title: reviewText.stepConditions,
      fields: ['answers'],
      content: (
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          <Hint>{t('meaningHelp')}</Hint>
          {basis?.conditions.map((condition, index) => (
            <Card
              key={condition.code}
              size="small"
              title={
                <Space>
                  <span>
                    {index + 1}. {condition.condition}
                  </span>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {codeText('conditionAxis', condition.axis)}
                  </Typography.Text>
                </Space>
              }
            >
              <Form.Item
                name={['answers', index, 'state']}
                label={t('meaningJudgement')}
                initialValue="UNKNOWN"
                rules={[
                  {
                    validator: (_, value: AnswerState | undefined) =>
                      value === 'APPLIES' || value === 'DOES_NOT_APPLY'
                        ? Promise.resolve()
                        : Promise.reject(new Error(reviewText.answerRequired)),
                  },
                ]}
              >
                <Segmented<AnswerState>
                  aria-label={condition.code}
                  options={(['UNKNOWN', 'APPLIES', 'DOES_NOT_APPLY'] as const).map((answer) => ({
                    value: answer,
                    label: codeText('meaningAnswer', answer),
                  }))}
                />
              </Form.Item>
              <Form.Item
                name={['answers', index, 'reason']}
                label={`${t('meaningReason')} ${String(index + 1)}`}
                style={{ marginBottom: 0 }}
                rules={[
                  { required: true, whitespace: true, message: reviewText.answerReasonRequired },
                ]}
              >
                <Input.TextArea maxLength={2000} autoSize={{ minRows: 2, maxRows: 6 }} />
              </Form.Item>
            </Card>
          ))}
        </Space>
      ),
    },
    {
      key: 'attest',
      title: reviewText.stepAttest,
      fields: ['reference', 'complete', 'reason'],
      content: (
        <>
          <Form.Item
            name="reference"
            label={t('meaningReference')}
            rules={[{ required: true, whitespace: true, message: reviewText.referenceRequired }]}
          >
            <Input maxLength={512} />
          </Form.Item>
          <Form.Item
            name="complete"
            valuePropName="checked"
            rules={[
              {
                validator: (_, value: boolean | undefined) =>
                  value === true
                    ? Promise.resolve()
                    : Promise.reject(new Error(reviewText.completeRequired)),
              },
            ]}
          >
            <Checkbox>{t('meaningComplete')}</Checkbox>
          </Form.Item>
          <Form.Item
            name="reason"
            label={reviewText.reviewReason}
            rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
          >
            <Input.TextArea
              rows={3}
              maxLength={500}
              showCount
              placeholder={dialog.reasonPlaceholder}
            />
          </Form.Item>
        </>
      ),
    },
  ];

  const submit = async (values: ReviewValues): Promise<SubmitOutcome> => {
    // The first step's rule refuses to go on without a qualified basis, so it is here.
    if (basis === undefined) {
      return { kind: 'malformed', detail: 'meaning review basis' };
    }
    const answers = basis.conditions.map((condition, index) => ({
      code: condition.code,
      state: values.answers?.[index]?.state ?? ('UNKNOWN' as const),
      reason: values.answers?.[index]?.reason ?? '',
    }));
    const outcome = await reviewAction(context, actionId, 'ATTESTED', values.reason ?? '', {
      model: 'LC_MEANING_REVIEW_1',
      basisDigest: basis.basisDigest,
      complete: values.complete === true,
      evidenceReference: values.reference ?? '',
      answers,
    });
    if (!outcome.ok) return outcome.failure;
    void message.success(reviewText.attested);
    onReviewed();
    return undefined;
  };

  return (
    <FormDrawer<ReviewValues>
      trigger={{ label: reviewText.start, type: 'primary' }}
      title={t('meaningReview')}
      steps={steps}
      submitText={reviewText.submit}
      onOpen={load}
      onSubmit={submit}
    />
  );
}

/** A current-evidence record: its state as a tag, the raw record folded away. */
function EvidenceCard({
  title,
  evidence,
}: {
  readonly title: string;
  readonly evidence: Readonly<Record<string, string>>;
}): React.JSX.Element {
  const state = evidence.state;
  const assessedAt = evidence.assessedAt;
  return (
    <section aria-label={title}>
      <Card size="small" title={title}>
        <Space orientation="vertical" size="small" style={{ width: '100%' }}>
          {state === undefined ? (
            <Typography.Text type="secondary">{t('noEvidenceState')}</Typography.Text>
          ) : (
            <Code family="evidenceState" code={state} />
          )}
          {assessedAt !== undefined && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('assessedAt')} <When value={assessedAt} />
            </Typography.Text>
          )}
          {Object.keys(evidence).length > 0 && <TechnicalDetails data={evidence} />}
        </Space>
      </Card>
    </section>
  );
}
