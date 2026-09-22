import { Flex, Form, Modal, Typography } from 'antd';
import type { FormInstance } from 'antd';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import type { ConsoleFailure } from '../api/console';
import { actions } from '../i18n/zh/common';
import { FailureAlert } from './FailureAlert';
import { TriggerButton } from './TriggerButton';
import type { TriggerProps } from './TriggerButton';

/** What a submit handler reports: a failure keeps the dialog open and shows it. */
export type SubmitOutcome = ConsoleFailure | undefined;

/** A short, focused task in a dialog. */
export interface ActionModalProps<V extends object> {
  readonly trigger: TriggerProps;
  readonly title: ReactNode;
  /** One sentence saying what happens once confirmed. */
  readonly consequence?: ReactNode;
  /** Read-only material shown above the fields, e.g. an impact summary. */
  readonly summary?: ReactNode;
  readonly okText?: ReactNode;
  readonly danger?: boolean;
  readonly width?: number;
  readonly initialValues?: Partial<V>;
  /** Why confirmation is refused regardless of the fields, e.g. a failed check. */
  readonly blockedReason?: ReactNode;
  /** The form's items. The form is rebuilt every time the dialog opens. */
  readonly children?: ReactNode | ((form: FormInstance<V>) => ReactNode);
  /** Return a failure to keep the dialog open and show it; `undefined` closes it. */
  readonly onSubmit: (values: V) => Promise<SubmitOutcome>;
  /** Called when the dialog opens, e.g. to load what it shows. */
  readonly onOpen?: () => void;
}

/**
 * A button that opens a small form in a dialog.
 *
 * Each action carries its own fields, so nothing is read from another form
 * behind the operator's back, and what is submitted is what the dialog shows.
 * Confirmation stays disabled until the required fields are filled; a failure
 * is shown inside the dialog, which stays open so nothing typed is lost. The
 * dialog is rebuilt on every opening, so a previous reason is never reused.
 * Pressing Enter in a field does not submit: only the confirm button does.
 */
export function ActionModal<V extends object>({
  trigger,
  title,
  consequence,
  summary,
  okText,
  danger = false,
  width,
  initialValues,
  blockedReason,
  children,
  onSubmit,
  onOpen,
}: ActionModalProps<V>): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<V>();
  const values: unknown = Form.useWatch([], form);
  const [submittable, setSubmittable] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    if (!open) {
      return;
    }
    let live = true;
    form.validateFields({ validateOnly: true }).then(
      () => {
        if (live) setSubmittable(true);
      },
      () => {
        if (live) setSubmittable(false);
      },
    );
    return () => {
      live = false;
    };
  }, [open, values, form]);

  const close = (): void => {
    setOpen(false);
    setFailure(undefined);
    setSubmittable(false);
  };

  const submit = async (): Promise<void> => {
    let parsed: V;
    try {
      parsed = await form.validateFields();
    } catch {
      return;
    }
    setBusy(true);
    setFailure(undefined);
    try {
      const outcome = await onSubmit(parsed);
      if (outcome === undefined) {
        close();
      } else {
        setFailure(outcome);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <TriggerButton
        trigger={trigger}
        onClick={() => {
          setOpen(true);
          onOpen?.();
        }}
      />
      <Modal
        open={open}
        title={title}
        onCancel={close}
        onOk={() => {
          void submit();
        }}
        okText={okText ?? actions.confirm}
        cancelText={actions.cancel}
        okButtonProps={{ danger, disabled: !submittable || blockedReason !== undefined }}
        confirmLoading={busy}
        destroyOnHidden
        mask={{ closable: false }}
        {...(width === undefined ? {} : { width })}
      >
        <Flex vertical gap={12}>
          {consequence !== undefined && (
            <Typography.Paragraph style={{ margin: 0 }}>{consequence}</Typography.Paragraph>
          )}
          {summary}
          <Form
            form={form}
            layout="vertical"
            preserve={false}
            {...(initialValues === undefined ? {} : { initialValues })}
          >
            {typeof children === 'function' ? children(form) : children}
          </Form>
          {blockedReason !== undefined && (
            <Typography.Text type="danger">{blockedReason}</Typography.Text>
          )}
          {failure !== undefined && <FailureAlert failure={failure} />}
        </Flex>
      </Modal>
    </>
  );
}
