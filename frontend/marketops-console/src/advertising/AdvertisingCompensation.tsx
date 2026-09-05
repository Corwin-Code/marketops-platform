import { useEffect, useState } from 'react';
import { actOnAdvertisingCompensation, fetchAdvertisingCompensation } from '../api/console';
import type { ConsoleRequest, ConsoleFailure } from '../api/console';
import { AdvertisingProblem } from './AdvertisingQueue';
export function AdvertisingCompensation({
  context,
  commandId,
}: {
  readonly context: ConsoleRequest;
  readonly commandId: string;
}): React.JSX.Element {
  const [state, setState] = useState<Readonly<Record<string, unknown>>>(),
    [failure, setFailure] = useState<ConsoleFailure>();
  const [bundle, setBundle] = useState(''),
    [busy, setBusy] = useState(false),
    [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    void fetchAdvertisingCompensation(context, commandId).then((result) => {
      if (!active) return;
      if (result.ok) {
        setState(result.value);
        setFailure(undefined);
      } else setFailure(result.failure);
    });
    return () => {
      active = false;
    };
  }, [context, commandId, revision]);
  const actions = Array.isArray(state?.allowedActions)
    ? state.allowedActions.filter((action): action is string => typeof action === 'string')
    : [];
  const bundles = Array.isArray(state?.availableBundleIds)
    ? state.availableBundleIds.filter((id): id is string => typeof id === 'string')
    : [];
  const display = (value: unknown): string =>
    typeof value === 'string' || typeof value === 'number' ? value.toString() : 'UNRESOLVED';
  return (
    <section aria-label="Exact prior bid compensation">
      <h3>Exact prior bid compensation</h3>
      {failure !== undefined && <AdvertisingProblem failure={failure} />}
      {state !== undefined && (
        <>
          <p>
            {display(state.state)} · observed owner bid {display(state.currentOwnerBid)} → exact
            prior bid {display(state.exactPriorBid)} {display(state.currencyCode)} (
            {display(state.bidUnitCode)})
          </p>
          {actions.includes('PREVIEW') && (
            <label>
              Current compensation bundle
              <select
                value={bundle}
                onChange={(event) => {
                  setBundle(event.target.value);
                }}
              >
                <option value="">Choose approved scope</option>
                {bundles.map((id) => (
                  <option key={id} value={id}>
                    {id}
                  </option>
                ))}
              </select>
            </label>
          )}
          {(['PREVIEW', 'ENDORSE', 'APPROVE'] as const)
            .filter((action) => actions.includes(action))
            .map((action) => (
              <button
                type="button"
                key={action}
                disabled={busy || (action === 'PREVIEW' && !bundle)}
                onClick={() => {
                  setBusy(true);
                  void actOnAdvertisingCompensation(
                    context,
                    commandId,
                    typeof state.existingPreviewId === 'string'
                      ? state.existingPreviewId
                      : undefined,
                    action,
                    bundle,
                  ).then((result) => {
                    setBusy(false);
                    if (result.ok) setRevision((value) => value + 1);
                    else setFailure(result.failure);
                  });
                }}
              >
                {action === 'PREVIEW'
                  ? 'Prepare exact prior bid recovery'
                  : action === 'ENDORSE'
                    ? 'Endorse exact recovery'
                    : 'Approve exact recovery'}
              </button>
            ))}
        </>
      )}
    </section>
  );
}
