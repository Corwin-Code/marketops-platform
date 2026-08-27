import { useState } from 'react';
import { createCommand, decide, requestImpactPreview } from '../api/console';
import type { ConsoleFailure, ConsoleRequest, ImpactPreview, Recommendation } from '../api/console';
import { formatAmount } from '../state/confidence';

/** What the review needs in order to decide one proposal. */
export interface RecommendationReviewProps {
  /** Where to send the request and who is asking. */
  readonly context: ConsoleRequest;
  /** The proposal being decided. */
  readonly recommendation: Recommendation;
  /** Called after a decision changes the proposal's state. */
  readonly onDecided: (state: string, commandId?: string) => void;
}

/**
 * Where a person decides whether a real price changes.
 *
 * The preview is requested rather than assumed: it runs the same deterministic
 * guardrail the write gate will run, against the same canonical values, so what
 * the reviewer sees is what will be checked. A screen built from a stale
 * projection would let somebody approve a change the gate then refuses, or
 * worse, one it would have refused.
 *
 * Approval is disabled until the preview has been taken and has passed, and
 * every refusal reason is listed in full. Refusing one condition at a time
 * turns an unfixable situation into a week of attempts and hides from the
 * reviewer that the proposal is nowhere near ready.
 *
 * The reason field is required because the decision is the record a real
 * marketplace write is later justified by, and a decision with no stated reason
 * is a decision nobody can review.
 */
export function RecommendationReview({
  context,
  recommendation,
  onDecided,
}: RecommendationReviewProps): React.JSX.Element {
  const [preview, setPreview] = useState<ImpactPreview | undefined>(undefined);
  const [failure, setFailure] = useState<ConsoleFailure | undefined>(undefined);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  const runPreview = async (): Promise<void> => {
    setBusy(true);
    const outcome = await requestImpactPreview(context, recommendation.id);
    setBusy(false);
    if (outcome.ok) {
      setPreview(outcome.value);
      setFailure(undefined);
    } else {
      setPreview(undefined);
      setFailure(outcome.failure);
    }
  };

  const record = async (kind: 'approval' | 'rejection' | 'policy-authorization'): Promise<void> => {
    setBusy(true);
    const outcome = await decide(context, recommendation.id, kind, reason, recommendation.version);
    if (!outcome.ok) {
      setBusy(false);
      setFailure(outcome.failure);
      return;
    }
    if (kind === 'rejection') {
      setBusy(false);
      onDecided(outcome.value.state);
      return;
    }
    // An authorized proposal still needs its command, and creating it runs the
    // guardrail again for execution specifically. Doing it here rather than
    // leaving it to a later screen keeps the person who decided in front of the
    // answer.
    const created = await createCommand(context, recommendation.id, recommendation.version + 1);
    setBusy(false);
    if (created.ok) {
      onDecided(outcome.value.state, created.value.commandId);
    } else {
      setFailure(created.failure);
      onDecided(outcome.value.state);
    }
  };

  const canDecide = preview?.verdict.passed === true && reason.trim().length > 0 && !busy;

  return (
    <section aria-label="Recommendation review" data-recommendation={recommendation.id}>
      <h2>Proposed {recommendation.actionKind.toLowerCase().replace(/_/g, ' ')}</h2>
      <dl>
        <dt>Subject</dt>
        <dd>{recommendation.subjectId}</dd>
        <dt>State</dt>
        <dd data-testid="recommendation-state">{recommendation.state}</dd>
        <dt>Origin</dt>
        <dd>{recommendation.origin}</dd>
        <dt>Risk</dt>
        <dd>{recommendation.riskLabel}</dd>
        <dt>Valid until</dt>
        <dd>{recommendation.validUntil}</dd>
        <dt>Proposed parameters</dt>
        <dd>
          {Object.entries(recommendation.proposedParameters)
            .map(([name, value]) => `${name}=${value}`)
            .join(', ') || 'none stated'}
        </dd>
      </dl>

      <button type="button" onClick={() => void runPreview()} disabled={busy}>
        {busy ? 'Working…' : 'Check what this would do'}
      </button>

      {failure !== undefined && (
        <p role="alert" data-testid="review-failure">
          {describeFailure(failure)}
        </p>
      )}

      {preview !== undefined && (
        <section aria-label="Impact preview" data-passed={preview.verdict.passed}>
          <h3>What this would do</h3>
          <dl>
            <dt>Current price</dt>
            <dd>{formatAmount(preview.currentPrice, preview.currencyCode)}</dd>
            <dt>Proposed price</dt>
            <dd>{formatAmount(preview.proposedPrice, preview.currencyCode)}</dd>
            <dt>Break-even price</dt>
            <dd>{formatAmount(preview.breakEvenPrice, preview.currencyCode)}</dd>
            <dt>Unit profit now</dt>
            <dd>{formatAmount(preview.currentUnitProfit, preview.currencyCode)}</dd>
            <dt>Unit profit after</dt>
            <dd>{formatAmount(preview.projectedUnitProfit, preview.currencyCode)}</dd>
            <dt>Margin after</dt>
            <dd>{formatAmount(preview.projectedMargin, null)}</dd>
          </dl>

          {preview.verdict.passed ? (
            <p role="status" data-testid="guardrail-verdict">
              Guardrails pass under policy version {preview.verdict.policyVersion ?? 'unrecorded'}.
            </p>
          ) : (
            <div data-testid="guardrail-blocked">
              <p role="alert">
                Guardrails refuse this change. Every reason is listed so it can be fixed at once.
              </p>
              <ul>
                {preview.verdict.reasons.map((code) => (
                  <li key={code} data-reason={code}>
                    {code}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
      )}

      <label htmlFor="decision-reason">Why are you deciding this?</label>
      <textarea
        id="decision-reason"
        value={reason}
        onChange={(event) => {
          setReason(event.target.value);
        }}
        required
      />

      <div className="decision-actions">
        <button type="button" onClick={() => void record('approval')} disabled={!canDecide}>
          Approve this change
        </button>
        <button
          type="button"
          onClick={() => void record('policy-authorization')}
          disabled={!canDecide}
        >
          Use a standing authorization
        </button>
        <button
          type="button"
          onClick={() => void record('rejection')}
          disabled={busy || reason.trim().length === 0}
        >
          Reject
        </button>
      </div>
    </section>
  );
}

/** Say what went wrong in terms of what the operator can do about it. */
export function describeFailure(failure: ConsoleFailure): string {
  switch (failure.kind) {
    case 'unauthenticated':
      return 'Your session has ended. Sign in again; nothing was changed.';
    case 'step-up-required':
      return 'Approving a price change needs a recent sign-in. Re-authenticate and try again.';
    case 'forbidden':
      return 'Your profile does not hold the approval action for this store.';
    case 'unreachable':
      return 'The platform did not answer. Nothing was changed.';
    case 'malformed':
      return 'The platform answered with something this console cannot read.';
    case 'refused':
      return `The platform refused this decision (${String(failure.status)}). Nothing was changed.`;
  }
}
