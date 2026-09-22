import { Alert, Card, Checkbox, Col, Form, Input, Row, Select, Space, Typography } from 'antd';
import type { FormRule } from 'antd';
import type { ConsoleRequest } from '../api/console';
import {
  recordDescriptionFact,
  recordDisplayFact,
  recordPromotionFact,
  type PromotionContextObservationInput,
  type PromotionTerms,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { recorderText as text } from '../i18n/zh/listingHealth';
import { ActionModal, FormDrawer, InfoTip } from '../ui';
import type { FieldName } from '../ui';
import { InstantField, codeOptions, codeText } from './ListingCommon';
import { promotionKindOptions } from './ListingPromotionTerms';
import {
  PromotionTermsFields,
  promotionTermsFieldNames,
  promotionTermsInitialValues,
  type PromotionTermsFieldValues,
} from './ListingPromotionTermsForm';

export { NativeScopeDrawer } from './ListingNativeScope';

/** What every recorder takes: its listing, whether it is open, and what to do next. */
interface RecorderProps {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly open: boolean;
  readonly onClose: () => void;
  /** Called with the new observation's number once it is saved. */
  readonly onRecorded: (observationId: string) => void;
}

type Kiz = 'undeclared' | 'yes' | 'no';

interface DescriptionValues {
  readonly text?: string;
  readonly kiz?: Kiz;
}

/** The Russian description as the seller's back office shows it, with the KIZ declaration. */
export function DescriptionRecorder({
  context,
  listingId,
  open,
  onClose,
  onRecorded,
}: RecorderProps): React.JSX.Element {
  return (
    <FormDrawer<DescriptionValues>
      open={open}
      onClose={onClose}
      title={
        <Space size={0}>
          {text.descriptionTitle}
          <InfoTip title={t('descriptionFactHelp')} />
        </Space>
      }
      size="large"
      submitText={text.submit}
      onSubmit={async (values) => {
        const kiz = values.kiz ?? 'undeclared';
        const outcome = await recordDescriptionFact(
          context,
          listingId,
          values.text ?? '',
          kiz === 'undeclared' ? undefined : kiz === 'yes',
          '',
        );
        if (!outcome.ok) return outcome.failure;
        onRecorded(outcome.value);
        return undefined;
      }}
    >
      <Form.Item
        name="text"
        label={t('observedDescription')}
        rules={[{ required: true, whitespace: true, message: text.descriptionTextRequired }]}
      >
        <Input.TextArea lang="ru" autoSize={{ minRows: 8, maxRows: 24 }} />
      </Form.Item>
      <Form.Item
        name="kiz"
        label={t('kiz')}
        rules={[{ required: true, message: text.kizRequired }]}
      >
        <Select<Kiz>
          style={{ maxWidth: 240 }}
          options={[
            { value: 'undeclared', label: t('undeclared') },
            { value: 'yes', label: t('yes') },
            { value: 'no', label: t('no') },
          ]}
        />
      </Form.Item>
    </FormDrawer>
  );
}

interface DisplayValues {
  readonly displayState?: string;
  readonly displayedText?: string;
  readonly evidenceReference?: string;
}

/** What a buyer actually sees, with the evidence it was seen by. */
export function DisplayRecorder({
  context,
  listingId,
  open,
  onClose,
  onRecorded,
}: RecorderProps): React.JSX.Element {
  return (
    <ActionModal<DisplayValues>
      open={open}
      onClose={onClose}
      title={text.displayTitle}
      consequence={text.displayConsequence}
      okText={text.submit}
      width={640}
      initialValues={{ displayState: 'DISPLAYED' }}
      onSubmit={async (values) => {
        const outcome = await recordDisplayFact(
          context,
          listingId,
          values.displayState ?? '',
          values.displayedText ?? '',
          values.evidenceReference ?? '',
        );
        if (!outcome.ok) return outcome.failure;
        onRecorded(outcome.value);
        return undefined;
      }}
    >
      <Form.Item
        name="displayState"
        label={t('displayStateLabel')}
        rules={[{ required: true, message: text.displayStateRequired }]}
      >
        <Select options={codeOptions('displayState', ['DISPLAYED', 'NOT_DISPLAYED', 'UNKNOWN'])} />
      </Form.Item>
      <Form.Item name="displayedText" label={t('displayedText')}>
        <Input.TextArea lang="ru" autoSize={{ minRows: 2, maxRows: 8 }} />
      </Form.Item>
      <Form.Item
        name="evidenceReference"
        label={t('evidence')}
        rules={[{ required: true, whitespace: true, message: text.evidenceRequired }]}
      >
        <Input maxLength={512} />
      </Form.Item>
    </ActionModal>
  );
}

const PROMOTION_AXIS_CODES = [
  'CONCURRENT_LISTINGS',
  'AFFECTED_VARIANTS',
  'REVENUE_EXPOSURE',
  'CATEGORY_SHARE',
] as const;
type PromotionAxisCode = (typeof PROMOTION_AXIS_CODES)[number];
const AXIS_PARTS = ['value', 'unitCode', 'evidenceReference'] as const;
type AxisPart = (typeof AXIS_PARTS)[number];
type AxisDraft = Partial<Record<AxisPart, string>>;

interface PromotionObservationValues {
  readonly kind?: string;
  readonly participation?: string;
  readonly observedAt?: string;
  readonly reference?: string;
  readonly declaration?: PromotionTermsFieldValues;
  readonly completeContext?: boolean;
  readonly coverageStart?: string;
  readonly coverageEnd?: string;
  readonly verificationExpiresAt?: string;
  readonly effectiveFrom?: string;
  readonly effectiveTo?: string;
  readonly newTransactions?: string;
  readonly residual?: string;
  readonly authority?: string;
  readonly authorityUntil?: string;
  readonly axes?: Partial<Record<PromotionAxisCode, AxisDraft>>;
}

const ACTIVITY_FIELDS: readonly FieldName[] = ['kind', 'participation', 'observedAt', 'reference'];
const CONTEXT_FIELDS: readonly FieldName[] = [
  'completeContext',
  'coverageStart',
  'coverageEnd',
  'verificationExpiresAt',
  'effectiveFrom',
  'effectiveTo',
  'newTransactions',
  'residual',
  'authority',
  'authorityUntil',
  'axes',
];

/** The declaration as the fields hold it; only called once the fields have validated. */
function declarationOf(values: PromotionTermsFieldValues, kind: string): PromotionTerms {
  const rows = (list: PromotionTermsFieldValues['terms']): [string, string][] =>
    (list ?? []).map((row) => [row.name ?? '', row.value ?? '']);
  return {
    engagementKind: kind,
    nativePromotionKey: values.nativeKey ?? '',
    termsEvidenceReference: values.source ?? '',
    priceFreeze: values.freeze === 'yes',
    autoParticipation: values.auto === 'yes',
    terms: Object.fromEntries(rows(values.terms)),
    obligations: Object.fromEntries(rows(values.obligations)),
  };
}

/** A later instant than the field `earlierName`, or `message` when it is not. */
function laterThan(earlierName: string, message: string): FormRule {
  return ({ getFieldValue }) => ({
    validator: (_, value: string | undefined) => {
      const earlier: unknown = getFieldValue(earlierName);
      if (value === undefined || value === '' || typeof earlier !== 'string' || earlier === '') {
        return Promise.resolve();
      }
      return Date.parse(value) > Date.parse(earlier)
        ? Promise.resolve()
        : Promise.reject(new Error(message));
    },
  });
}

const requiredTime: FormRule = {
  required: true,
  whitespace: true,
  message: text.contextTimeRequired,
};
const requiredState: FormRule = { required: true, message: text.contextStateRequired };

/** The complete-context fields, shown only when the source is a complete enumeration. */
function PromotionContextFields(): React.JSX.Element {
  const form = Form.useFormInstance<PromotionObservationValues>();
  const complete = Form.useWatch('completeContext', form) === true;
  const participating = Form.useWatch('participation', form) === 'PARTICIPATING';
  return (
    <>
      <Form.Item
        name="completeContext"
        valuePropName="checked"
        dependencies={[['declaration', 'termsKnown']]}
        rules={[
          ({ getFieldValue }) => ({
            validator: (_, value: boolean | undefined) =>
              value === true && getFieldValue(['declaration', 'termsKnown']) !== true
                ? Promise.reject(new Error(text.contextNeedsTerms))
                : Promise.resolve(),
          }),
        ]}
      >
        <Checkbox>{t('promotionContextComplete')}</Checkbox>
      </Form.Item>
      {complete && (
        <Card
          size="small"
          type="inner"
          title={
            <Space size={0}>
              {t('promotionContext')}
              <InfoTip long title={t('promotionContextHelp')} />
            </Space>
          }
        >
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="coverageStart"
                label={t('promotionCoverageStart')}
                rules={[requiredTime]}
              >
                <InstantField />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="coverageEnd"
                label={t('promotionCoverageEnd')}
                dependencies={['coverageStart']}
                rules={[requiredTime, laterThan('coverageStart', text.coverageOrder)]}
              >
                <InstantField />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="verificationExpiresAt"
                label={t('promotionVerificationExpires')}
                rules={[requiredTime]}
              >
                <InstantField />
              </Form.Item>
            </Col>
            <Col xs={24} md={12} />
            <Col xs={24} md={12}>
              <Form.Item
                name="effectiveFrom"
                label={t('promotionEffectiveFrom')}
                rules={[requiredTime]}
              >
                <InstantField />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="effectiveTo"
                label={t('promotionEffectiveTo')}
                dependencies={['effectiveFrom']}
                rules={[requiredTime, laterThan('effectiveFrom', text.effectiveOrder)]}
              >
                <InstantField />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="newTransactions"
                label={t('promotionNewTransactionsState')}
                rules={[requiredState]}
              >
                <Select
                  placeholder={t('undeclared')}
                  options={codeOptions('newTransactionsState', ['OPEN', 'STOPPED', 'UNKNOWN'])}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="residual"
                label={t('promotionResidualState')}
                rules={[requiredState]}
              >
                <Select
                  placeholder={t('undeclared')}
                  options={codeOptions('residualObligationState', [
                    'OUTSTANDING',
                    'CLEARED',
                    'UNKNOWN',
                  ])}
                />
              </Form.Item>
            </Col>
            {participating ? (
              <>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="authority"
                    label={t('promotionOriginalAuthority')}
                    rules={[{ required: true, whitespace: true, message: text.authorityRequired }]}
                  >
                    <Input maxLength={512} />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="authorityUntil"
                    label={t('promotionOriginalAuthorityUntil')}
                    rules={[
                      { required: true, whitespace: true, message: text.authorityUntilRequired },
                    ]}
                  >
                    <InstantField />
                  </Form.Item>
                </Col>
              </>
            ) : (
              <Col xs={24}>
                <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
                  {text.authorityOnlyParticipating}
                </Typography.Paragraph>
              </Col>
            )}
          </Row>
          <Typography.Title level={5} style={{ marginTop: 0 }}>
            {t('promotionAxisDemands')}
          </Typography.Title>
          {PROMOTION_AXIS_CODES.map((axis) => (
            <AxisRow key={axis} axis={axis} />
          ))}
        </Card>
      )}
    </>
  );
}

/** One demand axis: all three parts or none. */
function AxisRow({ axis }: { readonly axis: PromotionAxisCode }): React.JSX.Element {
  const path = (part: AxisPart): [string, string, string] => ['axes', axis, part];
  const rule: FormRule = ({ getFieldValue }) => ({
    validator: (_, value: string | undefined) => {
      const entered = AXIS_PARTS.some((other) => {
        const current: unknown = getFieldValue(path(other));
        return typeof current === 'string' && current.trim() !== '';
      });
      return entered && (value ?? '').trim() === ''
        ? Promise.reject(new Error(text.axisPartial))
        : Promise.resolve();
    },
  });
  const dependencies = (part: AxisPart): [string, string, string][] =>
    AXIS_PARTS.filter((other) => other !== part).map(path);
  return (
    <Row gutter={16} align="top">
      <Col xs={24} md={4} style={{ paddingTop: 34 }}>
        {codeText('allowanceAxis', axis)}
      </Col>
      <Col xs={24} md={5}>
        <Form.Item
          name={path('value')}
          label={t('promotionAxisValue')}
          dependencies={dependencies('value')}
          rules={[rule]}
        >
          <Input inputMode="decimal" maxLength={32} />
        </Form.Item>
      </Col>
      <Col xs={24} md={5}>
        <Form.Item
          name={path('unitCode')}
          label={t('promotionAxisUnit')}
          dependencies={dependencies('unitCode')}
          rules={[rule]}
        >
          <Input maxLength={32} />
        </Form.Item>
      </Col>
      <Col xs={24} md={10}>
        <Form.Item
          name={path('evidenceReference')}
          label={t('promotionAxisEvidence')}
          dependencies={dependencies('evidenceReference')}
          rules={[rule]}
        >
          <Input maxLength={512} />
        </Form.Item>
      </Col>
    </Row>
  );
}

/** The complete context as the backend takes it, or nothing when the source is a single activity. */
function contextOf(
  values: PromotionObservationValues,
  declaration: PromotionTerms | null,
  participation: string,
): PromotionContextObservationInput | undefined {
  if (values.completeContext !== true || declaration === null) return undefined;
  const participating = participation === 'PARTICIPATING';
  const authority = participating ? (values.authority ?? '') : '';
  const authorityUntil = participating ? (values.authorityUntil ?? '') : '';
  const axes = values.axes ?? {};
  return {
    coverageStart: values.coverageStart ?? '',
    coverageEnd: values.coverageEnd ?? '',
    verificationExpiresAt: values.verificationExpiresAt ?? '',
    records: [
      {
        declaration,
        participationState: participation,
        effectiveFrom: values.effectiveFrom ?? '',
        effectiveTo: values.effectiveTo ?? '',
        newTransactionsState: values.newTransactions ?? '',
        residualObligationState: values.residual ?? '',
        originalAuthorityReference: authority === '' ? null : authority,
        originalAuthorityValidUntil: authorityUntil === '' ? null : authorityUntil,
        axisDemands: Object.fromEntries(
          PROMOTION_AXIS_CODES.flatMap((axis) => {
            const draft = axes[axis];
            const value = draft?.value ?? '';
            return value.trim() === ''
              ? []
              : [
                  [
                    axis,
                    {
                      value,
                      unitCode: draft?.unitCode ?? '',
                      evidenceReference: draft?.evidenceReference ?? '',
                    },
                  ],
                ];
          }),
        ),
      },
    ],
  };
}

/**
 * A promotion participation observation in three steps: the activity as
 * observed, its exact terms when they were seen, and, when the source is a
 * complete enumeration, the whole context with its original authority and
 * demand axes. The record is a source fact that still needs independent
 * verification.
 */
export function PromotionObservationDrawer({
  context,
  listingId,
  open,
  onClose,
  onRecorded,
}: RecorderProps): React.JSX.Element {
  return (
    <FormDrawer<PromotionObservationValues>
      open={open}
      onClose={onClose}
      title={text.promotionTitle}
      size="large"
      intro={<Alert type="info" showIcon title={t('promotionObservationHelp')} />}
      submitText={text.submit}
      initialValues={{
        participation: 'UNKNOWN',
        completeContext: false,
        declaration: promotionTermsInitialValues(true),
      }}
      steps={[
        {
          key: 'activity',
          title: text.stepActivity,
          fields: ACTIVITY_FIELDS,
          content: (
            <Row gutter={16}>
              <Col xs={24} md={12}>
                <Form.Item
                  name="kind"
                  label={t('promotionKind')}
                  rules={[{ required: true, message: text.kindRequired }]}
                >
                  <Select placeholder={t('undeclared')} options={promotionKindOptions()} />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="participation" label={t('promotionParticipationState')}>
                  <Select
                    options={codeOptions('participationState', [
                      'UNKNOWN',
                      'PARTICIPATING',
                      'NOT_PARTICIPATING',
                    ])}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item
                  name="observedAt"
                  label={t('promotionObservedAt')}
                  rules={[{ required: true, whitespace: true, message: text.observedAtRequired }]}
                >
                  <InstantField />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item
                  name="reference"
                  label={t('promotionObservationReference')}
                  rules={[{ required: true, whitespace: true, message: text.referenceRequired }]}
                >
                  <Input maxLength={512} />
                </Form.Item>
              </Col>
            </Row>
          ),
        },
        {
          key: 'terms',
          title: text.stepTerms,
          fields: promotionTermsFieldNames('declaration'),
          content: <PromotionTermsFields prefix="declaration" observation />,
        },
        {
          key: 'context',
          title: text.stepContext,
          fields: CONTEXT_FIELDS,
          content: <PromotionContextFields />,
        },
      ]}
      onSubmit={async (values) => {
        const kind = values.kind ?? '';
        const participation = values.participation ?? 'UNKNOWN';
        const terms = values.declaration ?? {};
        const declaration = terms.termsKnown === true ? declarationOf(terms, kind) : null;
        const outcome = await recordPromotionFact(
          context,
          listingId,
          declaration,
          participation,
          values.observedAt ?? '',
          values.reference ?? '',
          { engagementKind: kind, nativePromotionKey: terms.nativeKey ?? '' },
          contextOf(values, declaration, participation),
        );
        if (!outcome.ok) return outcome.failure;
        onRecorded(outcome.value);
        return undefined;
      }}
    />
  );
}
