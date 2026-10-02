import { PlusOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Flex,
  Form,
  Input,
  Segmented,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleOutcome, ConsoleRequest } from '../api/console';
import type { Batch, Containment, RecalculationEntry } from '../api/listingConversion';
import {
  addBatchMember,
  attestContainment,
  closeBatch,
  createBatch,
  fetchBatches,
  fetchContainments,
  fetchRecalculationQueue,
  reenableContainment,
  stopScope,
} from '../api/listingConversion';
import { dialog } from '../i18n/zh/common';
import { t } from '../i18n/zh/listing';
import { governanceText as text } from '../i18n/zh/listingGovernance';
import {
  ActionModal,
  ConfirmButton,
  EmptyState,
  InfoTip,
  InlineInputPopover,
  LoadingState,
  SectionCard,
  TechnicalDetails,
  idRules,
  useSearchParam,
} from '../ui';
import type { SubmitOutcome } from '../ui';
import { Code, IdText, ListingProblem, Stack, When, codeOptions } from './ListingCommon';

export interface ListingGovernancePanelProps {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}

/** The governance view kept in the address bar, so it survives a reload. */
const VIEW_KEY = 'gview';
type View = 'containments' | 'batches' | 'recalculation';

function readView(raw: string | undefined): View {
  return raw === 'batches' || raw === 'recalculation' ? raw : 'containments';
}

interface StopValues {
  readonly platformListingId?: string;
  readonly causeClass?: string;
  readonly causeOwner?: string;
  readonly reason?: string;
  readonly evidence?: string;
}

interface BatchValues {
  readonly storeId?: string;
  readonly code?: string;
}

const CAUSE_CLASSES = [
  'LOCAL_COST',
  'SHARED_VERSION',
  'PATH_INTEGRITY',
  'SAFETY_FAILURE',
  'PLATFORM_INCIDENT',
] as const;
const CAUSE_OWNERS = ['OWNER', 'TECH_DATA', 'OPS_LEAD', 'MARKETPLACE_OPERATOR'] as const;

/**
 * Bounded batches, containment and the recalculation queue.
 *
 * Stopping is the fastest control on the page and needs one person and one
 * reason, so its button stays in view whichever list is shown. Re-enabling
 * needs two different people and the database counts them. Every action asks
 * for its own values: an attestation never reuses the evidence typed for a
 * stop, and each batch takes its own action number.
 */
