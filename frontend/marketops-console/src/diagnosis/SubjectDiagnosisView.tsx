import { Alert, Button, Col, Flex, Row, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  ArrowLeftOutlined,
  FileSearchOutlined,
  RobotOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { fetchDiagnosis } from '../api/console';
import type {
  ConsoleFailure,
  ConsoleRequest,
  DiagnosisFinding,
  MetricValue,
  SubjectDiagnosis,
} from '../api/console';
import { formatDecimal, isDecimal } from '../format';
import { codeLabel } from '../i18n';
import {
  DIAGNOSIS_CODE_LABELS,
  diagnosisText as text,
  EXPORT_WINDOW_LABELS,
  FINDING_DETAIL_LABELS,
  FINDING_OUTCOME_COLORS,
  FINDING_OUTCOME_LABELS,
  METRIC_GROUP_LABELS,
  METRIC_LABELS,
  RULE_LABELS,
  SEVERITY_COLORS,
  SEVERITY_LABELS,
} from '../i18n/zh/pricing';
import { pages } from '../i18n/zh/shell';
import { Page } from '../pages/Page';
import {
  CodeTag,
  EmptyState,
  FailureAlert,
  InfoTip,
  LoadingState,
  SectionCard,
  SectionCollapse,
  subjectSubtitle,
  subjectTitle,
  useSearchParam,
} from '../ui';
import type { SectionCollapseItem, SectionFlag } from '../ui';
import { ValueCell } from '../state/ValueCell';
import { RecommendationReviewDrawer } from '../workflow/RecommendationReview';
import { SubjectRecommendations } from '../workflow/SubjectRecommendations';
import { AiExplanationDrawer } from './AiExplanationPanel';
import { MetricEvidenceDrawer } from './MetricEvidencePanel';

/** What the diagnosis page needs in order to load itself. */
export interface SubjectDiagnosisProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The listing variant being examined. */
  readonly subjectId: string;
  /** Store it sits on. */
  readonly storeId: string;
  /** Called when the operator leaves the page. */
  readonly onBack: () => void;
  /** Go to the command that carries a decision. */
  readonly onOpenCommand: (commandId: string) => void;
}

/** The search parameter holding the proposal under review. */
export const REVIEW_PARAM = 'review';

type MetricGroup = keyof typeof METRIC_GROUP_LABELS;

const GROUP_ORDER: readonly MetricGroup[] = [
  'profit',
  'funnel',
  'sales',
  'returns',
  'inventory',
  'advertising',
  'cost',
  'other',
];

/** Known metric codes by domain; new codes fall back on their name. */
const METRIC_GROUPS: Readonly<Record<string, MetricGroup>> = {
  OBSERVED_SELLING_PRICE: 'profit',
  BREAK_EVEN_PRICE: 'profit',
  MINIMUM_PRICE: 'profit',
  OPERATIONAL_CONTRIBUTION_PROFIT: 'profit',
  SETTLED_CONTRIBUTION_PROFIT: 'profit',
  CONTRIBUTION_MARGIN: 'profit',
  REQUIRED_PROFIT_PER_UNIT: 'profit',
  SAFETY_BUFFER_PER_UNIT: 'profit',
  IMPRESSIONS: 'funnel',
  CLICKS: 'funnel',
  CLICK_THROUGH_RATE: 'funnel',
  CONVERSION_RATE: 'funnel',
  COMPLETED_UNITS: 'sales',
  COMPLETED_NET_SALES: 'sales',
  RETAINED_UNITS: 'sales',
  RETAINED_NET_SALES: 'sales',
  SETTLED_NET_SALES: 'sales',
  SETTLED_UNITS: 'sales',
  RETURN_UNITS: 'returns',
  RETURN_RATE: 'returns',
  RETURN_LOSS: 'returns',
  RETURN_LOSS_PER_UNIT: 'returns',
  PLATFORM_AVAILABLE_UNITS: 'inventory',
  INTERNAL_AVAILABLE_UNITS: 'inventory',
  STOCK_COVER_DAYS: 'inventory',
  AD_SPEND: 'advertising',
  AD_COST_OF_SALE: 'advertising',
  AD_SPEND_PER_UNIT: 'advertising',
  UNIT_COST: 'cost',
  PLATFORM_FEES: 'cost',
  PLATFORM_FEES_PER_UNIT: 'cost',
  VARIABLE_TAX_ESTIMATE: 'cost',
  VARIABLE_TAX_PER_UNIT: 'cost',
  DATA_COMPLETENESS: 'other',
};

