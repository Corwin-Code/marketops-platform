import {
  Alert,
  App,
  Button,
  Col,
  DatePicker,
  Descriptions,
  Divider,
  Flex,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Tabs,
  Typography,
} from 'antd';
import type { Dayjs } from 'dayjs';
import { useState } from 'react';
import {
  amendInboundAttestation,
  cancelInboundAttestation,
  createInboundAttestation,
  fetchInboundAttestation,
  publishLeadTimePolicy,
  retireAvailabilityPolicy,
  reverifyInboundAttestation,
} from '../api/console';
import type { ConsoleFailure, ConsoleRequest, InboundAttestation } from '../api/console';
import { STORE_TIMEZONE_LABEL, storeLocalToIso, toStoreDayjs } from '../format';
import { codeLabel } from '../i18n';
import {
  INBOUND_STATUS_LABELS,
  POLICY_STATUS_LABELS,
  availabilityText,
} from '../i18n/zh/availability';
import {
  CodeTag,
  ConfirmButton,
  DateTime,
  FailureAlert,
  SectionCard,
  TechnicalDetails,
} from '../ui';
import { INBOUND_STATUS_COLORS } from './tagColors';

export interface AvailabilityAuthorityPanelProps {
  readonly context: ConsoleRequest;
}

const INBOUND_STATUSES = [
  'DRAFT',
  'REQUESTED',
  'SUPPLIER_CONFIRMED',
  'IN_TRANSIT',
  'RECEIVED',
  'OVERDUE',
  'CONFLICTED',
  'UNKNOWN',
] as const;

const PICKER_FORMAT = 'YYYY-MM-DD HH:mm';

/** The last thing a control did, shown under the form. */
type Outcome =
  | { readonly kind: 'success'; readonly text: string }
  | { readonly kind: 'failure'; readonly failure: ConsoleFailure };

/** Governed procurement intake; this surface never writes provider stock. */
export function AvailabilityAuthorityPanel({
  context,
}: AvailabilityAuthorityPanelProps): React.JSX.Element {
  return (
    <section aria-label={availabilityText.authorityTitle}>
      <SectionCard title={availabilityText.authorityTitle}>
        <Typography.Paragraph type="secondary">
          {availabilityText.authorityHint}
        </Typography.Paragraph>
        <Tabs
          items={[
            {
              key: 'inbound',
              label: '入库证明',
              children: <InboundAuthority context={context} />,
            },
            {
              key: 'lead-time',
              label: '提前期与安全库存',
              children: <LeadTimeAuthority context={context} />,
            },
          ]}
        />
      </SectionCard>
    </section>
  );
}

/** Shows the last outcome of a control under a data-testid hook. */
function OutcomeNote({
  outcome,
  testId,
}: {
  readonly outcome: Outcome | undefined;
  readonly testId: string;
}): React.JSX.Element | null {
  if (outcome === undefined) {
    return null;
  }
  return (
    <div data-testid={testId}>
      {outcome.kind === 'success' ? (
        <Alert type="success" showIcon title={outcome.text} />
      ) : (
        <FailureAlert failure={outcome.failure} />
      )}
    </div>
  );
}

interface InboundValues {
  readonly productVariantId?: string;
  readonly externalReference?: string;
  readonly quantity?: number | null;
  readonly arrivalFrom?: Dayjs | null;
  readonly arrivalTo?: Dayjs | null;
  readonly status: (typeof INBOUND_STATUSES)[number];
  readonly evidence?: string;
  readonly reason?: string;
}

const REQUIRED_FOR_CREATE = [
  'productVariantId',
  'externalReference',
  'arrivalFrom',
  'arrivalTo',
  'evidence',
] as const;

const REQUIRED_FOR_AMEND = ['quantity', 'arrivalFrom', 'arrivalTo'] as const;

