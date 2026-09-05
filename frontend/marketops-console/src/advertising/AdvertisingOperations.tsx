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
import { ADVERTISING_EXPOSURE_AXES } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { AdvertisingProblem } from './AdvertisingQueue';

/** What the operations surface needs in order to load itself. */
export interface AdvertisingOperationsProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
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
  const labels = {
    activeInterventions: 'Active interventions',
    associatedOfficialSpend: 'Associated official spend',
    affectedRetainedSalesShare: 'Affected Retained Sales share',
    cumulativeBidChangeMajor: 'Cumulative bid movement',
    unresolvedTransmittedWrites: 'Unresolved transmitted writes',
    reservedRecoveryHeadroom: 'Reserved recovery headroom',
  };
  return (
    <section
      aria-label="Exposure envelope"
      data-state={exposure.resolved ? 'resolved' : 'unresolved'}
    >
      <h3>Exposure envelope</h3>
      <p>Measured at {exposure.measuredAt ?? 'unavailable'}.</p>
      {!exposure.resolved ? (
        <p role="alert">
          No exposure envelope is in force for every required Store. A Store without current
          authority cannot admit a new advertising action.
        </p>
      ) : null}
      {exposure.unresolvedStoreIds.length > 0 ? (
        <p>Unresolved Stores: {exposure.unresolvedStoreIds.join(', ')}</p>
      ) : null}
      {exposure.envelopes.map((envelope) => (
        <div key={envelope.envelopeId} data-envelope={envelope.envelopeId}>
          <p>
            Version {envelope.policyVersion} at {envelope.scopeKind} scope
            {envelope.platformCode === undefined ? '' : ` / ${envelope.platformCode}`}
            {envelope.storeId === undefined ? '' : ` / Store ${envelope.storeId}`}. Measurement
            window: {envelope.measurementWindowHours ?? 'unknown'} hours; Retained cohort:{' '}
            {envelope.retainedWindowDays ?? 'unknown'} days.
          </p>
          <table>
            <caption>Each axis has its own limit. These values describe current usage.</caption>
            <thead>
              <tr>
                <th scope="col">Axis</th>
                <th scope="col">Measured</th>
                <th scope="col">Limit / reserve</th>
                <th scope="col">State</th>
              </tr>
            </thead>
            <tbody>
              {ADVERTISING_EXPOSURE_AXES.map((code) => {
                const axis = envelope.axes[code];
                const headroom = code === 'reservedRecoveryHeadroom';
                const measurement = headroom ? axis.available : axis.usage;
                const limit = headroom ? axis.reserved : axis.limit;
                return (
                  <tr key={code} data-axis={code}>
                    <th scope="row">
                      {labels[code]}
                      {code === 'activeInterventions' ? (
                        <small>
                          {' '}
                          — {envelope.axes.reservedRecoveryHeadroom.reserved ?? 'unknown'} of{' '}
                          {axis.limit ?? 'unknown'} reserved for recovery
                        </small>
                      ) : null}
                      {headroom ? (
                        <small> — remaining intervention capacity / reserved capacity</small>
                      ) : null}
                      {code === 'affectedRetainedSalesShare' ? (
                        <small>
                          {' '}
                          — affected sales {axis.affectedSales ?? 'not measured'} / company sales{' '}
                          {axis.companySales ?? 'not measured'}
                        </small>
                      ) : null}
                      {code === 'associatedOfficialSpend' &&
                      axis.aggregationBasis === 'COMPLETE_INTERSECTING_OFFICIAL_REPORT_AMOUNTS' ? (
                        <small>
                          {' '}
                          — complete intersecting official reports;{' '}
                          {axis.conservativeBoundaryReportCount ?? 'unknown count of'} reports
                          crossed the start of this window and are counted in full
                        </small>
                      ) : null}
                      {axis.unit === undefined ? null : <small> — {axis.unit}</small>}
                      {axis.windowHours === undefined ? null : (
                        <small> over {axis.windowHours} hours</small>
                      )}
                    </th>
                    <td>{measurement ?? 'not measured'}</td>
                    <td>{limit ?? 'not stated'}</td>
                    <td data-state={axis.state}>
                      {axis.state === 'UNKNOWN'
                        ? 'unknown; capacity unproven'
                        : axis.state === 'EXCEEDED'
                          ? 'exceeded'
                          : 'within current limit'}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {envelope.reasons.length > 0 ? (
            <p>Unresolved or exceeded controls: {envelope.reasons.join(', ')}</p>
          ) : null}
        </div>
      ))}
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
