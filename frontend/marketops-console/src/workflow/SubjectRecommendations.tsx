import { useEffect, useState } from 'react';
import { fetchRecommendations } from '../api/console';
import type { ConsoleRequest, Recommendation } from '../api/console';

/** Server-filtered subject proposals stay reachable even in a large store queue. */
export function SubjectRecommendations({
  context,
  storeId,
  subjectId,
  onReview,
}: {
  readonly context: ConsoleRequest;
  readonly storeId: string;
  readonly subjectId: string;
  readonly onReview: (recommendation: Recommendation) => void;
}): React.JSX.Element {
  const [items, setItems] = useState<readonly Recommendation[]>();
  const [failure, setFailure] = useState<string>();
  useEffect(() => {
    let active = true;
    void fetchRecommendations(context, storeId, subjectId).then((result) => {
      if (!active) return;
      if (
        result.ok &&
        result.value.every((item) => item.storeId === storeId && item.subjectId === subjectId)
      )
        setItems(result.value);
      else setFailure(result.ok ? 'scope mismatch' : result.failure.kind);
    });
    return () => {
      active = false;
    };
  }, [context, storeId, subjectId]);
  return (
    <section aria-label="Subject recommendations">
      <h3>Recommendations</h3>
      {failure !== undefined && (
        <p role="alert">Recommendations could not be loaded ({failure}).</p>
      )}
      {items?.length === 0 && <p>No open recommendation for this subject.</p>}
      <ul>
        {items?.map((item) => (
          <li key={item.id}>
            {item.actionKind} — {item.state}
            <button
              type="button"
              onClick={() => {
                onReview(item);
              }}
            >
              Review recommendation {item.id}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
