import { useState } from 'react';
import { advertisingControl } from '../api/console';
import type { ConsoleFailure, ConsoleRequest } from '../api/console';
import { AdvertisingProblem } from './AdvertisingQueue';

export function AdvertisingStopControls({
  context,
  objectId,
  allowedActions,
}: {
  readonly context: ConsoleRequest;
  readonly objectId: string;
  readonly allowedActions: readonly string[];
}): React.JSX.Element | null {
  const [reason, setReason] = useState(''),
    [evidence, setEvidence] = useState(''),
    [owner, setOwner] = useState('');
  const [busy, setBusy] = useState(false),
    [failure, setFailure] = useState<ConsoleFailure>(),
    [notice, setNotice] = useState('');
  const commands = [
    {
      code: 'EMERGENCY_ENTITY_HOLD',
      scopeKind: 'ENTITY',
      containmentKind: 'EMERGENCY_ENTITY_HOLD',
      causeClass: 'BUSINESS_HARM',
      label: 'Apply emergency object hold',
    },
    {
      code: 'BUSINESS_STORE_STOP',
      scopeKind: 'PLATFORM_STORE_CAPABILITY',
      containmentKind: 'CAPABILITY_QUARANTINED',
      causeClass: 'BUSINESS_HARM',
      label: 'Stop Store advertising for business harm',
    },
    {
      code: 'TECHNICAL_STORE_STOP',
      scopeKind: 'PLATFORM_STORE_CAPABILITY',
      containmentKind: 'CAPABILITY_QUARANTINED',
      causeClass: 'PROVIDER_OR_READBACK_DEFECT',
      label: 'Stop Store advertising for technical investigation',
    },
  ].filter((item) => allowedActions.includes(item.code));
  if (commands.length === 0) return null;
  return (
    <section aria-label="Scoped advertising stop">
      <h3>Scoped advertising stop</h3>
      {failure !== undefined && <AdvertisingProblem failure={failure} />}
      {notice && <p role="status">{notice}</p>}
      <label>
        Stop reason
        <textarea
          value={reason}
          onChange={(event) => {
            setReason(event.target.value);
          }}
        />
      </label>
      <label>
        Stop evidence reference
        <input
          value={evidence}
          onChange={(event) => {
            setEvidence(event.target.value);
          }}
        />
      </label>
      <label>
        Responsible Operations Lead user ID
        <input
          value={owner}
          onChange={(event) => {
            setOwner(event.target.value);
          }}
        />
      </label>
      {commands.map((command) => (
        <button
          type="button"
          key={command.code}
          disabled={busy || !reason.trim() || !evidence.trim() || !owner.trim()}
          onClick={() => {
            setBusy(true);
            setFailure(undefined);
            void advertisingControl(
              context,
              `containments/objects/${encodeURIComponent(objectId)}/stop`,
              {
                scopeKind: command.scopeKind,
                containmentKind: command.containmentKind,
                causeClass: command.causeClass,
                reviewOwnerUserId: owner,
                reason,
                evidenceReference: evidence,
              },
            ).then((result) => {
              setBusy(false);
              if (result.ok)
                setNotice(
                  'Scoped stop recorded. Review current containment and preserve unresolved execution evidence.',
                );
              else setFailure(result.failure);
            });
          }}
        >
          {command.label}
        </button>
      ))}
    </section>
  );
}

export function AdvertisingRecoveryControls({
  context,
  id,
  allowedActions,
  reload,
}: {
  readonly context: ConsoleRequest;
  readonly id: string;
  readonly allowedActions: readonly string[];
  readonly reload: () => void;
}): React.JSX.Element | null {
  const [evidence, setEvidence] = useState(''),
    [bundle, setBundle] = useState('');
  const [busy, setBusy] = useState(false),
    [failure, setFailure] = useState<ConsoleFailure>();
  if (allowedActions.length === 0) return null;
  return (
    <section aria-label="Independent recovery review">
      {failure !== undefined && <AdvertisingProblem failure={failure} />}
      {allowedActions.some((action) => action.startsWith('ATTEST_')) && (
        <label>
          Canonical recovery evidence reference
          <input
            value={evidence}
            onChange={(event) => {
              setEvidence(event.target.value);
            }}
          />
        </label>
      )}
      {allowedActions.includes('REENABLE') && (
        <label>
          Newly approved replacement bundle ID
          <input
            value={bundle}
            onChange={(event) => {
              setBundle(event.target.value);
            }}
          />
        </label>
      )}
      {allowedActions.map((action) => (
        <button
          type="button"
          key={action}
          disabled={busy || (action === 'REENABLE' ? !bundle.trim() : !evidence.trim())}
          onClick={() => {
            setBusy(true);
            setFailure(undefined);
            void advertisingControl(
              context,
              `containments/${encodeURIComponent(id)}/${action === 'REENABLE' ? 'reenablement' : 'attestations'}`,
              action === 'REENABLE'
                ? { newBundleId: bundle }
                : { condition: action.slice('ATTEST_'.length), evidenceReference: evidence },
            ).then((result) => {
              setBusy(false);
              if (result.ok) reload();
              else setFailure(result.failure);
            });
          }}
        >
          {action.replaceAll('_', ' ')}
        </button>
      ))}
    </section>
  );
}