function metricGroup(code: string): MetricGroup {
  const known = METRIC_GROUPS[code];
  if (known !== undefined) return known;
  if (code.startsWith('AD_')) return 'advertising';
  if (code.startsWith('RETURN')) return 'returns';
  if (code.includes('PRICE') || code.includes('PROFIT') || code.includes('MARGIN')) return 'profit';
  if (code.includes('UNITS') || code.includes('STOCK')) return 'inventory';
  if (code.includes('COST') || code.includes('FEE') || code.includes('TAX')) return 'cost';
  if (code.includes('SALES')) return 'sales';
  return 'other';
}

/** Warning flags a folded group keeps in its header. */
function groupFlags(metrics: readonly MetricValue[]): SectionFlag[] {
  let missing = 0;
  let estimated = 0;
  let stale = 0;
  let conflicted = 0;
  let doubtful = 0;
  for (const metric of metrics) {
    if (metric.valueState !== 'AVAILABLE') {
      missing++;
      continue;
    }
    switch (metric.confidenceState) {
      case 'ESTIMATED_EXPLAINED':
        estimated++;
        break;
      case 'STALE':
        stale++;
        break;
      case 'CONFLICTED':
        conflicted++;
        break;
      case 'INCOMPLETE':
      case 'UNKNOWN':
      case 'CANONICAL_PENDING_SETTLEMENT':
        doubtful++;
        break;
      case 'CANONICAL_CONFIRMED':
        if (metric.estimated) estimated++;
        break;
    }
  }
  const flags: SectionFlag[] = [];
  if (missing > 0)
    flags.push({ key: 'missing', label: text.flagMissing(missing), color: 'default' });
  if (conflicted > 0)
    flags.push({ key: 'conflicted', label: text.flagConflicted(conflicted), color: 'error' });
  if (stale > 0) flags.push({ key: 'stale', label: text.flagStale(stale), color: 'warning' });
  if (estimated > 0)
    flags.push({ key: 'estimated', label: text.flagEstimated(estimated), color: 'warning' });
  if (doubtful > 0)
    flags.push({ key: 'doubtful', label: text.flagDoubtful(doubtful), color: 'warning' });
  return flags;
}

/** Blocking first, then by severity; rules that found nothing last. */
function findingRank(finding: DiagnosisFinding): number {
  if (finding.blocksExecution) return 0;
  if (finding.outcome === 'TRIGGERED') {
    return finding.severity === 'CRITICAL' ? 1 : finding.severity === 'WARNING' ? 2 : 3;
  }
  if (finding.outcome === 'DECLINED') return 4;
  return 5;
}

const CODE_LIST = /^[A-Z][A-Z0-9_]*(?:,[A-Z][A-Z0-9_]*)*$/;

