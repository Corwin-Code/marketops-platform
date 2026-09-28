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
  Space,
  Table,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import type { Dayjs } from 'dayjs';
import { useEffect, useState } from 'react';
import type { InboundListItem, InboundPage } from '../api/availability';
import {
  amendInboundAttestation,
  cancelInboundAttestation,
  createInboundAttestation,
  fetchInboundAttestations,
  reverifyInboundAttestation,
} from '../api/availability';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { STORE_TIMEZONE_LABEL, storeLocalToIso, toStoreDayjs } from '../format';
import { actions, codeLabel } from '../i18n';
import { INBOUND_CHANGE_KIND_LABELS, INBOUND_STATUS_LABELS } from '../i18n/zh/availability';
import { inboundText as text } from '../i18n/zh/availabilityAuthority';
import {
  ActionModal,
  CodeTag,
  DateTime,
  EmptyState,
  FailureAlert,
  FormDrawer,
  LoadingState,
  VariantName,
  idRules,
  usePageParam,
  useSearchParam,
  useSearchParamsPatch,
} from '../ui';
import type { SubmitOutcome } from '../ui';
import { INBOUND_STATUS_COLORS } from './tagColors';
import { VariantPicker } from './VariantPicker';

export interface InboundAttestationsProps {
  readonly context: ConsoleRequest;
}

/** Statuses a claim may be registered or amended with; cancelling is its own action. */
const EDITABLE_STATUSES = [
  'DRAFT',
  'REQUESTED',
  'SUPPLIER_CONFIRMED',
  'IN_TRANSIT',
  'RECEIVED',
  'OVERDUE',
  'CONFLICTED',
  'UNKNOWN',
] as const;
const ALL_STATUSES = [...EDITABLE_STATUSES, 'CANCELLED'] as const;

const PAGE_SIZE = 20;
const PICKER_FORMAT = 'YYYY-MM-DD HH:mm';
const REFERENCE_LIMIT = 128;
const EVIDENCE_LIMIT = 512;
const REASON_LIMIT = 1000;
const QUANTITY_MAX = 100_000_000;

/** Address-bar keys of this tab. */
const VARIANT_PARAM = 'ivariant';
const STATUS_PARAM = 'istatus';
const PAGE_PARAM = 'ipage';

type Claims =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly page: InboundPage }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/** A change being made to one listed claim. */
interface Editing {
  readonly kind: 'amend' | 'reverify' | 'cancel';
  readonly item: InboundListItem;
}

/**
 * The current version of every inbound claim, and the governed ways to change
 * one.
 *
 * Every change is its own form with its own required reason and evidence —
 * amend, re-verify and cancel never read another form's fields — and appends a
 * version instead of editing one. A product is always picked, never typed as
 * an identifier unless the product list cannot be read.
 */
