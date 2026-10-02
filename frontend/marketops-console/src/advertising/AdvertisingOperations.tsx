import { Alert, Card, Descriptions, Flex, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import { AdvertisingOrchestration } from './AdvertisingOrchestration';
import { AdvertisingRecoveryControls } from './AdvertisingContainmentControls';
import {
  fetchAdvertisingContainments,
  fetchAdvertisingExposure,
  fetchAdvertisingReservations,
} from '../api/console';
import type {
  AdvertisingContainment,
  AdvertisingExposure,
  AdvertisingExposureAxisCode,
  AdvertisingExposureEnvelope,
  AdvertisingReservation,
} from '../api/advertising';
import { ADVERTISING_EXPOSURE_AXES } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { formatDecimal } from '../format';
import { codeLabel } from '../i18n';
import {
  AXIS_STATE_COLORS,
  AXIS_STATE_LABELS,
  CAUSE_CLASS_LABELS,
  CONTAINMENT_CONDITION_LABELS,
  CONTAINMENT_KIND_LABELS,
  CONTAINMENT_SCOPE_LABELS,
  CONTAINMENT_STATE_COLORS,
  CONTAINMENT_STATE_LABELS,
  DIRECTION_LABELS,
  EXPOSURE_AXIS_LABELS,
  EXPOSURE_SCOPE_LABELS,
  EXPOSURE_STATUS_COLORS,
  EXPOSURE_STATUS_LABELS,
  LANE_COLORS,
  LANE_LABELS,
  PLATFORM_LABELS,
  RELEASE_CONDITION_LABELS,
  RESERVATION_KIND_LABELS,
  RESERVATION_STATE_COLORS,
  RESERVATION_STATE_LABELS,
} from '../i18n/zh/advertising';
import { CodeTag } from '../ui/CodeTag';
import { DateTime } from '../ui/DateTime';
import { EmptyState } from '../ui/EmptyState';
import { FailureAlert } from '../ui/FailureAlert';
import { InfoTip } from '../ui/InfoTip';
import { LoadingState } from '../ui/LoadingState';
import { Money } from '../ui/Money';
import { SectionCard } from '../ui/SectionCard';
import { SectionCollapse } from '../ui/SectionCollapse';
import type { SectionFlag } from '../ui/SectionCollapse';
import { AbsentValue, IdText, ReasonTags, dataAttributes } from './shared';

/** What the operations surface needs in order to load itself. */
export interface AdvertisingOperationsProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** Changes when the page's refresh asks every panel to read again. */
  readonly revision?: number;
}

/**
 * What advertising is currently doing, and what is stopping it.
 *
 * Three questions on one page because an operator asks them together: is
 * anything held, is the envelope spent, and what is standing in the way of
 * releasing it.
 *
 * The envelope is shown axis by axis. There is no combined figure and no
 * percentage, because the product does not have one — the write gate checks
 * every axis independently and never adds one axis's slack to another's, so a
 * single number would describe a quantity that does not exist.
 *
 * Nothing on this page authorises anything. Every fact here is re-derived
 * inside the database at the moment a write is attempted, which is why a
 * reading that has gone stale can mislead a person but cannot let a write
 * through.
 */
export function AdvertisingOperations({
  context,
  revision = 0,
}: AdvertisingOperationsProps): React.JSX.Element {
  // Reads again after an action on this page, besides the page's own refresh.
  const [ownRevision, setOwnRevision] = useState(0);
  const [reservations, setReservations] = useState<readonly AdvertisingReservation[] | undefined>(
    undefined,
  );
  const [exposure, setExposure] = useState<AdvertisingExposure | undefined>(undefined);
  const [containments, setContainments] = useState<readonly AdvertisingContainment[] | undefined>(
    undefined,
  );
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void Promise.all([
      fetchAdvertisingReservations(context),
      fetchAdvertisingExposure(context),
      fetchAdvertisingContainments(context),
    ]).then(([held, envelope, holds]) => {
      if (!active) {
        return;
      }
      // One failure fails the page. A partial view of what is stopping work is
      // worse than none: an operator who saw an empty containment list because
      // that one call failed would conclude nothing was held.
      if (!held.ok) {
        setFailure(held.failure);
        return;
      }
      if (!envelope.ok) {
        setFailure(envelope.failure);
        return;
      }
      if (!holds.ok) {
        setFailure(holds.failure);
        return;
      }
      setReservations(held.value);
      setExposure(envelope.value);
      setContainments(holds.value);
      setFailure(undefined);
    });
    return () => {
      active = false;
    };
  }, [context, revision, ownRevision]);

  const reload = (): void => {
    setOwnRevision((value) => value + 1);
  };

  if (failure !== undefined) {
    return (
      <section aria-label="广告执行" data-state="error">
        <SectionCard title="广告执行">
          <FailureAlert failure={failure} />
        </SectionCard>
      </section>
    );
  }
  if (exposure === undefined || reservations === undefined || containments === undefined) {
    return (
      <section aria-label="广告执行" data-state="loading">
        <SectionCard title="广告执行">
          <LoadingState rows={4} />
        </SectionCard>
      </section>
    );
  }

  return (
    <section aria-label="广告执行" data-state="loaded">
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <AdvertisingOrchestration context={context} revision={revision + ownRevision} />
        <ExposurePanel exposure={exposure} />
        <ContainmentPanel containments={containments} context={context} reload={reload} />
        <ReservationPanel reservations={reservations} />
      </Space>
    </section>
  );
}

