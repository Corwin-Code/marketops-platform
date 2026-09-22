import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Checkbox, Col, Form, Input, Row, Select, Space } from 'antd';
import type { FormListFieldData } from 'antd';
import { useState } from 'react';
import type { ConsoleFailure, ConsoleOutcome } from '../api/console';
import type { PromotionTerms } from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { SectionCard } from '../ui';
import type { FieldName } from '../ui';
import { Hint, ListingProblem } from './ListingCommon';

interface TermRow {
  name?: string;
  value?: string;
}

/** The values of one declaration as the fields hold them. */
export interface PromotionTermsFieldValues {
  nativeKey?: string;
  termsKnown?: boolean;
  source?: string;
  freeze?: string;
  auto?: string;
  terms?: TermRow[];
  obligations?: TermRow[];
}

type TermsValues = PromotionTermsFieldValues;

const MAX_ROWS = 64;

const TERM_KEYS = [
  'nativeKey',
  'termsKnown',
  'source',
  'freeze',
  'auto',
  'terms',
  'obligations',
] as const;

/** A field name, nested under `prefix` when the fields sit inside a larger form. */
function nameOf(prefix: string | undefined, key: string): string | [string, string] {
  return prefix === undefined ? key : [prefix, key];
}

/** Starting values of the declaration fields. */
export function promotionTermsInitialValues(observation = false): PromotionTermsFieldValues {
  return {
    termsKnown: !observation,
    terms: [{ name: '', value: '' }],
    obligations: [{ name: '', value: '' }],
  };
}

/** Every field name of the declaration, for a drawer step that validates them. */
export function promotionTermsFieldNames(prefix?: string): FieldName[] {
  return TERM_KEYS.map((key) => nameOf(prefix, key));
}

/**
 * The declaration the fields describe, or `undefined` while it is unknown,
 * incomplete or has a duplicated name. Callers submit only a defined value.
 */
export function readPromotionTerms(
  values: PromotionTermsFieldValues | undefined,
  kind: string,
): PromotionTerms | undefined {
  if (values === undefined || values.termsKnown === false) return undefined;
  const terms = rowsOf(values.terms);
  const obligations = rowsOf(values.obligations);
  const complete = [terms, obligations].every((rows) =>
    rows.every((entry) => entry.name.trim() !== '' && entry.value.trim() !== ''),
  );
  const nativeKey = values.nativeKey ?? '';
  const source = values.source ?? '';
  if (
    !complete ||
    hasDuplicate(terms) ||
    hasDuplicate(obligations) ||
    nativeKey.trim() === '' ||
    source.trim() === '' ||
    (values.freeze ?? '') === '' ||
    (values.auto ?? '') === ''
  ) {
    return undefined;
  }
  return {
    engagementKind: kind,
    nativePromotionKey: nativeKey,
    termsEvidenceReference: source,
    priceFreeze: values.freeze === 'yes',
    autoParticipation: values.auto === 'yes',
    terms: Object.fromEntries(terms.map((entry) => [entry.name, entry.value])),
    obligations: Object.fromEntries(obligations.map((entry) => [entry.name, entry.value])),
  };
}

function rowsOf(rows: TermRow[] | undefined): { name: string; value: string }[] {
  return (rows ?? []).map((row) => ({ name: row.name ?? '', value: row.value ?? '' }));
}

function hasDuplicate(rows: { name: string }[]): boolean {
  return new Set(rows.map((r) => r.name)).size !== rows.length;
}

