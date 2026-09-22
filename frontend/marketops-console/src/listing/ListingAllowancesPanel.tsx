import { ReloadOutlined } from '@ant-design/icons';
import { Alert, App, Button, Flex, Form, Input, Radio, Select, Table, Tag, Typography } from 'antd';
import type { FormInstance, TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  AllowanceOverview,
  AllowanceReservePolicy,
  ExposureAllowance,
} from '../api/listingAllowances';
import { fetchAllowances, publishAllowance, retireAllowance } from '../api/listingAllowances';
import { formatDecimal, formatMoney, formatPercent } from '../format';
import { dialog } from '../i18n/zh/common';
import { allowanceText as text } from '../i18n/zh/listingAllowances';
import {
  ActionModal,
  DateTime,
  EmptyState,
  InfoTip,
  LoadingState,
  SectionCard,
  SectionCollapse,
} from '../ui';
import type { SubmitOutcome } from '../ui';
import { Code, InstantField, ListingProblem, Stack, codeText } from './ListingCommon';

/** The four axes, in the order the launch check lists them. */
const AXES = [
  'CONCURRENT_LISTINGS',
  'AFFECTED_VARIANTS',
  'REVENUE_EXPOSURE',
  'CATEGORY_SHARE',
] as const;
const COUNT_AXES: readonly string[] = ['CONCURRENT_LISTINGS', 'AFFECTED_VARIANTS'];
const DECIMAL = /^\d{1,14}(\.\d{1,4})?$/;

type ScopeKind = 'STORE' | 'PLATFORM' | 'ORGANIZATION';

interface PublishValues {
  readonly scopeKind?: ScopeKind;
  readonly storeId?: string;
  readonly platformCode?: string;
  readonly axisCode?: string;
  readonly limitValue?: string;
  readonly reserveValue?: string;
  readonly effectiveFrom?: string;
  readonly evidenceReference?: string;
  readonly reason?: string;
}

interface ReasonValues {
  readonly reason?: string;
}

type Loaded =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ok'; readonly overview: AllowanceOverview }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

// ------------------------------------------------------------------ decimals

/** A decimal string as an integer of ten-thousandths, so nothing is compared as a float. */
function scaled(value: string | null | undefined): bigint | undefined {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(value?.trim() ?? '');
  if (match === null) return undefined;
  const fraction = (match[3] ?? '').padEnd(4, '0').slice(0, 4);
  const magnitude = BigInt(`${match[2] ?? '0'}${fraction}`);
  return match[1] === '-' ? -magnitude : magnitude;
}

function unscaled(value: bigint): string {
  const negative = value < 0n;
  const digits = (negative ? -value : value).toString().padStart(5, '0');
  const whole = digits.slice(0, -4);
  const fraction = digits.slice(-4).replace(/0+$/, '');
  return `${negative ? '-' : ''}${whole}${fraction === '' ? '' : `.${fraction}`}`;
}

/** A value in its unit: a count, a ratio with its percentage, or money. */
function amount(value: string | null | undefined, unit: string): string {
  if (value === null || value === undefined) return '—';
  if (unit === 'COUNT') return formatDecimal(value);
  if (unit === 'RATIO') return `${formatDecimal(value)}（${formatPercent(value)}）`;
  return formatMoney(value, unit);
}

/** A form rule's answer: resolved when there is no problem, rejected with it otherwise. */
function settle(problem: string | undefined): Promise<void> {
  return problem === undefined ? Promise.resolve() : Promise.reject(new Error(problem));
}

// ------------------------------------------------------------------ scope

function scopeName(allowance: ExposureAllowance): string {
  if (allowance.scopeKind === 'STORE') return allowance.storeName ?? text.storeUnknown;
  if (allowance.scopeKind === 'PLATFORM') return allowance.platformCode ?? '—';
  return text.organizationScope;
}

function sameScope(
  allowance: ExposureAllowance,
  scopeKind: ScopeKind | undefined,
  storeId: string | undefined,
  platformCode: string | undefined,
): boolean {
  if (allowance.scopeKind !== scopeKind) return false;
  if (scopeKind === 'STORE') return allowance.storeId === storeId;
  if (scopeKind === 'PLATFORM') return allowance.platformCode === platformCode;
  return true;
}

