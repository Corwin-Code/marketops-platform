import { App, Form, Select, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { actOnAdvertisingCompensation, fetchAdvertisingCompensation } from '../api/console';
import type { ConsoleRequest, ConsoleFailure } from '../api/console';
import {
  BID_UNIT_LABELS,
  COMPENSATION_ACTION_LABELS,
  COMPENSATION_STATE_COLORS,
  COMPENSATION_STATE_LABELS,
} from '../i18n/zh/advertising';
import { CodeTag } from '../ui/CodeTag';
import { ConfirmButton } from '../ui/ConfirmButton';
import { FailureAlert } from '../ui/FailureAlert';
import { LoadingState } from '../ui/LoadingState';
import { Money } from '../ui/Money';
import { AbsentValue, decimalField, field, stringList } from './shared';

const CONFIRMATIONS = {
  PREVIEW: {
    title: '确认准备恢复到精确的原出价？',
    description: '只生成恢复预览，需背书和批准后才会执行。',
  },
  ENDORSE: {
    title: '确认为这次恢复背书？',
    description: '背书人必须独立于发起人。',
  },
  APPROVE: {
    title: '确认批准恢复到原出价？',
    description: '批准后将创建恢复指令并经过回读确认；若会覆盖之后的修改将被阻止。',
  },
} as const;

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
  const [bundle, setBundle] = useState(''),
    [busy, setBusy] = useState(false),
    [revision, setRevision] = useState(0);
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
  const currency = field(state, 'currencyCode') ?? null;
  const owner = decimalField(state, 'currentOwnerBid');
  const prior = decimalField(state, 'exactPriorBid');
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
              <CodeTag
                labels={BID_UNIT_LABELS}
                code={field(state, 'bidUnitCode') ?? 'UNRESOLVED'}
              />
            </Space>
            {actions.includes('PREVIEW') && (
              <Form layout="vertical" style={{ maxWidth: 480 }}>
                <Form.Item label="当前恢复策略包" required style={{ marginBottom: 0 }}>
                  <Select
                    aria-label="当前恢复策略包"
                    placeholder="选择已批准的范围"
                    {...(bundle === '' ? {} : { value: bundle })}
                    options={bundles.map((id) => ({ value: id, label: id }))}
                    onChange={(value: string) => {
                      setBundle(value);
                    }}
                  />
                </Form.Item>
              </Form>
            )}
            <Space size={[8, 8]} wrap>
              {(['PREVIEW', 'ENDORSE', 'APPROVE'] as const)
                .filter((action) => actions.includes(action))
                .map((action) => (
                  <ConfirmButton
                    key={action}
                    type={action === 'APPROVE' ? 'primary' : 'default'}
                    title={CONFIRMATIONS[action].title}
                    description={CONFIRMATIONS[action].description}
                    disabled={busy || (action === 'PREVIEW' && !bundle)}
                    disabledReason={busy ? '正在处理…' : '请先选择恢复策略包'}
                    onConfirm={async () => {
                      setBusy(true);
                      const result = await actOnAdvertisingCompensation(
                        context,
                        commandId,
                        typeof state.existingPreviewId === 'string'
                          ? state.existingPreviewId
                          : undefined,
                        action,
                        bundle,
                      );
                      setBusy(false);
                      if (result.ok) {
                        void message.success(
                          `「${COMPENSATION_ACTION_LABELS[action] ?? action}」已记录`,
                        );
                        setRevision((value) => value + 1);
                      } else setFailure(result.failure);
                    }}
                  >
                    {COMPENSATION_ACTION_LABELS[action]}
                  </ConfirmButton>
                ))}
            </Space>
          </>
        )}
      </Space>
    </section>
  );
}
