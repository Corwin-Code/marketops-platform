import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  acknowledgeListingResponsibility,
  fetchListingResponsibility,
  type ListingResponsibility as Responsibility,
} from '../api/listingConversion';
import { ListingProblem, When, YesNo } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export function ListingResponsibility({
  context,
  actionId,
}: {
  readonly context: ConsoleRequest;
  readonly actionId: string;
}): React.JSX.Element {
  const { language } = useLanguage();
  const epoch = useRef(0);
  const [answer, setAnswer] = useState<Responsibility>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [pending, setPending] = useState(false);
  useEffect(() => {
    epoch.current += 1;
    setAnswer(undefined);
    setFailure(undefined);
    setPending(false);
    return () => {
      epoch.current += 1;
    };
  }, [context, actionId]);
  const status = answer?.status;
  const state = status?.clockState;
  return (
    <section aria-label={t('responsibilityLoad', language)}>
      <button
        type="button"
        disabled={pending}
        onClick={() => {
          const ticket = ++epoch.current;
          setPending(true);
          setAnswer(undefined);
          setFailure(undefined);
          void fetchListingResponsibility(context, actionId).then((result) => {
            if (ticket !== epoch.current) return;
            setPending(false);
            if (result.ok) setAnswer(result.value);
            else setFailure(result.failure);
          });
        }}
      >
        {t('responsibilityLoad', language)}
      </button>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {answer?.bound === false && <p>{t('responsibilityUnbound', language)}</p>}
      {status !== undefined && (
        <>
          <p>
            {t(
              state === 'IN_COVERAGE'
                ? 'responsibilityIn'
                : state === 'OUT_OF_COVERAGE'
                  ? 'responsibilityOut'
                  : state === 'SLO_UNRESOLVED'
                    ? 'responsibilitySloUnknown'
                    : 'responsibilityCoverageUnknown',
              language,
            )}
          </p>
          <p>{t('responsibilityHelp', language)}</p>
          <button
            type="button"
            disabled={pending || status.acknowledgedAt !== undefined}
            onClick={() => {
              const ticket = ++epoch.current;
              setPending(true);
              setFailure(undefined);
              void (async () => {
                const accepted = await acknowledgeListingResponsibility(context, actionId);
                if (ticket !== epoch.current) return;
                if (!accepted.ok) {
                  setPending(false);
                  setFailure(accepted.failure);
                  return;
                }
                const updated = await fetchListingResponsibility(context, actionId);
                if (ticket !== epoch.current) return;
                setPending(false);
                if (updated.ok) setAnswer(updated.value);
                else {
                  setAnswer(undefined);
                  setFailure(updated.failure);
                }
              })();
            }}
          >
            {t('responsibilityAcknowledge', language)}
          </button>
          <dl>
            <dt>{t('responsibilityTask', language)}</dt>
            <dd>{status.taskId}</dd>
            <dt>{t('responsibilityOrigin', language)}</dt>
            <dd>
              <When value={status.firstRaisedAt} />
            </dd>
            <dt>{t('responsibilityAckDue', language)}</dt>
            <dd>
              <When value={status.acknowledgementDueAt} />
            </dd>
            <dt>{t('responsibilityActionDue', language)}</dt>
            <dd>
              <When value={status.actionDueAt} />
            </dd>
            <dt>{t('responsibilityOutcomeDue', language)}</dt>
            <dd>
              <When value={status.outcomeMaturityDueAt} />
            </dd>
            <dt>{t('responsibilityNext', language)}</dt>
            <dd>
              <When value={status.nextCoveredAt} />
            </dd>
            <dt>{t('responsibilityAck', language)}</dt>
            <dd>
              <When value={status.acknowledgedAt} />
            </dd>
            <dt>{t('responsibilityAction', language)}</dt>
            <dd>
              <When value={status.firstAttributableActionAt} />
            </dd>
            <dt>{t('responsibilityAckLate', language)}</dt>
            <dd>
              <YesNo value={status.acknowledgementBreached} />
            </dd>
            <dt>{t('responsibilityActionLate', language)}</dt>
            <dd>
              <YesNo value={status.actionBreached} />
            </dd>
            <dt>{t('responsibilityBasis', language)}</dt>
            <dd>
              {status.calibrationPackageId ?? '—'} · {status.calibrationVersion ?? '—'} ·{' '}
              {status.basisDigest}
            </dd>
          </dl>
        </>
      )}
    </section>
  );
}
