import { useEffect, useRef, useState } from 'react';
import type { AiExplanation, ConsoleFailure, ConsoleRequest } from '../api/console';
import { fetchListingAssistance, requestListingAssistance } from '../api/listingConversion';
import type { ListingAssistancePurpose } from '../api/listingConversion';
import { AiClaimGroups } from '../diagnosis/AiExplanationPanel';
import { ListingProblem } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

const PURPOSES = [
  'HYPOTHESIS_COMPARISON',
  'RUSSIAN_DESCRIPTION',
  'SIMPLE_PROMOTION',
  'REVIEW_SUMMARY',
] as const;

export function ListingAssistancePanel({
  context,
  listingId,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
}): React.JSX.Element {
  const { language } = useLanguage();
  const [purpose, setPurpose] = useState<ListingAssistancePurpose>('HYPOTHESIS_COMPARISON');
  const [window, setWindow] = useState<'D7' | 'D14' | 'D30'>('D14');
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
  const purposeLabel = (value: ListingAssistancePurpose): string => {
    switch (value) {
      case 'HYPOTHESIS_COMPARISON':
        return t('assistanceHypotheses', language);
      case 'RUSSIAN_DESCRIPTION':
        return t('assistanceDescription', language);
      case 'SIMPLE_PROMOTION':
        return t('assistancePromotion', language);
      case 'REVIEW_SUMMARY':
        return t('assistanceReview', language);
    }
  };
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
    <section aria-label={t('assistance', language)}>
      <h4>{t('assistance', language)}</h4>
      <p>{t('assistanceBoundary', language)}</p>
      <fieldset disabled={pending}>
        <legend>{t('assistancePurpose', language)}</legend>
        <label>
          {t('assistancePurpose', language)}
          <select
            value={purpose}
            onChange={(event) => {
              setPurpose(event.target.value as ListingAssistancePurpose);
            }}
          >
            {PURPOSES.map((value) => (
              <option key={value} value={value}>
                {purposeLabel(value)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('assistanceWindow', language)}
          <select
            value={window}
            onChange={(event) => {
              setWindow(event.target.value as 'D7' | 'D14' | 'D30');
            }}
          >
            {['D7', 'D14', 'D30'].map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          onClick={() => {
            void load(false);
          }}
        >
          {t('assistanceRequest', language)}
        </button>
      </fieldset>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void load(true);
        }}
      >
        <fieldset disabled={pending}>
          <legend>{t('assistanceHistory', language)}</legend>
          <label>
            {t('assistanceInvocation', language)}
            <input
              required
              value={invocationId}
              onChange={(event) => {
                setInvocationId(event.target.value);
              }}
            />
          </label>
          <button type="submit">{t('assistanceRead', language)}</button>
        </fieldset>
      </form>
      {pending && <p role="status">{t('loading', language)}</p>}
      {failure !== undefined && (
        <>
          <ListingProblem failure={failure} />
          <p>{t('assistanceNoRetry', language)}</p>
        </>
      )}
      {output !== undefined && (
        <div data-state={output.state}>
          <p>
            {t('assistanceInvocation', language)}: {output.invocationId}
          </p>
          {output.state === 'PARTIAL_OUTPUT_REJECTED' ? (
            <p role="alert">{t('assistancePartial', language)}</p>
          ) : output.degraded ? (
            <p role="alert">
              {t('assistanceUnavailable', language)}: {output.failureCode ?? output.state}
            </p>
          ) : output.state !== 'SUCCEEDED' ? (
            <p role="status">{t('assistancePending', language)}</p>
          ) : (
            <p>{t('assistanceValidated', language)}</p>
          )}
          <AiClaimGroups
            output={output}
            labels={{
              FACT: t('assistanceFact', language),
              INFERENCE: t('assistanceInference', language),
              RECOMMENDATION: t('assistanceRecommendation', language),
              UNKNOWN: t('assistanceUnknown', language),
              confidence: t('assistanceConfidence', language),
              evidence: t('evidence', language),
              noEvidence: t('assistanceNoEvidence', language),
              rejected: t('assistanceRejected', language),
            }}
          />
        </div>
      )}
    </section>
  );
}
