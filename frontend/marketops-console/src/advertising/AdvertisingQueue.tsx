import { Button, Flex, Segmented, Space, Table, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { fetchAdvertisingQueue } from '../api/console';
import type { AdvertisingCase } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { actions } from '../i18n';
import {
  CAUSE_LABELS,
  LANE_COLORS,
  LANE_LABELS,
  OBJECT_KIND_LABELS,
  PROTECTION_TIER_COLORS,
  PROTECTION_TIER_LABELS,
  ROLE_LABELS,
} from '../i18n/zh/advertising';
import { CodeTag } from '../ui/CodeTag';
import { EmptyState } from '../ui/EmptyState';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import { SectionCard } from '../ui/SectionCard';
import { EvidenceChip } from './EvidenceChip';
import { MeasureValue, ReasonTags, dataAttributes } from './shared';

/** What the advertising queue needs in order to load itself. */
export interface AdvertisingQueueProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** Called when the operator opens a case. */
  readonly onSelect: (caseId: string) => void;
}

/** The lanes an operator may narrow to, in the order the product ranks them. */
const LANES = ['PROTECTION', 'DATA_REPAIR', 'OPTIMIZATION', 'WATCH'] as const;

const PAGE_SIZE = 50;
const ALL = 'ALL';

/**
 * The advertising work list, in the order the product ranks it.
 *
 * Not re-sorted here. The rank is a non-compensating band score with a
 * published definition — a protection case outranks every optimization case
 * whatever the money involved — and a console that re-ordered by amount would
 * be presenting its own opinion as the product's, in the one place where the
 * ordering is the safety property. The table therefore has no sortable columns.
 *
 * Every measure carries its state beside it. An operator scanning the spend
 * column has to be able to tell "nothing was spent" from "nobody knows what was
 * spent", because one of those is a finding and the other is a gap.
 */
