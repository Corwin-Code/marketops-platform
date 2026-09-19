import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  deferListingTask,
  type ListingDeferralTarget,
  type ListingTaskDeferral,
} from '../api/listingConversion';
import { ListingProblem, When } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export function ListingDeferral({
  context,
  target,
  current,
}: {
  readonly context: ConsoleRequest;
  readonly target: ListingDeferralTarget;
  readonly current: ListingTaskDeferral | undefined;
}): React.JSX.Element {
  const { language } = useLanguage();
  const epoch = useRef(0);
  const key =
    target.kind === 'ACTION'
      ? `action:${target.actionId}`
      : `diagnostic:${target.listingId}:${target.taskId}`;
  const [value, setValue] = useState(current);
  const [minutes, setMinutes] = useState('');
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    epoch.current += 1;
    setValue(current);
    setMinutes('');
    setReason('');
    setPending(false);
    setFailure(undefined);
    return () => {
      epoch.current += 1;
    };
  }, [context, key, current]);
  const stateLabels = {
    ACTIVE: 'deferralActive',
    REVIEW_DUE: 'deferralReviewDue',
    EXPIRED: 'deferralExpired',
    INVALIDATED: 'deferralInvalidated',
  } as const;
  const waiting =
    value !== undefined &&
    (value.state === 'ACTIVE' ||
      value.state === 'REVIEW_DUE' ||
      value.reviewHealthId === undefined);
  return (
    <section aria-label={t('deferralTitle', language)}>
      <h5>{t('deferralTitle', language)}</h5>
      <p>{t('deferralHelp', language)}</p>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {value !== undefined && (
        <dl>
          <dt>{t('deferralState', language)}</dt>
          <dd>{t(stateLabels[value.state], language)}</dd>
          <dt>{t('deferralReason', language)}</dt>
          <dd>{value.reason}</dd>
          <dt>{t('deferralUntil', language)}</dt>
          <dd>
            <When value={value.expiresAt} />
          </dd>
          <dt>{t('deferralReview', language)}</dt>
          <dd>{value.reviewHealthId ?? '—'}</dd>
        </dl>
      )}
      <form
        aria-label={t('deferralSubmit', language)}
        onSubmit={(event) => {
          event.preventDefault();
          const ticket = ++epoch.current;
          setPending(true);
          setFailure(undefined);
          void deferListingTask(context, target, Number(minutes), reason).then((result) => {
            if (ticket !== epoch.current) return;
            setPending(false);
            if (result.ok) setValue(result.value);
            else setFailure(result.failure);
          });
        }}
      >
        <label>
          {t('deferralMinutes', language)}{' '}
          <input
            type="number"
            min="1"
            step="1"
            required
            value={minutes}
            disabled={pending || waiting}
            onChange={(event) => {
              setMinutes(event.target.value);
            }}
          />
        </label>
        <label>
          {t('deferralReason', language)}{' '}
          <input
            required
            maxLength={512}
            value={reason}
            disabled={pending || waiting}
            onChange={(event) => {
              setReason(event.target.value);
            }}
          />
        </label>
        <button
          type="submit"
          disabled={
            pending ||
            waiting ||
            !Number.isInteger(Number(minutes)) ||
            Number(minutes) < 1 ||
            reason.trim() === ''
          }
        >
          {t('deferralSubmit', language)}
        </button>
      </form>
    </section>
  );
}