/** How an axis unit such as `RUB_MAJOR` reads. */
function unitLabel(unit: string): string {
  const major = /^([A-Z]{3})_MAJOR$/.exec(unit);
  if (major?.[1] !== undefined) return `${major[1]} 主币单位`;
  const minor = /^([A-Z]{3})_MINOR$/.exec(unit);
  if (minor?.[1] !== undefined) return `${minor[1]} 辅币单位`;
  return unit;
}

const MONEY_AXES: ReadonlySet<AdvertisingExposureAxisCode> = new Set([
  'associatedOfficialSpend',
  'cumulativeBidChangeMajor',
]);

interface AxisRow {
  readonly code: AdvertisingExposureAxisCode;
  readonly envelope: AdvertisingExposureEnvelope;
}

/** One axis figure: money in the envelope currency, anything else as an exact decimal. */
function AxisValue({
  code,
  value,
  currency,
  absent,
}: {
  readonly code: AdvertisingExposureAxisCode;
  readonly value: string | undefined;
  readonly currency: string | undefined;
  readonly absent: string;
}): React.JSX.Element {
  if (value === undefined) return <AbsentValue label={absent} />;
  if (MONEY_AXES.has(code)) return <Money value={value} currency={currency ?? null} />;
  return (
    <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>
      {formatDecimal(value)}
    </Typography.Text>
  );
}

/** The notes an axis carries beside its name. */
function AxisNotes({ code, envelope }: AxisRow): React.JSX.Element | null {
  const axis = envelope.axes[code];
  const notes: React.ReactNode[] = [];
  if (code === 'activeInterventions') {
    notes.push(
      <span key="reserved">
        其中预留用于恢复：{formatDecimal(envelope.axes.reservedRecoveryHeadroom.reserved)}
        {' / '}
        {formatDecimal(axis.limit)}
      </span>,
    );
  }
  if (code === 'reservedRecoveryHeadroom') {
    notes.push(<span key="headroom">剩余干预容量 / 预留容量</span>);
  }
  if (code === 'affectedRetainedSalesShare') {
    notes.push(
      <span key="sales">
        受影响销售{' '}
        {axis.affectedSales === undefined ? (
          '未测量'
        ) : (
          <Money value={axis.affectedSales} currency={envelope.currencyCode ?? null} />
        )}
        {' / 公司销售 '}
        {axis.companySales === undefined ? (
          '未测量'
        ) : (
          <Money value={axis.companySales} currency={envelope.currencyCode ?? null} />
        )}
      </span>,
    );
  }
  if (
    code === 'associatedOfficialSpend' &&
    axis.aggregationBasis === 'COMPLETE_INTERSECTING_OFFICIAL_REPORT_AMOUNTS'
  ) {
    notes.push(
      <span key="basis">
        按完整相交的官方报告合计；
        {axis.conservativeBoundaryReportCount === undefined
          ? '数量未知的'
          : `${String(axis.conservativeBoundaryReportCount)} 份`}
        报告跨越窗口起点，按全额计入
      </span>,
    );
  }
  if (axis.unit !== undefined) notes.push(<span key="unit">{unitLabel(axis.unit)}</span>);
  if (axis.windowHours !== undefined) {
    notes.push(<span key="window">统计窗口 {axis.windowHours} 小时</span>);
  }
  if (notes.length === 0) return null;
  return (
    <Flex vertical gap={0}>
      {notes.map((note, index) => (
        <Typography.Text key={index} type="secondary" style={{ fontSize: 12 }}>
          {note}
        </Typography.Text>
      ))}
    </Flex>
  );
}

