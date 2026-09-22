import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Card, Col, Collapse, Form, Input, InputNumber, Row, Select, Space } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  fetchCandidateSimulations,
  prepareAction,
  simulatePromotionCandidate,
  type ListingPurposeBasis,
  type PromotionSimulation,
  type PromotionTerms,
} from '../api/listingConversion';
import { formatStoreTime } from '../format';
import { t } from '../i18n/zh/listing';
import { Hint, InstantPicker, ListingProblem, codeOptions } from './ListingCommon';
import { PromotionTermsForm } from './ListingPromotionTermsForm';

type PromotionPurpose = 'PROMOTION' | 'BOUNDED_EXPLORATION';

interface ScenarioRow {
  name: string;
  value: string;
}

/** A decimal typed as text: never converted to a JavaScript number. */
function DecimalInput({
  value,
  onChange,
  ariaLabel,
}: {
  readonly value: string;
  readonly onChange: (next: string) => void;
  readonly ariaLabel?: string;
}): React.JSX.Element {
  return (
    <InputNumber<string>
      stringMode
      style={{ width: '100%' }}
      value={value === '' ? null : value}
      {...(ariaLabel === undefined ? {} : { 'aria-label': ariaLabel })}
      onChange={(next) => {
        onChange(next ?? '');
      }}
    />
  );
}

export function PromotionPreparationForm({
  context,
  candidateId,
  kind,
  onPrepared,
}: {
  readonly context: ConsoleRequest;
  readonly candidateId: string;
  readonly kind: string;
  readonly onPrepared: (id: string) => void;
}): React.JSX.Element {
  const [purpose, setPurpose] = useState<PromotionPurpose>('PROMOTION');
  const [basisEvidence, setBasisEvidence] = useState('');
  const [useCondition, setUseCondition] = useState('');
  const [endCondition, setEndCondition] = useState('');
  const [useUntil, setUseUntil] = useState('');
  const [simulationId, setSimulationId] = useState<string>();
  const useUntilValue = new Date(useUntil);
  const purposeBasis: ListingPurposeBasis | undefined =
    purpose === 'BOUNDED_EXPLORATION' &&
    basisEvidence.trim() !== '' &&
    useCondition.trim() !== '' &&
    endCondition.trim() !== '' &&
    Number.isFinite(useUntilValue.valueOf())
      ? {
          evidenceReference: basisEvidence,
          useConditions: [useCondition],
          endConditions: [endCondition],
          useUntil: useUntilValue.toISOString(),
        }
      : undefined;
  return (
    <PromotionTermsForm
      label={candidateId}
      kind={kind}
      onSaved={onPrepared}
      preparation={(draft) => (
        <>
          <Card size="small" type="inner" title={t('actionPurpose')}>
            <Form.Item label={t('actionPurpose')}>
              <Select<PromotionPurpose>
                value={purpose}
                style={{ maxWidth: 320 }}
                options={codeOptions('actionPurpose', ['PROMOTION', 'BOUNDED_EXPLORATION']).map(
                  (option) => ({ ...option, value: option.value as PromotionPurpose }),
                )}
                onChange={(next) => {
                  setPurpose(next);
                  setSimulationId(undefined);
                }}
              />
            </Form.Item>
            {purpose === 'BOUNDED_EXPLORATION' && (
              <Row gutter={16}>
                <Col xs={24} md={12}>
                  <Form.Item label={t('purposeEvidence')} required>
                    <Input
                      maxLength={512}
                      value={basisEvidence}
                      onChange={(event) => {
                        setBasisEvidence(event.target.value);
                      }}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item label={t('purposeUseUntil')} required>
                    <InstantPicker value={useUntil} onChange={setUseUntil} />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item label={t('purposeUseCondition')} required>
                    <Input.TextArea
                      maxLength={512}
                      autoSize={{ minRows: 2, maxRows: 4 }}
                      value={useCondition}
                      onChange={(event) => {
                        setUseCondition(event.target.value);
                      }}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item label={t('purposeEndCondition')} required>
                    <Input.TextArea
                      maxLength={512}
                      autoSize={{ minRows: 2, maxRows: 4 }}
                      value={endCondition}
                      onChange={(event) => {
                        setEndCondition(event.target.value);
                      }}
                    />
                  </Form.Item>
                </Col>
              </Row>
            )}
          </Card>
          <PromotionSimulationSelection
            context={context}
            candidateId={candidateId}
            purpose={purpose}
            declaration={draft}
            selected={simulationId}
            onSelected={setSimulationId}
          />
        </>
      )}
      onSave={async (terms) => {
        if (
          simulationId === undefined ||
          (purpose === 'BOUNDED_EXPLORATION' && purposeBasis === undefined)
        )
          return {
            ok: false as const,
            failure: {
              kind: 'refused' as const,
              status: 400,
              detail: t('promotionSimulationRequired'),
            },
          };
        const outcome = await prepareAction(
          context,
          candidateId,
          'MANUAL',
          '',
          undefined,
          undefined,
          terms,
          purpose,
          purposeBasis,
          simulationId,
        );
        return outcome.ok ? { ok: true, value: outcome.value.id } : outcome;
      }}
    />
  );
}