function InboundAuthority({ context }: AvailabilityAuthorityPanelProps): React.JSX.Element {
  const { message } = App.useApp();
  const [form] = Form.useForm<InboundValues>();
  const [attestationId, setAttestationId] = useState('');
  const [current, setCurrent] = useState<InboundAttestation | undefined>(undefined);
  const [outcome, setOutcome] = useState<Outcome | undefined>(undefined);
  const [busy, setBusy] = useState<'create' | 'load' | 'amend' | 'reverify' | undefined>(undefined);

  const draft = (values: InboundValues) => ({
    productVariantId: values.productVariantId ?? '',
    externalReference: values.externalReference ?? '',
    quantity: values.quantity ?? 0,
    expectedArrivalFrom: values.arrivalFrom ? storeLocalToIso(values.arrivalFrom) : '',
    expectedArrivalTo: values.arrivalTo ? storeLocalToIso(values.arrivalTo) : '',
    businessStatus: values.status,
    evidenceReference: values.evidence ?? '',
    sourceTime: new Date().toISOString(),
    reason: values.reason ?? '',
  });

  const settle = (
    result: Awaited<ReturnType<typeof cancelInboundAttestation>>,
    success: (value: InboundAttestation) => string,
  ): void => {
    if (result.ok) {
      setCurrent(result.value);
      const text = success(result.value);
      setOutcome({ kind: 'success', text });
      void message.success(text);
    } else {
      setOutcome({ kind: 'failure', failure: result.failure });
    }
  };

  const accepted = (value: InboundAttestation): string =>
    `入库证明第 ${String(value.versionNo)} 版已受理`;

  const create = async (): Promise<void> => {
    try {
      await form.validateFields([...REQUIRED_FOR_CREATE]);
    } catch {
      return;
    }
    setBusy('create');
    const result = await createInboundAttestation(
      context,
      draft(form.getFieldsValue(true) as InboundValues),
    );
    setBusy(undefined);
    if (result.ok) {
      setAttestationId(result.value.id);
    }
    settle(result, accepted);
  };

  const load = async (): Promise<void> => {
    setBusy('load');
    const result = await fetchInboundAttestation(context, attestationId);
    setBusy(undefined);
    if (!result.ok) {
      setOutcome({ kind: 'failure', failure: result.failure });
      return;
    }
    const loaded = result.value;
    setCurrent(loaded);
    form.setFieldsValue({
      productVariantId: loaded.productVariantId,
      externalReference: loaded.externalReference,
      quantity: loaded.quantity,
      arrivalFrom: toStoreDayjs(loaded.expectedArrivalFrom) ?? null,
      arrivalTo: toStoreDayjs(loaded.expectedArrivalTo) ?? null,
      status: loaded.businessStatus as (typeof INBOUND_STATUSES)[number],
      evidence: loaded.evidenceReference,
    });
    const text = `已加载入库证明第 ${String(loaded.versionNo)} 版`;
    setOutcome({ kind: 'success', text });
    void message.success(text);
  };

  const amend = async (target: InboundAttestation): Promise<void> => {
    try {
      await form.validateFields([...REQUIRED_FOR_AMEND]);
    } catch {
      return;
    }
    const body = draft(form.getFieldsValue(true) as InboundValues);
    setBusy('amend');
    const result = await amendInboundAttestation(context, target.id, {
      expectedVersion: target.versionNo,
      quantity: body.quantity,
      expectedArrivalFrom: body.expectedArrivalFrom,
      expectedArrivalTo: body.expectedArrivalTo,
      businessStatus: body.businessStatus,
      evidenceReference: body.evidenceReference,
      sourceTime: body.sourceTime,
      reason: body.reason,
    });
    setBusy(undefined);
    settle(result, accepted);
  };

  const reverify = async (target: InboundAttestation): Promise<void> => {
    const values = form.getFieldsValue(true) as InboundValues;
    setBusy('reverify');
    const result = await reverifyInboundAttestation(
      context,
      target.id,
      target.versionNo,
      values.evidence ?? '',
      values.reason ?? '',
    );
    setBusy(undefined);
    settle(result, accepted);
  };

  const cancel = async (target: InboundAttestation): Promise<void> => {
    const values = form.getFieldsValue(true) as InboundValues;
    const result = await cancelInboundAttestation(
      context,
      target.id,
      target.versionNo,
      values.evidence ?? '',
      values.reason ?? '',
    );
    settle(result, (value) => `入库证明已取消（第 ${String(value.versionNo)} 版）`);
  };

  return (
    <div data-testid="inbound-authority">
      <Form<InboundValues>
        form={form}
        layout="vertical"
        initialValues={{ quantity: 1, status: 'REQUESTED' }}
      >
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item
              name="productVariantId"
              label="商品变体 ID"
              rules={[{ required: true, whitespace: true, message: '请填写商品变体 ID' }]}
            >
              <Input data-testid="inbound-variant" />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="externalReference"
              label="外部订单或发运单号"
              rules={[{ required: true, whitespace: true, message: '请填写外部订单或发运单号' }]}
            >
              <Input data-testid="inbound-external-reference" />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="quantity"
              label="数量（件）"
              rules={[{ required: true, message: '请填写数量' }]}
            >
              <InputNumber
                data-testid="inbound-quantity"
                min={1}
                precision={0}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="arrivalFrom"
              label={`预计到货开始（${STORE_TIMEZONE_LABEL}）`}
              rules={[{ required: true, message: '请选择预计到货开始时间' }]}
            >
              <DatePicker
                data-testid="inbound-arrival-from"
                showTime={{ format: 'HH:mm' }}
                format={PICKER_FORMAT}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="arrivalTo"
              label={`预计到货结束（${STORE_TIMEZONE_LABEL}）`}
              rules={[{ required: true, message: '请选择预计到货结束时间' }]}
            >
              <DatePicker
                data-testid="inbound-arrival-to"
                showTime={{ format: 'HH:mm' }}
                format={PICKER_FORMAT}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="status" label="业务状态">
              <Select
                data-testid="inbound-status"
                options={INBOUND_STATUSES.map((one) => ({
                  value: one,
                  label: codeLabel(INBOUND_STATUS_LABELS, one),
                }))}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="evidence"
              label="证据引用"
              rules={[{ required: true, whitespace: true, message: '请填写证据引用' }]}
            >
              <Input data-testid="inbound-evidence" />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="reason" label="原因说明">
              <Input data-testid="inbound-reason" />
            </Form.Item>
          </Col>
        </Row>
        <Button
          type="primary"
          data-testid="inbound-create"
          loading={busy === 'create'}
          onClick={() => {
            void create();
          }}
        >
          创建入库证明
        </Button>
      </Form>

      <Divider titlePlacement="start" plain>
        修订已有入库证明
      </Divider>
      <Space.Compact style={{ width: '100%', maxWidth: 560 }}>
        <Input
          data-testid="inbound-id"
          aria-label="已有入库证明 ID"
          placeholder="已有入库证明 ID"
          value={attestationId}
          onChange={(event) => {
            setAttestationId(event.target.value);
          }}
        />
        <Button
          data-testid="inbound-load"
          loading={busy === 'load'}
          onClick={() => {
            void load();
          }}
        >
          加载当前版本
        </Button>
      </Space.Compact>

      {current === undefined ? null : (
        <div data-testid="inbound-current" style={{ marginTop: 16 }}>
          <Descriptions
            bordered
            size="small"
            column={{ xs: 1, md: 2, xl: 3 }}
            title={`当前版本：第 ${String(current.versionNo)} 版`}
            items={[
              {
                key: 'status',
                label: '业务状态',
                children: (
                  <CodeTag
                    labels={INBOUND_STATUS_LABELS}
                    code={current.businessStatus}
                    colors={INBOUND_STATUS_COLORS}
                  />
                ),
              },
              { key: 'quantity', label: '数量', children: `${String(current.quantity)} 件` },
              { key: 'reference', label: '外部单号', children: current.externalReference },
              {
                key: 'from',
                label: '预计到货开始',
                children: <DateTime value={current.expectedArrivalFrom} />,
              },
              {
                key: 'to',
                label: '预计到货结束',
                children: <DateTime value={current.expectedArrivalTo} />,
              },
              {
                key: 'verified',
                label: '最近核验',
                children: <DateTime value={current.lastVerifiedAt} relative />,
              },
              {
                key: 'evidence',
                label: '证据引用',
                children: <Typography.Text copyable>{current.evidenceReference}</Typography.Text>,
              },
            ]}
          />
          <TechnicalDetails
            data={{
              attestationId: current.id,
              versionId: current.versionId,
              productVariantId: current.productVariantId,
            }}
          />
          <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
            修订、重新核验和取消都使用上方表单中的当前填写内容（证据引用和原因说明）。
          </Typography.Paragraph>
          <Space wrap>
            <Button
              data-testid="inbound-amend"
              loading={busy === 'amend'}
              onClick={() => {
                void amend(current);
              }}
            >
              按表单内容修订
            </Button>
            <Button
              data-testid="inbound-reverify"
              loading={busy === 'reverify'}
              onClick={() => {
                void reverify(current);
              }}
            >
              重新核验证据
            </Button>
            <span data-testid="inbound-cancel">
              <ConfirmButton
                danger
                title="确认取消这份入库证明？"
                description="取消后该入库不再计入可用供应，并会触发重新计算。"
                onConfirm={() => cancel(current)}
              >
                取消入库证明
              </ConfirmButton>
            </span>
          </Space>
        </div>
      )}
      <div style={{ marginTop: 16 }}>
        <OutcomeNote outcome={outcome} testId="inbound-message" />
      </div>
    </div>
  );
}

