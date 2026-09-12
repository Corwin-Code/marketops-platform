import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  fetchMeaningReviewBasis,
  reviewAction,
  type MeaningAssessment,
  type MeaningReviewBasis,
} from '../api/listingConversion';
import { ListingProblem } from './ListingCommon';
import { PromotionTermsDetails } from './ListingPromotionTerms';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export function ListingMeaningReview({
  context,
  actionId,
  reason,
  onOutcome,
}: {
  readonly context: ConsoleRequest;
  readonly actionId: string;
  readonly reason: string;
  readonly onOutcome: (outcome: {
    readonly ok: boolean;
    readonly failure?: ConsoleFailure;
  }) => void;
}): React.JSX.Element {
  const { language } = useLanguage();
  const epoch = useRef(0);
  const [basis, setBasis] = useState<MeaningReviewBasis>();
  const [answers, setAnswers] = useState<MeaningAssessment['answers']>([]);
  const [reference, setReference] = useState('');
  const [complete, setComplete] = useState(false);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    epoch.current += 1;
    setBasis(undefined);
    setAnswers([]);
    setReference('');
    setComplete(false);
    setPending(false);
    setFailure(undefined);
    return () => {
      epoch.current += 1;
    };
  }, [context, actionId]);
  const qualified = basis?.ruleState === 'QUALIFIED' && basis.conditions.length > 0;
  const ready =
    qualified &&
    complete &&
    reference.trim() !== '' &&
    reason.trim() !== '' &&
    answers.length === basis.conditions.length &&
    answers.every((a) => a.state !== 'UNKNOWN' && a.reason.trim() !== '');
  return (
    <section aria-label={t('meaningLoad', language)}>
      <p>{t('meaningHelp', language)}</p>
      <button
        type="button"
        disabled={pending}
        onClick={() => {
          const ticket = ++epoch.current;
          setPending(true);
          setBasis(undefined);
          setAnswers([]);
          setComplete(false);
          setReference('');
          setFailure(undefined);
          void fetchMeaningReviewBasis(context, actionId).then((outcome) => {
            if (ticket !== epoch.current) return;
            setPending(false);
            if (outcome.ok) {
              setBasis(outcome.value);
              setAnswers(
                outcome.value.conditions.map((c) => ({
                  code: c.code,
                  state: 'UNKNOWN',
                  reason: '',
                })),
              );
            } else setFailure(outcome.failure);
          });
        }}
      >
        {t('meaningLoad', language)}
      </button>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {basis !== undefined && (
        <>
          {basis.currentText !== undefined && (
            <>
              <h4>{t('meaningCurrent', language)}</h4>
              <pre lang="ru">{basis.currentText}</pre>
            </>
          )}
          {basis.targetText !== undefined && (
            <>
              <h4>{t('meaningTarget', language)}</h4>
              <pre lang="ru">{basis.targetText}</pre>
            </>
          )}
          {basis.promotionTerms !== undefined && (
            <PromotionTermsDetails terms={basis.promotionTerms} />
          )}
          {!qualified && <p>{t('meaningUnqualified', language)}</p>}
          {basis.conditions.map((condition, index) => (
            <fieldset key={condition.code} disabled={pending}>
              <legend>{condition.condition}</legend>
              <label>
                {condition.code}
                <select
                  value={answers[index]?.state ?? 'UNKNOWN'}
                  onChange={(event) => {
                    const state = event.target
                      .value as MeaningAssessment['answers'][number]['state'];
                    setAnswers(answers.map((a, i) => (i === index ? { ...a, state } : a)));
                  }}
                >
                  <option value="UNKNOWN">{t('meaningUnknown', language)}</option>
                  <option value="APPLIES">{t('meaningApplies', language)}</option>
                  <option value="DOES_NOT_APPLY">{t('meaningNotApplies', language)}</option>
                </select>
              </label>
              <label>
                {t('meaningReason', language)} {index + 1}
                <textarea
                  maxLength={2000}
                  value={answers[index]?.reason ?? ''}
                  onChange={(event) => {
                    setAnswers(
                      answers.map((a, i) =>
                        i === index ? { ...a, reason: event.target.value } : a,
                      ),
                    );
                  }}
                />
              </label>
            </fieldset>
          ))}
          <label>
            {t('meaningReference', language)}
            <input
              disabled={pending}
              maxLength={512}
              value={reference}
              onChange={(event) => {
                setReference(event.target.value);
              }}
            />
          </label>
          <label>
            <input
              type="checkbox"
              disabled={pending}
              checked={complete}
              onChange={(event) => {
                setComplete(event.target.checked);
              }}
            />
            {t('meaningComplete', language)}
          </label>
        </>
      )}
      <button
        type="button"
        disabled={pending || !ready}
        onClick={() => {
          if (!ready) return;
          const ticket = ++epoch.current;
          setPending(true);
          void reviewAction(context, actionId, 'ATTESTED', reason, {
            model: 'LC_MEANING_REVIEW_1',
            basisDigest: basis.basisDigest,
            complete,
            evidenceReference: reference,
            answers,
          }).then((outcome) => {
            if (ticket !== epoch.current) return;
            setPending(false);
            onOutcome(outcome);
          });
        }}
      >
        {t('attest', language)}
      </button>
    </section>
  );
}
