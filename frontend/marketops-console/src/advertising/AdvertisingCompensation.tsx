import { App, Form, Select, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { actOnAdvertisingCompensation, fetchAdvertisingCompensation } from '../api/console';
import type { ConsoleRequest, ConsoleFailure } from '../api/console';
import { formatMoney } from '../format';
import {
  BID_UNIT_LABELS,
  COMPENSATION_ACTION_LABELS,
  COMPENSATION_STATE_COLORS,
  COMPENSATION_STATE_LABELS,
} from '../i18n/zh/advertising';
import { dialog } from '../i18n/zh/common';
import { ActionModal } from '../ui/ActionModal';
import type { SubmitOutcome } from '../ui/ActionModal';
import { CodeTag } from '../ui/CodeTag';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import { Money } from '../ui/Money';
import { WriteConfirmModal } from '../ui/WriteConfirmModal';
import { AbsentValue, decimalField, field, stringList } from './shared';

/** The two signatures that let a restore reach the platform. */
const SIGNATURES = {
  ENDORSE: {
    title: '为恢复原出价背书',
    consequence: '背书人必须独立于发起人。背书本身不会写入平台，批准后才会创建恢复指令。',
    confirm: (prior: string) => `确认背书：恢复到 ${prior}`,
  },
  APPROVE: {
    title: '批准恢复原出价',
    consequence:
      '批准后将创建恢复指令，经写入闸门发往平台并回读确认；若会覆盖之后的修改，将被阻止。',
    confirm: (prior: string) => `确认批准：恢复到 ${prior}`,
  },
} as const;

interface PreviewValues {
  readonly bundleId?: string;
}

export function AdvertisingCompensation({
  context,
  commandId,
}: {
  readonly context: ConsoleRequest;
  readonly commandId: string;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [state, setState] = useState<Readonly<Record<string, unknown>>>(),
    [failure, setFailure] = useState<ConsoleFailure>();
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    void fetchAdvertisingCompensation(context, commandId).then((result) => {
      if (!active) return;
      if (result.ok) {
        setState(result.value);
        setFailure(undefined);
      } else setFailure(result.failure);
    });
    return () => {
      active = false;
    };
  }, [context, commandId, revision]);
  const actions = stringList(state, 'allowedActions');
  const bundles = stringList(state, 'availableBundleIds');
  // A single approved bundle is the only choice, so the dialog starts with it chosen.
  const onlyBundle = bundles.length === 1 ? bundles[0] : undefined;
  const currency = field(state, 'currencyCode') ?? null;
  const owner = decimalField(state, 'currentOwnerBid');
  const prior = decimalField(state, 'exactPriorBid');

  async function act(
    action: 'PREVIEW' | 'ENDORSE' | 'APPROVE',
    bundleId = '',
  ): Promise<SubmitOutcome> {
    const result = await actOnAdvertisingCompensation(
      context,
      commandId,
      typeof state?.existingPreviewId === 'string' ? state.existingPreviewId : undefined,
      action,
      bundleId,
    );
    if (!result.ok) return result.failure;
    void message.success(`「${COMPENSATION_ACTION_LABELS[action] ?? action}」已记录`);
    setRevision((value) => value + 1);
    return undefined;
  }

  const change =
    state === undefined ? null : (
      <Space size={6} wrap>
        <Typography.Text type="secondary">当前出价</Typography.Text>
        {owner === undefined ? (
          <AbsentValue label="未确定" />
        ) : (
          <Money value={owner} currency={currency} />
        )}
        <Typography.Text type="secondary">→ 原出价</Typography.Text>
        {prior === undefined ? (
          <AbsentValue label="未确定" />
        ) : (
          <Money value={prior} currency={currency} strong />
        )}
        <CodeTag labels={BID_UNIT_LABELS} code={field(state, 'bidUnitCode') ?? 'UNRESOLVED'} />
      </Space>
    );

  return (
    <section aria-label="恢复精确原出价">
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        <Typography.Text strong>恢复精确原出价</Typography.Text>
        {failure !== undefined && <FailureAlert failure={failure} />}
        {state === undefined && failure === undefined && <LoadingState rows={2} />}
        {state !== undefined && (
          <>
            <Space size={6} wrap>
              <CodeTag
                labels={COMPENSATION_STATE_LABELS}
                code={field(state, 'state')}
                colors={COMPENSATION_STATE_COLORS}
              />
              {change}
            </Space>
            <Space size={[8, 8]} wrap>
              {actions.includes('PREVIEW') && (
                <ActionModal<PreviewValues>
                  trigger={{ label: COMPENSATION_ACTION_LABELS.PREVIEW ?? 'PREVIEW' }}
                  title="准备恢复到精确的原出价"
                  consequence="只生成恢复预览，平台出价不会改变；还需背书和批准后才会执行。"
                  summary={change}
                  okText="生成恢复预览"
                  {...(onlyBundle === undefined ? {} : { initialValues: { bundleId: onlyBundle } })}
                  onSubmit={(values) => act('PREVIEW', values.bundleId ?? '')}
                >
                  <Form.Item
                    name="bundleId"
                    label="当前恢复策略包"
                    rules={[{ required: true, message: '请选择恢复策略包' }]}
                  >
                    <Select
                      placeholder="选择已批准的范围"
                      options={bundles.map((id) => ({ value: id, label: id }))}
                      notFoundContent="没有可用的恢复策略包"
                    />
                  </Form.Item>
                </ActionModal>
              )}
              {(['ENDORSE', 'APPROVE'] as const)
                .filter((action) => actions.includes(action))
                .map((action) => (
                  <WriteConfirmModal
                    key={action}
                    trigger={{
                      label: COMPENSATION_ACTION_LABELS[action] ?? action,
                      type: action === 'APPROVE' ? 'primary' : 'default',
                    }}
                    title={SIGNATURES[action].title}
                    impact={change}
                    consequence={SIGNATURES[action].consequence}
                    confirmText={SIGNATURES[action].confirm(formatMoney(prior, currency))}
                    reasonLabel="备注（可选，不会保存）"
                    reasonPlaceholder={dialog.notePlaceholder}
                    reasonRequired={false}
                    onConfirm={() => act(action)}
                  />
                ))}
            </Space>
          </>
        )}
      </Space>
    </section>
  );
}
