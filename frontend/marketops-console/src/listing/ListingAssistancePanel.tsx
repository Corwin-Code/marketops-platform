import { RobotOutlined } from '@ant-design/icons';
import { Alert, Button, Flex, Select, Space, Tag, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { AiExplanation, ConsoleFailure, ConsoleOutcome, ConsoleRequest } from '../api/console';
import {
  endpointUnavailable,
  fetchListingAssistance,
  fetchListingAssistanceHistory,
  requestListingAssistance,
} from '../api/listingConversion';
import type { ListingAssistancePurpose, ListingAssistanceRecord } from '../api/listingConversion';
import { AiClaimGroups } from '../diagnosis/AiExplanationPanel';
import { formatStoreTime } from '../format';
import { codeLabel } from '../i18n/labels';
import { t } from '../i18n/zh/listing';
import { assistanceText as text } from '../i18n/zh/listingHealth';
import { AI_FAILURE_LABELS } from '../i18n/zh/pricing';
import { InlineInputPopover, LoadingState, SectionCard, TechnicalDetails } from '../ui';
import { Code, Hint, IdText, ListingProblem, Stack, codeOptions, codeText } from './ListingCommon';

const PURPOSES = [
  'HYPOTHESIS_COMPARISON',
  'RUSSIAN_DESCRIPTION',
  'SIMPLE_PROMOTION',
  'REVIEW_SUMMARY',
] as const;

type AssistanceWindow = 'D7' | 'D14' | 'D30';

/** Earlier requests of this listing, or why they cannot be listed. */
type History =
  | { readonly kind: 'loading' }
  | { readonly kind: 'loaded'; readonly records: readonly ListingAssistanceRecord[] }
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'failed'; readonly failure: ConsoleFailure };

/**
 * On-demand AI material for one listing, always marked as reference only.
 *
 * The boundary stays on screen, the request button names what it produces, and
 * nothing the model says is copied into any field: an operator reads it and
 * prepares actions by hand.
 */
export function ListingAssistancePanel({
  context,
  listingId,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
}): React.JSX.Element {
  const [purpose, setPurpose] = useState<ListingAssistancePurpose>('HYPOTHESIS_COMPARISON');
  const [window, setWindow] = useState<AssistanceWindow>('D14');
  const [selected, setSelected] = useState<string | undefined>();
  const [output, setOutput] = useState<AiExplanation | undefined>();
  const [failure, setFailure] = useState<ConsoleFailure | undefined>();
  const [pending, setPending] = useState(false);
  const [history, setHistory] = useState<History>({ kind: 'loading' });
  const generation = useRef(0);

  const loadHistory = useCallback(async (): Promise<void> => {
    const result = await fetchListingAssistanceHistory(context, listingId);
    if (result.ok) setHistory({ kind: 'loaded', records: result.value });
    else if (endpointUnavailable(result.failure)) setHistory({ kind: 'unavailable' });
    else setHistory({ kind: 'failed', failure: result.failure });
  }, [context, listingId]);

  useEffect(() => {
    generation.current += 1;
    setOutput(undefined);
    setFailure(undefined);
    setPending(false);
    setSelected(undefined);
    setHistory({ kind: 'loading' });
    void loadHistory();
    return () => {
      generation.current += 1;
    };
  }, [loadHistory]);

  /** Show one answer, or why it could not be read. */
  async function show(load: () => Promise<ConsoleOutcome<AiExplanation>>): Promise<void> {
    const requested = ++generation.current;
    setPending(true);
    setFailure(undefined);
    setOutput(undefined);
    const result = await load();
    if (requested !== generation.current) return;
    setPending(false);
    if (result.ok && result.value.subjectId === listingId) {
      setOutput(result.value);
      setSelected(result.value.invocationId);
    } else {
      setFailure(result.ok ? { kind: 'malformed', detail: 'subject mismatch' } : result.failure);
    }
  }

  const request = (): void => {
    void show(() => requestListingAssistance(context, listingId, window, purpose)).then(() => {
      void loadHistory();
    });
  };
  const read = (invocationId: string): Promise<void> =>
    show(() => fetchListingAssistance(context, listingId, invocationId));

  const records = history.kind === 'loaded' ? history.records : [];

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
          <Alert type="info" showIcon title={t('assistanceBoundary')} />
          <Flex gap={8} wrap align="center">
            <Typography.Text type="secondary">{t('assistancePurpose')}</Typography.Text>
            <Select<ListingAssistancePurpose>
              aria-label={t('assistancePurpose')}
              style={{ minWidth: 180 }}
              value={purpose}
              disabled={pending}
              onChange={setPurpose}
              options={codeOptions('assistancePurpose', PURPOSES).map((option) => ({
                ...option,
                value: option.value as ListingAssistancePurpose,
              }))}
            />
            <Typography.Text type="secondary">{t('assistanceWindow')}</Typography.Text>
            <Select<AssistanceWindow>
              aria-label={t('assistanceWindow')}
              style={{ minWidth: 120 }}
              value={window}
              disabled={pending}
              onChange={setWindow}
              options={codeOptions('assistanceWindow', ['D7', 'D14', 'D30']).map((option) => ({
                ...option,
                value: option.value as AssistanceWindow,
              }))}
            />
            <Button icon={<RobotOutlined />} loading={pending} onClick={request}>
              {text.request}
            </Button>
          </Flex>
          <Flex gap={8} wrap align="center">
            <Typography.Text type="secondary">{text.history}</Typography.Text>
            {history.kind !== 'unavailable' && (
              <Select<string>
                aria-label={text.history}
                style={{ minWidth: 320 }}
                placeholder={text.historyPlaceholder}
                loading={history.kind === 'loading'}
                disabled={pending}
                value={selected ?? null}
                notFoundContent={text.historyEmpty}
                onChange={(invocationId) => {
                  void read(invocationId);
                }}
                options={records.map((record) => ({
                  value: record.invocationId,
                  label: text.historyOption(
                    codeText('assistanceWindow', record.windowCode),
                    codeText('aiInvocationState', record.state),
                    formatStoreTime(record.startedAt),
                  ),
                }))}
              />
            )}
            {history.kind === 'unavailable' && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {text.historyUnavailable}
              </Typography.Text>
            )}
            <InlineInputPopover
              trigger={{ label: text.manual, type: 'link', disabled: pending }}
              title={text.manualTitle}
              placeholder={text.manualPlaceholder}
              maxLength={64}
              okText={t('assistanceRead')}
              onSubmit={async (invocationId) => {
                await read(invocationId);
                return undefined;
              }}
            />
          </Flex>
          {history.kind === 'failed' && <ListingProblem failure={history.failure} />}
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
                  <Alert
                    role="alert"
                    type="error"
                    showIcon
                    title={t('assistanceUnavailable')}
                    description={codeLabel(AI_FAILURE_LABELS, output.failureCode ?? output.state)}
                  />
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
