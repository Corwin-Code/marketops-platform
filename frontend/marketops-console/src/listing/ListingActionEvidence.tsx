import {
  Alert,
  Button,
  Card,
  Col,
  Flex,
  Form,
  Input,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  Allowance,
  AllowanceAxis,
  DescriptionCommand,
  Evaluation,
  EvaluationNodeResult,
} from '../api/listingConversion';
import { evaluateNode } from '../api/listingConversion';
import { formatDecimal } from '../format';
import { t } from '../i18n/zh/listing';
import { EmptyState, LoadingState, TechnicalDetails } from '../ui';
import {
  Code,
  Codes,
  Details,
  Hint,
  IdText,
  SubTitle,
  When,
  codeOptions,
  codeText,
} from './ListingCommon';

/** A decimal figure exactly as sent, digits aligned. */
function Figure({ value }: { readonly value: string | undefined }): React.JSX.Element {
  return (
    <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>
      {value === undefined ? '—' : formatDecimal(value)}
    </Typography.Text>
  );
}

export function AllowanceTable({
  allowance,
}: {
  readonly allowance: Allowance;
}): React.JSX.Element {
  const columns: TableColumnsType<AllowanceAxis> = [
    {
      key: 'axis',
      title: t('axis'),
      render: (_, axis) => <Code family="allowanceAxis" code={axis.axisCode} />,
    },
    {
      key: 'scope',
      title: t('allowanceScope'),
      render: (_, axis) => codeText('allowanceScope', axis.scopeKind),
    },
    {
      key: 'limit',
      title: t('limit'),
      align: 'right',
      render: (_, axis) => <Figure value={axis.limitValue} />,
    },
    {
      key: 'reserve',
      title: t('reserve'),
      align: 'right',
      render: (_, axis) => <Figure value={axis.reserveValue} />,
    },
    {
      key: 'occupied',
      title: t('occupied'),
      align: 'right',
      render: (_, axis) => <Figure value={axis.occupiedValue} />,
    },
    {
      key: 'headroom',
      title: t('headroom'),
      align: 'right',
      render: (_, axis) => <Figure value={axis.headroom} />,
    },
    {
      key: 'sufficient',
      title: t('sufficiency'),
      render: (_, axis) => (
        <Tag color={axis.sufficient ? 'success' : 'error'}>
          {axis.sufficient ? t('sufficient') : t('insufficient')}
        </Tag>
      ),
    },
  ];
  return (
    <Space orientation="vertical" size="small" style={{ width: '100%' }}>
      {!allowance.resolved && (
        <Alert role="status" type="warning" showIcon title={t('allowanceUnresolved')} />
      )}
      {allowance.gaps.length > 0 && (
        <ul style={{ margin: 0, paddingInlineStart: 18 }}>
          {allowance.gaps.map((gap) => {
            const parts = gap.split(':');
            const reason = (parts.length > 1 ? parts[1] : parts[0]) ?? '';
            return (
              <li key={gap}>
                {parts.length > 1 && (
                  <>
                    <Code family="allowanceAxis" code={parts[0] ?? ''} />{' '}
                  </>
                )}
                <Code family="allowanceGap" code={reason} />
              </li>
            );
          })}
        </ul>
      )}
      <div aria-label={t('allowancePreview')} data-resolved={String(allowance.resolved)}>
        <Table<AllowanceAxis>
          size="middle"
          rowKey={(axis) => `${axis.axisCode}:${axis.scopeKind}`}
          columns={columns}
          dataSource={[...allowance.axes]}
          pagination={false}
          onRow={(axis) =>
            ({ 'data-sufficient': String(axis.sufficient) }) as React.HTMLAttributes<HTMLElement>
          }
        />
      </div>
    </Space>
  );
}