/** One recorded detail value: codes become tags, decimals are grouped, text stays as sent. */
function DetailValue({
  name,
  value,
}: {
  readonly name: string;
  readonly value: string;
}): React.JSX.Element {
  // A currency is an ISO code, not a diagnosis code: shown as written.
  if (CODE_LIST.test(value) && name !== 'currencyCode') {
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
 * One listing variant: the proposals waiting on it, what was measured, and
 * what the rules concluded.
 *
 * Proposals come first because they move the work forward; the review opens
 * beside the page (its identifier in the address, so a reload keeps it), with
 * the measured values and rule conclusions still in view behind it.
 *
 * Measured values are folded by domain, but a folded group names in its header
 * how many values are missing, estimated, stale or conflicted, and groups that
 * a triggered or undecided rule cites open by default. Rule conclusions put
 * blocking ones first; rules that found nothing fold behind a count, rules that
 * could not answer never do. Evidence and the AI explanation open in drawers,
 * each one click from the figure or conclusion it concerns.
 */
export function SubjectDiagnosisView({
  context,
  subjectId,
  storeId,
  onBack,
  onOpenCommand,
}: SubjectDiagnosisProps): React.JSX.Element {
  const [diagnosis, setDiagnosis] = useState<SubjectDiagnosis | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [evidenceMetric, setEvidenceMetric] = useState<string>();
  const [aiOpen, setAiOpen] = useState(false);
  const [showClear, setShowClear] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [reviewId, setReviewId] = useSearchParam(REVIEW_PARAM);

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
  const findingsById = new Map<string, DiagnosisFinding>(
    (diagnosis?.findings ?? []).map((finding) => [finding.findingId, finding]),
  );
  const evidenceMetricCode =
    evidenceMetric === undefined ? undefined : metricsById.get(evidenceMetric)?.metricCode;
  const identity = diagnosis?.identity;

  const evidenceLink = (id: string): React.JSX.Element => {
    const metric = metricsById.get(id);
    const label =
      metric === undefined ? `指标 ${id.slice(0, 8)}` : codeLabel(METRIC_LABELS, metric.metricCode);
    return (
      <Button
        type="link"
        size="small"
        style={{ padding: 0, height: 'auto', textAlign: 'left' }}
        aria-label={`${text.viewEvidence} ${label}`}
        onClick={() => {
          setEvidenceMetric(id);
        }}
      >
        {label}
      </Button>
    );
  };

  /** An AI citation, named by what it cites; a metric opens its evidence. */
  const aiReference = (id: string): ReactNode => {
    if (metricsById.has(id)) {
      return evidenceLink(id);
    }
    const finding = findingsById.get(id);
    return (
      <Tag style={{ marginInlineEnd: 0 }}>
        {finding === undefined
          ? `证据 ${id.slice(0, 8)}`
          : `规则：${codeLabel(RULE_LABELS, finding.ruleCode)}`}
      </Tag>
    );
  };

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
      title: '证据',
      key: 'evidence',
      render: (_, finding) =>
        finding.metricValueIds.length === 0 ? (
          <Typography.Text type="secondary">未记录</Typography.Text>
        ) : (
          <Flex vertical gap={0} className="evidence-refs">
            {finding.metricValueIds.map((id) => (
              <span key={id}>{evidenceLink(id)}</span>
            ))}
          </Flex>
        ),
    },
  ];

  const renderDetail = (finding: DiagnosisFinding): React.JSX.Element =>
    Object.keys(finding.detail).length === 0 ? (
      <Typography.Text type="secondary">未记录依据明细</Typography.Text>
    ) : (
      <Flex vertical gap={2}>
        {Object.entries(finding.detail).map(([name, value]) => (
          <Flex key={name} gap={4} wrap align="center">
            <Typography.Text type="secondary">
              {codeLabel(FINDING_DETAIL_LABELS, name)}：
            </Typography.Text>
            <DetailValue name={name} value={value} />
          </Flex>
        ))}
      </Flex>
    );

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
    const sorted = diagnosis.findings
      .map((finding, index) => ({ finding, index }))
      .sort((a, b) => findingRank(a.finding) - findingRank(b.finding) || a.index - b.index)
      .map(({ finding }) => finding);
    const clear = sorted.filter((finding) => finding.outcome === 'CLEAR');
    const notable = sorted.filter((finding) => finding.outcome !== 'CLEAR');
    const visibleFindings = showClear ? sorted : notable;

    // Values that a triggered or undecided rule rests on open their group.
    const cited = new Set(
      diagnosis.findings
        .filter((finding) => finding.outcome !== 'CLEAR')
        .flatMap((finding) => finding.metricValueIds),
    );
    const groups = new Map<MetricGroup, [string, MetricValue][]>();
    for (const entry of metricEntries) {
      const group = metricGroup(entry[0]);
      groups.set(group, [...(groups.get(group) ?? []), entry]);
    }
    const metricItems: SectionCollapseItem[] = GROUP_ORDER.flatMap((group) => {
      const entries = groups.get(group);
      if (entries === undefined) return [];
      return [
        {
          key: group,
          title: METRIC_GROUP_LABELS[group],
          summary: text.itemCount(entries.length),
          flags: groupFlags(entries.map(([, metric]) => metric)),
          defaultOpen: entries.some(([, metric]) => cited.has(metric.metricValueId)),
          children: (
            <Row gutter={[12, 12]} className="value-grid">
              {entries.map(([code, metric]) => (
                <Col key={code} xs={24} sm={12} md={8} xl={6}>
                  <Flex vertical gap={4} data-metric={code}>
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
                      {text.viewEvidence}
                    </Button>
                  </Flex>
                </Col>
              ))}
            </Row>
          ),
        },
      ];
    });

    content = (
      <>
        <SubjectRecommendations
          context={context}
          storeId={storeId}
          subjectId={subjectId}
          refreshKey={refreshKey}
          onReview={(recommendation) => {
            setReviewId(recommendation.id);
          }}
        />

        <section aria-label={text.metrics} data-state="metrics">
          <SectionCard
            title={
              <span>
                {text.metrics}
                <InfoTip title={text.metricsHelp} long />
              </span>
            }
          >
            <Space orientation="vertical" size="small" style={{ width: '100%' }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {text.metricsAbsentNote}
              </Typography.Text>
              {metricEntries.length === 0 ? (
                <EmptyState description={text.noMetrics} />
              ) : (
                <SectionCollapse items={metricItems} size="small" />
              )}
            </Space>
          </SectionCard>
        </section>

        <section aria-label={text.findings} data-state="findings">
          <SectionCard title={text.findings}>
            <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
              {blocking.length > 0 && (
                <Alert type="error" showIcon title={text.blockingAlert(blocking.length)} />
              )}
              {declined.length > 0 && (
                <Alert type="warning" showIcon title={text.declinedAlert(declined.length)} />
              )}
              {diagnosis.findings.length === 0 ? (
                <EmptyState description={text.noFindings} />
              ) : (
                <>
                  {visibleFindings.length === 0 ? (
                    <Typography.Text type="secondary">{text.noProblems}</Typography.Text>
                  ) : (
                    <Table<DiagnosisFinding>
                      size="middle"
                      rowKey="findingId"
                      columns={findingColumns}
                      dataSource={visibleFindings}
                      pagination={false}
                      scroll={{ x: 'max-content' }}
                      expandable={{
                        expandedRowRender: renderDetail,
                        defaultExpandedRowKeys: blocking.map((finding) => finding.findingId),
                      }}
                      onRow={(finding) =>
                        ({
                          'data-rule': finding.ruleCode,
                          'data-outcome': finding.outcome,
                          'data-blocks-write': finding.blocksExecution,
                        }) as React.HTMLAttributes<HTMLElement>
                      }
                    />
                  )}
                  {clear.length > 0 && (
                    <Button
                      type="link"
                      style={{ padding: 0, alignSelf: 'flex-start' }}
                      onClick={() => {
                        setShowClear((shown) => !shown);
                      }}
                    >
                      {showClear ? text.clearShown : text.clearHidden(clear.length)}
                    </Button>
                  )}
                </>
              )}
            </Space>
          </SectionCard>
        </section>
      </>
    );
  }

  const subtitle = subjectSubtitle(identity);
  const description = [
    subtitle,
    diagnosis === undefined ? undefined : codeLabel(EXPORT_WINDOW_LABELS, diagnosis.window),
    `编号 ${subjectId.slice(0, 8)}`,
  ]
    .filter((part): part is string => part !== undefined)
    .join(' · ');

  return (
    <Page
      title={diagnosis === undefined ? pages.subject : subjectTitle(identity, subjectId)}
      description={description}
      extra={
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={onBack}>
            {text.back}
          </Button>
          <Button
            icon={<RobotOutlined />}
            onClick={() => {
              setAiOpen(true);
            }}
          >
            {text.aiButton}
          </Button>
        </Space>
      }
    >
      <section aria-label="SKU 诊断" data-subject={subjectId}>
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {content}
        </Space>
      </section>
      <MetricEvidenceDrawer
        context={context}
        subjectId={subjectId}
        storeId={storeId}
        metricValueId={evidenceMetric}
        metricLabel={
          evidenceMetricCode === undefined
            ? undefined
            : codeLabel(METRIC_LABELS, evidenceMetricCode)
        }
        onClose={() => {
          setEvidenceMetric(undefined);
        }}
      />
      <AiExplanationDrawer
        context={context}
        subjectId={subjectId}
        storeId={storeId}
        open={aiOpen}
        onClose={() => {
          setAiOpen(false);
        }}
        renderReference={aiReference}
      />
      <RecommendationReviewDrawer
        context={context}
        subjectId={subjectId}
        recommendationId={reviewId}
        identity={identity}
        onClose={() => {
          setReviewId(undefined);
        }}
        onChanged={() => {
          setRefreshKey((n) => n + 1);
        }}
        onOpenCommand={onOpenCommand}
      />
    </Page>
  );
}
