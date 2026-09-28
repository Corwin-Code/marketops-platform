import {
  Alert,
  App,
  Col,
  DatePicker,
  Flex,
  Form,
  Input,
  Radio,
  Row,
  Select,
  Typography,
} from 'antd';
import type { FormInstance } from 'antd';
import type { Dayjs } from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import type {
  AvailabilityCase,
  ExceptionOptions,
  ExceptionPreview,
  ExceptionScopeOption,
} from '../api/availability';
import { fetchExceptionOptions, previewException, requestException } from '../api/availability';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { formatMoney, STORE_TIMEZONE_LABEL, storeLocalToIso, toStoreDayjs } from '../format';
import { codeLabel } from '../i18n';
import {
  AUTHORITY_LEVEL_LABELS,
  EXCEPTION_REASON_LABELS,
  EXCEPTION_SCOPE_LABELS,
} from '../i18n/zh/availability';
import { exceptionRequestText as text } from '../i18n/zh/availabilityCases';
import { FailureAlert, FormDrawer, LoadingState } from '../ui';
import type { SubmitOutcome } from '../ui';
import { causeLabel, modeLabel, subjectChannelLabel } from './riskPresentation';

const PICKER_FORMAT = 'YYYY-MM-DD HH:mm';
const DECIMAL = /^\d{1,14}(\.\d{1,4})?$/;
const CURRENCY = /^[A-Z]{3}$/;
const LONG_TEXT = 2000;
const EVIDENCE_LIMIT = 512;
const PREVIEW_DELAY_MS = 400;

interface RequestValues {
  readonly scope?: string;
  readonly reasonCode?: string;
  readonly rationale?: string;
  readonly expectedConsequence?: string;
  readonly consequenceAmount?: string;
  readonly consequenceCurrency?: string;
  readonly effectiveFrom?: Dayjs | null;
  readonly expiresAt?: Dayjs | null;
  readonly reviewAt?: Dayjs | null;
  readonly evidenceReference?: string;
}

type Options =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly options: ExceptionOptions }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/**
 * A scope choice as `kind#reference`, so one radio value names both; `#` because
 * a channel reference already contains `|`.
 */
function scopeValue(option: ExceptionScopeOption): string {
  return `${option.scopeKind}#${option.reference}`;
}

/**
 * Ask the business to accept one case's calculated risk for a bounded period.
 *
 * Two steps: what is accepted and why, then for how long and on what evidence.
 * Every choice comes from the server — the scopes this case can name, the
 * closed reasons, the published maximum and thresholds — and the authority the
 * request would need is previewed live by the same rules the request is sized
 * by. With no materiality version in force the form says plainly that the
 * request will be recorded as 权限不足 and nobody will be able to approve it.
 */
