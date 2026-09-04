import { useEffect, useState } from 'react';
import { fetchAdvertisingOutcomes } from '../api/console';
import type { AdvertisingOutcome } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { AdvertisingProblem } from './AdvertisingQueue';

/** What the outcome history needs in order to load itself. */
export interface AdvertisingOutcomeHistoryProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The command whose outcome is being read. */
  readonly commandId: string;
}

/**
 * What one bid change actually did, stage by stage.
 *
 * Never one number. An operational reading counts orders and arrives within
 * days; a settled reading counts what the buyer kept and does not arrive until
 * returns have run their course. They are different claims about the same
 * change, and a console showing a single figure labelled &ldquo;result&rdquo;
 * would be showing whichever of the two happened to be written last.
 *
 * Restatements are shown in place rather than replacing what they restate. A
 * settled figure can change when a late return arrives, and the fact that the
 * answer changed is itself something an operator needs to see — particularly
 * when the original reading was the one somebody acted on.
 */
export function AdvertisingOutcomeHistory({
  context,
  commandId,
}: AdvertisingOutcomeHistoryProps): React.JSX.Element {
  const [outcomes, setOutcomes] = useState<readonly AdvertisingOutcome[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchAdvertisingOutcomes(context, commandId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setOutcomes(outcome.value);
        setFailure(undefined);
      } else {
        setOutcomes(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, commandId]);

  if (failure !== undefined) {
    return <AdvertisingProblem failure={failure} />;
  }
  if (outcomes === undefined) {
    return (
      <section aria-label="Outcome" data-state="loading">
        <h3>Outcome</h3>
        <p>Loading what this change achieved.</p>
      </section>
    );
  }
  if (outcomes.length === 0) {
    return (
      <section aria-label="Outcome" data-state="empty">
        <h3>Outcome</h3>
        <p>
          Nothing has been observed yet. An absent outcome is not a neutral one — the measurement
          window has not closed.
        </p>
      </section>
    );
  }

  return (
    <section aria-label="Outcome" data-state="loaded">
      <h3>Outcome</h3>
      <ol>
        {outcomes.map((observation) => (
          <li
            key={observation.id}
            data-stage={observation.outcomeStage}
            data-settled={observation.settled}
            data-revision={observation.revisionNo}
          >
            <h4>
              {observation.settled
                ? 'Settled — what the buyer kept'
                : 'Operational — what was ordered'}
              {observation.revisionNo > 1 ? ` (restatement ${String(observation.revisionNo)})` : ''}
            </h4>
            <p data-verdict={observation.verdict}>
              {observation.verdict}
              {observation.guardState === undefined ? null : (
                <small> — completed-sales guard: {observation.guardState}</small>
              )}
            </p>
            <dl>
              <dt>Baseline</dt>
              <dd data-state={observation.baselineMetricState}>
                {observation.baselineMetricValue ?? observation.baselineMetricState}
              </dd>
              <dt>Observed</dt>
              <dd data-state={observation.observedMetricState}>
                {observation.observedMetricValue ?? observation.observedMetricState}
              </dd>
              {observation.settled ? (
                <>
                  <dt>Settled coverage</dt>
                  <dd>{observation.settledCoverageRatio ?? 'not measured'}</dd>
                </>
              ) : null}
            </dl>
            {observation.adjustmentReason === undefined ? null : (
              <p data-adjustment="true">Restated because: {observation.adjustmentReason}</p>
            )}
            {observation.unresolvedReasonCodes.length === 0 ? null : (
              <ul aria-label="Why this verdict is not conclusive">
                {observation.unresolvedReasonCodes.map((code) => (
                  <li key={code}>{code}</li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ol>
    </section>
  );
}
