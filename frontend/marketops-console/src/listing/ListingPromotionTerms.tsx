import { Alert, Card, Checkbox, Col, Form, Input, Row, Select, Space, Table } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleOutcome, ConsoleRequest } from '../api/console';
import {
  fetchPromotionTerms,
  recordPromotionFact,
  type PromotionContextObservationInput,
  type PromotionTerms,
  type PromotionTermsView,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { LoadingState } from '../ui';
import {
  Details,
  Hint,
  IdText,
  InstantPicker,
  ListingProblem,
  SubTitle,
  YesNo,
  codeOptions,
  codeText,
} from './ListingCommon';
import { PromotionTermsForm } from './ListingPromotionTermsForm';

export { PromotionTermsForm } from './ListingPromotionTermsForm';
export { PromotionPreparationDrawer } from './ListingPromotionPreparation';

const PROMOTION_AXIS_CODES = [
  'CONCURRENT_LISTINGS',
  'AFFECTED_VARIANTS',
  'REVENUE_EXPOSURE',
  'CATEGORY_SHARE',
] as const;
type PromotionAxisCode = (typeof PROMOTION_AXIS_CODES)[number];
interface PromotionAxisDraft {
  value: string;
  unitCode: string;
  evidenceReference: string;
}

function emptyPromotionAxes(): Record<PromotionAxisCode, PromotionAxisDraft> {
  return Object.fromEntries(
    PROMOTION_AXIS_CODES.map((axis) => [axis, { value: '', unitCode: '', evidenceReference: '' }]),
  ) as Record<PromotionAxisCode, PromotionAxisDraft>;
}

function instant(value: string): string | undefined {
  const parsed = new Date(value);
  return Number.isFinite(parsed.valueOf()) ? parsed.toISOString() : undefined;
}

const PROMOTION_KINDS = ['OFFICIAL_PROMOTION_PARTICIPATION', 'SELLER_DIRECT_DISCOUNT'];

/** The two promotion kinds, for a select. */
export function promotionKindOptions(): { value: string; label: string }[] {
  return PROMOTION_KINDS.map((code) => ({
    value: code,
    label: t(code === 'SELLER_DIRECT_DISCOUNT' ? 'promotionSellerKind' : 'promotionOfficialKind'),
  }));
}

export function PromotionObservationForm({
  context,
  listingId,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
}): React.JSX.Element {
  const [kind, setKind] = useState('');
  const [state, setState] = useState('UNKNOWN');
  const [observedAt, setObservedAt] = useState('');
  const [reference, setReference] = useState('');
  const [completeContext, setCompleteContext] = useState(false);
  const [coverageStart, setCoverageStart] = useState('');
  const [coverageEnd, setCoverageEnd] = useState('');
  const [verificationExpiresAt, setVerificationExpiresAt] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [effectiveTo, setEffectiveTo] = useState('');
  const [newTransactionsState, setNewTransactionsState] = useState('');
  const [residualObligationState, setResidualObligationState] = useState('');
  const [originalAuthorityReference, setOriginalAuthorityReference] = useState('');
  const [originalAuthorityValidUntil, setOriginalAuthorityValidUntil] = useState('');
  const [axisDrafts, setAxisDrafts] = useState(emptyPromotionAxes);
  const [saved, setSaved] = useState<string>();
  const save = (
    declaration: PromotionTerms | null,
    nativeKey: string,
  ): Promise<ConsoleOutcome<string>> => {
    setSaved(undefined);
    const time = new Date(observedAt);
    if (kind === '' || reference.trim() === '' || !Number.isFinite(time.valueOf())) {
      return Promise.resolve({
        ok: false,
        failure: {
          kind: 'refused',
          status: 400,
          detail: t('promotionObservationRequired'),
        },
      });
    }
    let promotionContext: PromotionContextObservationInput | undefined;
    if (completeContext) {
      const coverageStartValue = instant(coverageStart);
      const coverageEndValue = instant(coverageEnd);
      const verificationExpiresAtValue = instant(verificationExpiresAt);
      const effectiveFromValue = instant(effectiveFrom);
      const effectiveToValue = instant(effectiveTo);
      const authorityUntilValue = instant(originalAuthorityValidUntil);
      const partiallyEnteredAxis = Object.values(axisDrafts).some((axis) => {
        const entered = [axis.value, axis.unitCode, axis.evidenceReference].filter(
          (value) => value.trim() !== '',
        ).length;
        return entered > 0 && entered < 3;
      });
      const authorityInvalid =
        state === 'PARTICIPATING'
          ? originalAuthorityReference.trim() === '' || authorityUntilValue === undefined
          : originalAuthorityReference.trim() !== '' || originalAuthorityValidUntil !== '';
      if (
        declaration === null ||
        coverageStartValue === undefined ||
        coverageEndValue === undefined ||
        coverageStartValue >= coverageEndValue ||
        verificationExpiresAtValue === undefined ||
        effectiveFromValue === undefined ||
        effectiveToValue === undefined ||
        effectiveFromValue >= effectiveToValue ||
        newTransactionsState === '' ||
        residualObligationState === '' ||
        partiallyEnteredAxis ||
        authorityInvalid
      ) {
        return Promise.resolve({
          ok: false,
          failure: {
            kind: 'refused',
            status: 400,
            detail: t('promotionContextRequired'),
          },
        });
      }
      promotionContext = {
        coverageStart: coverageStartValue,
        coverageEnd: coverageEndValue,
        verificationExpiresAt: verificationExpiresAtValue,
        records: [
          {
            declaration,
            participationState: state,
            effectiveFrom: effectiveFromValue,
            effectiveTo: effectiveToValue,
            newTransactionsState,
            residualObligationState,
            originalAuthorityReference:
              originalAuthorityReference === '' ? null : originalAuthorityReference,
            originalAuthorityValidUntil: authorityUntilValue ?? null,
            axisDemands: Object.fromEntries(
              PROMOTION_AXIS_CODES.filter((axis) => axisDrafts[axis].value.trim() !== '').map(
                (axis) => [axis, axisDrafts[axis]],
              ),
            ),
          },
        ],
      };
    }
    return recordPromotionFact(
      context,
      listingId,
      declaration,
      state,
      time.toISOString(),
      reference,
      { engagementKind: kind, nativePromotionKey: nativeKey },
      promotionContext,
    );
  };
  const contextTimes: readonly [
    (
      | 'promotionCoverageStart'
      | 'promotionCoverageEnd'
      | 'promotionVerificationExpires'
      | 'promotionEffectiveFrom'
      | 'promotionEffectiveTo'
    ),
    string,
    (next: string) => void,
  ][] = [
    ['promotionCoverageStart', coverageStart, setCoverageStart],
    ['promotionCoverageEnd', coverageEnd, setCoverageEnd],
    ['promotionVerificationExpires', verificationExpiresAt, setVerificationExpiresAt],
    ['promotionEffectiveFrom', effectiveFrom, setEffectiveFrom],
    ['promotionEffectiveTo', effectiveTo, setEffectiveTo],
  ];
  return (
    <section>
      <PromotionTermsForm
        label={t('promotionObservation')}
        kind={kind}
        observation
        onSaved={setSaved}
        onSave={(declaration) => save(declaration, declaration.nativePromotionKey)}
        onUnknown={(nativeKey) => save(null, nativeKey)}
      >
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item label={t('promotionKind')} required>
              <Select
                placeholder={t('undeclared')}
                value={kind === '' ? undefined : kind}
                options={promotionKindOptions()}
                onChange={(next: string | undefined) => {
                  setKind(next ?? '');
                }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item label={t('promotionParticipationState')}>
              <Select
                value={state}
                options={codeOptions('participationState', [
                  'UNKNOWN',
                  'PARTICIPATING',
                  'NOT_PARTICIPATING',
                ])}
                onChange={(next: string) => {
                  setState(next);
                  if (next !== 'PARTICIPATING') {
                    setOriginalAuthorityReference('');
                    setOriginalAuthorityValidUntil('');
                  }
                }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item label={t('promotionObservedAt')} required>
              <InstantPicker value={observedAt} onChange={setObservedAt} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item label={t('promotionObservationReference')} required>
              <Input
                maxLength={512}
                value={reference}
                onChange={(e) => {
                  setReference(e.target.value);
                }}
              />
            </Form.Item>
          </Col>
        </Row>
        <Checkbox
          checked={completeContext}
          onChange={(event) => {
            setCompleteContext(event.target.checked);
          }}
        >
          {t('promotionContextComplete')}
        </Checkbox>
        {completeContext && (
          <Card size="small" type="inner" title={t('promotionContext')}>
            <Hint>{t('promotionContextHelp')}</Hint>
            <Row gutter={16}>
              {contextTimes.map(([key, value, setter]) => (
                <Col key={key} xs={24} md={8}>
                  <Form.Item label={t(key)} required>
                    <InstantPicker value={value} onChange={setter} />
                  </Form.Item>
                </Col>
              ))}
              <Col xs={24} md={8}>
                <Form.Item label={t('promotionNewTransactionsState')} required>
                  <Select
                    placeholder={t('undeclared')}
                    value={newTransactionsState === '' ? undefined : newTransactionsState}
                    options={codeOptions('newTransactionsState', ['OPEN', 'STOPPED', 'UNKNOWN'])}
                    onChange={(next: string | undefined) => {
                      setNewTransactionsState(next ?? '');
                    }}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item label={t('promotionResidualState')} required>
                  <Select
                    placeholder={t('undeclared')}
                    value={residualObligationState === '' ? undefined : residualObligationState}
                    options={codeOptions('residualObligationState', [
                      'OUTSTANDING',
                      'CLEARED',
                      'UNKNOWN',
                    ])}
                    onChange={(next: string | undefined) => {
                      setResidualObligationState(next ?? '');
                    }}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item
                  label={t('promotionOriginalAuthority')}
                  required={state === 'PARTICIPATING'}
                >
                  <Input
                    disabled={state !== 'PARTICIPATING'}
                    maxLength={512}
                    value={originalAuthorityReference}
                    onChange={(event) => {
                      setOriginalAuthorityReference(event.target.value);
                    }}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item
                  label={t('promotionOriginalAuthorityUntil')}
                  required={state === 'PARTICIPATING'}
                >
                  <InstantPicker
                    disabled={state !== 'PARTICIPATING'}
                    value={originalAuthorityValidUntil}
                    onChange={setOriginalAuthorityValidUntil}
                  />
                </Form.Item>
              </Col>
            </Row>
            <SubTitle>{t('promotionAxisDemands')}</SubTitle>
            {PROMOTION_AXIS_CODES.map((axis) => {
              const draft = axisDrafts[axis];
              const partlyEntered = [draft.value, draft.unitCode, draft.evidenceReference].some(
                (value) => value.trim() !== '',
              );
              const update = (change: Partial<PromotionAxisDraft>): void => {
                setAxisDrafts((current) => ({
                  ...current,
                  [axis]: { ...current[axis], ...change },
                }));
              };
              return (
                <Row key={axis} gutter={16} align="bottom">
                  <Col xs={24} md={4} style={{ paddingBottom: 30 }}>
                    {codeText('allowanceAxis', axis)}
                  </Col>
                  <Col xs={24} md={5}>
                    <Form.Item label={t('promotionAxisValue')} required={partlyEntered}>
                      <Input
                        inputMode="decimal"
                        maxLength={32}
                        value={draft.value}
                        onChange={(event) => {
                          update({ value: event.target.value });
                        }}
                      />
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={5}>
                    <Form.Item label={t('promotionAxisUnit')} required={partlyEntered}>
                      <Input
                        maxLength={32}
                        value={draft.unitCode}
                        onChange={(event) => {
                          update({ unitCode: event.target.value });
                        }}
                      />
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={10}>
                    <Form.Item label={t('promotionAxisEvidence')} required={partlyEntered}>
                      <Input
                        maxLength={512}
                        value={draft.evidenceReference}
                        onChange={(event) => {
                          update({ evidenceReference: event.target.value });
                        }}
                      />
                    </Form.Item>
                  </Col>
                </Row>
              );
            })}
          </Card>
        )}
      </PromotionTermsForm>
      {saved !== undefined && (
        <Alert
          role="status"
          type="success"
          showIcon
          title={t('promotionObservationSaved')}
          description={<IdText value={saved} />}
        />
      )}
    </section>
  );
}

/**
 * The exact terms bound to one promotion action, read as soon as it is shown.
 * Only a caller entitled to every affected product's terms sees them in full.
 */
export function PromotionDeclaration({
  context,
  actionId,
  digest,
}: {
  readonly context: ConsoleRequest;
  readonly actionId: string;
  readonly digest: string | undefined;
}): React.JSX.Element {
  const [response, setResponse] = useState<{
    context: ConsoleRequest;
    value: PromotionTermsView;
  }>();
  const [loading, setLoading] = useState(false);
  const requestSequence = useRef(0);
  const answer =
    response?.context === context && response.value.actionId === actionId
      ? response.value
      : undefined;
  const [failure, setFailure] = useState<ConsoleFailure>();
  const terms = answer?.terms;
  useEffect(() => {
    // Clear old disclosed terms before requesting the current authorization projection.
    setResponse(undefined);
    const sequence = ++requestSequence.current;
    setFailure(undefined);
    setLoading(true);
    void fetchPromotionTerms(context, actionId).then((outcome) => {
      if (sequence !== requestSequence.current) return;
      setLoading(false);
      if (outcome.ok) setResponse({ context, value: outcome.value });
      else setFailure(outcome.failure);
    });
    return () => {
      requestSequence.current += 1;
    };
  }, [context, actionId]);
  return (
    <section aria-label={t('promotionDeclaration')} data-state={loading ? 'loading' : 'loaded'}>
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        {digest === undefined ? (
          <Alert type="warning" showIcon title={t('promotionTermsMissing')} />
        ) : (
          <IdText label={t('promotionDeclarationIdentity')} value={digest} />
        )}
        {loading && <LoadingState rows={3} />}
        {failure !== undefined && <ListingProblem failure={failure} />}
        {answer !== undefined && !answer.fullDisclosure && (
          <Alert type="info" showIcon title={t('promotionTermsRestricted')} />
        )}
        {answer?.fullDisclosure && terms === undefined && (
          <Alert type="warning" showIcon title={t('promotionTermsMissing')} />
        )}
        {terms !== undefined && <PromotionTermsDetails terms={terms} />}
      </Space>
    </section>
  );
}

/** A declaration map as a two-column table. */
function TermsTable({
  title,
  entries,
}: {
  readonly title: string;
  readonly entries: Readonly<Record<string, string>>;
}): React.JSX.Element {
  return (
    <Table
      size="middle"
      bordered
      pagination={false}
      rowKey="name"
      title={() => title}
      dataSource={Object.entries(entries).map(([name, value]) => ({ name, value }))}
      columns={[
        { key: 'name', title: t('promotionFieldName'), dataIndex: 'name', width: '35%' },
        { key: 'value', title: t('promotionFieldValue'), dataIndex: 'value' },
      ]}
      locale={{ emptyText: t('nothing') }}
    />
  );
}

/** The same full declaration is used by viewing and structured independent review. */
export function PromotionTermsDetails({
  terms,
}: {
  readonly terms: PromotionTerms;
}): React.JSX.Element {
  return (
    <Space orientation="vertical" size="small" style={{ width: '100%' }}>
      <Details
        items={[
          {
            key: 'kind',
            label: t('promotionKind'),
            children: codeText('engagementKind', terms.engagementKind),
          },
          {
            key: 'native',
            label: t('promotionNativeKey'),
            children: terms.nativePromotionKey,
          },
          {
            key: 'evidence',
            label: t('evidence'),
            children: <IdText value={terms.termsEvidenceReference} />,
          },
          {
            key: 'freeze',
            label: t('promotionPriceFreeze'),
            children: <YesNo value={terms.priceFreeze} />,
          },
          {
            key: 'auto',
            label: t('promotionAutoParticipation'),
            children: <YesNo value={terms.autoParticipation} />,
          },
        ]}
      />
      <TermsTable title={t('promotionTerms')} entries={terms.terms} />
      <TermsTable title={t('promotionObligations')} entries={terms.obligations} />
    </Space>
  );
}
