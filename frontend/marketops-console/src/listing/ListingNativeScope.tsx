import { Alert, Button, Col, DatePicker, Form, Input, InputNumber, Row, Select } from 'antd';
import type { Dayjs } from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { recordNativeScope } from '../api/listingConversion';
import { storeLocalToIso } from '../format';
import { t } from '../i18n/zh/listing';
import { SectionCard } from '../ui';
import { Hint, IdText, ListingProblem, codeOptions } from './ListingCommon';

interface NativeScopeValues {
  readonly scopeKind?: string;
  readonly nativeScopeKey?: string;
  readonly members?: string;
  readonly coverageState?: string;
  readonly expectedCount?: number | null;
  readonly continuation?: string;
  readonly source?: string;
  readonly basis?: string;
  readonly observedAt?: Dayjs | null;
  readonly expiresAt?: Dayjs | null;
}

/** Capture the source's actual enumeration; never populate it from the currently mapped subset. */
export function NativeScopeObservationForm({
  context,
  listingId,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
}): React.JSX.Element {
  const [form] = Form.useForm<NativeScopeValues>();
  const sequence = useRef(0);
  const [pending, setPending] = useState(false);
  const [receipt, setReceipt] = useState<string>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    sequence.current += 1;
    form.resetFields();
    setReceipt(undefined);
    setFailure(undefined);
    setPending(false);
    return () => {
      sequence.current += 1;
    };
  }, [context, listingId, form]);
  const text = (value: string | undefined): string => value ?? '';
  return (
    <SectionCard title={t('nativeScopeCapture')}>
      <Hint>{t('nativeScopeExplanation')}</Hint>
      <Form<NativeScopeValues>
        form={form}
        layout="vertical"
        aria-label={t('nativeScopeCapture')}
        disabled={pending}
        onFinish={(values) => {
          const current = ++sequence.current;
          setReceipt(undefined);
          setFailure(undefined);
          setPending(true);
          const continuation = text(values.continuation);
          void recordNativeScope(context, listingId, {
            scopeKind: text(values.scopeKind),
            nativeScopeKey: text(values.nativeScopeKey),
            nativeVariantKeys: text(values.members)
              .split(/\r?\n/)
              .filter((key) => key !== ''),
            coverageState: text(values.coverageState),
            expectedMemberCount: values.expectedCount ?? null,
            continuationReference: continuation === '' ? null : continuation,
            sourceReference: text(values.source),
            scopeBasisReference: text(values.basis),
            observedAt: values.observedAt ? storeLocalToIso(values.observedAt) : '',
            verificationExpiresAt: values.expiresAt ? storeLocalToIso(values.expiresAt) : '',
          }).then((outcome) => {
            if (current !== sequence.current) return;
            setPending(false);
            if (outcome.ok) setReceipt(outcome.value);
            else setFailure(outcome.failure);
          });
        }}
      >
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item
              name="scopeKind"
              label={t('nativeScopeKind')}
              rules={[{ required: true, message: '请选择平台作用范围' }]}
            >
              <Select
                options={codeOptions('nativeScopeKind', ['WHOLE_LISTING', 'NATIVE_VARIANT'])}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="nativeScopeKey"
              label={t('nativeScopeKey')}
              rules={[{ required: true, message: '请填写该范围的原生标识' }]}
            >
              <Input maxLength={512} />
            </Form.Item>
          </Col>
          <Col xs={24}>
            <Form.Item name="members" label={t('nativeMemberKeys')}>
              <Input.TextArea maxLength={2101248} autoSize={{ minRows: 3, maxRows: 10 }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="coverageState"
              label={t('nativeCoverage')}
              rules={[{ required: true, message: '请选择来源枚举状态' }]}
            >
              <Select options={codeOptions('nativeCoverage', ['COMPLETE', 'PARTIAL', 'UNKNOWN'])} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="expectedCount" label={t('nativeExpectedCount')}>
              <InputNumber min={0} max={4096} step={1} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="continuation" label={t('nativeContinuation')}>
              <Input maxLength={512} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="source"
              label={t('nativeScopeSource')}
              rules={[{ required: true, message: '请填写实际枚举来源引用' }]}
            >
              <Input maxLength={512} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="basis"
              label={t('nativeScopeBasis')}
              rules={[{ required: true, message: '请填写原生作用范围依据' }]}
            >
              <Input maxLength={512} />
            </Form.Item>
          </Col>
          <Col xs={24} md={6}>
            <Form.Item
              name="observedAt"
              label={t('nativeScopeObserved')}
              rules={[{ required: true, message: '请选择观察时间' }]}
            >
              <DatePicker showTime style={{ width: '100%' }} placeholder={t('pickStoreTime')} />
            </Form.Item>
          </Col>
          <Col xs={24} md={6}>
            <Form.Item
              name="expiresAt"
              label={t('nativeScopeExpires')}
              rules={[{ required: true, message: '请选择核验有效期' }]}
            >
              <DatePicker showTime style={{ width: '100%' }} placeholder={t('pickStoreTime')} />
            </Form.Item>
          </Col>
        </Row>
        <Button type="primary" htmlType="submit" loading={pending}>
          {t('nativeScopeCapture')}
        </Button>
      </Form>
      {failure !== undefined && (
        <div style={{ marginTop: 12 }}>
          <ListingProblem failure={failure} />
        </div>
      )}
      {receipt !== undefined && (
        <Alert
          style={{ marginTop: 12 }}
          type="success"
          showIcon
          role="status"
          title={t('nativeScopeSaved')}
          description={<IdText value={receipt} />}
        />
      )}
    </SectionCard>
  );
}
