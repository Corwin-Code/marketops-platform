import { useEffect, useRef, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  acknowledgeListingDiagnostic,
  type ListingDiagnosticResponsibility,
} from '../api/listingConversion';
import { Code, ListingProblem } from './ListingCommon';
import { ResponsibilityTimes } from './ListingResponsibility';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';
import { ListingDeferral } from './ListingDeferral';

export function ListingDiagnosticResponsibilities({
  context,
  listingId,
  responsibilities,
  refresh,
}: {
  readonly context: ConsoleRequest;
  readonly listingId: string;
  readonly responsibilities: readonly ListingDiagnosticResponsibility[];
  readonly refresh: () => void;
}): React.JSX.Element {
  const { language } = useLanguage();
  const epoch = useRef(0);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    epoch.current += 1;
    setPending(false);
    setFailure(undefined);
    return () => {
      epoch.current += 1;
    };
  }, [context, listingId]);
  return (
    <section aria-label={t('responsibilityDiagnostics', language)}>
      {responsibilities.length > 0 && <h4>{t('responsibilityDiagnostics', language)}</h4>}
      {failure !== undefined && <ListingProblem failure={failure} />}
      {responsibilities.map(({ causeCode, status }) => (
        <article key={status.taskId}>
          <h5>
            <Code family="healthCondition" code={causeCode} />
          </h5>
          <p>
            {t(
              status.clockState === 'CONTINUOUS_RISK'
                ? 'responsibilityContinuous'
                : 'responsibilitySloUnknown',
              language,
            )}
          </p>
          <button
            type="button"
            disabled={pending || status.acknowledgedAt !== undefined}
            onClick={() => {
              const ticket = ++epoch.current;
              setPending(true);
              setFailure(undefined);
              void acknowledgeListingDiagnostic(context, listingId, status.taskId).then(
                (result) => {
                  if (ticket !== epoch.current) return;
                  setPending(false);
                  if (result.ok) refresh();
                  else setFailure(result.failure);
                },
              );
            }}
          >
            {t('responsibilityAcknowledge', language)}
          </button>
          <ResponsibilityTimes status={status} />
          <ListingDeferral
            context={context}
            target={{ kind: 'DIAGNOSTIC', listingId, taskId: status.taskId }}
            current={status.deferral}
          />
        </article>
      ))}
    </section>
  );
}
