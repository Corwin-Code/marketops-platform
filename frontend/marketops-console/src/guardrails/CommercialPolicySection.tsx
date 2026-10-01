import {
  App,
  Alert,
  Descriptions,
  Flex,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  LimitInput,
  LimitKind,
  PolicyLimit,
  PolicyVersion,
  StorePolicy,
} from '../api/commercialPolicy';
import { fetchStorePolicy, publishStorePolicy } from '../api/commercialPolicy';
import { formatMoney, formatPercent, shiftDecimal } from '../format';
import { codeLabel } from '../i18n';
import {
  commercialPolicyText as text,
  LIMIT_LABELS,
  OBJECTIVE_LABELS,
  POLICY_SCOPE_LABELS,
  POLICY_STATUS_LABELS,
} from '../i18n/zh/guardrails';
import { GUARDRAIL_REASON_LABELS } from '../i18n/zh/pricing';
import { ActionModal, DateTime, EmptyState, FailureAlert, InfoTip, SectionCard } from '../ui';
import type { TagColor } from '../ui';

type Load =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure }
  | { readonly kind: 'loaded'; readonly policy: StorePolicy };

/** The publish form: each limit in the unit it is shown in, keyed by its code. */
type PublishValues = Record<string, number | string | undefined> & {
  readonly lifecycleObjective?: string;
  readonly reason?: string;
};

/** One row of the limits table. */
interface LimitLine {
  readonly kind: LimitKind;
  readonly limit: PolicyLimit | undefined;
}

const SECONDS_PER_HOUR = 3600;

/**
 * The limits the Owner decided on 2026-10-01, in the unit the form shows them in: percent for
 * rates, hours for durations, the store's currency for amounts and units for counts.
 */
const OWNER_DEFAULTS: Readonly<Record<string, number>> = {
  MIN_DATA_COMPLETENESS: 37.5,
  MAX_INPUT_AGE_SECONDS: 72,
  MIN_CONTRIBUTION_MARGIN: 15,
  MIN_UNIT_CONTRIBUTION_PROFIT: 0,
  MAX_SINGLE_CHANGE_RATE: 15,
  MAX_DAILY_CHANGE_RATE: 15,
  COOLDOWN_SECONDS: 72,
  MIN_AVAILABLE_UNITS: 1,
};

const DEFAULT_OBJECTIVE = 'GROWTH';

const STATUS_COLORS: Readonly<Record<string, TagColor>> = {
  ACTIVE: 'success',
  ENDED: 'default',
  CANCELLED: 'default',
};

/** A configured limit in the unit the form shows it in. */
function shown(kind: LimitKind, limit: PolicyLimit | undefined): number | undefined {
  if (limit === undefined) return undefined;
  switch (kind.valueKind) {
    case 'RATE':
      return limit.rateValue === null ? undefined : Number(shiftDecimal(limit.rateValue, 2));
    case 'AMOUNT':
      return limit.amountValue === null ? undefined : Number(limit.amountValue);
    case 'COUNT':
      return limit.countValue ?? undefined;
    case 'DURATION_SECONDS':
      return limit.durationSeconds === null ? undefined : limit.durationSeconds / SECONDS_PER_HOUR;
    default:
      return undefined;
  }
}

/** What the form's value publishes as: exactly the typed value the limit's kind names. */
function published(kind: LimitKind, value: number): LimitInput {
  switch (kind.valueKind) {
    case 'RATE':
      return { limitCode: kind.code, rateValue: Number((value / 100).toFixed(6)) };
    case 'AMOUNT':
      return { limitCode: kind.code, amountValue: Number(value.toFixed(2)) };
    case 'COUNT':
      return { limitCode: kind.code, countValue: Math.round(value) };
    default:
      return { limitCode: kind.code, durationSeconds: Math.round(value * SECONDS_PER_HOUR) };
  }
}

/** A configured limit as read. */
function formatLimit(kind: LimitKind, limit: PolicyLimit | undefined, currency: string): string {
  if (limit === undefined) return '—';
  switch (kind.valueKind) {
    case 'RATE':
      return formatPercent(limit.rateValue);
    case 'AMOUNT':
      return formatMoney(limit.amountValue, currency);
    case 'COUNT':
      return limit.countValue === null ? '—' : `${String(limit.countValue)} ${text.units}`;
    case 'DURATION_SECONDS':
      return limit.durationSeconds === null
        ? '—'
        : `${String(Math.round((limit.durationSeconds / SECONDS_PER_HOUR) * 100) / 100)} ${text.hours}`;
    default:
      return '—';
  }
}

