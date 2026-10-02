import { Alert, Descriptions, Space, Typography } from 'antd';
import type { DescriptionsProps } from 'antd';
import { useEffect, useState } from 'react';
import { fetchAdvertisingOrchestration } from '../api/console';
import type { ConsoleRequest, ConsoleFailure } from '../api/console';
import { codeLabel } from '../i18n';
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
import { SectionCollapse } from '../ui/SectionCollapse';
import type { SectionFlag } from '../ui/SectionCollapse';
import { TechnicalDetails } from '../ui/TechnicalDetails';
import { AbsentValue, field, fieldText, stringList } from './shared';

function count(state: Readonly<Record<string, unknown>>, key: string): React.ReactNode {
  return fieldText(state, key) ?? <AbsentValue label="无数据" />;
}

/**
 * How promptly advertising facts are recalculated and reconciled.
 *
 * Read occasionally, so folded; its state, distribution and every incident stay
 * in the header while folded, and an incident also raises an alert above it.
 */
export function AdvertisingOrchestration({
  context,
  revision = 0,
}: {
  readonly context: ConsoleRequest;
  /** Changes when the page asks every panel to read again. */
  readonly revision?: number;
}): React.JSX.Element {
  const [state, setState] = useState<Readonly<Record<string, unknown>>>();
  const [failure, setFailure] = useState<ConsoleFailure>();
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

  if (failure !== undefined || state === undefined) {
    return (
      <section aria-label="广告响应与对账">
        <SectionCard title="响应与对账">
          {failure !== undefined ? <FailureAlert failure={failure} /> : <LoadingState rows={2} />}
        </SectionCard>
      </section>
    );
  }

  const incidents = stringList(state, 'incidents');
  const orchestration = field(state, 'state');
  const stateColor =
    orchestration === undefined ? undefined : ORCHESTRATION_STATE_COLORS[orchestration];
  const flags: SectionFlag[] = [
    {
      key: 'state',
      label: codeLabel(ORCHESTRATION_STATE_LABELS, orchestration),
      color: stateColor ?? 'default',
    },
    {
      key: 'distribution',
      label: codeLabel(DISTRIBUTION_STATE_LABELS, field(state, 'distributionState')),
      color: 'default',
    },
    ...incidents.map((incident) => ({
      key: incident,
      label: codeLabel(INCIDENT_LABELS, incident),
      color: 'error',
    })),
  ];
  const items: DescriptionsProps['items'] = [
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
  ];

  return (
    <section aria-label="广告响应与对账" data-state={orchestration}>
      {orchestration === 'INCIDENT' && (
        <Alert
          type="error"
          showIcon
          role="alert"
          title="广告响应与对账存在事故"
          description={
            incidents.length === 0
              ? '事故类型未返回，请展开「响应与对账」查看原始数据。'
              : incidents.map((incident) => codeLabel(INCIDENT_LABELS, incident)).join('、')
          }
          style={{ marginBottom: 16 }}
        />
      )}
      <SectionCollapse
        items={[
          {
            key: 'orchestration',
            title: '响应与对账',
            summary: (
              <span>
                观测于 <DateTime value={field(state, 'observedAt')} />
              </span>
            ),
            flags,
            children: (
              <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
                <Descriptions
                  bordered
                  size="small"
                  column={{ xs: 1, md: 2, xl: 3 }}
                  items={items}
                />
                <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
                  数据延迟与人工值班响应是两套独立的时钟；对账会保留之前的违规记录。
                </Typography.Paragraph>
                <TechnicalDetails data={state} label="响应与恢复证据（原始数据）" />
              </Space>
            ),
          },
        ]}
      />
    </section>
  );
}
