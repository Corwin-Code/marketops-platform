import { useEffect, useState } from 'react';
import { fetchAdvertisingQueue } from '../api/console';
import type { AdvertisingCase } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { EvidenceChip } from './EvidenceChip';
import { presentMeasure } from './evidencePresentation';

/** What the advertising queue needs in order to load itself. */
export interface AdvertisingQueueProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** Called when the operator opens a case. */
  readonly onSelect: (caseId: string) => void;
}

/** The lanes an operator may narrow to, in the order the product ranks them. */
const LANES = ['PROTECTION', 'DATA_REPAIR', 'OPTIMIZATION', 'WATCH'] as const;

/**
 * The advertising work list, in the order the product ranks it.
 *
 * Not re-sorted here. The rank is a non-compensating band score with a
 * published definition — a protection case outranks every optimization case
 * whatever the money involved — and a console that re-ordered by amount would
 * be presenting its own opinion as the product's, in the one place where the
 * ordering is the safety property.
 *
 * Every measure carries its state beside it. An operator scanning the spend
 * column has to be able to tell "nothing was spent" from "nobody knows what was
 * spent", because one of those is a finding and the other is a gap.
 */
export function AdvertisingQueue({ context, onSelect }: AdvertisingQueueProps): React.JSX.Element {
  const [offset, setOffset] = useState(0);
  const [lane, setLane] = useState<string | undefined>(undefined);
  const [cases, setCases] = useState<readonly AdvertisingCase[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchAdvertisingQueue(context, lane, 50, offset).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setCases(outcome.value);
        setFailure(undefined);
      } else {
        setCases(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, lane, offset]);

  if (failure !== undefined) {
    return <AdvertisingProblem failure={failure} />;
  }
  if (cases === undefined) {
    return (
      <section aria-label="Advertising control" data-state="loading">
        <h2>Advertising</h2>
        <p>Loading the advertising work list.</p>
      </section>
    );
  }

  return (
    <section aria-label="Advertising control" data-state="loaded">
      <h2>Advertising</h2>
      <p>
        Ordered by the product&rsquo;s own rank. A protection case outranks every optimization case
        whatever the amounts involved.
      </p>
      <fieldset>
        <legend>Lane</legend>
        <button
          type="button"
          aria-pressed={lane === undefined}
          onClick={() => {
            setLane(undefined);
            setOffset(0);
          }}
        >
          All
        </button>
        {LANES.map((name) => (
          <button
            key={name}
            type="button"
            aria-pressed={lane === name}
            onClick={() => {
              setLane(name);
              setOffset(0);
            }}
          >
            {name}
          </button>
        ))}
      </fieldset>

      <nav aria-label="Advertising queue pages">
        <button
          type="button"
          disabled={offset === 0}
          onClick={() => {
            setOffset(Math.max(0, offset - 50));
          }}
        >
          Previous page
        </button>
        <span> Page {Math.floor(offset / 50) + 1} </span>
        <button
          type="button"
          disabled={cases.length < 50}
          onClick={() => {
            setOffset(offset + 50);
          }}
        >
          Next page
        </button>
      </nav>

      {cases.length === 0 ? (
        <p data-empty="advertising-queue">
          Nothing is waiting in this lane. That is a statement about the queue, not about whether
          the calculation ran.
        </p>
      ) : (
        <table>
          <caption>Advertising cases</caption>
          <thead>
            <tr>
              <th scope="col">Object</th>
              <th scope="col">Lane</th>
              <th scope="col">Cause</th>
              <th scope="col">Owner</th>
              <th scope="col">Spend</th>
              <th scope="col">Profit per ad rouble</th>
              <th scope="col">Evidence</th>
              <th scope="col" />
            </tr>
          </thead>
          <tbody>
            {cases.map((row) => (
              <tr key={row.id} data-case-id={row.id} data-lane={row.lane}>
                <td>
                  {row.nativeObjectName ?? row.adNativeObjectId}
                  <span data-object-kind={row.nativeObjectKind}> ({row.nativeObjectKind})</span>
                </td>
                <td data-protection-tier={row.protectionTier ?? ''}>
                  {row.lane}
                  {row.protectionTier === undefined ? '' : ` ${row.protectionTier}`}
                </td>
                <td>{row.causeCode}</td>
                <td>{row.accountableRoleCode ?? 'not assigned'}</td>
                <td data-measure="spend">
                  {presentMeasure(row.officialSpendState, row.officialSpendAmount, (value) =>
                    `${value.toFixed(2)} ${row.profitCurrencyCode ?? ''}`.trim(),
                  )}
                </td>
                <td data-measure="profit-per-ad-rub">
                  {presentMeasure(row.profitPerAdRubState, row.profitPerAdRubValue, (value) =>
                    value.toFixed(4),
                  )}
                </td>
                <td>
                  <EvidenceChip state={row.evidenceState} of="Evidence" />
                  {row.blockerCodes.length > 0 && (
                    <span data-blockers={row.blockerCodes.join(',')}>
                      {' '}
                      blocked: {row.blockerCodes.join(', ')}
                    </span>
                  )}
                </td>
                <td>
                  <button
                    type="button"
                    onClick={() => {
                      onSelect(row.id);
                    }}
                  >
                    Open
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/** What went wrong, in words an operator can act on. */
export function AdvertisingProblem({
  failure,
}: {
  readonly failure: ConsoleFailure;
}): React.JSX.Element {
  const message = ((): string => {
    switch (failure.kind) {
      case 'unauthenticated':
        return 'Your session has ended. Sign in again to continue.';
      case 'step-up-required':
        return 'This action needs a recent sign-in. Re-authenticate and try again.';
      case 'forbidden':
        return 'Your profile does not have advertising access for any store.';
      case 'unreachable':
        return 'The platform did not answer. Nothing was changed.';
      case 'malformed':
        return 'The platform answered with something this console cannot read.';
      case 'refused':
        return `The platform refused the request (${String(failure.status)}).`;
    }
  })();
  return (
    <section aria-label="Advertising control" data-state="error">
      <h2>Advertising</h2>
      <p role="alert">{message}</p>
    </section>
  );
}
