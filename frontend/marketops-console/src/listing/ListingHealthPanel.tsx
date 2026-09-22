import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  App,
  Button,
  Col,
  Flex,
  Form,
  Input,
  Row,
  Segmented,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { ConversionMeasurement, ListingDetail, ListingHealth } from '../api/listingConversion';
import {
  fetchHealthQueue,
  fetchListingDetail,
  measureConversion,
  recomputeHealth,
  recordDescriptionFact,
  recordDisplayFact,
} from '../api/listingConversion';
import { formatPercent } from '../format';
import { t } from '../i18n/zh/listing';
import { EmptyState, LoadingState, SectionCard, TechnicalDetails } from '../ui';
import { ListingAssistancePanel } from './ListingAssistancePanel';
import {
  Code,
  Codes,
  Details,
  Hint,
  IdText,
  InstantPicker,
  ListingProblem,
  Stack,
  SubTitle,
  When,
  codeOptions,
  codeText,
} from './ListingCommon';
import { ListingDiagnosticResponsibilities } from './ListingDiagnosticResponsibilities';
import { ListingFeedbackPanel } from './ListingFeedbackPanel';
import { NativeScopeObservationForm } from './ListingNativeScope';
import { PromotionObservationForm } from './ListingPromotionTerms';

export interface ListingHealthPanelProps {
  readonly context: ConsoleRequest;
  readonly onPrepare: (listingId: string) => void;
}

const HEALTH_STATES = ['PASS', 'FAIL', 'UNKNOWN'] as const;
const ALL = 'ALL';

/** Eligibility per purpose, as tags. */
function Eligibility({
  eligibility,
}: {
  readonly eligibility: Readonly<Record<string, string>>;
}): React.JSX.Element {
  const entries = Object.entries(eligibility);
  if (entries.length === 0) return <Typography.Text type="secondary">—</Typography.Text>;
  return (
    <Flex wrap gap={4}>
      {entries.map(([purpose, state]) => (
        <Space key={purpose} size={2}>
          <Typography.Text type="secondary">
            {codeText('eligibilityPurpose', purpose)}
          </Typography.Text>
          <Code family="eligibility" code={state} />
        </Space>
      ))}
    </Flex>
  );
}

/**
 * Listing Health as three layers and never as a score.
 *
 * The queue is the product's own ordering of necessary-condition state; the
 * detail shows every condition with its evidence, the eligibility per purpose
 * and the opportunities by name. A measurement is shown with its ratio state
 * beside it, so "no visits" and "not yet mature" never read the same.
 */
