import { ReloadOutlined } from '@ant-design/icons';
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
  Timeline,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { ListingAction, ManualPacket } from '../api/listingConversion';
import {
  fetchAction,
  fetchActionPackets,
  fetchActionsBy,
  fetchListingObservations,
  fetchMyPackets,
  issuePacket,
  reportPacket,
  verifyPacket,
} from '../api/listingConversion';
import { dialog } from '../i18n/zh/common';
import { t } from '../i18n/zh/listing';
import { packetText } from '../i18n/zh/listingManual';
import {
  ActionModal,
  EmptyState,
  FormDrawer,
  InfoTip,
  LoadingState,
  SectionCard,
  TechnicalDetails,
  useSearchParam,
  useSearchParamsPatch,
} from '../ui';
import {
  Code,
  Hint,
  IdText,
  ListingProblem,
  RussianText,
  Stack,
  SubTitle,
  When,
  codeOptions,
  codeText,
} from './ListingCommon';
import {
  IdLookup,
  InstantField,
  PersonField,
  PickOrType,
  descriptionOptions,
  displayOptions,
  idRules,
  isUuid,
  notInFutureRule,
  optionalId,
  promotionOptions,
  useRemote,
} from './ListingManualPickers';
import type { PickOption } from './ListingManualPickers';

const TEXT_LIMIT = 512;

/** Search-param keys of this card: the view, and the action whose packets are shown. */
const VIEW_KEY = 'mview';
const ACTION_KEY = 'maction';

/** One label for an action in a list: listing, state and kind. */
function actionLabel(action: ListingAction): string {
  return [
    action.nativeListingKey,
    codeText('actionState', action.state),
    codeText('actionKind', action.actionKind),
  ].join(' · ');
}

function actionOptions(actions: readonly ListingAction[]): PickOption[] {
  return actions.map((action) => ({
    value: action.id,
    label: actionLabel(action),
    search: `${actionLabel(action)} ${action.id}`,
  }));
}

/** Who may not verify a packet independently: its executor and its reporters. */
function executorsOf(packet: ManualPacket): ReadonlySet<string> {
  return new Set([packet.executorUserId, ...packet.reports.map((report) => report.reporterUserId)]);
}

/** Reports and verifications of one packet, in the order recorded. */
function PacketHistory({ packet }: { readonly packet: ManualPacket }): React.JSX.Element {
  if (packet.reports.length === 0 && packet.verifications.length === 0) {
    return <Typography.Text type="secondary">{packetText.noHistory}</Typography.Text>;
  }
  return (
    <Timeline
      items={[
        ...packet.reports.map((report) => ({
          key: report.id,
          content: (
            <Space size={6} wrap>
              <span>{t('report')}</span>
              <Code family="reportState" code={report.reportState} />
              {report.note !== '' && <span>{report.note}</span>}
            </Space>
          ),
        })),
        ...packet.verifications.map((verification) => ({
          key: verification.id,
          content: (
            <Space size={6} wrap>
              <span>{t('verify')}</span>
              <Code family="verificationBasis" code={verification.verificationBasis} />
              <Code family="managementMatch" code={verification.managementMatch} />
              <Code family="displayState" code={verification.displayState} />
            </Space>
          ),
        })),
      ]}
    />
  );
}

/** What an expanded packet row shows. */
function PacketDetail({ packet }: { readonly packet: ManualPacket }): React.JSX.Element {
  return (
    <Stack>
      <div>
        <SubTitle>{packetText.targetText}</SubTitle>
        {packet.targetText === undefined ? (
          <Typography.Text type="secondary">{packetText.noTargetText}</Typography.Text>
        ) : (
          <RussianText value={packet.targetText} />
        )}
      </div>
      <div>
        <SubTitle>{packetText.history}</SubTitle>
        <PacketHistory packet={packet} />
      </div>
      <TechnicalDetails>
        <Space orientation="vertical" size={2}>
          <IdText label={t('packetId')} value={packet.id} />
          <IdText label={t('actionId')} value={packet.actionId} />
          <IdText label={t('executor')} value={packet.executorUserId} />
          {packet.reports.map((report) => (
            <IdText key={report.id} label={t('reporter')} value={report.reporterUserId} />
          ))}
        </Space>
      </TechnicalDetails>
    </Stack>
  );
}

