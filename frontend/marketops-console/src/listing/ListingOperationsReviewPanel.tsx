import { ReloadOutlined } from '@ant-design/icons';
import {
  App,
  Button,
  Card,
  Collapse,
  Flex,
  Form,
  Input,
  Select,
  Space,
  Table,
  Tabs,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  fetchListingExperience,
  fetchListingOperationsReview,
  recordListingExperience,
  type ListingExperienceApplication,
  type ListingOperationsReview,
  type ListingReviewReading,
  type ListingReviewRow,
} from '../api/listingConversion';
import { timezoneLabel } from '../format';
import { t } from '../i18n/zh/listing';
import { reviewText as text } from '../i18n/zh/listingReview';
import {
  DetailDrawer,
  EmptyState,
  FormDrawer,
  LoadingState,
  SectionCard,
  TechnicalDetails,
} from '../ui';
import {
  Code,
  Codes,
  Details,
  Hint,
  IdText,
  ListingProblem,
  RussianText,
  Stack,
  When,
  codeOptions,
  codeText,
} from './ListingCommon';
import { IdLookup } from './ListingManualPickers';

type ReadingKey = 'current' | 'daily' | 'weekly';

const READING_KINDS: Readonly<Record<ReadingKey, ListingReviewReading['kind']>> = {
  current: 'CURRENT_QUEUE',
  daily: 'DAILY_ACTION_BRIEF',
  weekly: 'WEEKLY_EVIDENCE_REVIEW',
};

/**
 * The current queue, the daily brief and the weekly review, read from one
 * snapshot.
 *
 * Each reading is a compact table; a row opens the listing's health,
 * responsibilities, actions and recalculation receipt beside it. Switching
 * store and recording experience open from the card's corner instead of
 * sitting on the page as forms.
 */
