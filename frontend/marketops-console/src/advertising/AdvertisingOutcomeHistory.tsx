import { Descriptions, Flex, Space, Timeline, Typography } from 'antd';
import type { DescriptionsProps } from 'antd';
import { useEffect, useState } from 'react';
import { fetchAdvertisingOutcomes, fetchAdvertisingManualOutcomes } from '../api/console';
import type { AdvertisingOutcome } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  GUARD_STATE_COLORS,
  GUARD_STATE_LABELS,
  OUTCOME_STAGE_LABELS,
  VALUE_STATE_LABELS,
  VERDICT_COLORS,
  VERDICT_LABELS,
} from '../i18n/zh/advertising';
import { codeLabel } from '../i18n';
import { CodeTag } from '../ui/CodeTag';
import { DateTime } from '../ui/DateTime';
import { EmptyState } from '../ui/EmptyState';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import { SectionCard } from '../ui/SectionCard';
import { AdvertisingEvidenceDetails } from './AdvertisingEvidenceDetails';
import { AbsentValue, DecimalValue, ReasonTags } from './shared';

/** What the outcome history needs in order to load itself. */
export interface AdvertisingOutcomeHistoryProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The command whose outcome is being read. */
  readonly commandId?: string;
  readonly manualPacketId?: string;
  /** Render inside another block instead of as a section of its own. */
  readonly embedded?: boolean;
}

const VERDICT_TIMELINE_COLORS: Readonly<Record<string, string>> = {
  IMPROVED: 'green',
  REGRESSED: 'red',
  INDETERMINATE: 'gold',
  NOT_YET_EVALUABLE: 'gold',
};

function metric(state: string, value: string | undefined): React.ReactNode {
  return value === undefined ? (
    <AbsentValue label={codeLabel(VALUE_STATE_LABELS, state)} />
  ) : (
    <DecimalValue value={value} />
  );
}

/** One observation, as a timeline entry. */
function ObservationEntry({
  observation,
}: {
  readonly observation: AdvertisingOutcome;
}): React.JSX.Element {
  const items: DescriptionsProps['items'] = [
    {
      key: 'baseline',
      label: '基线',
      children: (
        <span data-state={observation.baselineMetricState}>
          {metric(observation.baselineMetricState, observation.baselineMetricValue)}
        </span>
      ),
    },
    {
      key: 'observed',
      label: '观察值',
      children: (
        <span data-state={observation.observedMetricState}>
          {metric(observation.observedMetricState, observation.observedMetricValue)}
        </span>
      ),
    },
    {
      key: 'window',
      label: '观察窗口',
      children: (
        <Space size={4} wrap>
          <DateTime value={observation.windowStartsAt} />
          <Typography.Text type="secondary">至</Typography.Text>
          <DateTime value={observation.windowEndsAt} />
        </Space>
      ),
    },
  ];
  if (observation.settled) {
    items.push({
      key: 'coverage',
      label: '结算覆盖率',
      children: <DecimalValue value={observation.settledCoverageRatio} />,
    });
  }
  return (
    <div
      data-stage={observation.outcomeStage}
      data-settled={observation.settled}
      data-revision={observation.revisionNo}
    >
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        <Flex align="center" gap={6} wrap>
          <Typography.Text strong>
            {codeLabel(OUTCOME_STAGE_LABELS, observation.outcomeStage)}
          </Typography.Text>
          {observation.revisionNo > 1 && (
            <Typography.Text type="warning">第 {observation.revisionNo} 次重述</Typography.Text>
          )}
          <span data-verdict={observation.verdict}>
            <CodeTag labels={VERDICT_LABELS} code={observation.verdict} colors={VERDICT_COLORS} />
          </span>
          {observation.guardState === undefined ? null : (
            <Space size={4}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                完成销售防护
              </Typography.Text>
              <CodeTag
                labels={GUARD_STATE_LABELS}
                code={observation.guardState}
                colors={GUARD_STATE_COLORS}
              />
            </Space>
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            评估于 <DateTime value={observation.evaluatedAt} />
          </Typography.Text>
        </Flex>
        <Typography.Text
          type="secondary"
          aria-label="效果推断范围"
          data-inference-scope={observation.inferenceScope}
        >
          {observation.inferenceScope === 'OPERATIONAL_ASSOCIATION_NOT_CAUSAL_INCREMENTALITY'
            ? '仅为观察到的关联，未证明因果增量。'
            : '推断范围未知。'}
        </Typography.Text>
        <Descriptions bordered size="small" column={{ xs: 1, md: 2, xl: 3 }} items={items} />
        {observation.adjustmentReason === undefined ? null : (
          <Typography.Text data-adjustment="true">
            重述原因：{observation.adjustmentReason}
          </Typography.Text>
        )}
        {observation.unresolvedReasonCodes.length === 0 ? null : (
          <div aria-label="结论不确定的原因">
            <ReasonTags codes={observation.unresolvedReasonCodes} color="warning" />
          </div>
        )}
        {observation.axes !== undefined && (
          <AdvertisingEvidenceDetails value={observation.axes} label="双轴经济与销售保持" />
        )}
      </Space>
    </div>
  );
}

/**
 * What was observed after one bid change, stage by stage.
 *
 * Completed, 30-day retained and settled stages carry independent sales and
 * economic observations. Unknown axes remain unresolved at every stage.
 *
 * Restatements are shown in place rather than replacing what they restate. A
 * settled figure can change when a late return arrives, and the fact that the
 * answer changed is itself something an operator needs to see — particularly
 * when the original reading was the one somebody acted on.
 */
export function AdvertisingOutcomeHistory({
  context,
  commandId,
  manualPacketId,
  embedded = false,
}: AdvertisingOutcomeHistoryProps): React.JSX.Element {
  const [outcomes, setOutcomes] = useState<readonly AdvertisingOutcome[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    if (commandId === undefined && manualPacketId === undefined) return;
    const pending =
      manualPacketId !== undefined
        ? fetchAdvertisingManualOutcomes(context, manualPacketId)
        : fetchAdvertisingOutcomes(context, commandId ?? '');
    void pending.then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setOutcomes(outcome.value);
        setFailure(undefined);
      } else {
        setOutcomes(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, commandId, manualPacketId]);

  const state =
    failure !== undefined
      ? 'error'
      : outcomes === undefined
        ? 'loading'
        : outcomes.length === 0
          ? 'empty'
          : 'loaded';

  const body =
    failure !== undefined ? (
      <FailureAlert failure={failure} />
    ) : outcomes === undefined ? (
      <LoadingState rows={2} />
    ) : outcomes.length === 0 ? (
      <EmptyState description="尚未观察到效果。没有结果不等于中性结果——观察窗口还未结束。" />
    ) : (
      <Timeline
        items={outcomes.map((observation) => ({
          key: observation.id,
          color: VERDICT_TIMELINE_COLORS[observation.verdict] ?? 'gray',
          content: <ObservationEntry observation={observation} />,
        }))}
      />
    );

  return (
    <section aria-label="效果" data-state={state}>
      {embedded ? (
        <Space orientation="vertical" size="small" style={{ width: '100%' }}>
          <Typography.Text strong>效果</Typography.Text>
          {body}
        </Space>
      ) : (
        <SectionCard title="效果">{body}</SectionCard>
      )}
    </section>
  );
}
