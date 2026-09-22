import {
  Alert,
  App,
  Button,
  Checkbox,
  DatePicker,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import type { Dayjs } from 'dayjs';
import { useEffect, useState } from 'react';
import {
  actOnAdvertisingManualPacket,
  fetchAdvertisingManualOptions,
  selectAdvertisingManualOption,
} from '../api/console';
import type {
  AdvertisingManualAction,
  AdvertisingManualOption,
  AdvertisingManualOptions,
  ConsoleFailure,
  ConsoleRequest,
} from '../api/console';
import type {
  AdvertisingManualIndependentObservation,
  AdvertisingManualPacket,
} from '../api/advertising';
import { STORE_TIMEZONE_LABEL, storeLocalToIso } from '../format';
import { codeLabel } from '../i18n';
import {
  BID_UNIT_LABELS,
  COMPLETENESS_LABELS,
  FIELD_PATH_LABELS,
  MANUAL_ACTION_KIND_LABELS,
  MANUAL_ACTION_LABELS,
  OBSERVATION_SOURCE_LABELS,
  PROFILE_VERIFICATION_COLORS,
  PROFILE_VERIFICATION_LABELS,
  VERIFICATION_MODE_LABELS,
} from '../i18n/zh/advertising';
import { CodeTag } from '../ui/CodeTag';
import { ConfirmButton } from '../ui/ConfirmButton';
import { EmptyState } from '../ui/EmptyState';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import { Money } from '../ui/Money';
import { AbsentValue, ReasonTags, plainDecimal } from './shared';

const ACTION_ORDER: readonly AdvertisingManualAction[] = [
  'ENDORSE',
  'APPROVE',
  'START',
  'REPORT',
  'INDEPENDENT_VERIFY',
  'OFFICIAL_VERIFY',
  'OBSERVE_EARLY_SAFETY',
];

/** Confirmation text for the actions that are sent straight away. */
const CONFIRMATIONS: Partial<Record<AdvertisingManualAction, string>> = {
  ENDORSE: '确认为该人工操作单背书？',
  APPROVE: '确认批准该人工操作单？',
  START: '确认开始执行已批准的人工操作？',
  REPORT: '确认报告已执行？报告不等于证明配置已生效。',
  OBSERVE_EARLY_SAFETY: '确认记录标准早期销售安全观察？',
};

type Source = AdvertisingManualIndependentObservation['evidenceSource'];
type Completeness = AdvertisingManualIndependentObservation['completeness'];

export function AdvertisingManualPacketControls({
  context,
  packet,
  reload,
}: {
  readonly context: ConsoleRequest;
  readonly packet: AdvertisingManualPacket;
  readonly reload: () => void;
}): React.JSX.Element | null {
  const { message } = App.useApp();
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [dialog, setDialog] = useState<'INDEPENDENT_VERIFY' | 'OFFICIAL_VERIFY'>();
  const [observed, setObserved] = useState('');
  const [configuration, setConfiguration] = useState('');
  const [observedAt, setObservedAt] = useState<Dayjs | null>(null);
  const [source, setSource] = useState<Source | undefined>(undefined);
  const [completeness, setCompleteness] = useState<Completeness | undefined>(undefined);
  const [evidenceReference, setEvidenceReference] = useState('');
  const [directAttested, setDirectAttested] = useState(false);
  const exactFieldPath = packet.packetDetails?.verificationFieldPath;
  const semanticProfileId = packet.packetDetails?.semanticProfileId;
  const nativeObjectKey = packet.packetDetails?.nativeObjectKey;
  const observation: AdvertisingManualIndependentObservation | undefined =
    observed.trim().length > 0 &&
    observedAt !== null &&
    observedAt.isValid() &&
    source !== undefined &&
    completeness !== undefined &&
    evidenceReference.trim().length > 0 &&
    typeof semanticProfileId === 'string' &&
    semanticProfileId.length > 0 &&
    (exactFieldPath === 'targetBid' ||
      exactFieldPath === 'targetBudget' ||
      exactFieldPath === 'targetStatus') &&
    (source !== 'DIRECT_OFFICIAL_CONSOLE' || completeness !== 'COMPLETE' || directAttested)
      ? {
          observedValue: observed.trim(),
          observedAt: storeLocalToIso(observedAt),
          evidenceSource: source,
          completeness,
          exactNativeObjectId: packet.adNativeObjectId,
          exactFieldPath,
          semanticProfileId,
          evidenceReference: evidenceReference.trim(),
          directObservationAttested: source === 'DIRECT_OFFICIAL_CONSOLE' && directAttested,
        }
      : undefined;
  const available = ACTION_ORDER.filter((action) => packet.allowedActions.includes(action));
  if (available.length === 0 || packet.version === undefined) return null;

  async function act(action: AdvertisingManualAction): Promise<void> {
    setBusy(true);
    setFailure(undefined);
    const result = await actOnAdvertisingManualPacket(
      context,
      packet,
      action,
      action === 'OFFICIAL_VERIFY' ? configuration : observed,
      action === 'INDEPENDENT_VERIFY' ? observation : undefined,
    );
    setBusy(false);
    if (result.ok) {
      void message.success(`「${codeLabel(MANUAL_ACTION_LABELS, action)}」已记录`);
      setDialog(undefined);
      reload();
    } else setFailure(result.failure);
  }

  const fieldName =
    typeof exactFieldPath === 'string' && Object.hasOwn(FIELD_PATH_LABELS, exactFieldPath)
      ? FIELD_PATH_LABELS[exactFieldPath]
      : '不可用';

  return (
    <section aria-label="人工操作单操作">
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        {failure !== undefined && dialog === undefined && <FailureAlert failure={failure} />}
        <Space size={[8, 8]} wrap>
          {available.map((action) =>
            action === 'INDEPENDENT_VERIFY' || action === 'OFFICIAL_VERIFY' ? (
              <Button
                key={action}
                disabled={busy}
                onClick={() => {
                  setFailure(undefined);
                  setDialog(action);
                }}
              >
                {codeLabel(MANUAL_ACTION_LABELS, action)}
              </Button>
            ) : (
              <ConfirmButton
                key={action}
                type={action === 'APPROVE' ? 'primary' : 'default'}
                title={CONFIRMATIONS[action] ?? '确认执行该操作？'}
                disabled={busy}
                disabledReason="正在处理…"
                onConfirm={() => act(action)}
              >
                {codeLabel(MANUAL_ACTION_LABELS, action)}
              </ConfirmButton>
            ),
          )}
        </Space>
      </Space>

      <Modal
        title="记录你实际观察到的配置"
        open={dialog === 'INDEPENDENT_VERIFY'}
        okText="提交观察"
        cancelText="取消"
        confirmLoading={busy}
        okButtonProps={{ disabled: observation === undefined }}
        onOk={() => {
          void act('INDEPENDENT_VERIFY');
        }}
        onCancel={() => {
          setDialog(undefined);
        }}
        destroyOnHidden
      >
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {failure !== undefined && <FailureAlert failure={failure} />}
          <Typography.Paragraph style={{ margin: 0 }}>
            请确认对象：
            <Typography.Text code copyable>
              {typeof nativeObjectKey === 'string' ? nativeObjectKey : packet.adNativeObjectId}
            </Typography.Text>
            ，字段：{fieldName}。
          </Typography.Paragraph>
          <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
            填写你看到配置的时间；这并不能确定变更实际生效的时间。未知实际观察时间时无法提交。
          </Typography.Paragraph>
          <Form layout="vertical">
            <Form.Item label="独立观察到的精确平台值" required>
              <Input
                aria-label="独立观察到的精确平台值"
                value={observed}
                maxLength={128}
                onChange={(event) => {
                  setObserved(event.target.value);
                }}
              />
            </Form.Item>
            <Form.Item label="观察来源" required>
              <Select<Source>
                aria-label="观察来源"
                placeholder="选择来源"
                {...(source === undefined ? {} : { value: source })}
                options={(['DIRECT_OFFICIAL_CONSOLE', 'SCREENSHOT'] as const).map((value) => ({
                  value,
                  label: OBSERVATION_SOURCE_LABELS[value],
                }))}
                onChange={(value) => {
                  setSource(value);
                  setDirectAttested(false);
                }}
              />
            </Form.Item>
            <Form.Item label="观察完整性" required>
              <Select<Completeness>
                aria-label="观察完整性"
                placeholder="选择完整性"
                {...(completeness === undefined ? {} : { value: completeness })}
                options={(['COMPLETE', 'INCOMPLETE'] as const).map((value) => ({
                  value,
                  label: COMPLETENESS_LABELS[value],
                }))}
                onChange={(value) => {
                  setCompleteness(value);
                }}
              />
            </Form.Item>
            <Form.Item label={`实际观察时间（${STORE_TIMEZONE_LABEL}）`} required>
              <DatePicker
                aria-label="实际观察时间"
                showTime={{ format: 'HH:mm:ss' }}
                format="YYYY-MM-DD HH:mm:ss"
                value={observedAt}
                onChange={(value: Dayjs | null) => {
                  setObservedAt(value);
                }}
              />
            </Form.Item>
            <Form.Item label="观察证据引用" required>
              <Input
                aria-label="观察证据引用"
                value={evidenceReference}
                maxLength={512}
                onChange={(event) => {
                  setEvidenceReference(event.target.value);
                }}
              />
            </Form.Item>
            {source === 'DIRECT_OFFICIAL_CONSOLE' && (
              <Form.Item>
                <Checkbox
                  checked={directAttested}
                  onChange={(event) => {
                    setDirectAttested(event.target.checked);
                  }}
                >
                  我已在官方后台直接看到该对象和字段
                </Checkbox>
              </Form.Item>
            )}
          </Form>
          {(source === 'SCREENSHOT' || completeness === 'INCOMPLETE') && (
            <Alert
              type="warning"
              showIcon
              title="截图或不完整的观察只作为证据记录，不能证明配置已生效，也不会释放额度占用。"
            />
          )}
        </Space>
      </Modal>

      <Modal
        title="官方证据核验"
        open={dialog === 'OFFICIAL_VERIFY'}
        okText="提交核验"
        cancelText="取消"
        confirmLoading={busy}
        okButtonProps={{ disabled: configuration.trim().length === 0 }}
        onOk={() => {
          void act('OFFICIAL_VERIFY');
        }}
        onCancel={() => {
          setDialog(undefined);
        }}
        destroyOnHidden
      >
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {failure !== undefined && <FailureAlert failure={failure} />}
          <Form layout="vertical">
            <Form.Item label="标准官方配置观察编号" required>
              <Input
                aria-label="标准官方配置观察编号"
                value={configuration}
                onChange={(event) => {
                  setConfiguration(event.target.value);
                }}
              />
            </Form.Item>
          </Form>
        </Space>
      </Modal>
    </section>
  );
}

/** The exact native target of a manual option. */
function OptionTarget({ option }: { readonly option: AdvertisingManualOption }): React.JSX.Element {
  const amount = plainDecimal(option.targetBid ?? option.targetBudget);
  const currency = option.currencyCode === 'UNRESOLVED' ? null : option.currencyCode;
  return (
    <Space size={4} wrap>
      {amount !== undefined ? (
        <Money value={amount} currency={currency} />
      ) : option.targetStatus !== undefined ? (
        // A native platform status value is data: shown exactly as the platform names it.
        <Typography.Text code>{option.targetStatus}</Typography.Text>
      ) : (
        <AbsentValue label="未确定" />
      )}
      <CodeTag labels={BID_UNIT_LABELS} code={option.bidUnitCode} />
    </Space>
  );
}

export function AdvertisingManualProposalControls({
  context,
  caseId,
  reload,
}: {
  readonly context: ConsoleRequest;
  readonly caseId: string;
  readonly reload: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [options, setOptions] = useState<AdvertisingManualOptions>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    void fetchAdvertisingManualOptions(context, caseId).then((result) => {
      if (!active) return;
      if (result.ok) {
        setOptions(result.value);
        setFailure(undefined);
      } else setFailure(result.failure);
    });
    return () => {
      active = false;
    };
  }, [context, caseId]);
  const canSelect = options?.allowedActions.includes('SELECT_MANUAL_PROPOSAL') === true;

  const columns: TableColumnsType<AdvertisingManualOption> = [
    {
      title: '操作类型',
      key: 'kind',
      render: (_, option) => (
        <CodeTag labels={MANUAL_ACTION_KIND_LABELS} code={option.actionKind} />
      ),
    },
    {
      title: '精确目标值',
      key: 'target',
      render: (_, option) => <OptionTarget option={option} />,
    },
    {
      title: '核验方式',
      key: 'verification',
      render: (_, option) => (
        <CodeTag labels={VERIFICATION_MODE_LABELS} code={option.verificationMode} />
      ),
    },
    {
      title: 'API 配置',
      key: 'apiProfile',
      render: (_, option) => (
        <CodeTag
          labels={PROFILE_VERIFICATION_LABELS}
          code={option.apiProfileState}
          colors={PROFILE_VERIFICATION_COLORS}
        />
      ),
    },
    {
      title: '策略版本',
      key: 'policyVersion',
      render: (_, option) => option.policyVersion,
    },
    {
      title: '阻断',
      key: 'blockers',
      render: (_, option) => (
        <ReasonTags
          codes={option.blockerCodes}
          empty={<Typography.Text type="secondary">无</Typography.Text>}
        />
      ),
    },
  ];
  if (canSelect) {
    columns.push({
      title: '操作',
      key: 'select',
      render: (_, option) => (
        <ConfirmButton
          type="primary"
          size="small"
          title="确认选定这个精确人工方案？"
          description="该人工流程不会创建任何 API 指令。"
          disabled={busy || reason.trim().length === 0 || option.blockerCodes.length > 0}
          disabledReason={
            busy
              ? '正在处理…'
              : option.blockerCodes.length > 0
                ? '该方案存在阻断，不可选定'
                : '请先填写选定理由'
          }
          onConfirm={async () => {
            setBusy(true);
            const result = await selectAdvertisingManualOption(
              context,
              caseId,
              option,
              reason.trim(),
            );
            setBusy(false);
            if (result.ok) {
              void message.success('已选定人工方案');
              setReason('');
              setFailure(undefined);
              reload();
            } else setFailure(result.failure);
          }}
        >
          选定
        </ConfirmButton>
      ),
    });
  }

  return (
    <section aria-label="受控人工方案">
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        <Typography.Text strong>Owner 管控的人工执行方案</Typography.Text>
        {failure !== undefined && <FailureAlert failure={failure} />}
        {options === undefined && failure === undefined && <LoadingState rows={2} />}
        {options !== undefined && options.blockerCodes.length > 0 && (
          <Alert
            type="warning"
            showIcon
            title="部分人工方案需要先确定策略"
            description={<ReasonTags codes={options.blockerCodes} color="warning" />}
          />
        )}
        {canSelect && (
          <Form layout="vertical" style={{ maxWidth: 720 }}>
            <Form.Item label="选定理由" required style={{ marginBottom: 0 }}>
              <Input.TextArea
                aria-label="选定理由"
                value={reason}
                maxLength={2000}
                showCount
                rows={2}
                onChange={(event) => {
                  setReason(event.target.value);
                }}
              />
            </Form.Item>
          </Form>
        )}
        {options !== undefined &&
          (options.options.length === 0 ? (
            <EmptyState description="当前没有不可变的 Owner 人工计划和标准方案" />
          ) : (
            <Table<AdvertisingManualOption>
              size="middle"
              rowKey={(option) => `${option.policyId}-${option.candidateId ?? option.actionKind}`}
              columns={columns}
              dataSource={[...options.options]}
              pagination={false}
              scroll={{ x: 'max-content' }}
            />
          ))}
      </Space>
    </section>
  );
}
