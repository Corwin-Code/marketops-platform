import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Checkbox, Col, Form, Input, Row, Select, Space } from 'antd';
import type { FormListFieldData } from 'antd';
import { useState } from 'react';
import type { ConsoleFailure, ConsoleOutcome } from '../api/console';
import type { PromotionTerms } from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { SectionCard } from '../ui';
import { Hint, ListingProblem } from './ListingCommon';

interface TermRow {
  name?: string;
  value?: string;
}

interface TermsValues {
  nativeKey?: string;
  termsKnown?: boolean;
  source?: string;
  freeze?: string;
  auto?: string;
  terms?: TermRow[];
  obligations?: TermRow[];
}

const MAX_ROWS = 64;

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
  readonly listName: 'terms' | 'obligations';
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
  const nativeKey = Form.useWatch('nativeKey', form) ?? '';
  const termsKnown = Form.useWatch('termsKnown', form) ?? !observation;
  const source = Form.useWatch('source', form) ?? '';
  const freeze = Form.useWatch('freeze', form) ?? '';
  const auto = Form.useWatch('auto', form) ?? '';
  const terms = rowsOf(Form.useWatch('terms', form) ?? [{}]);
  const obligations = rowsOf(Form.useWatch('obligations', form) ?? [{}]);
  const duplicate = [terms, obligations].some(hasDuplicate);
  const completeRows = [terms, obligations].every((rows) =>
    rows.every((entry) => entry.name.trim() !== '' && entry.value.trim() !== ''),
  );
  const draft: PromotionTerms | undefined =
    termsKnown &&
    !duplicate &&
    completeRows &&
    nativeKey.trim() !== '' &&
    source.trim() !== '' &&
    freeze !== '' &&
    auto !== ''
      ? {
          engagementKind: kind,
          nativePromotionKey: nativeKey,
          termsEvidenceReference: source,
          priceFreeze: freeze === 'yes',
          autoParticipation: auto === 'yes',
          terms: Object.fromEntries(terms.map((entry) => [entry.name, entry.value])),
          obligations: Object.fromEntries(obligations.map((entry) => [entry.name, entry.value])),
        }
      : undefined;
  const yesNo = [
    { value: 'yes', label: t('yes') },
    { value: 'no', label: t('no') },
  ];
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
        initialValues={{
          termsKnown: !observation,
          freeze: undefined,
          auto: undefined,
          terms: [{ name: '', value: '' }],
          obligations: [{ name: '', value: '' }],
        }}
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
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="nativeKey"
                label={t('promotionNativeKey')}
                rules={[{ required: true, message: '请填写平台促销标识' }]}
              >
                <Input maxLength={128} />
              </Form.Item>
            </Col>
            {observation && (
              <Col xs={24} md={12}>
                <Form.Item name="termsKnown" valuePropName="checked" label=" ">
                  <Checkbox>{t('promotionTermsObserved')}</Checkbox>
                </Form.Item>
              </Col>
            )}
          </Row>
          {termsKnown && (
            <>
              <Form.Item
                name="source"
                label={t(observation ? 'promotionDeclarationSource' : 'evidence')}
                rules={[{ required: true, message: '请填写条款来源引用' }]}
              >
                <Input maxLength={512} />
              </Form.Item>
              <TermRows listName="terms" title={t('promotionTerms')} />
              <TermRows listName="obligations" title={t('promotionObligations')} />
              <Row gutter={16}>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="freeze"
                    label={t('promotionPriceFreeze')}
                    rules={[{ required: true, message: '请选择是否锁定价格' }]}
                  >
                    <Select placeholder={t('undeclared')} options={yesNo} />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="auto"
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
