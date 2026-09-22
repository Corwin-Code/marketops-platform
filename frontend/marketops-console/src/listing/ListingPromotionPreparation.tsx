import { MinusCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Col, Flex, Form, Input, Row, Select, Space, Typography } from 'antd';
import type { FormListFieldData } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  fetchCandidateSimulations,
  prepareAction,
  simulatePromotionCandidate,
  type Candidate,
  type ListingPurposeBasis,
  type PromotionSimulation,
} from '../api/listingConversion';
import { formatStoreTime } from '../format';
import { promotionPreparationText as text } from '../i18n/zh/listingActions';
import { t } from '../i18n/zh/listing';
import { FormDrawer, SectionCollapse, TriggerButton } from '../ui';
import type { FormDrawerStep, SubmitOutcome } from '../ui';
import { DecimalField, InstantField, isInstant } from './ListingActionFields';
import { Code, Hint, ListingProblem, codeOptions } from './ListingCommon';
import {
  PromotionTermsFields,
  promotionTermsFieldNames,
  promotionTermsInitialValues,
  readPromotionTerms,
} from './ListingPromotionTermsForm';
import type { PromotionTermsFieldValues } from './ListingPromotionTermsForm';

type PromotionPurpose = 'PROMOTION' | 'BOUNDED_EXPLORATION';

interface PromotionPreparationValues {
  readonly declaration?: PromotionTermsFieldValues;
  readonly purpose?: PromotionPurpose;
  readonly basisEvidence?: string;
  readonly useUntil?: string;
  readonly useCondition?: string;
  readonly endCondition?: string;
  readonly simulationId?: string;
}

const PREFIX = 'declaration';

/**
 * Preparing a promotion action from one open candidate, in three steps: the
 * exact terms, the purpose with its basis, and a qualified simulation of the
 * same purpose and terms. The action always runs by hand.
 */
export function PromotionPreparationDrawer({
  context,
  candidate,
  onClose,
  onPrepared,
}: {
  readonly context: ConsoleRequest;
  readonly candidate: Candidate;
  readonly onClose: () => void;
  readonly onPrepared: (actionId: string) => void;
}): React.JSX.Element {
  const kind = candidate.candidateKind;
  const steps: readonly FormDrawerStep[] = [
    {
      key: 'terms',
      title: text.stepTerms,
      fields: promotionTermsFieldNames(PREFIX),
      content: (
        <>
          <Hint>{t('promotionDeclarationHelp')}</Hint>
          <PromotionTermsFields prefix={PREFIX} />
        </>
      ),
    },
    {
      key: 'purpose',
      title: text.stepPurpose,
      fields: ['purpose', 'basisEvidence', 'useUntil', 'useCondition', 'endCondition'],
      content: <PurposeFields />,
    },
    {
      key: 'simulation',
      title: text.stepSimulation,
      fields: ['simulationId'],
      content: <SimulationStep context={context} candidateId={candidate.id} kind={kind} />,
    },
  ];

  const submit = async (values: PromotionPreparationValues): Promise<SubmitOutcome> => {
    const purpose = values.purpose ?? 'PROMOTION';
    // Every check readPromotionTerms makes is a rule of the first step, so the
    // declaration is defined here; the backend refuses an incomplete one anyway.
    const terms = readPromotionTerms(values.declaration, kind);
    const basis: ListingPurposeBasis | undefined =
      purpose === 'BOUNDED_EXPLORATION'
        ? {
            evidenceReference: values.basisEvidence ?? '',
            useConditions: [values.useCondition ?? ''],
            endConditions: [values.endCondition ?? ''],
            useUntil: values.useUntil,
          }
        : undefined;
    const outcome = await prepareAction(
      context,
      candidate.id,
      'MANUAL',
      '',
      undefined,
      undefined,
      terms,
      purpose,
      basis,
      values.simulationId,
    );
    if (!outcome.ok) return outcome.failure;
    onPrepared(outcome.value.id);
    return undefined;
  };

  return (
    <FormDrawer<PromotionPreparationValues>
      open
      onClose={onClose}
      title={
        <Space size={8} wrap>
          <span>{text.title}</span>
          <Code family="candidateKind" code={kind} />
          <Typography.Text type="secondary" style={{ fontWeight: 'normal' }}>
            {candidate.comparisonRoundKey}
          </Typography.Text>
        </Space>
      }
      initialValues={{ [PREFIX]: promotionTermsInitialValues(false), purpose: 'PROMOTION' }}
      steps={steps}
      submitText={text.submit}
      onSubmit={submit}
    />
  );
}

