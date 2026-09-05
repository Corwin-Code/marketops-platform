import { useEffect, useState } from 'react';
import { fetchAdvertisingCase } from '../api/console';
import type { AdvertisingCase } from '../api/advertising';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { AdvertisingTimestamp } from './AdvertisingTimestamp';
import { AdvertisingWorkflow } from './AdvertisingWorkflow';
import { AdvertisingStopControls } from './AdvertisingContainmentControls';
import { AdvertisingManualShadow } from './AdvertisingManualShadow';
import { AdvertisingProblem } from './AdvertisingQueue';
import { EvidenceChip } from './EvidenceChip';
import { AdvertisingEvidenceDetails } from './AdvertisingEvidenceDetails';
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
 */
function nativeLabel(value: unknown, fallback = 'UNRESOLVED'): string {
  return typeof value === 'string' ? value : fallback;
}

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
  const nativeRules = detail.semanticProfile?.nativeRules;
  const nativeCurrency =
    typeof nativeRules === 'object' && nativeRules !== null && 'currencyCode' in nativeRules
      ? nativeLabel(nativeRules.currencyCode)
      : 'UNRESOLVED';
  const nativeUnit = nativeLabel(detail.semanticProfile?.bidUnitCode);
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

      <p>
        Fact cut: <AdvertisingTimestamp value={detail.asOf} timezone={detail.storeTimezone} />
      </p>
      <p data-disclosure={detail.disclosureState}>Disclosure: {detail.disclosureState}</p>
      <section aria-label="Native platform structure">
        <h3>Native platform structure</h3>
        <p>
          {detail.platformCode} · {detail.nativeObjectKind} ·{' '}
          {detail.nativeObjectKey ?? 'Native identity unresolved'}
        </p>
        <p>
          Bidding mode: {detail.biddingMode}. Control granularity: {detail.controlGranularityState}.
        </p>
        <p>
          Semantic profile: {nativeLabel(detail.semanticProfile?.verificationState)} ·{' '}
          {nativeLabel(detail.semanticProfile?.sourceMaturity)}
        </p>
        <p>Synthetic and unverified profiles provide no production write authority.</p>
        <AdvertisingEvidenceDetails
          value={detail.semanticProfile?.nativeRules}
          label="Native denomination, step and readback rules"
        />
        <ul>
          {detail.nativeRelationships.map((relation) => (
            <li key={String(relation.id)}>
              {String(relation.parentKind)} {String(relation.parentObjectId)} →{' '}
              {nativeLabel(relation.childKind, 'LISTING_VARIANT')}{' '}
              {String(relation.childObjectId ?? relation.listingVariantId)} (
              {String(relation.relationshipKind)})
            </li>
          ))}
        </ul>
      </section>
      <section aria-label="Complete affected set">
        <h3>Complete affected set</h3>
        <p>
          {detail.affectedSetResolution} · {detail.affectedSetDigest ?? 'Digest unresolved'}
        </p>
        <p>Structural membership stays visible when economic evidence is masked.</p>
        <ul>
          {detail.affectedProductVariantIds.map((id) => (
            <li key={id}>{id}</li>
          ))}
        </ul>
        {detail.affectedProductVariantIds.length === 0 && (
          <p>No complete ProductVariant membership is established.</p>
        )}
      </section>
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
          <dt>Current native bid</dt>
          <dd data-measure="current-bid" data-measure-state={detail.currentBidState}>
            {presentMeasure(
              detail.currentBidState,
              detail.currentBidAmount,
              (value) => `${value.toFixed(4)} ${nativeCurrency} (${nativeUnit})`,
            )}
          </dd>
        </dl>
      </section>

      <section aria-label="Rank factors">
        <h3>Why it is here in the queue</h3>
        {detail.rankFactors.length === 0 ? (
          <p data-empty="rank-factors">
            Factors are masked or unresolved. Canonical ordering is retained; no missing factor is a
            zero.
          </p>
        ) : (
          <table>
            <caption>Canonical lexicographic rank factors</caption>
            <thead>
              <tr>
                <th scope="col">Factor</th>
                <th scope="col">Value</th>
                <th scope="col">Evidence note</th>
              </tr>
            </thead>
            <tbody>
              {detail.rankFactors.map((factor) => (
                <tr key={factor.code} data-factor={factor.code}>
                  <td>{factor.code}</td>
                  <td>{factor.value ?? 'unresolved'}</td>
                  <td>{factor.absenceReason ?? 'Recorded'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {/*
        The manual work for this object, kept beside the evidence rather than on
        a page of its own. Budget and pause changes live entirely here and reach
        no marketplace, and an operator reading the case is the person who needs
        to know whether somebody has already done one by hand.
      */}
      <AdvertisingManualShadow
        context={context}
        objectId={detail.adNativeObjectId}
        caseId={detail.id}
      />
      <AdvertisingStopControls
        context={context}
        objectId={detail.adNativeObjectId}
        allowedActions={detail.allowedControlActions}
      />
      <AdvertisingWorkflow context={context} caseId={detail.id} timezone={detail.storeTimezone} />
    </section>
  );
}
