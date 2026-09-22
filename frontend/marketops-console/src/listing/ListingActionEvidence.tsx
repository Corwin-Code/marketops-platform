import { ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  App,
  AutoComplete,
  Button,
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
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  Allowance,
  AllowanceAxis,
  ConversionMeasurement,
  DescriptionCommand,
  Evaluation,
  EvaluationNodeResult,
  ListingAction,
} from '../api/listingConversion';
import { evaluateNode, fetchEvaluation, fetchListingDetail } from '../api/listingConversion';
import { formatDecimal, formatStoreTime } from '../format';
import { contentPreparationText, detailText, evaluationText } from '../i18n/zh/listingActions';
import { t } from '../i18n/zh/listing';
import { ActionModal, EmptyState, FailureAlert, LoadingState, TechnicalDetails } from '../ui';
import { UUID_PATTERN } from './ListingActionFields';
import {
  Code,
  Codes,
  Details,
  Hint,
  IdText,
  ListingProblem,
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

/** Measurement choices as the evaluation dialog knows them. */
type MeasurementsState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'listed'; readonly measurements: readonly ConversionMeasurement[] }
  | { readonly kind: 'unavailable' };

interface NodeEvaluationValues {
  readonly nodeCode?: string;
  readonly stage?: 'OPERATIONAL' | 'SETTLED';
  readonly measurementId?: string;
  readonly lateFactReference?: string;
}

/** Recording one node's result against the frozen plan, in a dialog. */
function NodeEvaluationModal({
  context,
  action,
  evaluation,
  onEvaluated,
}: {
  readonly context: ConsoleRequest;
  readonly action: ListingAction;
  readonly evaluation: Evaluation;
  readonly onEvaluated: (value: Evaluation) => void;
}): React.JSX.Element {
  const [measurements, setMeasurements] = useState<MeasurementsState>({ kind: 'loading' });
  const epoch = useRef(0);
  const launched = action.launch !== undefined;
  const firstNode = evaluation.formalNodes[0]?.nodeCode;
  return (
    <ActionModal<NodeEvaluationValues>
      trigger={{
        label: evaluationText.record,
        disabled: !launched || firstNode === undefined,
        disabledReason: evaluationText.onlyAfterLaunch,
      }}
      title={evaluationText.recordTitle}
      consequence={evaluationText.recordConsequence}
      initialValues={{
        stage: 'OPERATIONAL',
        ...(firstNode === undefined ? {} : { nodeCode: firstNode }),
      }}
      onOpen={() => {
        const ticket = ++epoch.current;
        setMeasurements({ kind: 'loading' });
        void fetchListingDetail(context, action.platformListingId).then((outcome) => {
          if (ticket !== epoch.current) return;
          setMeasurements(
            outcome.ok
              ? { kind: 'listed', measurements: outcome.value.measurements }
              : { kind: 'unavailable' },
          );
        });
      }}
      onSubmit={async (values) => {
        const measurementId = (values.measurementId ?? '').trim();
        const lateFactReference = (values.lateFactReference ?? '').trim();
        const outcome = await evaluateNode(context, action.id, {
          nodeCode: values.nodeCode ?? '',
          stage: values.stage ?? 'OPERATIONAL',
          ...(measurementId === '' ? {} : { measurementId }),
          ...(lateFactReference === '' ? {} : { lateFactReference }),
        });
        if (!outcome.ok) return outcome.failure;
        onEvaluated(outcome.value);
        return undefined;
      }}
    >
      <Row gutter={16}>
        <Col xs={24} md={12}>
          <Form.Item
            name="nodeCode"
            label={evaluationText.node}
            rules={[{ required: true, message: evaluationText.nodeRequired }]}
          >
            <Select
              options={evaluation.formalNodes.map((node) => ({
                value: node.nodeCode,
                label: node.nodeCode,
              }))}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item name="stage" label={evaluationText.stage} rules={[{ required: true }]}>
            <Select options={codeOptions('evaluationStage', ['OPERATIONAL', 'SETTLED'])} />
          </Form.Item>
        </Col>
      </Row>
      <Form.Item
        name="measurementId"
        label={evaluationText.measurement}
        rules={[{ pattern: UUID_PATTERN, message: evaluationText.measurementInvalid }]}
        {...(measurements.kind === 'unavailable'
          ? { extra: contentPreparationText.listUnavailable }
          : {})}
      >
        <AutoComplete
          allowClear
          placeholder={evaluationText.measurementPlaceholder}
          options={(measurements.kind === 'listed' ? measurements.measurements : []).map(
            (measurement) => ({
              value: measurement.id,
              label: (
                <Space size={6} wrap>
                  <Typography.Text>
                    {formatStoreTime(measurement.windowStart)} –{' '}
                    {formatStoreTime(measurement.windowEnd)}
                  </Typography.Text>
                  <Code family="ratioState" code={measurement.ratioState} />
                  <Typography.Text type="secondary" code style={{ fontSize: 12 }}>
                    {measurement.id.slice(0, 8)}
                  </Typography.Text>
                </Space>
              ),
            }),
          )}
          {...(measurements.kind === 'loading'
            ? { notFoundContent: <LoadingState rows={1} /> }
            : {})}
        />
      </Form.Item>
      <Form.Item name="lateFactReference" label={evaluationText.lateFact}>
        <Input maxLength={512} />
      </Form.Item>
    </ActionModal>
  );
}

/**
 * The evaluation plan and results of one action, read as soon as it is shown.
 * Results are recorded only after launch, against the plan frozen then.
 */
export function ActionEvaluation({
  context,
  action,
}: {
  readonly context: ConsoleRequest;
  readonly action: ListingAction;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [evaluation, setEvaluation] = useState<Evaluation | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const actionId = action.id;

  useEffect(() => {
    let active = true;
    void fetchEvaluation(context, actionId).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setEvaluation(outcome.value);
        setFailure(undefined);
      } else {
        setEvaluation(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, actionId, generation]);

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Flex justify="flex-end" gap={8} wrap>
        {evaluation !== undefined && (
          <NodeEvaluationModal
            context={context}
            action={action}
            evaluation={evaluation}
            onEvaluated={(value) => {
              void message.success(evaluationText.recorded);
              setEvaluation(value);
            }}
          />
        )}
        <Button
          icon={<ReloadOutlined />}
          onClick={() => {
            setGeneration((value) => value + 1);
          }}
        >
          {evaluationText.refresh}
        </Button>
      </Flex>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {evaluation === undefined && failure === undefined && <LoadingState rows={4} />}
      {evaluation !== undefined && <EvaluationTable evaluation={evaluation} />}
    </Space>
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
  gateFailure,
}: {
  readonly command: DescriptionCommand;
  readonly gate: readonly string[] | undefined;
  /** A gate read that failed: the gate is then unknown, never shown as open. */
  readonly gateFailure?: ConsoleFailure | undefined;
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
          {gateFailure !== undefined ? (
            <Alert
              type="warning"
              showIcon
              title={detailText.commandUnknown}
              description={<FailureAlert failure={gateFailure} />}
            />
          ) : gate === undefined ? (
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