/** Name/value rows of one declaration map, edited as a list. */
function TermRows({
  listName,
  title,
}: {
  readonly listName: string | [string, string];
  readonly title: string;
}): React.JSX.Element {
  return (
    <Card size="small" type="inner" title={title}>
      <Form.List name={listName}>
        {(fields: FormListFieldData[], { add, remove }) => (
          <>
            {fields.map((field, index) => (
              <Row key={field.key} gutter={8} align="top">
                <Col xs={24} md={9}>
                  <Form.Item
                    name={[field.name, 'name']}
                    label={`${t('promotionFieldName')} ${String(index + 1)}`}
                    rules={[
                      { required: true, whitespace: true, message: '请填写条款名称' },
                      ({ getFieldValue }) => ({
                        validator: (_, value: string | undefined) => {
                          const all = rowsOf(getFieldValue(listName) as TermRow[] | undefined);
                          const same = all.filter((row) => row.name === (value ?? '')).length;
                          return same > 1
                            ? Promise.reject(new Error(t('promotionDuplicateTerm')))
                            : Promise.resolve();
                        },
                      }),
                    ]}
                  >
                    <Input maxLength={128} />
                  </Form.Item>
                </Col>
                <Col xs={22} md={14}>
                  <Form.Item
                    name={[field.name, 'value']}
                    label={`${t('promotionFieldValue')} ${String(index + 1)}`}
                    rules={[{ required: true, whitespace: true, message: '请填写条款原值' }]}
                  >
                    <Input maxLength={512} />
                  </Form.Item>
                </Col>
                <Col xs={2} md={1} style={{ paddingTop: 34 }}>
                  {fields.length > 1 && (
                    <MinusCircleOutlined
                      aria-label={t('removeRow')}
                      onClick={() => {
                        remove(field.name);
                      }}
                    />
                  )}
                </Col>
              </Row>
            ))}
            <Button
              type="dashed"
              icon={<PlusOutlined />}
              disabled={fields.length >= MAX_ROWS}
              onClick={() => {
                add({ name: '', value: '' });
              }}
            >
              {t('promotionAddTerm')}
            </Button>
          </>
        )}
      </Form.List>
    </Card>
  );
}

/**
 * The declaration fields alone, to sit inside a larger form such as a drawer
 * step. With `prefix`, every value lives under that key of the outer form.
 */
export function PromotionTermsFields({
  prefix,
  observation = false,
}: {
  readonly prefix?: string;
  readonly observation?: boolean;
}): React.JSX.Element {
  const form = Form.useFormInstance();
  const watch = (key: string): unknown => Form.useWatch(nameOf(prefix, key), form);
  const termsKnown = (watch('termsKnown') as boolean | undefined) ?? !observation;
  const duplicate = [
    rowsOf(watch('terms') as TermRow[] | undefined),
    rowsOf(watch('obligations') as TermRow[] | undefined),
  ].some(hasDuplicate);
  const yesNo = [
    { value: 'yes', label: t('yes') },
    { value: 'no', label: t('no') },
  ];
  return (
    <>
      <Row gutter={16}>
        <Col xs={24} md={12}>
          <Form.Item
            name={nameOf(prefix, 'nativeKey')}
            label={t('promotionNativeKey')}
            rules={[{ required: true, message: '请填写平台促销标识' }]}
          >
            <Input maxLength={128} />
          </Form.Item>
        </Col>
        {observation && (
          <Col xs={24} md={12}>
            <Form.Item name={nameOf(prefix, 'termsKnown')} valuePropName="checked" label=" ">
              <Checkbox>{t('promotionTermsObserved')}</Checkbox>
            </Form.Item>
          </Col>
        )}
      </Row>
      {termsKnown && (
        <>
          <Form.Item
            name={nameOf(prefix, 'source')}
            label={t(observation ? 'promotionDeclarationSource' : 'evidence')}
            rules={[{ required: true, message: '请填写条款来源引用' }]}
          >
            <Input maxLength={512} />
          </Form.Item>
          <TermRows listName={nameOf(prefix, 'terms')} title={t('promotionTerms')} />
          <TermRows listName={nameOf(prefix, 'obligations')} title={t('promotionObligations')} />
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name={nameOf(prefix, 'freeze')}
                label={t('promotionPriceFreeze')}
                rules={[{ required: true, message: '请选择是否锁定价格' }]}
              >
                <Select placeholder={t('undeclared')} options={yesNo} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name={nameOf(prefix, 'auto')}
                label={t('promotionAutoParticipation')}
                rules={[{ required: true, message: '请选择是否自动参与' }]}
              >
                <Select placeholder={t('undeclared')} options={yesNo} />
              </Form.Item>
            </Col>
          </Row>
        </>
      )}
      {termsKnown && duplicate && (
        <Alert role="alert" type="warning" showIcon title={t('promotionDuplicateTerm')} />
      )}
    </>
  );
}