export function ListingHealthPanel({
  context,
  onPrepare,
}: ListingHealthPanelProps): React.JSX.Element {
  const [filter, setFilter] = useState<string | undefined>(undefined);
  const [rows, setRows] = useState<readonly ListingHealth[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [selected, setSelected] = useState<string | undefined>(undefined);
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    let active = true;
    void fetchHealthQueue(context, filter).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setRows(outcome.value);
        setFailure(undefined);
      } else {
        setRows(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, filter, generation]);

  if (selected !== undefined) {
    return (
      <ListingDetailView
        context={context}
        listingId={selected}
        onBack={() => {
          setSelected(undefined);
        }}
        onPrepare={onPrepare}
      />
    );
  }

  const columns: TableColumnsType<ListingHealth> = [
    {
      key: 'listing',
      title: t('listing'),
      render: (_, health) => (
        <Button
          type="link"
          style={{ padding: 0 }}
          onClick={() => {
            setSelected(health.platformListingId);
          }}
        >
          {health.nativeListingKey}
        </Button>
      ),
    },
    {
      key: 'necessary',
      title: t('healthNecessary'),
      render: (_, health) => <Code family="healthState" code={health.necessaryState} />,
    },
    {
      key: 'eligibility',
      title: t('healthEligibility'),
      render: (_, health) => <Eligibility eligibility={health.eligibility} />,
    },
    {
      key: 'opportunities',
      title: t('healthOpportunities'),
      render: (_, health) => (
        <Codes family="opportunity" codes={health.opportunities.map((entry) => entry.code)} />
      ),
    },
    {
      key: 'computedAt',
      title: t('computedAt'),
      render: (_, health) => <When value={health.computedAt} />,
    },
  ];

  return (
    <section aria-label={t('healthQueue')} data-state={rows === undefined ? 'loading' : 'loaded'}>
      <SectionCard
        title={t('healthQueue')}
        extra={
          <Button
            icon={<ReloadOutlined />}
            onClick={() => {
              setGeneration((value) => value + 1);
            }}
          >
            {t('refresh')}
          </Button>
        }
      >
        <Stack>
          <Hint>{t('noScore')}</Hint>
          <Space wrap>
            <Typography.Text type="secondary">{t('healthNecessary')}</Typography.Text>
            <Segmented<string>
              aria-label={t('healthNecessary')}
              value={filter ?? ALL}
              options={[
                { value: ALL, label: t('all') },
                ...HEALTH_STATES.map((state) => ({
                  value: state,
                  label: codeText('healthState', state),
                })),
              ]}
              onChange={(value) => {
                setFilter(value === ALL ? undefined : value);
              }}
            />
          </Space>
          {failure !== undefined && <ListingProblem failure={failure} />}
          {rows === undefined && failure === undefined && <LoadingState />}
          {rows?.length === 0 && <EmptyState description={t('noHealthRows')} />}
          {rows !== undefined && rows.length > 0 && (
            <Table<ListingHealth>
              size="middle"
              rowKey="id"
              columns={columns}
              dataSource={[...rows]}
              pagination={false}
              onRow={(health) =>
                ({ 'data-listing': health.platformListingId }) as React.HTMLAttributes<HTMLElement>
              }
            />
          )}
        </Stack>
      </SectionCard>
    </section>
  );
}

interface ListingDetailViewProps {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly onBack: () => void;
  readonly onPrepare: (listingId: string) => void;
}

type Kiz = 'undeclared' | 'yes' | 'no';

