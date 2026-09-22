import { Alert, Button, Card, Flex, Space, Table, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { fetchAdvertisingCommand } from '../api/console';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  ATTEMPT_OUTCOME_COLORS,
  ATTEMPT_OUTCOME_LABELS,
  ATTEMPT_PURPOSE_LABELS,
  BID_UNIT_LABELS,
  COMMAND_STATE_COLORS,
  COMMAND_STATE_LABELS,
  READBACK_MATCH_COLORS,
  READBACK_MATCH_LABELS,
  REASON_LABELS,
} from '../i18n/zh/advertising';
import { CodeTag } from '../ui/CodeTag';
import { DateTime } from '../ui/DateTime';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import { Money } from '../ui/Money';
import { TechnicalDetails } from '../ui/TechnicalDetails';
import { AdvertisingOutcomeHistory } from './AdvertisingOutcomeHistory';
import { AdvertisingCompensation } from './AdvertisingCompensation';
import { AdvertisingTimestamp } from './AdvertisingTimestamp';
import { AbsentValue, decimalField, field, fieldText } from './shared';
import type { AdRow } from './shared';

const UNRESOLVED_STATES = [
  'UNKNOWN_REQUIRES_READBACK',
  'READBACK_MISMATCH',
  'MANUAL_RESOLUTION',
  'COMPENSATION_FAILED',
];

function records(value: unknown): readonly AdRow[] {
  return Array.isArray(value)
    ? value.filter((item): item is AdRow => typeof item === 'object' && item !== null)
    : [];
}

const ATTEMPT_COLUMNS: TableColumnsType<AdRow> = [
  { title: '次数', key: 'attemptNo', render: (_, row) => fieldText(row, 'attemptNo') ?? '—' },
  {
    title: '用途',
    key: 'purpose',
    render: (_, row) => <CodeTag labels={ATTEMPT_PURPOSE_LABELS} code={field(row, 'purpose')} />,
  },
  {
    title: '结果',
    key: 'outcome',
    render: (_, row) => (
      <CodeTag
        labels={ATTEMPT_OUTCOME_LABELS}
        code={field(row, 'outcomeClass')}
        colors={ATTEMPT_OUTCOME_COLORS}
      />
    ),
  },
  {
    title: '平台状态',
    key: 'nativeStatus',
    render: (_, row) => field(row, 'nativeStatus') ?? '—',
  },
  {
    title: '错误代码',
    key: 'errorCode',
    render: (_, row) => {
      const code = field(row, 'errorCode');
      return code === undefined ? '—' : <CodeTag labels={REASON_LABELS} code={code} />;
    },
  },
  {
    title: '开始',
    key: 'startedAt',
    render: (_, row) => <DateTime value={field(row, 'startedAt')} />,
  },
  {
    title: '完成',
    key: 'completedAt',
    render: (_, row) => <DateTime value={field(row, 'completedAt')} />,
  },
];

const READBACK_COLUMNS: TableColumnsType<AdRow> = [
  {
    title: '比对结果',
    key: 'match',
    render: (_, row) => (
      <CodeTag
        labels={READBACK_MATCH_LABELS}
        code={field(row, 'matchState')}
        colors={READBACK_MATCH_COLORS}
      />
    ),
  },
  {
    title: '观察到的出价',
    key: 'observedBid',
    align: 'right',
    render: (_, row) => {
      const bid = decimalField(row, 'observedBid');
      return bid === undefined ? (
        <AbsentValue label="未读取" />
      ) : (
        <Money value={bid} currency={field(row, 'currencyCode') ?? null} />
      );
    },
  },
  {
    title: '单位',
    key: 'unit',
    render: (_, row) => <CodeTag labels={BID_UNIT_LABELS} code={field(row, 'bidUnitCode')} />,
  },
  {
    title: '观察时间',
    key: 'observedAt',
    render: (_, row) => <DateTime value={field(row, 'observedAt')} />,
  },
];