/** The purpose, and the basis a bounded exploration needs. */
function PurposeFields(): React.JSX.Element {
  const form = Form.useFormInstance<PromotionPreparationValues>();
  const purpose = Form.useWatch('purpose', form);
  return (
    <>
      <Form.Item
        name="purpose"
        label={t('actionPurpose')}
        rules={[{ required: true, message: text.purposeRequired }]}
      >
        <Select<PromotionPurpose>
          style={{ maxWidth: 320 }}
          options={codeOptions('actionPurpose', ['PROMOTION', 'BOUNDED_EXPLORATION']).map(
            (option) => ({ ...option, value: option.value as PromotionPurpose }),
          )}
        />
      </Form.Item>
      {purpose === 'BOUNDED_EXPLORATION' && (
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item
              name="basisEvidence"
              label={t('purposeEvidence')}
              rules={[{ required: true, whitespace: true, message: text.basisEvidenceRequired }]}
            >
              <Input maxLength={512} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="useUntil"
              label={t('purposeUseUntil')}
              rules={[{ required: true, message: text.useUntilRequired }]}
            >
              <InstantField ariaLabel={t('purposeUseUntil')} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="useCondition"
              label={t('purposeUseCondition')}
              rules={[{ required: true, whitespace: true, message: text.useConditionRequired }]}
            >
              <Input.TextArea maxLength={512} autoSize={{ minRows: 2, maxRows: 4 }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="endCondition"
              label={t('purposeEndCondition')}
              rules={[{ required: true, whitespace: true, message: text.endConditionRequired }]}
            >
              <Input.TextArea maxLength={512} autoSize={{ minRows: 2, maxRows: 4 }} />
            </Form.Item>
          </Col>
        </Row>
      )}
    </>
  );
}

interface SimulationValues {
  readonly listPrice?: string;
  readonly discount?: string;
  readonly unitCost?: string;
  readonly referenceProfit?: string;
  readonly platformFee?: string;
  readonly fixedFee?: string;
  readonly returnLoss?: string;
  readonly advertising?: string;
  readonly variableTax?: string;
  readonly currency?: string;
  readonly scenarios?: readonly { readonly name?: string; readonly value?: string }[];
  readonly periodStart?: string;
  readonly periodEnd?: string;
  readonly assumptions?: string;
  readonly sourceReference?: string;
}

const DECIMAL_FIELDS = [
  ['listPrice', 'simulationListPrice'],
  ['discount', 'simulationDiscount'],
  ['unitCost', 'simulationUnitCost'],
  ['referenceProfit', 'simulationReferenceProfit'],
  ['platformFee', 'simulationPlatformFee'],
  ['fixedFee', 'simulationFixedFee'],
  ['returnLoss', 'simulationReturnLoss'],
  ['advertising', 'simulationAdvertising'],
  ['variableTax', 'simulationVariableTax'],
] as const;

/**
 * Choosing a qualified simulation of this purpose and these terms. The list is
 * read when the drawer opens; changing the purpose or the terms clears the
 * choice, because a simulation is bound to both.
 */
function SimulationStep({
  context,
  candidateId,
  kind,
}: {
  readonly context: ConsoleRequest;
  readonly candidateId: string;
  readonly kind: string;
}): React.JSX.Element {
  const { message } = App.useApp();
  const form = Form.useFormInstance<PromotionPreparationValues>();
  const purpose = Form.useWatch('purpose', form) ?? 'PROMOTION';
  const declarationValues = Form.useWatch(PREFIX, form);
  const declaration = readPromotionTerms(declarationValues, kind);
  const declarationIdentity = JSON.stringify(declaration);
  const [simulations, setSimulations] = useState<readonly PromotionSimulation[] | undefined>(
    undefined,
  );
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [creating, setCreating] = useState(false);
  const [simulationForm] = Form.useForm<SimulationValues>();
  const epoch = useRef(0);

  const load = async (): Promise<void> => {
    const ticket = ++epoch.current;
    setSimulations(undefined);
    const outcome = await fetchCandidateSimulations(context, candidateId);
    if (ticket !== epoch.current) return;
    if (outcome.ok) {
      setSimulations(outcome.value);
      setFailure(undefined);
    } else {
      setSimulations([]);
      setFailure(outcome.failure);
    }
  };

  useEffect(() => {
    void load();
    return () => {
      epoch.current += 1;
    };
    // Read once per opening: the drawer mounts this step when it opens.
  }, [context, candidateId]);

  // A simulation is bound to its purpose and exact terms: a change clears the choice.
  useEffect(() => {
    form.setFieldValue('simulationId', undefined);
  }, [purpose, declarationIdentity, form]);

  const qualified = (simulations ?? []).filter(
    (value) =>
      value.purposeCode === purpose &&
      value.qualificationState === 'QUALIFIED_CONDITIONAL_ECONOMICS',
  );

  const create = async (): Promise<void> => {
    if (declaration === undefined) return;
    let values: SimulationValues;
    try {
      values = await simulationForm.validateFields();
    } catch {
      return;
    }
    const currency = (values.currency ?? 'RUB').toUpperCase();
    const money = (amount: string | undefined) =>
      amount === undefined || amount === '' ? null : { amount, currencyCode: currency };
    const optional = (amount: string | undefined): string | null =>
      amount === undefined || amount === '' ? null : amount;
    setCreating(true);
    setFailure(undefined);
    try {
      const result = await simulatePromotionCandidate(
        context,
        candidateId,
        {
          listPrice: values.listPrice ?? '',
          sellerDiscountRate: optional(values.discount),
          discountAlreadyInNetRevenue: false,
          unitCost: optional(values.unitCost),
          stepFees:
            optional(values.platformFee) === null
              ? []
              : [{ priceFloor: '0', feePerUnit: values.platformFee ?? '' }],
          feesKnown: optional(values.platformFee) !== null,
          scenarios: (values.scenarios ?? []).map((scenario) => ({
            code: scenario.name ?? '',
            quantity: scenario.value ?? '',
            necessary: true,
            conservative: true,
          })),
          referenceProfitLine: optional(values.referenceProfit),
          currencyCode: currency,
          expenses: {
            fixedPromotionFee: money(values.fixedFee),
            returnLossPerUnit: money(values.returnLoss),
            advertisingPerUnit: money(values.advertising),
            variableTaxPerUnit: money(values.variableTax),
          },
          context: {
            periodStart: values.periodStart ?? '',
            periodEnd: values.periodEnd ?? '',
            sourceReferences: { USER_REFERENCE: values.sourceReference ?? '' },
            assumptions: values.assumptions ?? '',
            commercialDeclaration: declaration,
          },
        },
        purpose,
      );
      if (!result.ok) {
        setFailure(result.failure);
        return;
      }
      void message.success(text.created);
      await load();
      if (
        result.value.purposeCode === purpose &&
        result.value.qualificationState === 'QUALIFIED_CONDITIONAL_ECONOMICS'
      ) {
        form.setFieldValue('simulationId', result.value.id);
      }
    } finally {
      setCreating(false);
    }
  };

  const createForm = (
    <Form<SimulationValues>
      form={simulationForm}
      component={false}
      layout="vertical"
      initialValues={{ currency: 'RUB', scenarios: [{ name: '', value: '' }] }}
    >
      <Row gutter={16}>
        {DECIMAL_FIELDS.map(([name, label]) => (
          <Col key={name} xs={24} md={8}>
            <Form.Item
              name={name}
              label={t(label)}
              rules={
                name === 'listPrice' ? [{ required: true, message: text.listPriceRequired }] : []
              }
            >
              <DecimalField ariaLabel={t(label)} />
            </Form.Item>
          </Col>
        ))}
        <Col xs={24} md={8}>
          <Form.Item
            name="currency"
            label={t('simulationCurrency')}
            rules={[{ required: true, pattern: /^[A-Za-z]{3}$/, message: text.currencyRequired }]}
          >
            <Input maxLength={3} />
          </Form.Item>
        </Col>
      </Row>
      <Form.List name="scenarios">
        {(fields: FormListFieldData[], { add, remove }) => (
          <>
            {fields.map((field, index) => (
              <Row key={field.key} gutter={8} align="top">
                <Col xs={24} md={11}>
                  <Form.Item
                    name={[field.name, 'name']}
                    label={`${t('simulationScenarioCode')} ${String(index + 1)}`}
                    rules={[
                      { required: true, whitespace: true, message: text.scenarioCodeRequired },
                    ]}
                  >
                    <Input maxLength={64} />
                  </Form.Item>
                </Col>
                <Col xs={22} md={12}>
                  <Form.Item
                    name={[field.name, 'value']}
                    label={`${t('simulationQuantity')} ${String(index + 1)}`}
                    rules={[{ required: true, message: text.scenarioQuantityRequired }]}
                  >
                    <DecimalField />
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
              disabled={fields.length >= 64}
              style={{ marginBottom: 16 }}
              onClick={() => {
                add({ name: '', value: '' });
              }}
            >
              {t('simulationAddScenario')}
            </Button>
          </>
        )}
      </Form.List>
      <Row gutter={16}>
        <Col xs={24} md={12}>
          <Form.Item
            name="periodStart"
            label={t('simulationPeriodStart')}
            rules={[{ required: true, message: text.periodRequired }]}
          >
            <InstantField ariaLabel={t('simulationPeriodStart')} />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item
            name="periodEnd"
            label={t('simulationPeriodEnd')}
            dependencies={['periodStart']}
            rules={[
              { required: true, message: text.periodRequired },
              ({ getFieldValue }) => ({
                validator: (_, value: string | undefined) => {
                  const start = getFieldValue('periodStart') as string | undefined;
                  return isInstant(start) &&
                    isInstant(value) &&
                    new Date(value).valueOf() <= new Date(start).valueOf()
                    ? Promise.reject(new Error(text.periodOrder))
                    : Promise.resolve();
                },
              }),
            ]}
          >
            <InstantField ariaLabel={t('simulationPeriodEnd')} />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item name="assumptions" label={t('simulationAssumptions')}>
            <Input.TextArea maxLength={512} autoSize={{ minRows: 2, maxRows: 4 }} />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item name="sourceReference" label={t('evidence')}>
            <Input maxLength={512} />
          </Form.Item>
        </Col>
      </Row>
      <TriggerButton
        trigger={{
          label: text.createButton,
          disabled: declaration === undefined,
          disabledReason: text.needsTerms,
          reasonPlacement: 'inline',
        }}
        loading={creating}
        onClick={() => {
          void create();
        }}
      />
    </Form>
  );

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Hint>{t('promotionSimulationBoundary')}</Hint>
      {failure !== undefined && <ListingProblem failure={failure} />}
      <Flex gap={8} align="flex-end" wrap>
        <Form.Item
          name="simulationId"
          label={t('promotionSimulationSelect')}
          rules={[{ required: true, message: text.simulationRequired }]}
          style={{ marginBottom: 0, minWidth: 320, flex: 1 }}
        >
          <Select
            allowClear
            placeholder={simulations === undefined ? text.simulationsLoading : t('undeclared')}
            loading={simulations === undefined}
            notFoundContent={t('promotionSimulationNone')}
            options={qualified.map((value) => ({
              value: value.id,
              label: text.simulationOption(formatStoreTime(value.computedAt), value.id.slice(0, 8)),
            }))}
          />
        </Form.Item>
        <Button
          icon={<ReloadOutlined />}
          aria-label={text.simulationRefresh}
          loading={simulations === undefined}
          onClick={() => {
            void load();
          }}
        />
      </Flex>
      {simulations !== undefined && qualified.length === 0 && (
        <Typography.Text type="secondary">{t('promotionSimulationNone')}</Typography.Text>
      )}
      <SectionCollapse
        size="small"
        items={[
          {
            key: 'create',
            title: text.createSection,
            summary: text.createSummary,
            children: createForm,
          },
        ]}
      />
    </Space>
  );
}
