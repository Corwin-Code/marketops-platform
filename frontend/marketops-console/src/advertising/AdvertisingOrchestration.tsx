import { Button, Descriptions, Flex, Space, Typography } from 'antd';
import type { DescriptionsProps } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { fetchAdvertisingOrchestration } from '../api/console';
import type { ConsoleRequest, ConsoleFailure } from '../api/console';
import { actions } from '../i18n';
import {
  DISTRIBUTION_STATE_LABELS,
  INCIDENT_LABELS,
  ORCHESTRATION_STATE_COLORS,
  ORCHESTRATION_STATE_LABELS,
  SWEEP_STATE_COLORS,
  SWEEP_STATE_LABELS,
} from '../i18n/zh/advertising';
import { CodeTag } from '../ui/CodeTag';
import { DateTime } from '../ui/DateTime';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import { SectionCard } from '../ui/SectionCard';
import { TechnicalDetails } from '../ui/TechnicalDetails';
import { AbsentValue, field, fieldText, stringList } from './shared';

function count(state: Readonly<Record<string, unknown>>, key: string): React.ReactNode {
  return fieldText(state, key) ?? <AbsentValue label="无数据" />;
}

export function AdvertisingOrchestration({
  context,
}: {
  readonly context: ConsoleRequest;
}): React.JSX.Element {
  const [state, setState] = useState<Readonly<Record<string, unknown>>>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    void fetchAdvertisingOrchestration(context).then((result) => {
      if (!active) return;
      if (result.ok) {
        setState(result.value);
        setFailure(undefined);
      } else {
        setFailure(result.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, revision]);

  const incidents = stringList(state, 'incidents');
  const items: DescriptionsProps['items'] =
    state === undefined
      ? []
      : [
          {
            key: 'window',
            label: '统计窗口',
            children: `${fieldText(state, 'windowHours') ?? '—'} 小时`,
          },
          { key: 'samples', label: '样本数', children: count(state, 'sampleCount') },
          { key: 'critical', label: '关键样本数', children: count(state, 'criticalSampleCount') },
          {
            key: 'p95',
            label: '关键延迟 P95（毫秒）',
            children: count(state, 'criticalP95Millis'),
          },
          { key: 'max', label: '最大延迟（毫秒）', children: count(state, 'maximumMillis') },
          { key: 'hard', label: '硬上限突破次数', children: count(state, 'hardBreachCount') },
          { key: 'clock', label: '时钟异常次数', children: count(state, 'clockDefectCount') },
          { key: 'pending', label: '待处理重算', children: count(state, 'pendingRequests') },
          { key: 'failed', label: '失败重算', children: count(state, 'failedRequests') },
          {
            key: 'oldest',
            label: '最早待处理数据',
            children: <DateTime value={field(state, 'oldestFactAcceptedAt')} />,
          },
          {
            key: 'sweep',
            label: '最近对账',
            children: (
              <Space size={4} wrap>
                <CodeTag
                  labels={SWEEP_STATE_LABELS}
                  code={field(state, 'lastSweepState')}
                  colors={SWEEP_STATE_COLORS}
                />
                <DateTime value={field(state, 'lastSweepCompletedAt')} />
              </Space>
            ),
          },
          {
            key: 'observed',
            label: '观测时间',
            children: <DateTime value={field(state, 'observedAt')} />,
          },
        ];

  return (
    <section aria-label="广告响应与对账">
      <SectionCard
        title="响应与对账"
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
        {failure !== undefined ? (
          <FailureAlert failure={failure} />
        ) : state === undefined ? (
          <LoadingState rows={2} />
        ) : (
          <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
            <Flex
              align="center"
              gap={8}
              wrap
              role={state.state === 'INCIDENT' ? 'alert' : 'status'}
            >
              <CodeTag
                labels={ORCHESTRATION_STATE_LABELS}
                code={field(state, 'state')}
                colors={ORCHESTRATION_STATE_COLORS}
              />
              <CodeTag
                labels={DISTRIBUTION_STATE_LABELS}
                code={field(state, 'distributionState')}
              />
              {incidents.map((incident) => (
                <CodeTag
                  key={incident}
                  labels={INCIDENT_LABELS}
                  code={incident}
                  colors={{ [incident]: 'error' }}
                />
              ))}
            </Flex>
            <Descriptions bordered size="small" column={{ xs: 1, md: 2, xl: 3 }} items={items} />
            <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
              数据延迟与人工值班响应是两套独立的时钟；对账会保留之前的违规记录。
            </Typography.Paragraph>
            <TechnicalDetails data={state} label="响应与恢复证据（原始数据）" />
          </Space>
        )}
      </SectionCard>
    </section>
  );
}
