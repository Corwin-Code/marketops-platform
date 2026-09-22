import { Alert, Button, Collapse, Flex, List, Space, Tag, Typography } from 'antd';
import { RobotOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import { requestExplanation } from '../api/console';
import type { AiExplanation, ConsoleFailure, ConsoleRequest } from '../api/console';
import { codeLabel } from '../i18n';
import {
  AI_CLAIM_KIND_COLORS,
  AI_FAILURE_LABELS,
  AI_REJECTION_LABELS,
  AI_STATE_COLORS,
  AI_STATE_LABELS,
} from '../i18n/zh/pricing';
import { CodeTag, FailureAlert, SectionCard, TechnicalDetails } from '../ui';

/** The one sentence that must stand beside every model output. */
const ADVISORY_NOTICE = 'AI 生成，仅供参考，不作为官方口径';

/** Separates advisory model statements from canonical values and approval authority. */
export function AiExplanationPanel({
  context,
  subjectId,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly subjectId: string;
  readonly storeId: string;
}): React.JSX.Element {
  const [output, setOutput] = useState<AiExplanation | undefined>();
  const [failure, setFailure] = useState<ConsoleFailure | undefined>();
  const [pending, setPending] = useState(false);
  const generation = useRef(0);
  useEffect(() => {
    generation.current++;
    setOutput(undefined);
    setFailure(undefined);
    setPending(false);
    return () => {
      generation.current++;
    };
  }, [context, subjectId, storeId]);

  async function explain(): Promise<void> {
    const requestGeneration = ++generation.current;
    setPending(true);
    setOutput(undefined);
    setFailure(undefined);
    const result = await requestExplanation(context, subjectId, storeId);
    if (requestGeneration !== generation.current) return;
    setPending(false);
    if (result.ok && result.value.subjectId === subjectId) setOutput(result.value);
    else setFailure(result.ok ? { kind: 'malformed', detail: 'subject mismatch' } : result.failure);
  }

  return (
    <section aria-label="AI 解释">
      <SectionCard
        title={
          <Space size="small">
            <RobotOutlined />
            <span>AI 解释</span>
          </Space>
        }
        extra={
          <Button
            type="primary"
            ghost
            loading={pending}
            disabled={pending}
            onClick={() => {
              void explain();
            }}
          >
            {pending ? '正在生成…' : output === undefined ? '生成解释' : '重新生成'}
          </Button>
        }
      >
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          <Alert
            type="info"
            showIcon
            title={ADVISORY_NOTICE}
            description="AI 结论不能批准变更，也不会创建平台指令；以上方规则结论与测量值为准。"
          />
          {failure !== undefined && (
            <>
              <FailureAlert failure={failure} />
              <Typography.Text type="secondary">
                请求可能已被记录，系统不会自动重试。
              </Typography.Text>
            </>
          )}
          {output !== undefined && (
            <div data-state={output.state}>
              <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
                <Flex gap={8} align="center" wrap>
                  <CodeTag labels={AI_STATE_LABELS} code={output.state} colors={AI_STATE_COLORS} />
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    调用编号：
                    <Typography.Text
                      type="secondary"
                      style={{ fontSize: 12 }}
                      copyable={{ text: output.invocationId }}
                    >
                      {output.invocationId.slice(0, 8)}
                    </Typography.Text>
                    {' · '}输出格式版本 {output.outputSchemaVersion}
                  </Typography.Text>
                </Flex>
                {output.state === 'PARTIAL_OUTPUT_REJECTED' ? (
                  <Alert
                    type="warning"
                    showIcon
                    role="alert"
                    title="部分解释：有些 AI 陈述未通过校验，已通过的陈述仍仅供参考"
                  />
                ) : output.degraded ? (
                  <Alert
                    type="warning"
                    showIcon
                    role="alert"
                    title={`暂无解释：${codeLabel(AI_FAILURE_LABELS, output.failureCode ?? output.state)}`}
                    description="上方的官方测量值与规则结论不受影响。"
                  />
                ) : output.state !== 'SUCCEEDED' ? (
                  <Alert
                    type="info"
                    showIcon
                    role="status"
                    title="调用尚在处理中，未假定任何结果"
                  />
                ) : (
                  <Typography.Text type="secondary">
                    以下陈述均已通过输出校验，但仍是 AI 的陈述。
                  </Typography.Text>
                )}
                <AiClaimGroups output={output} />
              </Space>
            </div>
          )}
        </Space>
      </SectionCard>
    </section>
  );
}

export interface AiClaimLabels {
  readonly FACT: string;
  readonly INFERENCE: string;
  readonly RECOMMENDATION: string;
  readonly UNKNOWN: string;
  readonly confidence: string;
  readonly evidence: string;
  readonly noEvidence: string;
  readonly rejected: string;
}

/** Chinese defaults for the claim groups. */
export const AI_CLAIM_LABELS_ZH: AiClaimLabels = {
  FACT: '事实',
  INFERENCE: '推断',
  RECOMMENDATION: '建议',
  UNKNOWN: '未知',
  confidence: 'AI 置信度',
  evidence: '引用证据',
  noEvidence: '未引用证据',
  rejected: '未通过校验的 AI 陈述',
};

const CLAIM_KINDS = ['FACT', 'INFERENCE', 'RECOMMENDATION', 'UNKNOWN'] as const;

/** Shared claim presentation; callers choose language, never authority or validation. */
export function AiClaimGroups({
  output,
  labels = AI_CLAIM_LABELS_ZH,
}: {
  readonly output: AiExplanation;
  readonly labels?: AiClaimLabels;
}): React.JSX.Element {
  const rejected = output.claims.filter((claim) => !claim.accepted);
  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      {CLAIM_KINDS.map((kind) => {
        const accepted = output.claims.filter((claim) => claim.kind === kind && claim.accepted);
        return accepted.length === 0 ? null : (
          <section aria-label={labels[kind]} key={kind} data-claim-kind={kind}>
            <Flex gap={8} align="center" style={{ marginBottom: 8 }}>
              <Tag color={AI_CLAIM_KIND_COLORS[kind]}>{labels[kind]}</Tag>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {accepted.length} 条 · {ADVISORY_NOTICE}
              </Typography.Text>
            </Flex>
            <List
              size="small"
              bordered
              dataSource={accepted}
              rowKey="claimId"
              renderItem={(claim) => {
                const refs = [...claim.metricValueRefs, ...claim.findingRefs];
                return (
                  <List.Item>
                    <Flex vertical gap={4} style={{ width: '100%' }}>
                      <Typography.Text>{claim.statement}</Typography.Text>
                      <Flex gap={12} wrap>
                        {claim.confidenceLabel !== null && (
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            {labels.confidence}：{claim.confidenceLabel}
                          </Typography.Text>
                        )}
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {labels.evidence}：
                          {refs.length === 0 ? labels.noEvidence : `${String(refs.length)} 条`}
                        </Typography.Text>
                      </Flex>
                      {(refs.length > 0 || Object.keys(claim.payload).length > 0) && (
                        <TechnicalDetails
                          data={{
                            evidence: refs,
                            ...(Object.keys(claim.payload).length > 0
                              ? { payload: claim.payload }
                              : {}),
                          }}
                        />
                      )}
                    </Flex>
                  </List.Item>
                );
              }}
            />
          </section>
        );
      })}
      {rejected.length > 0 && (
        <Collapse
          size="small"
          items={[
            {
              key: 'rejected',
              label: `${labels.rejected}（${String(rejected.length)}）`,
              children: (
                <List
                  size="small"
                  dataSource={rejected}
                  rowKey="claimId"
                  renderItem={(claim) => (
                    <List.Item>
                      <Flex gap={8} wrap align="center">
                        <Tag color={AI_CLAIM_KIND_COLORS[claim.kind]}>{labels[claim.kind]}</Tag>
                        <CodeTag labels={AI_REJECTION_LABELS} code={claim.rejectionCode} />
                        <Typography.Text type="secondary">{claim.statement}</Typography.Text>
                      </Flex>
                    </List.Item>
                  )}
                />
              ),
            },
          ]}
        />
      )}
    </Space>
  );
}
