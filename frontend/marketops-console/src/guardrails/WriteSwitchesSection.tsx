import {
  App,
  Descriptions,
  Flex,
  Form,
  Input,
  InputNumber,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { AllowlistEntry, WriteStatus, WriteSwitch } from '../api/priceWrites';
import {
  fetchAllowlist,
  fetchWriteStatus,
  fetchWriteSwitches,
  grantAllowlist,
  moveWriteSwitch,
  revokeAllowlist,
} from '../api/priceWrites';
import type { DiagnosisProduct } from '../api/storeDiagnosis';
import { fetchStoreDiagnosis } from '../api/storeDiagnosis';
import { codeLabel } from '../i18n';
import { writeSwitchesText as text } from '../i18n/zh/guardrails';
import { GATE_REASON_LABELS } from '../i18n/zh/pricing';
import { ActionModal, DateTime, FailureAlert, InfoTip, SectionCard } from '../ui';
import type { TagColor } from '../ui';

type Load =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure }
  | {
      readonly kind: 'loaded';
      readonly status: WriteStatus;
      readonly switches: readonly WriteSwitch[];
      readonly allowlist: readonly AllowlistEntry[];
    };

interface ReasonValues {
  readonly reason?: string;
}

interface GrantValues {
  readonly scope?: 'STORE' | 'LISTING';
  readonly variantId?: string;
  readonly hours?: number;
  readonly reason?: string;
}

const DEFAULT_HOURS = 24;
const MAXIMUM_HOURS = 720;
const MILLIS_PER_HOUR = 3_600_000;

/** Whether a listing is in a seller promotion: its buyer price differs from the price ceiling. */
function inPromotion(product: DiagnosisProduct): boolean {
  const discount = product.price?.discountPrice ?? null;
  const selling = product.price?.sellingPrice ?? null;
  return discount !== null && selling !== null && Number(discount) !== Number(selling);
}

function switchState(
  switches: readonly WriteSwitch[],
  scopeKind: string,
  capabilityId?: string | null,
) {
  const found = switches.find(
    (item) =>
      item.scopeKind === scopeKind &&
      item.status === 'ACTIVE' &&
      (scopeKind !== 'CAPABILITY' || item.capabilityId === capabilityId),
  );
  if (found === undefined) return <Tag>{text.switchUnset}</Tag>;
  return found.state === 'ENABLED' ? (
    <Tag color="success">{text.switchEnabled}</Tag>
  ) : (
    <Tag color="error">{text.switchDisabled}</Tag>
  );
}

/**
 * Whether the platform may change prices on the marketplace itself, and where: the deployment's
 * production-write switch, the price-write switches and the pilot allowlist (W1, 2026-10-01).
 */
