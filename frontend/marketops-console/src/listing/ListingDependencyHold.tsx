import { App, AutoComplete, Form, Input, InputNumber, Popover, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleRequest } from '../api/console';
import {
  holdListingTaskForDependency,
  type ListingDependencyHoldTarget,
  type ListingTaskDependencyHold,
} from '../api/listingConversion';
import { LISTING_CODES } from '../i18n/zh/listingCodes';
import { t } from '../i18n/zh/listing';
import { responsibilityText as text } from '../i18n/zh/listingHealth';
import { ActionModal } from '../ui';
import { Code, Details, IdText, When } from './ListingCommon';

interface HoldValues {
  readonly dependencyTaskId?: string;
  readonly minutes?: number | null;
  readonly evidence?: string;
}

/** A task the hold may depend on, offered in the picker. */
export interface DependencyTaskOption {
  readonly value: string;
  readonly label: string;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The end reason in Chinese when the console knows it; otherwise the raw text. */
function endReasonText(reason: string): string {
  const labels: Readonly<Record<string, string>> = LISTING_CODES.dependencyHoldEndReason;
  return Object.hasOwn(labels, reason)
    ? (labels[reason] ?? reason)
    : `${t('unrecognizedReason')}：${reason}`;
}

/** The hold's state as a tag, with its dependency and evidence one hover away. */
function HoldState({ value }: { readonly value: ListingTaskDependencyHold }): React.JSX.Element {
  return (
    <Popover
      title={t('dependencyHoldTitle')}
      trigger={['hover', 'click']}
      content={
        <div style={{ maxWidth: 420 }}>
          <Details
            column={1}
            items={[
              {
                key: 'until',
                label: t('dependencyHoldUntil'),
                children: <When value={value.expiresAt} />,
              },
              {
                key: 'task',
                label: t('dependencyTask'),
                children: <IdText value={value.dependencyTaskId} />,
              },
              {
                key: 'evidence',
                label: t('evidence'),
                children: <Typography.Text copyable>{value.evidenceReference}</Typography.Text>,
              },
              ...(value.endReason === undefined
                ? []
                : [
                    {
                      key: 'end',
                      label: t('dependencyHoldEnd'),
                      children: endReasonText(value.endReason),
                    },
                  ]),
            ]}
          />
        </div>
      }
    >
      <span aria-label={text.stateDetails} tabIndex={0} style={{ cursor: 'help' }}>
        <Code family="dependencyHoldState" code={value.state} />
      </span>
    </Popover>
  );
}

/**
 * A finite pause bound to another existing Task and its evidence: the current
 * hold's state, and a dialog to record a new one while none is active.
 */
export function ListingDependencyHold({
  context,
  target,
  current,
  taskOptions,
  onChanged,
}: {
  readonly context: ConsoleRequest;
  readonly target: ListingDependencyHoldTarget;
  readonly current?: ListingTaskDependencyHold | undefined;
  /** Tasks to pick the dependency from; a task number can always be pasted instead. */
  readonly taskOptions?: readonly DependencyTaskOption[];
  /** Called after a hold is recorded, e.g. to reload the clock. */
  readonly onChanged?: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [value, setValue] = useState(current);
  useEffect(() => {
    setValue(current);
  }, [current]);
  const active = value?.state === 'ACTIVE';
  return (
    <Space size={4} wrap aria-label={t('dependencyHoldTitle')} data-state={value?.state ?? 'none'}>
      {value !== undefined && <HoldState value={value} />}
      <ActionModal<HoldValues>
        trigger={{
          label: text.hold,
          size: 'small',
          disabled: active,
          ...(active ? { disabledReason: text.holdActive } : {}),
        }}
        title={text.holdTitle}
        consequence={text.holdConsequence}
        okText={t('dependencyHoldSubmit')}
        onSubmit={async (values) => {
          const result = await holdListingTaskForDependency(
            context,
            target,
            (values.dependencyTaskId ?? '').trim(),
            Number(values.minutes),
            (values.evidence ?? '').trim(),
          );
          if (!result.ok) return result.failure;
          setValue(result.value);
          void message.success(text.held);
          onChanged?.();
          return undefined;
        }}
      >
        <Form.Item
          name="dependencyTaskId"
          label={t('dependencyTask')}
          rules={[
            { required: true, whitespace: true, message: text.holdTaskRequired },
            { pattern: UUID, message: text.holdTaskInvalid },
          ]}
        >
          <AutoComplete
            allowClear
            placeholder={text.holdTaskPlaceholder}
            options={(taskOptions ?? []).map((option) => ({
              value: option.value,
              label: (
                <Space orientation="vertical" size={0}>
                  <Typography.Text>{option.label}</Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {option.value}
                  </Typography.Text>
                </Space>
              ),
            }))}
          />
        </Form.Item>
        <Form.Item
          name="minutes"
          label={t('dependencyHoldMinutes')}
          rules={[
            { required: true, message: text.minutesRequired },
            { type: 'integer', min: 1, message: text.minutesInvalid },
          ]}
        >
          <InputNumber min={1} step={1} precision={0} style={{ width: 200 }} />
        </Form.Item>
        <Form.Item
          name="evidence"
          label={t('dependencyHoldEvidence')}
          rules={[{ required: true, whitespace: true, message: text.holdEvidenceRequired }]}
        >
          <Input.TextArea maxLength={512} autoSize={{ minRows: 2, maxRows: 4 }} />
        </Form.Item>
      </ActionModal>
    </Space>
  );
}
