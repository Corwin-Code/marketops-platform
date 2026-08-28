import { useEffect, useState } from 'react';
import { fetchPriorityQueue } from '../api/console';
import type { ConsoleFailure, ConsoleRequest, PrioritySubject } from '../api/console';
import { formatAmount } from '../state/confidence';

/** What the queue needs in order to load itself. */
export interface PriorityQueueProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** Store whose work list is being shown. */
  readonly storeId: string;
  /** Called when the operator picks a subject to look at. */
  readonly onSelect: (subjectId: string) => void;
}

/**
 * What to look at first.
 *
 * The list is ordered by the backend rather than re-sorted here, because the
 * priority is a deterministic figure with a definition and a console that
 * re-ordered it would be presenting its own opinion as the product's.
 *
 * A subject whose findings block a platform write says so in the row. An
 * operator who opens a subject expecting to change its price and only then
 * learns that nothing can be changed has spent their attention for nothing.
 */
export function PriorityQueue({
  context,
  storeId,
  onSelect,
}: PriorityQueueProps): React.JSX.Element {
  const [subjects, setSubjects] = useState<readonly PrioritySubject[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchPriorityQueue(context, storeId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setSubjects(outcome.value);
        setFailure(undefined);
      } else {
        setSubjects(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, storeId]);

  if (failure !== undefined) {
    return <QueueProblem failure={failure} />;
  }
  if (subjects === undefined) {
    return (
      <section aria-label="Priority queue" data-state="loading">
        <h2>Today&rsquo;s work</h2>
        <p role="status">Loading the work list…</p>
      </section>
    );
  }
  if (subjects.length === 0) {
    return (
      <section aria-label="Priority queue" data-state="empty">
        <h2>Today&rsquo;s work</h2>
        <p role="status">Nothing needs attention in this store right now.</p>
      </section>
    );
  }

  return (
    <section aria-label="Priority queue" data-state="loaded">
      <h2>Today&rsquo;s work</h2>
      <table>
        <caption>Listing variants ordered by how much they need attention</caption>
        <thead>
          <tr>
            <th scope="col">Listing variant</th>
            <th scope="col">Priority</th>
            <th scope="col">Critical</th>
            <th scope="col">Warning</th>
            <th scope="col">Undecided</th>
            <th scope="col">Net sales</th>
            <th scope="col">Contribution profit</th>
            <th scope="col">Write</th>
          </tr>
        </thead>
        <tbody>
          {subjects.map((subject) => (
            <tr key={subject.subjectId} data-subject={subject.subjectId}>
              <td>
                <button
                  type="button"
                  onClick={() => {
                    onSelect(subject.subjectId);
                  }}
                >
                  {subject.subjectId}
                </button>
              </td>
              <td>{subject.priorityScore}</td>
              <td>{subject.criticalFindingCount}</td>
              <td>{subject.warningFindingCount}</td>
              <td>{subject.declinedRuleCount}</td>
              <td>{formatAmount(subject.netSales, subject.currencyCode)}</td>
              <td>{formatAmount(subject.contributionProfit, subject.currencyCode)}</td>
              <td data-write-blocked={subject.blockingRuleCodes.length > 0}>
                {subject.blockingRuleCodes.length > 0
                  ? `Blocked: ${subject.blockingRuleCodes.join(', ')}`
                  : 'Open'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/**
 * Say what went wrong in terms of what the operator can do about it.
 *
 * Each refusal has a different remedy, so each gets its own sentence rather
 * than a shared "something went wrong" that leaves a person guessing which of
 * three unrelated problems they have.
 */
export function QueueProblem({ failure }: { readonly failure: ConsoleFailure }): React.JSX.Element {
  const message = ((): string => {
    switch (failure.kind) {
      case 'unauthenticated':
        return 'Your session has ended. Sign in again to continue.';
      case 'step-up-required':
        return 'This action needs a recent sign-in. Re-authenticate and try again.';
      case 'forbidden':
        return 'Your profile does not have access to this store.';
      case 'unreachable':
        return 'The platform did not answer. Nothing was changed.';
      case 'malformed':
        return 'The platform answered with something this console cannot read.';
      case 'refused':
        return `The platform refused the request (${String(failure.status)}).`;
    }
  })();
  return (
    <section aria-label="Priority queue" data-state="error">
      <h2>Today&rsquo;s work</h2>
      <p role="alert">{message}</p>
    </section>
  );
}
