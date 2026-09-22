import { ReloadOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  Col,
  Collapse,
  Form,
  Input,
  Row,
  Select,
  Space,
  Table,
  Tabs,
  Typography,
} from 'antd';
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
import { EmptyState, LoadingState, SectionCard, TechnicalDetails } from '../ui';
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

type ReadingKey = 'current' | 'daily' | 'weekly';

const READING_KINDS: Readonly<Record<ReadingKey, ListingReviewReading['kind']>> = {
  current: 'CURRENT_QUEUE',
  daily: 'DAILY_ACTION_BRIEF',
  weekly: 'WEEKLY_EVIDENCE_REVIEW',
};

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
  const [storeInput, setStoreInput] = useState(storeId);
  const [loadedStoreId, setLoadedStoreId] = useState(storeId);
  const [generation, setGeneration] = useState(0);
  useEffect(() => {
    setStoreInput(storeId);
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
      <Stack>
        <SectionCard
          title={t('operationsReview')}
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
            <Hint>{t('operationsReviewBoundary')}</Hint>
            <Form
              layout="inline"
              aria-label={t('operationsReviewLoad')}
              onFinish={() => {
                if (storeInput.trim() === '') return;
                setLoadedStoreId(storeInput);
                setGeneration((value) => value + 1);
              }}
            >
              <Form.Item label={t('operationsReviewStore')} required>
                <Input
                  required
                  style={{ width: 340 }}
                  value={storeInput}
                  onChange={(event) => {
                    setStoreInput(event.target.value);
                  }}
                />
              </Form.Item>
              <Button type="primary" htmlType="submit">
                {t('operationsReviewLoad')}
              </Button>
            </Form>
            {failure !== undefined && <ListingProblem failure={failure} />}
            {bundle === undefined && failure === undefined && <LoadingState rows={6} />}
            {bundle !== undefined && (
              <>
                <Details
                  items={[
                    {
                      key: 'tz',
                      label: t('reviewTimezone'),
                      children: timezoneLabel(bundle.timezone),
                    },
                    {
                      key: 'asOf',
                      label: t('feedbackSnapshot'),
                      children: <When value={bundle.asOf} />,
                    },
                  ]}
                />
                <Tabs
                  activeKey={kind}
                  onChange={(key) => {
                    setKind(key as ReadingKey);
                  }}
                  items={(Object.keys(READING_KINDS) as ReadingKey[]).map((key) => ({
                    key,
                    label: codeText('readingKind', READING_KINDS[key]),
                    children: <ReviewReading reading={bundle[key]} />,
                  }))}
                />
              </>
            )}
          </Stack>
        </SectionCard>
        {bundle !== undefined && <ExperienceWorkbench context={context} reading={bundle.current} />}
      </Stack>
    </section>
  );
}

function ReviewRow({ row }: { readonly row: ListingReviewRow }): React.JSX.Element {
  return (
    <Card
      size="small"
      data-lane={row.lane}
      title={
        <Space wrap>
          <span>{row.health.nativeListingKey}</span>
          <Code family="taskLane" code={row.lane} />
          <Code family="healthState" code={row.health.necessaryState} />
        </Space>
      }
    >
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
    </Card>
  );
}

function ReviewReading({ reading }: { readonly reading: ListingReviewReading }): React.JSX.Element {
  return (
    <section data-reading={reading.kind}>
      <Stack>
        <Typography.Text type="secondary">
          {t('reviewPeriodStart')} {reading.periodStart} ·{t('feedbackSnapshot')}{' '}
          <When value={reading.asOf} />
        </Typography.Text>
        {reading.rows.length === 0 && <EmptyState description={t('noReviewRows')} />}
        {reading.rows.map((row) => (
          <ReviewRow key={row.health.id} row={row} />
        ))}
      </Stack>
    </section>
  );
}

function ExperienceWorkbench({
  context,
  reading,
}: {
  readonly context: ConsoleRequest;
  readonly reading: ListingReviewReading;
}): React.JSX.Element {
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
  const [target, setTarget] = useState(reading.rows[0]?.health.platformListingId ?? '');
  const [source, setSource] = useState(
    sources[0] === undefined ? '' : `${sources[0].actionId}|${sources[0].resultId}`,
  );
  const [candidateKind, setCandidateKind] = useState('CONTENT_DESCRIPTION');
  const [evidence, setEvidence] = useState('');
  const [history, setHistory] = useState<readonly ListingExperienceApplication[]>([]);
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    setHistory([]);
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
  const unavailable = reading.rows.length === 0 || sources.length === 0;
  return (
    <section aria-label={t('experience')}>
      <SectionCard title={t('experience')}>
        <Stack>
          <Hint>{t('experienceBoundary')}</Hint>
          {failure === undefined ? null : <ListingProblem failure={failure} />}
          {unavailable && <EmptyState description={t('experienceUnavailable')} />}
          <Form
            layout="vertical"
            disabled={busy || unavailable}
            onFinish={() => {
              const [sourceActionId, sourceResultId] = source.split('|');
              if (sourceActionId === undefined || sourceResultId === undefined) return;
              setBusy(true);
              setFailure(undefined);
              void recordListingExperience(context, {
                sourceActionId,
                sourceResultId,
                targetListingId: target,
                candidateKind,
                applicabilityEvidenceReference: evidence,
              })
                .then(async (result) => {
                  if (!result.ok) {
                    setFailure(result.failure);
                    return;
                  }
                  const updated = await fetchListingExperience(context, target);
                  if (updated.ok) setHistory(updated.value);
                  else setFailure(updated.failure);
                })
                .finally(() => {
                  setBusy(false);
                });
            }}
          >
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item label={t('experienceTarget')}>
                  <Select<string | undefined>
                    value={target === '' ? undefined : target}
                    onChange={(next) => {
                      setTarget(next ?? '');
                    }}
                    options={reading.rows.map((row) => ({
                      value: row.health.platformListingId,
                      label: row.health.nativeListingKey,
                    }))}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={10}>
                <Form.Item label={t('experienceSource')}>
                  <Select<string | undefined>
                    value={source === '' ? undefined : source}
                    onChange={(next) => {
                      setSource(next ?? '');
                    }}
                    options={sources.map((value) => ({
                      value: `${value.actionId}|${value.resultId}`,
                      label: value.label,
                    }))}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={6}>
                <Form.Item label={t('experienceCandidateKind')}>
                  <Select
                    value={candidateKind}
                    onChange={setCandidateKind}
                    options={codeOptions('candidateKind', [
                      'CONTENT_DESCRIPTION',
                      'OFFICIAL_PROMOTION_PARTICIPATION',
                      'SELLER_DIRECT_DISCOUNT',
                    ])}
                  />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item label={t('experienceEvidence')} required>
              <Input.TextArea
                required
                maxLength={512}
                autoSize={{ minRows: 2, maxRows: 4 }}
                value={evidence}
                onChange={(event) => {
                  setEvidence(event.target.value);
                }}
              />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              {t('experienceApply')}
            </Button>
          </Form>
          {history.length > 0 && (
            <Table
              size="middle"
              rowKey="id"
              pagination={false}
              dataSource={[...history]}
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
      </SectionCard>
    </section>
  );
}
