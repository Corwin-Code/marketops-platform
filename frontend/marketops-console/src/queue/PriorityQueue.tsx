import { Badge, Button, Flex, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { fetchPriorityQueue } from '../api/console';
import type { ConsoleFailure, ConsoleRequest, PrioritySubject } from '../api/console';
import { formatDecimal } from '../format';
import { actions } from '../i18n';
import { RULE_LABELS } from '../i18n/zh/pricing';
import { CodeTag, EmptyState, FailureAlert, LoadingState, Money, SectionCard } from '../ui';

/** What the queue needs in order to load itself. */
export interface PriorityQueueProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** Store whose work list is being shown. */
  readonly storeId: string;
  /** Called when the operator picks a subject to look at. */
  readonly onSelect: (subjectId: string) => void;
}

/** A count that draws the eye only when it is not zero. */
function CountBadge({
  value,
  color,
}: {
  readonly value: number;
  readonly color: string;
}): React.JSX.Element {
  return value === 0 ? (
    <Typography.Text type="secondary">0</Typography.Text>
  ) : (
    <Badge count={value} color={color} overflowCount={999} showZero />
  );
}

/**
 * What to look at first.
 *
 * The list is ordered by the backend rather than re-sorted here, because the
 * priority is a deterministic figure with a definition and a console that
 * re-ordered it would be presenting its own opinion as the product's.
 *
 * A subject whose findings block a platform write says so in the row. An
 * operator who opens a subject expecting to change its price and only then
 * learns that nothing can be changed has spent their attention for nothing.
 */
export function PriorityQueue({
  context,
  storeId,
  onSelect,
}: PriorityQueueProps): React.JSX.Element {
  const [subjects, setSubjects] = useState<readonly PrioritySubject[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let active = true;
    void fetchPriorityQueue(context, storeId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setSubjects(outcome.value);
        setFailure(undefined);
      } else {
        setSubjects(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, storeId, reload]);

  const refresh = (
    <Button
      icon={<ReloadOutlined />}
      aria-label="刷新今日工作"
      onClick={() => {
        setReload((value) => value + 1);
      }}
    >
      {actions.refresh}
    </Button>
  );

  const columns: TableColumnsType<PrioritySubject> = [
    {
      title: '商品（SKU）',
      key: 'subject',
      render: (_, subject) => (
        <Flex vertical gap={0}>
          <Button
            type="link"
            style={{ padding: 0, height: 'auto' }}
            aria-label={`查看商品 ${subject.subjectId} 的诊断`}
            onClick={() => {
              onSelect(subject.subjectId);
            }}
          >
            查看诊断
          </Button>
          <Typography.Text
            type="secondary"
            style={{ fontSize: 12 }}
            copyable={{ text: subject.subjectId }}
          >
            {subject.subjectId.slice(0, 8)}
          </Typography.Text>
        </Flex>
      ),
    },
    {
      title: (
        <Tooltip title="由后端确定性计算，列表按此排序">
          <span>优先级</span>
        </Tooltip>
      ),
      key: 'priority',
      render: (_, subject) => (
        <Typography.Text strong style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatDecimal(subject.priorityScore, { maxFractionDigits: 2 })}
        </Typography.Text>
      ),
    },
    {
      title: '严重',
      key: 'critical',
      align: 'center',
      render: (_, subject) => <CountBadge value={subject.criticalFindingCount} color="red" />,
    },
    {
      title: '警告',
      key: 'warning',
      align: 'center',
      render: (_, subject) => <CountBadge value={subject.warningFindingCount} color="gold" />,
    },
    {
      title: (
        <Tooltip title="因数据不足等原因无法得出结论的规则数">
          <span>无法判断</span>
        </Tooltip>
      ),
      key: 'declined',
      align: 'center',
      render: (_, subject) => <CountBadge value={subject.declinedRuleCount} color="orange" />,
    },
    {
      title: '净销售额',
      key: 'netSales',
      align: 'right',
      render: (_, subject) => <Money value={subject.netSales} currency={subject.currencyCode} />,
    },
    {
      title: '贡献利润',
      key: 'profit',
      align: 'right',
      render: (_, subject) => (
        <Money value={subject.contributionProfit} currency={subject.currencyCode} />
      ),
    },
    {
      title: '平台写入',
      key: 'write',
      onCell: (subject) =>
        ({
          'data-write-blocked': subject.blockingRuleCodes.length > 0,
        }) as React.TdHTMLAttributes<HTMLTableCellElement>,
      render: (_, subject): ReactNode =>
        subject.blockingRuleCodes.length > 0 ? (
          <Space size={[4, 4]} wrap>
            <Tag color="error">已阻断</Tag>
            {subject.blockingRuleCodes.map((code) => (
              <CodeTag key={code} labels={RULE_LABELS} code={code} />
            ))}
          </Space>
        ) : (
          <Tag color="success">可调价</Tag>
        ),
    },
  ];

  let body: ReactNode;
  let state: string;
  if (failure !== undefined) {
    state = 'error';
    body = <FailureAlert failure={failure} />;
  } else if (subjects === undefined) {
    state = 'loading';
    body = <LoadingState rows={5} />;
  } else if (subjects.length === 0) {
    state = 'empty';
    body = <EmptyState description="当前店铺暂无需要处理的商品" />;
  } else {
    state = 'loaded';
    body = (
      <Table<PrioritySubject>
        size="middle"
        rowKey="subjectId"
        columns={columns}
        dataSource={[...subjects]}
        pagination={{ pageSize: 20, hideOnSinglePage: true, showSizeChanger: false }}
        scroll={{ x: 'max-content' }}
        onRow={(subject) =>
          ({ 'data-subject': subject.subjectId }) as React.HTMLAttributes<HTMLElement>
        }
      />
    );
  }

  return (
    <section aria-label="今日工作" data-state={state}>
      <SectionCard title="今日工作" extra={refresh} state={state}>
        <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
          按处理优先级排列的商品；标记为已阻断的商品当前不能调价。
        </Typography.Paragraph>
        {body}
      </SectionCard>
    </section>
  );
}

/** Optional framing for a failed list read. */
export interface QueueProblemProps {
  readonly failure: ConsoleFailure;
  /** Accessible name of the region; defaults to 加载失败. */
  readonly label?: string;
}

/**
 * Say what went wrong in terms of what the operator can do about it.
 *
 * Shared by other work lists, so it carries no heading of its own: the caller's
 * section already names the list, and a fixed title would mislabel theirs.
 */
export function QueueProblem({
  failure,
  label = '加载失败',
}: QueueProblemProps): React.JSX.Element {
  return (
    <section aria-label={label} data-state="error">
      <FailureAlert failure={failure} />
    </section>
  );
}