export function NodeEvaluationForm({
  context,
  actionId,
  evaluation,
  onEvaluated,
  onFailure,
}: {
  readonly context: ConsoleRequest;
  readonly actionId: string;
  readonly evaluation: Evaluation;
  readonly onEvaluated: (value: Evaluation) => void;
  readonly onFailure: (failure: ConsoleFailure | undefined) => void;
}): React.JSX.Element {
  const [nodeCode, setNodeCode] = useState(evaluation.formalNodes[0]?.nodeCode ?? '');
  const [stage, setStage] = useState<'OPERATIONAL' | 'SETTLED'>('OPERATIONAL');
  const [measurementId, setMeasurementId] = useState('');
  const [lateFactReference, setLateFactReference] = useState('');
  const [pending, setPending] = useState(false);
  return (
    <Card size="small" type="inner" title={t('evaluationRecord')}>
      <Form
        layout="vertical"
        aria-label={t('evaluationRecord')}
        onFinish={() => {
          setPending(true);
          void evaluateNode(context, actionId, {
            nodeCode,
            stage,
            ...(measurementId.trim() === '' ? {} : { measurementId: measurementId.trim() }),
            ...(lateFactReference.trim() === ''
              ? {}
              : { lateFactReference: lateFactReference.trim() }),
          }).then((outcome) => {
            setPending(false);
            if (outcome.ok) {
              onEvaluated(outcome.value);
              onFailure(undefined);
            } else onFailure(outcome.failure);
          });
        }}
      >
        <Row gutter={16}>
          <Col xs={24} md={6}>
            <Form.Item label={t('nodes')} required>
              <Select<string | undefined>
                value={nodeCode === '' ? undefined : nodeCode}
                onChange={(next) => {
                  setNodeCode(next ?? '');
                }}
                options={evaluation.formalNodes.map((node) => ({
                  value: node.nodeCode,
                  label: node.nodeCode,
                }))}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={6}>
            <Form.Item label={t('evaluationStage')}>
              <Select<'OPERATIONAL' | 'SETTLED'>
                value={stage}
                onChange={setStage}
                options={codeOptions('evaluationStage', ['OPERATIONAL', 'SETTLED']).map(
                  (option) => ({ ...option, value: option.value as 'OPERATIONAL' | 'SETTLED' }),
                )}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={6}>
            <Form.Item label={t('measurementId')}>
              <Input
                value={measurementId}
                onChange={(event) => {
                  setMeasurementId(event.target.value);
                }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={6}>
            <Form.Item label={t('lateFactReference')}>
              <Input
                value={lateFactReference}
                onChange={(event) => {
                  setLateFactReference(event.target.value);
                }}
              />
            </Form.Item>
          </Col>
        </Row>
        <Button type="primary" htmlType="submit" loading={pending} disabled={nodeCode === ''}>
          {t('evaluationRecord')}
        </Button>
      </Form>
    </Card>
  );
}

export function EvaluationTable({
  evaluation,
}: {
  readonly evaluation: Evaluation;
}): React.JSX.Element {
  const columns: TableColumnsType<EvaluationNodeResult> = [
    {
      key: 'node',
      title: t('nodes'),
      render: (_, result) => (
        <Space orientation="vertical" size={2}>
          <Space size={4} wrap>
            <span>{result.nodeCode}</span>
            <Code family="evaluationStage" code={result.stage} />
            <Typography.Text type="secondary">
              {t('revisionShort')} {result.revisionNo}
            </Typography.Text>
          </Space>
          <Code family="nodeVerdict" code={result.verdict} />
        </Space>
      ),
    },
    {
      key: 'ratio',
      title: t('ratioBoundThreshold'),
      render: (_, result) => (
        <Space orientation="vertical" size={2}>
          <span>
            <Figure value={result.primaryRatio} /> / <Figure value={result.conservativeBound} /> /{' '}
            <Figure value={result.acceptedThreshold} />
          </span>
          {result.fixedTrafficObservation !== undefined && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('observedComparison')}：
              <Figure value={result.fixedTrafficObservation.referenceStandardized} /> /{' '}
              <Figure value={result.fixedTrafficObservation.targetStandardized} /> /{' '}
              <Figure value={result.fixedTrafficObservation.observedDifference} />
              <br />
              {t('observationOnly')}
            </Typography.Text>
          )}
          {(result.qualificationGaps?.length ?? 0) > 0 && (
            <Space size={4} wrap>
              <Typography.Text type="secondary">{t('qualification')}</Typography.Text>
              <Codes family="qualificationReason" codes={result.qualificationGaps ?? []} />
            </Space>
          )}
        </Space>
      ),
    },
    {
      key: 'protection',
      title: t('protection'),
      render: (_, result) => (
        <Space orientation="vertical" size={2}>
          <Code family="protectionVerdict" code={result.protectionVerdict} />
          <Flex wrap gap={4}>
            {Object.entries(result.protectionVector).map(([key, value]) => (
              <Space key={key} size={2}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {codeText('protection', key)}
                </Typography.Text>
                <Code family="protectionVerdict" code={value} />
              </Space>
            ))}
          </Flex>
        </Space>
      ),
    },
    {
      key: 'times',
      title: t('measurementComputedAt'),
      render: (_, result) => (
        <Space orientation="vertical" size={0}>
          <When value={result.measurementComputedAt} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('acquisitionTime')} <When value={result.measurementAcquiredAt} />
          </Typography.Text>
        </Space>
      ),
    },
  ];
  return (
    <div data-plan={evaluation.planId}>
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <Details
          items={[
            {
              key: 'frozen',
              label: t('evaluationFrozenAt'),
              children: <When value={evaluation.frozenAt} />,
            },
            {
              key: 'boundary',
              label: t('evaluationBoundary'),
              children: <When value={evaluation.latestBoundary} />,
            },
            {
              key: 'digest',
              label: t('evaluationPlanDigest'),
              children: <IdText value={evaluation.planDigest} />,
            },
          ]}
        />
        <div>
          <SubTitle>{t('formalNodes')}</SubTitle>
          <Table
            size="middle"
            rowKey="nodeCode"
            pagination={false}
            dataSource={[...evaluation.formalNodes]}
            columns={[
              { key: 'node', title: t('nodes'), dataIndex: 'nodeCode' },
              {
                key: 'maturity',
                title: t('maturityDays'),
                align: 'right',
                render: (_, node) => `${String(node.maturityDays)} 天`,
              },
              { key: 'method', title: t('method'), dataIndex: 'method' },
              {
                key: 'threshold',
                title: t('threshold'),
                align: 'right',
                render: (_, node) => <Figure value={node.threshold} />,
              },
            ]}
          />
        </div>
        <div>
          <SubTitle>{t('evaluationResults')}</SubTitle>
          {evaluation.results.length === 0 ? (
            <EmptyState description={t('noEvaluationResults')} />
          ) : (
            <Table<EvaluationNodeResult>
              size="middle"
              rowKey="id"
              columns={columns}
              dataSource={[...evaluation.results]}
              pagination={false}
              scroll={{ x: 'max-content' }}
              onRow={(result) =>
                ({
                  id: `evaluation-result-${result.id}`,
                  'data-verdict': result.verdict,
                }) as React.HTMLAttributes<HTMLElement>
              }
            />
          )}
        </div>
        {(evaluation.revisions?.length ?? 0) > 0 && (
          <section aria-label={t('evaluationRevisions')}>
            <SubTitle>{t('evaluationRevisions')}</SubTitle>
            <Timeline
              items={(evaluation.revisions ?? []).map((revision) => ({
                key: revision.id,
                content: (
                  <Space orientation="vertical" size={2}>
                    <Space size={6} wrap>
                      <Code family="revisionReason" code={revision.revisionReason} />
                      <When value={revision.recordedAt} />
                    </Space>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {t('evaluationRevisionLink')}：
                      <a href={`#evaluation-result-${revision.originalNodeResultId}`}>
                        {revision.originalNodeResultId.slice(0, 8)}
                      </a>
                      {' → '}
                      <a href={`#evaluation-result-${revision.revisedNodeResultId}`}>
                        {revision.revisedNodeResultId.slice(0, 8)}
                      </a>
                    </Typography.Text>
                    <IdText
                      label={t('lateFactReferenceShort')}
                      value={revision.lateFactReference}
                    />
                  </Space>
                ),
              }))}
            />
          </section>
        )}
      </Space>
    </div>
  );
}