const AXIS_COLUMNS: TableColumnsType<AxisRow> = [
  {
    title: '维度',
    key: 'axis',
    render: (_, row) => (
      <Flex vertical gap={2}>
        <Typography.Text strong>{codeLabel(EXPOSURE_AXIS_LABELS, row.code)}</Typography.Text>
        <AxisNotes code={row.code} envelope={row.envelope} />
      </Flex>
    ),
  },
  {
    title: '当前测量',
    key: 'measured',
    align: 'right',
    render: (_, row) => {
      const axis = row.envelope.axes[row.code];
      const headroom = row.code === 'reservedRecoveryHeadroom';
      return (
        <AxisValue
          code={row.code}
          value={headroom ? axis.available : axis.usage}
          currency={row.envelope.currencyCode}
          absent="未测量"
        />
      );
    },
  },
  {
    title: '上限 / 预留',
    key: 'limit',
    align: 'right',
    render: (_, row) => {
      const axis = row.envelope.axes[row.code];
      const headroom = row.code === 'reservedRecoveryHeadroom';
      return (
        <AxisValue
          code={row.code}
          value={headroom ? axis.reserved : axis.limit}
          currency={row.envelope.currencyCode}
          absent="未设定"
        />
      );
    },
  },
  {
    title: '状态',
    key: 'state',
    onCell: (row) => dataAttributes({ 'data-state': row.envelope.axes[row.code].state }),
    render: (_, row) => (
      <CodeTag
        labels={AXIS_STATE_LABELS}
        code={row.envelope.axes[row.code].state}
        colors={AXIS_STATE_COLORS}
      />
    ),
  },
];

/** Axes over their limit, and axes whose headroom is not proven, for a folded header. */
function envelopeFlags(envelope: AdvertisingExposureEnvelope): SectionFlag[] {
  const exceeded = ADVERTISING_EXPOSURE_AXES.filter(
    (code) => envelope.axes[code].state === 'EXCEEDED',
  ).length;
  const unknown = ADVERTISING_EXPOSURE_AXES.filter(
    (code) => envelope.axes[code].state === 'UNKNOWN',
  ).length;
  return [
    ...(exceeded > 0
      ? [{ key: 'exceeded', label: `${String(exceeded)} 项已超限`, color: 'error' as const }]
      : []),
    ...(unknown > 0
      ? [{ key: 'unknown', label: `${String(unknown)} 项余量未证实`, color: 'warning' as const }]
      : []),
  ];
}