/** The field of one limit, in the unit it is shown in. */
function LimitField({
  kind,
  currency,
}: {
  readonly kind: LimitKind;
  readonly currency: string;
}): React.JSX.Element {
  const label = codeLabel(LIMIT_LABELS, kind.code);
  const rules = [{ required: true, message: text.valueRequired }];
  switch (kind.valueKind) {
    case 'RATE':
      return (
        <Form.Item<PublishValues> name={kind.code} label={label} rules={rules}>
          <InputNumber min={0} max={100} step={0.5} suffix="%" />
        </Form.Item>
      );
    case 'AMOUNT':
      return (
        <Form.Item<PublishValues> name={kind.code} label={label} rules={rules}>
          <InputNumber min={0} step={10} precision={2} suffix={currency} />
        </Form.Item>
      );
    case 'COUNT':
      return (
        <Form.Item<PublishValues> name={kind.code} label={label} rules={rules}>
          <InputNumber min={0} step={1} precision={0} suffix={text.units} />
        </Form.Item>
      );
    default:
      return (
        <Form.Item<PublishValues> name={kind.code} label={label} rules={rules}>
          <InputNumber min={0} step={1} suffix={text.hours} />
        </Form.Item>
      );
  }
}

/**
 * The store's commercial policy: the limits every price change is checked against, published as
 * versions from this page.
 */