/** Whether a package at its scope governs any listing the allowance scope covers (mirrors the backend). */
function governs(
  policy: AllowanceReservePolicy,
  scopeKind: ScopeKind,
  storeId: string | undefined,
  platformCode: string | undefined,
  storePlatform: string | null,
): boolean {
  if (scopeKind === 'ORGANIZATION' || policy.scopeKind === 'ORGANIZATION') return true;
  if (scopeKind === 'PLATFORM') {
    return policy.scopeKind === 'PLATFORM'
      ? policy.platformCode === platformCode
      : policy.storePlatformCode === platformCode;
  }
  return policy.scopeKind === 'PLATFORM'
    ? policy.platformCode === storePlatform
    : policy.storeId === storeId;
}

/** The unit the backend will derive for the chosen axis and scope, or undefined when none can be. */
function unitFor(
  overview: AllowanceOverview,
  axis: string | undefined,
  scopeKind: ScopeKind | undefined,
  storeId: string | undefined,
  platformCode: string | undefined,
): string | undefined {
  if (axis === undefined) return undefined;
  if (COUNT_AXES.includes(axis)) return 'COUNT';
  if (axis === 'CATEGORY_SHARE') return 'RATIO';
  const stores = overview.stores.filter(
    (store) =>
      scopeKind === 'ORGANIZATION' ||
      (scopeKind === 'PLATFORM' && store.platformCode === platformCode) ||
      (scopeKind === 'STORE' && store.storeId === storeId),
  );
  const currencies = new Set(stores.map((store) => store.currencyCode));
  const only = [...currencies][0];
  return currencies.size === 1 && only !== null && only !== undefined ? only : undefined;
}

function unitLabel(unit: string | undefined, axis: string | undefined): string {
  if (axis === undefined) return '—';
  if (unit === 'COUNT') return text.unitCount;
  if (unit === 'RATIO') return text.unitRatio;
  return unit === undefined ? text.unitCurrencyUnknown : text.unitCurrency(unit);
}

// ------------------------------------------------------------------ panel

export interface ListingAllowancesPanelProps {
  readonly context: ConsoleRequest;
  /** The store chosen in the header, offered first when publishing. */
  readonly storeId: string;
}

/**
 * The Owner's launch allowance maintenance: what each axis allows, what is
 * occupied, publishing a new version and retiring one.
 */
export function ListingAllowancesPanel({
  context,
  storeId,
}: ListingAllowancesPanelProps): React.JSX.Element {
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    let live = true;
    void fetchAllowances(context).then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { kind: 'ok', overview: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, generation]);

  const reload = (): void => {
    setGeneration((value) => value + 1);
  };

  const help = (
    <InfoTip
      long
      title={
        <Flex vertical gap={6}>
          <Typography.Text strong>{text.axesHelpTitle}</Typography.Text>
          {text.axesHelp.map((line) => (
            <Typography.Text key={line}>{line}</Typography.Text>
          ))}
          <Typography.Text type="secondary">{text.releaseNote}</Typography.Text>
        </Flex>
      }
    />
  );

  return (
    <section aria-label={text.axesHelpTitle} data-state={loaded.kind}>
      <Stack>
        <Alert
          type="info"
          showIcon
          title={
            <span>
              {text.intro}
              {help}
            </span>
          }
          description={text.introBoundary}
        />
        <Flex gap={8} wrap justify="flex-end">
          <Button icon={<ReloadOutlined />} onClick={reload}>
            {text.refresh}
          </Button>
          {loaded.kind === 'ok' && (
            <PublishAllowance
              context={context}
              overview={loaded.overview}
              storeId={storeId}
              onPublished={reload}
            />
          )}
        </Flex>
        {loaded.kind === 'loading' && <LoadingState rows={6} />}
        {loaded.kind === 'failed' && <ListingProblem failure={loaded.failure} />}
        {loaded.kind === 'ok' &&
          AXES.map((axis) => (
            <AxisSection
              key={axis}
              axis={axis}
              context={context}
              allowances={loaded.overview.allowances.filter((row) => row.axisCode === axis)}
              onChanged={reload}
            />
          ))}
      </Stack>
    </section>
  );
}