function PromotionSimulationSelection({
  context,
  candidateId,
  purpose,
  declaration,
  selected,
  onSelected,
}: {
  readonly context: ConsoleRequest;
  readonly candidateId: string;
  readonly purpose: PromotionPurpose;
  readonly declaration: PromotionTerms | undefined;
  readonly selected: string | undefined;
  readonly onSelected: (id: string | undefined) => void;
}): React.JSX.Element {
  const [simulations, setSimulations] = useState<readonly PromotionSimulation[]>([]);
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [busy, setBusy] = useState(false);
  const [listPrice, setListPrice] = useState('');
  const [discount, setDiscount] = useState('');
  const [unitCost, setUnitCost] = useState('');
  const [currency, setCurrency] = useState('RUB');
  const [referenceProfit, setReferenceProfit] = useState('');
  const [scenarios, setScenarios] = useState<ScenarioRow[]>([{ name: '', value: '' }]);
  const [periodStart, setPeriodStart] = useState('');
  const [periodEnd, setPeriodEnd] = useState('');
  const [assumptions, setAssumptions] = useState('');
  const [sourceReference, setSourceReference] = useState('');
  const [platformFee, setPlatformFee] = useState('');
  const [fixedFee, setFixedFee] = useState('');
  const [returnLoss, setReturnLoss] = useState('');
  const [advertising, setAdvertising] = useState('');
  const [variableTax, setVariableTax] = useState('');
  const declarationIdentity = JSON.stringify(declaration);
  const qualified = simulations.filter(
    (value) =>
      value.purposeCode === purpose &&
      value.qualificationState === 'QUALIFIED_CONDITIONAL_ECONOMICS',
  );
  async function load(): Promise<void> {
    const result = await fetchCandidateSimulations(context, candidateId);
    if (result.ok) {
      setSimulations(result.value);
      setFailure(undefined);
    } else {
      setSimulations([]);
      setFailure(result.failure);
    }
  }
  useEffect(() => {
    onSelected(undefined);
    setSimulations([]);
  }, [purpose, declarationIdentity, onSelected]);

  const create = (): void => {
    const start = new Date(periodStart),
      end = new Date(periodEnd);
    if (
      declaration === undefined ||
      !Number.isFinite(start.valueOf()) ||
      !Number.isFinite(end.valueOf()) ||
      scenarios.some((scenario) => scenario.name.trim() === '' || scenario.value.trim() === '')
    ) {
      setFailure({
        kind: 'refused',
        status: 400,
        detail: t('promotionSimulationRequired'),
      });
      return;
    }
    setBusy(true);
    setFailure(undefined);
    const money = (amount: string) => (amount === '' ? null : { amount, currencyCode: currency });
    void simulatePromotionCandidate(
      context,
      candidateId,
      {
        listPrice,
        sellerDiscountRate: discount === '' ? null : discount,
        discountAlreadyInNetRevenue: false,
        unitCost: unitCost === '' ? null : unitCost,
        stepFees: platformFee === '' ? [] : [{ priceFloor: '0', feePerUnit: platformFee }],
        feesKnown: platformFee !== '',
        scenarios: scenarios.map((scenario) => ({
          code: scenario.name,
          quantity: scenario.value,
          necessary: true,
          conservative: true,
        })),
        referenceProfitLine: referenceProfit === '' ? null : referenceProfit,
        currencyCode: currency,
        expenses: {
          fixedPromotionFee: money(fixedFee),
          returnLossPerUnit: money(returnLoss),
          advertisingPerUnit: money(advertising),
          variableTaxPerUnit: money(variableTax),
        },
        context: {
          periodStart: start.toISOString(),
          periodEnd: end.toISOString(),
          sourceReferences: { USER_REFERENCE: sourceReference },
          assumptions,
          commercialDeclaration: declaration,
        },
      },
      purpose,
    )
      .then(async (result) => {
        if (!result.ok) {
          setFailure(result.failure);
          return;
        }
        await load();
        if (
          result.value.purposeCode === purpose &&
          result.value.qualificationState === 'QUALIFIED_CONDITIONAL_ECONOMICS'
        )
          onSelected(result.value.id);
      })
      .finally(() => {
        setBusy(false);
      });
  };

  const decimalFields: readonly [string, string, (next: string) => void][] = [
    [t('simulationListPrice'), listPrice, setListPrice],
    [t('simulationDiscount'), discount, setDiscount],
    [t('simulationUnitCost'), unitCost, setUnitCost],
    [t('simulationReferenceProfit'), referenceProfit, setReferenceProfit],
    [t('simulationPlatformFee'), platformFee, setPlatformFee],
    [t('simulationFixedFee'), fixedFee, setFixedFee],
    [t('simulationReturnLoss'), returnLoss, setReturnLoss],
    [t('simulationAdvertising'), advertising, setAdvertising],
    [t('simulationVariableTax'), variableTax, setVariableTax],
  ];

  const createForm = (
    <Space orientation="vertical" size="small" style={{ width: '100%' }}>
      <Row gutter={16}>
        {decimalFields.map(([label, value, setter]) => (
          <Col key={label} xs={24} md={8}>
            <Form.Item label={label}>
              <DecimalInput value={value} onChange={setter} ariaLabel={label} />
            </Form.Item>
          </Col>
        ))}
        <Col xs={24} md={8}>
          <Form.Item label={t('simulationCurrency')}>
            <Input
              maxLength={3}
              value={currency}
              onChange={(event) => {
                setCurrency(event.target.value.toUpperCase());
              }}
            />
          </Form.Item>
        </Col>
      </Row>
      {scenarios.map((scenario, index) => (
        <Row key={index} gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item label={`${t('simulationScenarioCode')} ${String(index + 1)}`}>
              <Input
                value={scenario.name}
                onChange={(event) => {
                  setScenarios(
                    scenarios.map((value, row) =>
                      row === index ? { ...value, name: event.target.value } : value,
                    ),
                  );
                }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item label={`${t('simulationQuantity')} ${String(index + 1)}`}>
              <DecimalInput
                value={scenario.value}
                onChange={(next) => {
                  setScenarios(
                    scenarios.map((value, row) =>
                      row === index ? { ...value, value: next } : value,
                    ),
                  );
                }}
              />
            </Form.Item>
          </Col>
        </Row>
      ))}
      <Button
        type="dashed"
        icon={<PlusOutlined />}
        disabled={scenarios.length >= 64}
        onClick={() => {
          setScenarios([...scenarios, { name: '', value: '' }]);
        }}
      >
        {t('simulationAddScenario')}
      </Button>
      <Row gutter={16}>
        <Col xs={24} md={12}>
          <Form.Item label={t('simulationPeriodStart')}>
            <InstantPicker value={periodStart} onChange={setPeriodStart} />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item label={t('simulationPeriodEnd')}>
            <InstantPicker value={periodEnd} onChange={setPeriodEnd} />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item label={t('simulationAssumptions')}>
            <Input.TextArea
              maxLength={512}
              autoSize={{ minRows: 2, maxRows: 4 }}
              value={assumptions}
              onChange={(event) => {
                setAssumptions(event.target.value);
              }}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item label={t('evidence')}>
            <Input
              maxLength={512}
              value={sourceReference}
              onChange={(event) => {
                setSourceReference(event.target.value);
              }}
            />
          </Form.Item>
        </Col>
      </Row>
      <Button
        loading={busy}
        disabled={declaration === undefined}
        {...(declaration === undefined ? { title: t('simulationNeedsTerms') } : {})}
        onClick={create}
      >
        {t('promotionSimulationCreate')}
      </Button>
    </Space>
  );

  return (
    <Card size="small" type="inner" title={t('promotionSimulation')}>
      <Hint>{t('promotionSimulationBoundary')}</Hint>
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        {failure === undefined ? null : <ListingProblem failure={failure} />}
        <Space wrap align="end">
          <Form.Item label={t('promotionSimulationSelect')} required style={{ marginBottom: 0 }}>
            <Select
              style={{ minWidth: 320 }}
              placeholder={t('undeclared')}
              allowClear
              value={selected}
              onChange={(next: string | undefined) => {
                onSelected(next === undefined || next === '' ? undefined : next);
              }}
              notFoundContent={t('promotionSimulationNone')}
              options={qualified.map((value) => ({
                value: value.id,
                label: `${formatStoreTime(value.computedAt)} · ${value.id.slice(0, 8)}`,
              }))}
            />
          </Form.Item>
          <Button
            icon={<ReloadOutlined />}
            loading={busy}
            onClick={() => {
              setBusy(true);
              void load().finally(() => {
                setBusy(false);
              });
            }}
          >
            {t('promotionSimulationLoad')}
          </Button>
        </Space>
        {qualified.length === 0 ? <Hint>{t('promotionSimulationNone')}</Hint> : null}
        <Collapse
          size="small"
          items={[{ key: 'create', label: t('promotionSimulationCreate'), children: createForm }]}
        />
      </Space>
    </Card>
  );
}
