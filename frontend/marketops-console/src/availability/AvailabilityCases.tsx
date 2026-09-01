import { useCallback, useEffect, useState } from 'react';
import {
  fetchAvailabilityCases,
  fetchCaseExceptions,
  fetchCaseJournal,
  recordCaseAction,
} from '../api/console';
import type {
  AcceptedException,
  AvailabilityCase,
  CaseJournalEntry,
  ConsoleFailure,
  ConsoleRequest,
} from '../api/console';
import { QueueProblem } from '../queue/PriorityQueue';
import { causeLabel, laneLabel } from './riskPresentation';
import {
  ACTION_KINDS,
  actionKindLabel,
  dueTone,
  exceptionStateLabel,
  presentCaseState,
} from './casePresentation';

/** What the case panel needs in order to load itself. */
export interface AvailabilityCasesProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The instant the deadlines are judged against. */
  readonly now?: Date;
}

/**
 * Who owns each availability failure, and what they have actually done.
 *
 * The two clocks are shown separately because they are separate obligations.
 * An action recorded on time and an outcome nobody ever verified is a specific
 * failure with a specific owner, and a single combined "overdue" badge would
 * hide exactly that case.
 *
 * There is no acknowledgement control anywhere on this panel. The action stage
 * takes a named action and the reference to the artefact behind it, and a
 * console that offered a button meaning "seen" would be offering something the
 * product refuses to accept.
 */
