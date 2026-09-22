import { Button, Flex, Input, Popover } from 'antd';
import { useState } from 'react';
import type { ReactNode } from 'react';
import type { ConsoleFailure } from '../api/console';
import { actions } from '../i18n/zh/common';
import type { SubmitOutcome } from './ActionModal';
import { FailureAlert } from './FailureAlert';
import { TriggerButton } from './TriggerButton';
import type { TriggerProps } from './TriggerButton';

/** A one-field action. */
export interface InlineInputPopoverProps {
  readonly trigger: TriggerProps;
  readonly title: ReactNode;
  readonly placeholder?: string;
  readonly required?: boolean;
  readonly multiline?: boolean;
  readonly maxLength?: number;
  readonly okText?: ReactNode;
  readonly initialValue?: string;
  /** Return a failure to keep the popover open and show it. */
  readonly onSubmit: (value: string) => Promise<SubmitOutcome>;
}

/**
 * A single field asked for beside the button that needs it.
 *
 * For actions that take one value, such as an assignee or an evidence
 * reference, a dialog would be heavier than the task. The value belongs to this
 * action alone and starts from `initialValue` each time.
 */
export function InlineInputPopover({
  trigger,
  title,
  placeholder,
  required = true,
  multiline = false,
  maxLength = 500,
  okText,
  initialValue = '',
  onSubmit,
}: InlineInputPopoverProps): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState(initialValue);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const empty = value.trim() === '';

  const close = (): void => {
    setOpen(false);
    setFailure(undefined);
    setValue(initialValue);
  };

  const submit = async (): Promise<void> => {
    setBusy(true);
    setFailure(undefined);
    try {
      const outcome = await onSubmit(value.trim());
      if (outcome === undefined) {
        close();
      } else {
        setFailure(outcome);
      }
    } finally {
      setBusy(false);
    }
  };

  const field = multiline ? (
    <Input.TextArea
      rows={3}
      value={value}
      maxLength={maxLength}
      {...(placeholder === undefined ? {} : { placeholder })}
      onChange={(event) => {
        setValue(event.target.value);
      }}
    />
  ) : (
    <Input
      value={value}
      maxLength={maxLength}
      {...(placeholder === undefined ? {} : { placeholder })}
      onChange={(event) => {
        setValue(event.target.value);
      }}
      onPressEnter={() => {
        if (!(required && empty) && !busy) void submit();
      }}
    />
  );

  return (
    <Popover
      open={open}
      trigger="click"
      title={title}
      onOpenChange={(next) => {
        if (!next) close();
        else if (trigger.disabled !== true) setOpen(true);
      }}
      content={
        <Flex vertical gap={8} style={{ width: 320 }}>
          {field}
          {failure !== undefined && <FailureAlert failure={failure} />}
          <Flex justify="flex-end" gap={8}>
            <Button size="small" onClick={close}>
              {actions.cancel}
            </Button>
            <Button
              size="small"
              type="primary"
              loading={busy}
              disabled={required && empty}
              onClick={() => {
                void submit();
              }}
            >
              {okText ?? actions.confirm}
            </Button>
          </Flex>
        </Flex>
      }
    >
      <span>
        <TriggerButton trigger={trigger} onClick={() => undefined} />
      </span>
    </Popover>
  );
}
