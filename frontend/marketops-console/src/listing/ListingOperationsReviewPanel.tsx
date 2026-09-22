import { useEffect, useMemo, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import {
  fetchListingExperience,
  fetchListingOperationsReview,
  recordListingExperience,
  type ListingExperienceApplication,
  type ListingOperationsReview,
  type ListingReviewReading,
} from '../api/listingConversion';
import { Code, ListingProblem, When } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export function ListingOperationsReviewPanel({
  context,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}): React.JSX.Element {
  const { language } = useLanguage();
  const [bundle, setBundle] = useState<ListingOperationsReview>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [kind, setKind] = useState<'current' | 'daily' | 'weekly'>('current');
  const [storeInput, setStoreInput] = useState(storeId);
  const [loadedStoreId, setLoadedStoreId] = useState(storeId);
  const [generation, setGeneration] = useState(0);
  useEffect(() => {
    setStoreInput(storeId);
    setLoadedStoreId(storeId);
  }, [storeId]);
  useEffect(() => {
    let active = true;
    setBundle(undefined);
    setFailure(undefined);
    void fetchListingOperationsReview(context, loadedStoreId).then((result) => {
      if (!active) return;
      if (result.ok) setBundle(result.value);
      else setFailure(result.failure);
    });
    return () => {
      active = false;
    };
  }, [context, loadedStoreId, generation]);
  const reading = bundle?.[kind];
  return (
    <section aria-label={t('operationsReview', language)}>
      <h3>{t('operationsReview', language)}</h3>
      <p>{t('operationsReviewBoundary', language)}</p>
      <form
        aria-label={t('operationsReviewLoad', language)}
        onSubmit={(event) => {
          event.preventDefault();
          if (storeInput.trim() === '') return;
          setLoadedStoreId(storeInput);
          setGeneration((value) => value + 1);
        }}
      >
        <label>
          {t('operationsReviewStore', language)}
          <input
            required
            value={storeInput}
            onChange={(event) => {
              setStoreInput(event.target.value);
            }}
          />
        </label>
        <button type="submit">{t('operationsReviewLoad', language)}</button>
      </form>
      {failure !== undefined && <ListingProblem failure={failure} />}
      {bundle === undefined && failure === undefined && <p>{t('loading', language)}</p>}
      {bundle !== undefined && (
        <>
          <p>
            {t('reviewTimezone', language)}: {bundle.timezone} · {t('feedbackSnapshot', language)}:{' '}
            <When value={bundle.asOf} />
          </p>
          <nav aria-label={t('operationsReview', language)}>
            {(['current', 'daily', 'weekly'] as const).map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={kind === value}
                onClick={() => {
                  setKind(value);
                }}
              >
                {t(
                  value === 'current'
                    ? 'reviewCurrent'
                    : value === 'daily'
                      ? 'reviewDaily'
                      : 'reviewWeekly',
                  language,
                )}
              </button>
            ))}
          </nav>
          {reading !== undefined && <ReviewReading reading={reading} />}
          <ExperienceWorkbench context={context} reading={bundle.current} />
        </>
      )}
    </section>
  );
}

