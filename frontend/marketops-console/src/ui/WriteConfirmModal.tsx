import { Alert, Flex, Form, Input, Typography } from 'antd';
import type { ReactNode } from 'react';
import { dialog } from '../i18n/zh/common';
import { ActionModal } from './ActionModal';
import type { SubmitOutcome } from './ActionModal';
import type { TriggerProps } from './TriggerButton';

/** The deterministic check a write depends on. */
export interface WriteGuard {
  readonly passed: boolean;
  /** The verdict as the operator should read it: outcome, reasons, values. */
  readonly content: ReactNode;
}

/** Confirmation of something that will be written to a marketplace. */
export interface WriteConfirmModalProps {
  readonly trigger: TriggerProps;
  readonly title: ReactNode;
  /**
   * What will change: current → target, magnitude, currency or unit. A value
   * that is not known is shown as not known, never left out.
   */
  readonly impact: ReactNode;
  /** The rule check; a failed check disables confirmation. */
  readonly guard?: WriteGuard;
  /** What confirming does, e.g. that a command is created and sent. */
  readonly consequence: ReactNode;
  /** The confirm button, naming the act, e.g. 确认改价为 ₽1,299.00. */
  readonly confirmText: ReactNode;
  readonly reasonLabel?: ReactNode;
  /** Whether a reason must be written; true unless the caller says otherwise. */
  readonly reasonRequired?: boolean;
  /** A reason confirmation is refused besides the guard, e.g. still checking. */
  readonly blockedReason?: ReactNode;
  /** Called when the dialog opens, e.g. to run the check it depends on. */
  readonly onOpen?: () => void;
  readonly onConfirm: (reason: string) => Promise<SubmitOutcome>;
}

interface ReasonValues {
  readonly reason?: string;
}

/**
 * The only way the console authorises a platform write.
 *
 * The dialog restates what will change, shows the rule check it rests on and
 * asks for the reason that goes into the audit, all in one place, so the person
 * confirming sees exactly what they are authorising. A failed check disables
 * confirmation; the reason starts empty every time; Enter does not confirm.
 */
export function WriteConfirmModal({
  trigger,
  title,
  impact,
  guard,
  consequence,
  confirmText,
  reasonLabel = dialog.reason,
  reasonRequired = true,
  blockedReason,
  onOpen,
  onConfirm,
}: WriteConfirmModalProps): React.JSX.Element {
  const refused =
    blockedReason ?? (guard !== undefined && !guard.passed ? dialog.guardFailed : undefined);
  return (
    <ActionModal<ReasonValues>
      trigger={trigger}
      title={title}
      okText={confirmText}
      width={560}
      {...(refused === undefined ? {} : { blockedReason: refused })}
      {...(onOpen === undefined ? {} : { onOpen })}
      summary={
        <Flex vertical gap={12}>
          <div>
            <Typography.Text type="secondary">{dialog.impact}</Typography.Text>
            <div style={{ marginTop: 4 }}>{impact}</div>
          </div>
          {guard !== undefined && (
            <Alert
              type={guard.passed ? 'success' : 'error'}
              showIcon
              title={dialog.guard}
              description={guard.content}
            />
          )}
          <Alert type="warning" showIcon title={consequence} />
        </Flex>
      }
      onSubmit={(values) => onConfirm((values.reason ?? '').trim())}
    >
      <Form.Item
        name="reason"
        label={reasonLabel}
        rules={
          reasonRequired
            ? [{ required: true, whitespace: true, message: dialog.reasonRequired }]
            : []
        }
      >
        <Input.TextArea
          rows={3}
          maxLength={500}
          showCount
          placeholder={dialog.reasonPlaceholder}
          autoFocus
        />
      </Form.Item>
    </ActionModal>
  );
}