/** The aggregate envelope, one axis at a time. */
function ExposurePanel({
  exposure,
}: {
  readonly exposure: AdvertisingExposure;
}): React.JSX.Element {
  const title = (
    <Space size={8}>
      <span>暴露额度</span>
      <CodeTag
        labels={EXPOSURE_STATUS_LABELS}
        code={exposure.status}
        colors={EXPOSURE_STATUS_COLORS}
      />
      <InfoTip title="每个维度单独限额，不合并计算，也不显示总百分比。" />
    </Space>
  );
  if (exposure.status === 'MASKED') {
    return (
      <section aria-label="暴露额度" data-state="masked">
        <SectionCard title={title}>
          <Alert
            type="warning"
            showIcon
            title="已遮蔽"
            description="组织层面的暴露超出你当前的可见范围。被遮蔽的数值不等于零，也不代表有剩余额度。"
          />
        </SectionCard>
      </section>
    );
  }
  return (
    <section aria-label="暴露额度" data-state={exposure.resolved ? 'resolved' : 'unresolved'}>
      <SectionCard title={title}>
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {/* In the body rather than the card header, where a narrow screen would
              squeeze the title out. */}
          <Typography.Text type="secondary">
            测量时间：
            <DateTime value={exposure.measuredAt} />
          </Typography.Text>
          {!exposure.resolved && (
            <Alert
              type="error"
              showIcon
              role="alert"
              title="并非每个店铺都有生效的暴露额度"
              description={
                <Flex vertical gap={4}>
                  <span>没有当前授权的店铺不能接受新的广告操作。</span>
                  {exposure.unresolvedStoreIds.length > 0 && (
                    <Flex gap={6} wrap align="center">
                      <span>未确定的店铺：</span>
                      {exposure.unresolvedStoreIds.map((id) => (
                        <IdText key={id} value={id} />
                      ))}
                    </Flex>
                  )}
                </Flex>
              }
            />
          )}
          {exposure.resolved && exposure.unresolvedStoreIds.length > 0 && (
            <Flex gap={6} wrap align="center">
              <Typography.Text>未确定的店铺：</Typography.Text>
              {exposure.unresolvedStoreIds.map((id) => (
                <IdText key={id} value={id} />
              ))}
            </Flex>
          )}
          {exposure.envelopes.length > 0 && (
            <SectionCollapse
              items={exposure.envelopes.map((envelope) => ({
                key: envelope.envelopeId,
                title: `策略版本 ${String(envelope.policyVersion)}`,
                summary: `${codeLabel(EXPOSURE_SCOPE_LABELS, envelope.scopeKind)} · 测量窗口 ${
                  envelope.measurementWindowHours === undefined
                    ? '未知'
                    : `${String(envelope.measurementWindowHours)} 小时`
                }`,
                flags: envelopeFlags(envelope),
                defaultOpen: true,
                children: (
                  <div data-envelope={envelope.envelopeId}>
                    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
                      <Descriptions
                        bordered
                        size="small"
                        column={{ xs: 1, md: 2, xl: 3 }}
                        items={[
                          {
                            key: 'scope',
                            label: '范围',
                            children: (
                              <Space size={4} wrap>
                                <CodeTag labels={EXPOSURE_SCOPE_LABELS} code={envelope.scopeKind} />
                                {envelope.platformCode === undefined ? null : (
                                  <CodeTag labels={PLATFORM_LABELS} code={envelope.platformCode} />
                                )}
                                {envelope.storeId === undefined ? null : (
                                  <IdText value={envelope.storeId} prefix="店铺" />
                                )}
                              </Space>
                            ),
                          },
                          {
                            key: 'window',
                            label: '测量窗口',
                            children:
                              envelope.measurementWindowHours === undefined
                                ? '未知'
                                : `${String(envelope.measurementWindowHours)} 小时`,
                          },
                          {
                            key: 'retained',
                            label: '留存周期',
                            children:
                              envelope.retainedWindowDays === undefined
                                ? '未知'
                                : `${String(envelope.retainedWindowDays)} 天`,
                          },
                        ]}
                      />
                      <Table<AxisRow>
                        size="middle"
                        rowKey="code"
                        columns={AXIS_COLUMNS}
                        dataSource={ADVERTISING_EXPOSURE_AXES.map((code) => ({ code, envelope }))}
                        pagination={false}
                        scroll={{ x: 'max-content' }}
                        onRow={(row) => dataAttributes({ 'data-axis': row.code })}
                      />
                      {envelope.reasons.length > 0 && (
                        <Flex gap={6} wrap align="center">
                          <Typography.Text>未确定或已超限的控制：</Typography.Text>
                          <ReasonTags codes={envelope.reasons} />
                        </Flex>
                      )}
                    </Space>
                  </div>
                ),
              }))}
            />
          )}
        </Space>
      </SectionCard>
    </section>
  );
}

/** Holds, quarantines and kills, each named rather than reduced to a severity. */
function ContainmentPanel({
  containments,
  context,
  reload,
}: {
  readonly containments: readonly AdvertisingContainment[];
  readonly context: ConsoleRequest;
  readonly reload: () => void;
}): React.JSX.Element {
  const title = (
    <Space size={4}>
      <span>管控</span>
      <InfoTip title="五类管控互不等同，停止的范围取决于触发的是哪一类。" />
    </Space>
  );
  if (containments.length === 0) {
    return (
      <section aria-label="管控" data-state="empty">
        <SectionCard title={title}>
          <Typography.Text type="secondary">当前没有管控，广告执行未被停止。</Typography.Text>
        </SectionCard>
      </section>
    );
  }
  return (
    <section aria-label="管控" data-state="loaded">
      <SectionCard title={title}>
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {containments.map((hold) => (
            <div key={hold.id} data-kind={hold.containmentKind} data-state={hold.state}>
              <Card
                size="small"
                title={
                  <Space size={6} wrap>
                    <CodeTag
                      labels={CONTAINMENT_KIND_LABELS}
                      code={hold.containmentKind}
                      colors={{ [hold.containmentKind]: 'error' }}
                    />
                    <CodeTag
                      labels={CONTAINMENT_STATE_LABELS}
                      code={hold.state}
                      colors={CONTAINMENT_STATE_COLORS}
                    />
                  </Space>
                }
                extra={
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    启动于 <DateTime value={hold.activatedAt} />
                  </Typography.Text>
                }
              >
                <Space orientation="vertical" size="small" style={{ width: '100%' }}>
                  <Flex gap={6} wrap align="center">
                    {hold.scopeKind === undefined ? (
                      <AbsentValue label="未命名范围" />
                    ) : (
                      <CodeTag labels={CONTAINMENT_SCOPE_LABELS} code={hold.scopeKind} />
                    )}
                    <CodeTag labels={CAUSE_CLASS_LABELS} code={hold.causeClass} />
                  </Flex>
                  {hold.reason === undefined ? (
                    <Typography.Text type="secondary">未记录原因。</Typography.Text>
                  ) : (
                    <Typography.Text>{hold.reason}</Typography.Text>
                  )}
                  {hold.outstandingConditions.length === 0 ? (
                    <span data-ready={hold.readyToLift}>
                      {hold.readyToLift ? (
                        <Tag color="success">恢复条件均已满足，且两人均已签署</Tag>
                      ) : (
                        <Tag color="warning">条件均已满足，仍缺背书或批准</Tag>
                      )}
                    </span>
                  ) : (
                    <Flex gap={6} wrap align="center" aria-label="待满足的恢复条件">
                      <Typography.Text>待满足的恢复条件：</Typography.Text>
                      {hold.outstandingConditions.map((condition) => (
                        <CodeTag
                          key={condition}
                          labels={CONTAINMENT_CONDITION_LABELS}
                          code={condition}
                          colors={{ [condition]: 'warning' }}
                        />
                      ))}
                    </Flex>
                  )}
                  <AdvertisingRecoveryControls
                    context={context}
                    id={hold.id}
                    allowedActions={hold.allowedActions}
                    reload={reload}
                  />
                </Space>
              </Card>
            </div>
          ))}
        </Space>
      </SectionCard>
    </section>
  );
}

