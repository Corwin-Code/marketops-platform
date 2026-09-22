import { Alert, Button, Collapse, Flex, List, Space, Tag, Typography } from 'antd';
import { ReloadOutlined, RobotOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { fetchLatestExplanation, requestExplanation } from '../api/console';
import type { AiExplanation, ConsoleFailure, ConsoleRequest } from '../api/console';
import { actions, codeLabel } from '../i18n';
import {
  AI_CLAIM_KIND_COLORS,
  AI_CONFIDENCE_LABELS,
  AI_FAILURE_LABELS,
  AI_REJECTION_LABELS,
  AI_STATE_COLORS,
  AI_STATE_LABELS,
  aiText,
} from '../i18n/zh/pricing';
import {
  CodeTag,
  DateTime,
  DetailDrawer,
  FailureAlert,
  LoadingState,
  TechnicalDetails,
} from '../ui';

/** The one sentence that must stand beside every model output. */
const ADVISORY_NOTICE = aiText.notice;

/** Where the shown explanation came from. */
type Origin =
  { readonly kind: 'recorded' } | { readonly kind: 'generated'; readonly receivedAt: string };

/**
 * The model's explanation of one subject, in a drawer beside the diagnosis.
 *
 * Opening the drawer only reads what an earlier request recorded; the model is
 * called only when the operator asks for a new explanation. While a request is
 * running the drawer says how long it has waited. Stopping the wait only stops
 * the drawer from waiting: the request cannot be withdrawn and may still be
 * recorded, which is why re-reading the latest result is offered.
 *
 * The advisory notice sits at the top once and cannot be folded away. Nothing
 * here can approve, reject or fill in a decision.
 */
export function AiExplanationDrawer({
  context,
  subjectId,
  storeId,
  open,
  onClose,
  renderReference,
}: {
  readonly context: ConsoleRequest;
  readonly subjectId: string;
  readonly storeId: string;
  readonly open: boolean;
  readonly onClose: () => void;
  /** Names a cited evidence identifier, e.g. as a link opening its evidence. */
  readonly renderReference?: (id: string) => ReactNode;
}): React.JSX.Element {
  const [output, setOutput] = useState<AiExplanation | null | undefined>(undefined);
  const [origin, setOrigin] = useState<Origin>({ kind: 'recorded' });
  const [failure, setFailure] = useState<ConsoleFailure | undefined>();
  const [failedGeneration, setFailedGeneration] = useState(false);
  const [loadingLatest, setLoadingLatest] = useState(false);
  const [pendingSince, setPendingSince] = useState<number | undefined>(undefined);
  const [elapsed, setElapsed] = useState(0);
  const [stopped, setStopped] = useState(false);
  const generation = useRef(0);
  const pendingRef = useRef(false);

  useEffect(
    () => () => {
      generation.current++;
    },
    [context, subjectId, storeId],
  );

  useEffect(() => {
    if (pendingSince === undefined) {
      return;
    }
    const timer = window.setInterval(() => {
      setElapsed(Math.floor((Date.now() - pendingSince) / 1000));
    }, 1000);
    return () => {
      window.clearInterval(timer);
    };
  }, [pendingSince]);

  const readLatest = async (): Promise<void> => {
    const requestGeneration = ++generation.current;
    setLoadingLatest(true);
    setFailure(undefined);
    setFailedGeneration(false);
    setStopped(false);
    const result = await fetchLatestExplanation(context, subjectId, storeId);
    if (requestGeneration !== generation.current) return;
    setLoadingLatest(false);
    if (!result.ok) {
      setFailure(result.failure);
    } else if (result.value !== null && result.value.subjectId !== subjectId) {
      setFailure({ kind: 'malformed', detail: 'subject mismatch' });
    } else {
      setOutput(result.value);
      setOrigin({ kind: 'recorded' });
    }
  };
  const readLatestRef = useRef(readLatest);
  readLatestRef.current = readLatest;

  // Each opening re-reads the recorded result; it never calls the model.
  useEffect(() => {
    if (open && !pendingRef.current) {
      void readLatestRef.current();
    }
  }, [open, context, subjectId, storeId]);

  const explain = async (): Promise<void> => {
    const requestGeneration = ++generation.current;
    pendingRef.current = true;
    setPendingSince(Date.now());
    setElapsed(0);
    setStopped(false);
    setFailure(undefined);
    setFailedGeneration(false);
    const result = await requestExplanation(context, subjectId, storeId);
    if (requestGeneration !== generation.current) return;
    pendingRef.current = false;
    setPendingSince(undefined);
    if (result.ok && result.value.subjectId === subjectId) {
      setOutput(result.value);
      setOrigin({ kind: 'generated', receivedAt: new Date().toISOString() });
    } else {
      setFailure(result.ok ? { kind: 'malformed', detail: 'subject mismatch' } : result.failure);
      setFailedGeneration(true);
    }
  };

  const stopWaiting = (): void => {
    // The answer of the abandoned request is ignored when it arrives.
    generation.current++;
    pendingRef.current = false;
    setPendingSince(undefined);
    setStopped(true);
  };

  const pending = pendingSince !== undefined;

  return (
    <DetailDrawer
      open={open}
      onClose={onClose}
      mask={false}
      size={560}
      title={
        <Space size="small">
          <RobotOutlined />
          <span>{aiText.title}</span>
        </Space>
      }
      extra={
        <Space size="small" wrap>
          <Button
            icon={<ReloadOutlined />}
            disabled={pending || loadingLatest}
            onClick={() => {
              void readLatest();
            }}
          >
            {aiText.latest}
          </Button>
          <Button
            type="primary"
            ghost
            loading={pending}
            disabled={pending || loadingLatest}
            onClick={() => {
              void explain();
            }}
          >
            {aiText.generate}
          </Button>
        </Space>
      }
    >
      <section aria-label="AI 解释">
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          <Alert type="info" showIcon title={ADVISORY_NOTICE} description={aiText.noticeDetail} />
          {pending && (
            <div data-state="waiting">
              <Alert
                type="info"
                showIcon
                role="status"
                title={aiText.waiting(elapsed)}
                action={
                  <Button size="small" onClick={stopWaiting}>
                    {actions.stopWaiting}
                  </Button>
                }
              />
            </div>
          )}
          {stopped && <Alert type="warning" showIcon role="status" title={aiText.stopped} />}
          {failure !== undefined && (
            <>
              <FailureAlert failure={failure} />
              {failedGeneration && (
                <Typography.Text type="secondary">{aiText.failedNote}</Typography.Text>
              )}
            </>
          )}
          {loadingLatest && <LoadingState rows={3} />}
          {!loadingLatest && !pending && output === null && (
            <Typography.Text type="secondary" data-state="none">
              {aiText.none}
            </Typography.Text>
          )}
          {!loadingLatest && output !== null && output !== undefined && (
            <ExplanationBody
              output={output}
              origin={origin}
              {...(renderReference === undefined ? {} : { renderReference })}
            />
          )}
        </Space>
      </section>
    </DetailDrawer>
  );
}

/** One explanation: its state, where it came from, and its claims. */
function ExplanationBody({
  output,
  origin,
  renderReference,
}: {
  readonly output: AiExplanation;
  readonly origin: Origin;
  readonly renderReference?: (id: string) => ReactNode;
}): React.JSX.Element {
  return (
    <div data-state={output.state}>
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <Flex gap={8} align="center" wrap>
          <CodeTag labels={AI_STATE_LABELS} code={output.state} colors={AI_STATE_COLORS} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {origin.kind === 'generated' ? (
              <>
                {aiText.justGenerated} <DateTime value={origin.receivedAt} />
              </>
            ) : (output.completedAt ?? output.startedAt) === undefined ? (
              aiText.lastRecorded
            ) : (
              <>
                {aiText.lastRecorded}{' '}
                <DateTime value={output.completedAt ?? output.startedAt ?? ''} />
              </>
            )}
          </Typography.Text>
        </Flex>
        {output.state === 'PARTIAL_OUTPUT_REJECTED' ? (
          <Alert type="warning" showIcon role="alert" title={aiText.partial} />
        ) : output.degraded ? (
          <Alert
            type="warning"
            showIcon
            role="alert"
            title={`${aiText.unavailable}：${codeLabel(AI_FAILURE_LABELS, output.failureCode ?? output.state)}`}
            description={aiText.unaffected}
          />
        ) : output.state !== 'SUCCEEDED' ? (
          <Alert type="info" showIcon role="status" title={aiText.pendingState} />
        ) : (
          <Typography.Text type="secondary">{aiText.validated}</Typography.Text>
        )}
        <AiClaimGroups
          output={output}
          groupNotice={false}
          {...(renderReference === undefined ? {} : { renderReference })}
        />
        <TechnicalDetails
          data={{
            [aiText.invocation]: output.invocationId,
            [aiText.schemaVersion]: output.outputSchemaVersion,
          }}
        />
      </Space>
    </div>
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
  groupNotice = true,
  renderReference,
}: {
  readonly output: AiExplanation;
  readonly labels?: AiClaimLabels;
  /**
   * Repeat the advisory notice beside each group. Off where the surrounding
   * surface already shows it once and cannot hide it.
   */
  readonly groupNotice?: boolean;
  /** Names a cited identifier; without it references stay in technical details. */
  readonly renderReference?: (id: string) => ReactNode;
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
                {accepted.length} 条{groupNotice ? ` · ${ADVISORY_NOTICE}` : ''}
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
                            {labels.confidence}：
                            {codeLabel(AI_CONFIDENCE_LABELS, claim.confidenceLabel)}
                          </Typography.Text>
                        )}
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {labels.evidence}：
                          {refs.length === 0 ? labels.noEvidence : `${String(refs.length)} 条`}
                        </Typography.Text>
                      </Flex>
                      {renderReference !== undefined && refs.length > 0 && (
                        <Flex gap={8} wrap align="center">
                          {refs.map((id) => (
                            <span key={id}>{renderReference(id)}</span>
                          ))}
                        </Flex>
                      )}
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