// ------------------------------------------------------------------ one axis

function AxisSection({
  axis,
  context,
  allowances,
  onChanged,
}: {
  readonly axis: string;
  readonly context: ConsoleRequest;
  readonly allowances: readonly ExposureAllowance[];
  readonly onChanged: () => void;
}): React.JSX.Element {
  const live = allowances.filter(
    (row) => row.lifecycle === 'CURRENT' || row.lifecycle === 'SCHEDULED',
  );
  const history = allowances.filter(
    (row) => row.lifecycle === 'ENDED' || row.lifecycle === 'RETIRED',
  );
  const scopeColumn: TableColumnsType<ExposureAllowance>[number] = {
    key: 'scope',
    title: text.columnScope,
    render: (_, row) => (
      <Flex gap={4} align="center" wrap>
        <Code family="allowanceScope" code={row.scopeKind} />
        <Typography.Text>{scopeName(row)}</Typography.Text>
      </Flex>
    ),
  };
  const valueColumns: TableColumnsType<ExposureAllowance> = [
    {
      key: 'limit',
      title: text.columnLimit,
      align: 'right',
      render: (_, row) => amount(row.limitValue, row.unitCode),
    },
    {
      key: 'reserve',
      title: text.columnReserve,
      align: 'right',
      render: (_, row) => amount(row.reserveValue, row.unitCode),
    },
  ];
  const evidenceColumn: TableColumnsType<ExposureAllowance>[number] = {
    key: 'evidence',
    title: text.columnEvidence,
    render: (_, row) => (
      <Flex vertical gap={0} style={{ maxWidth: 280 }}>
        <Typography.Text ellipsis={{ tooltip: row.evidenceReference }}>
          {row.evidenceReference}
        </Typography.Text>
        {row.publishReason !== null && (
          <Typography.Text
            type="secondary"
            style={{ fontSize: 12 }}
            ellipsis={{ tooltip: row.publishReason }}
          >
            {text.reasonLabel}：{row.publishReason}
          </Typography.Text>
        )}
      </Flex>
    ),
  };
  const versionColumn: TableColumnsType<ExposureAllowance>[number] = {
    key: 'version',
    title: text.columnVersion,
    render: (_, row) => (
      <Flex gap={4} align="center" wrap>
        <Typography.Text>{text.versionLabel(row.version)}</Typography.Text>
        <Tag
          color={
            row.lifecycle === 'CURRENT'
              ? 'success'
              : row.lifecycle === 'SCHEDULED'
                ? 'processing'
                : 'default'
          }
          style={{ marginInlineEnd: 0 }}
        >
          {text.lifecycle[row.lifecycle] ?? row.lifecycle}
        </Tag>
      </Flex>
    ),
  };
  const effectiveColumn: TableColumnsType<ExposureAllowance>[number] = {
    key: 'effective',
    title: text.columnEffective,
    render: (_, row) => (
      <Flex vertical gap={0}>
        <span>
          <DateTime value={row.effectiveFrom} /> {text.from}
        </span>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {row.effectiveTo === null ? (
            text.openEnded
          ) : (
            <>
              {text.until} <DateTime value={row.effectiveTo} />
            </>
          )}
        </Typography.Text>
      </Flex>
    ),
  };
  const publisherColumn: TableColumnsType<ExposureAllowance>[number] = {
    key: 'publisher',
    title: text.columnPublisher,
    render: (_, row) => (
      <Flex vertical gap={0}>
        <Typography.Text>{row.publishedByName ?? text.unknownPerson}</Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          <DateTime value={row.publishedAt} />
        </Typography.Text>
      </Flex>
    ),
  };

  const liveColumns: TableColumnsType<ExposureAllowance> = [
    scopeColumn,
    ...valueColumns,
    {
      key: 'occupied',
      title: text.columnOccupied,
      align: 'right',
      render: (_, row) => (
        <Flex vertical gap={0} align="flex-end">
          <span>{amount(row.occupiedValue, row.unitCode)}</span>
          {row.liveOccupations !== null && row.liveOccupations > 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.liveOccupations(row.liveOccupations)}
            </Typography.Text>
          )}
        </Flex>
      ),
    },
    {
      key: 'headroom',
      title: text.columnHeadroom,
      align: 'right',
      render: (_, row) => {
        const headroom = scaled(row.headroom);
        return (
          <Flex vertical gap={2} align="flex-end">
            <Typography.Text
              strong
              {...(headroom !== undefined && headroom <= 0n ? { type: 'danger' as const } : {})}
            >
              {amount(row.headroom, row.unitCode)}
            </Typography.Text>
            {headroom !== undefined && headroom <= 0n && (
              <Tag color="error" style={{ marginInlineEnd: 0 }}>
                {text.exhausted}
              </Tag>
            )}
            {row.occupancyUnresolved === true && (
              <Tag color="warning" style={{ marginInlineEnd: 0 }}>
                {text.occupancyUnresolved}
                <InfoTip title={text.occupancyUnresolvedHelp} />
              </Tag>
            )}
          </Flex>
        );
      },
    },
    versionColumn,
    effectiveColumn,
    publisherColumn,
    evidenceColumn,
    {
      key: 'actions',
      title: text.columnActions,
      render: (_, row) => (
        <ActionModal<ReasonValues>
          trigger={{
            label: text.retire,
            danger: true,
            size: 'small',
            disabled: !row.canManage,
            disabledReason: text.retireDisabled,
          }}
          title={text.retireTitle}
          consequence={text.retireConsequence}
          summary={
            <Typography.Text type="secondary">
              {codeText('allowanceAxis', row.axisCode)} · {scopeName(row)} ·{' '}
              {text.versionLabel(row.version)} · {text.columnLimit}{' '}
              {amount(row.limitValue, row.unitCode)}
            </Typography.Text>
          }
          okText={text.retire}
          danger
          onSubmit={async (values): Promise<SubmitOutcome> => {
            const outcome = await retireAllowance(context, row.id, (values.reason ?? '').trim());
            if (!outcome.ok) return outcome.failure;
            onChanged();
            return undefined;
          }}
        >
          <Form.Item
            name="reason"
            label={text.retireReason}
            rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
          >
            <Input.TextArea
              rows={3}
              maxLength={500}
              showCount
              placeholder={dialog.reasonPlaceholder}
            />
          </Form.Item>
        </ActionModal>
      ),
    },
  ];

  const historyColumns: TableColumnsType<ExposureAllowance> = [
    scopeColumn,
    ...valueColumns,
    versionColumn,
    effectiveColumn,
    publisherColumn,
    {
      key: 'retired',
      title: text.columnRetired,
      render: (_, row) =>
        row.lifecycle === 'RETIRED' ? (
          <Flex vertical gap={0} style={{ maxWidth: 240 }}>
            <Typography.Text>
              {row.retiredByName ?? text.unknownPerson} · <DateTime value={row.retiredAt} />
            </Typography.Text>
            {row.retireReason !== null && (
              <Typography.Text
                type="secondary"
                style={{ fontSize: 12 }}
                ellipsis={{ tooltip: row.retireReason }}
              >
                {text.reasonLabel}：{row.retireReason}
              </Typography.Text>
            )}
          </Flex>
        ) : row.supersededByAllowanceId !== null ? (
          <Typography.Text type="secondary">{text.supersededBy}</Typography.Text>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    evidenceColumn,
  ];

  return (
    <SectionCard
      title={codeText('allowanceAxis', axis)}
      state={live.length === 0 ? 'empty' : 'loaded'}
    >
      <Stack>
        {live.length === 0 ? (
          <EmptyState description={text.noCurrent} />
        ) : (
          <Table<ExposureAllowance>
            rowKey="id"
            size="small"
            pagination={false}
            scroll={{ x: 'max-content' }}
            columns={liveColumns}
            dataSource={[...live]}
          />
        )}
        <SectionCollapse
          size="small"
          items={[
            {
              key: 'history',
              title: text.history,
              summary: text.historyCount(history.length),
              children:
                history.length === 0 ? (
                  <Typography.Text type="secondary">{text.noHistory}</Typography.Text>
                ) : (
                  <Table<ExposureAllowance>
                    rowKey="id"
                    size="small"
                    pagination={false}
                    scroll={{ x: 'max-content' }}
                    columns={historyColumns}
                    dataSource={[...history]}
                  />
                ),
            },
          ]}
        />
      </Stack>
    </SectionCard>
  );
}

// ------------------------------------------------------------------ publish

function PublishAllowance({
  context,
  overview,
  storeId,
  onPublished,
}: {
  readonly context: ConsoleRequest;
  readonly overview: AllowanceOverview;
  readonly storeId: string;
  readonly onPublished: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const publishableStores = overview.stores.filter((store) => store.canPublish);
  const allowed = overview.canPublishOrganization || publishableStores.length > 0;
  const defaultStore =
    publishableStores.find((store) => store.storeId === storeId) ?? publishableStores[0];
  const initialValues: Partial<PublishValues> =
    defaultStore === undefined
      ? { scopeKind: 'ORGANIZATION' }
      : { scopeKind: 'STORE', storeId: defaultStore.storeId };

  return (
    <ActionModal<PublishValues>
      trigger={{
        label: text.publish,
        type: 'primary',
        disabled: !allowed,
        disabledReason: text.publishDisabled,
      }}
      title={text.publishTitle}
      consequence={text.publishConsequence}
      okText={text.publish}
      width={680}
      initialValues={initialValues}
      onSubmit={async (values): Promise<SubmitOutcome> => {
        const scopeKind = values.scopeKind ?? 'STORE';
        const outcome = await publishAllowance(context, {
          scopeKind,
          ...(scopeKind === 'STORE' && values.storeId !== undefined
            ? { storeId: values.storeId }
            : {}),
          ...(scopeKind === 'PLATFORM' && values.platformCode !== undefined
            ? { platformCode: values.platformCode }
            : {}),
          axisCode: values.axisCode ?? '',
          limitValue: (values.limitValue ?? '').trim(),
          reserveValue: (values.reserveValue ?? '').trim(),
          ...(values.effectiveFrom === undefined || values.effectiveFrom === ''
            ? {}
            : { effectiveFrom: values.effectiveFrom }),
          evidenceReference: (values.evidenceReference ?? '').trim(),
          reason: (values.reason ?? '').trim(),
        });
        if (!outcome.ok) return outcome.failure;
        const publication = outcome.value;
        if (publication.reserveWarnings.length > 0) {
          void message.warning(text.publishedWithWarning(publication.allowanceVersion));
        } else {
          void message.success(text.published(publication.allowanceVersion));
        }
        onPublished();
        return undefined;
      }}
    >
      {(form) => <PublishFields form={form} overview={overview} />}
    </ActionModal>
  );
}

function PublishFields({
  form,
  overview,
}: {
  readonly form: FormInstance<PublishValues>;
  readonly overview: AllowanceOverview;
}): React.JSX.Element {
  const scopeKind = Form.useWatch('scopeKind', form);
  const storeId = Form.useWatch('storeId', form);
  const platformCode = Form.useWatch('platformCode', form);
  const axisCode = Form.useWatch('axisCode', form);
  const limitValue = Form.useWatch('limitValue', form);
  const reserveValue = Form.useWatch('reserveValue', form);

  const publishableStores = overview.stores.filter((store) => store.canPublish);
  const unit = unitFor(overview, axisCode, scopeKind, storeId, platformCode);
  const countAxis = axisCode !== undefined && COUNT_AXES.includes(axisCode);

  /** Why a limit or reserve entry is refused, or undefined when it is acceptable. */
  const numberProblem = (trimmed: string, positive: boolean): string | undefined => {
    if (trimmed === '') return undefined;
    if (!DECIMAL.test(trimmed)) return text.numberInvalid;
    const current = form.getFieldValue('axisCode') as string | undefined;
    if (
      current !== undefined &&
      COUNT_AXES.includes(current) &&
      trimmed.includes('.') &&
      !/\.0+$/.test(trimmed)
    ) {
      return text.wholeNumber;
    }
    return positive && (scaled(trimmed) ?? 0n) <= 0n ? text.limitPositive : undefined;
  };

  const decimalRules = (label: string, positive: boolean) => [
    { required: true, message: `请填写${label}` },
    {
      validator: (_: unknown, value: string | undefined): Promise<void> =>
        settle(numberProblem((value ?? '').trim(), positive)),
    },
  ];

  return (
    <>
      <Form.Item
        name="scopeKind"
        label={text.scopeKind}
        rules={[{ required: true, message: dialog.required }]}
      >
        <Radio.Group
          options={[
            { value: 'STORE', label: text.store, disabled: publishableStores.length === 0 },
            { value: 'PLATFORM', label: text.platform, disabled: !overview.canPublishOrganization },
            {
              value: 'ORGANIZATION',
              label: text.organizationScope,
              disabled: !overview.canPublishOrganization,
            },
          ]}
        />
      </Form.Item>
      {scopeKind === 'STORE' && (
        <Form.Item
          name="storeId"
          label={text.store}
          rules={[{ required: true, message: text.pickStore }]}
        >
          <Select
            placeholder={text.pickStore}
            options={overview.stores.map((store) => ({
              value: store.storeId,
              label: [store.displayName, store.platformCode, store.currencyCode]
                .filter((part) => part !== null && part !== '')
                .join(' · '),
              disabled: !store.canPublish,
            }))}
          />
        </Form.Item>
      )}
      {scopeKind === 'PLATFORM' && (
        <Form.Item
          name="platformCode"
          label={text.platform}
          rules={[{ required: true, message: text.pickPlatform }]}
        >
          <Select
            placeholder={text.pickPlatform}
            options={overview.platforms.map((platform) => ({ value: platform, label: platform }))}
          />
        </Form.Item>
      )}
      <Form.Item
        name="axisCode"
        label={text.axis}
        rules={[{ required: true, message: text.pickAxis }]}
      >
        <Select
          placeholder={text.pickAxis}
          options={AXES.map((axis) => ({ value: axis, label: codeText('allowanceAxis', axis) }))}
          onChange={() => {
            // The number rules depend on the axis (whole numbers for counts).
            void form.validateFields(['limitValue', 'reserveValue']).catch(() => undefined);
          }}
        />
      </Form.Item>
      <Form.Item label={text.unit}>
        <Typography.Text
          {...(axisCode !== undefined && unit === undefined ? { type: 'danger' as const } : {})}
        >
          {unitLabel(unit, axisCode)}
        </Typography.Text>
      </Form.Item>
      <Flex gap={16} wrap>
        <Form.Item
          name="limitValue"
          label={text.limit}
          style={{ flex: '1 1 200px' }}
          rules={decimalRules(text.limit, true)}
        >
          <Input inputMode="decimal" {...(countAxis ? { placeholder: '5' } : {})} />
        </Form.Item>
        <Form.Item
          name="reserveValue"
          label={text.reserve}
          extra={text.reserveHelp}
          style={{ flex: '1 1 200px' }}
          dependencies={['limitValue']}
          rules={[
            ...decimalRules(text.reserve, false),
            {
              validator: (_: unknown, value: string | undefined): Promise<void> => {
                const reserve = scaled(value);
                const limit = scaled(form.getFieldValue('limitValue') as string | undefined);
                return settle(
                  reserve !== undefined && limit !== undefined && reserve >= limit
                    ? text.reserveBelowLimit
                    : undefined,
                );
              },
            },
          ]}
        >
          <Input inputMode="decimal" {...(countAxis ? { placeholder: '1' } : {})} />
        </Form.Item>
      </Flex>
      <Form.Item name="effectiveFrom" label={text.effectiveFrom} extra={text.effectiveFromHelp}>
        <InstantField ariaLabel={text.effectiveFrom} />
      </Form.Item>
      <Form.Item
        name="evidenceReference"
        label={text.evidence}
        rules={[{ required: true, whitespace: true, message: `请填写${text.evidence}` }]}
      >
        <Input maxLength={500} placeholder={text.evidencePlaceholder} />
      </Form.Item>
      <Form.Item
        name="reason"
        label={text.reason}
        rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
      >
        <Input.TextArea rows={3} maxLength={500} showCount placeholder={text.reasonPlaceholder} />
      </Form.Item>
      <PublishPreview
        overview={overview}
        scopeKind={scopeKind}
        storeId={storeId}
        platformCode={platformCode}
        axisCode={axisCode}
        unit={unit}
        limitValue={limitValue}
        reserveValue={reserveValue}
      />
    </>
  );
}

/** What the new version replaces, the headroom it leaves, and any reserve below an accepted policy. */
function PublishPreview({
  overview,
  scopeKind,
  storeId,
  platformCode,
  axisCode,
  unit,
  limitValue,
  reserveValue,
}: {
  readonly overview: AllowanceOverview;
  readonly scopeKind: ScopeKind | undefined;
  readonly storeId: string | undefined;
  readonly platformCode: string | undefined;
  readonly axisCode: string | undefined;
  readonly unit: string | undefined;
  readonly limitValue: string | undefined;
  readonly reserveValue: string | undefined;
}): React.JSX.Element | null {
  if (scopeKind === undefined || axisCode === undefined) return null;
  if (scopeKind === 'STORE' && storeId === undefined) return null;
  if (scopeKind === 'PLATFORM' && platformCode === undefined) return null;

  const sameRows = overview.allowances.filter(
    (row) => row.axisCode === axisCode && sameScope(row, scopeKind, storeId, platformCode),
  );
  const current = sameRows.find((row) => row.lifecycle === 'CURRENT');
  const scheduled = sameRows.some((row) => row.lifecycle === 'SCHEDULED');

  const limit = scaled(limitValue);
  const reserve = scaled(reserveValue);
  const occupied = scaled(current?.occupiedValue ?? '0') ?? 0n;
  const headroom =
    limit !== undefined && reserve !== undefined ? limit - reserve - occupied : undefined;

  const storePlatform =
    scopeKind === 'STORE'
      ? (overview.stores.find((store) => store.storeId === storeId)?.platformCode ?? null)
      : null;
  const below =
    reserve === undefined
      ? []
      : overview.reservePolicies.filter((policy) => {
          const accepted = scaled(policy.reserveValue);
          return (
            policy.axisCode === axisCode &&
            accepted !== undefined &&
            reserve < accepted &&
            governs(policy, scopeKind, storeId, platformCode, storePlatform)
          );
        });

  return (
    <Flex vertical gap={8}>
      <Alert
        type={headroom !== undefined && headroom <= 0n ? 'warning' : 'info'}
        showIcon
        title={text.previewTitle}
        description={
          <Flex vertical gap={2}>
            <span>
              {current === undefined ? text.previewNone : text.previewCurrent(current.version)}
            </span>
            {scheduled && <span>{text.previewScheduled}</span>}
            {headroom !== undefined && unit !== undefined && (
              <span>{text.previewHeadroom(amount(unscaled(headroom), unit))}</span>
            )}
            {headroom !== undefined && headroom <= 0n && <span>{text.previewNegative}</span>}
          </Flex>
        }
      />
      {below.length > 0 && (
        <Alert
          type="warning"
          showIcon
          title={text.reserveWarningTitle}
          description={text.reserveWarning(
            below
              .map((policy) =>
                text.reserveWarningItem(
                  policy.packageCode,
                  text.purposeLabels[policy.purposeCode] ?? policy.purposeCode,
                  amount(policy.reserveValue, unit ?? 'COUNT'),
                ),
              )
              .join('、'),
          )}
        />
      )}
    </Flex>
  );
}