/** One exact declaration form for preparation and independently entered observations. */
export function PromotionTermsForm({
  label,
  kind,
  onSave,
  onSaved,
  onUnknown,
  observation = false,
  heading,
  help,
  children,
  preparation,
}: {
  readonly label: string;
  readonly kind: string;
  readonly onSave: (terms: PromotionTerms) => Promise<ConsoleOutcome<string>>;
  readonly onSaved: (id: string) => void;
  readonly onUnknown?: (nativeKey: string) => Promise<ConsoleOutcome<string>>;
  readonly observation?: boolean;
  readonly heading?: string;
  readonly help?: string;
  readonly children?: React.ReactNode;
  readonly preparation?: (draft: PromotionTerms | undefined) => React.ReactNode;
}): React.JSX.Element {
  const [form] = Form.useForm<TermsValues>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [pending, setPending] = useState(false);
  const values = Form.useWatch([], form) as TermsValues | undefined;
  const termsKnown = values?.termsKnown ?? !observation;
  const duplicate = [rowsOf(values?.terms), rowsOf(values?.obligations)].some(hasDuplicate);
  const draft = readPromotionTerms(values, kind);
  return (
    <SectionCard
      title={heading ?? t(observation ? 'promotionObservation' : 'promotionDeclaration')}
    >
      <Hint>
        {help ?? t(observation ? 'promotionObservationHelp' : 'promotionDeclarationHelp')}
      </Hint>
      <Form<TermsValues>
        form={form}
        layout="vertical"
        aria-label={label}
        initialValues={promotionTermsInitialValues(observation)}
        onFinish={(values) => {
          const known = values.termsKnown ?? !observation;
          const rowsTerms = rowsOf(values.terms);
          const rowsObligations = rowsOf(values.obligations);
          const dup = hasDuplicate(rowsTerms) || hasDuplicate(rowsObligations);
          const freezeValue = values.freeze ?? '';
          const autoValue = values.auto ?? '';
          if (pending || (known && (dup || freezeValue === '' || autoValue === ''))) return;
          if (!known && onUnknown === undefined) return;
          setPending(true);
          const key = values.nativeKey ?? '';
          const saving = known
            ? onSave({
                engagementKind: kind,
                nativePromotionKey: key,
                termsEvidenceReference: values.source ?? '',
                priceFreeze: freezeValue === 'yes',
                autoParticipation: autoValue === 'yes',
                terms: Object.fromEntries(rowsTerms.map((r) => [r.name, r.value])),
                obligations: Object.fromEntries(rowsObligations.map((r) => [r.name, r.value])),
              })
            : onUnknown?.(key);
          void saving?.then((outcome) => {
            setPending(false);
            if (outcome.ok) {
              setFailure(undefined);
              onSaved(outcome.value);
            } else setFailure(outcome.failure);
          });
        }}
      >
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {children}
          {failure !== undefined && <ListingProblem failure={failure} />}
          <PromotionTermsFields observation={observation} />
          {preparation?.(draft)}
          <div>
            <Button
              type="primary"
              htmlType="submit"
              loading={pending}
              disabled={termsKnown && duplicate}
            >
              {t('submit')}
            </Button>
          </div>
        </Space>
      </Form>
    </SectionCard>
  );
}
