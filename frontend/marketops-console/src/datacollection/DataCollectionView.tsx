import { ReloadOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import { Alert, App, Button, Flex, Form, Input, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  CollectionEvent,
  CollectionJob,
  CollectionRun,
  CollectionTarget,
  DataCollection,
} from '../api/dataCollection';
import {
  enableDataCollection,
  fetchDataCollection,
  retireDataCollection,
} from '../api/dataCollection';
import { actions } from '../i18n';
import {
  CADENCE_LABELS,
  DATASET_LABELS,
  dataCollectionText as text,
  EVENT_LABELS,
  REASON_LABELS,
  RUN_STATE_LABELS,
} from '../i18n/zh/dataCollection';
import {
  ActionModal,
  DateTime,
  EmptyState,
  FailureAlert,
  InfoTip,
  LoadingState,
  SectionCard,
} from '../ui';
import type { TagColor } from '../ui';
import { FeedFreshnessSection } from './FeedFreshness';

/** What the page needs in order to load itself. */
export interface DataCollectionViewProps {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}

type Loaded =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly data: DataCollection }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

interface ReasonValues {
  readonly reason?: string;
}

/** Evidence lapsing within this many days is flagged. */
const EVIDENCE_WARNING_DAYS = 7;

const DAY_MILLIS = 86_400_000;

const STATE_COLORS: Readonly<Record<string, TagColor>> = {
  QUEUED: 'processing',
  LEASED: 'processing',
  RUNNING: 'processing',
  RETRY_WAIT: 'warning',
  BLOCKED: 'error',
  SUCCEEDED: 'success',
  FAILED_TERMINAL: 'error',
};

const EVENT_COLORS: Readonly<Record<string, TagColor>> = {
  COLLECTED: 'success',
  WAITING: 'warning',
  BLOCKED: 'error',
  FAILED: 'error',
  SKIPPED: 'warning',
  NORMALIZATION_STOPPED: 'error',
  RECALCULATED: 'processing',
  RECALCULATION_FAILED: 'error',
  INTERPRETED: 'processing',
  INTERPRETATION_FAILED: 'error',
};

/** A UTC window as the dates it covers: one date for a day, a range for a week. */
function windowText(from: string | null, to: string | null): string | null {
  if (from === null || to === null) return null;
  const first = from.slice(0, 10);
  const last = new Date(Date.parse(to) - 1).toISOString().slice(0, 10);
  return first === last ? first : text.windowLabel(first, last);
}

function datasetLabel(code: string | null): string {
  return code === null ? '—' : (DATASET_LABELS[code] ?? code);
}

function RunState({ run }: { readonly run: CollectionRun | null }): React.JSX.Element {
  if (run === null) return <Typography.Text type="secondary">{text.stateIdle}</Typography.Text>;
  return (
    <Flex vertical gap={2}>
      <Space size={4}>
        <Tag color={STATE_COLORS[run.state] ?? 'default'} style={{ marginInlineEnd: 0 }}>
          {RUN_STATE_LABELS[run.state] ?? run.state}
        </Tag>
        {run.state === 'BLOCKED' && <InfoTip title={text.blockedHint} />}
      </Space>
      {run.failureCode !== null && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }} code>
          {run.failureCode}
        </Typography.Text>
      )}
      {run.state === 'RETRY_WAIT' && run.nextAttemptAt !== null && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.nextAttemptAt} <DateTime value={run.nextAttemptAt} />
        </Typography.Text>
      )}
    </Flex>
  );
}

function LastSucceeded({ run }: { readonly run: CollectionRun | null }): React.JSX.Element {
  if (run === null) return <Typography.Text type="secondary">{text.never}</Typography.Text>;
  const window = windowText(run.windowFrom, run.windowTo);
  return (
    <Flex vertical gap={2}>
      <Space size={4} wrap>
        <DateTime value={run.updatedAt} />
        <Tag style={{ marginInlineEnd: 0 }}>
          {run.runKind === 'SCHEDULED' ? text.scheduledRun : text.manualRun}
        </Tag>
      </Space>
      {window !== null && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {window}
        </Typography.Text>
      )}
    </Flex>
  );
}

