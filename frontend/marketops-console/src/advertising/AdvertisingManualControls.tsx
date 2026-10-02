import {
  Alert,
  App,
  Checkbox,
  DatePicker,
  Form,
  Input,
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
import { dialog } from '../i18n/zh/common';
import { ActionModal } from '../ui/ActionModal';
import type { SubmitOutcome } from '../ui/ActionModal';
import { CodeTag } from '../ui/CodeTag';
import { ConfirmButton } from '../ui/ConfirmButton';
import { EmptyState } from '../ui/EmptyState';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import { Money } from '../ui/Money';
import { idRules } from '../ui/PickOrType';
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
type FieldPath = AdvertisingManualIndependentObservation['exactFieldPath'];

interface ObservationValues {
  readonly observedValue?: string;
  readonly evidenceSource?: Source;
  readonly completeness?: Completeness;
  readonly observedAt?: Dayjs | null;
  readonly evidenceReference?: string;
  readonly directObservationAttested?: boolean;
}

interface OfficialValues {
  readonly configurationObservationId?: string;
}

function fieldPathOf(value: unknown): FieldPath | undefined {
  return value === 'targetBid' || value === 'targetBudget' || value === 'targetStatus'
    ? value
    : undefined;
}

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
  const fieldPath = fieldPathOf(packet.packetDetails?.verificationFieldPath);
  const rawProfileId = packet.packetDetails?.semanticProfileId;
  const semanticProfileId =
    typeof rawProfileId === 'string' && rawProfileId.length > 0 ? rawProfileId : undefined;
  const nativeObjectKey = packet.packetDetails?.nativeObjectKey;
  const available = ACTION_ORDER.filter((action) => packet.allowedActions.includes(action));
  if (available.length === 0 || packet.version === undefined) return null;

  /** Sends one action; the dialogs keep a failure, the one-click actions show it above. */
  async function send(
    action: AdvertisingManualAction,
    value: string,
    observation?: AdvertisingManualIndependentObservation,
  ): Promise<SubmitOutcome> {
    const result = await actOnAdvertisingManualPacket(context, packet, action, value, observation);
    if (!result.ok) return result.failure;
    void message.success(`「${codeLabel(MANUAL_ACTION_LABELS, action)}」已记录`);
    reload();
    return undefined;
  }

  const fieldName =
    fieldPath === undefined ? '不可用' : (FIELD_PATH_LABELS[fieldPath] ?? fieldPath);
  const objectKey = typeof nativeObjectKey === 'string' ? nativeObjectKey : packet.adNativeObjectId;

  return (
    <section aria-label="人工操作单操作">
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        {failure !== undefined && <FailureAlert failure={failure} />}
        <Space size={[8, 8]} wrap>
          {available.map((action) => {
            if (action === 'INDEPENDENT_VERIFY') {
              return (
                <ActionModal<ObservationValues>
                  key={action}
                  trigger={{ label: codeLabel(MANUAL_ACTION_LABELS, action) }}
                  title="记录你实际观察到的配置"
                  okText="提交观察"
                  width={560}
                  {...(fieldPath === undefined || semanticProfileId === undefined
                    ? { blockedReason: '该操作单缺少核验字段或配置档案，无法记录观察。' }
                    : {})}
                  summary={
                    <Space orientation="vertical" size={4} style={{ width: '100%' }}>
                      <Typography.Paragraph style={{ margin: 0 }}>
                        请确认对象：
                        <Typography.Text code copyable>
                          {objectKey}
                        </Typography.Text>
                        ，字段：{fieldName}。
                      </Typography.Paragraph>
                      <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
                        填写你看到配置的时间；这并不能确定变更实际生效的时间。未知实际观察时间时无法提交。
                      </Typography.Paragraph>
                    </Space>
                  }
                  onSubmit={(values) => {
                    const { observedAt, evidenceSource, completeness } = values;
                    if (
                      !observedAt ||
                      evidenceSource === undefined ||
                      completeness === undefined ||
                      fieldPath === undefined ||
                      semanticProfileId === undefined
                    )
                      return Promise.resolve(undefined);
                    const observedValue = (values.observedValue ?? '').trim();
                    return send(action, observedValue, {
                      observedValue,
                      observedAt: storeLocalToIso(observedAt),
                      evidenceSource,
                      completeness,
                      exactNativeObjectId: packet.adNativeObjectId,
                      exactFieldPath: fieldPath,
                      semanticProfileId,
                      evidenceReference: (values.evidenceReference ?? '').trim(),
                      directObservationAttested:
                        evidenceSource === 'DIRECT_OFFICIAL_CONSOLE' &&
                        values.directObservationAttested === true,
                    });
                  }}
                >
                  <Form.Item
                    name="observedValue"
                    label="独立观察到的精确平台值"
                    rules={[{ required: true, whitespace: true, message: '请填写观察到的值' }]}
                  >
                    <Input maxLength={128} autoFocus />
                  </Form.Item>
                  <Form.Item
                    name="evidenceSource"
                    label="观察来源"
                    rules={[{ required: true, message: '请选择观察来源' }]}
                  >
                    <Select<Source>
                      placeholder="选择来源"
                      options={(['DIRECT_OFFICIAL_CONSOLE', 'SCREENSHOT'] as const).map(
                        (value) => ({ value, label: OBSERVATION_SOURCE_LABELS[value] }),
                      )}
                    />
                  </Form.Item>
                  <Form.Item
                    name="completeness"
                    label="观察完整性"
                    rules={[{ required: true, message: '请选择观察完整性' }]}
                  >
                    <Select<Completeness>
                      placeholder="选择完整性"
                      options={(['COMPLETE', 'INCOMPLETE'] as const).map((value) => ({
                        value,
                        label: COMPLETENESS_LABELS[value],
                      }))}
                    />
                  </Form.Item>
                  <Form.Item
                    name="observedAt"
                    label={`实际观察时间（${STORE_TIMEZONE_LABEL}）`}
                    rules={[{ required: true, message: '请选择实际观察时间' }]}
                  >
                    <DatePicker showTime={{ format: 'HH:mm:ss' }} format="YYYY-MM-DD HH:mm:ss" />
                  </Form.Item>
                  <Form.Item
                    name="evidenceReference"
                    label="观察证据引用"
                    rules={[{ required: true, whitespace: true, message: '请填写观察证据引用' }]}
                  >
                    <Input maxLength={512} />
                  </Form.Item>
                  <Form.Item noStyle dependencies={['evidenceSource', 'completeness']}>
                    {({ getFieldValue }) => {
                      const source = getFieldValue('evidenceSource') as Source | undefined;
                      const completeness = getFieldValue('completeness') as
                        Completeness | undefined;
                      return (
                        <>
                          {source === 'DIRECT_OFFICIAL_CONSOLE' && (
                            <Form.Item
                              name="directObservationAttested"
                              valuePropName="checked"
                              dependencies={['completeness']}
                              rules={[
                                {
                                  validator: (_: unknown, checked: boolean | undefined) =>
                                    completeness !== 'COMPLETE' || checked === true
                                      ? Promise.resolve()
                                      : Promise.reject(
                                          new Error('完整的直接观察需要确认你已在官方后台看到'),
                                        ),
                                },
                              ]}
                            >
                              <Checkbox>我已在官方后台直接看到该对象和字段</Checkbox>
                            </Form.Item>
                          )}
                          {(source === 'SCREENSHOT' || completeness === 'INCOMPLETE') && (
                            <Alert
                              type="warning"
                              showIcon
                              title="截图或不完整的观察只作为证据记录，不能证明配置已生效，也不会释放额度占用。"
                            />
                          )}
                        </>
                      );
                    }}
                  </Form.Item>
                </ActionModal>
              );
            }
            if (action === 'OFFICIAL_VERIFY') {
              return (
                <ActionModal<OfficialValues>
                  key={action}
                  trigger={{ label: codeLabel(MANUAL_ACTION_LABELS, action) }}
                  title="官方证据核验"
                  consequence="用一条标准官方配置观察核验该操作单的配置是否已生效。"
                  okText="提交核验"
                  onSubmit={(values) =>
                    send(action, (values.configurationObservationId ?? '').trim())
                  }
                >
                  <Form.Item
                    name="configurationObservationId"
                    label="标准官方配置观察编号"
                    extra="暂时没有可选的观察列表，请填写观察编号。"
                    rules={idRules('观察编号')}
                  >
                    <Input placeholder="输入编号（UUID）" autoFocus />
                  </Form.Item>
                </ActionModal>
              );
            }
            return (
              <ConfirmButton
                key={action}
                type={action === 'APPROVE' ? 'primary' : 'default'}
                title={CONFIRMATIONS[action] ?? '确认执行该操作？'}
                disabled={busy}
                disabledReason="正在处理…"
                onConfirm={async () => {
                  setBusy(true);
                  setFailure(undefined);
                  const outcome = await send(action, '');
                  setBusy(false);
                  if (outcome !== undefined) setFailure(outcome);
                }}
              >
                {codeLabel(MANUAL_ACTION_LABELS, action)}
              </ConfirmButton>
            );
          })}
        </Space>
      </Space>
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
        <ActionModal<{ readonly reason?: string }>
          trigger={{
            label: '选定',
            type: 'primary',
            size: 'small',
            disabled: option.blockerCodes.length > 0,
            disabledReason: '该方案存在阻断，不可选定',
          }}
          title="选定人工执行方案"
          consequence="选定后生成人工操作单，需背书和批准后由人在平台后台执行；该流程不会创建任何 API 指令。"
          summary={
            <Space size={6} wrap>
              <CodeTag labels={MANUAL_ACTION_KIND_LABELS} code={option.actionKind} />
              <OptionTarget option={option} />
            </Space>
          }
          okText="确认选定"
          onSubmit={async (values) => {
            const result = await selectAdvertisingManualOption(
              context,
              caseId,
              option,
              (values.reason ?? '').trim(),
            );
            if (!result.ok) return result.failure;
            void message.success('已选定人工方案');
            setFailure(undefined);
            reload();
            return undefined;
          }}
        >
          <Form.Item
            name="reason"
            label="选定理由"
            rules={[{ required: true, whitespace: true, message: dialog.reasonRequired }]}
          >
            <Input.TextArea
              rows={3}
              maxLength={2000}
              showCount
              placeholder={dialog.reasonPlaceholder}
              autoFocus
            />
          </Form.Item>
        </ActionModal>
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
