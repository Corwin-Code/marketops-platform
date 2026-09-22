import { Button, Drawer, Flex, Form, Steps } from 'antd';
import type { FormInstance } from 'antd';
import { useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { ConsoleFailure } from '../api/console';
import { actions } from '../i18n/zh/common';
import type { SubmitOutcome } from './ActionModal';
import { FailureAlert } from './FailureAlert';
import { TriggerButton } from './TriggerButton';
import type { TriggerProps } from './TriggerButton';

/** A form field name: a key, or a path of keys into nested values. */
export type FieldName = string | number | readonly (string | number)[];

/** One step of a longer form: its title, the fields it validates, its content. */
export interface FormDrawerStep {
  readonly key: string;
  readonly title: ReactNode;
  /** Fields checked before moving on from this step. */
  readonly fields: readonly FieldName[];
  readonly content: ReactNode;
}

/** A longer form beside the page. */
export interface FormDrawerProps<V extends object> {
  /** A button that opens the drawer. Omit it and pass `open` to control it. */
  readonly trigger?: TriggerProps;
  readonly open?: boolean;
  readonly onClose?: () => void;
  readonly title: ReactNode;
  /** Drawer width: default (378px), large (736px) or pixels. */
  readonly size?: 'default' | 'large' | number;
  readonly extra?: ReactNode;
  /** Read-only material above the form, e.g. the text being reviewed. */
  readonly intro?: ReactNode;
  /** Split the form into steps; each is validated before the next. */
  readonly steps?: readonly FormDrawerStep[];
  /** The form's items when there are no steps. */
  readonly children?: ReactNode | ((form: FormInstance<V>) => ReactNode);
  readonly initialValues?: Partial<V>;
  readonly submitText?: ReactNode;
  readonly danger?: boolean;
  /** Return a failure to keep the drawer open and show it; `undefined` closes it. */
  readonly onSubmit: (values: V) => Promise<SubmitOutcome>;
}

/**
 * A form that opens beside the page instead of being laid out on it.
 *
 * The list or record it belongs to stays visible behind the drawer. Long forms
 * can be split into steps; fields of hidden steps stay mounted, so nothing is
 * lost moving back and forth. Submission happens only from the footer button,
 * and a failure is shown in the drawer, which stays open.
 */
export function FormDrawer<V extends object>({
  trigger,
  open: controlledOpen,
  onClose,
  title,
  size = 'large',
  extra,
  intro,
  steps,
  children,
  initialValues,
  submitText,
  danger = false,
  onSubmit,
}: FormDrawerProps<V>): React.JSX.Element {
  const [ownOpen, setOwnOpen] = useState(false);
  const open = controlledOpen ?? ownOpen;
  const [form] = Form.useForm<V>();
  const [step, setStep] = useState(0);
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  // A new form on every opening, so initial values apply afresh each time.
  const [generation, setGeneration] = useState(0);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  const close = (): void => {
    setOwnOpen(false);
    setStep(0);
    setFailure(undefined);
    onClose?.();
  };

  const last = steps === undefined || step === steps.length - 1;

  const next = async (): Promise<void> => {
    const current = steps?.[step];
    if (current === undefined) {
      return;
    }
    try {
      await form.validateFields(
        current.fields.map((name) => (typeof name === 'object' ? [...name] : name)),
      );
      setStep(step + 1);
    } catch {
      // The failing fields show their own messages.
    }
  };

  const submit = async (): Promise<void> => {
    if (submitting.current) {
      return;
    }
    let values: V;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    submitting.current = true;
    setBusy(true);
    setFailure(undefined);
    try {
      const outcome = await onSubmit(values);
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

  const body =
    steps === undefined
      ? typeof children === 'function'
        ? children(form)
        : children
      : steps.map((item, index) => (
          <div key={item.key} style={index === step ? undefined : { display: 'none' }}>
            {item.content}
          </div>
        ));

  return (
    <>
      {trigger !== undefined && (
        <TriggerButton
          trigger={trigger}
          onClick={() => {
            setGeneration((current) => current + 1);
            setOwnOpen(true);
          }}
        />
      )}
      <Drawer
        open={open}
        title={title}
        size={size}
        onClose={() => {
          if (!busy) close();
        }}
        closable={!busy}
        keyboard={!busy}
        destroyOnHidden
        mask={{ closable: false }}
        {...(extra === undefined ? {} : { extra })}
        footer={
          <Flex justify="flex-end" gap={8}>
            <Button onClick={close} disabled={busy}>
              {actions.cancel}
            </Button>
            {steps !== undefined && step > 0 && (
              <Button
                onClick={() => {
                  setStep(step - 1);
                }}
              >
                {actions.previous}
              </Button>
            )}
            {last ? (
              <Button
                type="primary"
                danger={danger}
                loading={busy}
                onClick={() => {
                  void submit();
                }}
              >
                {submitText ?? actions.submit}
              </Button>
            ) : (
              <Button
                type="primary"
                onClick={() => {
                  void next();
                }}
              >
                {actions.next}
              </Button>
            )}
          </Flex>
        }
      >
        <Flex vertical gap={16}>
          {steps !== undefined && (
            <Steps
              size="small"
              current={step}
              items={steps.map((item) => ({ key: item.key, title: item.title }))}
            />
          )}
          {intro}
          <Form
            key={generation}
            form={form}
            layout="vertical"
            {...(initialValues === undefined ? {} : { initialValues })}
          >
            {body}
          </Form>
          {failure !== undefined && <FailureAlert failure={failure} />}
        </Flex>
      </Drawer>
    </>
  );
}
