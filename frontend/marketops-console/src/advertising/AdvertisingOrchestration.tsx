import { useEffect, useState } from 'react';
import { fetchAdvertisingOrchestration } from '../api/console';
import type { ConsoleRequest, ConsoleFailure } from '../api/console';
import { AdvertisingProblem } from './AdvertisingQueue';
import { AdvertisingEvidenceDetails } from './AdvertisingEvidenceDetails';

export function AdvertisingOrchestration({
  context,
}: {
  readonly context: ConsoleRequest;
}): React.JSX.Element {
  const [state, setState] = useState<Readonly<Record<string, unknown>>>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  useEffect(() => {
    let active = true;
    void fetchAdvertisingOrchestration(context).then((result) => {
      if (!active) return;
      if (result.ok) {
        setState(result.value);
        setFailure(undefined);
      } else {
        setFailure(result.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [context]);
  return (
    <section aria-label="Advertising orchestration SLO">
      <h3>Advertising response and reconciliation</h3>
      {failure !== undefined ? (
        <AdvertisingProblem failure={failure} />
      ) : state === undefined ? (
        <p>Response evidence is loading.</p>
      ) : (
        <>
          <p role={state.state === 'INCIDENT' ? 'alert' : 'status'}>
            {String(state.state)} · {String(state.distributionState)}
          </p>
          <AdvertisingEvidenceDetails label="Response and recovery evidence" value={state} />
          <p>
            Source latency and staffed human response are separate clocks. Reconciliation preserves
            earlier violations.
          </p>
        </>
      )}
    </section>
  );
}