export function WriteSwitchesSection({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [products, setProducts] = useState<readonly DiagnosisProduct[]>([]);
  const [revoking, setRevoking] = useState<AllowlistEntry | undefined>(undefined);

  useEffect(() => {
    let live = true;
    void Promise.all([
      fetchWriteStatus(context, storeId),
      fetchWriteSwitches(context),
      fetchAllowlist(context),
    ]).then(([status, switches, allowlist]) => {
      if (!live) return;
      if (!status.ok) setLoad({ kind: 'failed', failure: status.failure });
      else if (!switches.ok) setLoad({ kind: 'failed', failure: switches.failure });
      else if (!allowlist.ok) setLoad({ kind: 'failed', failure: allowlist.failure });
      else {
        const entries = allowlist.value.filter((entry) => entry.storeId === storeId);
        setLoad({
          kind: 'loaded',
          status: status.value,
          switches: switches.value,
          allowlist: entries,
        });
        // Single-listing entries are labelled by their SKU, which the store's listings carry.
        if (entries.some((entry) => entry.platformListingVariantId !== null)) {
          void fetchStoreDiagnosis(context, storeId).then((outcome) => {
            if (live && outcome.ok) setProducts(outcome.value.products);
          });
        }
      }
    });
    return () => {
      live = false;
    };
  }, [context, storeId, generation]);

  const reload = (): void => {
    setGeneration((value) => value + 1);
  };

  const productLabel = (variantId: string | null): string => {
    if (variantId === null) return text.wholeStore;
    const product = products.find((item) => item.variantId === variantId);
    return product === undefined
      ? `${text.oneListing} · ${variantId.slice(0, 8)}`
      : `${product.nativeSkuKey ?? product.nativeListingKey}${product.title === null ? '' : ` · ${product.title}`}`;
  };

  let body: React.JSX.Element;
  if (load.kind === 'loading') {
    body = <Typography.Text type="secondary">…</Typography.Text>;
  } else if (load.kind === 'failed') {
    body = <FailureAlert failure={load.failure} />;
  } else {
    const { status, switches, allowlist } = load;
    const canEnable = status.productionWritesEnabled && status.capabilityId !== null;
    const now = Date.now();
    const allowlistColumns: TableColumnsType<AllowlistEntry> = [
      {
        key: 'scope',
        title: text.scope,
        render: (_, entry) => productLabel(entry.platformListingVariantId),
      },
      {
        key: 'validity',
        title: text.validity,
        render: (_, entry) => (
          <Space size={4} wrap>
            <DateTime value={entry.validFrom} />~<DateTime value={entry.validUntil} />
          </Space>
        ),
      },
      {
        key: 'status',
        title: text.status,
        render: (_, entry) => {
          const expired = entry.status === 'ACTIVE' && Date.parse(entry.validUntil) <= now;
          const label =
            entry.status !== 'ACTIVE' ? text.revoked : expired ? text.expired : text.active;
          const color: TagColor = entry.status !== 'ACTIVE' || expired ? 'default' : 'success';
          return <Tag color={color}>{label}</Tag>;
        },
      },
      {
        key: 'reason',
        title: text.reason,
        render: (_, entry) => <Typography.Text type="secondary">{entry.reason}</Typography.Text>,
      },
      {
        key: 'actions',
        title: '',
        render: (_, entry) =>
          entry.status === 'ACTIVE' && Date.parse(entry.validUntil) > now ? (
            <Typography.Link
              type="danger"
              onClick={() => {
                setRevoking(entry);
              }}
            >
              {text.revoke}
            </Typography.Link>
          ) : null,
      },
    ];

    body = (
      <Flex vertical gap={16}>
        <Descriptions size="small" column={{ xs: 1, md: 2 }} bordered>
          <Descriptions.Item label={text.productionWrites}>
            {status.productionWritesEnabled ? (
              <Tag color="success">{text.on}</Tag>
            ) : (
              <Tag>{text.off}</Tag>
            )}
          </Descriptions.Item>
          <Descriptions.Item label={text.capability}>
            {status.capabilityId === null
              ? text.capabilityNone
              : (status.capabilityVerification ?? '—')}
          </Descriptions.Item>
          <Descriptions.Item label={text.globalSwitch}>
            {switchState(switches, 'GLOBAL')}
          </Descriptions.Item>
          <Descriptions.Item label={text.capabilitySwitch}>
            {switchState(switches, 'CAPABILITY', status.capabilityId)}
          </Descriptions.Item>
          <Descriptions.Item label={text.readiness} span="filled">
            {status.reasons.length === 0 && status.productionWritesEnabled ? (
              <Typography.Text type="success">{text.ready}</Typography.Text>
            ) : (
              <Space size={[4, 4]} wrap>
                {!status.productionWritesEnabled && (
                  <Tag color="warning">
                    {codeLabel(GATE_REASON_LABELS, 'PRODUCTION_WRITES_DISABLED')}
                  </Tag>
                )}
                {status.reasons.map((reason) => (
                  <Tag key={reason} color="warning">
                    {codeLabel(GATE_REASON_LABELS, reason)}
                  </Tag>
                ))}
              </Space>
            )}
          </Descriptions.Item>
        </Descriptions>
        <Space size={8} wrap>
          <ActionModal<ReasonValues>
            trigger={{ label: text.disable, size: 'small', danger: true }}
            title={text.disableTitle}
            consequence={text.disableConsequence}
            danger
            width={520}
            onSubmit={async (values) => {
              const outcome = await moveWriteSwitch(context, 'disable', {
                scopeKind: 'GLOBAL',
                scopeReference: null,
                storeId: null,
                reason: (values.reason ?? '').trim(),
              });
              if (!outcome.ok) return outcome.failure;
              void message.success(text.moved);
              reload();
              return undefined;
            }}
          >
            <Form.Item<ReasonValues>
              name="reason"
              label={text.reason}
              rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
            >
              <Input.TextArea rows={2} maxLength={500} showCount />
            </Form.Item>
          </ActionModal>
          <ActionModal<ReasonValues>
            trigger={{
              label: text.enable,
              size: 'small',
              disabled: !canEnable,
              disabledReason: text.enableBlocked,
            }}
            title={text.enableTitle}
            consequence={text.enableConsequence}
            width={520}
            onSubmit={async (values) => {
              const reason = (values.reason ?? '').trim();
              if (status.capabilityId === null) return undefined;
              const capability = await moveWriteSwitch(context, 'enable', {
                scopeKind: 'CAPABILITY',
                scopeReference: status.capabilityId,
                storeId: null,
                reason,
              });
              if (!capability.ok) return capability.failure;
              const global = await moveWriteSwitch(context, 'enable', {
                scopeKind: 'GLOBAL',
                scopeReference: null,
                storeId: null,
                reason,
              });
              if (!global.ok) return global.failure;
              void message.success(text.moved);
              reload();
              return undefined;
            }}
          >
            <Form.Item<ReasonValues>
              name="reason"
              label={text.reason}
              rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
            >
              <Input.TextArea rows={2} maxLength={500} showCount />
            </Form.Item>
          </ActionModal>
        </Space>
        <Flex vertical gap={8}>
          <Space size={8} wrap>
            <Typography.Text strong>{text.allowlist}</Typography.Text>
            <ActionModal<GrantValues>
              trigger={{ label: text.grant, size: 'small', disabled: status.platformCode === null }}
              title={text.grantTitle}
              consequence={text.grantConsequence}
              initialValues={{ scope: 'LISTING', hours: DEFAULT_HOURS }}
              width={560}
              onOpen={() => {
                void fetchStoreDiagnosis(context, storeId).then((outcome) => {
                  if (outcome.ok) setProducts(outcome.value.products);
                });
              }}
              onSubmit={async (values) => {
                if (status.platformCode === null) return undefined;
                const from = new Date();
                const until = new Date(
                  from.getTime() + (values.hours ?? DEFAULT_HOURS) * MILLIS_PER_HOUR,
                );
                const outcome = await grantAllowlist(context, {
                  platformCode: status.platformCode,
                  storeId,
                  platformListingVariantId:
                    values.scope === 'STORE' ? null : (values.variantId ?? null),
                  validFrom: from.toISOString(),
                  validUntil: until.toISOString(),
                  reason: (values.reason ?? '').trim(),
                });
                if (!outcome.ok) return outcome.failure;
                void message.success(text.granted);
                reload();
                return undefined;
              }}
            >
              {(form) => (
                <>
                  <Form.Item<GrantValues> name="scope" label={text.scope}>
                    <Radio.Group
                      options={[
                        { value: 'LISTING', label: text.oneListing },
                        { value: 'STORE', label: text.wholeStore },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item<GrantValues> noStyle dependencies={['scope']}>
                    {() =>
                      form.getFieldValue('scope') === 'STORE' ? null : (
                        <Form.Item<GrantValues>
                          name="variantId"
                          label={text.listing}
                          rules={[{ required: true, message: text.listingRequired }]}
                        >
                          <Select
                            showSearch={{ optionFilterProp: 'label' }}
                            options={products.map((product) => ({
                              value: product.variantId,
                              label: `${product.nativeSkuKey ?? product.nativeListingKey}${
                                inPromotion(product) ? `（${text.inPromotion}）` : ''
                              }`,
                              disabled: inPromotion(product),
                            }))}
                          />
                        </Form.Item>
                      )
                    }
                  </Form.Item>
                  <Form.Item<GrantValues> name="hours" label={text.hours}>
                    <InputNumber min={1} max={MAXIMUM_HOURS} precision={0} />
                  </Form.Item>
                  <Form.Item<GrantValues>
                    name="reason"
                    label={text.reason}
                    rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
                  >
                    <Input.TextArea rows={2} maxLength={500} showCount />
                  </Form.Item>
                </>
              )}
            </ActionModal>
          </Space>
          <Table<AllowlistEntry>
            rowKey="id"
            size="small"
            columns={allowlistColumns}
            dataSource={[...allowlist]}
            pagination={false}
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: text.noAllowlist }}
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
      <ActionModal<ReasonValues>
        open={revoking !== undefined}
        onClose={() => {
          setRevoking(undefined);
        }}
        title={text.revokeTitle}
        consequence={text.revokeConsequence}
        danger
        width={520}
        onSubmit={async (values) => {
          if (revoking === undefined) return undefined;
          const outcome = await revokeAllowlist(context, revoking.id, {
            reason: (values.reason ?? '').trim(),
            expectedVersion: revoking.version,
          });
          if (!outcome.ok) return outcome.failure;
          void message.success(text.revokedDone);
          reload();
          return undefined;
        }}
      >
        <Form.Item<ReasonValues>
          name="reason"
          label={text.reason}
          rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
        >
          <Input.TextArea rows={2} maxLength={500} showCount />
        </Form.Item>
      </ActionModal>
    </SectionCard>
  );
}
