import { ReloadOutlined } from '@ant-design/icons';
import {
  App,
  Button,
  Col,
  DatePicker,
  Flex,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Table,
  Typography,
} from 'antd';
import type { FormInstance, TableColumnsType } from 'antd';
import type { Dayjs } from 'dayjs';
import { useEffect, useState } from 'react';
import type { LeadTimePolicy, LeadTimePolicyPage } from '../api/availability';
import {
  fetchLeadTimePolicies,
  publishLeadTimePolicy,
  retireAvailabilityPolicy,
} from '../api/availability';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { formatStoreTime, STORE_TIMEZONE_LABEL, storeLocalToIso, toStoreDayjs } from '../format';
import { actions, codeLabel } from '../i18n';
import {
  LEAD_SCOPE_KIND_LABELS,
  POLICY_LIFECYCLE_LABELS,
  POLICY_STATUS_LABELS,
} from '../i18n/zh/availability';
import { leadTimeText as text } from '../i18n/zh/availabilityAuthority';
import {
  ActionModal,
  CodeTag,
  DateTime,
  EmptyState,
  FailureAlert,
  FormDrawer,
  LoadingState,
  StepUpNotice,
  VariantName,
  idRules,
  useSearchParam,
  useSearchParamsPatch,
} from '../ui';
import type { SubmitOutcome } from '../ui';
import { POLICY_LIFECYCLE_COLORS } from './tagColors';
import { VariantPicker } from './VariantPicker';

export interface LeadTimePoliciesProps {
  readonly context: ConsoleRequest;
}

/** The scope kinds the backend accepts, broadest first. */
const SCOPE_KINDS = [
  'ORGANIZATION',
  'SUPPLIER',
  'PRODUCT_CATEGORY',
  'VARIANT_SUPPLIER_ROUTE',
] as const;
type ScopeKind = (typeof SCOPE_KINDS)[number];

const STATUSES = ['ACTIVE', 'RETIRED', 'CANCELLED'] as const;
const ALL = 'ALL';
const PAGE_SIZE = 200;
const PICKER_FORMAT = 'YYYY-MM-DD HH:mm';
const CODE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const DAYS_MAX = 3650;
const EVIDENCE_LIMIT = 512;
const REASON_LIMIT = 1000;

/** Address-bar key of this tab's status filter. */
const STATUS_PARAM = 'pstatus';

type Policies =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly page: LeadTimePolicyPage }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/**
 * Lead-time and safety policy versions, and publishing and retiring them.
 *
 * The list shows each version's scope spelled out rather than as an
 * identifier. Publishing picks the scope and, to replace a version, picks the
 * version it supersedes from the same scope; retiring is its own dialog with
 * its own reason and evidence. Both need a recent sign-in, which is named
 * before anything is typed.
 */
