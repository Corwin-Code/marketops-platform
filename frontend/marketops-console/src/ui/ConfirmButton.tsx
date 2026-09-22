import { Button, Popconfirm, Tooltip } from 'antd';
import type { ButtonProps } from 'antd';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { actions } from '../i18n';

/** A button whose action must be confirmed first. */
export interface ConfirmButtonProps {
  readonly children: ReactNode;
  /** The question asked before acting. */
  readonly title: ReactNode;
  /** What will happen, in one or two sentences. */
  readonly description?: ReactNode;
  readonly onConfirm: () => void | Promise<void>;
  readonly danger?: boolean;
  readonly type?: ButtonProps['type'];
  readonly icon?: ReactNode;
  readonly size?: ButtonProps['size'];
  readonly disabled?: boolean;
  /** Why the button is disabled, shown on hover. */
  readonly disabledReason?: ReactNode;
  /** Loading controlled by the caller, in addition to the button's own. */
  readonly loading?: boolean;
}

/**
 * A button that asks before it acts.
 *
 * A disabled button says why on hover, because a control that silently refuses
 * to work is read as broken. While an asynchronous confirmation runs, the
 * button stays in its loading state so the action cannot be sent twice.
 */
export function ConfirmButton({
  children,
  title,
  description,
  onConfirm,
  danger = false,
  type = 'default',
  icon,
  size,
  disabled = false,
  disabledReason,
  loading = false,
}: ConfirmButtonProps): React.JSX.Element {
  const [pending, setPending] = useState(false);
  const busy = loading || pending;

  const run = async (): Promise<void> => {
    setPending(true);
    try {
      await onConfirm();
    } finally {
      setPending(false);
    }
  };

  const button = (
    <Button
      type={type}
      danger={danger}
      disabled={disabled}
      loading={busy}
      {...(icon === undefined ? {} : { icon })}
      {...(size === undefined ? {} : { size })}
      data-state={disabled ? 'disabled' : busy ? 'pending' : 'ready'}
    >
      {children}
    </Button>
  );

  if (disabled) {
    return disabledReason === undefined ? (
      button
    ) : (
      <Tooltip title={disabledReason}>
        <span style={{ display: 'inline-block', cursor: 'not-allowed' }}>{button}</span>
      </Tooltip>
    );
  }

  return (
    <Popconfirm
      title={title}
      {...(description === undefined ? {} : { description })}
      okText={actions.confirm}
      cancelText={actions.cancel}
      okButtonProps={{ danger }}
      disabled={busy}
      onConfirm={() => {
        void run();
      }}
    >
      {button}
    </Popconfirm>
  );
}