export function ListingOperationsReviewPanel({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element {
  const [bundle, setBundle] = useState<ListingOperationsReview>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [kind, setKind] = useState<ReadingKey>('current');
  const [loadedStoreId, setLoadedStoreId] = useState(storeId);
  const [generation, setGeneration] = useState(0);
  const [selected, setSelected] = useState<ListingReviewRow>();
  useEffect(() => {
    setLoadedStoreId(storeId);
  }, [storeId]);
  useEffect(() => {
    let active = true;
    setBundle(undefined);
    setFailure(undefined);
    void fetchListingOperationsReview(context, loadedStoreId).then((result) => {
      if (!active) return;
      if (result.ok) setBundle(result.value);
      else setFailure(result.failure);
    });
    return () => {
      active = false;
    };
  }, [context, loadedStoreId, generation]);
  return (
    <section aria-label={t('operationsReview')}>
      <SectionCard>
        <Stack>
          {/* Title and controls wrap on a narrow screen rather than squeezing the title. */}
          <Flex justify="space-between" align="center" gap={8} wrap>
            <Typography.Text strong style={{ fontSize: 16 }}>
              {t('operationsReview')}
            </Typography.Text>
            <Flex gap={8} wrap align="center">
              <Button
                icon={<ReloadOutlined />}
                onClick={() => {
                  setGeneration((value) => value + 1);
                }}
              >
                {t('refresh')}
              </Button>
              <IdLookup
                label={text.switchStore}
                title={text.switchStoreTitle}
                fieldLabel={t('operationsReviewStore')}
                initial={loadedStoreId}
                onOpenId={(id) => {
                  setLoadedStoreId(id);
                  setGeneration((value) => value + 1);
                }}
              />
              {bundle !== undefined && (
                <ExperienceDrawer context={context} reading={bundle.current} />
              )}
            </Flex>
          </Flex>
          <Typography.Text type="secondary">{t('operationsReviewBoundary')}</Typography.Text>
          {failure !== undefined && <ListingProblem failure={failure} />}
          {bundle === undefined && failure === undefined && <LoadingState rows={6} />}
          {bundle !== undefined && (
            <>
              <Typography.Text type="secondary">
                {text.storeLine} <IdText value={bundle.storeId} /> · {t('reviewTimezone')}{' '}
                {timezoneLabel(bundle.timezone)} · {t('feedbackSnapshot')}{' '}
                <When value={bundle.asOf} />
              </Typography.Text>
              <Tabs
                activeKey={kind}
                onChange={(key) => {
                  setKind(key as ReadingKey);
                }}
                items={(Object.keys(READING_KINDS) as ReadingKey[]).map((key) => ({
                  key,
                  label: `${codeText('readingKind', READING_KINDS[key])} (${String(
                    bundle[key].rows.length,
                  )})`,
                  children: <ReviewReading reading={bundle[key]} onOpen={setSelected} />,
                }))}
              />
            </>
          )}
        </Stack>
      </SectionCard>
      <DetailDrawer
        open={selected !== undefined}
        onClose={() => {
          setSelected(undefined);
        }}
        size="large"
        title={
          selected === undefined ? (
            text.detailTitle
          ) : (
            <Space wrap>
              <span>{selected.health.nativeListingKey}</span>
              <Code family="taskLane" code={selected.lane} />
              <Code family="healthState" code={selected.health.necessaryState} />
            </Space>
          )
        }
      >
        {selected !== undefined && <ReviewRowDetail row={selected} />}
      </DetailDrawer>
    </section>
  );
}

/** One listing of a reading: health, responsibilities, actions and the recalculation receipt. */
function ReviewRowDetail({ row }: { readonly row: ListingReviewRow }): React.JSX.Element {
  return (
    <div data-lane={row.lane}>
      <Stack>
        <Details
          items={[
            { key: 'version', label: t('healthVersion'), children: row.health.healthVersion },
            {
              key: 'affected',
              label: t('affectedSetShort'),
              children: (
                <Space size={4}>
                  <Code family="affectedSetState" code={row.health.affectedSetState} />
                  <Typography.Text type="secondary">
                    {row.health.affectedVariantCount} 个变体
                  </Typography.Text>
                </Space>
              ),
            },
            {
              key: 'source',
              label: t('sourceTime'),
              children: <When value={row.health.sourceTime} />,
            },
            {
              key: 'acquired',
              label: t('acquisitionTime'),
              children: <When value={row.health.acquisitionTime} />,
            },
            {
              key: 'computed',
              label: t('computedAt'),
              children: <When value={row.health.computedAt} />,
            },
          ]}
        />
        {row.responsibilities.length > 0 && (
          <Table
            size="middle"
            rowKey="taskId"
            pagination={false}
            dataSource={[...row.responsibilities]}
            aria-label={t('responsibilityTask')}
            columns={[
              {
                key: 'cause',
                title: t('responsibilityTask'),
                render: (_, task) => (
                  <Space size={4} wrap>
                    <Code family="taskLane" code={task.lane} />
                    <Code family="healthCondition" code={task.causeCode} />
                  </Space>
                ),
              },
              {
                key: 'state',
                title: t('state'),
                render: (_, task) => <Code family="taskState" code={task.taskState} />,
              },
              {
                key: 'origin',
                title: t('responsibilityOrigin'),
                render: (_, task) => <When value={task.status.firstRaisedAt} />,
              },
              {
                key: 'due',
                title: t('responsibilityActionDue'),
                render: (_, task) => <When value={task.status.actionDueAt} />,
              },
              {
                key: 'hold',
                title: t('dependencyHoldState'),
                render: (_, task) =>
                  task.status.dependencyHold === undefined ? (
                    '—'
                  ) : (
                    <Code family="dependencyHoldState" code={task.status.dependencyHold.state} />
                  ),
              },
            ]}
          />
        )}
        {row.actions.map((entry) => (
          <Card
            key={entry.action.id}
            size="small"
            type="inner"
            aria-label={t('actions')}
            title={
              <Space wrap>
                <span>{codeText('actionKind', entry.action.actionKind)}</span>
                <Code family="actionState" code={entry.action.state} />
                {entry.action.purposeCode === undefined ? (
                  <Typography.Text type="secondary">{t('undeclared')}</Typography.Text>
                ) : (
                  <Code family="actionPurpose" code={entry.action.purposeCode} />
                )}
              </Space>
            }
          >
            <Stack>
              <Details
                items={[
                  {
                    key: 'affected',
                    label: t('affectedSetShort'),
                    children: (
                      <Space size={4}>
                        <Code family="affectedSetState" code={entry.action.affectedSetState} />
                        <Typography.Text type="secondary">
                          {entry.action.affectedVariantCount} 个变体
                        </Typography.Text>
                      </Space>
                    ),
                  },
                  ...(entry.action.bindingGaps.length === 0
                    ? []
                    : [
                        {
                          key: 'gaps',
                          label: t('bindingGaps'),
                          children: <Codes family="bindingGap" codes={entry.action.bindingGaps} />,
                        },
                      ]),
                  ...(entry.command === undefined
                    ? []
                    : [
                        {
                          key: 'command',
                          label: t('descriptionCommand'),
                          children: (
                            <Space size={4} wrap>
                              <Code family="commandState" code={entry.command.state} />
                              {entry.command.failureCode !== undefined && (
                                <Code family="errorCode" code={entry.command.failureCode} />
                              )}
                            </Space>
                          ),
                        },
                      ]),
                ]}
              />
              {entry.action.targetText !== undefined && (
                <Collapse
                  size="small"
                  items={[
                    {
                      key: 'target',
                      label: t('targetText'),
                      children: <RussianText value={entry.action.targetText} />,
                    },
                  ]}
                />
              )}
              {entry.evaluation === undefined ? (
                <Typography.Text type="secondary">{t('noEvaluation')}</Typography.Text>
              ) : entry.evaluation.results.length === 0 ? (
                <Typography.Text type="secondary">{t('noEvaluationResults')}</Typography.Text>
              ) : (
                <Table
                  size="middle"
                  rowKey="id"
                  pagination={false}
                  dataSource={[...entry.evaluation.results]}
                  columns={[
                    {
                      key: 'node',
                      title: t('nodes'),
                      render: (_, result) => (
                        <Space size={4} wrap>
                          <span>{result.nodeCode}</span>
                          <Code family="evaluationStage" code={result.stage} />
                          <Typography.Text type="secondary">
                            {t('revisionShort')} {result.revisionNo}
                          </Typography.Text>
                        </Space>
                      ),
                    },
                    {
                      key: 'verdict',
                      title: t('nodeVerdictLabel'),
                      render: (_, result) => <Code family="nodeVerdict" code={result.verdict} />,
                    },
                    {
                      key: 'protection',
                      title: t('protection'),
                      render: (_, result) => (
                        <Code family="protectionVerdict" code={result.protectionVerdict} />
                      ),
                    },
                  ]}
                />
              )}
              <TechnicalDetails>
                <Space orientation="vertical" size={2}>
                  <IdText label={t('actionId')} value={entry.action.id} />
                  <IdText label={t('affectedSet')} value={entry.action.affectedSetDigest} />
                  <IdText label={t('commandId')} value={entry.command?.id} />
                  <IdText label={t('evaluationPlanDigest')} value={entry.evaluation?.planDigest} />
                  {entry.evaluation?.results.map((result) => (
                    <IdText key={result.id} label={t('resultId')} value={result.id} />
                  ))}
                </Space>
              </TechnicalDetails>
            </Stack>
          </Card>
        ))}
        {row.recalculation !== undefined && (
          <Details
            items={[
              {
                key: 'class',
                label: t('recalculationReceipt'),
                children: (
                  <Space size={4} wrap>
                    <Code family="recalculationClass" code={row.recalculation.triggerClass} />
                    <Code family="queueState" code={row.recalculation.state} />
                  </Space>
                ),
              },
              {
                key: 'measurements',
                label: t('recalculationMeasurements'),
                children: row.recalculation.measurementResultIds.length,
              },
              {
                key: 'bindings',
                label: t('recalculationBindings'),
                children: `${String(row.recalculation.bindingAssessedCount ?? 0)} / ${String(
                  row.recalculation.bindingInvalidatedCount ?? 0,
                )}`,
              },
            ]}
          />
        )}
      </Stack>
    </div>
  );
}

const REVIEW_COLUMNS: TableColumnsType<ListingReviewRow> = [
  {
    key: 'listing',
    title: t('listing'),
    render: (_, row) => <Typography.Text strong>{row.health.nativeListingKey}</Typography.Text>,
  },
  {
    key: 'lane',
    title: text.laneColumn,
    render: (_, row) => <Code family="taskLane" code={row.lane} />,
  },
  {
    key: 'health',
    title: text.healthColumn,
    render: (_, row) => <Code family="healthState" code={row.health.necessaryState} />,
  },
  {
    key: 'responsibilities',
    title: text.responsibilitiesColumn,
    align: 'right',
    render: (_, row) =>
      row.responsibilities.length === 0 ? '—' : text.count(row.responsibilities.length),
  },
  {
    key: 'actions',
    title: t('actions'),
    render: (_, row) =>
      row.actions.length === 0 ? (
        '—'
      ) : (
        <Space size={4} wrap>
          {row.actions.map((entry) => (
            <Code key={entry.action.id} family="actionState" code={entry.action.state} />
          ))}
        </Space>
      ),
  },
  {
    key: 'recalculation',
    title: t('recalculationReceipt'),
    render: (_, row) =>
      row.recalculation === undefined ? (
        '—'
      ) : (
        <Code family="queueState" code={row.recalculation.state} />
      ),
  },
  {
    key: 'computed',
    title: t('computedAt'),
    render: (_, row) => <When value={row.health.computedAt} />,
  },
];

function ReviewReading({
  reading,
  onOpen,
}: {
  readonly reading: ListingReviewReading;
  readonly onOpen: (row: ListingReviewRow) => void;
}): React.JSX.Element {
  return (
    <section data-reading={reading.kind}>
      <Stack>
        <Typography.Text type="secondary">
          {t('reviewPeriodStart')} {reading.periodStart} · {t('feedbackSnapshot')}{' '}
          <When value={reading.asOf} />
        </Typography.Text>
        {reading.rows.length === 0 ? (
          <EmptyState description={t('noReviewRows')} />
        ) : (
          <Table<ListingReviewRow>
            size="middle"
            rowKey={(row) => row.health.id}
            columns={REVIEW_COLUMNS}
            dataSource={[...reading.rows]}
            pagination={false}
            scroll={{ x: 'max-content' }}
            onRow={(row) =>
              ({
                'data-lane': row.lane,
                onClick: () => {
                  onOpen(row);
                },
                onKeyDown: (event: React.KeyboardEvent<HTMLElement>) => {
                  if (event.key === 'Enter' && event.target === event.currentTarget) onOpen(row);
                },
                tabIndex: 0,
                'aria-label': text.openRow(row.health.nativeListingKey),
                style: { cursor: 'pointer' },
              }) as React.HTMLAttributes<HTMLElement>
            }
          />
        )}
      </Stack>
    </section>
  );
}

interface ExperienceValues {
  readonly target?: string;
  readonly source?: string;
  readonly candidateKind?: string;
  readonly evidence?: string;
}

/**
 * Record that a stage result supports preparing a candidate on another listing.
 *
 * The target's earlier applications are listed under the form, so the person
 * sees what has already been recorded for it before adding more.
 */
function ExperienceDrawer({
  context,
  reading,
}: {
  readonly context: ConsoleRequest;
  readonly reading: ListingReviewReading;
}): React.JSX.Element {
  const { message } = App.useApp();
  const sources = useMemo(
    () =>
      reading.rows.flatMap((row) =>
        row.actions.flatMap((entry) =>
          (entry.evaluation?.results ?? []).map((result) => ({
            actionId: entry.action.id,
            resultId: result.id,
            label: `${row.health.nativeListingKey} · ${result.nodeCode} · ${codeText(
              'evaluationStage',
              result.stage,
            )} · ${t('revisionShort')} ${String(result.revisionNo)}`,
          })),
        ),
      ),
    [reading],
  );
  const unavailable = reading.rows.length === 0 || sources.length === 0;
  const firstTarget = reading.rows[0]?.health.platformListingId;
  const firstSource = sources[0];
  return (
    <FormDrawer<ExperienceValues>
      trigger={{
        label: t('experienceApply'),
        type: 'primary',
        disabled: unavailable,
        disabledReason: t('experienceUnavailable'),
      }}
      title={t('experience')}
      intro={<Hint>{t('experienceBoundary')}</Hint>}
      submitText={t('experienceApply')}
      initialValues={{
        ...(firstTarget === undefined ? {} : { target: firstTarget }),
        ...(firstSource === undefined
          ? {}
          : { source: `${firstSource.actionId}|${firstSource.resultId}` }),
        candidateKind: 'CONTENT_DESCRIPTION',
      }}
      onSubmit={async (values) => {
        const [sourceActionId, sourceResultId] = (values.source ?? '').split('|');
        if (sourceActionId === undefined || sourceResultId === undefined) return undefined;
        const result = await recordListingExperience(context, {
          sourceActionId,
          sourceResultId,
          targetListingId: values.target ?? '',
          candidateKind: values.candidateKind ?? 'CONTENT_DESCRIPTION',
          applicabilityEvidenceReference: (values.evidence ?? '').trim(),
        });
        if (!result.ok) return result.failure;
        void message.success(t('done'));
        return undefined;
      }}
    >
      <Form.Item
        name="target"
        label={t('experienceTarget')}
        rules={[{ required: true, message: text.targetRequired }]}
      >
        <Select
          options={reading.rows.map((row) => ({
            value: row.health.platformListingId,
            label: row.health.nativeListingKey,
          }))}
        />
      </Form.Item>
      <Form.Item
        name="source"
        label={t('experienceSource')}
        rules={[{ required: true, message: text.sourceRequired }]}
      >
        <Select
          options={sources.map((value) => ({
            value: `${value.actionId}|${value.resultId}`,
            label: value.label,
          }))}
        />
      </Form.Item>
      <Form.Item name="candidateKind" label={t('experienceCandidateKind')}>
        <Select
          options={codeOptions('candidateKind', [
            'CONTENT_DESCRIPTION',
            'OFFICIAL_PROMOTION_PARTICIPATION',
            'SELLER_DIRECT_DISCOUNT',
          ])}
        />
      </Form.Item>
      <Form.Item
        name="evidence"
        label={t('experienceEvidence')}
        rules={[{ required: true, whitespace: true, message: text.evidenceRequired }]}
      >
        <Input.TextArea maxLength={512} showCount autoSize={{ minRows: 2, maxRows: 4 }} />
      </Form.Item>
      <Form.Item noStyle dependencies={['target']}>
        {({ getFieldValue }) => (
          <ExperienceHistory context={context} target={String(getFieldValue('target') ?? '')} />
        )}
      </Form.Item>
    </FormDrawer>
  );
}

/** What has already been recorded for one target listing. */
function ExperienceHistory({
  context,
  target,
}: {
  readonly context: ConsoleRequest;
  readonly target: string;
}): React.JSX.Element | null {
  const [history, setHistory] = useState<readonly ListingExperienceApplication[]>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    let active = true;
    setHistory(undefined);
    setFailure(undefined);
    if (target !== '')
      void fetchListingExperience(context, target).then((result) => {
        if (!active) return;
        if (result.ok) setHistory(result.value);
        else setFailure(result.failure);
      });
    return () => {
      active = false;
    };
  }, [context, target]);
  if (target === '') return null;
  return (
    <section aria-label={text.historyTitle}>
      <Stack>
        <Typography.Text strong>{text.historyTitle}</Typography.Text>
        {failure !== undefined && <ListingProblem failure={failure} />}
        {history === undefined && failure === undefined && <LoadingState rows={2} />}
        {history?.length === 0 && <EmptyState description={text.noHistory} />}
        {history !== undefined && history.length > 0 && (
          <Table
            size="small"
            rowKey="id"
            pagination={false}
            dataSource={[...history]}
            scroll={{ x: 'max-content' }}
            columns={[
              {
                key: 'kind',
                title: t('experienceCandidateKind'),
                render: (_, item) => <Code family="candidateKind" code={item.candidateKind} />,
              },
              {
                key: 'stage',
                title: t('evaluationStage'),
                render: (_, item) => <Code family="evaluationStage" code={item.sourceStage} />,
              },
              {
                key: 'verdict',
                title: t('nodeVerdictLabel'),
                render: (_, item) => <Code family="nodeVerdict" code={item.sourceVerdict} />,
              },
              {
                key: 'applicability',
                title: t('applicability'),
                render: (_, item) => (
                  <Code family="applicabilityState" code={item.applicabilityState} />
                ),
              },
              {
                key: 'evidence',
                title: t('experienceEvidence'),
                dataIndex: 'applicabilityEvidenceReference',
              },
              {
                key: 'recorded',
                title: t('recordedAt'),
                render: (_, item) => <When value={item.recordedAt} />,
              },
            ]}
          />
        )}
      </Stack>
    </section>
  );
}