export function CommercialPolicySection({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    let live = true;
    void fetchStorePolicy(context, storeId).then((outcome) => {
      if (!live) return;
      setLoad(
        outcome.ok
          ? { kind: 'loaded', policy: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, storeId, generation]);

  let body: React.JSX.Element;
  if (load.kind === 'loading') {
    body = <Typography.Text type="secondary">…</Typography.Text>;
  } else if (load.kind === 'failed') {
    body = <FailureAlert failure={load.failure} />;
  } else {
    const { inForce, limits, limitKinds, versions } = load.policy;
    const currency = load.policy.currencyCode ?? '';
    const required = limitKinds.filter((kind) => kind.requiredForPriceWrite);
    const configured = new Map(limits.map((limit) => [limit.limitCode, limit]));
    const lines: LimitLine[] = required.map((kind) => ({
      kind,
      limit: configured.get(kind.code),
    }));
    const own = inForce?.scopeKind === 'STORE' ? inForce : null;
    const initialValues: PublishValues = {
      lifecycleObjective: own?.lifecycleObjective ?? DEFAULT_OBJECTIVE,
    };
    for (const kind of required) {
      const value =
        own === null ? OWNER_DEFAULTS[kind.code] : shown(kind, configured.get(kind.code));
      if (value !== undefined) initialValues[kind.code] = value;
    }

    const limitColumns: TableColumnsType<LimitLine> = [
      {
        key: 'limit',
        title: text.limit,
        render: (_, line) => codeLabel(LIMIT_LABELS, line.kind.code),
      },
      {
        key: 'value',
        title: text.value,
        render: (_, line) => formatLimit(line.kind, line.limit, currency),
      },
      {
        key: 'breach',
        title: text.breach,
        render: (_, line) => (
          <Typography.Text type="secondary">
            {codeLabel(GUARDRAIL_REASON_LABELS, line.kind.guardrailCode)}
          </Typography.Text>
        ),
      },
    ];
    const versionColumns: TableColumnsType<PolicyVersion> = [
      { key: 'version', title: text.version, render: (_, version) => version.policyVersion },
      {
        key: 'status',
        title: text.status,
        render: (_, version) => (
          <Tag color={STATUS_COLORS[version.status] ?? 'default'}>
            {codeLabel(POLICY_STATUS_LABELS, version.status)}
          </Tag>
        ),
      },
      {
        key: 'range',
        title: text.effectiveRange,
        render: (_, version) => (
          <Space size={4} wrap>
            <DateTime value={version.effectiveFrom} />~
            {version.effectiveTo === null ? text.until : <DateTime value={version.effectiveTo} />}
          </Space>
        ),
      },
      {
        key: 'publisher',
        title: text.publisher,
        render: (_, version) =>
          version.publishedByViewer ? text.publisherYou : text.publisherOther,
      },
      {
        key: 'reason',
        title: text.reason,
        render: (_, version) => (
          <Typography.Text type="secondary">{version.reason}</Typography.Text>
        ),
      },
    ];

    body = (
      <Flex vertical gap={16}>
        {inForce === null ? (
          <EmptyState description={text.noPolicy} />
        ) : (
          <Flex vertical gap={8}>
            {inForce.scopeKind !== 'STORE' && (
              <Alert
                type="info"
                showIcon
                title={text.widerScope(
                  codeLabel(POLICY_SCOPE_LABELS, inForce.scopeKind),
                  inForce.policyVersion,
                )}
              />
            )}
            <Descriptions size="small" column={{ xs: 1, md: 2 }} bordered title={text.inForce}>
              <Descriptions.Item label={text.version}>{inForce.policyVersion}</Descriptions.Item>
              <Descriptions.Item label={text.scope}>
                {codeLabel(POLICY_SCOPE_LABELS, inForce.scopeKind)}
              </Descriptions.Item>
              <Descriptions.Item label={text.objective}>
                {codeLabel(OBJECTIVE_LABELS, inForce.lifecycleObjective)}
              </Descriptions.Item>
              <Descriptions.Item label={text.effectiveFrom}>
                <DateTime value={inForce.effectiveFrom} />
              </Descriptions.Item>
              <Descriptions.Item label={text.publisher}>
                {inForce.publishedByViewer ? text.publisherYou : text.publisherOther}
              </Descriptions.Item>
              <Descriptions.Item label={text.reason}>{inForce.reason}</Descriptions.Item>
            </Descriptions>
            <Table<LimitLine>
              rowKey={(line) => line.kind.code}
              size="small"
              columns={limitColumns}
              dataSource={lines}
              pagination={false}
              scroll={{ x: 'max-content' }}
            />
          </Flex>
        )}
        <Flex vertical gap={8}>
          <Space size={8} wrap>
            <Typography.Text strong>{text.history}</Typography.Text>
            <ActionModal<PublishValues>
              trigger={{ label: text.publish, size: 'small', disabled: currency === '' }}
              title={text.publishTitle}
              consequence={text.publishConsequence}
              summary={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {text.defaultsNote}
                </Typography.Text>
              }
              initialValues={initialValues}
              width={600}
              onSubmit={async (values) => {
                const chosen: LimitInput[] = [];
                for (const kind of required) {
                  const value = values[kind.code];
                  if (typeof value !== 'number') return undefined;
                  chosen.push(published(kind, value));
                }
                const outcome = await publishStorePolicy(context, storeId, {
                  lifecycleObjective: values.lifecycleObjective ?? DEFAULT_OBJECTIVE,
                  limits: chosen,
                  reason: (values.reason ?? '').trim(),
                });
                if (!outcome.ok) return outcome.failure;
                void message.success(text.published);
                setGeneration((value) => value + 1);
                return undefined;
              }}
            >
              <Form.Item<PublishValues> name="lifecycleObjective" label={text.objective}>
                <Select
                  options={Object.entries(OBJECTIVE_LABELS).map(([value, label]) => ({
                    value,
                    label,
                  }))}
                />
              </Form.Item>
              {required.map((kind) => (
                <LimitField key={kind.code} kind={kind} currency={currency} />
              ))}
              <Form.Item<PublishValues>
                name="reason"
                label={text.reason}
                rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
              >
                <Input.TextArea
                  rows={2}
                  maxLength={500}
                  showCount
                  placeholder={text.reasonPlaceholder}
                />
              </Form.Item>
            </ActionModal>
          </Space>
          <Table<PolicyVersion>
            rowKey="policyId"
            size="small"
            columns={versionColumns}
            dataSource={[...versions]}
            pagination={false}
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: text.noHistory }}
          />
        </Flex>
      </Flex>
    );
  }

  return (
    <SectionCard
      title={
        <Space size={4}>
          {text.title}
          <InfoTip title={text.hint} long />
        </Space>
      }
    >
      {body}
    </SectionCard>
  );
}
