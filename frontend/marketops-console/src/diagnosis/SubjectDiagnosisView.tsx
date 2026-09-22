import { Alert, Button, Col, Flex, Row, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { ArrowLeftOutlined, FileSearchOutlined, StopOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { fetchDiagnosis } from '../api/console';
import type {
  ConsoleFailure,
  ConsoleRequest,
  DiagnosisFinding,
  MetricValue,
  SubjectDiagnosis,
  Recommendation,
} from '../api/console';
import { formatDecimal, isDecimal } from '../format';
import { actions, codeLabel } from '../i18n';
import {
  DIAGNOSIS_CODE_LABELS,
  EXPORT_WINDOW_LABELS,
  FINDING_DETAIL_LABELS,
  FINDING_OUTCOME_COLORS,
  FINDING_OUTCOME_LABELS,
  METRIC_LABELS,
  RULE_LABELS,
  SEVERITY_COLORS,
  SEVERITY_LABELS,
} from '../i18n/zh/pricing';
import { CodeTag, EmptyState, FailureAlert, LoadingState, SectionCard } from '../ui';
import { MetricEvidencePanel } from './MetricEvidencePanel';
import { SubjectRecommendations } from '../workflow/SubjectRecommendations';
import { ValueCell } from '../state/ValueCell';
import { AiExplanationPanel } from './AiExplanationPanel';

/** What the diagnosis view needs in order to load itself. */
export interface SubjectDiagnosisProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The listing variant being examined. */
  readonly subjectId: string;
  /** Store it sits on. */
  readonly storeId: string;
  /** Called when the operator goes back to the work list. */
  readonly onBack: () => void;
  readonly onReview?: (recommendation: Recommendation) => void;
}

const CODE_LIST = /^[A-Z][A-Z0-9_]*(?:,[A-Z][A-Z0-9_]*)*$/;

/** One recorded detail value: codes become tags, decimals are grouped, text stays as sent. */
function DetailValue({ value }: { readonly value: string }): React.JSX.Element {
  if (CODE_LIST.test(value)) {
    return (
      <Space size={[4, 4]} wrap>
        {value.split(',').map((code) => (
          <CodeTag key={code} labels={DIAGNOSIS_CODE_LABELS} code={code} />
        ))}
      </Space>
    );
  }
  if (value === '') {
    return <Typography.Text type="secondary">—</Typography.Text>;
  }
  return (
    <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>
      {isDecimal(value) ? formatDecimal(value) : value}
    </Typography.Text>
  );
}

/**
 * Everything known about one listing variant, and what the rules concluded.
 *
 * Metrics and findings are shown together because a number without the rule
 * that read it invites an operator to draw their own conclusion, and a finding
 * without its inputs cannot be checked. The evidence identifiers are rendered
 * so a person can ask for the source record behind any figure.
 *
 * A rule that could not answer is shown as prominently as one that triggered.
 * Silence about a rule that declined is how a data problem gets mistaken for a
 * clean bill of health.
 */
export function SubjectDiagnosisView({
  context,
  subjectId,
  storeId,
  onBack,
  onReview,
}: SubjectDiagnosisProps): React.JSX.Element {
  const [diagnosis, setDiagnosis] = useState<SubjectDiagnosis | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [evidenceMetric, setEvidenceMetric] = useState<string>();

  useEffect(() => {
    let active = true;
    void fetchDiagnosis(context, subjectId, storeId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setDiagnosis(outcome.value);
        setFailure(undefined);
      } else {
        setDiagnosis(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, subjectId, storeId]);

  const metricsById = new Map<string, MetricValue>(
    Object.values(diagnosis?.metrics ?? {}).map((metric) => [metric.metricValueId, metric]),
  );
  const evidenceMetricCode =
    evidenceMetric === undefined ? undefined : metricsById.get(evidenceMetric)?.metricCode;

  const header = (
    <SectionCard
      title={
        <Space size="small" wrap>
          <span>SKU 诊断</span>
          {diagnosis !== undefined && (
            <Tag>{codeLabel(EXPORT_WINDOW_LABELS, diagnosis.window)}</Tag>
          )}
        </Space>
      }
      extra={
        <Button icon={<ArrowLeftOutlined />} onClick={onBack} aria-label="返回今日工作">
          {actions.back}今日工作
        </Button>
      }
    >
      <Flex vertical gap={4}>
        <Typography.Text type="secondary">
          商品编号：
          <Typography.Text type="secondary" copyable code>
            {subjectId}
          </Typography.Text>
        </Typography.Text>
        <Typography.Text type="secondary">
          下方为平台确定性计算的指标与规则结论；存疑的数值会明确标注，缺失显示为“—”而不是 0。
        </Typography.Text>
      </Flex>
    </SectionCard>
  );

  const findingColumns: TableColumnsType<DiagnosisFinding> = [
    {
      title: '规则',
      key: 'rule',
      render: (_, finding) => (
        <Typography.Text strong>{codeLabel(RULE_LABELS, finding.ruleCode)}</Typography.Text>
      ),
    },
    {
      title: '结论',
      key: 'outcome',
      render: (_, finding) => (
        <Space size={[4, 4]} wrap>
          <CodeTag
            labels={FINDING_OUTCOME_LABELS}
            code={finding.outcome}
            colors={FINDING_OUTCOME_COLORS}
          />
          {finding.outcome === 'TRIGGERED' && (
            <CodeTag labels={SEVERITY_LABELS} code={finding.severity} colors={SEVERITY_COLORS} />
          )}
          {finding.outcome === 'DECLINED' && finding.declineReason !== null && (
            <CodeTag labels={DIAGNOSIS_CODE_LABELS} code={finding.declineReason} />
          )}
          {finding.blocksExecution && (
            <Tag color="error" icon={<StopOutlined />} data-testid="blocks-write">
              阻断平台写入
            </Tag>
          )}
        </Space>
      ),
    },
    {
      title: '依据明细',
      key: 'detail',
      render: (_, finding) =>
        Object.keys(finding.detail).length === 0 ? (
          <Typography.Text type="secondary">—</Typography.Text>
        ) : (
          <Flex vertical gap={2}>
            {Object.entries(finding.detail).map(([name, value]) => (
              <Flex key={name} gap={4} wrap align="center">
                <Typography.Text type="secondary">
                  {codeLabel(FINDING_DETAIL_LABELS, name)}：
                </Typography.Text>
                <DetailValue value={value} />
              </Flex>
            ))}
          </Flex>
        ),
    },
    {
      title: '证据',
      key: 'evidence',
      render: (_, finding) =>
        finding.metricValueIds.length === 0 ? (
          <Typography.Text type="secondary">未记录</Typography.Text>
        ) : (
          <Flex vertical gap={0} className="evidence-refs">
            {finding.metricValueIds.map((id) => {
              const metric = metricsById.get(id);
              return (
                <Button
                  key={id}
                  type="link"
                  size="small"
                  style={{ padding: 0, height: 'auto', textAlign: 'left' }}
                  aria-label={`查看证据 ${metric === undefined ? id : codeLabel(METRIC_LABELS, metric.metricCode)}`}
                  onClick={() => {
                    setEvidenceMetric(id);
                  }}
                >
                  {metric === undefined
                    ? `指标 ${id.slice(0, 8)}`
                    : codeLabel(METRIC_LABELS, metric.metricCode)}
                </Button>
              );
            })}
          </Flex>
        ),
    },
  ];

  let content: ReactNode;
  if (failure !== undefined) {
    content = <FailureAlert failure={failure} />;
  } else if (diagnosis === undefined) {
    content = (
      <SectionCard>
        <LoadingState rows={6} />
      </SectionCard>
    );
  } else {
    const metricEntries = Object.entries(diagnosis.metrics);
    const blocking = diagnosis.findings.filter((finding) => finding.blocksExecution);
    const declined = diagnosis.findings.filter((finding) => finding.outcome === 'DECLINED');
    content = (
      <>
        <section aria-label="平台测量值" data-state="metrics">
          <SectionCard title="平台测量值">
            {metricEntries.length === 0 ? (
              <EmptyState description="暂无指标" />
            ) : (
              <Row gutter={[12, 12]} className="value-grid">
                {metricEntries.map(([code, metric]) => (
                  <Col key={code} xs={24} sm={12} md={8} xl={6}>
                    <Flex vertical gap={4}>
                      <ValueCell
                        label={codeLabel(METRIC_LABELS, code)}
                        value={metric.numericValue}
                        currencyCode={metric.currencyCode}
                        valueState={metric.valueState}
                        confidenceState={metric.confidenceState}
                        freshnessSeconds={metric.freshnessSeconds}
                      />
                      <Button
                        type="link"
                        size="small"
                        icon={<FileSearchOutlined />}
                        style={{ alignSelf: 'flex-start', padding: 0 }}
                        aria-label={`查看${codeLabel(METRIC_LABELS, code)}的证据`}
                        onClick={() => {
                          setEvidenceMetric(metric.metricValueId);
                        }}
                      >
                        查看证据
                      </Button>
                    </Flex>
                  </Col>
                ))}
              </Row>
            )}
          </SectionCard>
        </section>
        {evidenceMetric !== undefined && (
          <MetricEvidencePanel
            key={evidenceMetric}
            context={context}
            subjectId={subjectId}
            storeId={storeId}
            metricValueId={evidenceMetric}
            {...(evidenceMetricCode === undefined
              ? {}
              : { metricLabel: codeLabel(METRIC_LABELS, evidenceMetricCode) })}
            onClose={() => {
              setEvidenceMetric(undefined);
            }}
          />
        )}

        <section aria-label="规则结论" data-state="findings">
          <SectionCard title="规则结论">
            <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
              {blocking.length > 0 && (
                <Alert
                  type="error"
                  showIcon
                  title={`${String(blocking.length)} 条规则结论阻断平台写入，当前不能调价`}
                />
              )}
              {declined.length > 0 && (
                <Alert
                  type="warning"
                  showIcon
                  title={`${String(declined.length)} 条规则因数据不足等原因无法判断，不代表没有问题`}
                />
              )}
              {diagnosis.findings.length === 0 ? (
                <EmptyState description="暂无规则结论" />
              ) : (
                <Table<DiagnosisFinding>
                  size="middle"
                  rowKey="findingId"
                  columns={findingColumns}
                  dataSource={[...diagnosis.findings]}
                  pagination={false}
                  scroll={{ x: 'max-content' }}
                  onRow={(finding) =>
                    ({
                      'data-rule': finding.ruleCode,
                      'data-outcome': finding.outcome,
                      'data-blocks-write': finding.blocksExecution,
                    }) as React.HTMLAttributes<HTMLElement>
                  }
                />
              )}
            </Space>
          </SectionCard>
        </section>
        <AiExplanationPanel context={context} subjectId={subjectId} storeId={storeId} />
        {onReview !== undefined && (
          <SubjectRecommendations
            context={context}
            storeId={storeId}
            subjectId={subjectId}
            onReview={onReview}
          />
        )}
      </>
    );
  }

  return (
    <section aria-label="SKU 诊断" data-subject={subjectId}>
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        {header}
        {content}
      </Space>
    </section>
  );
}