function Next({ job }: { readonly job: CollectionJob }): React.JSX.Element {
  const target: CollectionTarget | null = job.due ?? job.upcoming;
  if (target === null) return <Typography.Text type="secondary">—</Typography.Text>;
  const window = windowText(target.windowFrom, target.windowTo);
  return (
    <Flex vertical gap={2}>
      {job.due !== null ? (
        <Space size={4} wrap>
          <Tag color="processing" style={{ marginInlineEnd: 0 }}>
            {text.dueNow}
          </Tag>
          {job.attempts > 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.attempts(job.attempts)}
            </Typography.Text>
          )}
        </Space>
      ) : (
        <DateTime value={target.slotStart} />
      )}
      {window !== null && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {window}
        </Typography.Text>
      )}
    </Flex>
  );
}

function Evidence({ validUntil }: { readonly validUntil: string | null }): React.JSX.Element {
  if (validUntil === null) {
    return <Tag color="error">{text.evidenceMissing}</Tag>;
  }
  const days = Math.ceil((Date.parse(validUntil) - Date.now()) / DAY_MILLIS);
  return (
    <Flex vertical gap={2}>
      <DateTime value={validUntil} />
      {days <= EVIDENCE_WARNING_DAYS && (
        <Tag color="warning" style={{ marginInlineEnd: 0, width: 'fit-content' }}>
          {text.evidenceExpiresSoon(Math.max(days, 0))}
        </Tag>
      )}
    </Flex>
  );
}

/** A record's detail in words: what was read, what it produced, why it stopped. */
function eventDetail(event: CollectionEvent): string {
  const detail = event.detail;
  const parts: string[] = [];
  const window = windowText(detail.windowFrom ?? null, detail.windowTo ?? null);
  if (window !== null) parts.push(window);
  if (event.kind === 'RECALCULATED') {
    parts.push(text.recalculatedDetail(detail.subjectCount ?? '0', detail.findingCount ?? '0'));
  }
  if (detail.pagesStored !== undefined) parts.push(text.pagesStored(detail.pagesStored));
  if (detail.factsRecorded !== undefined) parts.push(text.factsRecorded(detail.factsRecorded));
  if (detail.masterDataAutomation === 'RAN') parts.push(text.masterData);
  if (detail.masterDataAutomation === 'FAILED') parts.push(text.masterDataFailed);
  if (detail.failureCode !== undefined) parts.push(detail.failureCode);
  const reason = detail.reason;
  if (reason !== undefined && event.kind !== 'COLLECTED')
    parts.push(REASON_LABELS[reason] ?? reason);
  if (detail.normalization !== undefined && detail.normalization !== 'NOTHING_TO_PROCESS') {
    parts.push(REASON_LABELS[detail.normalization] ?? detail.normalization);
  }
  if (detail.failureType !== undefined) parts.push(detail.failureType);
  if (event.kind.startsWith('INTERPRET') && detail.reused === 'true')
    parts.push(text.summaryReused);
  return parts.join(' · ');
}

function EventTag({ kind }: { readonly kind: string }): React.JSX.Element {
  return (
    <Tag color={EVENT_COLORS[kind] ?? 'default'} style={{ marginInlineEnd: 0 }}>
      {EVENT_LABELS[kind] ?? kind}
    </Tag>
  );
}

/**
 * Scheduled collection of one store: the Owner's standing authorization, what each scheduled job
 * read last and reads next, the kept diagnosis calculations and everything the scheduler recorded.
 */