export function AdvertisingQueue({ context, onSelect }: AdvertisingQueueProps): React.JSX.Element {
  const [offset, setOffset] = useState(0);
  const [lane, setLane] = useState<string | undefined>(undefined);
  const [revision, setRevision] = useState(0);
  const [cases, setCases] = useState<readonly AdvertisingCase[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchAdvertisingQueue(context, lane, PAGE_SIZE, offset).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setCases(outcome.value);
        setFailure(undefined);
      } else {
        setCases(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, lane, offset, revision]);

  const columns: TableColumnsType<AdvertisingCase> = [
    {
      title: '广告对象',
      key: 'object',
      render: (_, row) => (
        <Flex vertical gap={0}>
          <Button
            type="link"
            style={{ padding: 0, height: 'auto', textAlign: 'left', whiteSpace: 'normal' }}
            onClick={() => {
              onSelect(row.id);
            }}
          >
            {row.nativeObjectName === undefined ? (
              <span>{row.adNativeObjectId}</span>
            ) : (
              <span lang="ru">{row.nativeObjectName}</span>
            )}
          </Button>
          <Typography.Text
            type="secondary"
            style={{ fontSize: 12 }}
            data-object-kind={row.nativeObjectKind}
          >
            {OBJECT_KIND_LABELS[row.nativeObjectKind] ?? row.nativeObjectKind}
          </Typography.Text>
        </Flex>
      ),
    },
    {
      title: '工作类型',
      key: 'lane',
      render: (_, row) => (
        <Space size={4} wrap data-protection-tier={row.protectionTier ?? ''}>
          <CodeTag labels={LANE_LABELS} code={row.lane} colors={LANE_COLORS} />
          {row.protectionTier === undefined ? null : (
            <CodeTag
              labels={PROTECTION_TIER_LABELS}
              code={row.protectionTier}
              colors={PROTECTION_TIER_COLORS}
            />
          )}
        </Space>
      ),
    },
    {
      title: '原因',
      key: 'cause',
      render: (_, row) => <CodeTag labels={CAUSE_LABELS} code={row.causeCode} />,
    },
    {
      title: '负责角色',
      key: 'owner',
      render: (_, row) =>
        row.accountableRoleCode === undefined ? (
          <Typography.Text type="secondary">未分配</Typography.Text>
        ) : (
          <CodeTag labels={ROLE_LABELS} code={row.accountableRoleCode} />
        ),
    },
    {
      title: '官方花费',
      key: 'spend',
      align: 'right',
      onCell: (row) =>
        dataAttributes({ 'data-measure': 'spend', 'data-measure-state': row.officialSpendState }),
      render: (_, row) => (
        <MeasureValue
          state={row.officialSpendState}
          value={row.officialSpendAmount}
          currency={row.profitCurrencyCode}
          kind="money"
        />
      ),
    },
    {
      title: '每广告卢布利润',
      key: 'profitPerAdRub',
      align: 'right',
      onCell: (row) =>
        dataAttributes({
          'data-measure': 'profit-per-ad-rub',
          'data-measure-state': row.profitPerAdRubState,
        }),
      render: (_, row) => (
        <MeasureValue state={row.profitPerAdRubState} value={row.profitPerAdRubValue} />
      ),
    },
    {
      title: '证据',
      key: 'evidence',
      render: (_, row) => (
        <Space size={[4, 4]} wrap>
          <EvidenceChip state={row.evidenceState} of="证据" />
          {row.blockerCodes.length > 0 && (
            <span data-blockers={row.blockerCodes.join(',')}>
              <ReasonTags codes={row.blockerCodes} />
            </span>
          )}
        </Space>
      ),
    },
  ];

  const page = Math.floor(offset / PAGE_SIZE) + 1;

  return (
    <section
      aria-label="广告工作台"
      data-state={failure !== undefined ? 'error' : cases !== undefined ? 'loaded' : 'loading'}
    >
      <SectionCard
        title="广告工作台"
        extra={
          <Button
            icon={<ReloadOutlined />}
            onClick={() => {
              setRevision((value) => value + 1);
            }}
          >
            {actions.refresh}
          </Button>
        }
      >
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
            按系统排名排序：止损保护类事项始终排在优化类之前，与金额大小无关。
          </Typography.Paragraph>
          <Segmented
            aria-label="工作类型"
            value={lane ?? ALL}
            options={[
              { label: '全部', value: ALL },
              ...LANES.map((name) => ({ label: LANE_LABELS[name] ?? name, value: name })),
            ]}
            onChange={(value) => {
              setLane(value === ALL ? undefined : value);
              setOffset(0);
            }}
          />
          {failure !== undefined ? (
            <FailureAlert failure={failure} />
          ) : cases === undefined ? (
            <LoadingState rows={5} />
          ) : cases.length === 0 && offset === 0 ? (
            <div data-empty="advertising-queue">
              <EmptyState description="该工作类型下暂无事项（这只说明队列为空，不代表计算未运行）" />
            </div>
          ) : (
            <Table<AdvertisingCase>
              size="middle"
              rowKey="id"
              columns={columns}
              dataSource={[...cases]}
              pagination={false}
              scroll={{ x: 'max-content' }}
              onRow={(row) => dataAttributes({ 'data-case-id': row.id, 'data-lane': row.lane })}
              locale={{ emptyText: <EmptyState description="本页暂无事项" /> }}
            />
          )}
          <nav aria-label="广告事项分页">
            <Flex justify="flex-end" align="center" gap={8}>
              <Button
                disabled={offset === 0}
                onClick={() => {
                  setOffset(Math.max(0, offset - PAGE_SIZE));
                }}
              >
                {actions.previousPage}
              </Button>
              <Typography.Text type="secondary">第 {page} 页</Typography.Text>
              <Button
                disabled={cases === undefined || cases.length < PAGE_SIZE}
                onClick={() => {
                  setOffset(offset + PAGE_SIZE);
                }}
              >
                {actions.nextPage}
              </Button>
            </Flex>
          </nav>
        </Space>
      </SectionCard>
    </section>
  );
}
