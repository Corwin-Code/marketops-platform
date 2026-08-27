import { useEffect, useState } from 'react';
import { fetchCommand, fetchGate } from '../api/console';
import type { ConsoleFailure, ConsoleRequest, PriceCommand } from '../api/console';
import { formatAmount } from '../state/confidence';

/** What the timeline needs in order to load itself. */
export interface CommandTimelineProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The command being followed. */
  readonly commandId: string;
}

/** States in which the console must not suggest the change has taken effect. */
const UNRESOLVED = new Set([
  'UNKNOWN_REQUIRES_READBACK',
  'READBACK_MISMATCH',
  'MANUAL_RESOLUTION',
  'COMPENSATION_FAILED',
]);

/**
 * What happened to a price change, in the order it happened.
 *
 * Every call and every observation is shown, because the question an operator
 * asks about an unresolved change is never just what state it is in. A command
 * sitting in {@code UNKNOWN_REQUIRES_READBACK} is only actionable next to what
 * was actually called and what the marketplace actually answered.
 *
 * An unresolved command says so in plain words at the top. The most dangerous
 * screen this product could render is one that shows a price change and lets a
 * reader assume it took effect.
 */
export function CommandTimeline({ context, commandId }: CommandTimelineProps): React.JSX.Element {
  const [command, setCommand] = useState<PriceCommand | undefined>(undefined);
  const [gate, setGate] = useState<readonly string[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchCommand(context, commandId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setCommand(outcome.value);
        setFailure(undefined);
      } else {
        setFailure(outcome.failure);
      }
    });
    void fetchGate(context, commandId).then((outcome) => {
      if (active && outcome.ok) {
        setGate(outcome.value.blockingReasons);
      }
    });
    return () => {
      active = false;
    };
  }, [context, commandId]);

  if (failure !== undefined) {
    return (
      <section aria-label="Command timeline" data-state="error">
        <h2>Price change</h2>
        <p role="alert">The command could not be loaded ({failure.kind}).</p>
      </section>
    );
  }
  if (command === undefined) {
    return (
      <section aria-label="Command timeline" data-state="loading">
        <h2>Price change</h2>
        <p role="status">Loading the command…</p>
      </section>
    );
  }

  const unresolved = UNRESOLVED.has(command.state);

  return (
    <section aria-label="Command timeline" data-command={command.id} data-state={command.state}>
      <h2>Price change on {command.platformCode}</h2>

      <p role="status" data-testid="command-state">
        {describeState(command.state)}
      </p>
      {unresolved && (
        <p role="alert" data-testid="command-unresolved">
          This change is not resolved. Do not assume the marketplace holds the new price until a
          readback says so.
        </p>
      )}

      <dl>
        <dt>Price before</dt>
        <dd>{formatAmount(command.priorPrice, command.currencyCode)}</dd>
        <dt>Price intended</dt>
        <dd>{formatAmount(command.targetPrice, command.currencyCode)}</dd>
        <dt>Attempts made</dt>
        <dd>{command.attemptNo}</dd>
        {command.failureCode !== null && (
          <>
            <dt>Failure</dt>
            <dd>{command.failureCode}</dd>
          </>
        )}
      </dl>

      {gate !== undefined && gate.length > 0 && (
        <section aria-label="Write gate" data-testid="gate-closed">
          <h3>Why this cannot leave the system right now</h3>
          <ul>
            {gate.map((reason) => (
              <li key={reason} data-reason={reason}>
                {reason}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-label="Calls made">
        <h3>Calls made</h3>
        {command.attempts.length === 0 ? (
          <p>No call has been made yet.</p>
        ) : (
          <ol>
            {command.attempts.map((attempt) => (
              <li key={attempt.id} data-purpose={attempt.purpose}>
                <strong>{attempt.purpose}</strong> — {attempt.outcomeClass}
                {attempt.nativeStatus !== null && <span> ({attempt.nativeStatus})</span>}
                {attempt.errorCode !== null && <span> — {attempt.errorCode}</span>}
                <time dateTime={attempt.startedAt}> at {attempt.startedAt}</time>
              </li>
            ))}
          </ol>
        )}
      </section>

      <section aria-label="What the marketplace holds">
        <h3>What the marketplace holds</h3>
        {command.readbacks.length === 0 ? (
          <p data-testid="no-readback">
            Nothing has been read back yet, so nothing here says what the marketplace holds.
          </p>
        ) : (
          <ol>
            {command.readbacks.map((readback) => (
              <li key={readback.id} data-match={readback.matchState}>
                {formatAmount(readback.observedPrice, readback.currencyCode)} —{' '}
                {describeMatch(readback.matchState)}
                <time dateTime={readback.observedAt}> at {readback.observedAt}</time>
              </li>
            ))}
          </ol>
        )}
      </section>
    </section>
  );
}

/** Say what a command's state means, rather than showing the code alone. */
export function describeState(state: string): string {
  switch (state) {
    case 'PENDING':
      return 'Waiting for a worker. No call has been made.';
    case 'LEASED':
    case 'EXECUTING':
      return 'A worker is making the call now.';
    case 'PLATFORM_PENDING':
      return 'The marketplace accepted the request and is still working on it.';
    case 'READBACK_PENDING':
      return 'The marketplace has answered; what it now holds is being read back.';
    case 'SUCCEEDED':
      return 'A readback observed the intended price. The change is confirmed.';
    case 'RETRY_WAIT':
      return 'A retriable condition occurred. The call will be tried again.';
    case 'UNKNOWN_REQUIRES_READBACK':
      return 'The result could not be classified. The change is neither confirmed nor ruled out.';
    case 'READBACK_MISMATCH':
      return 'A readback observed a different price from the one intended.';
    case 'MANUAL_RESOLUTION':
      return 'An operator has taken this out of automatic handling.';
    case 'FAILED_FINAL':
      return 'The change was not applied and will not be retried.';
    case 'COMPENSATION_PENDING':
      return 'Restoring the previous price was authorised and is being performed.';
    case 'COMPENSATED':
      return 'The previous price was restored and read back.';
    case 'COMPENSATION_FAILED':
      return 'The restore could not be completed. A person has to resolve this.';
    default:
      return `The command is in an unrecognised state (${state}).`;
  }
}

/** Say what a readback observed, rather than showing the code alone. */
export function describeMatch(matchState: string): string {
  switch (matchState) {
    case 'MATCHES_TARGET':
      return 'this is the price that was intended';
    case 'MATCHES_PRIOR':
      return 'this is the price from before the change';
    case 'DIFFERENT':
      return 'this is neither the intended nor the previous price';
    default:
      return 'the marketplace answer could not be read';
  }
}
