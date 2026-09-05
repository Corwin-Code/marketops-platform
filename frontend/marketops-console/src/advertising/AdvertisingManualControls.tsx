import { useEffect, useState } from 'react';
import {
  actOnAdvertisingManualPacket,
  fetchAdvertisingManualOptions,
  selectAdvertisingManualOption,
} from '../api/console';
import type {
  AdvertisingManualAction,
  AdvertisingManualOptions,
  ConsoleFailure,
  ConsoleRequest,
} from '../api/console';
import type { AdvertisingManualPacket } from '../api/advertising';
import { AdvertisingProblem } from './AdvertisingQueue';

const LABELS: Record<AdvertisingManualAction, string> = {
  ENDORSE: 'Endorse manual packet',
  APPROVE: 'Approve manual packet',
  START: 'Begin approved human execution',
  REPORT: 'Report execution without proof',
  INDEPENDENT_VERIFY: 'Record independent configuration observation',
  OFFICIAL_VERIFY: 'Verify canonical official observation',
  OBSERVE_EARLY_SAFETY: 'Observe canonical early sales safety',
};
export function AdvertisingManualPacketControls({
  context,
  packet,
  reload,
}: {
  readonly context: ConsoleRequest;
  readonly packet: AdvertisingManualPacket;
  readonly reload: () => void;
}): React.JSX.Element | null {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [observed, setObserved] = useState('');
  const [configuration, setConfiguration] = useState('');
  const actions = Object.keys(LABELS).filter((action): action is AdvertisingManualAction =>
    packet.allowedActions.includes(action),
  );
  if (actions.length === 0 || packet.version === undefined) return null;
  async function act(action: AdvertisingManualAction): Promise<void> {
    setBusy(true);
    setFailure(undefined);
    const result = await actOnAdvertisingManualPacket(
      context,
      packet,
      action,
      action === 'OFFICIAL_VERIFY' ? configuration : observed,
    );
    setBusy(false);
    if (result.ok) reload();
    else setFailure(result.failure);
  }
  return (
    <section aria-label="Manual packet actions">
      {failure !== undefined && <AdvertisingProblem failure={failure} />}
      {actions.includes('INDEPENDENT_VERIFY') && (
        <label>
          Independently observed exact native value
          <input
            value={observed}
            maxLength={128}
            onChange={(event) => {
              setObserved(event.target.value);
            }}
          />
        </label>
      )}
      {actions.includes('OFFICIAL_VERIFY') && (
        <label>
          Canonical official configuration observation ID
          <input
            value={configuration}
            onChange={(event) => {
              setConfiguration(event.target.value);
            }}
          />
        </label>
      )}
      {actions.map((action) => (
        <button
          type="button"
          key={action}
          disabled={
            busy ||
            (action === 'INDEPENDENT_VERIFY' && observed.trim().length === 0) ||
            (action === 'OFFICIAL_VERIFY' && configuration.trim().length === 0)
          }
          onClick={() => {
            void act(action);
          }}
        >
          {LABELS[action]}
        </button>
      ))}
    </section>
  );
}

export function AdvertisingManualProposalControls({
  context,
  caseId,
  reload,
}: {
  readonly context: ConsoleRequest;
  readonly caseId: string;
  readonly reload: () => void;
}): React.JSX.Element {
  const [options, setOptions] = useState<AdvertisingManualOptions>();
  const [failure, setFailure] = useState<ConsoleFailure>();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    void fetchAdvertisingManualOptions(context, caseId).then((result) => {
      if (!active) return;
      if (result.ok) {
        setOptions(result.value);
        setFailure(undefined);
      } else setFailure(result.failure);
    });
    return () => {
      active = false;
    };
  }, [context, caseId]);
  return (
    <section aria-label="Governed manual proposals">
      <h4>Owner governed human execution proposals</h4>
      {failure !== undefined && <AdvertisingProblem failure={failure} />}
      {options?.options.length === 0 && (
        <p>No current immutable Owner manual plan and canonical proposal is available.</p>
      )}
      {options?.allowedActions.includes('SELECT_MANUAL_PROPOSAL') === true && (
        <label>
          Manual selection reason
          <textarea
            value={reason}
            maxLength={2000}
            onChange={(event) => {
              setReason(event.target.value);
            }}
          />
        </label>
      )}
      <ul>
        {options?.options.map((option) => (
          <li key={`${option.policyId}-${option.candidateId ?? option.actionKind}`}>
            <p>
              {option.actionKind} · policy revision {option.policyVersion} ·{' '}
              {option.verificationMode}
            </p>
            <p>
              Exact native target:{' '}
              {option.targetBid ?? option.targetBudget ?? option.targetStatus ?? 'UNRESOLVED'}{' '}
              {option.currencyCode} · {option.bidUnitCode}
            </p>
            <p>
              API profile: {option.apiProfileState}. This human workflow creates no API command.
            </p>
            {options.allowedActions.includes('SELECT_MANUAL_PROPOSAL') && (
              <button
                type="button"
                disabled={busy || reason.trim().length === 0}
                onClick={() => {
                  setBusy(true);
                  void selectAdvertisingManualOption(context, caseId, option, reason.trim()).then(
                    (result) => {
                      setBusy(false);
                      if (result.ok) {
                        setReason('');
                        setFailure(undefined);
                        reload();
                      } else setFailure(result.failure);
                    },
                  );
                }}
              >
                Select exact manual proposal
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
