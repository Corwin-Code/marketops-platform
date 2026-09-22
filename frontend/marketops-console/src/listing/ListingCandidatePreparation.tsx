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
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  Candidate,
  ListingActionPurpose,
  ListingDetail,
  RestorationSource,
} from '../api/listingConversion';
import {
  dismissCandidate,
  endpointUnavailable,
  fetchCandidates,
  fetchListingDetail,
  fetchRestorationSources,
  prepareAction,
  prepareCandidate,
} from '../api/listingConversion';
import { formatStoreTime } from '../format';
import { dialog } from '../i18n/zh/common';
import { candidateText, contentPreparationText } from '../i18n/zh/listingActions';
import { t } from '../i18n/zh/listing';
import { ActionModal, EmptyState, FormDrawer, InfoTip, LoadingState, SectionCard } from '../ui';
import { SectionCollapse } from '../ui';
import type { SubmitOutcome } from '../ui';
import { InstantField, UUID_PATTERN, nonBlankLines } from './ListingActionFields';
import { Code, Hint, ListingName, ListingProblem, RussianText, codeOptions } from './ListingCommon';
import { PromotionPreparationDrawer } from './ListingPromotionPreparation';

type Kiz = 'undeclared' | 'yes' | 'no';

const CANDIDATE_KINDS = [
  'CONTENT_DESCRIPTION',
  'OFFICIAL_PROMOTION_PARTICIPATION',
  'SELLER_DIRECT_DISCOUNT',
];

export interface CandidatePreparationProps {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly onPrepared: (actionId: string) => void;
}

interface NewCandidateValues {
  readonly kind?: string;
  readonly roundKey?: string;
  readonly evidence?: string;
}

interface DismissValues {
  readonly reason?: string;
}

/**
 * The candidates of one listing, and preparing an exact action from one.
 *
 * Each open candidate is prepared in its own drawer, opened from its row and
 * filled only from that row, so nothing typed for one candidate reaches another.
 */
