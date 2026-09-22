import { ArrowLeftOutlined, DownOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Dropdown,
  Flex,
  Form,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { ConversionMeasurement, ListingDetail, ListingHealth } from '../api/listingConversion';
import { fetchListingDetail, measureConversion, recomputeHealth } from '../api/listingConversion';
import { formatPercent } from '../format';
import { t } from '../i18n/zh/listing';
import { healthDetailText as text } from '../i18n/zh/listingHealth';
import {
  ActionModal,
  EmptyState,
  LoadingState,
  SectionCard,
  TechnicalDetails,
  useSearchParam,
} from '../ui';
import { ListingAssistancePanel } from './ListingAssistancePanel';
import {
  Code,
  Codes,
  Details,
  IdText,
  InstantField,
  ListingName,
  ListingProblem,
  Stack,
  SubTitle,
  When,
  codeOptions,
  codeText,
} from './ListingCommon';
import { ListingDiagnosticResponsibilities } from './ListingDiagnosticResponsibilities';
import { ListingFeedbackPanel } from './ListingFeedbackPanel';
import {
  DescriptionRecorder,
  DisplayRecorder,
  NativeScopeDrawer,
  PromotionObservationDrawer,
} from './ListingObservationRecorders';

const TABS = ['health', 'measurements', 'feedback', 'assistance'] as const;
type DetailTab = (typeof TABS)[number];
const DEFAULT_TAB: DetailTab = 'health';
const TAB_PARAM = 'htab';

/** A detail tab read from the address bar; anything unknown is the health tab. */
function readTab(raw: string | undefined): DetailTab {
  return (TABS as readonly string[]).includes(raw ?? '') ? (raw as DetailTab) : DEFAULT_TAB;
}

type Recorder = 'description' | 'display' | 'nativeScope' | 'promotion';

/** A number just returned by a recorder, kept in view until dismissed. */
interface Recent {
  readonly key: number;
  readonly kind: Recorder;
  readonly id: string;
}

const RECENT_LABEL: Readonly<Record<Recorder, string>> = {
  description: text.recentDescription,
  display: text.recentDisplay,
  nativeScope: text.recentNativeScope,
  promotion: text.recentPromotion,
};

export interface ListingHealthDetailProps {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly onBack: () => void;
  readonly onPrepare: (listingId: string) => void;
}

/**
 * One listing's health: a header that stays in view with the listing's name,
 * its necessary-condition state and the ways forward, and tabs for health and
 * responsibility, conversion measurements, feedback and AI assistance.
 * Observations are recorded from the header without leaving the listing, and
 * the numbers they return stay visible until dismissed.
 */
export function ListingHealthDetail({
  context,
  listingId,
  onBack,
  onPrepare,
}: ListingHealthDetailProps): React.JSX.Element {
  const { message } = App.useApp();
  const [rawTab, setRawTab] = useSearchParam(TAB_PARAM);
  const tab = readTab(rawTab);
  const [detail, setDetail] = useState<ListingDetail | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [recomputing, setRecomputing] = useState(false);
  const [recomputeFailure, setRecomputeFailure] = useState<ConsoleFailure | undefined>();
  const [recording, setRecording] = useState<Recorder | undefined>(undefined);
  const [recent, setRecent] = useState<readonly Recent[]>([]);

  useEffect(() => {
    let active = true;
    void fetchListingDetail(context, listingId).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setDetail(outcome.value);
        setFailure(undefined);
      } else {
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

  const recorded =
    (kind: Recorder) =>
    (id: string): void => {
      setRecording(undefined);
      setRecent((current) => [{ key: Date.now(), kind, id }, ...current].slice(0, 5));
      void message.success(RECENT_LABEL[kind]);
      refresh();
    };
  const closeRecorder = (): void => {
    setRecording(undefined);
  };

  if (detail === undefined) {
    return (
      <section aria-label={t('listing')} data-listing={listingId} data-state="loading">
        <Stack>
          <Button icon={<ArrowLeftOutlined />} onClick={onBack}>
            {text.back}
          </Button>
          {failure === undefined ? <LoadingState rows={6} /> : <ListingProblem failure={failure} />}
        </Stack>
      </section>
    );
  }

  const header = (
    <div style={{ position: 'sticky', top: 64, zIndex: 5 }}>
      <SectionCard
        title={
          <Flex gap={12} align="center" style={{ minWidth: 0 }}>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              aria-label={text.back}
              title={text.back}
              onClick={onBack}
            />
            <div style={{ minWidth: 0, maxWidth: 420 }}>
              <ListingName
                identity={detail.identity}
                nativeListingKey={detail.nativeListingKey}
                platformCode={detail.platformCode}
                listingId={listingId}
              />
            </div>
            {detail.health !== undefined && (
              <Code family="healthState" code={detail.health.necessaryState} />
            )}
          </Flex>
        }
        extra={
          <Flex gap={8} wrap justify="flex-end">
            <Button
              icon={<ReloadOutlined />}
              loading={recomputing}
              onClick={() => {
                setRecomputing(true);
                setRecomputeFailure(undefined);
                void recomputeHealth(context, listingId).then((outcome) => {
                  setRecomputing(false);
                  if (outcome.ok) {
                    void message.success(text.recomputed);
                    refresh();
                  } else setRecomputeFailure(outcome.failure);
                });
              }}
            >
              {t('recompute')}
            </Button>
            <Dropdown
              trigger={['click']}
              menu={{
                items: [
                  { key: 'description', label: text.recordDescription },
                  { key: 'display', label: text.recordDisplay },
                  { key: 'nativeScope', label: text.recordNativeScope },
                  { key: 'promotion', label: text.recordPromotion },
                ],
                onClick: ({ key }) => {
                  setRecording(key as Recorder);
                },
              }}
            >
              <Button>
                <Space size={4}>
                  {text.record}
                  <DownOutlined />
                </Space>
              </Button>
            </Dropdown>
            <Button
              type="primary"
              onClick={() => {
                onPrepare(listingId);
              }}
            >
              {t('prepareCandidates')}
            </Button>
          </Flex>
        }
      />
    </div>
  );

  return (
    <section aria-label={t('listing')} data-listing={listingId} data-state="loaded">
      {header}
      <Stack>
        {recomputeFailure !== undefined && <ListingProblem failure={recomputeFailure} />}
        {failure !== undefined && <ListingProblem failure={failure} />}
        {recent.length > 0 && (
          <div role="status" aria-label={text.recentTitle}>
            <Flex vertical gap={4}>
              {recent.map((entry) => (
                <Alert
                  key={entry.key}
                  type="success"
                  showIcon
                  closable={{
                    onClose: () => {
                      setRecent((current) => current.filter((item) => item.key !== entry.key));
                    },
                  }}
                  title={
                    <Flex gap={8} wrap align="center">
                      <Typography.Text type="secondary">{text.recentTitle}</Typography.Text>
                      <IdText label={RECENT_LABEL[entry.kind]} value={entry.id} />
                    </Flex>
                  }
                />
              ))}
            </Flex>
          </div>
        )}
        <Tabs
          activeKey={tab}
          onChange={(key) => {
            setRawTab(key === DEFAULT_TAB ? undefined : key);
          }}
          items={[
            {
              key: 'health',
              label: text.tabHealth,
              children: (
                <Stack>
                  <SectionCard title={t('healthNecessary')}>
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
                </Stack>
              ),
            },
            {
              key: 'measurements',
              label: text.tabMeasurements,
              children: (
                <SectionCard
                  title={t('measurements')}
                  extra={
                    <MeasureConversion
                      context={context}
                      listingId={listingId}
                      onMeasured={() => {
                        void message.success(text.measured);
                        refresh();
                      }}
                    />
                  }
                >
                  <Measurements measurements={detail.measurements} />
                </SectionCard>
              ),
            },
            {
              key: 'feedback',
              label: text.tabFeedback,
              children: <ListingFeedbackPanel context={context} listingId={listingId} />,
            },
            {
              key: 'assistance',
              label: (
                <Space size={6}>
                  {text.tabAssistance}
                  <Tag color="purple" style={{ marginInlineEnd: 0 }}>
                    {text.referenceOnly}
                  </Tag>
                </Space>
              ),
              children: <ListingAssistancePanel context={context} listingId={listingId} />,
            },
          ]}
        />
      </Stack>
      <DescriptionRecorder
        context={context}
        listingId={listingId}
        open={recording === 'description'}
        onClose={closeRecorder}
        onRecorded={recorded('description')}
      />
      <DisplayRecorder
        context={context}
        listingId={listingId}
        open={recording === 'display'}
        onClose={closeRecorder}
        onRecorded={recorded('display')}
      />
      <NativeScopeDrawer
        context={context}
        listingId={listingId}
        open={recording === 'nativeScope'}
        onClose={closeRecorder}
        onRecorded={recorded('nativeScope')}
      />
      <PromotionObservationDrawer
        context={context}
        listingId={listingId}
        open={recording === 'promotion'}
        onClose={closeRecorder}
        onRecorded={recorded('promotion')}
      />
    </section>
  );
}

interface MeasureValues {
  readonly windowStart?: string;
  readonly windowEnd?: string;
  readonly retentionDays?: string;
  readonly evidencePath?: string;
}

/** Compute one conversion measurement over a chosen window. */
function MeasureConversion({
  context,
  listingId,
  onMeasured,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly onMeasured: () => void;
}): React.JSX.Element {
  return (
    <ActionModal<MeasureValues>
      trigger={{ label: t('measure'), type: 'primary' }}
      title={t('measure')}
      consequence={text.measureConsequence}
      okText={t('measure')}
      initialValues={{ retentionDays: '14', evidencePath: 'DETAIL' }}
      onSubmit={async (values) => {
        const outcome = await measureConversion(
          context,
          listingId,
          values.windowStart ?? '',
          values.windowEnd ?? '',
          Number(values.retentionDays ?? '14'),
          values.evidencePath ?? 'DETAIL',
        );
        if (!outcome.ok) return outcome.failure;
        onMeasured();
        return undefined;
      }}
    >
      <Form.Item
        name="windowStart"
        label={t('windowStart')}
        rules={[{ required: true, whitespace: true, message: text.windowStartRequired }]}
      >
        <InstantField ariaLabel={t('windowStart')} />
      </Form.Item>
      <Form.Item
        name="windowEnd"
        label={t('windowEnd')}
        dependencies={['windowStart']}
        rules={[
          { required: true, whitespace: true, message: text.windowEndRequired },
          ({ getFieldValue }) => ({
            validator: (_, value: string | undefined) => {
              const start: unknown = getFieldValue('windowStart');
              if (value === undefined || value === '' || typeof start !== 'string' || start === '')
                return Promise.resolve();
              return Date.parse(value) > Date.parse(start)
                ? Promise.resolve()
                : Promise.reject(new Error(text.windowOrder));
            },
          }),
        ]}
      >
        <InstantField ariaLabel={t('windowEnd')} />
      </Form.Item>
      <Form.Item name="retentionDays" label={t('retentionDays')} rules={[{ required: true }]}>
        <Select
          options={['7', '14', '30'].map((days) => ({ value: days, label: text.daysOption(days) }))}
        />
      </Form.Item>
      <Form.Item name="evidencePath" label={t('evidencePath')} rules={[{ required: true }]}>
        <Select options={codeOptions('evidencePath', ['DETAIL', 'OFFICIAL_SUMMARY'])} />
      </Form.Item>
    </ActionModal>
  );
}

/** Eligibility per purpose, as tags. */
export function Eligibility({
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

/** The three layers of health, every condition beside its evidence. */
function HealthLayers({ health }: { readonly health: ListingHealth }): React.JSX.Element {
  return (
    <div data-health-version={health.healthVersion}>
      <Stack>
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
              title: text.conditionEvidence,
              render: (_, condition) => <IdText value={condition.evidenceReference} />,
            },
          ]}
        />
        <Details
          items={[
            {
              key: 'eligibility',
              label: t('healthEligibility'),
              children: <Eligibility eligibility={health.eligibility} />,
            },
            {
              key: 'affected',
              label: t('affectedSetShort'),
              children: (
                <Space size={4}>
                  <Code family="affectedSetState" code={health.affectedSetState} />
                  <Typography.Text type="secondary">
                    {text.variants(health.affectedVariantCount)}
                  </Typography.Text>
                </Space>
              ),
            },
            { key: 'version', label: t('healthVersion'), children: health.healthVersion },
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
                  title: text.conditionEvidence,
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

/** Measurements with their ratio state beside every ratio. */
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
            {t('retentionDays')} {text.daysOption(String(measurement.retentionWindowDays))}
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