export function InboundAttestations({ context }: InboundAttestationsProps): React.JSX.Element {
  const { message } = App.useApp();
  const [variant] = useSearchParam(VARIANT_PARAM);
  const [rawStatus] = useSearchParam(STATUS_PARAM);
  const [page, setPage] = usePageParam(PAGE_PARAM);
  const patch = useSearchParamsPatch();
  const status = (ALL_STATUSES as readonly string[]).includes(rawStatus ?? '')
    ? rawStatus
    : undefined;
  const [claims, setClaims] = useState<Claims>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);
  const [fetching, setFetching] = useState(true);
  const [editing, setEditing] = useState<Editing | undefined>(undefined);

  useEffect(() => {
    let live = true;
    setFetching(true);
    void fetchInboundAttestations(context, {
      productVariantId: variant,
      status,
      limit: PAGE_SIZE,
      offset: (page - 1) * PAGE_SIZE,
    }).then((outcome) => {
      if (!live) return;
      setFetching(false);
      setClaims(
        outcome.ok
          ? { kind: 'loaded', page: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, variant, status, page, generation]);

  const loaded = claims.kind === 'loaded' ? claims.page : undefined;
  const reload = (): void => {
    setGeneration((value) => value + 1);
  };
  const changed = (success: string): void => {
    void message.success(success);
    setEditing(undefined);
    reload();
  };

  const known = loaded?.items.find((item) => item.productVariantId === variant);
  const total =
    loaded === undefined
      ? 0
      : (loaded.total ??
        (page - 1) * PAGE_SIZE + loaded.items.length + (loaded.items.length >= PAGE_SIZE ? 1 : 0));

  const columns: TableColumnsType<InboundListItem> = [
    {
      key: 'product',
      title: text.columnProduct,
      width: 220,
      render: (_, item) => (
        <VariantName
          identity={{ displayName: item.displayName, skuCode: item.skuCode }}
          productVariantId={item.productVariantId}
        />
      ),
    },
    {
      key: 'reference',
      title: text.columnReference,
      render: (_, item) => <Typography.Text copyable>{item.externalReference}</Typography.Text>,
    },
    {
      key: 'status',
      title: text.columnStatus,
      render: (_, item) => (
        <CodeTag
          labels={INBOUND_STATUS_LABELS}
          code={item.businessStatus}
          colors={INBOUND_STATUS_COLORS}
        />
      ),
    },
    {
      key: 'quantity',
      title: text.columnQuantity,
      align: 'right',
      render: (_, item) => text.quantity(item.quantity),
    },
    {
      key: 'arrival',
      title: text.columnArrival,
      render: (_, item) => (
        <Flex vertical>
          <DateTime value={item.expectedArrivalFrom} />
          <DateTime value={item.expectedArrivalTo} />
        </Flex>
      ),
    },
    {
      key: 'verified',
      title: text.columnVerified,
      render: (_, item) => <DateTime value={item.lastVerifiedAt} relative />,
    },
    {
      key: 'version',
      title: text.columnVersion,
      render: (_, item) =>
        text.version(item.versionNo, codeLabel(INBOUND_CHANGE_KIND_LABELS, item.changeKind)),
    },
    {
      key: 'attestedBy',
      title: text.columnAttestedBy,
      render: (_, item) => item.attestedByName ?? '—',
    },
    {
      key: 'actions',
      title: text.columnActions,
      render: (_, item) =>
        !item.canAttest ? null : item.businessStatus === 'CANCELLED' ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.alreadyCancelled}
          </Typography.Text>
        ) : (
          <Space size={4} wrap>
            <Button
              size="small"
              onClick={() => {
                setEditing({ kind: 'amend', item });
              }}
            >
              {text.amend}
            </Button>
            <Button
              size="small"
              onClick={() => {
                setEditing({ kind: 'reverify', item });
              }}
            >
              {text.reverify}
            </Button>
            <Button
              size="small"
              danger
              onClick={() => {
                setEditing({ kind: 'cancel', item });
              }}
            >
              {text.cancel}
            </Button>
          </Space>
        ),
    },
  ];

  return (
    <div data-testid="inbound-authority" data-state={claims.kind}>
      <Flex gap={8} wrap align="center" justify="space-between" style={{ marginBottom: 12 }}>
        <Flex gap={8} wrap align="center">
          <div style={{ width: 280 }}>
            <VariantPicker
              context={context}
              purpose="VIEW"
              value={variant}
              known={known}
              placeholder={text.productFilterPlaceholder}
              onChange={(next) => {
                patch({ [VARIANT_PARAM]: next?.trim(), [PAGE_PARAM]: undefined });
              }}
            />
          </div>
          <Select<string>
            aria-label={text.statusFilter}
            allowClear
            style={{ width: 160 }}
            placeholder={text.allStatuses}
            value={status ?? null}
            options={ALL_STATUSES.map((code) => ({
              value: code,
              label: codeLabel(INBOUND_STATUS_LABELS, code),
            }))}
            onChange={(next: string | undefined) => {
              patch({ [STATUS_PARAM]: next, [PAGE_PARAM]: undefined });
            }}
          />
          <Button icon={<ReloadOutlined />} aria-label={text.refreshLabel} onClick={reload}>
            {actions.refresh}
          </Button>
        </Flex>
        <CreateAttestation
          context={context}
          allowed={loaded?.canAttestAny ?? false}
          onDone={changed}
        />
      </Flex>
      {claims.kind === 'failed' && <FailureAlert failure={claims.failure} />}
      {claims.kind === 'loading' && <LoadingState />}
      {loaded?.items.length === 0 && (
        <EmptyState
          description={variant === undefined && status === undefined ? text.empty : text.noMatch}
        />
      )}
      {loaded !== undefined && loaded.items.length > 0 && (
        <Table<InboundListItem>
          size="middle"
          rowKey="id"
          columns={columns}
          dataSource={[...loaded.items]}
          scroll={{ x: 'max-content' }}
          loading={fetching}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            total,
            showSizeChanger: false,
            hideOnSinglePage: true,
            showTotal: () =>
              loaded.total === undefined ? text.totalUnknown : text.total(loaded.total),
            onChange: (next) => {
              setPage(next);
            },
          }}
          onRow={(item) =>
            ({
              'data-attestation': item.id,
              'data-status': item.businessStatus,
            }) as React.HTMLAttributes<HTMLElement>
          }
        />
      )}
      <AmendAttestation
        context={context}
        item={editing?.kind === 'amend' ? editing.item : undefined}
        onClose={() => {
          setEditing(undefined);
        }}
        onDone={changed}
      />
      <ReverifyAttestation
        context={context}
        item={editing?.kind === 'reverify' ? editing.item : undefined}
        onClose={() => {
          setEditing(undefined);
        }}
        onDone={changed}
      />
      <CancelAttestation
        context={context}
        item={editing?.kind === 'cancel' ? editing.item : undefined}
        onClose={() => {
          setEditing(undefined);
        }}
        onDone={changed}
      />
    </div>
  );
}