export function ListingGovernancePanel({
  context,
  storeId,
}: ListingGovernancePanelProps): React.JSX.Element {
  const { message } = App.useApp();
  const [rawView, setRawView] = useSearchParam(VIEW_KEY);
  const view = readView(rawView);
  const [batches, setBatches] = useState<readonly Batch[] | undefined>(undefined);
  const [containments, setContainments] = useState<readonly Containment[] | undefined>(undefined);
  const [queue, setQueue] = useState<readonly RecalculationEntry[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [activeOnly, setActiveOnly] = useState(true);

  useEffect(() => {
    let active = true;
    void Promise.all([
      fetchBatches(context),
      fetchContainments(context, activeOnly),
      fetchRecalculationQueue(context),
    ]).then(([batchOutcome, containmentOutcome, queueOutcome]) => {
      if (!active) return;
      const first = [batchOutcome, containmentOutcome, queueOutcome].find((outcome) => !outcome.ok);
      setFailure(first?.ok === false ? first.failure : undefined);
      if (batchOutcome.ok) setBatches(batchOutcome.value);
      if (containmentOutcome.ok) setContainments(containmentOutcome.value);
      if (queueOutcome.ok) setQueue(queueOutcome.value);
    });
    return () => {
      active = false;
    };
  }, [context, generation, activeOnly]);

  const reload = (): void => {
    setGeneration((value) => value + 1);
  };

  /** A dialog's or popover's action: a failure stays where it was raised, success reloads. */
  async function submit<T>(operation: Promise<ConsoleOutcome<T>>): Promise<SubmitOutcome> {
    const outcome = await operation;
    if (!outcome.ok) return outcome.failure;
    void message.success(t('done'));
    setFailure(undefined);
    reload();
    return undefined;
  }

  /** A one-click action confirmed in a popover; its failure is shown above the list. */
  async function run<T>(operation: Promise<ConsoleOutcome<T>>): Promise<void> {
    const outcome = await submit(operation);
    if (outcome !== undefined) setFailure(outcome);
  }

  const stop = (
    <ActionModal<StopValues>
      trigger={{ label: t('stopTitle'), type: 'primary', danger: true, icon: <StopOutlined /> }}
      title={t('stopTitle')}
      okText={text.stopOk}
      danger
      initialValues={{ causeClass: 'SAFETY_FAILURE', causeOwner: 'OWNER' }}
      onSubmit={(values) => {
        const listingId = (values.platformListingId ?? '').trim();
        return submit(
          stopScope(
            context,
            listingId === '' ? 'ORGANIZATION' : 'LISTING',
            listingId === '' ? undefined : listingId,
            values.causeClass ?? 'SAFETY_FAILURE',
            values.causeOwner ?? 'OWNER',
            (values.reason ?? '').trim(),
            (values.evidence ?? '').trim(),
          ),
        );
      }}
    >
      <Form.Item
        name="platformListingId"
        label={t('stopListingId')}
        extra={t('stopListingHelp')}
        rules={idRules(t('listingIdInput'), false)}
      >
        <Input placeholder={dialog.optional} autoFocus />
      </Form.Item>
      <Form.Item noStyle dependencies={['platformListingId']}>
        {({ getFieldValue }) => {
          const whole = String(getFieldValue('platformListingId') ?? '').trim() === '';
          return (
            <Alert
              type={whole ? 'error' : 'warning'}
              showIcon
              title={whole ? text.stopConsequenceOrganization : text.stopConsequenceListing}
              style={{ marginBottom: 16 }}
            />
          );
        }}
      </Form.Item>
      <Flex gap={12} wrap>
        <Form.Item name="causeClass" label={t('cause')} style={{ minWidth: 200, flex: 1 }}>
          <Select options={codeOptions('containmentCauseClass', CAUSE_CLASSES)} />
        </Form.Item>
        <Form.Item name="causeOwner" label={t('causeOwner')} style={{ minWidth: 200, flex: 1 }}>
          <Select options={codeOptions('causeOwnerRole', CAUSE_OWNERS)} />
        </Form.Item>
      </Flex>
      <Form.Item
        name="reason"
        label={t('reason')}
        rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
      >
        <Input.TextArea rows={2} maxLength={500} showCount placeholder={dialog.reasonPlaceholder} />
      </Form.Item>
      <Form.Item name="evidence" label={t('evidence')}>
        <Input maxLength={512} placeholder={dialog.optional} />
      </Form.Item>
    </ActionModal>
  );

  const containmentColumns: TableColumnsType<Containment> = [
    {
      key: 'scope',
      title: t('scope'),
      render: (_, containment) => (
        <Space size={4} wrap>
          <Code family="containmentScope" code={containment.scopeKind} />
          {containment.platformListingId !== undefined && (
            <IdText value={containment.platformListingId} />
          )}
        </Space>
      ),
    },
    {
      key: 'cause',
      title: t('cause'),
      render: (_, containment) => (
        <Space size={4} wrap>
          <Code family="containmentCauseClass" code={containment.causeClass} />
          <Code family="causeOwnerRole" code={containment.causeOwnerRoleCode} />
        </Space>
      ),
    },
    {
      key: 'state',
      title: t('state'),
      render: (_, containment) => <Code family="containmentState" code={containment.state} />,
    },
    {
      key: 'stoppedAt',
      title: text.stoppedAt,
      render: (_, containment) => <When value={containment.stoppedAt} />,
    },
    {
      key: 'reason',
      title: t('reason'),
      render: (_, containment) => (
        <Typography.Text style={{ maxWidth: 280 }} ellipsis={{ tooltip: containment.reason }}>
          {containment.reason}
        </Typography.Text>
      ),
    },
    {
      key: 'attestations',
      title: text.attestations,
      render: (_, containment) =>
        containment.attestations.length === 0 ? (
          <Typography.Text type="secondary">{text.noAttestations}</Typography.Text>
        ) : (
          <Space size={4} wrap>
            {containment.attestations.map((attestation) => (
              <Code
                key={`${attestation.attestationKind}:${attestation.actorUserId}`}
                family="attestationKind"
                code={attestation.attestationKind}
              />
            ))}
          </Space>
        ),
    },
    {
      key: 'actions',
      title: t('actions'),
      render: (_, containment) =>
        containment.state === 'ACTIVE' ? (
          <Space size={[8, 8]} wrap>
            <InlineInputPopover
              trigger={{ label: t('attestRepair'), size: 'small' }}
              title={text.attestRepairTitle}
              placeholder={text.evidencePlaceholder}
              maxLength={512}
              okText={t('submit')}
              onSubmit={(value) =>
                submit(attestContainment(context, containment.id, 'REPAIR_ATTESTATION', value))
              }
            />
            <InlineInputPopover
              trigger={{ label: t('consent'), size: 'small' }}
              title={text.consentTitle}
              placeholder={text.evidencePlaceholder}
              maxLength={512}
              okText={t('submit')}
              onSubmit={(value) =>
                submit(attestContainment(context, containment.id, 'BUSINESS_CONSENT', value))
              }
            />
            <ConfirmButton
              type="primary"
              size="small"
              title={text.reenableTitle}
              description={text.reenableHelp}
              onConfirm={() => run(reenableContainment(context, containment.id))}
            >
              {t('reenable')}
            </ConfirmButton>
          </Space>
        ) : null,
    },
  ];

  const batchColumns: TableColumnsType<Batch> = [
    {
      key: 'code',
      title: t('batchCode'),
      render: (_, batch) => <Typography.Text strong>{batch.batchCode}</Typography.Text>,
    },
    {
      key: 'state',
      title: t('state'),
      render: (_, batch) => <Code family="batchState" code={batch.state} />,
    },
    {
      key: 'members',
      title: t('members'),
      align: 'right',
      render: (_, batch) => text.memberCount(batch.members.length),
    },
    {
      key: 'store',
      title: t('batchStoreId'),
      render: (_, batch) => <IdText value={batch.storeId} />,
    },
    {
      key: 'actions',
      title: t('actions'),
      render: (_, batch) =>
        batch.state === 'OPEN' ? (
          <Space size={[8, 8]} wrap>
            <InlineInputPopover
              trigger={{ label: t('addMember'), size: 'small' }}
              title={text.addMemberTitle}
              placeholder={t('actionIdInput')}
              maxLength={64}
              okText={t('addMember')}
              onSubmit={(value) => submit(addBatchMember(context, batch.id, value))}
            />
            <ConfirmButton
              danger
              size="small"
              title={text.closeBatchTitle}
              description={text.closeBatchHelp}
              onConfirm={() => run(closeBatch(context, batch.id))}
            >
              {t('closeBatch')}
            </ConfirmButton>
          </Space>
        ) : null,
    },
  ];

  const queueColumns: TableColumnsType<RecalculationEntry> = [
    {
      key: 'listing',
      title: t('listing'),
      render: (_, entry) => <IdText value={entry.platformListingId} />,
    },
    {
      key: 'state',
      title: t('state'),
      render: (_, entry) => <Code family="queueState" code={entry.state} />,
    },
    {
      key: 'target',
      title: t('target'),
      render: (_, entry) => (
        <Space size={4}>
          <Code family="recalculationClass" code={entry.triggerClass} />
          <Typography.Text type="secondary">
            {entry.targetMinutes} {t('minutes')}
          </Typography.Text>
        </Space>
      ),
    },
    {
      key: 'enqueued',
      title: t('enqueued'),
      render: (_, entry) => <When value={entry.acceptedAt} />,
    },
    {
      key: 'latency',
      title: t('latency'),
      align: 'right',
      render: (_, entry) => (
        <Space size={4}>
          <span>
            {entry.latencySeconds === undefined ? '—' : `${String(entry.latencySeconds)} 秒`}
          </span>
          <Tag color={entry.withinTarget ? 'success' : 'error'}>
            {entry.withinTarget ? t('withinTarget') : t('overTarget')}
          </Tag>
        </Space>
      ),
    },
  ];

  let body: React.ReactNode;
  if (view === 'containments') {
    body =
      containments === undefined ? (
        failure === undefined ? (
          <LoadingState />
        ) : null
      ) : containments.length === 0 ? (
        <EmptyState description={t('noContainments')} />
      ) : (
        <Table<Containment>
          size="middle"
          rowKey="id"
          columns={containmentColumns}
          dataSource={[...containments]}
          pagination={false}
          scroll={{ x: 'max-content' }}
          onRow={(containment) =>
            ({
              'data-containment': containment.id,
              'data-containment-state': containment.state,
            }) as React.HTMLAttributes<HTMLElement>
          }
          expandable={{
            expandedRowRender: (containment) => (
              <TechnicalDetails>
                <Space orientation="vertical" size={2}>
                  <IdText label={t('containmentId')} value={containment.id} />
                  <IdText label={t('stoppedBy')} value={containment.stoppedByUserId} />
                  <IdText label={t('listing')} value={containment.platformListingId} />
                  {containment.attestations.map((attestation) => (
                    <IdText
                      key={`${attestation.attestationKind}:${attestation.actorUserId}`}
                      label={t('attester')}
                      value={attestation.actorUserId}
                    />
                  ))}
                </Space>
              </TechnicalDetails>
            ),
          }}
        />
      );
  } else if (view === 'batches') {
    body =
      batches === undefined ? (
        failure === undefined ? (
          <LoadingState />
        ) : null
      ) : batches.length === 0 ? (
        <EmptyState description={t('noBatches')} />
      ) : (
        <Table<Batch>
          size="middle"
          rowKey="id"
          columns={batchColumns}
          dataSource={[...batches]}
          pagination={false}
          scroll={{ x: 'max-content' }}
          onRow={(batch) => ({ 'data-batch': batch.id }) as React.HTMLAttributes<HTMLElement>}
          expandable={{
            expandedRowRender: (batch) => (
              <Stack>
                {batch.members.length === 0 ? (
                  <Typography.Text type="secondary">{text.noMembers}</Typography.Text>
                ) : (
                  <Table
                    size="small"
                    rowKey="actionId"
                    pagination={false}
                    dataSource={[...batch.members]}
                    columns={[
                      {
                        key: 'seq',
                        title: t('sequence'),
                        width: 80,
                        render: (_, member) => `#${String(member.sequenceNo)}`,
                      },
                      {
                        key: 'action',
                        title: t('actions'),
                        render: (_, member) => <IdText value={member.actionId} />,
                      },
                      {
                        key: 'state',
                        title: t('state'),
                        render: (_, member) => (
                          <Code family="actionState" code={member.actionState} />
                        ),
                      },
                      {
                        key: 'membership',
                        title: t('membership'),
                        render: (_, member) => (
                          <Code family="membershipState" code={member.membershipState} />
                        ),
                      },
                    ]}
                  />
                )}
                <TechnicalDetails>
                  <IdText label={t('batchId')} value={batch.id} />
                </TechnicalDetails>
              </Stack>
            ),
          }}
        />
      );
  } else {
    body =
      queue === undefined ? (
        failure === undefined ? (
          <LoadingState />
        ) : null
      ) : queue.length === 0 ? (
        <EmptyState description={t('noRecalculation')} />
      ) : (
        <Table<RecalculationEntry>
          size="middle"
          rowKey="id"
          columns={queueColumns}
          dataSource={[...queue]}
          pagination={false}
          scroll={{ x: 'max-content' }}
          onRow={(entry) =>
            ({
              'data-within-target': String(entry.withinTarget),
            }) as React.HTMLAttributes<HTMLElement>
          }
        />
      );
  }

  return (
    <section
      aria-label={t('tabGovernance')}
      data-state={containments === undefined ? 'loading' : 'loaded'}
    >
      <SectionCard>
        <Stack>
          {/* The view switch and its controls wrap onto two lines on a narrow screen
              instead of squeezing the switch out of a card header. */}
          <Flex justify="space-between" align="center" gap={8} wrap>
            <Space size={4} align="center">
              <Segmented<View>
                value={view}
                options={[
                  { value: 'containments', label: text.viewContainments },
                  { value: 'batches', label: text.viewBatches },
                  { value: 'recalculation', label: text.viewRecalculation },
                ]}
                onChange={(next) => {
                  setRawView(next === 'containments' ? undefined : next);
                }}
              />
              {view === 'containments' && <InfoTip title={text.stopTip} />}
            </Space>
            <Flex gap={8} wrap align="center">
              {view === 'containments' && (
                <Space size={4}>
                  <Switch
                    size="small"
                    checked={activeOnly}
                    onChange={setActiveOnly}
                    aria-label={t('activeOnly')}
                  />
                  <Typography.Text type="secondary">{t('activeOnly')}</Typography.Text>
                </Space>
              )}
              <Button icon={<ReloadOutlined />} onClick={reload}>
                {t('refresh')}
              </Button>
              {view === 'batches' && (
                <ActionModal<BatchValues>
                  trigger={{ label: t('createBatch'), icon: <PlusOutlined /> }}
                  title={t('createBatch')}
                  consequence={text.createBatchConsequence}
                  okText={t('createBatch')}
                  initialValues={{ storeId }}
                  onSubmit={(values) =>
                    submit(
                      createBatch(
                        context,
                        (values.storeId ?? '').trim(),
                        (values.code ?? '').trim(),
                      ),
                    )
                  }
                >
                  <Form.Item
                    name="storeId"
                    label={t('batchStoreId')}
                    rules={idRules(t('batchStoreId'))}
                  >
                    <Input />
                  </Form.Item>
                  <Form.Item
                    name="code"
                    label={t('batchCode')}
                    rules={[{ required: true, whitespace: true, message: text.codeRequired }]}
                  >
                    <Input maxLength={64} autoFocus />
                  </Form.Item>
                </ActionModal>
              )}
              {stop}
            </Flex>
          </Flex>
          {failure !== undefined && <ListingProblem failure={failure} />}
          {body}
        </Stack>
      </SectionCard>
    </section>
  );
}
