import {
  Alert,
  Button,
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
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  fetchMeaningReviewBasis,
  reviewAction,
  type MeaningAssessment,
  type MeaningReviewBasis,
} from '../api/listingConversion';
import { formatDecimal } from '../format';
import { t } from '../i18n/zh/listing';
import { ConfirmButton, Money, TechnicalDetails } from '../ui';
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

export function ListingMeaningReview({
  context,
  actionId,
  reason,
  onOutcome,
  approvalMode = false,
  onMaterialLoaded,
}: {
  readonly context: ConsoleRequest;
  readonly actionId: string;
  readonly reason: string;
  readonly onOutcome: (outcome: {
    readonly ok: boolean;
    readonly failure?: ConsoleFailure;
  }) => void;
  readonly approvalMode?: boolean;
  readonly onMaterialLoaded?: (basis: MeaningReviewBasis | undefined) => void;
}): React.JSX.Element {
  const epoch = useRef(0);
  const [basis, setBasis] = useState<MeaningReviewBasis>();
  const [answers, setAnswers] = useState<MeaningAssessment['answers']>([]);
  const [reference, setReference] = useState('');
  const [complete, setComplete] = useState(false);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    epoch.current += 1;
    setBasis(undefined);
    setAnswers([]);
    setReference('');
    setComplete(false);
    setPending(false);
    setFailure(undefined);
    onMaterialLoaded?.(undefined);
    return () => {
      epoch.current += 1;
    };
  }, [context, actionId, onMaterialLoaded]);
  const qualified = basis?.ruleState === 'QUALIFIED' && basis.conditions.length > 0;
  const ready =
    qualified &&
    complete &&
    reference.trim() !== '' &&
    reason.trim() !== '' &&
    answers.length === basis.conditions.length &&
    answers.every((a) => a.state !== 'UNKNOWN' && a.reason.trim() !== '');
  const notReadyReason = !qualified
    ? t('meaningUnqualified')
    : reason.trim() === ''
      ? t('meaningNeedReason')
      : t('meaningNeedAnswers');
  const loadLabel = approvalMode ? t('approvalMaterialLoad') : t('meaningLoad');
  return (
    <section aria-label={approvalMode ? t('approvalMaterial') : t('meaningLoad')}>
      <Card
        size="small"
        type="inner"
        title={approvalMode ? t('approvalMaterial') : t('meaningReview')}
      >
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          <Hint>{approvalMode ? t('approvalMaterialHelp') : t('meaningHelp')}</Hint>
          <div>
            <Button
              loading={pending && basis === undefined}
              disabled={pending}
              onClick={() => {
                const ticket = ++epoch.current;
                setPending(true);
                setBasis(undefined);
                setAnswers([]);
                setComplete(false);
                setReference('');
                setFailure(undefined);
                onMaterialLoaded?.(undefined);
                void fetchMeaningReviewBasis(context, actionId).then((outcome) => {
                  if (ticket !== epoch.current) return;
                  setPending(false);
                  if (outcome.ok) {
                    setBasis(outcome.value);
                    onMaterialLoaded?.(outcome.value);
                    setAnswers(
                      outcome.value.conditions.map((c) => ({
                        code: c.code,
                        state: 'UNKNOWN',
                        reason: '',
                      })),
                    );
                  } else {
                    setFailure(outcome.failure);
                    onMaterialLoaded?.(undefined);
                  }
                });
              }}
            >
              {loadLabel}
            </Button>
          </div>
          {failure !== undefined && <ListingProblem failure={failure} />}
          {basis !== undefined && (
            <>
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
                      <IdText
                        label={t('observationId')}
                        value={basis.currentDescription.observationId}
                      />
                      <IdText
                        label={t('currentDigest')}
                        value={basis.currentDescription.textDigest}
                      />
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
                        children: (
                          <Code family="reviewVerdict" code={basis.reviewEvidence.verdict} />
                        ),
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
                          <Code
                            family="materialityRoute"
                            code={basis.reviewEvidence.materialityRoute}
                          />
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
                  <EvidenceCard
                    title={t('currentCalibration')}
                    evidence={basis.calibrationEvidence}
                  />
                </Col>
                <Col xs={24} lg={8}>
                  <EvidenceCard
                    title={t('currentMateriality')}
                    evidence={basis.materialityEvidence}
                  />
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
                        render: (_, item) => (
                          <Code family="candidateKind" code={item.candidateKind} />
                        ),
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
              {basis.currentText !== undefined && (
                <div>
                  <SubTitle>{t('meaningCurrent')}</SubTitle>
                  <RussianText value={basis.currentText} />
                </div>
              )}
              {basis.targetText !== undefined && (
                <div>
                  <SubTitle>{t('meaningTarget')}</SubTitle>
                  <RussianText value={basis.targetText} />
                </div>
              )}
              {basis.promotionTerms !== undefined && (
                <PromotionTermsDetails terms={basis.promotionTerms} />
              )}
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
                            <Code
                              family="simulationState"
                              code={basis.selectedSimulation.inverseState}
                            />
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
                          scenario.missingInputs.length === 0
                            ? '—'
                            : scenario.missingInputs.join('、'),
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
              {!approvalMode &&
                basis.conditions.map((condition, index) => (
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
                    <Form layout="vertical" disabled={pending} component={false}>
                      <Form.Item label={t('meaningJudgement')}>
                        <Segmented<AnswerState>
                          aria-label={condition.code}
                          value={answers[index]?.state ?? 'UNKNOWN'}
                          options={(['UNKNOWN', 'APPLIES', 'DOES_NOT_APPLY'] as const).map(
                            (state) => ({ value: state, label: codeText('meaningAnswer', state) }),
                          )}
                          onChange={(state) => {
                            setAnswers(answers.map((a, i) => (i === index ? { ...a, state } : a)));
                          }}
                        />
                      </Form.Item>
                      <Form.Item
                        label={`${t('meaningReason')} ${String(index + 1)}`}
                        style={{ marginBottom: 0 }}
                      >
                        <Input.TextArea
                          maxLength={2000}
                          autoSize={{ minRows: 2, maxRows: 6 }}
                          value={answers[index]?.reason ?? ''}
                          onChange={(event) => {
                            setAnswers(
                              answers.map((a, i) =>
                                i === index ? { ...a, reason: event.target.value } : a,
                              ),
                            );
                          }}
                        />
                      </Form.Item>
                    </Form>
                  </Card>
                ))}
              {!approvalMode && (
                <Form layout="vertical" disabled={pending} component={false}>
                  <Form.Item label={t('meaningReference')}>
                    <Input
                      maxLength={512}
                      value={reference}
                      onChange={(event) => {
                        setReference(event.target.value);
                      }}
                    />
                  </Form.Item>
                  <Checkbox
                    disabled={pending}
                    checked={complete}
                    onChange={(event) => {
                      setComplete(event.target.checked);
                    }}
                  >
                    {t('meaningComplete')}
                  </Checkbox>
                </Form>
              )}
            </>
          )}
          {!approvalMode && (
            <div>
              <ConfirmButton
                type="primary"
                title="确认提交独立复核证明？"
                description="证明将绑定当前审核依据；之后仍需另行批准。"
                disabled={pending || !ready}
                {...(pending ? {} : ready ? {} : { disabledReason: notReadyReason })}
                onConfirm={() => {
                  if (!ready) return;
                  const ticket = ++epoch.current;
                  setPending(true);
                  void reviewAction(context, actionId, 'ATTESTED', reason, {
                    model: 'LC_MEANING_REVIEW_1',
                    basisDigest: basis.basisDigest,
                    complete,
                    evidenceReference: reference,
                    answers,
                  }).then((outcome) => {
                    if (ticket !== epoch.current) return;
                    setPending(false);
                    onOutcome(outcome);
                  });
                }}
              >
                {t('attest')}
              </ConfirmButton>
            </div>
          )}
        </Space>
      </Card>
    </section>
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
