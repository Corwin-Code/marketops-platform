import { Button, Form, Input, InputNumber, Space } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  deferListingTask,
  type ListingDeferralTarget,
  type ListingTaskDeferral,
} from '../api/listingConversion';
import { t } from '../i18n/zh/listing';
import { Code, Details, Hint, IdText, ListingProblem, SubTitle, When } from './ListingCommon';

interface DeferralValues {
  readonly minutes?: number | null;
  readonly reason?: string;
}

export function ListingDeferral({
  context,
  target,
  current,
}: {
  readonly context: ConsoleRequest;
  readonly target: ListingDeferralTarget;
  readonly current: ListingTaskDeferral | undefined;
}): React.JSX.Element {
  const epoch = useRef(0);
  const [form] = Form.useForm<DeferralValues>();
  const key =
    target.kind === 'ACTION'
      ? `action:${target.actionId}`
      : `diagnostic:${target.listingId}:${target.taskId}`;
  const [value, setValue] = useState(current);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    epoch.current += 1;
    setValue(current);
    form.resetFields();
    setPending(false);
    setFailure(undefined);
    return () => {
      epoch.current += 1;
    };
  }, [context, key, current, form]);
  const waiting =
    value !== undefined &&
    (value.state === 'ACTIVE' ||
      value.state === 'REVIEW_DUE' ||
      value.reviewHealthId === undefined);
  return (
    <section aria-label={t('deferralTitle')}>
      <SubTitle>{t('deferralTitle')}</SubTitle>
      <Hint>{t('deferralHelp')}</Hint>
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        {failure !== undefined && <ListingProblem failure={failure} />}
        {value !== undefined && (
          <Details
            items={[
              {
                key: 'state',
                label: t('deferralState'),
                children: <Code family="deferralState" code={value.state} />,
              },
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
        )}
        <Form<DeferralValues>
          form={form}
          layout="vertical"
          aria-label={t('deferralSubmit')}
          disabled={pending || waiting}
          onFinish={(values) => {
            const ticket = ++epoch.current;
            setPending(true);
            setFailure(undefined);
            void deferListingTask(
              context,
              target,
              Number(values.minutes),
              values.reason ?? '',
            ).then((result) => {
              if (ticket !== epoch.current) return;
              setPending(false);
              if (result.ok) setValue(result.value);
              else setFailure(result.failure);
            });
          }}
        >
          <Space wrap align="start">
            <Form.Item
              name="minutes"
              label={t('deferralMinutes')}
              rules={[
                { required: true, message: '请填写暂缓分钟数' },
                { type: 'integer', min: 1, message: '分钟数须为不小于 1 的整数' },
              ]}
            >
              <InputNumber min={1} step={1} precision={0} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item
              name="reason"
              label={t('deferralReason')}
              rules={[{ required: true, whitespace: true, message: '请填写暂缓理由' }]}
            >
              <Input maxLength={512} style={{ width: 360 }} />
            </Form.Item>
          </Space>
          <Button type="primary" htmlType="submit" loading={pending}>
            {t('deferralSubmit')}
          </Button>
        </Form>
      </Space>
    </section>
  );
}