function ReviewReading({ reading }: { readonly reading: ListingReviewReading }): React.JSX.Element {
  const { language } = useLanguage();
  return (
    <section data-reading={reading.kind}>
      <h4>
        {t(
          reading.kind === 'CURRENT_QUEUE'
            ? 'reviewCurrent'
            : reading.kind === 'DAILY_ACTION_BRIEF'
              ? 'reviewDaily'
              : 'reviewWeekly',
          language,
        )}
      </h4>
      <p>
        {t('reviewPeriodStart', language)}: {reading.periodStart} ·{' '}
        {t('feedbackSnapshot', language)}: <When value={reading.asOf} />
      </p>
      {reading.rows.length === 0 && <p>{t('nothing', language)}</p>}
      {reading.rows.map((row) => (
        <article key={row.health.id} data-lane={row.lane}>
          <h5>
            {row.health.nativeListingKey} · {row.lane}
          </h5>
          <dl>
            <dt>{t('healthNecessary', language)}</dt>
            <dd>
              <Code family="healthState" code={row.health.necessaryState} />
            </dd>
            <dt>{t('healthVersion', language)}</dt>
            <dd>{row.health.healthVersion}</dd>
            <dt>{t('affectedSet', language)}</dt>
            <dd>
              {row.health.affectedSetState} · {row.health.affectedVariantCount}
            </dd>
            <dt>{t('sourceTime', language)}</dt>
            <dd>
              <When value={row.health.sourceTime} />
            </dd>
            <dt>{t('acquisitionTime', language)}</dt>
            <dd>
              <When value={row.health.acquisitionTime} />
            </dd>
            <dt>{t('computedAt', language)}</dt>
            <dd>
              <When value={row.health.computedAt} />
            </dd>
          </dl>
          {row.responsibilities.map((task) => (
            <section key={task.taskId} aria-label={t('responsibilityTask', language)}>
              <p>
                {task.lane} · {task.causeCode} · {task.taskState} · {task.taskId}
              </p>
              <p>
                {t('responsibilityOrigin', language)}: <When value={task.status.firstRaisedAt} /> ·{' '}
                {t('responsibilityActionDue', language)}: <When value={task.status.actionDueAt} />
              </p>
              {task.status.dependencyHold === undefined ? null : (
                <p>
                  {t('dependencyHoldState', language)}: {task.status.dependencyHold.state} ·{' '}
                  {task.status.dependencyHold.dependencyTaskId}
                </p>
              )}
            </section>
          ))}
          {row.actions.map((entry) => (
            <section key={entry.action.id} aria-label={t('actions', language)}>
              <h6>
                {entry.action.actionKind} · {entry.action.state}
              </h6>
              <p>
                {entry.action.id} · {entry.action.purposeCode ?? t('undeclared', language)}
              </p>
              <p>
                {t('affectedSet', language)}: {entry.action.affectedSetState} ·{' '}
                {entry.action.affectedVariantCount} · <code>{entry.action.affectedSetDigest}</code>
              </p>
              {entry.action.targetText === undefined ? null : <pre>{entry.action.targetText}</pre>}
              {entry.action.bindingGaps.length === 0 ? null : (
                <p>
                  {t('bindingGaps', language)}: {entry.action.bindingGaps.join(', ')}
                </p>
              )}
              {entry.command === undefined ? null : (
                <p>
                  {t('descriptionCommand', language)}: {entry.command.id} · {entry.command.state}
                  {entry.command.failureCode === undefined ? '' : ` · ${entry.command.failureCode}`}
                </p>
              )}
              {entry.evaluation === undefined ? (
                <p>{t('noEvaluation', language)}</p>
              ) : (
                <>
                  <p>
                    {t('evaluationPlanDigest', language)}:{' '}
                    <code>{entry.evaluation.planDigest ?? '—'}</code>
                  </p>
                  <ul>
                    {entry.evaluation.results.map((result) => (
                      <li key={result.id}>
                        {result.id} · {result.nodeCode} · {result.stage} #{result.revisionNo} ·{' '}
                        {result.verdict} · {result.protectionVerdict}
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </section>
          ))}
          {row.recalculation === undefined ? null : (
            <p>
              {t('recalculationReceipt', language)}: {row.recalculation.triggerClass} (
              {row.recalculation.targetMinutes}) · {row.recalculation.state} ·{' '}
              {row.recalculation.measurementResultIds.length} ·{' '}
              {row.recalculation.bindingAssessedCount ?? 0}/
              {row.recalculation.bindingInvalidatedCount ?? 0}
            </p>
          )}
        </article>
      ))}
    </section>
  );
}

function ExperienceWorkbench({
  context,
  reading,
}: {
  readonly context: ConsoleRequest;
  readonly reading: ListingReviewReading;
}): React.JSX.Element {
  const { language } = useLanguage();
  const sources = useMemo(
    () =>
      reading.rows.flatMap((row) =>
        row.actions.flatMap((entry) =>
          (entry.evaluation?.results ?? []).map((result) => ({
            actionId: entry.action.id,
            resultId: result.id,
            label: `${row.health.nativeListingKey} · ${result.nodeCode} · ${result.stage} #${String(result.revisionNo)}`,
          })),
        ),
      ),
    [reading],
  );
  const [target, setTarget] = useState(reading.rows[0]?.health.platformListingId ?? '');
  const [source, setSource] = useState(
    sources[0] === undefined ? '' : `${sources[0].actionId}|${sources[0].resultId}`,
  );
  const [candidateKind, setCandidateKind] = useState('CONTENT_DESCRIPTION');
  const [evidence, setEvidence] = useState('');
  const [history, setHistory] = useState<readonly ListingExperienceApplication[]>([]);
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    setHistory([]);
    setFailure(undefined);
    if (target !== '')
      void fetchListingExperience(context, target).then((result) => {
        if (!active) return;
        if (result.ok) setHistory(result.value);
        else setFailure(result.failure);
      });
    return () => {
      active = false;
    };
  }, [context, target]);
  return (
    <section aria-label={t('experience', language)}>
      <h4>{t('experience', language)}</h4>
      <p>{t('experienceBoundary', language)}</p>
      {failure === undefined ? null : <ListingProblem failure={failure} />}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          const [sourceActionId, sourceResultId] = source.split('|');
          if (sourceActionId === undefined || sourceResultId === undefined) return;
          setBusy(true);
          setFailure(undefined);
          void recordListingExperience(context, {
            sourceActionId,
            sourceResultId,
            targetListingId: target,
            candidateKind,
            applicabilityEvidenceReference: evidence,
          })
            .then(async (result) => {
              if (!result.ok) {
                setFailure(result.failure);
                return;
              }
              const updated = await fetchListingExperience(context, target);
              if (updated.ok) setHistory(updated.value);
              else setFailure(updated.failure);
            })
            .finally(() => {
              setBusy(false);
            });
        }}
      >
        <fieldset disabled={busy || reading.rows.length === 0 || sources.length === 0}>
          <label>
            {t('experienceTarget', language)}
            <select
              value={target}
              onChange={(event) => {
                setTarget(event.target.value);
              }}
            >
              {reading.rows.map((row) => (
                <option key={row.health.platformListingId} value={row.health.platformListingId}>
                  {row.health.nativeListingKey}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('experienceSource', language)}
            <select
              value={source}
              onChange={(event) => {
                setSource(event.target.value);
              }}
            >
              {sources.map((value) => (
                <option
                  key={`${value.actionId}:${value.resultId}`}
                  value={`${value.actionId}|${value.resultId}`}
                >
                  {value.label}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('experienceCandidateKind', language)}
            <select
              value={candidateKind}
              onChange={(event) => {
                setCandidateKind(event.target.value);
              }}
            >
              {[
                'CONTENT_DESCRIPTION',
                'OFFICIAL_PROMOTION_PARTICIPATION',
                'SELLER_DIRECT_DISCOUNT',
              ].map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('experienceEvidence', language)}
            <textarea
              required
              maxLength={512}
              value={evidence}
              onChange={(event) => {
                setEvidence(event.target.value);
              }}
            />
          </label>
          <button type="submit">{t('experienceApply', language)}</button>
        </fieldset>
      </form>
      <ul>
        {history.map((item) => (
          <li key={item.id}>
            {item.candidateKind} · {item.sourceStage} · {item.sourceVerdict} ·{' '}
            {item.applicabilityState} · {item.applicabilityEvidenceReference}
          </li>
        ))}
      </ul>
    </section>
  );
}
