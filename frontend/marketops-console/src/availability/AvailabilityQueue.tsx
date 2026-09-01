import { useEffect, useState } from 'react';
import { fetchAvailabilityQueue } from '../api/console';
import type {
  AvailabilityCard,
  AvailabilityChild,
  ConsoleFailure,
  ConsoleRequest,
} from '../api/console';
import { QueueProblem } from '../queue/PriorityQueue';
import { causeLabel, childLabel, laneIsSafe, laneLabel, presentEvidence } from './riskPresentation';

/** What the availability queue needs in order to load itself. */
export interface AvailabilityQueueProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** Narrow to one lane, or show every lane. */
  readonly lane?: string;
  /** Called when the operator opens a card. */
  readonly onSelect?: (productVariantId: string) => void;
}

/**
 * What is about to run out, and who owns it.
 *
 * The list is ordered by the backend and is not re-sorted here: the order is a
 * deterministic figure with a published definition, and a console that
 * reordered it would present its own opinion as the product's.
 *
 * Every card shows both of its children rather than a blended state, because
 * they fail differently and are fixed by different people. A channel with an
 * empty shelf and a warehouse that is full is a marketplace problem; a company
 * that is running out is a procurement one, and a single merged badge would
 * send both to the wrong person.
 */
export function AvailabilityQueue({
  context,
  lane,
  onSelect,
}: AvailabilityQueueProps): React.JSX.Element {
  const [cards, setCards] = useState<readonly AvailabilityCard[] | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchAvailabilityQueue(context, lane).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setCards(outcome.value);
        setFailure(undefined);
      } else {
        setCards(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, lane]);

  if (failure !== undefined) {
    // A session that has ended is one condition about the whole session rather
    // than about this panel. The session surface reports it once; every panel
    // repeating the same sentence would tell an operator nothing new three
    // times over and bury the one message that is about this panel.
    if (failure.kind === 'unauthenticated') {
      return (
        <section aria-label="Stockout and availability" data-state="signed-out">
          <h2>Stockout &amp; availability</h2>
        </section>
      );
    }
    return <QueueProblem failure={failure} />;
  }
  if (cards === undefined) {
    return (
      <section aria-label="Stockout and availability" data-state="loading">
        <h2>Stockout &amp; availability</h2>
        <p>Loading the queue.</p>
      </section>
    );
  }
  if (cards.length === 0) {
    return (
      <section aria-label="Stockout and availability" data-state="empty">
        <h2>Stockout &amp; availability</h2>
        <p>
          No variant in your scope currently carries an availability risk. An empty queue is not the
          same as an unmonitored one.
        </p>
      </section>
    );
  }

  return (
    <section aria-label="Stockout and availability" data-state="loaded">
      <h2>Stockout &amp; availability</h2>
      <ol data-testid="availability-queue">
        {cards.map((card) => (
          <VariantCard key={card.id} card={card} {...(onSelect ? { onSelect } : {})} />
        ))}
      </ol>
    </section>
  );
}

/**
 * One grouped Internal Variant card.
 *
 * The parent names the child that produced its lane rather than leaving the
 * reader to infer it. Two children can share the parent's lane, and an operator
 * who opens the wrong one has spent their attention for nothing.
 */
function VariantCard({
  card,
  onSelect,
}: {
  readonly card: AvailabilityCard;
  readonly onSelect?: (productVariantId: string) => void;
}): React.JSX.Element {
  const trigger = card.children.find((child) => child.id === card.triggeringChildId);
  return (
    <li data-testid="availability-card" data-lane={card.lane}>
      <article>
        <header>
          <h3>
            <button
              type="button"
              onClick={() => onSelect?.(card.productVariantId)}
              disabled={onSelect === undefined}
            >
              {card.displayName}
            </button>
          </h3>
          <p data-testid="card-sku">{card.skuCode}</p>
          <p data-testid="card-lane" data-lane={card.lane}>
            {laneLabel(card.lane)}
            {trigger === undefined ? null : (
              <span data-testid="card-trigger">
                {' '}
                — raised by{' '}
                {childLabel(trigger.childKind, trigger.platformCode, trigger.fulfillmentModeCode)}
              </span>
            )}
          </p>
          <p data-testid="card-policy-version">
            Policy set {card.policyVersionDigest.slice(0, 12)}
          </p>
        </header>
        <ul>
          {card.children.map((child) => (
            <ChildRisk key={child.id} child={child} />
          ))}
        </ul>
      </article>
    </li>
  );
}

