import { RobotOutlined } from '@ant-design/icons';
import { Alert, Button, Col, Form, Input, Row, Select, Space, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { AiExplanation, ConsoleFailure, ConsoleRequest } from '../api/console';
import { fetchListingAssistance, requestListingAssistance } from '../api/listingConversion';
import type { ListingAssistancePurpose } from '../api/listingConversion';
import { AiClaimGroups } from '../diagnosis/AiExplanationPanel';
import { t } from '../i18n/zh/listing';
import { LoadingState, SectionCard, TechnicalDetails } from '../ui';
import { Code, Hint, IdText, ListingProblem, Stack, codeOptions } from './ListingCommon';

const PURPOSES = [
  'HYPOTHESIS_COMPARISON',
  'RUSSIAN_DESCRIPTION',
  'SIMPLE_PROMOTION',
  'REVIEW_SUMMARY',
] as const;

type AssistanceWindow = 'D7' | 'D14' | 'D30';

export function ListingAssistancePanel({
  context,
  listingId,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
}): React.JSX.Element {
  const [purpose, setPurpose] = useState<ListingAssistancePurpose>('HYPOTHESIS_COMPARISON');
  const [window, setWindow] = useState<AssistanceWindow>('D14');
  const [invocationId, setInvocationId] = useState('');
  const [output, setOutput] = useState<AiExplanation | undefined>();
  const [failure, setFailure] = useState<ConsoleFailure | undefined>();
  const [pending, setPending] = useState(false);
  const generation = useRef(0);
  useEffect(() => {
    generation.current += 1;
    setOutput(undefined);
    setFailure(undefined);
    setPending(false);
    setInvocationId('');
    return () => {
      generation.current += 1;
    };
  }, [context, listingId]);
  async function load(history: boolean): Promise<void> {
    const requestedGeneration = ++generation.current;
    setPending(true);
    setFailure(undefined);
    setOutput(undefined);
    const result = history
      ? await fetchListingAssistance(context, listingId, invocationId)
      : await requestListingAssistance(context, listingId, window, purpose);
    if (requestedGeneration !== generation.current) return;
    setPending(false);
    if (result.ok && result.value.subjectId === listingId) {
      setOutput(result.value);
      setInvocationId(result.value.invocationId);
    } else
      setFailure(result.ok ? { kind: 'malformed', detail: 'subject mismatch' } : result.failure);
  }
  return (
    <section aria-label={t('assistance')}>
      <SectionCard
        title={
          <Space>
            <RobotOutlined />
            {t('assistance')}
            <Tag color="purple">{t('aiGenerated')}</Tag>
          </Space>
        }
      >
        <Stack>
          <Hint>{t('assistanceBoundary')}</Hint>
          <Form layout="vertical" disabled={pending} component={false}>
            <Row gutter={16} align="bottom">
              <Col xs={24} md={9}>
                <Form.Item label={t('assistancePurpose')}>
                  <Select<ListingAssistancePurpose>
                    value={purpose}
                    onChange={setPurpose}
                    options={codeOptions('assistancePurpose', PURPOSES).map((option) => ({
                      ...option,
                      value: option.value as ListingAssistancePurpose,
                    }))}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={7}>
                <Form.Item label={t('assistanceWindow')}>
                  <Select<AssistanceWindow>
                    value={window}
                    onChange={setWindow}
                    options={codeOptions('assistanceWindow', ['D7', 'D14', 'D30']).map(
                      (option) => ({ ...option, value: option.value as AssistanceWindow }),
                    )}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item>
                  <Button
                    type="primary"
                    icon={<RobotOutlined />}
                    loading={pending}
                    onClick={() => {
                      void load(false);
                    }}
                  >
                    {t('assistanceRequest')}
                  </Button>
                </Form.Item>
              </Col>
            </Row>
          </Form>
          <Form
            layout="inline"
            disabled={pending}
            onFinish={() => {
              void load(true);
            }}
          >
            <Form.Item label={t('assistanceInvocation')} required>
              <Input
                required
                style={{ width: 340 }}
                value={invocationId}
                onChange={(event) => {
                  setInvocationId(event.target.value);
                }}
              />
            </Form.Item>
            <Button htmlType="submit">{t('assistanceRead')}</Button>
          </Form>
          {pending && (
            <div role="status">
              <LoadingState />
            </div>
          )}
          {failure !== undefined && (
            <>
              <ListingProblem failure={failure} />
              <Hint>{t('assistanceNoRetry')}</Hint>
            </>
          )}
          {output !== undefined && (
            <div data-state={output.state}>
              <Stack>
                <Space wrap>
                  <Tag color="purple">{t('aiGenerated')}</Tag>
                  <Code family="aiInvocationState" code={output.state} />
                </Space>
                {output.state === 'PARTIAL_OUTPUT_REJECTED' ? (
                  <Alert role="alert" type="warning" showIcon title={t('assistancePartial')} />
                ) : output.degraded ? (
                  <Alert role="alert" type="error" showIcon title={t('assistanceUnavailable')} />
                ) : output.state !== 'SUCCEEDED' ? (
                  <Alert role="status" type="info" showIcon title={t('assistancePending')} />
                ) : (
                  <Alert type="success" showIcon title={t('assistanceValidated')} />
                )}
                <AiClaimGroups
                  output={output}
                  labels={{
                    FACT: t('assistanceFact'),
                    INFERENCE: t('assistanceInference'),
                    RECOMMENDATION: t('assistanceRecommendation'),
                    UNKNOWN: t('assistanceUnknown'),
                    confidence: t('assistanceConfidence'),
                    evidence: t('evidence'),
                    noEvidence: t('assistanceNoEvidence'),
                    rejected: t('assistanceRejected'),
                  }}
                />
                <TechnicalDetails>
                  <Space orientation="vertical" size={2}>
                    <IdText label={t('assistanceInvocation')} value={output.invocationId} />
                    <IdText label={t('failureCode')} value={output.failureCode} />
                  </Space>
                </TechnicalDetails>
              </Stack>
            </div>
          )}
        </Stack>
      </SectionCard>
    </section>
  );
}
