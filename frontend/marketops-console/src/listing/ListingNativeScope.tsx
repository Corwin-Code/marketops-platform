import { Alert, Col, DatePicker, Form, Input, InputNumber, Row, Select } from 'antd';
import type { Dayjs } from 'dayjs';
import type { ConsoleRequest } from '../api/console';
import { recordNativeScope } from '../api/listingConversion';
import { storeLocalToIso } from '../format';
import { t } from '../i18n/zh/listing';
import { FormDrawer } from '../ui';
import { codeOptions } from './ListingCommon';

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

/**
 * Capture the source's actual enumeration of a listing's native scope, in a
 * drawer beside the listing. It is never populated from the currently mapped
 * subset: the mapped members do not prove the platform's full scope.
 */
export function NativeScopeDrawer({
  context,
  listingId,
  open,
  onClose,
  onRecorded,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly open: boolean;
  readonly onClose: () => void;
  /** Called with the new observation's number once it is saved. */
  readonly onRecorded: (observationId: string) => void;
}): React.JSX.Element {
  const text = (value: string | undefined): string => value ?? '';
  return (
    <FormDrawer<NativeScopeValues>
      open={open}
      onClose={onClose}
      title={t('nativeScopeCapture')}
      size="large"
      intro={<Alert type="info" showIcon title={t('nativeScopeExplanation')} />}
      submitText={t('nativeScopeCapture')}
      onSubmit={async (values) => {
        const continuation = text(values.continuation);
        const outcome = await recordNativeScope(context, listingId, {
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
        });
        if (!outcome.ok) return outcome.failure;
        onRecorded(outcome.value);
        return undefined;
      }}
    >
      <Row gutter={16}>
        <Col xs={24} md={12}>
          <Form.Item
            name="scopeKind"
            label={t('nativeScopeKind')}
            rules={[{ required: true, message: '请选择平台作用范围' }]}
          >
            <Select options={codeOptions('nativeScopeKind', ['WHOLE_LISTING', 'NATIVE_VARIANT'])} />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item
            name="nativeScopeKey"
            label={t('nativeScopeKey')}
            rules={[{ required: true, whitespace: true, message: '请填写该范围的原生标识' }]}
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
            rules={[{ required: true, whitespace: true, message: '请填写实际枚举来源引用' }]}
          >
            <Input maxLength={512} />
          </Form.Item>
        </Col>
        <Col xs={24}>
          <Form.Item
            name="basis"
            label={t('nativeScopeBasis')}
            rules={[{ required: true, whitespace: true, message: '请填写原生作用范围依据' }]}
          >
            <Input maxLength={512} />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item
            name="observedAt"
            label={t('nativeScopeObserved')}
            rules={[{ required: true, message: '请选择观察时间' }]}
          >
            <DatePicker showTime style={{ width: '100%' }} placeholder={t('pickStoreTime')} />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item
            name="expiresAt"
            label={t('nativeScopeExpires')}
            rules={[{ required: true, message: '请选择核验有效期' }]}
          >
            <DatePicker showTime style={{ width: '100%' }} placeholder={t('pickStoreTime')} />
          </Form.Item>
        </Col>
      </Row>
    </FormDrawer>
  );
}
