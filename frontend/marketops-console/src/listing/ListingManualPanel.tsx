import { ReloadOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Col, Form, Input, Row, Select, Space, Timeline } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleOutcome, ConsoleRequest } from '../api/console';
import type { ManualPacket, PromotionEngagement, PromotionTerms } from '../api/listingConversion';
import {
  adoptEngagement,
  authorizeExit,
  fetchActionPackets,
  fetchEngagements,
  fetchMyPackets,
  issuePacket,
  releaseEngagement,
  reportPacket,
  verifyPacket,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { ConfirmButton, EmptyState, LoadingState, SectionCard, TechnicalDetails } from '../ui';
import {
  Code,
  Details,
  Hint,
  IdText,
  InstantPicker,
  ListingProblem,
  RussianText,
  Stack,
  SubTitle,
  When,
  codeOptions,
} from './ListingCommon';
import { PromotionTermsForm, promotionKindOptions } from './ListingPromotionTerms';

export interface ListingManualPanelProps {
  readonly context: ConsoleRequest;
}

/** A declaration map as label/value rows. */
function MapDetails({
  entries,
}: {
  readonly entries: Readonly<Record<string, string>>;
}): React.JSX.Element {
  const rows = Object.entries(entries);
  if (rows.length === 0) return <EmptyState description={t('nothing')} />;
  return (
    <Details
      column={{ xs: 1, md: 2 }}
      items={rows.map(([key, value]) => ({ key, label: key, children: value }))}
    />
  );
}

/**
 * The manual execution path and simple promotion engagements.
 *
 * A packet carries exactly the approved material; the executor reports, and a
 * different person verifies. An engagement is entered on the platform's own
 * console and only recorded, exited and released here.
 */
export function ListingManualPanel({ context }: ListingManualPanelProps): React.JSX.Element {
  const { message } = App.useApp();
  const [packets, setPackets] = useState<readonly ManualPacket[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [issueAction, setIssueAction] = useState('');
  const [issueExecutor, setIssueExecutor] = useState('');
  const [packetActionDraft, setPacketActionDraft] = useState('');
  const [packetAction, setPacketAction] = useState('');
  const [operationTime, setOperationTime] = useState('');
  const [reportState, setReportState] = useState('APPLIED');
  const [note, setNote] = useState('');
  const [basis, setBasis] = useState('INDEPENDENT_HUMAN');
  const [managementObservation, setManagementObservation] = useState('');
  const [displayObservation, setDisplayObservation] = useState('');
  const [promotionObservation, setPromotionObservation] = useState('');
  const [managementMatch, setManagementMatch] = useState('MATCHED_TARGET');
  const [displayState, setDisplayState] = useState('DISPLAYED');
  const [listingId, setListingId] = useState('');
  const [engagements, setEngagements] = useState<readonly PromotionEngagement[] | undefined>(
    undefined,
  );
  const [exitReason, setExitReason] = useState('OWNER_DECISION');
  const [exitAuthority, setExitAuthority] = useState('');
  const [exitEvidenceId, setExitEvidenceId] = useState('');
  const [releaseObservationId, setReleaseObservationId] = useState('');
  const [releaseEvidence, setReleaseEvidence] = useState('');
  const [adoptionKind, setAdoptionKind] = useState('');
  const [adoptionContextObservation, setAdoptionContextObservation] = useState('');
  const [adoptionAuthority, setAdoptionAuthority] = useState('');
  const [adoptionAuthorityUntil, setAdoptionAuthorityUntil] = useState('');
  const [adoptionResponsibleUser, setAdoptionResponsibleUser] = useState('');
  const [busy, setBusy] = useState<string | undefined>(undefined);

  useEffect(() => {
    let active = true;
    const request =
      packetAction === '' ? fetchMyPackets(context) : fetchActionPackets(context, packetAction);
    void request.then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setPackets(outcome.value);
        setFailure(undefined);
      } else {
        setPackets(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, generation, packetAction]);

  const settle = (outcome: { readonly ok: boolean; readonly failure?: ConsoleFailure }): void => {
    setBusy(undefined);
    if (outcome.ok) {
      void message.success(t('done'));
      setFailure(undefined);
      setGeneration((value) => value + 1);
    } else if (outcome.failure !== undefined) {
      setFailure(outcome.failure);
    }
  };
  const loadEngagements = (): void => {
    void fetchEngagements(context, listingId).then((outcome) => {
      if (outcome.ok) {
        setEngagements(outcome.value);
        setFailure(undefined);
      } else setFailure(outcome.failure);
    });
  };
  const adoptCurrentEngagement = (terms: PromotionTerms): Promise<ConsoleOutcome<string>> => {
    const authorityUntil = new Date(adoptionAuthorityUntil);
    if (
      listingId.trim() === '' ||
      adoptionKind === '' ||
      adoptionContextObservation.trim() === '' ||
      adoptionAuthority.trim() === '' ||
      !Number.isFinite(authorityUntil.valueOf()) ||
      adoptionResponsibleUser.trim() === ''
    ) {
      return Promise.resolve({
        ok: false,
        failure: {
          kind: 'refused',
          status: 400,
          detail: t('promotionAdoptionRequired'),
        },
      });
    }
    return adoptEngagement(
      context,
      listingId,
      terms,
      adoptionContextObservation,
      adoptionAuthority,
      authorityUntil.toISOString(),
      adoptionResponsibleUser,
    ).then((outcome): ConsoleOutcome<string> => {
      if (outcome.ok) return { ok: true, value: outcome.value.id };
      return outcome;
    });
  };

  return (
    <section aria-label={t('packets')} data-state={packets === undefined ? 'loading' : 'loaded'}>
      <Stack>
        {failure !== undefined && <ListingProblem failure={failure} />}
        <SectionCard
          title={t('packets')}
          extra={
            <Button
              icon={<ReloadOutlined />}
              onClick={() => {
                setGeneration((value) => value + 1);
              }}
            >
              {t('refresh')}
            </Button>
          }
        >
          <Stack>
            <Row gutter={24}>
              <Col xs={24} lg={12}>
                <SubTitle>{t('issuePacket')}</SubTitle>
                <Form
                  layout="vertical"
                  aria-label={t('executor')}
                  onFinish={() => {
                    setBusy('issue');
                    void issuePacket(context, issueAction, issueExecutor).then(settle);
                  }}
                >
                  <Form.Item label={t('actionIdInput')}>
                    <Input
                      value={issueAction}
                      onChange={(e) => {
                        setIssueAction(e.target.value);
                      }}
                    />
                  </Form.Item>
                  <Form.Item label={t('executorId')}>
                    <Input
                      value={issueExecutor}
                      onChange={(e) => {
                        setIssueExecutor(e.target.value);
                      }}
                    />
                  </Form.Item>
                  <Button type="primary" htmlType="submit" loading={busy === 'issue'}>
                    {t('issuePacket')}
                  </Button>
                </Form>
              </Col>
              <Col xs={24} lg={12}>
                <SubTitle>{t('packetLookup')}</SubTitle>
                <Hint>{t('packetLookupHelp')}</Hint>
                <Form
                  layout="vertical"
                  aria-label={t('packetLookup')}
                  onFinish={() => {
                    setPacketAction(packetActionDraft.trim());
                  }}
                >
                  <Form.Item label={t('actionIdInput')} required>
                    <Input
                      required
                      value={packetActionDraft}
                      onChange={(event) => {
                        setPacketActionDraft(event.target.value);
                      }}
                    />
                  </Form.Item>
                  <Space>
                    <Button type="primary" htmlType="submit">
                      {t('open')}
                    </Button>
                    {packetAction !== '' && (
                      <Button
                        onClick={() => {
                          setPacketActionDraft('');
                          setPacketAction('');
                        }}
                      >
                        {t('myPackets')}
                      </Button>
                    )}
                  </Space>
                </Form>
              </Col>
            </Row>
            {packets === undefined && failure === undefined && <LoadingState />}
            {packets?.length === 0 && <EmptyState description={t('noPackets')} />}
            {packets?.map((packet) => (
              <Card
                key={packet.id}
                size="small"
                data-packet={packet.id}
                data-packet-state={packet.state}
                title={
                  <Space wrap>
                    <span>{packet.nativeListingKey}</span>
                    <Code family="packetState" code={packet.state} />
                  </Space>
                }
                extra={
                  <Space size={4}>
                    {t('expires')}
                    <When value={packet.expiresAt} />
                  </Space>
                }
              >
                <Stack>
                  {packet.targetText !== undefined && <RussianText value={packet.targetText} />}
                  {(packet.reports.length > 0 || packet.verifications.length > 0) && (
                    <Timeline
                      items={[
                        ...packet.reports.map((report) => ({
                          key: report.id,
                          content: (
                            <Space size={6} wrap>
                              <span>{t('report')}</span>
                              <Code family="reportState" code={report.reportState} />
                              {report.note !== '' && <span>{report.note}</span>}
                            </Space>
                          ),
                        })),
                        ...packet.verifications.map((verification) => ({
                          key: verification.id,
                          content: (
                            <Space size={6} wrap>
                              <span>{t('verify')}</span>
                              <Code
                                family="verificationBasis"
                                code={verification.verificationBasis}
                              />
                              <Code family="managementMatch" code={verification.managementMatch} />
                              <Code family="displayState" code={verification.displayState} />
                            </Space>
                          ),
                        })),
                      ]}
                    />
                  )}
                  {packet.state === 'ISSUED' && (
                    <Form
                      layout="vertical"
                      aria-label={`${t('report')} ${packet.id}`}
                      onFinish={() => {
                        setBusy(`report:${packet.id}`);
                        void reportPacket(
                          context,
                          packet.id,
                          operationTime,
                          reportState,
                          note,
                        ).then(settle);
                      }}
                    >
                      <Row gutter={16}>
                        <Col xs={24} md={8}>
                          <Form.Item label={t('operationTime')}>
                            <InstantPicker value={operationTime} onChange={setOperationTime} />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={6}>
                          <Form.Item label={t('reportStateLabel')}>
                            <Select
                              value={reportState}
                              onChange={setReportState}
                              options={codeOptions('reportState', [
                                'APPLIED',
                                'NOT_APPLIED',
                                'PARTIAL',
                              ])}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={10}>
                          <Form.Item label={t('note')}>
                            <Input
                              value={note}
                              onChange={(e) => {
                                setNote(e.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Button
                        type="primary"
                        htmlType="submit"
                        loading={busy === `report:${packet.id}`}
                      >
                        {t('report')}
                      </Button>
                    </Form>
                  )}
                  {packet.state === 'REPORTED' && (
                    <Form
                      layout="vertical"
                      aria-label={`${t('verify')} ${packet.id}`}
                      onFinish={() => {
                        setBusy(`verify:${packet.id}`);
                        void verifyPacket(
                          context,
                          packet.id,
                          basis,
                          managementMatch,
                          displayState,
                          note,
                          {
                            ...(managementObservation === ''
                              ? {}
                              : { managementObservationId: managementObservation }),
                            ...(displayObservation === ''
                              ? {}
                              : { displayObservationId: displayObservation }),
                            ...(promotionObservation === ''
                              ? {}
                              : { promotionObservationId: promotionObservation }),
                          },
                        ).then(settle);
                      }}
                    >
                      <Hint>{t('promotionVerificationExtent')}</Hint>
                      <Row gutter={16}>
                        <Col xs={24} md={8}>
                          <Form.Item label={t('descriptionObservationId')}>
                            <Input
                              value={managementObservation}
                              onChange={(e) => {
                                setManagementObservation(e.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                          <Form.Item label={t('displayObservationId')}>
                            <Input
                              value={displayObservation}
                              onChange={(e) => {
                                setDisplayObservation(e.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                          <Form.Item label={t('promotionObservationId')}>
                            <Input
                              value={promotionObservation}
                              onChange={(e) => {
                                setPromotionObservation(e.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                          <Form.Item label={t('verificationBasisLabel')}>
                            <Select
                              value={basis}
                              onChange={setBasis}
                              options={codeOptions('verificationBasis', [
                                'INDEPENDENT_HUMAN',
                                'OFFICIAL_EVIDENCE',
                              ])}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                          <Form.Item label={t('managementMatchLabel')}>
                            <Select
                              value={managementMatch}
                              onChange={setManagementMatch}
                              options={codeOptions('managementMatch', [
                                'MATCHED_TARGET',
                                'MATCHED_PRIOR',
                                'DIFFERENT',
                                'UNKNOWN',
                              ])}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                          <Form.Item label={t('displayStateLabel')}>
                            <Select
                              value={displayState}
                              onChange={setDisplayState}
                              options={codeOptions('displayState', [
                                'DISPLAYED',
                                'NOT_DISPLAYED',
                                'UNKNOWN',
                              ])}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24}>
                          <Form.Item label={t('note')}>
                            <Input
                              value={note}
                              onChange={(e) => {
                                setNote(e.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Button
                        type="primary"
                        htmlType="submit"
                        loading={busy === `verify:${packet.id}`}
                      >
                        {t('verify')}
                      </Button>
                    </Form>
                  )}
                  <TechnicalDetails>
                    <Space orientation="vertical" size={2}>
                      <IdText label={t('packetId')} value={packet.id} />
                      <IdText label={t('actionId')} value={packet.actionId} />
                      <IdText label={t('executor')} value={packet.executorUserId} />
                      {packet.reports.map((report) => (
                        <IdText
                          key={report.id}
                          label={t('reporter')}
                          value={report.reporterUserId}
                        />
                      ))}
                    </Space>
                  </TechnicalDetails>
                </Stack>
              </Card>
            ))}
          </Stack>
        </SectionCard>

        <SectionCard title={t('engagements')}>
          <Form
            layout="inline"
            aria-label={t('engagements')}
            onFinish={() => {
              loadEngagements();
            }}
          >
            <Form.Item label={t('listingIdInput')}>
              <Input
                style={{ width: 360 }}
                value={listingId}
                onChange={(e) => {
                  setListingId(e.target.value);
                }}
              />
            </Form.Item>
            <Button type="primary" htmlType="submit">
              {t('open')}
            </Button>
          </Form>
          <div style={{ height: 16 }} />
          {engagements?.length === 0 && <EmptyState description={t('noEngagements')} />}
          <Stack>
            {engagements?.map((engagement) => (
              <Card
                key={engagement.id}
                size="small"
                data-engagement={engagement.id}
                data-engagement-state={engagement.state}
                title={
                  <Space wrap>
                    <Code family="engagementKind" code={engagement.engagementKind} />
                    <Code family="engagementState" code={engagement.state} />
                    {engagement.exitReasonCode !== undefined && (
                      <Code family="exitReason" code={engagement.exitReasonCode} />
                    )}
                  </Space>
                }
              >
                <Stack>
                  {!engagement.fullDisclosure && (
                    <Alert type="info" showIcon title={t('promotionTermsRestricted')} />
                  )}
                  {engagement.fullDisclosure && (
                    <>
                      <SubTitle>{t('terms')}</SubTitle>
                      <MapDetails entries={engagement.terms} />
                      <SubTitle>{t('obligations')}</SubTitle>
                      <MapDetails entries={engagement.obligations} />
                      {engagement.termsEvidenceReference !== undefined && (
                        <IdText label={t('evidence')} value={engagement.termsEvidenceReference} />
                      )}
                    </>
                  )}
                  {engagement.state === 'ACTIVE' && (
                    <Card size="small" type="inner" title={t('exit')}>
                      <Row gutter={16}>
                        <Col xs={24} md={8}>
                          <Form.Item label={t('exitReasonLabel')} layout="vertical">
                            <Select
                              value={exitReason}
                              onChange={setExitReason}
                              options={codeOptions('exitReason', [
                                'MARGIN_BELOW_BOUND',
                                'RETURN_RATE_ABOVE_BOUND',
                                'SUPPLY_COVERAGE_LOST',
                                'PLATFORM_TERMS_CHANGED',
                                'OWNER_DECISION',
                              ])}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                          <Form.Item label={t('authorityReference')} layout="vertical">
                            <Input
                              value={exitAuthority}
                              onChange={(e) => {
                                setExitAuthority(e.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                          <Form.Item label={t('evidenceId')} layout="vertical">
                            <Input
                              value={exitEvidenceId}
                              onChange={(e) => {
                                setExitEvidenceId(e.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                      </Row>
                      <ConfirmButton
                        danger
                        title="确认授权退出此促销？"
                        description="退出将停止新增交易；存量义务仍需清理后释放。"
                        onConfirm={() =>
                          authorizeExit(
                            context,
                            engagement.id,
                            exitReason,
                            exitAuthority,
                            exitEvidenceId,
                          ).then((outcome) => {
                            settle(outcome);
                            loadEngagements();
                          })
                        }
                      >
                        {t('exit')}
                      </ConfirmButton>
                    </Card>
                  )}
                  {(engagement.state === 'EXITING' || engagement.state === 'STOPPED') && (
                    <Card size="small" type="inner" title={t('release')}>
                      <Row gutter={16}>
                        <Col xs={24} md={12}>
                          <Form.Item label={t('observationId')} layout="vertical">
                            <Input
                              value={releaseObservationId}
                              onChange={(e) => {
                                setReleaseObservationId(e.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                          <Form.Item label={t('evidence')} layout="vertical">
                            <Input
                              value={releaseEvidence}
                              onChange={(e) => {
                                setReleaseEvidence(e.target.value);
                              }}
                            />
                          </Form.Item>
                        </Col>
                      </Row>
                      <ConfirmButton
                        type="primary"
                        title={
                          engagement.state === 'EXITING'
                            ? '确认新增交易已停止并释放？'
                            : '确认存量义务已清并释放？'
                        }
                        description="释放依据当前观测与证据，由后端核对。"
                        onConfirm={() =>
                          releaseEngagement(
                            context,
                            engagement.id,
                            engagement.state === 'EXITING'
                              ? 'NEW_TRANSACTIONS_STOPPED'
                              : 'OBLIGATIONS_CLEARED',
                            releaseObservationId,
                            releaseEvidence,
                          ).then((outcome) => {
                            settle(outcome);
                            loadEngagements();
                          })
                        }
                      >
                        {t('release')}
                      </ConfirmButton>
                    </Card>
                  )}
                  <TechnicalDetails>
                    <Space orientation="vertical" size={2}>
                      <IdText label={t('engagementId')} value={engagement.id} />
                      <IdText label={t('actionId')} value={engagement.actionId} />
                      <IdText
                        label={t('promotionNativeKey')}
                        value={engagement.nativePromotionKey}
                      />
                      <IdText
                        label={t('promotionContextObservationId')}
                        value={engagement.sourceContextObservationId}
                      />
                      <IdText
                        label={t('promotionOriginalAuthority')}
                        value={engagement.originalAuthorityReference}
                      />
                    </Space>
                  </TechnicalDetails>
                </Stack>
              </Card>
            ))}
          </Stack>
        </SectionCard>

        <PromotionTermsForm
          label={t('promotionAdoption')}
          kind={adoptionKind}
          heading={t('promotionAdoption')}
          help={t('promotionAdoptionHelp')}
          onSave={adoptCurrentEngagement}
          onSaved={loadEngagements}
        >
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item label={t('listingIdInput')} required>
                <Input
                  value={listingId}
                  onChange={(event) => {
                    setListingId(event.target.value);
                  }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label={t('promotionKind')} required>
                <Select
                  placeholder={t('undeclared')}
                  value={adoptionKind === '' ? undefined : adoptionKind}
                  options={promotionKindOptions()}
                  onChange={(next: string | undefined) => {
                    setAdoptionKind(next ?? '');
                  }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label={t('promotionContextObservationId')} required>
                <Input
                  value={adoptionContextObservation}
                  onChange={(event) => {
                    setAdoptionContextObservation(event.target.value);
                  }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label={t('promotionOriginalAuthority')} required>
                <Input
                  maxLength={512}
                  value={adoptionAuthority}
                  onChange={(event) => {
                    setAdoptionAuthority(event.target.value);
                  }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label={t('promotionOriginalAuthorityUntil')} required>
                <InstantPicker
                  value={adoptionAuthorityUntil}
                  onChange={setAdoptionAuthorityUntil}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label={t('promotionResponsibleUser')} required>
                <Input
                  value={adoptionResponsibleUser}
                  onChange={(event) => {
                    setAdoptionResponsibleUser(event.target.value);
                  }}
                />
              </Form.Item>
            </Col>
          </Row>
        </PromotionTermsForm>
      </Stack>
    </section>
  );
}
