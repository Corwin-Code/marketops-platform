import { Flex, Form, Modal, Typography } from 'antd';
import type { FormInstance } from 'antd';
import { useEffect, useRef, useState } from 'react';
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
  /** A button that opens the dialog. Omit it and pass `open` to open it from elsewhere. */
  readonly trigger?: TriggerProps;
  /** Controlled opening, e.g. from a menu item or a row action. */
  readonly open?: boolean;
  /** Called when a controlled dialog asks to close. */
  readonly onClose?: () => void;
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
  open: controlledOpen,
  onClose,
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
  const [ownOpen, setOwnOpen] = useState(false);
  const open = controlledOpen ?? ownOpen;
  // The dialog's content mounts during its opening animation, so the fields
  // are registered only once it has opened. Validating earlier would find no
  // required field and enable confirmation over an empty form.
  const [ready, setReady] = useState(false);
  const [form] = Form.useForm<V>();
  const values: unknown = Form.useWatch([], form);
  const [submittable, setSubmittable] = useState(false);
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  // A new form on every opening, so initial values apply afresh each time.
  const [generation, setGeneration] = useState(0);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    if (!open || !ready) {
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
  }, [open, ready, values, form]);

  // Each opening, by its own button or by its owner, starts a fresh form.
  const onOpenRef = useRef(onOpen);
  onOpenRef.current = onOpen;
  const wasOpen = useRef(false);
  useEffect(() => {
    if (open && !wasOpen.current) {
      setGeneration((current) => current + 1);
      onOpenRef.current?.();
    }
    wasOpen.current = open;
  }, [open]);

  const close = (): void => {
    setOwnOpen(false);
    onClose?.();
    setReady(false);
    setFailure(undefined);
    setSubmittable(false);
  };

  const submit = async (): Promise<void> => {
    // One submission at a time: a second click or Enter while the first is
    // in flight must not send the action again.
    if (submitting.current) {
      return;
    }
    let parsed: V;
    try {
      parsed = await form.validateFields();
    } catch {
      return;
    }
    submitting.current = true;
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
      submitting.current = false;
      setBusy(false);
    }
  };

  return (
    <>
      {trigger !== undefined && (
        <TriggerButton
          trigger={trigger}
          onClick={() => {
            setOwnOpen(true);
          }}
        />
      )}
      <Modal
        open={open}
        title={title}
        // While the action is being sent the dialog cannot be dismissed: closing
        // it would hide the outcome and let the action be started again.
        onCancel={() => {
          if (!busy) close();
        }}
        closable={!busy}
        keyboard={!busy}
        cancelButtonProps={{ disabled: busy }}
        onOk={() => {
          void submit();
        }}
        okText={okText ?? actions.confirm}
        cancelText={actions.cancel}
        okButtonProps={{ danger, disabled: !submittable || blockedReason !== undefined }}
        confirmLoading={busy}
        afterOpenChange={(visible) => {
          setReady(visible);
        }}
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
            key={generation}
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