export function DataCollectionView({
  context,
  storeId,
}: DataCollectionViewProps): React.JSX.Element {
  const { message } = App.useApp();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    if (storeId === '') return undefined;
    let live = true;
    void fetchDataCollection(context, storeId).then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { kind: 'loaded', data: outcome.value }
          : { kind: 'failed', failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, storeId, generation]);

  if (storeId === '') return <EmptyState description={text.noStore} />;
  if (loaded.kind === 'loading') return <LoadingState />;
  if (loaded.kind === 'failed') return <FailureAlert failure={loaded.failure} />;
  const data = loaded.data;
  const reload = (): void => {
    setGeneration((value) => value + 1);
  };

  const reasonField = (
    <Form.Item
      name="reason"
      label={text.reasonLabel}
      rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
    >
      <Input.TextArea rows={3} maxLength={500} showCount placeholder={text.reasonPlaceholder} />
    </Form.Item>
  );

  const jobColumns: TableColumnsType<CollectionJob> = [
    {
      key: 'dataset',
      title: text.columnDataset,
      width: 150,
      render: (_, job) => (
        <Flex vertical gap={2}>
          <Typography.Text strong>{datasetLabel(job.datasetKind)}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {job.jobCode}
          </Typography.Text>
        </Flex>
      ),
    },
    {
      key: 'cadence',
      title: text.columnCadence,
      width: 130,
      render: (_, job) => CADENCE_LABELS[job.cadence] ?? job.cadence,
    },
    {
      key: 'last',
      title: text.columnLast,
      width: 190,
      render: (_, job) => <LastSucceeded run={job.lastSucceeded} />,
    },
    {
      key: 'now',
      title: text.columnNow,
      width: 150,
      render: (_, job) => (
        <Flex vertical gap={2}>
          <RunState run={job.liveRun} />
          {job.liveRunLastAnswer !== null && job.liveRun?.state === 'BLOCKED' && (
            <Typography.Text type="danger" style={{ fontSize: 12 }}>
              {text.lastAnswer(job.liveRunLastAnswer)}
            </Typography.Text>
          )}
        </Flex>
      ),
    },
    {
      key: 'next',
      title: text.columnNext,
      width: 190,
      render: (_, job) => <Next job={job} />,
    },
    {
      key: 'evidence',
      title: (
        <Space size={4}>
          {text.columnEvidence}
          <InfoTip title={text.evidenceHint} />
        </Space>
      ),
      width: 170,
      render: (_, job) => <Evidence validUntil={job.evidenceValidUntil} />,
    },
    {
      key: 'lastEvent',
      title: text.columnLastEvent,
      width: 170,
      render: (_, job) =>
        job.lastEvent === null ? (
          <Typography.Text type="secondary">—</Typography.Text>
        ) : (
          <Flex vertical gap={2}>
            <EventTag kind={job.lastEvent.kind} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              <DateTime value={job.lastEvent.occurredAt} />
            </Typography.Text>
          </Flex>
        ),
    },
  ];

  const eventColumns: TableColumnsType<CollectionEvent> = [
    {
      key: 'time',
      title: text.columnTime,
      width: 170,
      render: (_, event) => <DateTime value={event.occurredAt} />,
    },
    {
      key: 'dataset',
      title: text.columnDataset,
      width: 110,
      render: (_, event) =>
        event.kind.startsWith('RECALCULAT')
          ? text.calculationWindow(event.targetKey ?? '')
          : event.kind.startsWith('INTERPRET')
            ? text.weeklySummary
            : datasetLabel(event.datasetKind),
    },
    {
      key: 'event',
      title: text.columnEvent,
      width: 120,
      render: (_, event) => <EventTag kind={event.kind} />,
    },
    {
      key: 'detail',
      title: text.columnDetail,
      render: (_, event) => (
        <Typography.Text style={{ fontSize: 13 }}>{eventDetail(event) || '—'}</Typography.Text>
      ),
    },
  ];

  return (
    <Flex vertical gap={16}>
      <SectionCard
        title={text.policyTitle}
        extra={
          <Button icon={<ReloadOutlined />} onClick={reload}>
            {text.refreshLabel}
          </Button>
        }
      >
        <Flex vertical gap={10}>
          {!data.schedulerEnabled && <Alert type="warning" showIcon title={text.schedulerOff} />}
          {data.policy === null ? (
            <Typography.Text>{text.policyOff}</Typography.Text>
          ) : (
            <Flex vertical gap={2}>
              <Typography.Text>
                <Tag color="success">{text.policyOnTag}</Tag>
                {text.policyOn}
              </Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {text.authorizedAt} <DateTime value={data.policy.authorizedAt} />
                {data.policy.reason === '' ? '' : ` · ${data.policy.reason}`}
              </Typography.Text>
            </Flex>
          )}
          <Typography.Text>{text.schedule(data.dailyAtUtc)}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {text.scheduleRules}
          </Typography.Text>
          <Flex gap={8} wrap>
            {data.policy === null && (
              <ActionModal<ReasonValues>
                trigger={{ label: text.enable, type: 'primary' }}
                title={text.enableTitle}
                consequence={text.enableConsequence}
                okText={actions.confirm}
                width={560}
                onSubmit={async (values) => {
                  const outcome = await enableDataCollection(
                    context,
                    storeId,
                    (values.reason ?? '').trim(),
                  );
                  if (!outcome.ok) return outcome.failure;
                  void message.success(text.enabled);
                  setLoaded({ kind: 'loaded', data: outcome.value });
                  return undefined;
                }}
              >
                {reasonField}
              </ActionModal>
            )}
            {data.policy !== null && (
              <ActionModal<ReasonValues>
                trigger={{ label: text.retire, danger: true }}
                title={text.retireTitle}
                consequence={text.retireConsequence}
                okText={actions.confirm}
                danger
                width={520}
                onSubmit={async (values) => {
                  if (data.policy === null) return undefined;
                  const outcome = await retireDataCollection(
                    context,
                    storeId,
                    (values.reason ?? '').trim(),
                    data.policy.version,
                  );
                  if (!outcome.ok) return outcome.failure;
                  void message.success(text.retired);
                  setLoaded({ kind: 'loaded', data: outcome.value });
                  return undefined;
                }}
              >
                {reasonField}
              </ActionModal>
            )}
          </Flex>
        </Flex>
      </SectionCard>

      <SectionCard title={text.jobsTitle}>
        {data.jobs.length === 0 ? (
          <EmptyState description={text.noJobs} />
        ) : (
          <Table<CollectionJob>
            rowKey="jobId"
            size="middle"
            columns={jobColumns}
            dataSource={[...data.jobs]}
            pagination={false}
            scroll={{ x: 1150 }}
          />
        )}
      </SectionCard>

      <SectionCard
        title={
          <Space size={4}>
            {text.calculationsTitle}
            <InfoTip title={text.calculationsHint} />
          </Space>
        }
      >
        <Flex gap={24} wrap>
          {data.calculations.map((calculation) => (
            <Flex key={calculation.window} vertical gap={2}>
              <Typography.Text type="secondary">
                {text.calculationWindow(calculation.window)}
              </Typography.Text>
              {calculation.latestPeriodEnd === null ? (
                <Typography.Text type="secondary">{text.notCalculated}</Typography.Text>
              ) : (
                <Typography.Text>
                  {text.calculatedUntil} <DateTime value={calculation.latestPeriodEnd} />
                </Typography.Text>
              )}
            </Flex>
          ))}
        </Flex>
      </SectionCard>

      <FeedFreshnessSection
        key={`freshness-${String(generation)}`}
        context={context}
        storeId={storeId}
      />

      <SectionCard title={text.eventsTitle}>
        {data.events.length === 0 ? (
          <EmptyState description={text.eventsEmpty} />
        ) : (
          <Table<CollectionEvent>
            rowKey="eventId"
            size="small"
            columns={eventColumns}
            dataSource={[...data.events]}
            pagination={false}
            scroll={{ x: 800 }}
          />
        )}
      </SectionCard>
    </Flex>
  );
}
