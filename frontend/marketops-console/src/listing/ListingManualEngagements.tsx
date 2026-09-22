import { ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  App,
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
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type {
  ListingIdentity,
  PromotionEngagement,
  PromotionObservationSummary,
} from '../api/listingConversion';
import {
  adoptEngagement,
  authorizeExit,
  fetchEngagements,
  fetchHealthQueuePage,
  fetchListingDetail,
  fetchListingObservations,
  releaseEngagement,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { engagementText } from '../i18n/zh/listingManual';
import {
  ActionModal,
  EmptyState,
  FormDrawer,
  LoadingState,
  SectionCard,
  TechnicalDetails,
  useSearchParam,
} from '../ui';
import {
  Code,
  Details,
  Hint,
  IdText,
  ListingName,
  ListingProblem,
  Stack,
  SubTitle,
  codeOptions,
  requiredRule,
} from './ListingCommon';
import { promotionKindOptions } from './ListingPromotionTerms';
import {
  PromotionTermsFields,
  promotionTermsFieldNames,
  promotionTermsInitialValues,
  readPromotionTerms,
} from './ListingPromotionTermsForm';
import type { PromotionTermsFieldValues } from './ListingPromotionTermsForm';
import {
  IdLookup,
  InstantField,
  PersonField,
  PickOrType,
  idRules,
  isUuid,
  promotionOptions,
  useRemote,
} from './ListingManualPickers';

const TEXT_LIMIT = 512;
const LISTING_KEY = 'mlisting';

const EXIT_REASONS = [
  'MARGIN_BELOW_BOUND',
  'RETURN_RATE_ABOVE_BOUND',
  'SUPPLY_COVERAGE_LOST',
  'PLATFORM_TERMS_CHANGED',
  'OWNER_DECISION',
] as const;

/** Exit reasons proven by a guardrail evaluation of a governed action. */
const GUARDRAIL_REASONS: readonly string[] = [
  'MARGIN_BELOW_BOUND',
  'RETURN_RATE_ABOVE_BOUND',
  'SUPPLY_COVERAGE_LOST',
];

/** A declaration map as label/value rows. */
function MapDetails({
  entries,
}: {
  readonly entries: Readonly<Record<string, string>>;
}): React.JSX.Element {
  const rows = Object.entries(entries);
  if (rows.length === 0) return <EmptyState description={t('nothing')} />;
  return (
    <Details
      column={{ xs: 1, md: 2 }}
      items={rows.map(([key, value]) => ({ key, label: key, children: value }))}
    />
  );
}

/** The record of this engagement inside a complete promotion observation, if any. */
function recordOf(observation: PromotionObservationSummary, engagement: PromotionEngagement) {
  return observation.contextRecords.find(
    (record) =>
      record.engagementKind === engagement.engagementKind &&
      record.nativePromotionKey === engagement.nativePromotionKey,
  );
}

/** A listing as the picker and the drawer show it. */
interface ListingLabel {
  readonly listingId: string;
  readonly identity: ListingIdentity | undefined;
  readonly nativeListingKey: string;
  readonly platformCode: string | undefined;
}

function ListingLabelView({ listing }: { readonly listing: ListingLabel }): React.JSX.Element {
  return (
    <ListingName
      identity={listing.identity}
      nativeListingKey={listing.nativeListingKey}
      platformCode={listing.platformCode}
      listingId={listing.listingId}
      strong={false}
    />
  );
}

/** Choose a listing by searching names and keys; the full identifier is never typed. */
function ListingPicker({
  context,
  value,
  selected,
  onChange,
}: {
  readonly context: ConsoleRequest;
  readonly value: string | undefined;
  readonly selected: ListingLabel | undefined;
  readonly onChange: (listingId: string | undefined) => void;
}): React.JSX.Element {
  const [query, setQuery] = useState('');
  const [debounced, setDebounced] = useState('');
  useEffect(() => {
    const handle = setTimeout(() => {
      setDebounced(query.trim());
    }, 300);
    return () => {
      clearTimeout(handle);
    };
  }, [query]);
  const found = useRemote(`q:${debounced}`, () =>
    fetchHealthQueuePage(context, { ...(debounced === '' ? {} : { q: debounced }), limit: 20 }),
  );
  const labels: ListingLabel[] = (found.value?.items ?? []).map((item) => ({
    listingId: item.platformListingId,
    identity: item.identity,
    nativeListingKey: item.nativeListingKey,
    platformCode: undefined,
  }));
  if (selected !== undefined && !labels.some((label) => label.listingId === selected.listingId)) {
    labels.unshift(selected);
  }
  return (
    <Flex gap={8} align="center" wrap>
      <Select<string>
        style={{ width: 320 }}
        aria-label={engagementText.listingPicker}
        placeholder={engagementText.listingPickerPlaceholder}
        loading={found.loading}
        allowClear
        value={value ?? null}
        showSearch={{ filterOption: false, onSearch: setQuery }}
        options={labels.map((label) => ({
          value: label.listingId,
          label: <ListingLabelView listing={label} />,
        }))}
        onChange={(next: string | undefined) => {
          onChange(next);
        }}
      />
      {found.failed && (
        <IdLookup
          label={engagementText.listingManual}
          title={engagementText.listingManualTitle}
          fieldLabel={t('listingIdInput')}
          initial={value}
          onOpenId={onChange}
        />
      )}
    </Flex>
  );
}

interface AdoptValues {
  engagementKind?: string;
  contextObservationId?: string;
  originalAuthorityReference?: string;
  originalAuthorityValidUntil?: string;
  responsibleUserId?: string;
  declaration?: PromotionTermsFieldValues;
}

/** Record an existing platform promotion as a managed engagement of one listing. */
function AdoptEngagement({
  context,
  listing,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly listing: ListingLabel | undefined;
  readonly onDone: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [round, setRound] = useState(0);
  const listingId = listing?.listingId;
  const observations = useRemote(
    listingId === undefined || round === 0 ? undefined : `${listingId}:${String(round)}`,
    () => fetchListingObservations(context, listingId ?? '', 20),
  );
  const candidates = (observations.value?.promotion ?? []).filter(
    (observation) =>
      observation.contextCoverage === 'COMPLETE_ENUMERATION' && observation.independentCurrent,
  );
  return (
    <FormDrawer<AdoptValues>
      trigger={{
        label: engagementText.adoptTrigger,
        type: 'primary',
        disabled: listing === undefined,
        disabledReason: engagementText.adoptNeedsListing,
      }}
      title={t('promotionAdoption')}
      submitText={engagementText.adoptSubmit}
      initialValues={{ declaration: promotionTermsInitialValues() }}
      onOpen={() => {
        setRound((value) => value + 1);
      }}
      intro={
        <Stack>
          {listing !== undefined && (
            <div>
              <Typography.Text type="secondary">{engagementText.adoptListing}</Typography.Text>
              <ListingLabelView listing={listing} />
            </div>
          )}
          <Hint>{t('promotionAdoptionHelp')}</Hint>
        </Stack>
      }
      steps={[
        {
          key: 'activity',
          title: engagementText.adoptStepActivity,
          fields: [
            'engagementKind',
            'contextObservationId',
            'originalAuthorityReference',
            'originalAuthorityValidUntil',
            'responsibleUserId',
          ],
          content: (
            <>
              <Form.Item
                name="engagementKind"
                label={t('promotionKind')}
                rules={[requiredRule(t('promotionKind'))]}
              >
                <Select placeholder={t('undeclared')} options={promotionKindOptions()} />
              </Form.Item>
              <Form.Item
                name="contextObservationId"
                label={engagementText.adoptContext}
                extra={engagementText.adoptContextHelp}
                rules={idRules(engagementText.adoptContext)}
              >
                <PickOrType
                  options={promotionOptions(candidates, new Set())}
                  loading={observations.loading}
                  unavailable={observations.failed}
                />
              </Form.Item>
              <Row gutter={16}>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="originalAuthorityReference"
                    label={t('promotionOriginalAuthority')}
                    rules={[
                      {
                        required: true,
                        whitespace: true,
                        message: `请填写${t('promotionOriginalAuthority')}`,
                      },
                    ]}
                  >
                    <Input maxLength={TEXT_LIMIT} />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="originalAuthorityValidUntil"
                    label={t('promotionOriginalAuthorityUntil')}
                    rules={[
                      { required: true, message: `请选择${t('promotionOriginalAuthorityUntil')}` },
                    ]}
                  >
                    <InstantField />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item
                name="responsibleUserId"
                label={engagementText.adoptSteward}
                extra={engagementText.adoptStewardHelp}
                rules={idRules(engagementText.adoptSteward)}
              >
                <PersonField
                  context={context}
                  role="PROMOTION_STEWARD"
                  targetId={listingId}
                  needsTarget={engagementText.adoptNeedsListing}
                />
              </Form.Item>
            </>
          ),
        },
        {
          key: 'terms',
          title: engagementText.adoptStepTerms,
          fields: promotionTermsFieldNames('declaration'),
          content: <PromotionTermsFields prefix="declaration" />,
        },
      ]}
      onSubmit={async (values) => {
        if (listingId === undefined) return undefined;
        const terms = readPromotionTerms(values.declaration, values.engagementKind ?? '');
        if (terms === undefined) {
          // The step's rules make this unreachable; never send a partial declaration.
          return { kind: 'malformed', detail: engagementText.termsIncomplete };
        }
        const outcome = await adoptEngagement(
          context,
          listingId,
          terms,
          (values.contextObservationId ?? '').trim(),
          (values.originalAuthorityReference ?? '').trim(),
          values.originalAuthorityValidUntil ?? '',
          (values.responsibleUserId ?? '').trim(),
        );
        if (!outcome.ok) return outcome.failure;
        void message.success(engagementText.adoptDone);
        onDone();
        return undefined;
      }}
    />
  );
}

interface ExitValues {
  reasonCode?: string;
  authorityReference?: string;
  evidenceId?: string;
}

/** The remaining obligations an exit leaves to be cleared, or why they cannot be shown. */
function ExitImpact({
  engagement,
}: {
  readonly engagement: PromotionEngagement;
}): React.JSX.Element {
  if (!engagement.fullDisclosure) {
    return <Alert type="warning" showIcon title={engagementText.exitRestricted} />;
  }
  const rows = Object.entries(engagement.obligations);
  return (
    <div>
      <SubTitle>{engagementText.exitImpact}</SubTitle>
      {rows.length === 0 ? (
        <Typography.Text type="secondary">{engagementText.exitNoObligations}</Typography.Text>
      ) : (
        <MapDetails entries={engagement.obligations} />
      )}
    </div>
  );
}

/** Authorise leaving an active promotion; new transactions stop, obligations remain. */
function ExitModal({
  context,
  engagement,
  open,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly engagement: PromotionEngagement;
  readonly open: boolean;
  readonly onClose: () => void;
  readonly onDone: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [reason, setReason] = useState<string | undefined>(undefined);
  const observations = useRemote(
    open ? `observations:${engagement.platformListingId}` : undefined,
    () => fetchListingObservations(context, engagement.platformListingId, 20),
  );
  const adopted = engagement.actionId === undefined;
  const clause =
    reason === undefined || !engagement.fullDisclosure
      ? undefined
      : engagement.obligations[`exit.${reason}`];
  const blocked =
    reason !== undefined && adopted && GUARDRAIL_REASONS.includes(reason)
      ? engagementText.exitAdoptedNeedsAction
      : undefined;
  const observationEvidence =
    reason === 'PLATFORM_TERMS_CHANGED' || (reason === 'OWNER_DECISION' && adopted);
  const evidenceHelp =
    reason === 'PLATFORM_TERMS_CHANGED'
      ? engagementText.exitEvidenceTermsChanged
      : reason === 'OWNER_DECISION'
        ? adopted
          ? engagementText.exitEvidenceOwnerAdopted
          : engagementText.exitEvidenceOwnerAction
        : reason === undefined
          ? undefined
          : engagementText.exitEvidenceGuardrail;
  const options = promotionOptions(
    (observations.value?.promotion ?? []).filter(
      (observation) => recordOf(observation, engagement) !== undefined,
    ),
    new Set(),
  );

  return (
    <ActionModal<ExitValues>
      open={open}
      onClose={onClose}
      onOpen={() => {
        setReason(undefined);
      }}
      danger
      width={640}
      title={engagementText.exitTitle}
      consequence={engagementText.exitConsequence}
      summary={<ExitImpact engagement={engagement} />}
      okText={engagementText.exitSubmit}
      {...(blocked === undefined ? {} : { blockedReason: blocked })}
      onSubmit={async (values) => {
        const outcome = await authorizeExit(
          context,
          engagement.id,
          values.reasonCode ?? '',
          values.authorityReference ?? '',
          (values.evidenceId ?? '').trim(),
        );
        if (!outcome.ok) return outcome.failure;
        void message.success(engagementText.exited);
        onDone();
        return undefined;
      }}
    >
      {(form) => (
        <>
          <Form.Item
            name="reasonCode"
            label={engagementText.exitReason}
            rules={[requiredRule(engagementText.exitReason)]}
          >
            <Select
              placeholder={t('undeclared')}
              options={codeOptions('exitReason', EXIT_REASONS)}
              onChange={(next: string) => {
                setReason(next);
                if (engagement.fullDisclosure) {
                  form.setFieldValue(
                    'authorityReference',
                    engagement.obligations[`exit.${next}`] ?? undefined,
                  );
                }
                form.setFieldValue(
                  'evidenceId',
                  next === 'OWNER_DECISION' && adopted
                    ? engagement.sourceContextObservationId
                    : undefined,
                );
              }}
            />
          </Form.Item>
          <Form.Item
            name="authorityReference"
            label={engagementText.exitAuthority}
            extra={
              engagement.fullDisclosure && reason !== undefined && clause === undefined ? (
                <Typography.Text type="warning">
                  {engagementText.exitAuthorityMissing}
                </Typography.Text>
              ) : (
                engagementText.exitAuthorityHelp
              )
            }
            rules={[
              {
                required: true,
                whitespace: true,
                message: `请填写${engagementText.exitAuthority}`,
              },
            ]}
          >
            <Input maxLength={TEXT_LIMIT} />
          </Form.Item>
          <Form.Item
            name="evidenceId"
            label={engagementText.exitEvidence}
            {...(evidenceHelp === undefined ? {} : { extra: evidenceHelp })}
            rules={idRules(engagementText.exitEvidence)}
          >
            {observationEvidence ? (
              <PickOrType
                options={options}
                loading={observations.loading}
                unavailable={observations.failed}
              />
            ) : (
              <Input placeholder={engagementText.exitEvidence} />
            )}
          </Form.Item>
        </>
      )}
    </ActionModal>
  );
}

interface ReleaseValues {
  observationId?: string;
  evidenceReference?: string;
}

/** Release one phase of an exiting promotion on independent evidence. */
function ReleaseModal({
  context,
  engagement,
  open,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly engagement: PromotionEngagement;
  readonly open: boolean;
  readonly onClose: () => void;
  readonly onDone: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const stopping = engagement.state === 'EXITING';
  const observations = useRemote(
    open ? `observations:${engagement.platformListingId}` : undefined,
    () => fetchListingObservations(context, engagement.platformListingId, 20),
  );
  const qualifies = (observation: PromotionObservationSummary): boolean | undefined => {
    const record = recordOf(observation, engagement);
    if (record === undefined) return undefined;
    const states = stopping
      ? record.participationState === 'NOT_PARTICIPATING' &&
        record.newTransactionsState === 'STOPPED'
      : record.newTransactionsState === 'STOPPED' && record.residualObligationState === 'CLEARED';
    return states && observation.independentCurrent;
  };
  const verdict = (observation: PromotionObservationSummary): ReactNode => {
    const verdictValue = qualifies(observation);
    if (verdictValue === undefined) {
      return <Tag style={{ marginInlineEnd: 0 }}>{engagementText.releaseNoRecord}</Tag>;
    }
    return verdictValue ? (
      <Tag color="success" style={{ marginInlineEnd: 0 }}>
        {engagementText.releaseMatches}
      </Tag>
    ) : (
      <Tag color="warning" style={{ marginInlineEnd: 0 }}>
        {engagementText.releaseStatesDiffer}
      </Tag>
    );
  };
  const all = observations.value?.promotion ?? [];
  const ordered = [
    ...all.filter((observation) => qualifies(observation) === true),
    ...all.filter((observation) => qualifies(observation) !== true),
  ];

  return (
    <ActionModal<ReleaseValues>
      open={open}
      onClose={onClose}
      width={640}
      title={stopping ? engagementText.releaseStoppedTitle : engagementText.releaseClearedTitle}
      consequence={
        stopping
          ? engagementText.releaseStoppedConsequence
          : engagementText.releaseClearedConsequence
      }
      summary={<Hint>{engagementText.releaseBasis}</Hint>}
      okText={engagementText.releaseSubmit}
      onSubmit={async (values) => {
        const outcome = await releaseEngagement(
          context,
          engagement.id,
          stopping ? 'NEW_TRANSACTIONS_STOPPED' : 'OBLIGATIONS_CLEARED',
          (values.observationId ?? '').trim(),
          (values.evidenceReference ?? '').trim(),
        );
        if (!outcome.ok) return outcome.failure;
        void message.success(engagementText.released);
        onDone();
        return undefined;
      }}
    >
      <Form.Item
        name="observationId"
        label={engagementText.releaseObservation}
        extra={
          stopping
            ? engagementText.releaseObservationStoppedHelp
            : engagementText.releaseObservationClearedHelp
        }
        rules={idRules(engagementText.releaseObservation)}
      >
        <PickOrType
          options={promotionOptions(ordered, new Set(), verdict)}
          loading={observations.loading}
          unavailable={observations.failed}
        />
      </Form.Item>
      <Form.Item
        name="evidenceReference"
        label={engagementText.releaseEvidence}
        rules={[
          { required: true, whitespace: true, message: `请填写${engagementText.releaseEvidence}` },
        ]}
      >
        <Input maxLength={TEXT_LIMIT} />
      </Form.Item>
    </ActionModal>
  );
}

/** What an expanded engagement row shows. */
function EngagementDetail({
  engagement,
}: {
  readonly engagement: PromotionEngagement;
}): React.JSX.Element {
  return (
    <Stack>
      {engagement.fullDisclosure ? (
        <>
          <SubTitle>{t('terms')}</SubTitle>
          <MapDetails entries={engagement.terms} />
          <SubTitle>{t('obligations')}</SubTitle>
          <MapDetails entries={engagement.obligations} />
          {engagement.termsEvidenceReference !== undefined && (
            <IdText label={t('evidence')} value={engagement.termsEvidenceReference} />
          )}
        </>
      ) : (
        <Alert type="info" showIcon title={t('promotionTermsRestricted')} />
      )}
      <TechnicalDetails>
        <Space orientation="vertical" size={2}>
          <IdText label={t('engagementId')} value={engagement.id} />
          <IdText label={t('actionId')} value={engagement.actionId} />
          <IdText label={t('promotionNativeKey')} value={engagement.nativePromotionKey} />
          <IdText
            label={t('promotionContextObservationId')}
            value={engagement.sourceContextObservationId}
          />
          <IdText
            label={t('promotionOriginalAuthority')}
            value={engagement.originalAuthorityReference}
          />
        </Space>
      </TechnicalDetails>
    </Stack>
  );
}

interface Acting {
  readonly kind: 'exit' | 'release';
  readonly engagement: PromotionEngagement;
  readonly open: boolean;
}

/**
 * Promotion engagements of one listing: entered on the platform's own console
 * and only recorded, exited and released here.
 */
export function ListingManualEngagements({
  context,
}: {
  readonly context: ConsoleRequest;
}): React.JSX.Element {
  const [listingParam, setListingParam] = useSearchParam(LISTING_KEY);
  const listingId = isUuid(listingParam) ? listingParam : undefined;
  const [generation, setGeneration] = useState(0);
  const [acting, setActing] = useState<Acting | undefined>(undefined);

  const detail = useRemote(listingId === undefined ? undefined : `detail:${listingId}`, () =>
    fetchListingDetail(context, listingId ?? ''),
  );
  const listing: ListingLabel | undefined =
    listingId === undefined
      ? undefined
      : {
          listingId,
          identity: detail.value?.identity,
          nativeListingKey: detail.value?.nativeListingKey ?? listingId,
          platformCode: detail.value?.platformCode,
        };

  const loadKey = listingId === undefined ? undefined : `${listingId}:${String(generation)}`;
  const [loaded, setLoaded] = useState<
    | {
        readonly key: string;
        readonly engagements: readonly PromotionEngagement[] | undefined;
        readonly failure: ConsoleFailure | undefined;
      }
    | undefined
  >(undefined);
  useEffect(() => {
    if (loadKey === undefined || listingId === undefined) return;
    let live = true;
    void fetchEngagements(context, listingId).then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { key: loadKey, engagements: outcome.value, failure: undefined }
          : { key: loadKey, engagements: undefined, failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, listingId, loadKey]);
  const current = loaded !== undefined && loaded.key === loadKey ? loaded : undefined;
  const engagements = current?.engagements;
  const refresh = (): void => {
    setGeneration((value) => value + 1);
  };
  const closeActing = (): void => {
    setActing((previous) => (previous === undefined ? undefined : { ...previous, open: false }));
  };

  const columns: TableColumnsType<PromotionEngagement> = [
    {
      key: 'kind',
      title: engagementText.kindColumn,
      render: (_, engagement) => <Code family="engagementKind" code={engagement.engagementKind} />,
    },
    {
      key: 'key',
      title: engagementText.keyColumn,
      render: (_, engagement) => engagement.nativePromotionKey ?? '—',
    },
    {
      key: 'state',
      title: engagementText.stateColumn,
      render: (_, engagement) => (
        <Space size={4} wrap>
          <Code family="engagementState" code={engagement.state} />
          {engagement.exitReasonCode !== undefined && (
            <Code family="exitReason" code={engagement.exitReasonCode} />
          )}
        </Space>
      ),
    },
    {
      key: 'source',
      title: engagementText.sourceColumn,
      render: (_, engagement) =>
        engagement.actionId === undefined ? engagementText.adopted : engagementText.governed,
    },
    {
      key: 'actions',
      title: engagementText.actionsColumn,
      render: (_, engagement) => {
        if (engagement.state === 'ACTIVE') {
          return (
            <Button
              size="small"
              danger
              onClick={() => {
                setActing({ kind: 'exit', engagement, open: true });
              }}
            >
              {t('exit')}
            </Button>
          );
        }
        if (engagement.state === 'EXITING' || engagement.state === 'STOPPED') {
          return (
            <Button
              size="small"
              onClick={() => {
                setActing({ kind: 'release', engagement, open: true });
              }}
            >
              {t('release')}
            </Button>
          );
        }
        return <Typography.Text type="secondary">—</Typography.Text>;
      },
    },
  ];

  return (
    <SectionCard
      title={t('engagements')}
      extra={
        <Flex gap={8} wrap align="center">
          <ListingPicker
            context={context}
            value={listingId}
            selected={listing}
            onChange={setListingParam}
          />
          <AdoptEngagement context={context} listing={listing} onDone={refresh} />
          <Button
            icon={<ReloadOutlined />}
            aria-label={engagementText.refresh}
            disabled={listingId === undefined}
            onClick={refresh}
          >
            {t('refresh')}
          </Button>
        </Flex>
      }
    >
      <section
        aria-label={t('engagements')}
        data-state={loadKey === undefined ? 'idle' : current === undefined ? 'loading' : 'loaded'}
      >
        <Stack>
          {current?.failure !== undefined && <ListingProblem failure={current.failure} />}
          {loadKey === undefined && <EmptyState description={engagementText.pickListingFirst} />}
          {loadKey !== undefined && current === undefined && <LoadingState />}
          {engagements?.length === 0 && <EmptyState description={t('noEngagements')} />}
          {engagements !== undefined && engagements.length > 0 && (
            <Table<PromotionEngagement>
              rowKey="id"
              size="small"
              columns={columns}
              dataSource={[...engagements]}
              pagination={false}
              expandable={{
                expandedRowRender: (engagement) => <EngagementDetail engagement={engagement} />,
              }}
            />
          )}
        </Stack>
      </section>
      {acting?.kind === 'exit' && (
        <ExitModal
          key={`exit:${acting.engagement.id}`}
          context={context}
          engagement={acting.engagement}
          open={acting.open}
          onClose={closeActing}
          onDone={refresh}
        />
      )}
      {acting?.kind === 'release' && (
        <ReleaseModal
          key={`release:${acting.engagement.id}`}
          context={context}
          engagement={acting.engagement}
          open={acting.open}
          onClose={closeActing}
          onDone={refresh}
        />
      )}
    </SectionCard>
  );
}
