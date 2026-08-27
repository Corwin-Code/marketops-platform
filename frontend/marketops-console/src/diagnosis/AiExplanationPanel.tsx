import { useEffect, useRef, useState } from 'react';
import { requestExplanation } from '../api/console';
import type { AiExplanation, ConsoleFailure, ConsoleRequest } from '../api/console';

/** Separates advisory model statements from canonical values and approval authority. */
export function AiExplanationPanel({
  context,
  subjectId,
  storeId,
}: {
  readonly context: ConsoleRequest;
  readonly subjectId: string;
  readonly storeId: string;
}): React.JSX.Element {
  const [output, setOutput] = useState<AiExplanation | undefined>();
  const [failure, setFailure] = useState<ConsoleFailure | undefined>();
  const [pending, setPending] = useState(false);
  const generation = useRef(0);
  useEffect(() => {
    generation.current++;
    setOutput(undefined);
    setFailure(undefined);
    setPending(false);
    return () => {
      generation.current++;
    };
  }, [context, subjectId, storeId]);

  async function explain(): Promise<void> {
    const requestGeneration = ++generation.current;
    setPending(true);
    setOutput(undefined);
    setFailure(undefined);
    const result = await requestExplanation(context, subjectId, storeId);
    if (requestGeneration !== generation.current) return;
    setPending(false);
    if (result.ok && result.value.subjectId === subjectId) setOutput(result.value);
    else setFailure(result.ok ? { kind: 'malformed', detail: 'subject mismatch' } : result.failure);
  }

  return (
    <section aria-label="AI explanation">
      <h3>Model-assisted explanation</h3>
      <p>Advisory only. Model claims do not approve a change or create a platform command.</p>
      <button
        type="button"
        disabled={pending}
        onClick={() => {
          void explain();
        }}
      >
        {pending ? 'Waiting for explanation…' : 'Request explanation'}
      </button>
      {failure !== undefined && (
        <p role="alert">
          The explanation could not be loaded ({failure.kind}). The request may have been recorded;
          it was not automatically retried.
        </p>
      )}
      {output !== undefined && (
        <div data-state={output.state}>
          <p>
            Invocation {output.invocationId} · schema {output.outputSchemaVersion}
          </p>
          {output.state === 'PARTIAL_OUTPUT_REJECTED' ? (
            <p role="alert">
              Partial explanation: some model claims were rejected. Accepted claims remain advisory.
            </p>
          ) : output.degraded ? (
            <p role="alert">
              Explanation unavailable: {output.failureCode ?? output.state}. Canonical values remain
              available above.
            </p>
          ) : output.state !== 'SUCCEEDED' ? (
            <p role="status">The recorded invocation is still pending. No result is assumed.</p>
          ) : (
            <p>All displayed claims passed output validation. They remain model statements.</p>
          )}
          {(['FACT', 'INFERENCE', 'RECOMMENDATION', 'UNKNOWN'] as const).map((kind) => {
            const accepted = output.claims.filter((claim) => claim.kind === kind && claim.accepted);
            return accepted.length === 0 ? null : (
              <section aria-label={`Model ${kind.toLowerCase()} claims`} key={kind}>
                <h4>{kind}</h4>
                <ul>
                  {accepted.map((claim) => (
                    <li key={claim.claimId}>
                      <p>{claim.statement}</p>
                      {claim.confidenceLabel !== null && (
                        <p>Model confidence: {claim.confidenceLabel}</p>
                      )}
                      {Object.keys(claim.payload).length > 0 && (
                        <pre>{JSON.stringify(claim.payload, null, 2)}</pre>
                      )}
                      <p>
                        Evidence:{' '}
                        {[...claim.metricValueRefs, ...claim.findingRefs].join(', ') ||
                          'none cited'}
                      </p>
                    </li>
                  ))}
                </ul>
              </section>
            );
          })}
          {output.claims.some((claim) => !claim.accepted) && (
            <details>
              <summary>
                Rejected model claims ({output.claims.filter((claim) => !claim.accepted).length})
              </summary>
              <ul>
                {output.claims
                  .filter((claim) => !claim.accepted)
                  .map((claim) => (
                    <li key={claim.claimId}>
                      {claim.kind} · {claim.rejectionCode}: {claim.statement}
                    </li>
                  ))}
              </ul>
            </details>
          )}
        </div>
      )}
    </section>
  );
}
