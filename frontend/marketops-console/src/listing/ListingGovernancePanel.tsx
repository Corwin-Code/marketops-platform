import { useEffect, useState } from 'react';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import type { Batch, Containment, RecalculationEntry } from '../api/listingConversion';
import {
  addBatchMember,
  attestContainment,
  closeBatch,
  createBatch,
  fetchBatches,
  fetchContainments,
  fetchRecalculationQueue,
  reenableContainment,
  stopScope,
} from '../api/listingConversion';
import { Code, ListingProblem, When } from './ListingCommon';
import { useLanguage } from './i18n/language';
import { t } from './i18n/ui';

export interface ListingGovernancePanelProps {
  readonly context: ConsoleRequest;
  readonly storeId: string;
}

/**
 * Bounded batches, containment and the recalculation queue.
 *
 * Stopping is the fastest control on the page and needs one person and one
 * reason. Re-enabling needs two different people and the database counts them.
 */
export function ListingGovernancePanel({
  context,
  storeId,
}: ListingGovernancePanelProps): React.JSX.Element {
  const { language } = useLanguage();
  const [batches, setBatches] = useState<readonly Batch[] | undefined>(undefined);
  const [containments, setContainments] = useState<readonly Containment[] | undefined>(undefined);
  const [queue, setQueue] = useState<readonly RecalculationEntry[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [batchCode, setBatchCode] = useState('');
  const [batchStoreId, setBatchStoreId] = useState(storeId);
  const [memberAction, setMemberAction] = useState('');
  const [stopListing, setStopListing] = useState('');
  const [causeClass, setCauseClass] = useState('SAFETY_FAILURE');
  const [causeOwner, setCauseOwner] = useState('OWNER');
  const [reason, setReason] = useState('');
  const [evidence, setEvidence] = useState('');
  const [activeOnly, setActiveOnly] = useState(true);

  useEffect(() => {
    setBatchStoreId(storeId);
  }, [storeId]);

  useEffect(() => {
    let active = true;
    void Promise.all([
      fetchBatches(context),
      fetchContainments(context, activeOnly),
      fetchRecalculationQueue(context),
    ]).then(([batchOutcome, containmentOutcome, queueOutcome]) => {
      if (!active) return;
      const first = [batchOutcome, containmentOutcome, queueOutcome].find((outcome) => !outcome.ok);
      setFailure(first?.ok === false ? first.failure : undefined);
      if (batchOutcome.ok) setBatches(batchOutcome.value);
      if (containmentOutcome.ok) setContainments(containmentOutcome.value);
      if (queueOutcome.ok) setQueue(queueOutcome.value);
    });
    return () => {
      active = false;
    };
  }, [context, generation, activeOnly]);

  const settle = (outcome: { readonly ok: boolean; readonly failure?: ConsoleFailure }): void => {
    if (outcome.ok) {
      setFailure(undefined);
      setGeneration((value) => value + 1);
    } else if (outcome.failure !== undefined) {
      setFailure(outcome.failure);
    }
  };

  return (
    <section
      aria-label={t('tabGovernance', language)}
      data-state={containments === undefined ? 'loading' : 'loaded'}
    >
      {failure !== undefined && <ListingProblem failure={failure} />}

      <h3>{t('containments', language)}</h3>
      <form
        aria-label={t('stop', language)}
        onSubmit={(event) => {
          event.preventDefault();
          void stopScope(
            context,
            stopListing === '' ? 'ORGANIZATION' : 'LISTING',
            stopListing === '' ? undefined : stopListing,
            causeClass,
            causeOwner,
            reason,
            evidence,
          ).then(settle);
        }}
      >
        <label>
          {t('listing', language)}{' '}
          <input
            value={stopListing}
            onChange={(e) => {
              setStopListing(e.target.value);
            }}
          />
        </label>
        <label>
          {t('cause', language)}
          <select
            value={causeClass}
            onChange={(e) => {
              setCauseClass(e.target.value);
            }}
          >
            {[
              'LOCAL_COST',
              'SHARED_VERSION',
              'PATH_INTEGRITY',
              'SAFETY_FAILURE',
              'PLATFORM_INCIDENT',
            ].map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('attestRepair', language)}
          <select
            value={causeOwner}
            onChange={(e) => {
              setCauseOwner(e.target.value);
            }}
          >
            {['OWNER', 'TECH_DATA', 'OPS_LEAD', 'MARKETPLACE_OPERATOR'].map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('reason', language)}{' '}
          <input
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
            }}
          />
        </label>
        <label>
          {t('evidence', language)}{' '}
          <input
            value={evidence}
            onChange={(e) => {
              setEvidence(e.target.value);
            }}
          />
        </label>
        <button type="submit">
          {stopListing === '' ? t('stopAll', language) : t('stop', language)}
        </button>
      </form>
      <label>
        <input
          type="checkbox"
          checked={activeOnly}
          onChange={(e) => {
            setActiveOnly(e.target.checked);
          }}
        />{' '}
        <Code family="containmentState" code="ACTIVE" />
      </label>
      {containments?.length === 0 && <p>{t('nothing', language)}</p>}
      {containments?.map((containment) => (
        <article
          key={containment.id}
          data-containment={containment.id}
          data-containment-state={containment.state}
        >
          <h4>
            <Code family="containmentScope" code={containment.scopeKind} /> ·{' '}
            <Code family="containmentCauseClass" code={containment.causeClass} /> ·{' '}
            <Code family="containmentState" code={containment.state} />
          </h4>
          <p>
            {t('stoppedBy', language)} {containment.stoppedByUserId}{' '}
            <When value={containment.stoppedAt} /> — {containment.reason}
          </p>
          <ul>
            {containment.attestations.map((attestation) => (
              <li key={`${attestation.attestationKind}:${attestation.actorUserId}`}>
                <Code family="attestationKind" code={attestation.attestationKind} />{' '}
                {attestation.actorUserId}
              </li>
            ))}
          </ul>
          {containment.state === 'ACTIVE' && (
            <>
              <button
                type="button"
                onClick={() => {
                  void attestContainment(
                    context,
                    containment.id,
                    'REPAIR_ATTESTATION',
                    evidence,
                  ).then(settle);
                }}
              >
                {t('attestRepair', language)}
              </button>
              <button
                type="button"
                onClick={() => {
                  void attestContainment(
                    context,
                    containment.id,
                    'BUSINESS_CONSENT',
                    evidence,
                  ).then(settle);
                }}
              >
                {t('consent', language)}
              </button>
              <button
                type="button"
                onClick={() => {
                  void reenableContainment(context, containment.id).then(settle);
                }}
              >
                {t('reenable', language)}
              </button>
            </>
          )}
        </article>
      ))}

      <h3>{t('batches', language)}</h3>
      <form
        aria-label={t('batches', language)}
        onSubmit={(event) => {
          event.preventDefault();
          void createBatch(context, batchStoreId, batchCode).then(settle);
        }}
      >
        <label>
          {t('batchStoreId', language)}{' '}
          <input
            required
            value={batchStoreId}
            onChange={(event) => {
              setBatchStoreId(event.target.value);
            }}
          />
        </label>
        <label>
          {t('batches', language)}{' '}
          <input
            value={batchCode}
            onChange={(e) => {
              setBatchCode(e.target.value);
            }}
          />
        </label>
        <button type="submit">{t('submit', language)}</button>
      </form>
      {batches?.length === 0 && <p>{t('nothing', language)}</p>}
      {batches?.map((batch) => (
        <article key={batch.id} data-batch={batch.id}>
          <h4>
            {batch.batchCode} · {batch.state} · {t('members', language)} {batch.members.length}
          </h4>
          <ul>
            {batch.members.map((member) => (
              <li key={member.actionId}>
                #{member.sequenceNo} {member.actionId}{' '}
                <Code family="actionState" code={member.actionState} /> {member.membershipState}
              </li>
            ))}
          </ul>
          {batch.state === 'OPEN' && (
            <>
              <label>
                {t('actions', language)}{' '}
                <input
                  value={memberAction}
                  onChange={(e) => {
                    setMemberAction(e.target.value);
                  }}
                />
              </label>
              <button
                type="button"
                onClick={() => {
                  void addBatchMember(context, batch.id, memberAction).then(settle);
                }}
              >
                {t('members', language)}
              </button>
              <button
                type="button"
                onClick={() => {
                  void closeBatch(context, batch.id).then(settle);
                }}
              >
                {t('cancel', language)}
              </button>
            </>
          )}
        </article>
      ))}

      <h3>{t('recalculation', language)}</h3>
      {queue?.length === 0 && <p>{t('nothing', language)}</p>}
      {queue !== undefined && queue.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>{t('listing', language)}</th>
              <th>{t('state', language)}</th>
              <th>{t('target', language)}</th>
              <th>{t('enqueued', language)}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {queue.map((entry) => (
              <tr key={entry.id} data-within-target={String(entry.withinTarget)}>
                <td>{entry.platformListingId}</td>
                <td>
                  <Code family="queueState" code={entry.state} />
                </td>
                <td>
                  <Code family="recalculationClass" code={entry.triggerClass} />{' '}
                  {entry.targetMinutes} {t('minutes', language)}
                </td>
                <td>
                  <When value={entry.acceptedAt} />
                </td>
                <td>{entry.latencySeconds ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
