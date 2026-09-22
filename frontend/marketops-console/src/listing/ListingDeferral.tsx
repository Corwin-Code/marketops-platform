import { App, Form, Input, InputNumber, Popover, Space } from 'antd';
import { useEffect, useState } from 'react';
import type { ConsoleRequest } from '../api/console';
import {
  deferListingTask,
  type ListingDeferralTarget,
  type ListingTaskDeferral,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { responsibilityText as text } from '../i18n/zh/listingHealth';
import { ActionModal } from '../ui';
import { Code, Details, IdText, When } from './ListingCommon';

interface DeferralValues {
  readonly minutes?: number | null;
  readonly reason?: string;
}

/** The deferral's state as a tag, with its reason and times one hover away. */
function DeferralState({ value }: { readonly value: ListingTaskDeferral }): React.JSX.Element {
  return (
    <Popover
      title={t('deferralTitle')}
      trigger={['hover', 'click']}
      content={
        <div style={{ maxWidth: 420 }}>
          <Details
            column={1}
            items={[
              { key: 'reason', label: t('deferralReason'), children: value.reason },
              {
                key: 'until',
                label: t('deferralUntil'),
                children: <When value={value.expiresAt} />,
              },
              {
                key: 'review',
                label: t('deferralReview'),
                children: <IdText value={value.reviewHealthId} />,
              },
            ]}
          />
        </div>
      }
    >
      <span aria-label={text.stateDetails} tabIndex={0} style={{ cursor: 'help' }}>
        <Code family="deferralState" code={value.state} />
      </span>
    </Popover>
  );
}

/**
 * A finite deferral of one responsibility: its state, and a dialog to record
 * a new one with its minutes and reason. Deferral never resets the original
 * start, and a new one is refused while one is running or awaiting review.
 */
export function ListingDeferral({
  context,
  target,
  current,
  onChanged,
}: {
  readonly context: ConsoleRequest;
  readonly target: ListingDeferralTarget;
  readonly current: ListingTaskDeferral | undefined;
  /** Called after a deferral is recorded, e.g. to reload the clock. */
  readonly onChanged?: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const [value, setValue] = useState(current);
  useEffect(() => {
    setValue(current);
  }, [current]);
  const waiting =
    value !== undefined &&
    (value.state === 'ACTIVE' ||
      value.state === 'REVIEW_DUE' ||
      value.reviewHealthId === undefined);
  const waitingReason = value?.state === 'ACTIVE' ? text.deferActive : text.deferReviewDue;
  return (
    <Space size={4} wrap aria-label={t('deferralTitle')} data-state={value?.state ?? 'none'}>
      {value !== undefined && <DeferralState value={value} />}
      <ActionModal<DeferralValues>
        trigger={{
          label: text.defer,
          size: 'small',
          disabled: waiting,
          ...(waiting ? { disabledReason: waitingReason } : {}),
        }}
        title={text.deferTitle}
        consequence={text.deferConsequence}
        okText={t('deferralSubmit')}
        onSubmit={async (values) => {
          const result = await deferListingTask(
            context,
            target,
            Number(values.minutes),
            (values.reason ?? '').trim(),
          );
          if (!result.ok) return result.failure;
          setValue(result.value);
          void message.success(text.deferred);
          onChanged?.();
          return undefined;
        }}
      >
        <Form.Item
          name="minutes"
          label={t('deferralMinutes')}
          rules={[
            { required: true, message: text.minutesRequired },
            { type: 'integer', min: 1, message: text.minutesInvalid },
          ]}
        >
          <InputNumber min={1} step={1} precision={0} style={{ width: 200 }} />
        </Form.Item>
        <Form.Item
          name="reason"
          label={t('deferralReason')}
          rules={[{ required: true, whitespace: true, message: text.deferReasonRequired }]}
        >
          <Input.TextArea maxLength={512} showCount autoSize={{ minRows: 2, maxRows: 4 }} />
        </Form.Item>
      </ActionModal>
    </Space>
  );
}
