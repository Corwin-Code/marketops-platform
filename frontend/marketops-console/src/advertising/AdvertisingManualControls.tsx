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
import type {
  AdvertisingManualIndependentObservation,
  AdvertisingManualPacket,
} from '../api/advertising';
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
  const [observedAt, setObservedAt] = useState('');
  const [source, setSource] = useState<
    AdvertisingManualIndependentObservation['evidenceSource'] | ''
  >('');
  const [completeness, setCompleteness] = useState<
    AdvertisingManualIndependentObservation['completeness'] | ''
  >('');
  const [evidenceReference, setEvidenceReference] = useState('');
  const [directAttested, setDirectAttested] = useState(false);
  const exactFieldPath = packet.packetDetails?.verificationFieldPath;
  const semanticProfileId = packet.packetDetails?.semanticProfileId;
  const nativeObjectKey = packet.packetDetails?.nativeObjectKey;
  const observation: AdvertisingManualIndependentObservation | undefined =
    observed.trim().length > 0 &&
    observedAt.length > 0 &&
    Number.isFinite(Date.parse(observedAt)) &&
    source !== '' &&
    completeness !== '' &&
    evidenceReference.trim().length > 0 &&
    typeof semanticProfileId === 'string' &&
    semanticProfileId.length > 0 &&
    (exactFieldPath === 'targetBid' ||
      exactFieldPath === 'targetBudget' ||
      exactFieldPath === 'targetStatus') &&
    (source !== 'DIRECT_OFFICIAL_CONSOLE' || completeness !== 'COMPLETE' || directAttested)
      ? {
          observedValue: observed.trim(),
          observedAt: new Date(observedAt).toISOString(),
          evidenceSource: source,
          completeness,
          exactNativeObjectId: packet.adNativeObjectId,
          exactFieldPath,
          semanticProfileId,
          evidenceReference: evidenceReference.trim(),
          directObservationAttested: source === 'DIRECT_OFFICIAL_CONSOLE' && directAttested,
        }
      : undefined;
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
      action === 'INDEPENDENT_VERIFY' ? observation : undefined,
    );
    setBusy(false);
    if (result.ok) reload();
    else setFailure(result.failure);
  }
  return (
    <section aria-label="Manual packet actions">
      {failure !== undefined && <AdvertisingProblem failure={failure} />}
      {actions.includes('INDEPENDENT_VERIFY') && (
        <fieldset>
          <legend>Record what you actually observed</legend>
          <p>
            Confirm this object:{' '}
            {typeof nativeObjectKey === 'string' ? nativeObjectKey : packet.adNativeObjectId}.
            Field:{' '}
            {exactFieldPath === 'targetBid'
              ? 'Bid'
              : exactFieldPath === 'targetBudget'
                ? 'Budget'
                : exactFieldPath === 'targetStatus'
                  ? 'Status'
                  : 'Unavailable'}
            .
          </p>
          <p>
            Use the time you saw the configuration. This does not establish when the change was
            applied.
          </p>
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
          <label>
            Observation source
            <select
              value={source}
              onChange={(event) => {
                const value = event.target.value;
                setSource(
                  value === 'DIRECT_OFFICIAL_CONSOLE' || value === 'SCREENSHOT' ? value : '',
                );
                setDirectAttested(false);
              }}
            >
              <option value="">Choose the source</option>
              <option value="DIRECT_OFFICIAL_CONSOLE">Official console, observed directly</option>
              <option value="SCREENSHOT">Screenshot</option>
            </select>
          </label>
          <label>
            Observation completeness
            <select
              value={completeness}
              onChange={(event) => {
                const value = event.target.value;
                setCompleteness(value === 'COMPLETE' || value === 'INCOMPLETE' ? value : '');
              }}
            >
              <option value="">Choose completeness</option>
              <option value="COMPLETE">Complete: exact object, field and value are visible</option>
              <option value="INCOMPLETE">Incomplete</option>
            </select>
          </label>
          <label>
            Time actually observed (your local time)
            <input
              type="datetime-local"
              step="0.001"
              value={observedAt}
              onChange={(event) => {
                setObservedAt(event.target.value);
              }}
            />
          </label>
          <p>If the actual observation time is unknown, this observation cannot be submitted.</p>
          <label>
            Observation evidence reference
            <input
              value={evidenceReference}
              maxLength={512}
              onChange={(event) => {
                setEvidenceReference(event.target.value);
              }}
            />
          </label>
          {source === 'DIRECT_OFFICIAL_CONSOLE' && (
            <label>
              <input
                type="checkbox"
                checked={directAttested}
                onChange={(event) => {
                  setDirectAttested(event.target.checked);
                }}
              />
              I observed this object and field directly in the official console
            </label>
          )}
          {(source === 'SCREENSHOT' || completeness === 'INCOMPLETE') && (
            <p>
              A screenshot or incomplete observation records evidence and does not establish the
              configuration or release a held reservation.
            </p>
          )}
        </fieldset>
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
            (action === 'INDEPENDENT_VERIFY' && observation === undefined) ||
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
      {options !== undefined && options.blockerCodes.length > 0 && (
        <p>Some manual proposals need policy resolution: {options.blockerCodes.join(', ')}.</p>
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
            {option.blockerCodes.length > 0 && (
              <p>This proposal is unavailable: {option.blockerCodes.join(', ')}.</p>
            )}
            {options.allowedActions.includes('SELECT_MANUAL_PROPOSAL') && (
              <button
                type="button"
                disabled={busy || reason.trim().length === 0 || option.blockerCodes.length > 0}
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
