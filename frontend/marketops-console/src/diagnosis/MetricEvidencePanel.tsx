import { useEffect, useState } from 'react';
import { fetchMetricInputs, fetchEvidenceSource } from '../api/console';
import type { ConsoleRequest, MetricInputs, EvidenceSource } from '../api/console';

/** One explicit evidence read; typed edges never masquerade as source provenance. */
export function MetricEvidencePanel({
  context,
  subjectId,
  storeId,
  metricValueId,
}: {
  readonly context: ConsoleRequest;
  readonly subjectId: string;
  readonly storeId: string;
  readonly metricValueId: string;
}): React.JSX.Element {
  const [inputs, setInputs] = useState<MetricInputs>();
  const [selected, setSelected] = useState<string>();
  const [source, setSource] = useState<EvidenceSource>();
  const [failure, setFailure] = useState<string>();
  useEffect(() => {
    let active = true;
    void fetchMetricInputs(context, subjectId, storeId, metricValueId).then((result) => {
      if (!active) return;
      if (result.ok) setInputs(result.value);
      else setFailure(result.failure.kind);
    });
    return () => {
      active = false;
    };
  }, [context, subjectId, storeId, metricValueId]);
  useEffect(() => {
    let active = true;
    if (selected !== undefined)
      void fetchEvidenceSource(context, selected).then((result) => {
        if (!active) return;
        if (result.ok) {
          setSource(result.value);
          setFailure(undefined);
        } else {
          setSource(undefined);
          setFailure(result.failure.kind);
        }
      });
    return () => {
      active = false;
    };
  }, [context, selected]);
  return (
    <section aria-label="Metric evidence">
      <h3>Evidence for metric {metricValueId}</h3>
      {failure !== undefined && <p role="alert">Evidence could not be read ({failure}).</p>}
      {inputs !== undefined && (
        <>
          <p>
            {inputs.references.length === 0
              ? 'No source reference is recorded.'
              : 'Recorded input references:'}
          </p>
          <ul>
            {inputs.references.map((ref) => (
              <li key={`${ref.kind}:${ref.id}`}>
                {ref.kind} {ref.id}
                {ref.kind === 'FACT_PROVENANCE' && (
                  <button
                    type="button"
                    onClick={() => {
                      setSource(undefined);
                      setSelected(ref.id);
                    }}
                  >
                    View source {ref.id}
                  </button>
                )}
              </li>
            ))}
          </ul>
          {inputs.truncated && (
            <p role="status">
              Only the first 200 references are shown. Use the asynchronous export for the complete
              snapshot.
            </p>
          )}
        </>
      )}
      {source !== undefined && (
        <dl aria-label="Source provenance">
          <dt>Source kind</dt>
          <dd>{source.sourceKind}</dd>
          <dt>Source time</dt>
          <dd>{source.sourceTime ?? 'Not recorded'}</dd>
          <dt>Ingested at</dt>
          <dd>{source.ingestionTime}</dd>
          <dt>Stored content hash</dt>
          <dd>{source.contentSha256 ?? 'No stored source bytes'}</dd>
        </dl>
      )}
    </section>
  );
}