interface IssueValues {
  actionId?: string;
  executorUserId?: string;
}

/** The executor picker of the issue dialog; it follows the chosen action. */
function ExecutorField({
  context,
  value,
  onChange,
  id,
}: {
  readonly context: ConsoleRequest;
  readonly value?: string | undefined;
  readonly onChange?: ((value: string | undefined) => void) | undefined;
  readonly id?: string | undefined;
}): React.JSX.Element {
  const form = Form.useFormInstance<IssueValues>();
  const actionId = Form.useWatch('actionId', form);
  // A person eligible for one action may not be for another.
  const previous = useRef(actionId);
  useEffect(() => {
    if (previous.current !== actionId) {
      previous.current = actionId;
      form.setFieldValue('executorUserId', undefined);
    }
  }, [actionId, form]);
  return (
    <PersonField
      context={context}
      role="MANUAL_EXECUTOR"
      targetId={actionId}
      needsTarget={packetText.issueExecutorNeedsAction}
      value={value}
      onChange={onChange}
      id={id}
    />
  );
}

/** Issue a packet from a launched action on the manual path. */
function IssuePacket({
  context,
  launched,
  preselected,
  onIssued,
}: {
  readonly context: ConsoleRequest;
  readonly launched: {
    readonly options: PickOption[];
    readonly loading: boolean;
    readonly failed: boolean;
  };
  readonly preselected: string | undefined;
  readonly onIssued: (actionId: string) => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  return (
    <ActionModal<IssueValues>
      trigger={{ label: t('issuePacket'), type: 'primary' }}
      title={packetText.issueTitle}
      consequence={packetText.issueConsequence}
      okText={t('issuePacket')}
      {...(preselected === undefined ? {} : { initialValues: { actionId: preselected } })}
      onSubmit={async (values) => {
        const actionId = (values.actionId ?? '').trim();
        const outcome = await issuePacket(context, actionId, (values.executorUserId ?? '').trim());
        if (!outcome.ok) return outcome.failure;
        void message.success(packetText.issued);
        onIssued(actionId);
        return undefined;
      }}
    >
      <Form.Item
        name="actionId"
        label={packetText.issueAction}
        extra={packetText.issueActionHelp}
        rules={idRules(packetText.issueAction)}
      >
        <PickOrType
          options={launched.options}
          loading={launched.loading}
          unavailable={launched.failed}
        />
      </Form.Item>
      <Form.Item
        name="executorUserId"
        label={packetText.issueExecutor}
        extra={packetText.issueExecutorHelp}
        rules={idRules(packetText.issueExecutor)}
      >
        <ExecutorField context={context} />
      </Form.Item>
    </ActionModal>
  );
}

interface ReportValues {
  operationTime?: string;
  reportState?: string;
  note?: string;
}

/** The executor's report on one issued packet. */
function ReportModal({
  context,
  packet,
  open,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly packet: ManualPacket;
  readonly open: boolean;
  readonly onClose: () => void;
  readonly onDone: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  return (
    <ActionModal<ReportValues>
      open={open}
      onClose={onClose}
      title={`${packetText.reportTitle} · ${packet.nativeListingKey}`}
      consequence={packetText.reportConsequence}
      summary={
        packet.targetText === undefined ? undefined : (
          <div>
            <Typography.Text type="secondary">{packetText.targetText}</Typography.Text>
            <RussianText value={packet.targetText} />
          </div>
        )
      }
      okText={t('report')}
      width={640}
      initialValues={{ reportState: 'APPLIED' }}
      onSubmit={async (values) => {
        const outcome = await reportPacket(
          context,
          packet.id,
          values.operationTime ?? '',
          values.reportState ?? '',
          (values.note ?? '').trim(),
        );
        if (!outcome.ok) return outcome.failure;
        void message.success(packetText.reported);
        onDone();
        return undefined;
      }}
    >
      <Row gutter={16}>
        <Col xs={24} md={14}>
          <Form.Item
            name="operationTime"
            label={packetText.reportOperationTime}
            rules={[
              { required: true, message: `请选择${packetText.reportOperationTime}` },
              notInFutureRule,
            ]}
          >
            <InstantField />
          </Form.Item>
        </Col>
        <Col xs={24} md={10}>
          <Form.Item
            name="reportState"
            label={packetText.reportState}
            rules={[{ required: true, message: `请选择${packetText.reportState}` }]}
          >
            <Select options={codeOptions('reportState', ['APPLIED', 'NOT_APPLIED', 'PARTIAL'])} />
          </Form.Item>
        </Col>
      </Row>
      <Form.Item
        name="note"
        label={packetText.reportNote}
        rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
      >
        <Input.TextArea
          rows={3}
          maxLength={TEXT_LIMIT}
          showCount
          placeholder={packetText.reportNotePlaceholder}
        />
      </Form.Item>
    </ActionModal>
  );
}

interface VerifyValues {
  basis?: string;
  managementObservationId?: string;
  managementMatch?: string;
  displayObservationId?: string;
  displayState?: string;
  promotionObservationId?: string;
  note?: string;
}

/** Independent verification of one reported packet, by someone other than its executor. */
function VerifyDrawer({
  context,
  packet,
  open,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly packet: ManualPacket;
  readonly open: boolean;
  readonly onClose: () => void;
  readonly onDone: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const action = useRemote(open ? `action:${packet.actionId}` : undefined, () =>
    fetchAction(context, packet.actionId),
  );
  const listingId = action.value?.platformListingId;
  const observations = useRemote(
    open && listingId !== undefined ? `observations:${listingId}` : undefined,
    () => fetchListingObservations(context, listingId ?? '', 20),
  );
  const loading = action.loading || observations.loading;
  const unavailable = action.failed || observations.failed;
  const excluded = executorsOf(packet);
  const promotion =
    action.value === undefined || action.value.actionKind === 'LISTING_PROMOTION_ACTION';
  const latest = packet.reports.at(-1);

  return (
    <FormDrawer<VerifyValues>
      open={open}
      onClose={onClose}
      title={`${packetText.verifyTitle} · ${packet.nativeListingKey}`}
      submitText={packetText.verifySubmit}
      initialValues={{ basis: 'INDEPENDENT_HUMAN' }}
      intro={
        <Stack>
          <Row gutter={16}>
            <Col xs={24} md={14}>
              <Typography.Text type="secondary">{packetText.verifyIntroTarget}</Typography.Text>
              {packet.targetText === undefined ? (
                <div>
                  <Typography.Text type="secondary">{packetText.noTargetText}</Typography.Text>
                </div>
              ) : (
                <RussianText value={packet.targetText} />
              )}
            </Col>
            <Col xs={24} md={10}>
              <Typography.Text type="secondary">{packetText.verifyIntroReport}</Typography.Text>
              {latest === undefined ? (
                <div>
                  <Typography.Text type="secondary">{packetText.verifyNoReport}</Typography.Text>
                </div>
              ) : (
                <Flex vertical gap={4} style={{ marginTop: 4 }}>
                  <Code family="reportState" code={latest.reportState} />
                  <Typography.Text>{latest.note}</Typography.Text>
                </Flex>
              )}
            </Col>
          </Row>
          {loading && <Hint>{packetText.loadingObservations}</Hint>}
          {action.failed && <Hint>{packetText.actionUnreadable}</Hint>}
        </Stack>
      }
      onSubmit={async (values) => {
        const managementObservationId = optionalId(values.managementObservationId);
        const displayObservationId = optionalId(values.displayObservationId);
        const promotionObservationId = promotion
          ? optionalId(values.promotionObservationId)
          : undefined;
        const outcome = await verifyPacket(
          context,
          packet.id,
          values.basis ?? '',
          values.managementMatch ?? '',
          values.displayState ?? '',
          (values.note ?? '').trim(),
          {
            ...(managementObservationId === undefined ? {} : { managementObservationId }),
            ...(displayObservationId === undefined ? {} : { displayObservationId }),
            ...(promotionObservationId === undefined ? {} : { promotionObservationId }),
          },
        );
        if (!outcome.ok) return outcome.failure;
        void message.success(packetText.verified);
        onDone();
        return undefined;
      }}
    >
      <Form.Item
        name="basis"
        label={packetText.verifyBasis}
        rules={[{ required: true, message: `请选择${packetText.verifyBasis}` }]}
      >
        <Select
          options={codeOptions('verificationBasis', ['INDEPENDENT_HUMAN', 'OFFICIAL_EVIDENCE'])}
        />
      </Form.Item>
      <Form.Item
        name="managementObservationId"
        label={packetText.verifyDescription}
        extra={packetText.verifyDescriptionHelp}
        rules={idRules(packetText.verifyDescription, false)}
      >
        <PickOrType
          options={descriptionOptions(
            observations.value?.description ?? [],
            action.value?.targetTextDigest,
            excluded,
          )}
          loading={loading}
          unavailable={unavailable}
        />
      </Form.Item>
      <Form.Item
        name="managementMatch"
        label={packetText.verifyMatch}
        rules={[{ required: true, message: `请选择${packetText.verifyMatch}` }]}
      >
        <Select
          placeholder={t('undeclared')}
          options={codeOptions('managementMatch', [
            'MATCHED_TARGET',
            'MATCHED_PRIOR',
            'DIFFERENT',
            'UNKNOWN',
          ])}
        />
      </Form.Item>
      <Form.Item
        name="displayObservationId"
        label={packetText.verifyDisplay}
        rules={idRules(packetText.verifyDisplay, false)}
      >
        <PickOrType
          options={displayOptions(observations.value?.display ?? [], excluded)}
          loading={loading}
          unavailable={unavailable}
        />
      </Form.Item>
      <Form.Item
        name="displayState"
        label={packetText.verifyDisplayState}
        rules={[{ required: true, message: `请选择${packetText.verifyDisplayState}` }]}
      >
        <Select
          placeholder={t('undeclared')}
          options={codeOptions('displayState', ['DISPLAYED', 'NOT_DISPLAYED', 'UNKNOWN'])}
        />
      </Form.Item>
      {promotion && (
        <Form.Item
          name="promotionObservationId"
          label={packetText.verifyPromotion}
          extra={t('promotionVerificationExtent')}
          rules={idRules(packetText.verifyPromotion, false)}
        >
          <PickOrType
            options={promotionOptions(observations.value?.promotion ?? [], excluded)}
            loading={loading}
            unavailable={unavailable}
          />
        </Form.Item>
      )}
      <Form.Item
        name="note"
        label={packetText.verifyNote}
        rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
      >
        <Input.TextArea
          rows={3}
          maxLength={TEXT_LIMIT}
          showCount
          placeholder={packetText.verifyNotePlaceholder}
        />
      </Form.Item>
    </FormDrawer>
  );
}

/** Which row action is open, kept so the dialog can animate closed. */
interface Acting {
  readonly kind: 'report' | 'verify';
  readonly packet: ManualPacket;
  readonly open: boolean;
}

/**
 * Governed manual execution packets: issued from a launched manual action,
 * reported by their executor and verified by someone else.
 */
export function ListingManualPackets({
  context,
}: {
  readonly context: ConsoleRequest;
}): React.JSX.Element {
  const [view] = useSearchParam(VIEW_KEY);
  const [actionParam] = useSearchParam(ACTION_KEY);
  const patch = useSearchParamsPatch();
  const byAction = view === 'action';
  const selectedAction = isUuid(actionParam) ? actionParam : undefined;
  const [generation, setGeneration] = useState(0);
  const [acting, setActing] = useState<Acting | undefined>(undefined);

  const actions = useRemote(`manual-actions:${String(generation)}`, () =>
    fetchActionsBy(context, { executionPath: 'MANUAL', limit: 200 }),
  );
  // Only launched manual actions can receive a packet; the server filters
  // them, so an older launched action is not lost behind newer ones.
  const launchedActions = useRemote(`manual-launched:${String(generation)}`, () =>
    fetchActionsBy(context, { executionPath: 'MANUAL', state: 'LAUNCHED', limit: 200 }),
  );
  const launched = launchedActions.value ?? [];

  const loadKey = byAction
    ? selectedAction === undefined
      ? undefined
      : `action:${selectedAction}:${String(generation)}`
    : `mine:${String(generation)}`;
  const [loaded, setLoaded] = useState<
    | {
        readonly key: string;
        readonly packets: readonly ManualPacket[] | undefined;
        readonly failure: ConsoleFailure | undefined;
      }
    | undefined
  >(undefined);
  useEffect(() => {
    if (loadKey === undefined) return;
    let live = true;
    const request =
      selectedAction !== undefined && loadKey.startsWith('action:')
        ? fetchActionPackets(context, selectedAction)
        : fetchMyPackets(context);
    void request.then((outcome) => {
      if (!live) return;
      setLoaded(
        outcome.ok
          ? { key: loadKey, packets: outcome.value, failure: undefined }
          : { key: loadKey, packets: undefined, failure: outcome.failure },
      );
    });
    return () => {
      live = false;
    };
  }, [context, loadKey, selectedAction]);
  const current = loaded !== undefined && loaded.key === loadKey ? loaded : undefined;
  const packets = current?.packets;
  const refresh = (): void => {
    setGeneration((value) => value + 1);
  };

  const columns: TableColumnsType<ManualPacket> = [
    {
      key: 'listing',
      title: packetText.listingColumn,
      render: (_, packet) => <Typography.Text strong>{packet.nativeListingKey}</Typography.Text>,
    },
    {
      key: 'state',
      title: packetText.stateColumn,
      render: (_, packet) => <Code family="packetState" code={packet.state} />,
    },
    {
      key: 'expires',
      title: packetText.expiresColumn,
      render: (_, packet) => <When value={packet.expiresAt} />,
    },
    {
      key: 'reports',
      title: packetText.reportsColumn,
      align: 'right',
      render: (_, packet) => packet.reports.length,
    },
    {
      key: 'verifications',
      title: packetText.verificationsColumn,
      align: 'right',
      render: (_, packet) => packet.verifications.length,
    },
    {
      key: 'actions',
      title: packetText.actionsColumn,
      render: (_, packet) => {
        const report = (label: string): React.JSX.Element => (
          <Button
            size="small"
            onClick={() => {
              setActing({ kind: 'report', packet, open: true });
            }}
          >
            {label}
          </Button>
        );
        if (packet.state === 'ISSUED') {
          return report(t('report'));
        }
        if (packet.state === 'REPORTED') {
          // The executor may add a later report, for example after a partial
          // one; verification must come from someone else. "My packets" are
          // the ones I execute, so verifying is not offered there.
          return byAction ? (
            <Button
              size="small"
              onClick={() => {
                setActing({ kind: 'verify', packet, open: true });
              }}
            >
              {t('verify')}
            </Button>
          ) : (
            <Flex vertical gap={2} align="flex-start">
              {report(packetText.followUpReport)}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {packetText.verifyByOthers}
              </Typography.Text>
            </Flex>
          );
        }
        return <Typography.Text type="secondary">—</Typography.Text>;
      },
    },
  ];

  const closeActing = (): void => {
    setActing((previous) => (previous === undefined ? undefined : { ...previous, open: false }));
  };

  return (
    <SectionCard
      title={t('packets')}
      extra={
        <Flex gap={8} wrap align="center">
          <Segmented<string>
            value={byAction ? 'action' : 'mine'}
            options={[
              { value: 'mine', label: packetText.viewMine },
              { value: 'action', label: packetText.viewByAction },
            ]}
            onChange={(next) => {
              patch({ [VIEW_KEY]: next === 'action' ? 'action' : undefined });
            }}
          />
          {byAction &&
            (actions.failed ? (
              <IdLookup
                label={packetText.actionFilterManual}
                title={packetText.actionFilterManualTitle}
                fieldLabel={t('actionIdInput')}
                initial={selectedAction}
                onOpenId={(value) => {
                  patch({ [VIEW_KEY]: 'action', [ACTION_KEY]: value });
                }}
              />
            ) : (
              <Flex align="center" gap={0}>
                <Select<string>
                  style={{ width: 280 }}
                  aria-label={packetText.actionFilter}
                  placeholder={packetText.actionFilter}
                  loading={actions.loading}
                  allowClear
                  showSearch={{ optionFilterProp: 'search' }}
                  value={selectedAction ?? null}
                  options={actionOptions(actions.value ?? [])}
                  onChange={(next: string | undefined) => {
                    patch({ [VIEW_KEY]: 'action', [ACTION_KEY]: next });
                  }}
                />
                <InfoTip title={packetText.actionFilterHelp} />
                <IdLookup
                  label={packetText.actionFilterManual}
                  title={packetText.actionFilterManualTitle}
                  fieldLabel={t('actionIdInput')}
                  initial={selectedAction}
                  onOpenId={(value) => {
                    patch({ [VIEW_KEY]: 'action', [ACTION_KEY]: value });
                  }}
                />
              </Flex>
            ))}
          <IssuePacket
            context={context}
            launched={{
              options: actionOptions(launched),
              loading: launchedActions.loading,
              failed: launchedActions.failed,
            }}
            preselected={
              selectedAction !== undefined && launched.some((a) => a.id === selectedAction)
                ? selectedAction
                : undefined
            }
            onIssued={(actionId) => {
              patch({ [VIEW_KEY]: 'action', [ACTION_KEY]: actionId });
              refresh();
            }}
          />
          <Button icon={<ReloadOutlined />} aria-label={packetText.refresh} onClick={refresh}>
            {t('refresh')}
          </Button>
        </Flex>
      }
    >
      <section
        aria-label={t('packets')}
        data-state={loadKey === undefined ? 'idle' : current === undefined ? 'loading' : 'loaded'}
      >
        <Stack>
          {current?.failure !== undefined && <ListingProblem failure={current.failure} />}
          {loadKey === undefined && <EmptyState description={packetText.pickActionFirst} />}
          {loadKey !== undefined && current === undefined && <LoadingState />}
          {packets?.length === 0 && <EmptyState description={t('noPackets')} />}
          {packets !== undefined && packets.length > 0 && (
            <Table<ManualPacket>
              rowKey="id"
              size="small"
              columns={columns}
              dataSource={[...packets]}
              pagination={false}
              expandable={{ expandedRowRender: (packet) => <PacketDetail packet={packet} /> }}
            />
          )}
        </Stack>
      </section>
      {acting?.kind === 'report' && (
        <ReportModal
          key={`report:${acting.packet.id}`}
          context={context}
          packet={acting.packet}
          open={acting.open}
          onClose={closeActing}
          onDone={refresh}
        />
      )}
      {acting?.kind === 'verify' && (
        <VerifyDrawer
          key={`verify:${acting.packet.id}`}
          context={context}
          packet={acting.packet}
          open={acting.open}
          onClose={closeActing}
          onDone={refresh}
        />
      )}
    </SectionCard>
  );
}
