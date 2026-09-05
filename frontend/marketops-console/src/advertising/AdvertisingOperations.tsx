import { AdvertisingOrchestration } from './AdvertisingOrchestration';
import { AdvertisingRecoveryControls } from './AdvertisingContainmentControls';
import { useEffect, useState } from 'react';
import {
  fetchAdvertisingContainments,
  fetchAdvertisingExposure,
  fetchAdvertisingReservations,
} from '../api/console';
import type {
  AdvertisingContainment,
  AdvertisingExposure,
  AdvertisingReservation,
} from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { AdvertisingProblem } from './AdvertisingQueue';

/** What the operations surface needs in order to load itself. */
export interface AdvertisingOperationsProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
}

/** One envelope axis, as this surface renders it. */
interface Axis {
  readonly code: string;
  readonly label: string;
  readonly used: number | undefined;
  readonly limit: number | undefined;
  readonly note: string | undefined;
}

/**
 * What advertising is currently doing, and what is stopping it.
 *
 * Three questions on one page because an operator asks them together: is
 * anything held, is the envelope spent, and what is standing in the way of
 * releasing it.
 *
 * The envelope is shown axis by axis. There is no combined figure and no
 * percentage, because the product does not have one — the write gate checks
 * every axis independently and never adds one axis's slack to another's, so a
 * single number would describe a quantity that does not exist.
 *
 * Nothing on this page authorises anything. Every fact here is re-derived
 * inside the database at the moment a write is attempted, which is why a
 * reading that has gone stale can mislead a person but cannot let a write
 * through.
 */
export function AdvertisingOperations({ context }: AdvertisingOperationsProps): React.JSX.Element {
  const [revision, setRevision] = useState(0);
  const [reservations, setReservations] = useState<readonly AdvertisingReservation[] | undefined>(
    undefined,
  );
  const [exposure, setExposure] = useState<AdvertisingExposure | undefined>(undefined);
  const [containments, setContainments] = useState<readonly AdvertisingContainment[] | undefined>(
    undefined,
  );
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void Promise.all([
      fetchAdvertisingReservations(context),
      fetchAdvertisingExposure(context),
      fetchAdvertisingContainments(context),
    ]).then(([held, envelope, holds]) => {
      if (!active) {
        return;
      }
      // One failure fails the page. A partial view of what is stopping work is
      // worse than none: an operator who saw an empty containment list because
      // that one call failed would conclude nothing was held.
      if (!held.ok) {
        setFailure(held.failure);
        return;
      }
      if (!envelope.ok) {
        setFailure(envelope.failure);
        return;
      }
      if (!holds.ok) {
        setFailure(holds.failure);
        return;
      }
      setReservations(held.value);
      setExposure(envelope.value);
      setContainments(holds.value);
      setFailure(undefined);
    });
    return () => {
      active = false;
    };
  }, [context, revision]);

  if (failure !== undefined) {
    return <AdvertisingProblem failure={failure} />;
  }
  if (exposure === undefined || reservations === undefined || containments === undefined) {
    return (
      <section aria-label="Advertising execution" data-state="loading">
        <h2>Advertising execution</h2>
        <p>Loading what is currently in flight.</p>
      </section>
    );
  }

  return (
    <section aria-label="Advertising execution" data-state="loaded">
      <h2>Advertising execution</h2>
      <AdvertisingOrchestration context={context} />
      <ExposurePanel exposure={exposure} />
      <ContainmentPanel
        containments={containments}
        context={context}
        reload={() => {
          setRevision((value) => value + 1);
        }}
      />
      <ReservationPanel reservations={reservations} />
    </section>
  );
}

