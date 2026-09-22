import { Button, Form, Input, InputNumber, Space, Typography } from 'antd';
import { useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  holdListingTaskForDependency,
  type ListingDependencyHoldTarget,
  type ListingTaskDependencyHold,
} from '../api/listingConversion';
import { LISTING_CODES } from '../i18n/zh/listingCodes';
import { t } from '../i18n/zh/listing';
import { TechnicalDetails } from '../ui';
import { Code, Details, Hint, IdText, ListingProblem, SubTitle, When } from './ListingCommon';

interface HoldValues {
  readonly dependencyTaskId?: string;
  readonly minutes?: number | null;
  readonly evidence?: string;
}

/** The end reason in Chinese when the console knows it; otherwise raw, folded away. */
function EndReason({ reason }: { readonly reason: string }): React.JSX.Element {
  const labels: Readonly<Record<string, string>> = LISTING_CODES.dependencyHoldEndReason;
  if (Object.hasOwn(labels, reason)) {
    return <Typography.Text>{labels[reason]}</Typography.Text>;
  }
  return (
    <TechnicalDetails label={t('unrecognizedReason')}>
      <Typography.Text>{reason}</Typography.Text>
    </TechnicalDetails>
  );
}

/** A finite pause bound to another existing Task and its evidence. */
export function ListingDependencyHold({
  context,
  target,
  current,
}: {
  readonly context: ConsoleRequest;
  readonly target: ListingDependencyHoldTarget;
  readonly current?: ListingTaskDependencyHold | undefined;
}): React.JSX.Element {
  const [form] = Form.useForm<HoldValues>();
  const [answer, setAnswer] = useState<ListingTaskDependencyHold | undefined>(current);
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [busy, setBusy] = useState(false);
  const shown = answer ?? current;
  return (
    <section aria-label={t('dependencyHoldTitle')}>
      <SubTitle>{t('dependencyHoldTitle')}</SubTitle>
      <Hint>{t('dependencyHoldHelp')}</Hint>
      <Space orientation="vertical" size="small" style={{ width: '100%' }}>
        {failure !== undefined && <ListingProblem failure={failure} />}
        {shown !== undefined && (
          <Details
            items={[
              {
                key: 'state',
                label: t('dependencyHoldState'),
                children: <Code family="dependencyHoldState" code={shown.state} />,
              },
              {
                key: 'until',
                label: t('dependencyHoldUntil'),
                children: <When value={shown.expiresAt} />,
              },
              {
                key: 'task',
                label: t('dependencyTask'),
                children: <IdText value={shown.dependencyTaskId} />,
              },
              {
                key: 'evidence',
                label: t('evidence'),
                children: <Typography.Text copyable>{shown.evidenceReference}</Typography.Text>,
              },
              ...(shown.endReason === undefined
                ? []
                : [
                    {
                      key: 'end',
                      label: t('dependencyHoldEnd'),
                      children: <EndReason reason={shown.endReason} />,
                    },
                  ]),
            ]}
          />
        )}
        {shown?.state !== 'ACTIVE' && (
          <Form<HoldValues>
            form={form}
            layout="vertical"
            disabled={busy}
            onFinish={(values) => {
              setBusy(true);
              setFailure(undefined);
              void holdListingTaskForDependency(
                context,
                target,
                values.dependencyTaskId ?? '',
                Number(values.minutes),
                values.evidence ?? '',
              )
                .then((result) => {
                  if (result.ok) setAnswer(result.value);
                  else setFailure(result.failure);
                })
                .finally(() => {
                  setBusy(false);
                });
            }}
          >
            <Space wrap align="start">
              <Form.Item
                name="dependencyTaskId"
                label={t('dependencyTask')}
                rules={[{ required: true, message: '请填写依赖的原任务编号' }]}
              >
                <Input style={{ width: 320 }} />
              </Form.Item>
              <Form.Item
                name="minutes"
                label={t('dependencyHoldMinutes')}
                rules={[
                  { required: true, message: '请填写暂停分钟数' },
                  { type: 'integer', min: 1, message: '分钟数须为不小于 1 的整数' },
                ]}
              >
                <InputNumber min={1} step={1} precision={0} style={{ width: 160 }} />
              </Form.Item>
            </Space>
            <Form.Item
              name="evidence"
              label={t('dependencyHoldEvidence')}
              rules={[{ required: true, message: '请填写依赖证据引用' }]}
            >
              <Input.TextArea maxLength={512} autoSize={{ minRows: 2, maxRows: 4 }} />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              {t('dependencyHoldSubmit')}
            </Button>
          </Form>
        )}
      </Space>
    </section>
  );
}
