import { Alert, Button, Card, Col, Form, Input, Row, Select, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { Candidate, ListingActionPurpose } from '../api/listingConversion';
import { fetchCandidates, prepareAction, prepareCandidate } from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { EmptyState, SectionCard } from '../ui';
import { Code, Hint, InstantPicker, ListingProblem, SubTitle, codeOptions } from './ListingCommon';
import { PromotionPreparationForm } from './ListingPromotionTerms';

type Kiz = 'undeclared' | 'yes' | 'no';

const CANDIDATE_KINDS = [
  'CONTENT_DESCRIPTION',
  'OFFICIAL_PROMOTION_PARTICIPATION',
  'SELLER_DIRECT_DISCOUNT',
];

const UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export interface CandidatePreparationProps {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly onPrepared: (actionId: string) => void;
}

export function CandidatePreparation({
  context,
  listingId,
  onPrepared,
}: CandidatePreparationProps): React.JSX.Element {
  const [candidates, setCandidates] = useState<readonly Candidate[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [kind, setKind] = useState('CONTENT_DESCRIPTION');
  const [roundKey, setRoundKey] = useState('round-1');
  const [evidence, setEvidence] = useState('');
  const [path, setPath] = useState('API');
  const [purpose, setPurpose] = useState<ListingActionPurpose>('LISTING_CONVERSION');
  const [purposeReference, setPurposeReference] = useState('');
  const [purposeUseConditions, setPurposeUseConditions] = useState('');
  const [purposeEndConditions, setPurposeEndConditions] = useState('');
  const [purposeUseUntil, setPurposeUseUntil] = useState('');
  const [targetText, setTargetText] = useState('');
  const [restoresCommandId, setRestoresCommandId] = useState('');
  const [kiz, setKiz] = useState<Kiz>('undeclared');
  const [busy, setBusy] = useState<string | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchCandidates(context, listingId).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setCandidates(outcome.value);
        setFailure(undefined);
      } else {
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, listingId, generation]);

  const restoring = restoresCommandId.trim() !== '';
  const restoreInvalid = restoring && !UUID_PATTERN.test(restoresCommandId);
  const basisMissing =
    purpose !== 'LISTING_CONVERSION' &&
    (purposeReference.trim() === '' ||
      purposeUseConditions.trim() === '' ||
      purposeEndConditions.trim() === '' ||
      (purpose === 'BOUNDED_EXPLORATION' && purposeUseUntil.trim() === ''));

  return (
    <section aria-label={t('candidates')} data-listing={listingId}>
      <SectionCard title={t('candidates')}>
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {failure !== undefined && <ListingProblem failure={failure} />}
          <Form
            layout="vertical"
            onFinish={() => {
              setBusy('candidate');
              void prepareCandidate(
                context,
                listingId,
                kind,
                roundKey,
                evidence === '' ? [] : [evidence],
              ).then((outcome) => {
                setBusy(undefined);
                if (outcome.ok) {
                  setGeneration((value) => value + 1);
                  setFailure(undefined);
                } else {
                  setFailure(outcome.failure);
                }
              });
            }}
          >
            <SubTitle>{t('newCandidate')}</SubTitle>
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item label={t('experienceCandidateKind')}>
                  <Select
                    value={kind}
                    onChange={setKind}
                    options={codeOptions('candidateKind', CANDIDATE_KINDS)}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item label={t('comparisonRound')}>
                  <Input
                    value={roundKey}
                    onChange={(e) => {
                      setRoundKey(e.target.value);
                    }}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item label={t('evidence')}>
                  <Input
                    value={evidence}
                    onChange={(e) => {
                      setEvidence(e.target.value);
                    }}
                  />
                </Form.Item>
              </Col>
            </Row>
            <Button type="primary" htmlType="submit" loading={busy === 'candidate'}>
              {t('createCandidate')}
            </Button>
          </Form>
          {candidates?.length === 0 && <EmptyState description={t('noCandidates')} />}
          {candidates?.map((candidate) => (
            <Card
              key={candidate.id}
              size="small"
              data-candidate={candidate.id}
              title={
                <Space wrap>
                  <Code family="candidateKind" code={candidate.candidateKind} />
                  <Typography.Text type="secondary">{candidate.comparisonRoundKey}</Typography.Text>
                  <Code family="candidateState" code={candidate.state} />
                </Space>
              }
            >
              {candidate.state === 'OPEN' && candidate.candidateKind !== 'CONTENT_DESCRIPTION' && (
                <PromotionPreparationForm
                  context={context}
                  candidateId={candidate.id}
                  kind={candidate.candidateKind}
                  onPrepared={onPrepared}
                />
              )}
              {candidate.state === 'OPEN' && candidate.candidateKind === 'CONTENT_DESCRIPTION' && (
                <Form
                  layout="vertical"
                  aria-label={candidate.id}
                  onFinish={() => {
                    setBusy(candidate.id);
                    void prepareAction(
                      context,
                      candidate.id,
                      candidate.candidateKind === 'CONTENT_DESCRIPTION' ? path : 'MANUAL',
                      targetText,
                      kiz === 'undeclared' ? undefined : kiz === 'yes',
                      restoresCommandId.trim() || undefined,
                      undefined,
                      purpose,
                      purpose === 'LISTING_CONVERSION'
                        ? undefined
                        : {
                            evidenceReference: purposeReference,
                            useConditions: purposeUseConditions
                              .split('\n')
                              .filter((value) => value.trim() !== ''),
                            endConditions: purposeEndConditions
                              .split('\n')
                              .filter((value) => value.trim() !== ''),
                            useUntil: purposeUseUntil.trim() || undefined,
                          },
                    ).then((outcome) => {
                      setBusy(undefined);
                      if (outcome.ok) {
                        onPrepared(outcome.value.id);
                      } else {
                        setFailure(outcome.failure);
                      }
                    });
                  }}
                >
                  <Row gutter={16}>
                    <Col xs={24} md={12}>
                      <Form.Item label={t('actionPurpose')} extra={t('purposeHelp')}>
                        <Select<ListingActionPurpose>
                          value={purpose}
                          options={codeOptions('actionPurpose', [
                            'LISTING_CONVERSION',
                            'DESCRIPTION_CORRECTION',
                            'BOUNDED_EXPLORATION',
                          ]).map((option) => ({
                            ...option,
                            value: option.value as ListingActionPurpose,
                          }))}
                          onChange={(selected) => {
                            setPurpose(selected);
                            if (selected === 'BOUNDED_EXPLORATION') setPath('MANUAL');
                          }}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={12}>
                      <Form.Item label={t('path')}>
                        <Select
                          value={path}
                          onChange={setPath}
                          options={[
                            {
                              value: 'API',
                              label: t('pathApi'),
                              disabled: purpose === 'BOUNDED_EXPLORATION',
                            },
                            { value: 'MANUAL', label: t('pathManual') },
                          ]}
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                  {purpose !== 'LISTING_CONVERSION' && (
                    <Card size="small" type="inner" title={t('purposeBasis')}>
                      <Row gutter={16}>
                        <Col xs={24} md={12}>
                          <Form.Item label={t('purposeEvidence')} required>
                            <Input
                              maxLength={512}
                              value={purposeReference}
                              onChange={(event) => {
                                setPurposeReference(event.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                          <Form.Item
                            label={t('purposeUseUntil')}
                            required={purpose === 'BOUNDED_EXPLORATION'}
                          >
                            <InstantPicker value={purposeUseUntil} onChange={setPurposeUseUntil} />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                          <Form.Item label={t('purposeUseConditions')} required>
                            <Input.TextArea
                              autoSize={{ minRows: 2, maxRows: 6 }}
                              value={purposeUseConditions}
                              onChange={(event) => {
                                setPurposeUseConditions(event.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                          <Form.Item label={t('purposeEndConditions')} required>
                            <Input.TextArea
                              autoSize={{ minRows: 2, maxRows: 6 }}
                              value={purposeEndConditions}
                              onChange={(event) => {
                                setPurposeEndConditions(event.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                      </Row>
                    </Card>
                  )}
                  <Form.Item
                    label={t('restoresCommandId')}
                    style={{ marginTop: 16 }}
                    {...(restoreInvalid
                      ? { validateStatus: 'error' as const, help: t('uuidInvalid') }
                      : {})}
                  >
                    <Input
                      value={restoresCommandId}
                      onChange={(e) => {
                        setRestoresCommandId(e.target.value);
                      }}
                      placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                    />
                  </Form.Item>
                  {restoring && (
                    <Alert
                      style={{ marginBottom: 16 }}
                      type="info"
                      showIcon
                      title={t('restorationApproval')}
                    />
                  )}
                  <Form.Item label={t('targetText')}>
                    <Input.TextArea
                      lang="ru"
                      disabled={restoring}
                      autoSize={{ minRows: 4, maxRows: 14 }}
                      value={targetText}
                      onChange={(e) => {
                        setTargetText(e.target.value);
                      }}
                    />
                  </Form.Item>
                  <Form.Item label={t('kiz')}>
                    <Select<Kiz>
                      value={kiz}
                      style={{ maxWidth: 240 }}
                      onChange={setKiz}
                      options={[
                        { value: 'undeclared', label: t('undeclared') },
                        { value: 'yes', label: t('yes') },
                        { value: 'no', label: t('no') },
                      ]}
                    />
                  </Form.Item>
                  <Hint>{t('exposureEvidence')}</Hint>
                  <Button
                    type="primary"
                    htmlType="submit"
                    loading={busy === candidate.id}
                    disabled={restoreInvalid || basisMissing}
                  >
                    {t('prepareAction')}
                  </Button>
                </Form>
              )}
            </Card>
          ))}
        </Space>
      </SectionCard>
    </section>
  );
}