/**
 * One independently governed child risk.
 *
 * The evidence tone is a separate attribute from the lane so that a provisional
 * critical and a confirmed critical cannot be styled identically by accident.
 * The conservative proof is shown in full when there is one: an operator asked
 * to act on a lower-bound argument is entitled to read the argument.
 */
function ChildRisk({ child }: { readonly child: AvailabilityChild }): React.JSX.Element {
  const evidence = presentEvidence(child.evidenceState);
  return (
    <li
      data-testid="availability-child"
      data-child-kind={child.childKind}
      data-lane={child.lane}
      data-evidence-tone={evidence.tone}
      data-established-fact={String(evidence.establishedFact)}
    >
      <h4>{childLabel(child.childKind, child.platformCode, child.fulfillmentModeCode)}</h4>
      <p data-testid="child-lane">
        {laneLabel(child.lane)} <span data-testid="child-evidence">{evidence.label}</span>
      </p>
      <p data-testid="child-evidence-explanation">{evidence.explanation}</p>
      {laneIsSafe(child.lane) ? null : (
        <p data-testid="child-cause">{causeLabel(child.causeCode)}</p>
      )}
      <dl>
        <dt>Available</dt>
        <dd data-testid="child-available">
          {child.availableUnits === null ? 'Not reported' : String(child.availableUnits)}
        </dd>
        <dt>Observed demand</dt>
        <dd data-testid="child-demand">
          {child.dailyDemandRate === null ? 'Not observable' : `${child.dailyDemandRate} per day`}
        </dd>
        <dt>Days of cover</dt>
        <dd data-testid="child-cover">{child.daysOfCover ?? 'Not projected'}</dd>
        <dt>Coverage horizon</dt>
        <dd data-testid="child-horizon">
          {child.coverageHorizonDays === null
            ? 'No policy resolved'
            : `${String(child.coverageHorizonDays)} days`}
        </dd>
        <dt>Profit lane</dt>
        <dd data-testid="child-profit">{child.profitLane}</dd>
      </dl>
      <p data-testid="child-demand-reason">{child.demandSelectionReason}</p>
      {child.conservativeProofTerms.length === 0 ? null : (
        <details data-testid="child-proof">
          <summary>Why this is already established</summary>
          <ul>
            {child.conservativeProofTerms.map((term) => (
              <li key={term}>{term}</li>
            ))}
          </ul>
        </details>
      )}
      {child.blockerCodes.length === 0 ? null : (
        <ul data-testid="child-blockers">
          {child.blockerCodes.map((code) => (
            <li key={code}>{code}</li>
          ))}
        </ul>
      )}
      {child.rankFactors.length === 0 ? null : (
        <details data-testid="child-factors">
          <summary>Why it is ranked here</summary>
          <ul>
            {child.rankFactors.map((factor) => (
              <li key={factor.factorCode}>
                {factor.factorCode}: {factor.displayNote}
              </li>
            ))}
          </ul>
        </details>
      )}
      {child.demandWindows.length === 0 ? null : (
        <details data-testid="child-windows">
          <summary>Demand windows</summary>
          <ul>
            {child.demandWindows.map((window) => (
              <li key={window.windowCode} data-eligibility={window.eligibility}>
                {window.windowCode}:{' '}
                {window.completedUnits === null
                  ? 'not observed'
                  : `${String(window.completedUnits)} units`}
                {window.censored ? ` — censored (${window.censoringReason ?? 'unknown'})` : ''}
              </li>
            ))}
          </ul>
        </details>
      )}
    </li>
  );
}
