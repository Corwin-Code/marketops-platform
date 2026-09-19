import { useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  holdListingTaskForDependency,
  type ListingDependencyHoldTarget,
  type ListingTaskDependencyHold,
} from '../api/listingConversion';
import { ListingProblem, When } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

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
  const { language } = useLanguage();
  const [dependencyTaskId, setDependencyTaskId] = useState('');
  const [minutes, setMinutes] = useState('');
  const [evidence, setEvidence] = useState('');
  const [answer, setAnswer] = useState<ListingTaskDependencyHold | undefined>(current);
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [busy, setBusy] = useState(false);
  const shown = answer ?? current;
  return (
    <section aria-label={t('dependencyHoldTitle', language)}>
      <h5>{t('dependencyHoldTitle', language)}</h5>
      <p>{t('dependencyHoldHelp', language)}</p>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {shown !== undefined && (
        <dl>
          <dt>{t('dependencyTask', language)}</dt>
          <dd>{shown.dependencyTaskId}</dd>
          <dt>{t('dependencyHoldState', language)}</dt>
          <dd>{shown.state}</dd>
          <dt>{t('dependencyHoldUntil', language)}</dt>
          <dd>
            <When value={shown.expiresAt} />
          </dd>
          <dt>{t('evidence', language)}</dt>
          <dd>{shown.evidenceReference}</dd>
          {shown.endReason === undefined ? null : (
            <>
              <dt>{t('dependencyHoldEnd', language)}</dt>
              <dd>{shown.endReason}</dd>
            </>
          )}
        </dl>
      )}
      {shown?.state !== 'ACTIVE' && (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            setBusy(true);
            setFailure(undefined);
            void holdListingTaskForDependency(
              context,
              target,
              dependencyTaskId,
              Number(minutes),
              evidence,
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
          <fieldset disabled={busy}>
            <label>
              {t('dependencyTask', language)}
              <input
                required
                value={dependencyTaskId}
                onChange={(event) => {
                  setDependencyTaskId(event.target.value);
                }}
              />
            </label>
            <label>
              {t('dependencyHoldMinutes', language)}
              <input
                required
                type="number"
                min="1"
                step="1"
                value={minutes}
                onChange={(event) => {
                  setMinutes(event.target.value);
                }}
              />
            </label>
            <label>
              {t('dependencyHoldEvidence', language)}
              <textarea
                required
                maxLength={512}
                value={evidence}
                onChange={(event) => {
                  setEvidence(event.target.value);
                }}
              />
            </label>
            <button type="submit">{t('dependencyHoldSubmit', language)}</button>
          </fieldset>
        </form>
      )}
    </section>
  );
}
