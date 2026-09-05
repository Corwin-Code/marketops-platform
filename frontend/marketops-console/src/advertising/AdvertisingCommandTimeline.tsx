import { useEffect, useState } from 'react';
import { fetchAdvertisingCommand } from '../api/console';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { AdvertisingProblem } from './AdvertisingQueue';
import { AdvertisingOutcomeHistory } from './AdvertisingOutcomeHistory';
import { AdvertisingCompensation } from './AdvertisingCompensation';
import { AdvertisingTimestamp } from './AdvertisingTimestamp';
import { AdvertisingEvidenceDetails } from './AdvertisingEvidenceDetails';

const display = (value: unknown): string =>
  typeof value === 'string' || typeof value === 'number' ? value.toString() : 'UNRESOLVED';

/** Native submission, configuration observation and business outcomes remain separate. */
export function AdvertisingCommandTimeline({
  context,
  commandId,
  timezone,
}: {
  readonly context: ConsoleRequest;
  readonly commandId: string;
  readonly timezone: string | undefined;
}): React.JSX.Element {
  const [command, setCommand] = useState<Readonly<Record<string, unknown>>>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    void fetchAdvertisingCommand(context, commandId).then((result) => {
      if (!active) return;
      if (result.ok) {
        setCommand(result.value);
        setFailure(undefined);
      } else setFailure(result.failure);
    });
    return () => {
      active = false;
    };
  }, [context, commandId, revision]);
  return (
    <section
      aria-label="Advertising command timeline"
      data-command-id={commandId}
      data-state={command === undefined ? 'loading' : 'loaded'}
    >
      <h4>Advertising bid command</h4>
      <button
        type="button"
        onClick={() => {
          setRevision((value) => value + 1);
        }}
      >
        Refresh command and outcome evidence
      </button>
      {failure !== undefined && <AdvertisingProblem failure={failure} />}
      {command !== undefined && (
        <>
          <p>
            {String(command.state)} · {display(command.priorBidAmount)} →{' '}
            {display(command.targetBidAmount)} {display(command.currencyCode)} (
            {display(command.bidUnitCode)})
          </p>
          <p>
            Provider acceptance, configuration readback and business outcomes are independently
            established.
          </p>
          {typeof command.failureCode === 'string' && <p>Current refusal: {command.failureCode}</p>}
          {typeof command.approvalExpiresAt === 'string' &&
            Date.parse(command.approvalExpiresAt) <= Date.now() && (
              <p>
                Approval has expired. The existing command and observations remain in history; this
                approval cannot authorize another transmission.
              </p>
            )}
          {[
            'UNKNOWN_REQUIRES_READBACK',
            'READBACK_MISMATCH',
            'MANUAL_RESOLUTION',
            'COMPENSATION_FAILED',
          ].includes(String(command.state)) && (
            <p role="alert">
              Configuration is unresolved. Preserve the reservation and reconcile current evidence
              before another intervention.
            </p>
          )}
          <p>
            Approval expiry:{' '}
            <AdvertisingTimestamp
              value={
                typeof command.approvalExpiresAt === 'string'
                  ? command.approvalExpiresAt
                  : undefined
              }
              timezone={timezone}
            />
          </p>
          <AdvertisingEvidenceDetails
            value={command.attempts}
            label="Advertising transmission and status attempts"
          />
          <AdvertisingEvidenceDetails
            value={command.readbacks}
            label="Advertising native configuration readbacks"
          />
          <AdvertisingOutcomeHistory key={revision} context={context} commandId={commandId} />
          <AdvertisingCompensation
            key={`compensation-${String(revision)}`}
            context={context}
            commandId={commandId}
          />
        </>
      )}
    </section>
  );
}