interface ClaimValues {
  readonly productVariantId?: string;
  readonly externalReference?: string;
  readonly quantity?: number | null;
  readonly arrivalFrom?: Dayjs | null;
  readonly arrivalTo?: Dayjs | null;
  readonly businessStatus?: string;
  readonly evidenceReference?: string;
  readonly reason?: string;
}

/** Quantity, arrival window and status: the fields registering and amending share. */
function ClaimFields(): React.JSX.Element {
  return (
    <>
      <Row gutter={12}>
        <Col xs={24} md={8}>
          <Form.Item
            name="quantity"
            label={text.quantityLabel}
            rules={[{ required: true, message: text.quantityRequired }]}
          >
            <InputNumber min={1} max={QUANTITY_MAX} precision={0} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col xs={24} md={16}>
          <Form.Item
            name="businessStatus"
            label={text.status}
            rules={[{ required: true, message: text.statusRequired }]}
          >
            <Select
              options={EDITABLE_STATUSES.map((code) => ({
                value: code,
                label: codeLabel(INBOUND_STATUS_LABELS, code),
              }))}
            />
          </Form.Item>
        </Col>
      </Row>
      <Row gutter={12}>
        <Col xs={24} md={12}>
          <Form.Item
            name="arrivalFrom"
            label={`${text.arrivalFrom}（${STORE_TIMEZONE_LABEL}）`}
            rules={[{ required: true, message: text.arrivalFromRequired }]}
          >
            <DatePicker
              showTime={{ format: 'HH:mm' }}
              format={PICKER_FORMAT}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item
            name="arrivalTo"
            label={`${text.arrivalTo}（${STORE_TIMEZONE_LABEL}）`}
            dependencies={['arrivalFrom']}
            rules={[
              { required: true, message: text.arrivalToRequired },
              ({ getFieldValue }) => ({
                validator: (_: unknown, value: Dayjs | null | undefined) => {
                  const from = getFieldValue('arrivalFrom') as Dayjs | null | undefined;
                  return !value || !from || !value.isBefore(from)
                    ? Promise.resolve()
                    : Promise.reject(new Error(text.arrivalOrder));
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
    </>
  );
}

function EvidenceField({ extra }: { readonly extra?: string }): React.JSX.Element {
  return (
    <Form.Item
      name="evidenceReference"
      label={text.evidence}
      {...(extra === undefined ? {} : { extra })}
      rules={[{ required: true, whitespace: true, message: text.evidenceRequired }]}
    >
      <Input maxLength={EVIDENCE_LIMIT} placeholder={text.evidencePlaceholder} />
    </Form.Item>
  );
}

function ReasonField({ required }: { readonly required: boolean }): React.JSX.Element {
  return (
    <Form.Item
      name="reason"
      label={required ? text.reason : text.reasonOptional}
      rules={required ? [{ required: true, whitespace: true, message: text.reasonRequired }] : []}
    >
      <Input.TextArea rows={2} maxLength={REASON_LIMIT} showCount />
    </Form.Item>
  );
}

/** Register the first version of a claim, on a product picked by name. */
function CreateAttestation({
  context,
  allowed,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly allowed: boolean;
  readonly onDone: (success: string) => void;
}): React.JSX.Element {
  const submit = async (values: ClaimValues): Promise<SubmitOutcome> => {
    if (!values.arrivalFrom || !values.arrivalTo) return undefined;
    const reason = (values.reason ?? '').trim();
    const outcome = await createInboundAttestation(context, {
      productVariantId: (values.productVariantId ?? '').trim(),
      externalReference: (values.externalReference ?? '').trim(),
      quantity: values.quantity ?? 0,
      expectedArrivalFrom: storeLocalToIso(values.arrivalFrom),
      expectedArrivalTo: storeLocalToIso(values.arrivalTo),
      businessStatus: values.businessStatus ?? 'REQUESTED',
      evidenceReference: (values.evidenceReference ?? '').trim(),
      sourceTime: new Date().toISOString(),
      reason: reason === '' ? null : reason,
    });
    if (!outcome.ok) return outcome.failure;
    onDone(text.created(outcome.value.versionNo));
    return undefined;
  };
  return (
    <FormDrawer<ClaimValues>
      trigger={{
        label: text.create,
        type: 'primary',
        disabled: !allowed,
        disabledReason: text.noAttestRight,
      }}
      title={text.createTitle}
      intro={<Typography.Paragraph type="secondary">{text.createIntro}</Typography.Paragraph>}
      initialValues={{ quantity: 1, businessStatus: 'REQUESTED' }}
      submitText={text.create}
      onSubmit={submit}
    >
      <Form.Item name="productVariantId" label={text.product} rules={idRules(text.product)}>
        <VariantPicker context={context} purpose="INBOUND_ATTEST" />
      </Form.Item>
      <Form.Item
        name="externalReference"
        label={text.externalReference}
        rules={[{ required: true, whitespace: true, message: text.externalReferenceRequired }]}
      >
        <Input maxLength={REFERENCE_LIMIT} />
      </Form.Item>
      <ClaimFields />
      <EvidenceField />
      <ReasonField required={false} />
    </FormDrawer>
  );
}

/** Append a corrected version of one claim; its external reference cannot change. */
function AmendAttestation({
  context,
  item,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly item: InboundListItem | undefined;
  readonly onClose: () => void;
  readonly onDone: (success: string) => void;
}): React.JSX.Element {
  const from = toStoreDayjs(item?.expectedArrivalFrom);
  const to = toStoreDayjs(item?.expectedArrivalTo);
  return (
    <FormDrawer<ClaimValues>
      open={item !== undefined}
      onClose={onClose}
      title={text.amendTitle}
      intro={
        item === undefined ? undefined : (
          <Typography.Paragraph type="secondary">
            {text.amendIntro(item.externalReference, item.versionNo)}
          </Typography.Paragraph>
        )
      }
      initialValues={{
        ...(item === undefined
          ? {}
          : { quantity: item.quantity, businessStatus: item.businessStatus }),
        ...(from === undefined ? {} : { arrivalFrom: from }),
        ...(to === undefined ? {} : { arrivalTo: to }),
      }}
      submitText={text.amend}
      onSubmit={async (values) => {
        if (item === undefined || !values.arrivalFrom || !values.arrivalTo) return undefined;
        const outcome = await amendInboundAttestation(context, item.id, {
          expectedVersion: item.versionNo,
          quantity: values.quantity ?? item.quantity,
          expectedArrivalFrom: storeLocalToIso(values.arrivalFrom),
          expectedArrivalTo: storeLocalToIso(values.arrivalTo),
          businessStatus: values.businessStatus ?? item.businessStatus,
          evidenceReference: (values.evidenceReference ?? '').trim(),
          sourceTime: new Date().toISOString(),
          reason: (values.reason ?? '').trim(),
        });
        if (!outcome.ok) return outcome.failure;
        onDone(text.amended(outcome.value.versionNo));
        return undefined;
      }}
    >
      <ClaimFields />
      <EvidenceField
        {...(item === undefined ? {} : { extra: text.amendEvidenceHelp(item.evidenceReference) })}
      />
      <ReasonField required />
    </FormDrawer>
  );
}

/** Record a fresh verification of one claim, unchanged otherwise. */
function ReverifyAttestation({
  context,
  item,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly item: InboundListItem | undefined;
  readonly onClose: () => void;
  readonly onDone: (success: string) => void;
}): React.JSX.Element {
  return (
    <ActionModal<{ readonly evidenceReference?: string; readonly reason?: string }>
      open={item !== undefined}
      onClose={onClose}
      title={text.reverifyTitle}
      consequence={text.reverifyConsequence}
      okText={text.reverify}
      onSubmit={async (values) => {
        if (item === undefined) return undefined;
        const outcome = await reverifyInboundAttestation(context, item.id, {
          expectedVersion: item.versionNo,
          evidenceReference: (values.evidenceReference ?? '').trim(),
          reason: (values.reason ?? '').trim(),
        });
        if (!outcome.ok) return outcome.failure;
        onDone(text.reverified(outcome.value.versionNo));
        return undefined;
      }}
    >
      <EvidenceField />
      <ReasonField required />
    </ActionModal>
  );
}

/** Cancel one claim: it stops counting as supply, with the history kept. */
function CancelAttestation({
  context,
  item,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly item: InboundListItem | undefined;
  readonly onClose: () => void;
  readonly onDone: (success: string) => void;
}): React.JSX.Element {
  return (
    <ActionModal<{ readonly evidenceReference?: string; readonly reason?: string }>
      open={item !== undefined}
      onClose={onClose}
      title={text.cancelTitle}
      consequence={text.cancelConsequence}
      okText={text.cancel}
      danger
      onSubmit={async (values) => {
        if (item === undefined) return undefined;
        const outcome = await cancelInboundAttestation(context, item.id, {
          expectedVersion: item.versionNo,
          evidenceReference: (values.evidenceReference ?? '').trim(),
          reason: (values.reason ?? '').trim(),
        });
        if (!outcome.ok) return outcome.failure;
        onDone(text.cancelled(outcome.value.versionNo));
        return undefined;
      }}
    >
      <EvidenceField />
      <ReasonField required />
    </ActionModal>
  );
}