const RESERVATION_COLUMNS: TableColumnsType<AdvertisingReservation> = [
  {
    title: '干预',
    key: 'intervention',
    render: (_, held) => (
      <Space size={4} wrap>
        <CodeTag labels={RESERVATION_KIND_LABELS} code={held.interventionKind} />
        {held.direction === undefined ? null : (
          <CodeTag labels={DIRECTION_LABELS} code={held.direction} />
        )}
      </Space>
    ),
  },
  {
    title: '工作类型',
    key: 'lane',
    render: (_, held) => <CodeTag labels={LANE_LABELS} code={held.lane} colors={LANE_COLORS} />,
  },
  {
    title: '商品规格数',
    key: 'variants',
    align: 'right',
    render: (_, held) => held.productVariantIds.length,
  },
  {
    title: '状态',
    key: 'state',
    render: (_, held) => (
      <CodeTag
        labels={RESERVATION_STATE_LABELS}
        code={held.state}
        colors={RESERVATION_STATE_COLORS}
      />
    ),
  },
  {
    title: '占用时间',
    key: 'reservedAt',
    render: (_, held) => <DateTime value={held.reservedAt} />,
  },
  {
    title: '等待条件',
    key: 'waiting',
    render: (_, held) =>
      held.outstandingReleaseConditions.length === 0 ? (
        held.holding ? (
          <Tag color="success">无，可释放</Tag>
        ) : (
          <Typography.Text type="secondary">{held.releaseReason ?? '已释放'}</Typography.Text>
        )
      ) : (
        <Space size={[4, 4]} wrap>
          {held.outstandingReleaseConditions.map((condition) => (
            <CodeTag
              key={condition}
              labels={RELEASE_CONDITION_LABELS}
              code={condition}
              colors={{ [condition]: 'warning' }}
            />
          ))}
        </Space>
      ),
  },
];

/** Reservations, and what each one is waiting for before it can release. */
function ReservationPanel({
  reservations,
}: {
  readonly reservations: readonly AdvertisingReservation[];
}): React.JSX.Element {
  return (
    <section aria-label="额度占用" data-state={reservations.length === 0 ? 'empty' : 'loaded'}>
      <SectionCard
        title={
          <Space size={4}>
            <span>额度占用</span>
            <InfoTip title="这里只列出真实干预；无人处理的建议不占用任何额度。" />
          </Space>
        }
      >
        {reservations.length === 0 ? (
          <EmptyState description="当前没有额度占用。只有真实干预才会占用额度，所以队列满时这里也可能为空。" />
        ) : (
          <Table<AdvertisingReservation>
            size="middle"
            rowKey="id"
            columns={RESERVATION_COLUMNS}
            dataSource={[...reservations]}
            pagination={false}
            scroll={{ x: 'max-content' }}
            onRow={(held) => dataAttributes({ 'data-holding': held.holding })}
          />
        )}
      </SectionCard>
    </section>
  );
}