export function AvailabilityCases({
  context,
  now = new Date(),
}: AvailabilityCasesProps): React.JSX.Element {
  const [cases, setCases] = useState<readonly AvailabilityCase[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  const load = useCallback(() => {
    void fetchAvailabilityCases(context).then((outcome) => {
      if (outcome.ok) {
        setCases(outcome.value);
        setFailure(undefined);
      } else {
        setCases(undefined);
        setFailure(outcome.failure);
      }
    });
  }, [context]);

  useEffect(load, [load]);

  if (failure !== undefined) {
    // A session that has ended is one condition about the whole session rather
    // than about this panel. The session surface reports it once; every panel
    // repeating the same sentence would tell an operator nothing new three
    // times over and bury the one message that is about this panel.
    if (failure.kind === 'unauthenticated') {
      return (
        <section aria-label="Availability cases" data-state="signed-out">
          <h2>Accountable availability work</h2>
        </section>
      );
    }
    return <QueueProblem failure={failure} />;
  }
  if (cases === undefined) {
    return (
      <section aria-label="Availability cases" data-state="loading">
        <h2>Accountable availability work</h2>
        <p>Loading the cases.</p>
      </section>
    );
  }
  if (cases.length === 0) {
    return (
      <section aria-label="Availability cases" data-state="empty">
        <h2>Accountable availability work</h2>
        <p>
          Nothing in your scope currently needs somebody. A WATCH lane stays visible in the queue
          without raising work, so an empty case list is not an empty queue.
        </p>
      </section>
    );
  }

  return (
    <section aria-label="Availability cases" data-state="loaded">
      <h2>Accountable availability work</h2>
      <ol data-testid="availability-cases">
        {cases.map((one) => (
          <CaseRow key={one.id} context={context} governed={one} now={now} onChanged={load} />
        ))}
      </ol>
    </section>
  );
}

/**
 * One case, its two clocks and its history.
 *
 * The reopen count is shown rather than hidden behind the current state. "This
 * is the fourth time this month" is the question a reviewer actually asks, and
 * a row that showed only where the case stands now could not answer it.
 */
function CaseRow({
  context,
  governed,
  now,
  onChanged,
}: {
  readonly context: ConsoleRequest;
  readonly governed: AvailabilityCase;
  readonly now: Date;
  readonly onChanged: () => void;
}): React.JSX.Element {
  const state = presentCaseState(governed.state);
  const [journal, setJournal] = useState<readonly CaseJournalEntry[] | undefined>(undefined);
  const [exceptions, setExceptions] = useState<readonly AcceptedException[] | undefined>(undefined);

  return (
    <li
      data-testid="availability-case"
      data-case-state={governed.state}
      data-case-tone={state.tone}
      data-severity={governed.severity}
    >
      <article>
        <header>
          <h3>{causeLabel(governed.causeCode)}</h3>
          <p data-testid="case-severity">{laneLabel(governed.severity)}</p>
          <p data-testid="case-state">{state.label}</p>
          <p data-testid="case-state-explanation">{state.explanation}</p>
          <p data-testid="case-owner">Owned by {governed.accountableRoleCode}</p>
        </header>
        <dl>
          <dt>Action due</dt>
          <dd data-testid="case-action-due" data-due={dueTone(governed.actionDueAt, now)}>
            {governed.actionDueAt}
          </dd>
          <dt>Outcome due</dt>
          <dd data-testid="case-outcome-due" data-due={dueTone(governed.outcomeDueAt, now)}>
            {governed.outcomeDueAt ?? 'Starts when action is recorded'}
          </dd>
          <dt>Times returned</dt>
          <dd data-testid="case-reopens">{governed.reopenCount}</dd>
          <dt>Escalation level</dt>
          <dd data-testid="case-escalation">{governed.escalationLevel}</dd>
        </dl>

        <ActionForm context={context} caseId={governed.id} onChanged={onChanged} />

        <button
          type="button"
          data-testid="case-load-journal"
          onClick={() => {
            void fetchCaseJournal(context, governed.id).then((outcome) => {
              setJournal(outcome.ok ? outcome.value : []);
            });
            void fetchCaseExceptions(context, governed.id).then((outcome) => {
              setExceptions(outcome.ok ? outcome.value : []);
            });
          }}
        >
          Show history
        </button>
        {journal === undefined ? null : (
          <ol data-testid="case-journal">
            {journal.map((entry) => (
              <li key={entry.sequenceNo} data-event-kind={entry.eventKind}>
                {entry.eventKind}
                {entry.actionKind === null ? '' : ` — ${actionKindLabel(entry.actionKind)}`}
                {entry.verificationOutcome === null ? '' : ` — ${entry.verificationOutcome}`}:{' '}
                {entry.reason}
              </li>
            ))}
          </ol>
        )}
        {exceptions === undefined || exceptions.length === 0 ? null : (
          <ul data-testid="case-exceptions">
            {exceptions.map((accepted) => (
              <li key={accepted.id} data-exception-state={accepted.state}>
                {exceptionStateLabel(accepted.state)} — {accepted.reasonCode}
                {accepted.expiresAt === null ? '' : `, expires ${accepted.expiresAt}`}
                <span data-testid="exception-authority"> ({accepted.requiredAuthority})</span>
              </li>
            ))}
          </ul>
        )}
      </article>
    </li>
  );
}

/**
 * Record accountable structured action.
 *
 * The evidence reference is required by the control itself rather than only by
 * the backend, so the refusal an operator meets is immediate and legible
 * instead of arriving as a validation failure from a server round trip.
 */
function ActionForm({
  context,
  caseId,
  onChanged,
}: {
  readonly context: ConsoleRequest;
  readonly caseId: string;
  readonly onChanged: () => void;
}): React.JSX.Element {
  const [actionKind, setActionKind] = useState<string>(ACTION_KINDS[0]);
  const [evidenceReference, setEvidenceReference] = useState('');
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<string | undefined>(undefined);

  return (
    <form
      data-testid="case-action-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (evidenceReference.trim() === '' || reason.trim() === '') {
          setProblem('An action needs the artefact behind it and a reason.');
          return;
        }
        setProblem(undefined);
        void recordCaseAction(context, caseId, actionKind, evidenceReference, reason).then(
          (outcome) => {
            if (outcome.ok) {
              setEvidenceReference('');
              setReason('');
              onChanged();
            } else {
              setProblem(`The action was refused (${outcome.failure.kind}).`);
            }
          },
        );
      }}
    >
      <label>
        Action taken
        <select
          data-testid="action-kind"
          value={actionKind}
          onChange={(event) => {
            setActionKind(event.target.value);
          }}
        >
          {ACTION_KINDS.map((kind) => (
            <option key={kind} value={kind}>
              {actionKindLabel(kind)}
            </option>
          ))}
        </select>
      </label>
      <label>
        Evidence reference
        <input
          data-testid="action-evidence"
          value={evidenceReference}
          onChange={(event) => {
            setEvidenceReference(event.target.value);
          }}
        />
      </label>
      <label>
        Reason
        <input
          data-testid="action-reason"
          value={reason}
          onChange={(event) => {
            setReason(event.target.value);
          }}
        />
      </label>
      <button type="submit" data-testid="action-submit">
        Record action
      </button>
      {problem === undefined ? null : <p data-testid="action-problem">{problem}</p>}
    </form>
  );
}