interface LeadTimeValues {
  readonly leadMin?: number | null;
  readonly leadMax?: number | null;
  readonly safety?: number | null;
  readonly effectiveFrom?: Dayjs | null;
  readonly evidence?: string;
  readonly reason?: string;
  readonly supersedes?: string;
}

function LeadTimeAuthority({ context }: AvailabilityAuthorityPanelProps): React.JSX.Element {
  const { message } = App.useApp();
  const [form] = Form.useForm<LeadTimeValues>();
  const [retireId, setRetireId] = useState('');
  const [publishing, setPublishing] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | undefined>(undefined);

  const publish = async (values: LeadTimeValues): Promise<void> => {
    if (values.effectiveFrom === undefined || values.effectiveFrom === null) {
      return;
    }
    const supersedes = values.supersedes ?? '';
    setPublishing(true);
    const result = await publishLeadTimePolicy(context, {
      scopeKind: 'ORGANIZATION',
      productVariantId: null,
      supplierCode: null,
      routeCode: null,
      categoryCode: null,
      leadTimeDaysMin: values.leadMin ?? 0,
      leadTimeDaysMax: values.leadMax ?? 0,
      safetyDays: values.safety ?? 0,
      reason: values.reason ?? '',
      evidenceReference: values.evidence ?? '',
      lastReviewedAt: new Date().toISOString(),
      effectiveFrom: storeLocalToIso(values.effectiveFrom),
      effectiveTo: null,
      fallbackOfId: null,
      supersedesPolicyId: supersedes === '' ? null : supersedes,
    });
    setPublishing(false);
    if (result.ok) {
      setRetireId(result.value.id);
      const text = `提前期策略第 ${String(result.value.version)} 版已发布`;
      setOutcome({ kind: 'success', text });
      void message.success(text);
    } else {
      setOutcome({ kind: 'failure', failure: result.failure });
    }
  };

  const retire = async (): Promise<void> => {
    const values = form.getFieldsValue(true) as LeadTimeValues;
    const result = await retireAvailabilityPolicy(
      context,
      'LEAD_TIME',
      retireId,
      values.reason ?? '',
      values.evidence ?? '',
    );
    if (result.ok) {
      const text = `策略状态：${codeLabel(POLICY_STATUS_LABELS, result.value.status)}`;
      setOutcome({ kind: 'success', text });
      void message.success(text);
    } else {
      setOutcome({ kind: 'failure', failure: result.failure });
    }
  };

  return (
    <div data-testid="lead-time-authority">
      <Typography.Paragraph type="secondary">
        组织级提前期与安全库存策略，按生效时间管理。
      </Typography.Paragraph>
      <Form<LeadTimeValues>
        form={form}
        layout="vertical"
        initialValues={{ leadMin: 0, leadMax: 14, safety: 7 }}
        onFinish={(values) => {
          void publish(values);
        }}
      >
        <Row gutter={16}>
          <Col xs={24} md={8}>
            <Form.Item
              name="leadMin"
              label="最短提前期（天）"
              rules={[{ required: true, message: '请填写最短提前期' }]}
            >
              <InputNumber data-testid="lead-min" min={0} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="leadMax"
              label="最长提前期（天）"
              dependencies={['leadMin']}
              rules={[
                { required: true, message: '请填写最长提前期' },
                ({ getFieldValue }) => ({
                  validator: (_, value: number | null | undefined) => {
                    const min = getFieldValue('leadMin') as number | null | undefined;
                    if (
                      value === null ||
                      value === undefined ||
                      min === null ||
                      min === undefined ||
                      min <= value
                    ) {
                      return Promise.resolve();
                    }
                    return Promise.reject(new Error('最长提前期不能小于最短提前期'));
                  },
                }),
              ]}
            >
              <InputNumber data-testid="lead-max" min={0} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="safety"
              label="安全库存天数"
              rules={[{ required: true, message: '请填写安全库存天数' }]}
            >
              <InputNumber
                data-testid="lead-safety"
                min={0}
                precision={0}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="effectiveFrom"
              label={`生效时间（${STORE_TIMEZONE_LABEL}）`}
              rules={[{ required: true, message: '请选择生效时间' }]}
            >
              <DatePicker
                data-testid="lead-effective-from"
                showTime={{ format: 'HH:mm' }}
                format={PICKER_FORMAT}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="evidence"
              label="证据引用"
              rules={[{ required: true, whitespace: true, message: '请填写证据引用' }]}
            >
              <Input data-testid="lead-evidence" />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="reason"
              label="原因说明"
              rules={[{ required: true, whitespace: true, message: '请填写原因说明' }]}
            >
              <Input data-testid="lead-reason" />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="supersedes" label="被替代的策略 ID（可选）">
              <Input data-testid="lead-supersedes" />
            </Form.Item>
          </Col>
        </Row>
        <Button type="primary" htmlType="submit" data-testid="lead-publish" loading={publishing}>
          发布策略
        </Button>
      </Form>

      <Divider titlePlacement="start" plain>
        停用策略
      </Divider>
      <Flex gap={8} wrap align="center">
        <Input
          data-testid="policy-retire-id"
          aria-label="要停用的策略 ID"
          placeholder="要停用的策略 ID"
          style={{ maxWidth: 420 }}
          value={retireId}
          onChange={(event) => {
            setRetireId(event.target.value);
          }}
        />
        <span data-testid="policy-retire">
          <ConfirmButton
            danger
            title="确认停用该提前期策略？"
            description="停用使用上方表单中的原因说明和证据引用，停用后将触发重新计算。"
            disabled={retireId.trim() === ''}
            disabledReason="请先填写要停用的策略 ID"
            onConfirm={retire}
          >
            停用策略
          </ConfirmButton>
        </span>
      </Flex>
      <div style={{ marginTop: 16 }}>
        <OutcomeNote outcome={outcome} testId="policy-message" />
      </div>
    </div>
  );
}