/** The aggregate envelope, one axis at a time. */
function ExposurePanel({
  exposure,
}: {
  readonly exposure: AdvertisingExposure;
}): React.JSX.Element {
  if (exposure.status === 'MASKED') {
    return (
      <section aria-label="Exposure envelope" data-state="masked">
        <h3>Exposure envelope</h3>
        <p>
          Masked: organization exposure exceeds your current disclosure scope. A masked amount is
          not zero or spare capacity.
        </p>
      </section>
    );
  }
  if (!exposure.resolved) {
    return (
      <section aria-label="Exposure envelope" data-state="unresolved">
        <h3>Exposure envelope</h3>
        <p role="alert">
          No exposure envelope is in force. No advertising write may be made at all until one is
          written, whatever else is otherwise ready.
        </p>
        <p>
          {exposure.activeInterventions ?? 'Unresolved'} interventions are standing and{' '}
          {exposure.unresolvedTransmittedWrites ?? 'Unresolved'} transmitted writes are unresolved.
        </p>
      </section>
    );
  }
  const headroom = exposure.reservedRecoveryHeadroom;
  const axes: readonly Axis[] = [
    {
      code: 'ACTIVE_INTERVENTIONS',
      label: 'Active interventions',
      used: exposure.activeInterventions,
      limit:
        exposure.maxActiveInterventions === undefined || headroom === undefined
          ? undefined
          : exposure.maxActiveInterventions - headroom,
      note:
        headroom !== undefined && headroom > 0
          ? `${String(headroom)} of ${String(exposure.maxActiveInterventions)} reserved for recovery`
          : undefined,
    },
    {
      code: 'UNRESOLVED_TRANSMITTED_WRITES',
      label: 'Unresolved transmitted writes',
      used: exposure.unresolvedTransmittedWrites,
      limit: exposure.maxUnresolvedTransmittedWrites,
      note: 'Writes whose outcome nobody has established',
    },
    {
      code: 'CUMULATIVE_BID_CHANGE',
      label: 'Cumulative bid movement',
      used: exposure.cumulativeBidChangeAmount,
      limit: exposure.maxCumulativeBidChangeAmount,
      note:
        exposure.cumulativeWindowHours === undefined
          ? undefined
          : `over ${String(exposure.cumulativeWindowHours)} hours, in ${
              exposure.currencyCode ?? 'the envelope currency'
            }`,
    },
  ];
  return (
    <section aria-label="Exposure envelope" data-state="resolved">
      <h3>Exposure envelope</h3>
      <p>
        Version {exposure.policyVersion ?? '—'} at {exposure.scopeKind ?? '—'} scope
        {exposure.status === 'RETIRED' ? '. This envelope is retired and still binding.' : '.'}
      </p>
      <table>
        <caption>
          Each axis against its own limit. The product has no combined figure, so this page does not
          show one.
        </caption>
        <thead>
          <tr>
            <th scope="col">Axis</th>
            <th scope="col">Consumed</th>
            <th scope="col">Limit</th>
            <th scope="col">State</th>
          </tr>
        </thead>
        <tbody>
          {axes.map((axis) => (
            <tr key={axis.code} data-axis={axis.code}>
              <th scope="row">
                {axis.label}
                {axis.note === undefined ? null : <small> — {axis.note}</small>}
              </th>
              <td>{axis.used ?? 'not measured'}</td>
              <td>{axis.limit ?? 'not stated'}</td>
              <td data-exhausted={exposure.exhaustedAxes.includes(axis.code)}>
                {exposure.exhaustedAxes.includes(axis.code) ? 'at limit' : 'within limit'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/** Holds, quarantines and kills, each named rather than reduced to a severity. */
function ContainmentPanel({
  containments,
  context,
  reload,
}: {
  readonly containments: readonly AdvertisingContainment[];
  readonly context: ConsoleRequest;
  readonly reload: () => void;
}): React.JSX.Element {
  if (containments.length === 0) {
    return (
      <section aria-label="Containment" data-state="empty">
        <h3>Containment</h3>
        <p>Nothing is held. Advertising execution is not stopped by a containment.</p>
      </section>
    );
  }
  return (
    <section aria-label="Containment" data-state="loaded">
      <h3>Containment</h3>
      <p>
        Five kinds, and they are not degrees of one thing. What is held depends on which was thrown.
      </p>
      <ul>
        {containments.map((hold) => (
          <li key={hold.id} data-kind={hold.containmentKind} data-state={hold.state}>
            <strong>{hold.containmentKind}</strong> over {hold.scopeKind ?? 'an unnamed scope'}
            {hold.causeClass === undefined ? null : <> — {hold.causeClass}</>}
            <AdvertisingRecoveryControls
              context={context}
              id={hold.id}
              allowedActions={hold.allowedActions}
              reload={reload}
            />
            <p>{hold.reason ?? 'No reason was recorded.'}</p>
            {hold.outstandingConditions.length === 0 ? (
              <p data-ready={hold.readyToLift}>
                {hold.readyToLift
                  ? 'Every reenablement condition is met and both people have signed.'
                  : 'Every condition is met; the endorsement or the approval is still missing.'}
              </p>
            ) : (
              <ul aria-label="Outstanding reenablement conditions">
                {hold.outstandingConditions.map((condition) => (
                  <li key={condition}>{condition}</li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Reservations, and what each one is waiting for before it can release. */
function ReservationPanel({
  reservations,
}: {
  readonly reservations: readonly AdvertisingReservation[];
}): React.JSX.Element {
  if (reservations.length === 0) {
    return (
      <section aria-label="Reservations" data-state="empty">
        <h3>Reservations</h3>
        <p>
          Nothing is reserved. Only a real intervention takes a reservation, so an empty list here
          is compatible with a full queue.
        </p>
      </section>
    );
  }
  return (
    <section aria-label="Reservations" data-state="loaded">
      <h3>Reservations</h3>
      <table>
        <caption>
          Only real interventions appear here. A proposal nobody has acted on holds nothing and
          spends none of the envelope.
        </caption>
        <thead>
          <tr>
            <th scope="col">Intervention</th>
            <th scope="col">Lane</th>
            <th scope="col">Variants</th>
            <th scope="col">Waiting on</th>
          </tr>
        </thead>
        <tbody>
          {reservations.map((held) => (
            <tr key={held.id} data-holding={held.holding}>
              <th scope="row">
                {held.interventionKind}
                {held.direction === undefined ? null : <small> — {held.direction}</small>}
              </th>
              <td>{held.lane}</td>
              <td>{held.productVariantIds.length}</td>
              <td>
                {held.outstandingReleaseConditions.length === 0
                  ? held.holding
                    ? 'nothing — releasable'
                    : (held.releaseReason ?? 'released')
                  : held.outstandingReleaseConditions.join(', ')}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
