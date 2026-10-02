import { App, Form, Input, Space, Typography } from 'antd';
import { StopOutlined } from '@ant-design/icons';
import { advertisingControl } from '../api/console';
import type { ConsoleRequest } from '../api/console';
import { recoveryActionLabel } from '../i18n/zh/advertising';
import { dialog } from '../i18n/zh/common';
import { ActionModal } from '../ui/ActionModal';
import { idRules } from '../ui/PickOrType';

/** The scoped stop actions a case may offer, in the order they are shown. */
const STOP_COMMANDS = [
  {
    code: 'EMERGENCY_ENTITY_HOLD',
    scopeKind: 'ENTITY',
    containmentKind: 'EMERGENCY_ENTITY_HOLD',
    causeClass: 'BUSINESS_HARM',
    label: '紧急冻结该对象',
    ok: '确认紧急冻结',
    description: '冻结后该对象的广告执行将停止，直到完成恢复评审。',
  },
  {
    code: 'BUSINESS_STORE_STOP',
    scopeKind: 'PLATFORM_STORE_CAPABILITY',
    containmentKind: 'CAPABILITY_QUARANTINED',
    causeClass: 'BUSINESS_HARM',
    label: '因业务损失停止店铺广告',
    ok: '确认停止整个店铺的广告',
    description: '店铺广告能力将被隔离，恢复需要独立评审与批准。',
  },
  {
    code: 'TECHNICAL_STORE_STOP',
    scopeKind: 'PLATFORM_STORE_CAPABILITY',
    containmentKind: 'CAPABILITY_QUARANTINED',
    causeClass: 'PROVIDER_OR_READBACK_DEFECT',
    label: '因技术问题停止店铺广告',
    ok: '确认停止整个店铺的广告',
    description: '店铺广告能力将被隔离，恢复需要独立评审与批准。',
  },
] as const;

/** Codes of every scoped stop action, so a caller can tell whether any applies. */
export const STOP_ACTION_CODES: readonly string[] = STOP_COMMANDS.map((command) => command.code);

/** The longest reason or reference the console sends. */
const TEXT_LIMIT = 2000;
const EVIDENCE_LIMIT = 512;

interface StopValues {
  readonly reason?: string;
  readonly evidenceReference?: string;
  readonly reviewOwnerUserId?: string;
}

interface ReenableValues {
  readonly newBundleId?: string;
}

interface AttestValues {
  readonly evidenceReference?: string;
}

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
  const commands = STOP_COMMANDS.filter((item) => allowedActions.includes(item.code));
  if (commands.length === 0) return null;
  return (
    <section aria-label="范围止损">
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
          止损只停止执行，不会修改平台上的出价；未解决的执行证据会被保留。
        </Typography.Paragraph>
        <Space wrap>
          {commands.map((command) => (
            <ActionModal<StopValues>
              key={command.code}
              trigger={{ label: command.label, danger: true, icon: <StopOutlined /> }}
              title={command.label}
              consequence={command.description}
              okText={command.ok}
              danger
              onSubmit={async (values) => {
                const result = await advertisingControl(
                  context,
                  `containments/objects/${encodeURIComponent(objectId)}/stop`,
                  {
                    scopeKind: command.scopeKind,
                    containmentKind: command.containmentKind,
                    causeClass: command.causeClass,
                    reviewOwnerUserId: (values.reviewOwnerUserId ?? '').trim(),
                    reason: (values.reason ?? '').trim(),
                    evidenceReference: (values.evidenceReference ?? '').trim(),
                  },
                );
                if (!result.ok) return result.failure;
                void message.success('已记录范围止损。请查看当前管控，并保留未解决的执行证据。');
                return undefined;
              }}
            >
              <Form.Item
                name="reason"
                label="停止原因"
                rules={[{ required: true, whitespace: true, message: '请填写停止原因' }]}
              >
                <Input.TextArea
                  rows={3}
                  maxLength={TEXT_LIMIT}
                  showCount
                  placeholder={dialog.reasonPlaceholder}
                  autoFocus
                />
              </Form.Item>
              <Form.Item
                name="evidenceReference"
                label="证据引用"
                rules={[{ required: true, whitespace: true, message: '请填写证据引用' }]}
              >
                <Input maxLength={EVIDENCE_LIMIT} />
              </Form.Item>
              <Form.Item
                name="reviewOwnerUserId"
                label="负责评审的运营主管（用户编号）"
                extra="暂时没有可选的人员列表，请填写对方的用户编号。"
                rules={idRules('运营主管用户编号')}
              >
                <Input placeholder="输入编号（UUID）" />
              </Form.Item>
            </ActionModal>
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
  if (allowedActions.length === 0) return null;
  return (
    <section aria-label="独立恢复评审">
      <Space size={[8, 8]} wrap>
        {allowedActions.map((action) =>
          action === 'REENABLE' ? (
            <ActionModal<ReenableValues>
              key={action}
              trigger={{ label: recoveryActionLabel(action), type: 'primary' }}
              title="恢复广告执行"
              consequence="恢复后，新批准的策略包将开始约束后续广告操作。"
              okText="确认恢复"
              onSubmit={async (values) => {
                const result = await advertisingControl(
                  context,
                  `containments/${encodeURIComponent(id)}/reenablement`,
                  { newBundleId: (values.newBundleId ?? '').trim() },
                );
                if (!result.ok) return result.failure;
                void message.success('已提交恢复');
                reload();
                return undefined;
              }}
            >
              <Form.Item
                name="newBundleId"
                label="新批准的替代策略包编号"
                extra="暂时没有可选的策略包列表，请填写策略包编号。"
                rules={idRules('替代策略包编号')}
              >
                <Input placeholder="输入编号（UUID）" autoFocus />
              </Form.Item>
            </ActionModal>
          ) : (
            <ActionModal<AttestValues>
              key={action}
              trigger={{ label: recoveryActionLabel(action) }}
              title={`提交「${recoveryActionLabel(action)}」`}
              consequence="记录这项恢复条件已满足的证明。全部条件满足并经背书和批准后，才能恢复广告执行。"
              okText="提交证明"
              onSubmit={async (values) => {
                const result = await advertisingControl(
                  context,
                  `containments/${encodeURIComponent(id)}/attestations`,
                  {
                    condition: action.slice('ATTEST_'.length),
                    evidenceReference: (values.evidenceReference ?? '').trim(),
                  },
                );
                if (!result.ok) return result.failure;
                void message.success('已记录证明');
                reload();
                return undefined;
              }}
            >
              <Form.Item
                name="evidenceReference"
                label="恢复证据引用"
                rules={[{ required: true, whitespace: true, message: '请填写恢复证据引用' }]}
              >
                <Input maxLength={EVIDENCE_LIMIT} autoFocus />
              </Form.Item>
            </ActionModal>
          ),
        )}
      </Space>
    </section>
  );
}