function ListingDetailView({
  context,
  listingId,
  onBack,
  onPrepare,
}: ListingDetailViewProps): React.JSX.Element {
  const { message } = App.useApp();
  const [detail, setDetail] = useState<ListingDetail | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [descriptionText, setDescriptionText] = useState('');
  const [kiz, setKiz] = useState<Kiz>('undeclared');
  const [displayState, setDisplayState] = useState('DISPLAYED');
  const [displayedText, setDisplayedText] = useState('');
  const [evidenceReference, setEvidenceReference] = useState('');
  const [windowStart, setWindowStart] = useState('');
  const [windowEnd, setWindowEnd] = useState('');
  const [retentionDays, setRetentionDays] = useState('14');
  const [evidencePath, setEvidencePath] = useState('DETAIL');
  const [busy, setBusy] = useState<string | undefined>(undefined);

  useEffect(() => {
    let active = true;
    setDetail(undefined);
    void fetchListingDetail(context, listingId).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setDetail(outcome.value);
        setFailure(undefined);
      } else {
        setDetail(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, listingId, generation]);

  const refresh = (): void => {
    setGeneration((value) => value + 1);
  };
  const report = (outcome: { readonly ok: boolean; readonly failure?: ConsoleFailure }): void => {
    setBusy(undefined);
    if (outcome.ok) {
      void message.success(t('done'));
      setFailure(undefined);
      refresh();
    } else if (outcome.failure !== undefined) {
      setFailure(outcome.failure);
    }
  };
  const kizOptions: { value: Kiz; label: string }[] = [
    { value: 'undeclared', label: t('undeclared') },
    { value: 'yes', label: t('yes') },
    { value: 'no', label: t('no') },
  ];

  return (
    <section aria-label={t('listing')} data-listing={listingId}>
      <Stack>
        <Button icon={<ArrowLeftOutlined />} onClick={onBack}>
          {t('healthQueue')}
        </Button>
        {failure !== undefined && <ListingProblem failure={failure} />}
        {detail === undefined && failure === undefined && <LoadingState rows={6} />}
        {detail !== undefined && (
          <>
            <SectionCard
              title={
                <Space>
                  {t('listing')}
                  <span>{detail.nativeListingKey}</span>
                  <Typography.Text type="secondary">{detail.platformCode}</Typography.Text>
                </Space>
              }
              extra={
                <Space wrap>
                  <Button
                    icon={<ReloadOutlined />}
                    loading={busy === 'recompute'}
                    onClick={() => {
                      setBusy('recompute');
                      void recomputeHealth(context, listingId).then(report);
                    }}
                  >
                    {t('recompute')}
                  </Button>
                  <Button
                    type="primary"
                    onClick={() => {
                      onPrepare(listingId);
                    }}
                  >
                    {t('prepareCandidates')}
                  </Button>
                </Space>
              }
            >
              {detail.health === undefined ? (
                <EmptyState description={t('noHealth')} />
              ) : (
                <HealthLayers health={detail.health} />
              )}
            </SectionCard>
            <ListingDiagnosticResponsibilities
              context={context}
              listingId={listingId}
              responsibilities={detail.diagnosticResponsibilities ?? []}
              refresh={refresh}
            />
            <SectionCard title={t('measurements')}>
              <Stack>
                <Measurements measurements={detail.measurements} />
                <SubTitle>{t('measure')}</SubTitle>
                <Form
                  layout="vertical"
                  aria-label={t('measure')}
                  onFinish={() => {
                    setBusy('measure');
                    void measureConversion(
                      context,
                      listingId,
                      windowStart,
                      windowEnd,
                      Number(retentionDays),
                      evidencePath,
                    ).then(report);
                  }}
                >
                  <Row gutter={16}>
                    <Col xs={24} md={6}>
                      <Form.Item label={t('windowStart')}>
                        <InstantPicker
                          value={windowStart}
                          onChange={setWindowStart}
                          ariaLabel={t('windowStart')}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={6}>
                      <Form.Item label={t('windowEnd')}>
                        <InstantPicker
                          value={windowEnd}
                          onChange={setWindowEnd}
                          ariaLabel={t('windowEnd')}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={6}>
                      <Form.Item label={t('retentionDays')}>
                        <Select
                          value={retentionDays}
                          onChange={setRetentionDays}
                          options={['7', '14', '30'].map((days) => ({
                            value: days,
                            label: `${days} 天`,
                          }))}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={6}>
                      <Form.Item label={t('evidencePath')}>
                        <Select
                          value={evidencePath}
                          onChange={setEvidencePath}
                          options={codeOptions('evidencePath', ['DETAIL', 'OFFICIAL_SUMMARY'])}
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Button type="primary" htmlType="submit" loading={busy === 'measure'}>
                    {t('measure')}
                  </Button>
                </Form>
              </Stack>
            </SectionCard>
            <NativeScopeObservationForm context={context} listingId={listingId} />
            <PromotionObservationForm context={context} listingId={listingId} />
            <ListingFeedbackPanel key={listingId} context={context} listingId={listingId} />
            <ListingAssistancePanel
              key={`assistance-${listingId}`}
              context={context}
              listingId={listingId}
            />
            <SectionCard title={t('descriptionFact')}>
              <Hint>{t('descriptionFactHelp')}</Hint>
              <Form
                layout="vertical"
                aria-label={t('targetText')}
                onFinish={() => {
                  setBusy('description');
                  void recordDescriptionFact(
                    context,
                    listingId,
                    descriptionText,
                    kiz === 'undeclared' ? undefined : kiz === 'yes',
                    '',
                  ).then(report);
                }}
              >
                <Form.Item label={t('observedDescription')}>
                  <Input.TextArea
                    lang="ru"
                    value={descriptionText}
                    autoSize={{ minRows: 4, maxRows: 12 }}
                    onChange={(e) => {
                      setDescriptionText(e.target.value);
                    }}
                  />
                </Form.Item>
                <Form.Item label={t('kiz')}>
                  <Select<Kiz>
                    value={kiz}
                    style={{ maxWidth: 240 }}
                    onChange={setKiz}
                    options={kizOptions}
                  />
                </Form.Item>
                <Button type="primary" htmlType="submit" loading={busy === 'description'}>
                  {t('submit')}
                </Button>
              </Form>
            </SectionCard>
            <SectionCard title={t('displayFact')}>
              <Form
                layout="vertical"
                aria-label={t('evidence')}
                onFinish={() => {
                  setBusy('display');
                  void recordDisplayFact(
                    context,
                    listingId,
                    displayState,
                    displayedText,
                    evidenceReference,
                  ).then(report);
                }}
              >
                <Row gutter={16}>
                  <Col xs={24} md={6}>
                    <Form.Item label={t('displayStateLabel')}>
                      <Select
                        value={displayState}
                        onChange={setDisplayState}
                        options={codeOptions('displayState', [
                          'DISPLAYED',
                          'NOT_DISPLAYED',
                          'UNKNOWN',
                        ])}
                      />
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={10}>
                    <Form.Item label={t('displayedText')}>
                      <Input
                        lang="ru"
                        value={displayedText}
                        onChange={(e) => {
                          setDisplayedText(e.target.value);
                        }}
                      />
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={8}>
                    <Form.Item label={t('evidence')}>
                      <Input
                        value={evidenceReference}
                        onChange={(e) => {
                          setEvidenceReference(e.target.value);
                        }}
                      />
                    </Form.Item>
                  </Col>
                </Row>
                <Button type="primary" htmlType="submit" loading={busy === 'display'}>
                  {t('submit')}
                </Button>
              </Form>
            </SectionCard>
          </>
        )}
      </Stack>
    </section>
  );
}

function HealthLayers({ health }: { readonly health: ListingHealth }): React.JSX.Element {
  return (
    <div data-health-version={health.healthVersion}>
      <Stack>
        <Details
          items={[
            {
              key: 'necessary',
              label: t('healthNecessary'),
              children: <Code family="healthState" code={health.necessaryState} />,
            },
            {
              key: 'eligibility',
              label: t('healthEligibility'),
              children: <Eligibility eligibility={health.eligibility} />,
            },
            {
              key: 'version',
              label: t('healthVersion'),
              children: health.healthVersion,
            },
            {
              key: 'affected',
              label: t('affectedSetShort'),
              children: (
                <Space size={4}>
                  <Code family="affectedSetState" code={health.affectedSetState} />
                  <Typography.Text type="secondary">
                    {health.affectedVariantCount} 个变体
                  </Typography.Text>
                </Space>
              ),
            },
            { key: 'source', label: t('sourceTime'), children: <When value={health.sourceTime} /> },
            {
              key: 'acquired',
              label: t('acquisitionTime'),
              children: <When value={health.acquisitionTime} />,
            },
            {
              key: 'computed',
              label: t('computedAt'),
              children: <When value={health.computedAt} />,
            },
          ]}
        />
        <div>
          <SubTitle>{t('healthNecessary')}</SubTitle>
          <Table
            size="middle"
            rowKey="code"
            pagination={false}
            dataSource={[...health.necessaryConditions]}
            columns={[
              {
                key: 'code',
                title: t('condition'),
                render: (_, condition) => <Code family="healthCondition" code={condition.code} />,
              },
              {
                key: 'state',
                title: t('state'),
                render: (_, condition) => <Code family="healthState" code={condition.state} />,
              },
              {
                key: 'evidence',
                title: t('evidence'),
                render: (_, condition) => <IdText value={condition.evidenceReference} />,
              },
            ]}
          />
        </div>
        <div>
          <SubTitle>{t('healthOpportunities')}</SubTitle>
          {health.opportunities.length === 0 ? (
            <EmptyState description={t('noOpportunities')} />
          ) : (
            <Table
              size="middle"
              rowKey="code"
              pagination={false}
              dataSource={[...health.opportunities]}
              columns={[
                {
                  key: 'code',
                  title: t('opportunity'),
                  render: (_, opportunity) => <Code family="opportunity" code={opportunity.code} />,
                },
                {
                  key: 'evidence',
                  title: t('evidence'),
                  render: (_, opportunity) => <IdText value={opportunity.evidenceReference} />,
                },
              ]}
            />
          )}
        </div>
      </Stack>
    </div>
  );
}

function Measurements({
  measurements,
}: {
  readonly measurements: readonly ConversionMeasurement[];
}): React.JSX.Element {
  if (measurements.length === 0) {
    return <EmptyState description={t('noMeasurements')} />;
  }
  const columns: TableColumnsType<ConversionMeasurement> = [
    {
      key: 'window',
      title: t('window'),
      render: (_, measurement) => (
        <Space orientation="vertical" size={0}>
          <span>
            <When value={measurement.windowStart} /> — <When value={measurement.windowEnd} />
          </span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('retentionDays')} {measurement.retentionWindowDays} 天
          </Typography.Text>
        </Space>
      ),
    },
    {
      key: 'path',
      title: t('evidencePath'),
      render: (_, measurement) => <Code family="evidencePath" code={measurement.evidencePath} />,
    },
    {
      key: 'visits',
      title: t('visits'),
      align: 'right',
      render: (_, measurement) => measurement.visitCount ?? '—',
    },
    {
      key: 'retained',
      title: t('retained'),
      align: 'right',
      render: (_, measurement) => measurement.retainedPurchaseVisitCount ?? '—',
    },
    {
      key: 'ratio',
      title: t('ratio'),
      render: (_, measurement) => (
        <Space size={4}>
          {measurement.ratioState === 'DEFINED' && (
            <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>
              {measurement.primaryRatio === undefined
                ? '—'
                : formatPercent(measurement.primaryRatio)}
            </Typography.Text>
          )}
          <Code family="ratioState" code={measurement.ratioState} />
        </Space>
      ),
    },
    {
      key: 'excluded',
      title: t('excludedDays'),
      render: (_, measurement) =>
        measurement.excludedTransitionDays.length === 0
          ? '—'
          : measurement.excludedTransitionDays.join('、'),
    },
    {
      key: 'qualification',
      title: t('qualification'),
      render: (_, measurement) => (
        <Codes family="qualificationReason" codes={measurement.qualificationReasonCodes} />
      ),
    },
    {
      key: 'times',
      title: t('sourceTime'),
      render: (_, measurement) => (
        <Space orientation="vertical" size={0}>
          <When value={measurement.sourceTime} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('acquisitionTime')} <When value={measurement.acquisitionTime} />
          </Typography.Text>
        </Space>
      ),
    },
  ];
  return (
    <div>
      <Table<ConversionMeasurement>
        size="middle"
        rowKey="id"
        columns={columns}
        dataSource={[...measurements]}
        pagination={false}
        scroll={{ x: 'max-content' }}
        onRow={(measurement) =>
          ({ 'data-ratio-state': measurement.ratioState }) as React.HTMLAttributes<HTMLElement>
        }
      />
      <TechnicalDetails label={t('measurementIds')}>
        <Space orientation="vertical" size={2}>
          {measurements.map((measurement) => (
            <IdText key={measurement.id} value={measurement.id} />
          ))}
        </Space>
      </TechnicalDetails>
    </div>
  );
}