export function ExceptionRequestDrawer({
  context,
  governed,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  /** The case, or nothing when the drawer is closed. */
  readonly governed: AvailabilityCase | undefined;
  readonly onClose: () => void;
  readonly onDone: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [options, setOptions] = useState<Options>({ kind: 'loading' });
  const caseId = governed?.id;

  useEffect(() => {
    if (caseId === undefined) return;
    let live = true;
    setOptions({ kind: 'loading' });
    void fetchExceptionOptions(context, caseId).then((outcome) => {
      if (!live) return;
      setOptions(
        outcome.ok
          ? { kind: 'loaded', options: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, caseId]);

  const loaded = options.kind === 'loaded' ? options.options : undefined;

  const submit = async (
    values: RequestValues,
    form: FormInstance<RequestValues>,
  ): Promise<SubmitOutcome> => {
    if (governed === undefined || loaded === undefined) return undefined;
    const scope = loaded.scopes.find((option) => scopeValue(option) === values.scope);
    const from = values.effectiveFrom;
    const until = values.expiresAt;
    const review = values.reviewAt;
    if (scope === undefined || !from || !until || !review) return undefined;
    const amount = (values.consequenceAmount ?? '').trim();
    const currency = (values.consequenceCurrency ?? '').trim();
    const outcome = await requestException(context, governed.id, {
      scopeKind: scope.scopeKind,
      scopeReference: scope.reference,
      reasonCode: values.reasonCode ?? '',
      rationale: (values.rationale ?? '').trim(),
      expectedConsequence: (values.expectedConsequence ?? '').trim(),
      consequenceAmount: amount === '' ? null : amount,
      consequenceCurrency: amount === '' ? null : currency,
      evidenceReference: (values.evidenceReference ?? '').trim(),
      effectiveFrom: storeLocalToIso(from),
      expiresAt: storeLocalToIso(until),
      reviewAt: storeLocalToIso(review),
    });
    if (!outcome.ok) {
      if (outcome.failure.kind === 'refused' && outcome.failure.code === PERIOD_CODE) {
        form.setFields([{ name: 'expiresAt', errors: [periodTooLong(loaded)] }]);
      }
      return outcome.failure;
    }
    void message.success(
      outcome.value.state === 'AUTHORITY_BLOCKED' ? text.submittedBlocked : text.submitted,
    );
    onDone();
    return undefined;
  };

  const intro = (
    <Flex vertical gap={8}>
      <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
        {text.intro}
      </Typography.Paragraph>
      {governed === undefined ? null : (
        <Typography.Text>
          {causeLabel(governed.causeCode)}
          {governed.subject === undefined ? null : (
            <Typography.Text type="secondary">
              {' · '}
              {subjectChannelLabel(governed.subject)}
            </Typography.Text>
          )}
        </Typography.Text>
      )}
      {options.kind === 'failed' && <FailureAlert failure={options.failure} />}
      {loaded !== undefined && !loaded.policyInForce && (
        <Alert type="warning" showIcon title={text.noPolicyTitle} description={text.noPolicyHelp} />
      )}
    </Flex>
  );

  const now = toStoreDayjs(new Date().toISOString());

  return (
    <FormDrawer<RequestValues>
      open={governed !== undefined}
      onClose={onClose}
      title={text.title}
      intro={intro}
      submitText={text.submit}
      {...(now === undefined ? {} : { initialValues: { effectiveFrom: now } })}
      steps={[
        {
          key: 'scope',
          title: text.stepScope,
          fields: [
            'scope',
            'reasonCode',
            'rationale',
            'expectedConsequence',
            'consequenceAmount',
            'consequenceCurrency',
          ],
          content:
            loaded === undefined ? (
              <>
                {options.kind === 'loading' ? (
                  <LoadingState />
                ) : (
                  <Typography.Text type="danger">{text.optionsUnavailable}</Typography.Text>
                )}
                {/* Without the server's scopes there is nothing to ask for: the
                    required scope keeps the form from moving on or submitting. */}
                <Form.Item
                  name="scope"
                  hidden
                  rules={[{ required: true, message: text.scopeRequired }]}
                >
                  <Input />
                </Form.Item>
              </>
            ) : (
              <ScopeStep options={loaded} />
            ),
        },
        {
          key: 'period',
          title: text.stepPeriod,
          fields: ['effectiveFrom', 'expiresAt', 'reviewAt', 'evidenceReference'],
          content:
            loaded === undefined || governed === undefined ? null : (
              <PeriodStep context={context} caseId={governed.id} options={loaded} />
            ),
        },
      ]}
      onSubmit={submit}
    />
  );
}

const PERIOD_CODE = 'EXCEPTION_PERIOD_EXCEEDS_MAXIMUM';

function periodTooLong(options: ExceptionOptions): string {
  return options.maxDurationDays === null
    ? text.periodTooLongUnknown
    : text.periodTooLong(options.maxDurationDays);
}

/** The first step: what is accepted, why, and what the business expects to lose. */
function ScopeStep({ options }: { readonly options: ExceptionOptions }): React.JSX.Element {
  const form = Form.useFormInstance<RequestValues>();
  // The options arrive after the form is built, so their defaults are filled in
  // here: the narrowest scope, and the currency the calculation speaks in.
  useEffect(() => {
    const first = options.scopes[0];
    const currency = options.profitAtRiskCurrency ?? options.materialCurrency;
    const current = form.getFieldsValue(['scope', 'consequenceCurrency']) as RequestValues;
    form.setFieldsValue({
      ...(current.scope === undefined && first !== undefined ? { scope: scopeValue(first) } : {}),
      ...(current.consequenceCurrency === undefined && currency !== null
        ? { consequenceCurrency: currency }
        : {}),
    });
  }, [form, options]);
  return (
    <>
      <Form.Item
        name="scope"
        label={text.scope}
        extra={text.scopeHelp}
        rules={[{ required: true, message: text.scopeRequired }]}
      >
        <Radio.Group style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {options.scopes.map((option) => (
            <Radio key={scopeValue(option)} value={scopeValue(option)}>
              <Typography.Text strong>
                {codeLabel(EXCEPTION_SCOPE_LABELS, option.scopeKind)}
              </Typography.Text>
              <Typography.Text type="secondary" style={{ marginInlineStart: 8 }}>
                {scopeDescription(option)}
              </Typography.Text>
            </Radio>
          ))}
        </Radio.Group>
      </Form.Item>
      <Form.Item
        name="reasonCode"
        label={text.reasonCode}
        rules={[{ required: true, message: text.reasonCodeRequired }]}
      >
        <Select
          options={options.reasonCodes.map((code) => ({
            value: code,
            label: codeLabel(EXCEPTION_REASON_LABELS, code),
          }))}
        />
      </Form.Item>
      <Form.Item
        name="rationale"
        label={text.rationale}
        rules={[{ required: true, whitespace: true, message: text.rationaleRequired }]}
      >
        <Input.TextArea
          rows={3}
          maxLength={LONG_TEXT}
          showCount
          placeholder={text.rationalePlaceholder}
        />
      </Form.Item>
      <Form.Item
        name="expectedConsequence"
        label={text.consequence}
        rules={[{ required: true, whitespace: true, message: text.consequenceRequired }]}
      >
        <Input.TextArea
          rows={3}
          maxLength={LONG_TEXT}
          showCount
          placeholder={text.consequencePlaceholder}
        />
      </Form.Item>
      <Row gutter={12}>
        <Col xs={24} md={16}>
          <Form.Item
            name="consequenceAmount"
            label={text.amount}
            {...(options.profitAtRiskAmount === null
              ? {}
              : {
                  extra: text.profitHint(
                    formatMoney(options.profitAtRiskAmount, options.profitAtRiskCurrency),
                  ),
                })}
            rules={[
              {
                validator: (_: unknown, value: string | undefined) =>
                  value === undefined || value.trim() === '' || DECIMAL.test(value.trim())
                    ? Promise.resolve()
                    : Promise.reject(new Error(text.amountInvalid)),
              },
            ]}
          >
            <Input inputMode="decimal" />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item
            name="consequenceCurrency"
            label={text.currency}
            dependencies={['consequenceAmount']}
            normalize={(value: string | undefined) => value?.toUpperCase()}
            rules={[
              ({ getFieldValue }) => ({
                validator: (_: unknown, value: string | undefined) => {
                  const amount = (getFieldValue('consequenceAmount') as string | undefined) ?? '';
                  const currency = value?.trim() ?? '';
                  if (amount.trim() === '') return Promise.resolve();
                  if (currency === '') return Promise.reject(new Error(text.amountCurrencyPair));
                  return CURRENCY.test(currency)
                    ? Promise.resolve()
                    : Promise.reject(new Error(text.currencyInvalid));
                },
              }),
            ]}
          >
            <Input maxLength={3} />
          </Form.Item>
        </Col>
      </Row>
    </>
  );
}

/** A scope in words, from its structured identity. */
function scopeDescription(option: ExceptionScopeOption): string {
  const variant = [option.displayName, option.skuCode === null ? null : `SKU ${option.skuCode}`]
    .filter((part): part is string => part !== null && part !== '')
    .join(' · ');
  switch (option.scopeKind) {
    case 'VARIANT':
      return variant === '' ? option.label : variant;
    case 'STORE':
      return (
        [option.storeCode, option.storeName]
          .filter((part): part is string => part !== null && part !== '')
          .join(' · ') || option.label
      );
    case 'CHANNEL':
      return [
        option.platformCode,
        option.platformSkuKey === null ? null : `SKU ${option.platformSkuKey}`,
        option.fulfillmentModeCode === null ? null : modeLabel(option.fulfillmentModeCode),
      ]
        .filter((part): part is string => part !== null && part !== '')
        .join(' · ');
    default:
      return [
        variant,
        option.storeCode === null
          ? null
          : subjectChannelLabel({
              childKind: 'CHANNEL',
              platformCode: option.platformCode,
              storeCode: option.storeCode,
              fulfillmentModeCode: option.fulfillmentModeCode,
            }),
      ]
        .filter((part): part is string => part !== null && part !== '')
        .join(' · ');
  }
}

/** The second step: the bounded period, the review date and the evidence. */
function PeriodStep({
  context,
  caseId,
  options,
}: {
  readonly context: ConsoleRequest;
  readonly caseId: string;
  readonly options: ExceptionOptions;
}): React.JSX.Element {
  const thresholds = [
    ...(options.severity === 'CRITICAL' ? [text.thresholdCritical] : []),
    ...(options.materialDurationDays === null
      ? []
      : [text.thresholdDuration(options.materialDurationDays)]),
    ...(options.materialProfitAtRisk === null
      ? []
      : [
          text.thresholdAmount(formatMoney(options.materialProfitAtRisk, options.materialCurrency)),
        ]),
    ...(options.repeatOccurrenceCount === null || options.repeatLookbackDays === null
      ? []
      : [text.thresholdRepeat(options.repeatOccurrenceCount, options.repeatLookbackDays)]),
  ];
  return (
    <>
      <Row gutter={12}>
        <Col xs={24} md={8}>
          <Form.Item
            name="effectiveFrom"
            label={`${text.effectiveFrom}（${STORE_TIMEZONE_LABEL}）`}
            rules={[{ required: true, message: text.effectiveFromRequired }]}
          >
            <DatePicker
              showTime={{ format: 'HH:mm' }}
              format={PICKER_FORMAT}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item
            name="expiresAt"
            label={`${text.expiresAt}（${STORE_TIMEZONE_LABEL}）`}
            dependencies={['effectiveFrom']}
            {...(options.maxDurationDays === null
              ? {}
              : { extra: text.maxDays(options.maxDurationDays) })}
            rules={[
              { required: true, message: text.expiresAtRequired },
              ({ getFieldValue }) => ({
                validator: (_: unknown, value: Dayjs | null | undefined) => {
                  const from = getFieldValue('effectiveFrom') as Dayjs | null | undefined;
                  if (!value || !from) return Promise.resolve();
                  if (!value.isAfter(from)) return Promise.reject(new Error(text.expiresAfterFrom));
                  if (
                    options.maxDurationDays !== null &&
                    value.diff(from, 'millisecond') > options.maxDurationDays * 86_400_000
                  ) {
                    return Promise.reject(new Error(periodTooLong(options)));
                  }
                  return Promise.resolve();
                },
              }),
            ]}
          >
            <DatePicker
              showTime={{ format: 'HH:mm' }}
              format={PICKER_FORMAT}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item
            name="reviewAt"
            label={`${text.reviewAt}（${STORE_TIMEZONE_LABEL}）`}
            dependencies={['effectiveFrom', 'expiresAt']}
            rules={[
              { required: true, message: text.reviewAtRequired },
              ({ getFieldValue }) => ({
                validator: (_: unknown, value: Dayjs | null | undefined) => {
                  const from = getFieldValue('effectiveFrom') as Dayjs | null | undefined;
                  const until = getFieldValue('expiresAt') as Dayjs | null | undefined;
                  if (!value || !from || !until) return Promise.resolve();
                  return value.isBefore(from) || value.isAfter(until)
                    ? Promise.reject(new Error(text.reviewBetween))
                    : Promise.resolve();
                },
              }),
            ]}
          >
            <DatePicker
              showTime={{ format: 'HH:mm' }}
              format={PICKER_FORMAT}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </Col>
      </Row>
      <Form.Item
        name="evidenceReference"
        label={text.evidence}
        rules={[{ required: true, whitespace: true, message: text.evidenceRequired }]}
      >
        <Input maxLength={EVIDENCE_LIMIT} placeholder={text.evidencePlaceholder} />
      </Form.Item>
      {options.policyInForce && thresholds.length > 0 && (
        <Flex vertical gap={2} style={{ marginBottom: 12 }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.thresholdsTitle}
          </Typography.Text>
          <ul style={{ margin: 0, paddingInlineStart: 20 }}>
            {thresholds.map((line) => (
              <li key={line}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {line}
                </Typography.Text>
              </li>
            ))}
          </ul>
          {options.occurrenceCount === null ? null : (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.occurrence(options.occurrenceCount)}
            </Typography.Text>
          )}
        </Flex>
      )}
      <RequiredAuthorityPreview context={context} caseId={caseId} options={options} />
    </>
  );
}

type Preview =
  | { readonly kind: 'pending' }
  | { readonly kind: 'checking' }
  | { readonly kind: 'ready'; readonly preview: ExceptionPreview }
  | { readonly kind: 'too-long' }
  | { readonly kind: 'failed' };

/**
 * The authority the request would need, asked of the server as the period and
 * exposure change. Nothing is recorded by asking.
 */
function RequiredAuthorityPreview({
  context,
  caseId,
  options,
}: {
  readonly context: ConsoleRequest;
  readonly caseId: string;
  readonly options: ExceptionOptions;
}): React.JSX.Element {
  const form = Form.useFormInstance<RequestValues>();
  const from = Form.useWatch('effectiveFrom', form);
  const until = Form.useWatch('expiresAt', form);
  const amount = Form.useWatch('consequenceAmount', form);
  const currency = Form.useWatch('consequenceCurrency', form);
  const [preview, setPreview] = useState<Preview>({ kind: 'pending' });
  const sequence = useRef(0);

  const fromIso = from ? storeLocalToIso(from) : undefined;
  const untilIso = until ? storeLocalToIso(until) : undefined;
  const trimmedAmount = (amount ?? '').trim();
  const trimmedCurrency = (currency ?? '').trim();
  const sizable =
    fromIso !== undefined &&
    untilIso !== undefined &&
    Date.parse(untilIso) > Date.parse(fromIso) &&
    (trimmedAmount === '' || (DECIMAL.test(trimmedAmount) && CURRENCY.test(trimmedCurrency)));

  useEffect(() => {
    if (!sizable) {
      setPreview({ kind: 'pending' });
      return;
    }
    const current = ++sequence.current;
    setPreview({ kind: 'checking' });
    const timer = setTimeout(() => {
      void previewException(context, caseId, {
        effectiveFrom: fromIso,
        expiresAt: untilIso,
        consequenceAmount: trimmedAmount === '' ? null : trimmedAmount,
        consequenceCurrency: trimmedAmount === '' ? null : trimmedCurrency,
      }).then((outcome) => {
        if (current !== sequence.current) return;
        if (outcome.ok) {
          setPreview({ kind: 'ready', preview: outcome.value });
        } else if (outcome.failure.kind === 'refused' && outcome.failure.code === PERIOD_CODE) {
          setPreview({ kind: 'too-long' });
        } else {
          setPreview({ kind: 'failed' });
        }
      });
    }, PREVIEW_DELAY_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [context, caseId, sizable, fromIso, untilIso, trimmedAmount, trimmedCurrency]);

  let body: React.ReactNode;
  switch (preview.kind) {
    case 'pending':
      body = <Typography.Text type="secondary">{text.previewPending}</Typography.Text>;
      break;
    case 'checking':
      body = <Typography.Text type="secondary">{text.previewChecking}</Typography.Text>;
      break;
    case 'too-long':
      body = <Typography.Text type="danger">{periodTooLong(options)}</Typography.Text>;
      break;
    case 'failed':
      body = <Typography.Text type="secondary">{text.previewFailed}</Typography.Text>;
      break;
    case 'ready':
      body = (
        <Flex vertical gap={2}>
          <Typography.Text strong>
            {text.previewLevel(
              codeLabel(AUTHORITY_LEVEL_LABELS, preview.preview.requiredAuthority),
            )}
          </Typography.Text>
          <Typography.Text type="secondary">
            {preview.preview.separationRequired ? text.separation : text.noSeparation}
          </Typography.Text>
          {preview.preview.policyInForce ? null : (
            <Typography.Text type="warning">{text.noPolicyTitle}</Typography.Text>
          )}
        </Flex>
      );
      break;
  }

  return (
    <Alert
      type={preview.kind === 'ready' && !preview.preview.policyInForce ? 'warning' : 'info'}
      showIcon
      title={text.previewTitle}
      description={body}
      data-state={preview.kind}
    />
  );
}
