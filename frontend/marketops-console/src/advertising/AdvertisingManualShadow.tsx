import { useEffect, useState } from 'react';
import { fetchAdvertisingManualPackets } from '../api/console';
import type { AdvertisingManualPacket } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { AdvertisingProblem } from './AdvertisingQueue';

/** What the manual shadow surface needs in order to load itself. */
export interface AdvertisingManualShadowProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The advertising object whose manual work is being read. */
  readonly objectId: string;
}

/**
 * Work somebody was asked to do by hand, and what was observed afterwards.
 *
 * A manual packet is a written instruction. It cannot become a command, an
 * outbox row, an attempt or a call to a marketplace — there is no path from
 * this surface to a provider, which is exactly why budget and pause work lives
 * here rather than in the controlled write path.
 *
 * The distinction this page exists to hold open is between somebody reporting
 * they made a change and somebody establishing that the change is there. A
 * self-report is a report; only an official readback, an official export or a
 * second person&rsquo;s independent look proves the configuration, and this
 * surface labels the two differently on purpose.
 */
export function AdvertisingManualShadow({
  context,
  objectId,
}: AdvertisingManualShadowProps): React.JSX.Element {
  const [packets, setPackets] = useState<readonly AdvertisingManualPacket[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchAdvertisingManualPackets(context, objectId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setPackets(outcome.value);
        setFailure(undefined);
      } else {
        setPackets(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, objectId]);

  if (failure !== undefined) {
    return <AdvertisingProblem failure={failure} />;
  }
  if (packets === undefined) {
    return (
      <section aria-label="Manual execution" data-state="loading">
        <h3>Manual execution</h3>
        <p>Loading the manual work issued for this object.</p>
      </section>
    );
  }
  if (packets.length === 0) {
    return (
      <section aria-label="Manual execution" data-state="empty">
        <h3>Manual execution</h3>
        <p>No manual packet has been issued for this object.</p>
      </section>
    );
  }

  return (
    <section aria-label="Manual execution" data-state="loaded">
      <h3>Manual execution</h3>
      <p>
        Instructions for a person. Nothing here reaches a marketplace by itself, and nothing here
        creates a command.
      </p>
      <ul>
        {packets.map((packet) => (
          <li
            key={packet.id}
            data-action={packet.actionKind}
            data-state={packet.state}
            data-proven={packet.configurationProven}
          >
            <h4>{packet.actionKind}</h4>
            <p>{packet.reason ?? 'No reason was recorded.'}</p>
            {packet.intendedState === undefined ? null : <p>Intended: {packet.intendedState}</p>}
            <p data-proven={packet.configurationProven}>
              {packet.configurationProven
                ? 'The resulting configuration has been established.'
                : 'Nothing has established the resulting configuration yet.'}
            </p>
            {packet.verifications.length === 0 ? (
              <p>No observation has been recorded.</p>
            ) : (
              <ul aria-label="Observations">
                {packet.verifications.map((verification) => (
                  <li
                    key={verification.id}
                    data-grade={verification.evidenceGrade}
                    data-proves={verification.provesConfiguration}
                  >
                    {verification.evidenceGrade}
                    {' — '}
                    {verification.provesConfiguration
                      ? 'establishes the configuration'
                      : 'a report, not a proof'}
                    {verification.observedValue === undefined ? null : (
                      <>
                        {' '}
                        ({verification.observedFieldPath ?? 'field'}: {verification.observedValue})
                      </>
                    )}
                    {verification.conflictState === undefined ||
                    verification.conflictState === 'NONE' ? null : (
                      <strong> — {verification.conflictState}</strong>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {packet.blockerCodes.length === 0 ? null : (
              <ul aria-label="Blockers">
                {packet.blockerCodes.map((code) => (
                  <li key={code}>{code}</li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
