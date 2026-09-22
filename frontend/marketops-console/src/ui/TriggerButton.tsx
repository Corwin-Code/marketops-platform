import { Button, Flex, Tooltip, Typography } from 'antd';
import type { ButtonProps } from 'antd';
import type { ReactNode } from 'react';

/** The button that opens a dialog, drawer or popover. */
export interface TriggerProps {
  readonly label: ReactNode;
  readonly type?: ButtonProps['type'];
  readonly danger?: boolean;
  readonly icon?: ReactNode;
  readonly size?: ButtonProps['size'];
  readonly disabled?: boolean;
  /** Why the button is disabled. */
  readonly disabledReason?: ReactNode;
  /**
   * Where the reason is shown: on hover, or as a line under the button that
   * stays visible (keyboard and touch users never see a tooltip).
   */
  readonly reasonPlacement?: 'tooltip' | 'inline';
}

/**
 * A button that says why it cannot be used.
 *
 * A control that silently refuses to work reads as broken, so a disabled
 * trigger always carries its reason: on hover by default, or as a visible line
 * beneath it where the reason is part of the task.
 */
export function TriggerButton({
  trigger,
  onClick,
  loading = false,
}: {
  readonly trigger: TriggerProps;
  readonly onClick: () => void;
  readonly loading?: boolean;
}): React.JSX.Element {
  const {
    label,
    type = 'default',
    danger = false,
    icon,
    size,
    disabled = false,
    disabledReason,
    reasonPlacement = 'tooltip',
  } = trigger;
  const button = (
    <Button
      type={type}
      danger={danger}
      disabled={disabled}
      loading={loading}
      onClick={onClick}
      {...(icon === undefined ? {} : { icon })}
      {...(size === undefined ? {} : { size })}
      data-state={disabled ? 'disabled' : loading ? 'pending' : 'ready'}
    >
      {label}
    </Button>
  );
  if (!disabled || disabledReason === undefined) {
    return button;
  }
  if (reasonPlacement === 'inline') {
    return (
      <Flex vertical gap={2} align="flex-start" style={{ display: 'inline-flex' }}>
        {button}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {disabledReason}
        </Typography.Text>
      </Flex>
    );
  }
  return (
    <Tooltip title={disabledReason}>
      <span style={{ display: 'inline-block', cursor: 'not-allowed' }}>{button}</span>
    </Tooltip>
  );
}