export function LeadTimePolicies({ context }: LeadTimePoliciesProps): React.JSX.Element {
  const { message } = App.useApp();
  const [rawStatus] = useSearchParam(STATUS_PARAM);
  const patch = useSearchParamsPatch();
  const status = (STATUSES as readonly string[]).includes(rawStatus ?? '') ? rawStatus : undefined;
  const [policies, setPolicies] = useState<Policies>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [fetching, setFetching] = useState(true);
  const [retiring, setRetiring] = useState<LeadTimePolicy | undefined>(undefined);

  useEffect(() => {
    let live = true;
    setFetching(true);
    void fetchLeadTimePolicies(context, { status, limit: PAGE_SIZE, offset: 0 }).then((outcome) => {
      if (!live) return;
      setFetching(false);
      setPolicies(
        outcome.ok
          ? { kind: 'loaded', page: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, status, generation]);

  const loaded = policies.kind === 'loaded' ? policies.page : undefined;
  const reload = (): void => {
    setGeneration((value) => value + 1);
  };
  const changed = (success: string): void => {
    void message.success(success);
    setRetiring(undefined);
    reload();
  };

  const columns: TableColumnsType<LeadTimePolicy> = [
    {
      key: 'scope',
      title: text.columnScope,
      width: 280,
      render: (_, policy) => <PolicyScope policy={policy} />,
    },
    {
      key: 'version',
      title: text.columnVersion,
      render: (_, policy) => text.version(policy.version),
    },
    {
      key: 'lead',
      title: text.columnLeadTime,
      render: (_, policy) => text.leadRange(policy.leadTimeDaysMin, policy.leadTimeDaysMax),
    },
    {
      key: 'safety',
      title: text.columnSafety,
      render: (_, policy) => text.days(policy.safetyDays),
    },
    {
      key: 'validity',
      title: text.columnValidity,
      render: (_, policy) => (
        <Flex vertical>
          <DateTime value={policy.effectiveFrom} />
          {policy.effectiveTo === null ? (
            <Typography.Text type="secondary">{text.openEnded}</Typography.Text>
          ) : (
            <DateTime value={policy.effectiveTo} />
          )}
        </Flex>
      ),
    },
    {
      key: 'lifecycle',
      title: text.columnLifecycle,
      render: (_, policy) => (
        <CodeTag
          labels={POLICY_LIFECYCLE_LABELS}
          code={policy.lifecycle}
          colors={POLICY_LIFECYCLE_COLORS}
        />
      ),
    },
    {
      key: 'owner',
      title: text.columnOwner,
      render: (_, policy) => policy.ownerName ?? '—',
    },
    {
      key: 'actions',
      title: text.columnActions,
      render: (_, policy) =>
        policy.canManage && (policy.lifecycle === 'CURRENT' || policy.lifecycle === 'SCHEDULED') ? (
          <Button
            size="small"
            danger
            onClick={() => {
              setRetiring(policy);
            }}
          >
            {text.retire}
          </Button>
        ) : null,
    },
  ];

  const canPublish =
    loaded !== undefined && (loaded.canPublishOrganization || loaded.canPublishVariant);

  return (
    <div data-testid="lead-time-authority" data-state={policies.kind}>
      <Flex gap={8} wrap align="center" justify="space-between" style={{ marginBottom: 12 }}>
        <Flex gap={8} wrap align="center">
          <Select<string>
            aria-label={text.statusFilter}
            style={{ width: 140 }}
            value={status ?? ALL}
            options={[
              { value: ALL, label: text.allStatuses },
              ...STATUSES.map((code) => ({
                value: code,
                label: codeLabel(POLICY_STATUS_LABELS, code),
              })),
            ]}
            onChange={(next) => {
              patch({ [STATUS_PARAM]: next === ALL ? undefined : next });
            }}
          />
          <Button icon={<ReloadOutlined />} aria-label={text.refreshLabel} onClick={reload}>
            {actions.refresh}
          </Button>
        </Flex>
        <PublishPolicy context={context} page={loaded} allowed={canPublish} onDone={changed} />
      </Flex>
      {policies.kind === 'failed' && <FailureAlert failure={policies.failure} />}
      {policies.kind === 'loading' && <LoadingState />}
      {loaded?.items.length === 0 && <EmptyState description={text.empty} />}
      {loaded?.total !== undefined && loaded.total > loaded.items.length && (
        <Typography.Paragraph type="warning">
          {text.truncated(loaded.items.length, loaded.total)}
        </Typography.Paragraph>
      )}
      {loaded !== undefined && loaded.items.length > 0 && (
        <Table<LeadTimePolicy>
          size="middle"
          rowKey="id"
          columns={columns}
          dataSource={[...loaded.items]}
          scroll={{ x: 'max-content' }}
          loading={fetching}
          pagination={false}
          onRow={(policy) =>
            ({
              'data-policy': policy.id,
              'data-lifecycle': policy.lifecycle,
            }) as React.HTMLAttributes<HTMLElement>
          }
        />
      )}
      <RetirePolicy
        context={context}
        policy={retiring}
        stepUpSatisfied={loaded?.stepUpSatisfied ?? true}
        onClose={() => {
          setRetiring(undefined);
        }}
        onDone={changed}
      />
    </div>
  );
}

/** A version's scope in words: the organization default, a supplier, a category or a route. */
function PolicyScope({ policy }: { readonly policy: LeadTimePolicy }): React.JSX.Element {
  const kind = codeLabel(LEAD_SCOPE_KIND_LABELS, policy.scopeKind);
  switch (policy.scopeKind) {
    case 'VARIANT_SUPPLIER_ROUTE':
      return (
        <Flex vertical gap={2}>
          {policy.productVariantId === null ? null : (
            <VariantName
              identity={{ displayName: policy.displayName, skuCode: policy.skuCode }}
              productVariantId={policy.productVariantId}
            />
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.scopeRoute(policy.supplierCode ?? '—', policy.routeCode ?? '—')}
          </Typography.Text>
        </Flex>
      );
    case 'SUPPLIER':
      return <Typography.Text>{text.scopeSupplier(policy.supplierCode ?? '—')}</Typography.Text>;
    case 'PRODUCT_CATEGORY':
      return <Typography.Text>{text.scopeCategory(policy.categoryCode ?? '—')}</Typography.Text>;
    case 'ORGANIZATION':
      return <Typography.Text>{text.scopeOrganization}</Typography.Text>;
    default:
      return <Typography.Text>{kind}</Typography.Text>;
  }
}

interface PublishValues {
  readonly scopeKind?: ScopeKind;
  readonly productVariantId?: string;
  readonly supplierCode?: string;
  readonly routeCode?: string;
  readonly categoryCode?: string;
  readonly leadTimeDaysMin?: number | null;
  readonly leadTimeDaysMax?: number | null;
  readonly safetyDays?: number | null;
  readonly effectiveFrom?: Dayjs | null;
  readonly effectiveTo?: Dayjs | null;
  readonly lastReviewedAt?: Dayjs | null;
  readonly supersedesPolicyId?: string;
  readonly evidenceReference?: string;
  readonly reason?: string;
}

/** The stored scope key a draft would publish under, exactly as the database builds it. */
function scopeKey(values: PublishValues): string | undefined {
  if (values.scopeKind === undefined) return undefined;
  const part = (value: string | undefined): string => {
    const trimmed = value?.trim() ?? '';
    return trimmed === '' ? '-' : trimmed;
  };
  switch (values.scopeKind) {
    case 'ORGANIZATION':
      return 'ORGANIZATION|-|-|-|-';
    case 'SUPPLIER':
      return `SUPPLIER|-|${part(values.supplierCode)}|-|-`;
    case 'PRODUCT_CATEGORY':
      return `PRODUCT_CATEGORY|-|-|-|${part(values.categoryCode)}`;
    case 'VARIANT_SUPPLIER_ROUTE':
      return `VARIANT_SUPPLIER_ROUTE|${part(values.productVariantId)}|${part(values.supplierCode)}|${part(values.routeCode)}|-`;
  }
}

function codeRules(required: string): { required: true; whitespace: true; message: string }[] {
  return [{ required: true, whitespace: true, message: required }];
}

const codeFormat = {
  validator: (_: unknown, value: string | undefined) =>
    value === undefined || value.trim() === '' || CODE.test(value.trim())
      ? Promise.resolve()
      : Promise.reject(new Error(text.codeInvalid)),
};

/** Publish one lead-time and safety version, optionally superseding one of the same scope. */
function PublishPolicy({
  context,
  page,
  allowed,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly page: LeadTimePolicyPage | undefined;
  readonly allowed: boolean;
  readonly onDone: (success: string) => void;
}): React.JSX.Element {
  const submit = async (values: PublishValues): Promise<SubmitOutcome> => {
    const kind = values.scopeKind;
    if (kind === undefined || !values.effectiveFrom || !values.lastReviewedAt) return undefined;
    const code = (value: string | undefined): string | null => {
      const trimmed = value?.trim() ?? '';
      return trimmed === '' ? null : trimmed;
    };
    const variantRoute = kind === 'VARIANT_SUPPLIER_ROUTE';
    const outcome = await publishLeadTimePolicy(context, {
      scopeKind: kind,
      productVariantId: variantRoute ? code(values.productVariantId) : null,
      supplierCode: variantRoute || kind === 'SUPPLIER' ? code(values.supplierCode) : null,
      routeCode: variantRoute ? code(values.routeCode) : null,
      categoryCode: kind === 'PRODUCT_CATEGORY' ? code(values.categoryCode) : null,
      leadTimeDaysMin: values.leadTimeDaysMin ?? 0,
      leadTimeDaysMax: values.leadTimeDaysMax ?? 0,
      safetyDays: values.safetyDays ?? 0,
      reason: (values.reason ?? '').trim(),
      evidenceReference: (values.evidenceReference ?? '').trim(),
      lastReviewedAt: storeLocalToIso(values.lastReviewedAt),
      effectiveFrom: storeLocalToIso(values.effectiveFrom),
      effectiveTo: values.effectiveTo ? storeLocalToIso(values.effectiveTo) : null,
      fallbackOfId: null,
      supersedesPolicyId: code(values.supersedesPolicyId),
    });
    if (!outcome.ok) return outcome.failure;
    onDone(text.published(outcome.value.version));
    return undefined;
  };

  const now = toStoreDayjs(new Date().toISOString());
  return (
    <FormDrawer<PublishValues>
      trigger={{
        label: text.publish,
        type: 'primary',
        disabled: !allowed,
        disabledReason: text.noPublishRight,
      }}
      title={text.publishTitle}
      intro={
        <Flex vertical gap={8}>
          <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
            {text.publishIntro}
          </Typography.Paragraph>
          {page !== undefined && !page.stepUpSatisfied && <StepUpNotice />}
        </Flex>
      }
      initialValues={{
        scopeKind:
          page?.canPublishOrganization === false ? 'VARIANT_SUPPLIER_ROUTE' : 'ORGANIZATION',
        leadTimeDaysMin: 0,
        leadTimeDaysMax: 14,
        safetyDays: 7,
        ...(now === undefined ? {} : { effectiveFrom: now, lastReviewedAt: now }),
      }}
      submitText={text.publish}
      steps={[
        {
          key: 'scope',
          title: text.stepScope,
          fields: ['scopeKind', 'productVariantId', 'supplierCode', 'routeCode', 'categoryCode'],
          content: <ScopeFields context={context} page={page} />,
        },
        {
          key: 'values',
          title: text.stepValues,
          fields: [
            'leadTimeDaysMin',
            'leadTimeDaysMax',
            'safetyDays',
            'effectiveFrom',
            'effectiveTo',
            'lastReviewedAt',
            'supersedesPolicyId',
            'evidenceReference',
            'reason',
          ],
          content: <ValueFields page={page} />,
        },
      ]}
      onSubmit={submit}
    />
  );
}

/** The scope kind and the fields it needs, and nothing it does not. */
function ScopeFields({
  context,
  page,
}: {
  readonly context: ConsoleRequest;
  readonly page: LeadTimePolicyPage | undefined;
}): React.JSX.Element {
  const form = Form.useFormInstance<PublishValues>();
  const kind = Form.useWatch('scopeKind', form);
  const organizationAllowed = page?.canPublishOrganization === true;
  return (
    <>
      <Form.Item
        name="scopeKind"
        label={text.scopeKind}
        rules={[{ required: true, message: text.scopeKindRequired }]}
      >
        <Select<ScopeKind>
          options={SCOPE_KINDS.map((code) => ({
            value: code,
            label:
              code !== 'VARIANT_SUPPLIER_ROUTE' && !organizationAllowed
                ? `${codeLabel(LEAD_SCOPE_KIND_LABELS, code)}（${text.organizationOnly}）`
                : codeLabel(LEAD_SCOPE_KIND_LABELS, code),
            disabled: code !== 'VARIANT_SUPPLIER_ROUTE' && !organizationAllowed,
          }))}
        />
      </Form.Item>
      {kind === 'VARIANT_SUPPLIER_ROUTE' && (
        <Form.Item name="productVariantId" label={text.variant} rules={idRules(text.variant)}>
          <VariantPicker context={context} purpose="SUPPLY_POLICY_MANAGE" />
        </Form.Item>
      )}
      {(kind === 'SUPPLIER' || kind === 'VARIANT_SUPPLIER_ROUTE') && (
        <Form.Item
          name="supplierCode"
          label={text.supplierCode}
          rules={[...codeRules(text.supplierRequired), codeFormat]}
        >
          <Input maxLength={64} />
        </Form.Item>
      )}
      {kind === 'VARIANT_SUPPLIER_ROUTE' && (
        <Form.Item
          name="routeCode"
          label={text.routeCode}
          rules={[...codeRules(text.routeRequired), codeFormat]}
        >
          <Input maxLength={64} />
        </Form.Item>
      )}
      {kind === 'PRODUCT_CATEGORY' && (
        <Form.Item
          name="categoryCode"
          label={text.categoryCode}
          rules={[...codeRules(text.categoryRequired), codeFormat]}
        >
          <Input maxLength={64} />
        </Form.Item>
      )}
    </>
  );
}

/** Lead time, safety, the validity window, what it supersedes, and why. */
function ValueFields({
  page,
}: {
  readonly page: LeadTimePolicyPage | undefined;
}): React.JSX.Element {
  const form: FormInstance<PublishValues> = Form.useFormInstance<PublishValues>();
  const values = Form.useWatch([], form) as PublishValues | undefined;
  const key = values === undefined ? undefined : scopeKey(values);
  const from = values?.effectiveFrom ?? undefined;
  const candidates = (page?.items ?? []).filter(
    (policy) =>
      policy.scopeKey === key &&
      policy.status === 'ACTIVE' &&
      (policy.lifecycle === 'CURRENT' || policy.lifecycle === 'SCHEDULED') &&
      (from === undefined || Date.parse(policy.effectiveFrom) < from.valueOf()),
  );
  return (
    <>
      <Row gutter={12}>
        <Col xs={24} md={8}>
          <Form.Item
            name="leadTimeDaysMin"
            label={text.leadMin}
            rules={[{ required: true, message: text.leadMinRequired }]}
          >
            <InputNumber min={0} max={DAYS_MAX} precision={0} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item
            name="leadTimeDaysMax"
            label={text.leadMax}
            dependencies={['leadTimeDaysMin']}
            rules={[
              { required: true, message: text.leadMaxRequired },
              ({ getFieldValue }) => ({
                validator: (_: unknown, value: number | null | undefined) => {
                  const min = getFieldValue('leadTimeDaysMin') as number | null | undefined;
                  return value === null ||
                    value === undefined ||
                    min === null ||
                    min === undefined ||
                    min <= value
                    ? Promise.resolve()
                    : Promise.reject(new Error(text.leadOrder));
                },
              }),
            ]}
          >
            <InputNumber min={0} max={DAYS_MAX} precision={0} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item
            name="safetyDays"
            label={text.safety}
            rules={[{ required: true, message: text.safetyRequired }]}
          >
            <InputNumber min={0} max={DAYS_MAX} precision={0} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>
      <Row gutter={12}>
        <Col xs={24} md={8}>
          <Form.Item
            name="effectiveFrom"
            label={`${text.effectiveFrom}（${STORE_TIMEZONE_LABEL}）`}
            rules={[
              { required: true, message: text.effectiveFromRequired },
              {
                validator: (_: unknown, value: Dayjs | null | undefined) =>
                  !value || value.valueOf() >= Date.now() - 60_000
                    ? Promise.resolve()
                    : Promise.reject(new Error(text.effectiveFromPast)),
              },
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
            name="effectiveTo"
            label={`${text.effectiveTo}（${STORE_TIMEZONE_LABEL}）`}
            dependencies={['effectiveFrom']}
            rules={[
              ({ getFieldValue }) => ({
                validator: (_: unknown, value: Dayjs | null | undefined) => {
                  const start = getFieldValue('effectiveFrom') as Dayjs | null | undefined;
                  return !value || !start || value.isAfter(start)
                    ? Promise.resolve()
                    : Promise.reject(new Error(text.effectiveToOrder));
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
            name="lastReviewedAt"
            label={`${text.lastReviewedAt}（${STORE_TIMEZONE_LABEL}）`}
            dependencies={['effectiveFrom']}
            rules={[
              { required: true, message: text.lastReviewedRequired },
              ({ getFieldValue }) => ({
                validator: (_: unknown, value: Dayjs | null | undefined) => {
                  const start = getFieldValue('effectiveFrom') as Dayjs | null | undefined;
                  return !value || !start || !value.isAfter(start)
                    ? Promise.resolve()
                    : Promise.reject(new Error(text.lastReviewedOrder));
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
        name="supersedesPolicyId"
        label={text.supersedes}
        extra={candidates.length === 0 ? text.supersedesNone : text.supersedesHelp}
      >
        <Select<string>
          allowClear
          disabled={candidates.length === 0}
          options={candidates.map((policy) => ({
            value: policy.id,
            label: text.supersedesOption(policy.version, formatStoreTime(policy.effectiveFrom)),
          }))}
        />
      </Form.Item>
      <Form.Item
        name="evidenceReference"
        label={text.evidence}
        rules={[{ required: true, whitespace: true, message: text.evidenceRequired }]}
      >
        <Input maxLength={EVIDENCE_LIMIT} />
      </Form.Item>
      <Form.Item
        name="reason"
        label={text.reason}
        rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
      >
        <Input.TextArea rows={2} maxLength={REASON_LIMIT} showCount />
      </Form.Item>
    </>
  );
}

/** Retire one version: its own reason and evidence, and what retiring it does. */
function RetirePolicy({
  context,
  policy,
  stepUpSatisfied,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly policy: LeadTimePolicy | undefined;
  readonly stepUpSatisfied: boolean;
  readonly onClose: () => void;
  readonly onDone: (success: string) => void;
}): React.JSX.Element {
  const scheduled = policy?.lifecycle === 'SCHEDULED';
  return (
    <ActionModal<{ readonly reason?: string; readonly evidenceReference?: string }>
      open={policy !== undefined}
      onClose={onClose}
      title={text.retireTitle}
      consequence={scheduled ? text.retireScheduled : text.retireCurrent}
      summary={stepUpSatisfied ? undefined : <StepUpNotice />}
      okText={text.retire}
      danger
      onSubmit={async (values) => {
        if (policy === undefined) return undefined;
        const outcome = await retireAvailabilityPolicy(context, 'LEAD_TIME', policy.id, {
          reason: (values.reason ?? '').trim(),
          evidenceReference: (values.evidenceReference ?? '').trim(),
        });
        if (!outcome.ok) return outcome.failure;
        onDone(outcome.value.status === 'CANCELLED' ? text.cancelledScheduled : text.retired);
        return undefined;
      }}
    >
      <Form.Item
        name="evidenceReference"
        label={text.evidence}
        rules={[{ required: true, whitespace: true, message: text.evidenceRequired }]}
      >
        <Input maxLength={EVIDENCE_LIMIT} />
      </Form.Item>
      <Form.Item
        name="reason"
        label={text.reason}
        rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
      >
        <Input.TextArea rows={2} maxLength={REASON_LIMIT} showCount autoFocus />
      </Form.Item>
    </ActionModal>
  );
}
