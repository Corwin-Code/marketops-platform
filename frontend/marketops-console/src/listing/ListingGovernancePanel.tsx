import { ReloadOutlined } from '@ant-design/icons';
import {
  App,
  Button,
  Card,
  Col,
  Form,
  Input,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
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
import { t } from '../i18n/zh/listing';
import { ConfirmButton, EmptyState, LoadingState, SectionCard, TechnicalDetails } from '../ui';
import { Code, Hint, IdText, ListingProblem, Stack, When, codeOptions } from './ListingCommon';

export interface ListingGovernancePanelProps {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}

/**
 * Bounded batches, containment and the recalculation queue.
 *
 * Stopping is the fastest control on the page and needs one person and one
 * reason. Re-enabling needs two different people and the database counts them.
 */
export function ListingGovernancePanel({
  context,
  storeId,
}: ListingGovernancePanelProps): React.JSX.Element {
  const { message } = App.useApp();
  const [batches, setBatches] = useState<readonly Batch[] | undefined>(undefined);
  const [containments, setContainments] = useState<readonly Containment[] | undefined>(undefined);
  const [queue, setQueue] = useState<readonly RecalculationEntry[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [batchCode, setBatchCode] = useState('');
  const [batchStoreId, setBatchStoreId] = useState(storeId);
  const [memberAction, setMemberAction] = useState('');
  const [stopListing, setStopListing] = useState('');
  const [causeClass, setCauseClass] = useState('SAFETY_FAILURE');
  const [causeOwner, setCauseOwner] = useState('OWNER');
  const [reason, setReason] = useState('');
  const [evidence, setEvidence] = useState('');
  const [activeOnly, setActiveOnly] = useState(true);
  const [busy, setBusy] = useState<string | undefined>(undefined);

  useEffect(() => {
    setBatchStoreId(storeId);
  }, [storeId]);

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

  const settle = (outcome: { readonly ok: boolean; readonly failure?: ConsoleFailure }): void => {
    setBusy(undefined);
    if (outcome.ok) {
      void message.success(t('done'));
      setFailure(undefined);
      setGeneration((value) => value + 1);
    } else if (outcome.failure !== undefined) {
      setFailure(outcome.failure);
    }
  };
  const refreshButton = (
    <Button
      icon={<ReloadOutlined />}
      onClick={() => {
        setGeneration((value) => value + 1);
      }}
    >
      {t('refresh')}
    </Button>
  );
  const stopWhole = stopListing === '';

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

  return (
    <section
      aria-label={t('tabGovernance')}
      data-state={containments === undefined ? 'loading' : 'loaded'}
    >
      <Stack>
        {failure !== undefined && <ListingProblem failure={failure} />}

        <SectionCard title={t('containments')} extra={refreshButton}>
          <Stack>
            <Card size="small" type="inner" title={t('stopTitle')}>
              <Hint>{t('stopHelp')}</Hint>
              {/* No submit on Enter: stopping must always pass the confirmation. */}
              <div aria-label={t('stop')} role="form">
                <Form layout="vertical" component={false}>
                  <Row gutter={16}>
                    <Col xs={24} md={12}>
                      <Form.Item label={t('stopListingId')} extra={t('stopListingHelp')}>
                        <Input
                          value={stopListing}
                          onChange={(e) => {
                            setStopListing(e.target.value);
                          }}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={6}>
                      <Form.Item label={t('cause')}>
                        <Select
                          value={causeClass}
                          onChange={setCauseClass}
                          options={codeOptions('containmentCauseClass', [
                            'LOCAL_COST',
                            'SHARED_VERSION',
                            'PATH_INTEGRITY',
                            'SAFETY_FAILURE',
                            'PLATFORM_INCIDENT',
                          ])}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={6}>
                      <Form.Item label={t('causeOwner')}>
                        <Select
                          value={causeOwner}
                          onChange={setCauseOwner}
                          options={codeOptions('causeOwnerRole', [
                            'OWNER',
                            'TECH_DATA',
                            'OPS_LEAD',
                            'MARKETPLACE_OPERATOR',
                          ])}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={12}>
                      <Form.Item label={t('reason')}>
                        <Input
                          value={reason}
                          onChange={(e) => {
                            setReason(e.target.value);
                          }}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={12}>
                      <Form.Item label={t('evidence')} extra={t('evidenceSharedHelp')}>
                        <Input
                          value={evidence}
                          onChange={(e) => {
                            setEvidence(e.target.value);
                          }}
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                  <ConfirmButton
                    danger
                    type="primary"
                    loading={busy === 'stop'}
                    title={stopWhole ? '确认停止整个组织的 Listing 行动？' : '确认停止此 Listing？'}
                    description="停止立即生效；恢复需要两人分别证明。"
                    onConfirm={() => {
                      setBusy('stop');
                      return stopScope(
                        context,
                        stopWhole ? 'ORGANIZATION' : 'LISTING',
                        stopWhole ? undefined : stopListing,
                        causeClass,
                        causeOwner,
                        reason,
                        evidence,
                      ).then(settle);
                    }}
                  >
                    {stopWhole ? t('stopAll') : t('stop')}
                  </ConfirmButton>
                </Form>
              </div>
            </Card>
            <Space>
              <Switch checked={activeOnly} onChange={setActiveOnly} aria-label={t('activeOnly')} />
              <span>{t('activeOnly')}</span>
            </Space>
            {containments === undefined && failure === undefined && <LoadingState />}
            {containments?.length === 0 && <EmptyState description={t('noContainments')} />}
            {containments?.map((containment) => (
              <Card
                key={containment.id}
                size="small"
                data-containment={containment.id}
                data-containment-state={containment.state}
                title={
                  <Space wrap>
                    <Code family="containmentScope" code={containment.scopeKind} />
                    <Code family="containmentCauseClass" code={containment.causeClass} />
                    <Code family="containmentState" code={containment.state} />
                  </Space>
                }
                extra={<When value={containment.stoppedAt} />}
              >
                <Stack>
                  <Typography.Text>{containment.reason}</Typography.Text>
                  {containment.attestations.length > 0 && (
                    <Space wrap>
                      {containment.attestations.map((attestation) => (
                        <Code
                          key={`${attestation.attestationKind}:${attestation.actorUserId}`}
                          family="attestationKind"
                          code={attestation.attestationKind}
                        />
                      ))}
                    </Space>
                  )}
                  {containment.state === 'ACTIVE' && (
                    <Space wrap>
                      <ConfirmButton
                        title="确认提交修复证明？"
                        description="使用上方填写的证据引用。"
                        onConfirm={() =>
                          attestContainment(
                            context,
                            containment.id,
                            'REPAIR_ATTESTATION',
                            evidence,
                          ).then(settle)
                        }
                      >
                        {t('attestRepair')}
                      </ConfirmButton>
                      <ConfirmButton
                        title="确认提交业务同意？"
                        description="使用上方填写的证据引用。"
                        onConfirm={() =>
                          attestContainment(
                            context,
                            containment.id,
                            'BUSINESS_CONSENT',
                            evidence,
                          ).then(settle)
                        }
                      >
                        {t('consent')}
                      </ConfirmButton>
                      <ConfirmButton
                        type="primary"
                        title="确认恢复？"
                        description="需已有两名不同人员的证明，由后端核对。"
                        onConfirm={() => reenableContainment(context, containment.id).then(settle)}
                      >
                        {t('reenable')}
                      </ConfirmButton>
                    </Space>
                  )}
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
                </Stack>
              </Card>
            ))}
          </Stack>
        </SectionCard>

        <SectionCard title={t('batches')} extra={refreshButton}>
          <Stack>
            <Form
              layout="vertical"
              aria-label={t('batches')}
              onFinish={() => {
                setBusy('batch');
                void createBatch(context, batchStoreId, batchCode).then(settle);
              }}
            >
              <Row gutter={16}>
                <Col xs={24} md={12}>
                  <Form.Item label={t('batchStoreId')} required>
                    <Input
                      required
                      value={batchStoreId}
                      onChange={(event) => {
                        setBatchStoreId(event.target.value);
                      }}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item label={t('batchCode')}>
                    <Input
                      value={batchCode}
                      onChange={(e) => {
                        setBatchCode(e.target.value);
                      }}
                    />
                  </Form.Item>
                </Col>
              </Row>
              <Button type="primary" htmlType="submit" loading={busy === 'batch'}>
                {t('createBatch')}
              </Button>
            </Form>
            {batches?.length === 0 && <EmptyState description={t('noBatches')} />}
            {batches?.map((batch) => (
              <Card
                key={batch.id}
                size="small"
                data-batch={batch.id}
                title={
                  <Space wrap>
                    <span>{batch.batchCode}</span>
                    <Code family="batchState" code={batch.state} />
                    <Typography.Text type="secondary">
                      {t('members')} {batch.members.length}
                    </Typography.Text>
                  </Space>
                }
              >
                <Stack>
                  {batch.members.length > 0 && (
                    <Table
                      size="middle"
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
                  {batch.state === 'OPEN' && (
                    <Space wrap align="end">
                      <Form.Item
                        label={t('actionIdInput')}
                        layout="vertical"
                        style={{ marginBottom: 0 }}
                      >
                        <Input
                          style={{ width: 340 }}
                          value={memberAction}
                          onChange={(e) => {
                            setMemberAction(e.target.value);
                          }}
                        />
                      </Form.Item>
                      <Button
                        loading={busy === `member:${batch.id}`}
                        onClick={() => {
                          setBusy(`member:${batch.id}`);
                          void addBatchMember(context, batch.id, memberAction).then(settle);
                        }}
                      >
                        {t('addMember')}
                      </Button>
                      <ConfirmButton
                        danger
                        title="确认关闭此批次？"
                        description="关闭后不能再加入成员。"
                        onConfirm={() => closeBatch(context, batch.id).then(settle)}
                      >
                        {t('closeBatch')}
                      </ConfirmButton>
                    </Space>
                  )}
                  <TechnicalDetails>
                    <IdText label={t('batchId')} value={batch.id} />
                  </TechnicalDetails>
                </Stack>
              </Card>
            ))}
          </Stack>
        </SectionCard>

        <SectionCard title={t('recalculation')} extra={refreshButton}>
          {queue === undefined && failure === undefined && <LoadingState />}
          {queue?.length === 0 && <EmptyState description={t('noRecalculation')} />}
          {queue !== undefined && queue.length > 0 && (
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
          )}
        </SectionCard>
      </Stack>
    </section>
  );
}