export function CommandTimeline({
  command,
  gate,
}: {
  readonly command: DescriptionCommand;
  readonly gate: readonly string[] | undefined;
}): React.JSX.Element {
  return (
    <section
      aria-label={t('descriptionCommand')}
      data-command={command.id}
      data-command-state={command.state}
    >
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <Details
          items={[
            {
              key: 'state',
              label: t('state'),
              children: <Code family="commandState" code={command.state} />,
            },
            {
              key: 'attempts',
              label: t('attemptCount'),
              children: command.attemptNo,
            },
            {
              key: 'budget',
              label: t('retryBudget'),
              children: command.retryBudgetRemaining,
            },
            {
              key: 'restore',
              label: t('priorTextCaptured'),
              children: command.priorTextCaptured ? (
                <Tag color="success">{t('yes')}</Tag>
              ) : (
                <Code family="errorCode" code="RESTORE_UNSUPPORTED" />
              ),
            },
            {
              key: 'lease',
              label: t('approvalExpiresAt'),
              children: <When value={command.approvalExpiresAt} />,
            },
            ...(command.failureCode === undefined
              ? []
              : [
                  {
                    key: 'failure',
                    label: t('failureCode'),
                    children: <Code family="errorCode" code={command.failureCode} />,
                  },
                ]),
          ]}
        />
        <div>
          <SubTitle>{t('gate')}</SubTitle>
          {gate === undefined ? (
            <LoadingState rows={1} />
          ) : gate.length === 0 ? (
            <Alert type="success" showIcon title={t('gateOpen')} />
          ) : (
            <Alert
              type="error"
              showIcon
              title={t('gateBlocked')}
              description={<Codes family="gateReason" codes={gate} />}
            />
          )}
        </div>
        <div>
          <SubTitle>{t('executionObservation')}</SubTitle>
          <Hint>{t('executionBoundary')}</Hint>
          {command.executionReceipts.length === 0 ? (
            <EmptyState description={t('noExecutionObservation')} />
          ) : (
            <Timeline
              items={command.executionReceipts.map((receipt) => ({
                key: receipt.id,
                color: receipt.executionState === 'MANAGEMENT_VERIFIED' ? 'green' : 'gold',
                content: (
                  <div data-execution-state={receipt.executionState}>
                    <Space orientation="vertical" size={2}>
                      <Space size={6} wrap>
                        <Code family="descriptionExecutionState" code={receipt.executionState} />
                        <When value={receipt.recordedAt} />
                        <Tag color={receipt.taskEventId === undefined ? 'warning' : 'success'}>
                          {receipt.taskEventId === undefined
                            ? t('taskDeliveryPending')
                            : t('taskDeliveryRecorded')}
                        </Tag>
                      </Space>
                      {receipt.gaps.length > 0 && (
                        <Codes family="descriptionExecutionGap" codes={receipt.gaps} />
                      )}
                    </Space>
                  </div>
                ),
              }))}
            />
          )}
        </div>
        <Row gutter={16}>
          <Col xs={24} lg={12}>
            <SubTitle>{t('attempts')}</SubTitle>
            {command.attempts.length === 0 ? (
              <EmptyState description={t('noAttempts')} />
            ) : (
              <Timeline
                items={command.attempts.map((attempt) => ({
                  key: attempt.id,
                  content: (
                    <Space size={6} wrap>
                      <Typography.Text>#{attempt.attemptNo}</Typography.Text>
                      <Typography.Text type="secondary">
                        {codeText('attemptPurpose', attempt.purpose)}
                      </Typography.Text>
                      <Code family="attemptOutcome" code={attempt.outcomeClass} />
                      {attempt.errorCode !== undefined && (
                        <Code family="errorCode" code={attempt.errorCode} />
                      )}
                    </Space>
                  ),
                }))}
              />
            )}
          </Col>
          <Col xs={24} lg={12}>
            <SubTitle>{t('readbacks')}</SubTitle>
            {command.readbacks.length === 0 ? (
              <EmptyState description={t('noReadbacks')} />
            ) : (
              <Timeline
                items={command.readbacks.map((readback) => ({
                  key: readback.id,
                  content: (
                    <Space size={6} wrap>
                      <Code family="readbackMatch" code={readback.matchState} />
                      <When value={readback.observedAt} />
                    </Space>
                  ),
                }))}
              />
            )}
          </Col>
        </Row>
        <TechnicalDetails>
          <Space orientation="vertical" size={2}>
            <IdText label={t('commandId')} value={command.id} />
            <IdText label={t('equivalenceRule')} value={command.equivalenceRule} />
          </Space>
        </TechnicalDetails>
      </Space>
    </section>
  );
}