export function CandidatePreparation({
  context,
  listingId,
  onPrepared,
}: CandidatePreparationProps): React.JSX.Element {
  const { message } = App.useApp();
  const [candidates, setCandidates] = useState<readonly Candidate[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [detail, setDetail] = useState<ListingDetail | undefined>(undefined);
  const [preparing, setPreparing] = useState<Candidate | undefined>(undefined);
  const [dismissing, setDismissing] = useState<Candidate | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchCandidates(context, listingId).then((outcome) => {
      if (!active) return;
      if (outcome.ok) {
        setCandidates(outcome.value);
        setFailure(undefined);
      } else {
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, listingId, generation]);

  // The listing's name, for the heading only; a failure leaves the key shown.
  useEffect(() => {
    let active = true;
    void fetchListingDetail(context, listingId).then((outcome) => {
      if (active && outcome.ok) setDetail(outcome.value);
    });
    return () => {
      active = false;
    };
  }, [context, listingId]);

  const reload = (): void => {
    setGeneration((value) => value + 1);
  };

  const columns: TableColumnsType<Candidate> = [
    {
      key: 'kind',
      title: candidateText.kind,
      render: (_, candidate) => <Code family="candidateKind" code={candidate.candidateKind} />,
    },
    { key: 'round', title: candidateText.round, dataIndex: 'comparisonRoundKey' },
    {
      key: 'state',
      title: t('state'),
      render: (_, candidate) => <Code family="candidateState" code={candidate.state} />,
    },
    {
      key: 'evidence',
      title: candidateText.evidenceCount,
      align: 'right',
      render: (_, candidate) =>
        candidateText.evidenceCountValue(candidate.evidenceReferences.length),
    },
    {
      key: 'operations',
      title: candidateText.operations,
      render: (_, candidate) =>
        candidate.state === 'OPEN' ? (
          <Space size={4}>
            <Button
              type="link"
              size="small"
              onClick={() => {
                setPreparing(candidate);
              }}
            >
              {candidateText.prepare}
            </Button>
            <Button
              type="link"
              size="small"
              danger
              onClick={() => {
                setDismissing(candidate);
              }}
            >
              {candidateText.dismiss}
            </Button>
          </Space>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
  ];

  const prepared = (actionId: string): void => {
    void message.success(candidateText.prepared);
    setPreparing(undefined);
    onPrepared(actionId);
  };

  return (
    <section aria-label={candidateText.title} data-listing={listingId}>
      <SectionCard
        title={
          <Flex gap={12} align="center" wrap>
            <span>{candidateText.title}</span>
            {detail === undefined ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {listingId}
              </Typography.Text>
            ) : (
              <ListingName
                identity={detail.identity}
                nativeListingKey={detail.nativeListingKey}
                platformCode={detail.platformCode}
                listingId={detail.listingId}
                strong={false}
              />
            )}
          </Flex>
        }
        extra={
          <Space wrap>
            <ActionModal<NewCandidateValues>
              trigger={{ label: candidateText.newCandidate }}
              title={candidateText.newCandidateTitle}
              consequence={candidateText.newCandidateConsequence}
              initialValues={{ kind: 'CONTENT_DESCRIPTION', roundKey: 'round-1' }}
              onSubmit={async (values) => {
                const evidence = (values.evidence ?? '').trim();
                const outcome = await prepareCandidate(
                  context,
                  listingId,
                  values.kind ?? 'CONTENT_DESCRIPTION',
                  (values.roundKey ?? '').trim(),
                  evidence === '' ? [] : [evidence],
                );
                if (!outcome.ok) return outcome.failure;
                void message.success(candidateText.created);
                reload();
                return undefined;
              }}
            >
              <Form.Item name="kind" label={candidateText.kind} rules={[{ required: true }]}>
                <Select options={codeOptions('candidateKind', CANDIDATE_KINDS)} />
              </Form.Item>
              <Form.Item
                name="roundKey"
                label={candidateText.round}
                rules={[{ required: true, whitespace: true, message: candidateText.roundRequired }]}
              >
                <Input maxLength={128} />
              </Form.Item>
              <Form.Item name="evidence" label={candidateText.evidence}>
                <Input maxLength={512} />
              </Form.Item>
            </ActionModal>
            <Button icon={<ReloadOutlined />} onClick={reload}>
              {t('refresh')}
            </Button>
          </Space>
        }
      >
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {failure !== undefined && <ListingProblem failure={failure} />}
          {candidates === undefined && failure === undefined && <LoadingState rows={2} />}
          {candidates?.length === 0 && <EmptyState description={t('noCandidates')} />}
          {candidates !== undefined && candidates.length > 0 && (
            <Table<Candidate>
              size="small"
              rowKey="id"
              columns={columns}
              dataSource={[...candidates]}
              pagination={false}
              scroll={{ x: 'max-content' }}
              onRow={(candidate) =>
                ({
                  'data-candidate': candidate.id,
                  'data-candidate-state': candidate.state,
                }) as React.HTMLAttributes<HTMLElement>
              }
            />
          )}
        </Space>
      </SectionCard>
      {preparing?.candidateKind === 'CONTENT_DESCRIPTION' && (
        <ContentPreparationDrawer
          key={preparing.id}
          context={context}
          candidate={preparing}
          onClose={() => {
            setPreparing(undefined);
          }}
          onPrepared={prepared}
        />
      )}
      {preparing !== undefined && preparing.candidateKind !== 'CONTENT_DESCRIPTION' && (
        <PromotionPreparationDrawer
          key={preparing.id}
          context={context}
          candidate={preparing}
          onClose={() => {
            setPreparing(undefined);
          }}
          onPrepared={prepared}
        />
      )}
      {dismissing !== undefined && (
        <ActionModal<DismissValues>
          key={dismissing.id}
          open
          onClose={() => {
            setDismissing(undefined);
          }}
          title={candidateText.dismissTitle}
          consequence={candidateText.dismissConsequence}
          summary={
            <Space size={8} wrap>
              <Code family="candidateKind" code={dismissing.candidateKind} />
              <Typography.Text type="secondary">{dismissing.comparisonRoundKey}</Typography.Text>
            </Space>
          }
          okText={candidateText.dismiss}
          danger
          onSubmit={async (values) => {
            const outcome = await dismissCandidate(
              context,
              dismissing.id,
              dismissing.version,
              (values.reason ?? '').trim(),
            );
            if (!outcome.ok) return outcome.failure;
            void message.success(candidateText.dismissed);
            reload();
            return undefined;
          }}
        >
          <Form.Item
            name="reason"
            label={candidateText.dismissReason}
            rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
          >
            <Input.TextArea rows={3} maxLength={500} showCount autoFocus />
          </Form.Item>
        </ActionModal>
      )}
    </section>
  );
}

interface ContentPreparationValues {
  readonly purpose?: ListingActionPurpose;
  readonly path?: 'API' | 'MANUAL';
  readonly basisEvidence?: string;
  readonly useUntil?: string;
  readonly useConditions?: string;
  readonly endConditions?: string;
  readonly restoresCommandId?: string;
  readonly targetText?: string;
  readonly kiz?: Kiz;
}

/** Restoration sources as the drawer knows them: loading, listed, or not available. */
type SourcesState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'listed'; readonly sources: readonly RestorationSource[] }
  | { readonly kind: 'unavailable'; readonly failure: ConsoleFailure | undefined };

/** Preparing an exact description action from one open candidate. */
function ContentPreparationDrawer({
  context,
  candidate,
  onClose,
  onPrepared,
}: {
  readonly context: ConsoleRequest;
  readonly candidate: Candidate;
  readonly onClose: () => void;
  readonly onPrepared: (actionId: string) => void;
}): React.JSX.Element {
  const [sources, setSources] = useState<SourcesState>({ kind: 'loading' });
  const epoch = useRef(0);

  const loadSources = (): void => {
    const ticket = ++epoch.current;
    setSources({ kind: 'loading' });
    void fetchRestorationSources(context, candidate.platformListingId).then((outcome) => {
      if (ticket !== epoch.current) return;
      if (outcome.ok) {
        // Sources that restore over the current text first; otherwise the server's order.
        const ordered = [...outcome.value].sort(
          (a, b) => Number(b.appliesToCurrent) - Number(a.appliesToCurrent),
        );
        setSources({ kind: 'listed', sources: ordered });
      } else {
        setSources({
          kind: 'unavailable',
          failure: endpointUnavailable(outcome.failure) ? undefined : outcome.failure,
        });
      }
    });
  };

  const submit = async (values: ContentPreparationValues): Promise<SubmitOutcome> => {
    const purpose = values.purpose ?? 'LISTING_CONVERSION';
    const restoresCommandId = (values.restoresCommandId ?? '').trim();
    const kiz = values.kiz ?? 'undeclared';
    const outcome = await prepareAction(
      context,
      candidate.id,
      values.path ?? 'API',
      values.targetText ?? '',
      kiz === 'undeclared' ? undefined : kiz === 'yes',
      restoresCommandId === '' ? undefined : restoresCommandId,
      undefined,
      purpose,
      purpose === 'LISTING_CONVERSION'
        ? undefined
        : {
            evidenceReference: values.basisEvidence ?? '',
            useConditions: nonBlankLines(values.useConditions),
            endConditions: nonBlankLines(values.endConditions),
            useUntil:
              values.useUntil === undefined || values.useUntil === '' ? undefined : values.useUntil,
          },
    );
    if (!outcome.ok) return outcome.failure;
    onPrepared(outcome.value.id);
    return undefined;
  };

  return (
    <FormDrawer<ContentPreparationValues>
      open
      onClose={onClose}
      title={
        <Space size={8} wrap>
          <span>{contentPreparationText.title}</span>
          <Typography.Text type="secondary" style={{ fontWeight: 'normal' }}>
            {candidate.comparisonRoundKey}
          </Typography.Text>
        </Space>
      }
      initialValues={{ purpose: 'LISTING_CONVERSION', path: 'API', kiz: 'undeclared' }}
      submitText={contentPreparationText.submit}
      onOpen={loadSources}
      onSubmit={submit}
    >
      <ContentPreparationFields sources={sources} />
    </FormDrawer>
  );
}

/** The fields of the description preparation; they react to the purpose and restoration. */
function ContentPreparationFields({
  sources,
}: {
  readonly sources: SourcesState;
}): React.JSX.Element {
  const form = Form.useFormInstance<ContentPreparationValues>();
  const purpose = Form.useWatch('purpose', form);
  const path = Form.useWatch('path', form);
  const restoreValue = Form.useWatch('restoresCommandId', form);
  const restoring = (restoreValue ?? '').trim() !== '';
  const exploration = purpose === 'BOUNDED_EXPLORATION';
  const needsBasis = purpose !== undefined && purpose !== 'LISTING_CONVERSION';

  // Bounded exploration runs only by hand: the API path is withdrawn.
  useEffect(() => {
    if (exploration && path === 'API') form.setFieldValue('path', 'MANUAL');
  }, [exploration, path, form]);

  // Restoring takes the text from the source command: a typed target is dropped.
  useEffect(() => {
    if (restoring) form.setFields([{ name: 'targetText', errors: [] }]);
  }, [restoring, form]);

  const listed = sources.kind === 'listed' ? sources.sources : [];
  const chosen = listed.find((source) => source.commandId === (restoreValue ?? '').trim());

  return (
    <>
      <Typography.Title level={5} style={{ marginTop: 0 }}>
        {contentPreparationText.purposeSection}
      </Typography.Title>
      <Row gutter={16}>
        <Col xs={24} md={12}>
          <Form.Item
            name="purpose"
            label={
              <>
                {t('actionPurpose')}
                <InfoTip title={t('purposeHelp')} />
              </>
            }
            rules={[{ required: true }]}
          >
            <Select<ListingActionPurpose>
              options={codeOptions('actionPurpose', [
                'LISTING_CONVERSION',
                'DESCRIPTION_CORRECTION',
                'BOUNDED_EXPLORATION',
              ]).map((option) => ({ ...option, value: option.value as ListingActionPurpose }))}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item
            name="path"
            label={t('path')}
            rules={[{ required: true }]}
            {...(exploration ? { extra: contentPreparationText.pathApiExplorationDisabled } : {})}
          >
            <Select
              options={[
                { value: 'API', label: t('pathApi'), disabled: exploration },
                { value: 'MANUAL', label: t('pathManual') },
              ]}
            />
          </Form.Item>
        </Col>
      </Row>
      {needsBasis && (
        <>
          <Typography.Title level={5}>{contentPreparationText.basisSection}</Typography.Title>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="basisEvidence"
                label={t('purposeEvidence')}
                rules={[
                  {
                    required: true,
                    whitespace: true,
                    message: contentPreparationText.evidenceRequired,
                  },
                ]}
              >
                <Input maxLength={512} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="useUntil"
                label={t('purposeUseUntil')}
                rules={
                  exploration
                    ? [{ required: true, message: contentPreparationText.useUntilRequired }]
                    : []
                }
              >
                <InstantField ariaLabel={t('purposeUseUntil')} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="useConditions"
                label={t('purposeUseConditions')}
                rules={[
                  {
                    required: true,
                    whitespace: true,
                    message: contentPreparationText.useConditionsRequired,
                  },
                ]}
              >
                <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="endConditions"
                label={t('purposeEndConditions')}
                rules={[
                  {
                    required: true,
                    whitespace: true,
                    message: contentPreparationText.endConditionsRequired,
                  },
                ]}
              >
                <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} />
              </Form.Item>
            </Col>
          </Row>
        </>
      )}
      <div style={{ marginBottom: 16 }}>
        <SectionCollapse
          size="small"
          items={[
            {
              key: 'restore',
              title: contentPreparationText.restoreSection,
              summary: contentPreparationText.restoreSummary,
              flags: restoring
                ? [
                    {
                      key: 'restoring',
                      label: contentPreparationText.restoreSelected,
                      color: 'processing',
                    },
                  ]
                : [],
              children: (
                <>
                  <Form.Item
                    name="restoresCommandId"
                    label={contentPreparationText.restoreSource}
                    rules={[{ pattern: UUID_PATTERN, message: t('uuidInvalid') }]}
                    extra={
                      sources.kind === 'unavailable'
                        ? contentPreparationText.listUnavailable
                        : contentPreparationText.restoreManualHelp
                    }
                  >
                    <AutoComplete
                      allowClear
                      placeholder={contentPreparationText.restorePlaceholder}
                      options={listed.map((source) => ({
                        value: source.commandId,
                        label: <RestorationOption source={source} />,
                      }))}
                      {...(sources.kind === 'loading'
                        ? { notFoundContent: <LoadingState rows={1} /> }
                        : {})}
                    />
                  </Form.Item>
                  {sources.kind === 'unavailable' && sources.failure !== undefined && (
                    <div style={{ marginBottom: 12 }}>
                      <ListingProblem failure={sources.failure} />
                    </div>
                  )}
                  {chosen !== undefined && (
                    <Space orientation="vertical" size="small" style={{ width: '100%' }}>
                      {!chosen.appliesToCurrent && (
                        <Alert
                          type="warning"
                          showIcon
                          title={contentPreparationText.restoreNotCurrentWarning}
                        />
                      )}
                      <Typography.Text type="secondary">
                        {contentPreparationText.restorePriorText}
                      </Typography.Text>
                      <RussianText value={chosen.priorText} />
                    </Space>
                  )}
                  {restoring && (
                    <Alert
                      style={{ marginTop: 12 }}
                      type="info"
                      showIcon
                      title={t('restorationApproval')}
                    />
                  )}
                </>
              ),
            },
          ]}
        />
      </div>
      <Typography.Title level={5}>{contentPreparationText.textSection}</Typography.Title>
      <Form.Item
        name="targetText"
        label={t('targetText')}
        rules={
          restoring
            ? []
            : [{ required: true, whitespace: true, message: contentPreparationText.targetRequired }]
        }
        {...(restoring ? { extra: contentPreparationText.targetDisabledByRestore } : {})}
      >
        <Input.TextArea lang="ru" disabled={restoring} autoSize={{ minRows: 6, maxRows: 16 }} />
      </Form.Item>
      <Form.Item name="kiz" label={t('kiz')}>
        <Select<Kiz>
          style={{ maxWidth: 240 }}
          options={[
            { value: 'undeclared', label: t('undeclared') },
            { value: 'yes', label: t('yes') },
            { value: 'no', label: t('no') },
          ]}
        />
      </Form.Item>
      <Hint>{t('exposureEvidence')}</Hint>
    </>
  );
}

/** One restoration source in the picker: when, whether it fits, and the text's start. */
function RestorationOption({ source }: { readonly source: RestorationSource }): React.JSX.Element {
  return (
    <Flex vertical gap={0} data-command={source.commandId}>
      <Space size={6} wrap>
        <Tag color={source.appliesToCurrent ? 'success' : 'warning'}>
          {source.appliesToCurrent
            ? contentPreparationText.restoreAppliesToCurrent
            : contentPreparationText.restoreNotCurrent}
        </Tag>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {contentPreparationText.restoreCompletedAt}{' '}
          {source.completedAt === undefined ? '—' : formatStoreTime(source.completedAt)}
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }} code>
          {source.commandId.slice(0, 8)}
        </Typography.Text>
      </Space>
      <Typography.Text ellipsis lang="ru" style={{ maxWidth: 520 }}>
        {source.priorText}
      </Typography.Text>
    </Flex>
  );
}
