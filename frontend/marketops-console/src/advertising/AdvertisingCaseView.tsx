import { useEffect, useState } from 'react';
import { fetchAdvertisingCase } from '../api/console';
import type { AdvertisingCase } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { AdvertisingProblem } from './AdvertisingQueue';
import { EvidenceChip } from './EvidenceChip';
import { presentMeasure } from './evidencePresentation';

/** What the case view needs in order to load itself. */
export interface AdvertisingCaseViewProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The case being opened. */
  readonly caseId: string;
  /** Called when the operator goes back to the queue. */
  readonly onBack: () => void;
}

/**
 * One advertising case, and everything the decision would rest on.
 *
 * Every measure is shown with its own state rather than as a number that might
 * be missing, and the rank factors are shown with their contributions so an
 * operator can see why this case is where it is in the queue rather than having
 * to trust the position.
 *
 * There is deliberately no approve button here. This Slice builds the evidence
 * and the decision surface; approving a bid change is a step-up action on a
 * separate route, and a console that offered it from a read-only view would be
 * inviting a decision made without the approval path's own checks.
 */
export function AdvertisingCaseView({
  context,
  caseId,
  onBack,
}: AdvertisingCaseViewProps): React.JSX.Element {
  const [detail, setDetail] = useState<AdvertisingCase | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);

  useEffect(() => {
    let active = true;
    void fetchAdvertisingCase(context, caseId).then((outcome) => {
      if (!active) {
        return;
      }
      if (outcome.ok) {
        setDetail(outcome.value);
        setFailure(undefined);
      } else {
        setDetail(undefined);
        setFailure(outcome.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context, caseId]);

  if (failure !== undefined) {
    return <AdvertisingProblem failure={failure} />;
  }
  if (detail === undefined) {
    return (
      <section aria-label="Advertising case" data-state="loading">
        <h2>Advertising case</h2>
        <p>Loading the case.</p>
      </section>
    );
  }

  const currency = detail.profitCurrencyCode ?? '';
  return (
    <section aria-label="Advertising case" data-state="loaded" data-case-id={detail.id}>
      <button type="button" onClick={onBack}>
        Back to the advertising queue
      </button>
      <h2>
        {detail.nativeObjectName ?? detail.adNativeObjectId} ({detail.nativeObjectKind})
      </h2>
      <p>
        {detail.lane}
        {detail.protectionTier === undefined ? '' : ` ${detail.protectionTier}`} —{' '}
        {detail.causeCode}. Owned by {detail.accountableRoleCode ?? 'nobody yet'}.
      </p>

      <EvidenceChip state={detail.evidenceState} of="Evidence" />
      <span data-confidence={detail.confidenceState}> Confidence: {detail.confidenceState}</span>

      {detail.blockerCodes.length > 0 && (
        <section aria-label="Blockers">
          <h3>What is blocking this case</h3>
          <ul>
            {detail.blockerCodes.map((code) => (
              <li key={code} data-blocker={code}>
                {code}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-label="Measures">
        <h3>What the calculation found</h3>
        <dl>
          <dt>Official spend</dt>
          <dd data-measure="spend" data-measure-state={detail.officialSpendState}>
            {presentMeasure(detail.officialSpendState, detail.officialSpendAmount, (value) =>
              `${value.toFixed(2)} ${currency}`.trim(),
            )}
          </dd>
          <dt>Contribution profit</dt>
          <dd data-measure="profit" data-measure-state={detail.contributionProfitState}>
            {presentMeasure(
              detail.contributionProfitState,
              detail.contributionProfitAmount,
              (value) => `${value.toFixed(2)} ${currency}`.trim(),
            )}
          </dd>
          <dt>Profit per advertising rouble</dt>
          <dd data-measure="profit-per-ad-rub" data-measure-state={detail.profitPerAdRubState}>
            {presentMeasure(detail.profitPerAdRubState, detail.profitPerAdRubValue, (value) =>
              value.toFixed(4),
            )}
          </dd>
          <dt>Eligible traffic</dt>
          <dd data-measure="traffic" data-measure-state={detail.eligibleTrafficState}>
            {presentMeasure(detail.eligibleTrafficState, detail.eligibleTrafficCount, (value) =>
              String(Math.trunc(value)),
            )}
          </dd>
          <dt>Max CPC</dt>
          <dd data-measure="max-cpc" data-measure-state={detail.maxCpcState}>
            {presentMeasure(detail.maxCpcState, detail.maxCpcAmount, (value) =>
              `${value.toFixed(4)} ${currency}`.trim(),
            )}
          </dd>
          <dt>Current bid</dt>
          <dd data-measure="current-bid" data-measure-state={detail.currentBidState}>
            {presentMeasure(detail.currentBidState, detail.currentBidAmount, (value) =>
              `${value.toFixed(4)} ${currency}`.trim(),
            )}
          </dd>
        </dl>
      </section>

      <section aria-label="Rank factors">
        <h3>Why it is here in the queue</h3>
        {detail.rankFactors.length === 0 ? (
          <p data-empty="rank-factors">
            No factors were recorded, which happens when no priority policy is in
            force. The case still sorts by its lane.
          </p>
        ) : (
          <table>
            <caption>Rank factors, and what each contributed</caption>
            <thead>
              <tr>
                <th scope="col">Factor</th>
                <th scope="col">Value</th>
                <th scope="col">Weight</th>
                <th scope="col">Contribution</th>
              </tr>
            </thead>
            <tbody>
              {detail.rankFactors.map((factor) => (
                <tr key={factor.code} data-factor={factor.code}>
                  <td>{factor.code}</td>
                  <td>{factor.value === undefined ? (factor.absenceReason ?? 'absent') : factor.value}</td>
                  <td>{factor.weight ?? '—'}</td>
                  <td>{factor.contribution ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </section>
  );
}
