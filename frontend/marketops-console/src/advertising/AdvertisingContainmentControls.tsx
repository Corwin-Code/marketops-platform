import { App, Form, Input, Space, Typography } from 'antd';
import { StopOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { advertisingControl } from '../api/console';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { recoveryActionLabel } from '../i18n/zh/advertising';
import { ConfirmButton } from '../ui/ConfirmButton';
import { FailureAlert } from '../ui/FailureAlert';

/** The scoped stop actions a case may offer, in the order they are shown. */
const STOP_COMMANDS = [
  {
    code: 'EMERGENCY_ENTITY_HOLD',
    scopeKind: 'ENTITY',
    containmentKind: 'EMERGENCY_ENTITY_HOLD',
    causeClass: 'BUSINESS_HARM',
    label: '紧急冻结该对象',
    confirm: '确认紧急冻结该广告对象？',
    description: '冻结后该对象的广告执行将停止，直到完成恢复评审。',
  },
  {
    code: 'BUSINESS_STORE_STOP',
    scopeKind: 'PLATFORM_STORE_CAPABILITY',
    containmentKind: 'CAPABILITY_QUARANTINED',
    causeClass: 'BUSINESS_HARM',
    label: '因业务损失停止店铺广告',
    confirm: '确认因业务损失停止整个店铺的广告执行？',
    description: '店铺广告能力将被隔离，恢复需要独立评审与批准。',
  },
  {
    code: 'TECHNICAL_STORE_STOP',
    scopeKind: 'PLATFORM_STORE_CAPABILITY',
    containmentKind: 'CAPABILITY_QUARANTINED',
    causeClass: 'PROVIDER_OR_READBACK_DEFECT',
    label: '因技术问题停止店铺广告',
    confirm: '确认因技术调查停止整个店铺的广告执行？',
    description: '店铺广告能力将被隔离，恢复需要独立评审与批准。',
  },
] as const;

/** Codes of every scoped stop action, so a caller can tell whether any applies. */
export const STOP_ACTION_CODES: readonly string[] = STOP_COMMANDS.map((command) => command.code);

export function AdvertisingStopControls({
  context,
  objectId,
  allowedActions,
}: {
  readonly context: ConsoleRequest;
  readonly objectId: string;
  readonly allowedActions: readonly string[];
}): React.JSX.Element | null {
  const { message } = App.useApp();
  const [reason, setReason] = useState(''),
    [evidence, setEvidence] = useState(''),
    [owner, setOwner] = useState('');
  const [busy, setBusy] = useState(false),
    [failure, setFailure] = useState<ConsoleFailure>();
  const commands = STOP_COMMANDS.filter((item) => allowedActions.includes(item.code));
  if (commands.length === 0) return null;
  const incomplete = !reason.trim() || !evidence.trim() || !owner.trim();
  return (
    <section aria-label="范围止损">
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
          止损只停止执行，不会修改平台上的出价；未解决的执行证据会被保留。
        </Typography.Paragraph>
        {failure !== undefined && <FailureAlert failure={failure} />}
        <Form layout="vertical" style={{ maxWidth: 640 }}>
          <Form.Item label="停止原因" required>
            <Input.TextArea
              aria-label="停止原因"
              value={reason}
              rows={3}
              onChange={(event) => {
                setReason(event.target.value);
              }}
            />
          </Form.Item>
          <Form.Item label="证据引用" required>
            <Input
              aria-label="证据引用"
              value={evidence}
              onChange={(event) => {
                setEvidence(event.target.value);
              }}
            />
          </Form.Item>
          <Form.Item label="负责评审的运营主管（用户编号）" required>
            <Input
              aria-label="负责评审的运营主管用户编号"
              value={owner}
              onChange={(event) => {
                setOwner(event.target.value);
              }}
            />
          </Form.Item>
        </Form>
        <Space wrap>
          {commands.map((command) => (
            <ConfirmButton
              key={command.code}
              danger
              icon={<StopOutlined />}
              title={command.confirm}
              description={command.description}
              disabled={busy || incomplete}
              disabledReason={busy ? '正在提交…' : '请先填写停止原因、证据引用和负责人'}
              onConfirm={async () => {
                setBusy(true);
                setFailure(undefined);
                const result = await advertisingControl(
                  context,
                  `containments/objects/${encodeURIComponent(objectId)}/stop`,
                  {
                    scopeKind: command.scopeKind,
                    containmentKind: command.containmentKind,
                    causeClass: command.causeClass,
                    reviewOwnerUserId: owner,
                    reason,
                    evidenceReference: evidence,
                  },
                );
                setBusy(false);
                if (result.ok) {
                  void message.success('已记录范围止损。请查看当前管控，并保留未解决的执行证据。');
                } else setFailure(result.failure);
              }}
            >
              {command.label}
            </ConfirmButton>
          ))}
        </Space>
      </Space>
    </section>
  );
}

export function AdvertisingRecoveryControls({
  context,
  id,
  allowedActions,
  reload,
}: {
  readonly context: ConsoleRequest;
  readonly id: string;
  readonly allowedActions: readonly string[];
  readonly reload: () => void;
}): React.JSX.Element | null {
  const { message } = App.useApp();
  const [evidence, setEvidence] = useState(''),
    [bundle, setBundle] = useState('');
  const [busy, setBusy] = useState(false),
    [failure, setFailure] = useState<ConsoleFailure>();
  if (allowedActions.length === 0) return null;
  return (
    <section aria-label="独立恢复评审">
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        {failure !== undefined && <FailureAlert failure={failure} />}
        <Form layout="vertical" style={{ maxWidth: 640 }}>
          {allowedActions.some((action) => action.startsWith('ATTEST_')) && (
            <Form.Item label="恢复证据引用" required style={{ marginBottom: 8 }}>
              <Input
                aria-label="恢复证据引用"
                value={evidence}
                onChange={(event) => {
                  setEvidence(event.target.value);
                }}
              />
            </Form.Item>
          )}
          {allowedActions.includes('REENABLE') && (
            <Form.Item label="新批准的替代策略包编号" required style={{ marginBottom: 8 }}>
              <Input
                aria-label="新批准的替代策略包编号"
                value={bundle}
                onChange={(event) => {
                  setBundle(event.target.value);
                }}
              />
            </Form.Item>
          )}
        </Form>
        <Space wrap>
          {allowedActions.map((action) => {
            const reenable = action === 'REENABLE';
            const missing = reenable ? !bundle.trim() : !evidence.trim();
            return (
              <ConfirmButton
                key={action}
                type={reenable ? 'primary' : 'default'}
                title={
                  reenable ? '确认恢复广告执行？' : `确认提交「${recoveryActionLabel(action)}」？`
                }
                description={
                  reenable ? '恢复后，新批准的策略包将开始约束后续广告操作。' : undefined
                }
                disabled={busy || missing}
                disabledReason={
                  busy ? '正在提交…' : reenable ? '请先填写替代策略包编号' : '请先填写恢复证据引用'
                }
                onConfirm={async () => {
                  setBusy(true);
                  setFailure(undefined);
                  const result = await advertisingControl(
                    context,
                    `containments/${encodeURIComponent(id)}/${reenable ? 'reenablement' : 'attestations'}`,
                    reenable
                      ? { newBundleId: bundle }
                      : { condition: action.slice('ATTEST_'.length), evidenceReference: evidence },
                  );
                  setBusy(false);
                  if (result.ok) {
                    void message.success(reenable ? '已提交恢复' : '已记录证明');
                    reload();
                  } else setFailure(result.failure);
                }}
              >
                {recoveryActionLabel(action)}
              </ConfirmButton>
            );
          })}
        </Space>
      </Space>
    </section>
  );
}