/** Native submission, configuration observation and business outcomes remain separate. */
export function AdvertisingCommandTimeline({
  context,
  commandId,
  timezone,
}: {
  readonly context: ConsoleRequest;
  readonly commandId: string;
  readonly timezone: string | undefined;
}): React.JSX.Element {
  const [command, setCommand] = useState<Readonly<Record<string, unknown>>>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    void fetchAdvertisingCommand(context, commandId).then((result) => {
      if (!active) return;
      if (result.ok) {
        setCommand(result.value);
        setFailure(undefined);
      } else setFailure(result.failure);
    });
    return () => {
      active = false;
    };
  }, [context, commandId, revision]);

  const state = field(command, 'state');
  const currency = field(command, 'currencyCode') ?? null;
  const prior = decimalField(command, 'priorBidAmount');
  const target = decimalField(command, 'targetBidAmount');
  const approvalExpiresAt = field(command, 'approvalExpiresAt');
  const attempts = records(command?.attempts);
  const readbacks = records(command?.readbacks);

  return (
    <section
      aria-label="广告出价指令"
      data-command-id={commandId}
      data-state={command === undefined ? 'loading' : 'loaded'}
    >
      <Card
        size="small"
        type="inner"
        title="出价指令"
        extra={
          <Button
            size="small"
            icon={<ReloadOutlined />}
            onClick={() => {
              setRevision((value) => value + 1);
            }}
          >
            刷新指令与效果
          </Button>
        }
      >
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {failure !== undefined && <FailureAlert failure={failure} />}
          {command === undefined && failure === undefined && <LoadingState rows={3} />}
          {command !== undefined && (
            <>
              <Space size={6} wrap>
                <CodeTag labels={COMMAND_STATE_LABELS} code={state} colors={COMMAND_STATE_COLORS} />
                {prior === undefined ? (
                  <AbsentValue label="未确定" />
                ) : (
                  <Money value={prior} currency={currency} />
                )}
                <Typography.Text type="secondary">→</Typography.Text>
                {target === undefined ? (
                  <AbsentValue label="未确定" />
                ) : (
                  <Money value={target} currency={currency} strong />
                )}
                <CodeTag
                  labels={BID_UNIT_LABELS}
                  code={field(command, 'bidUnitCode') ?? 'UNRESOLVED'}
                />
              </Space>
              <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
                平台受理、配置回读和业务效果分别独立确认。
              </Typography.Paragraph>
              {typeof command.failureCode === 'string' && (
                <Flex align="center" gap={6} wrap>
                  <Typography.Text>当前拒绝原因：</Typography.Text>
                  <CodeTag labels={REASON_LABELS} code={command.failureCode} />
                </Flex>
              )}
              {approvalExpiresAt !== undefined && Date.parse(approvalExpiresAt) <= Date.now() && (
                <Alert
                  type="warning"
                  showIcon
                  title="审批已过期"
                  description="已有的指令和观察记录仍保留在历史中；这次审批不能再授权新的发送。"
                />
              )}
              {UNRESOLVED_STATES.includes(String(command.state)) && (
                <Alert
                  type="error"
                  showIcon
                  role="alert"
                  title="配置状态未确定"
                  description="请保留额度占用，并在下一次干预前核对当前证据。"
                />
              )}
              <Flex align="center" gap={6} wrap>
                <Typography.Text type="secondary">审批到期时间：</Typography.Text>
                <AdvertisingTimestamp value={approvalExpiresAt} timezone={timezone} />
              </Flex>
              <div aria-label="发送与状态查询记录">
                <Typography.Text strong>发送与状态查询</Typography.Text>
                <Table<AdRow>
                  size="middle"
                  rowKey={(row) => field(row, 'id') ?? JSON.stringify(row)}
                  columns={ATTEMPT_COLUMNS}
                  dataSource={[...attempts]}
                  pagination={false}
                  scroll={{ x: 'max-content' }}
                  locale={{ emptyText: '暂无发送记录' }}
                />
              </div>
              <div aria-label="平台配置回读记录">
                <Typography.Text strong>配置回读</Typography.Text>
                <Table<AdRow>
                  size="middle"
                  rowKey={(row) => field(row, 'id') ?? JSON.stringify(row)}
                  columns={READBACK_COLUMNS}
                  dataSource={[...readbacks]}
                  pagination={false}
                  scroll={{ x: 'max-content' }}
                  locale={{ emptyText: '暂无回读记录' }}
                />
              </div>
              <TechnicalDetails data={command} />
              <AdvertisingOutcomeHistory
                key={revision}
                context={context}
                commandId={commandId}
                embedded
              />
              <AdvertisingCompensation
                key={`compensation-${String(revision)}`}
                context={context}
                commandId={commandId}
              />
            </>
          )}
        </Space>
      </Card>
    </section>
  );
}
