import { useState } from 'react';
import {
  amendInboundAttestation,
  cancelInboundAttestation,
  createInboundAttestation,
  fetchInboundAttestation,
  publishLeadTimePolicy,
  retireAvailabilityPolicy,
  reverifyInboundAttestation,
} from '../api/console';
import type { ConsoleRequest, InboundAttestation } from '../api/console';

export interface AvailabilityAuthorityPanelProps {
  readonly context: ConsoleRequest;
}

const INBOUND_STATUSES = [
  'DRAFT',
  'REQUESTED',
  'SUPPLIER_CONFIRMED',
  'IN_TRANSIT',
  'RECEIVED',
  'OVERDUE',
  'CONFLICTED',
  'UNKNOWN',
] as const;

/** Governed procurement intake; this surface never writes provider stock. */
export function AvailabilityAuthorityPanel({
  context,
}: AvailabilityAuthorityPanelProps): React.JSX.Element {
  return (
    <section aria-label="Availability authority">
      <h2>Supply authority</h2>
      <p>
        Record attributable inbound evidence and effective-dated lead-time policy. Every accepted
        change creates recalculation work; none of these controls calls a marketplace provider.
      </p>
      <InboundAuthority context={context} />
      <LeadTimeAuthority context={context} />
    </section>
  );
}

function InboundAuthority({ context }: AvailabilityAuthorityPanelProps): React.JSX.Element {
  const [productVariantId, setProductVariantId] = useState('');
  const [externalReference, setExternalReference] = useState('');
  const [attestationId, setAttestationId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [arrivalFrom, setArrivalFrom] = useState('');
  const [arrivalTo, setArrivalTo] = useState('');
  const [status, setStatus] = useState<(typeof INBOUND_STATUSES)[number]>('REQUESTED');
  const [evidence, setEvidence] = useState('');
  const [reason, setReason] = useState('');
  const [current, setCurrent] = useState<InboundAttestation | undefined>(undefined);
  const [message, setMessage] = useState<string | undefined>(undefined);

  const draft = () => ({
    productVariantId,
    externalReference,
    quantity: Number(quantity),
    expectedArrivalFrom: iso(arrivalFrom),
    expectedArrivalTo: iso(arrivalTo),
    businessStatus: status,
    evidenceReference: evidence,
    sourceTime: new Date().toISOString(),
    reason,
  });

  return (
    <fieldset data-testid="inbound-authority">
      <legend>Inbound attestation</legend>
      <label>
        Product variant ID
        <input
          data-testid="inbound-variant"
          value={productVariantId}
          onChange={(event) => {
            setProductVariantId(event.target.value);
          }}
        />
      </label>
      <label>
        External order or shipment reference
        <input
          data-testid="inbound-external-reference"
          value={externalReference}
          onChange={(event) => {
            setExternalReference(event.target.value);
          }}
        />
      </label>
      <label>
        Quantity
        <input
          data-testid="inbound-quantity"
          type="number"
          min="1"
          value={quantity}
          onChange={(event) => {
            setQuantity(event.target.value);
          }}
        />
      </label>
      <label>
        Arrival window starts (UTC)
        <input
          data-testid="inbound-arrival-from"
          type="datetime-local"
          value={arrivalFrom}
          onChange={(event) => {
            setArrivalFrom(event.target.value);
          }}
        />
      </label>
      <label>
        Arrival window ends (UTC)
        <input
          data-testid="inbound-arrival-to"
          type="datetime-local"
          value={arrivalTo}
          onChange={(event) => {
            setArrivalTo(event.target.value);
          }}
        />
      </label>
      <label>
        Business status
        <select
          data-testid="inbound-status"
          value={status}
          onChange={(event) => {
            setStatus(event.target.value as (typeof INBOUND_STATUSES)[number]);
          }}
        >
          {INBOUND_STATUSES.map((one) => (
            <option key={one} value={one}>
              {one}
            </option>
          ))}
        </select>
      </label>
      <label>
        Evidence reference
        <input
          data-testid="inbound-evidence"
          value={evidence}
          onChange={(event) => {
            setEvidence(event.target.value);
          }}
        />
      </label>
      <label>
        Reason
        <input
          data-testid="inbound-reason"
          value={reason}
          onChange={(event) => {
            setReason(event.target.value);
          }}
        />
      </label>
      <button
        type="button"
        data-testid="inbound-create"
        onClick={() => {
          if (!complete(productVariantId, externalReference, arrivalFrom, arrivalTo, evidence)) {
            setMessage('Product, external reference, arrival window and evidence are required.');
            return;
          }
          void createInboundAttestation(context, draft()).then((outcome) => {
            if (outcome.ok) {
              setCurrent(outcome.value);
              setAttestationId(outcome.value.id);
              setMessage(`Inbound version ${String(outcome.value.versionNo)} accepted.`);
            } else {
              setMessage(`Inbound was refused (${outcome.failure.kind}).`);
            }
          });
        }}
      >
        Create attestation
      </button>

      <label>
        Existing attestation ID
        <input
          data-testid="inbound-id"
          value={attestationId}
          onChange={(event) => {
            setAttestationId(event.target.value);
          }}
        />
      </label>
      <button
        type="button"
        data-testid="inbound-load"
        onClick={() => {
          void fetchInboundAttestation(context, attestationId).then((outcome) => {
            if (!outcome.ok) {
              setMessage(`Attestation could not be loaded (${outcome.failure.kind}).`);
              return;
            }
            const loaded = outcome.value;
            setCurrent(loaded);
            setProductVariantId(loaded.productVariantId);
            setExternalReference(loaded.externalReference);
            setQuantity(String(loaded.quantity));
            setArrivalFrom(localTime(loaded.expectedArrivalFrom));
            setArrivalTo(localTime(loaded.expectedArrivalTo));
            setStatus(loaded.businessStatus as (typeof INBOUND_STATUSES)[number]);
            setEvidence(loaded.evidenceReference);
            setMessage(`Loaded inbound version ${String(loaded.versionNo)}.`);
          });
        }}
      >
        Load current version
      </button>

      {current === undefined ? null : (
        <div data-testid="inbound-current">
          <p>Current version: {current.versionNo}</p>
          <button
            type="button"
            data-testid="inbound-amend"
            onClick={() => {
              const body = draft();
              void amendInboundAttestation(context, current.id, {
                expectedVersion: current.versionNo,
                quantity: body.quantity,
                expectedArrivalFrom: body.expectedArrivalFrom,
                expectedArrivalTo: body.expectedArrivalTo,
                businessStatus: body.businessStatus,
                evidenceReference: body.evidenceReference,
                sourceTime: body.sourceTime,
                reason: body.reason,
              }).then((outcome) => {
                settleInbound(outcome, setCurrent, setMessage);
              });
            }}
          >
            Amend with these values
          </button>
          <button
            type="button"
            data-testid="inbound-reverify"
            onClick={() => {
              void reverifyInboundAttestation(
                context,
                current.id,
                current.versionNo,
                evidence,
                reason,
              ).then((outcome) => {
                settleInbound(outcome, setCurrent, setMessage);
              });
            }}
          >
            Reverify evidence
          </button>
          <button
            type="button"
            data-testid="inbound-cancel"
            onClick={() => {
              void cancelInboundAttestation(
                context,
                current.id,
                current.versionNo,
                evidence,
                reason,
              ).then((outcome) => {
                settleInbound(outcome, setCurrent, setMessage);
              });
            }}
          >
            Cancel attestation
          </button>
        </div>
      )}
      {message === undefined ? null : <p data-testid="inbound-message">{message}</p>}
    </fieldset>
  );
}

function LeadTimeAuthority({ context }: AvailabilityAuthorityPanelProps): React.JSX.Element {
  const [leadMin, setLeadMin] = useState('0');
  const [leadMax, setLeadMax] = useState('14');
  const [safety, setSafety] = useState('7');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [reason, setReason] = useState('');
  const [evidence, setEvidence] = useState('');
  const [supersedes, setSupersedes] = useState('');
  const [retireId, setRetireId] = useState('');
  const [message, setMessage] = useState<string | undefined>(undefined);

  return (
    <fieldset data-testid="lead-time-authority">
      <legend>Organization lead-time and safety policy</legend>
      <label>
        Minimum lead days
        <input
          type="number"
          min="0"
          value={leadMin}
          onChange={(event) => {
            setLeadMin(event.target.value);
          }}
        />
      </label>
      <label>
        Maximum lead days
        <input
          type="number"
          min="0"
          value={leadMax}
          onChange={(event) => {
            setLeadMax(event.target.value);
          }}
        />
      </label>
      <label>
        Safety days
        <input
          type="number"
          min="0"
          value={safety}
          onChange={(event) => {
            setSafety(event.target.value);
          }}
        />
      </label>
      <label>
        Effective from (UTC)
        <input
          data-testid="lead-effective-from"
          type="datetime-local"
          value={effectiveFrom}
          onChange={(event) => {
            setEffectiveFrom(event.target.value);
          }}
        />
      </label>
      <label>
        Evidence reference
        <input
          data-testid="lead-evidence"
          value={evidence}
          onChange={(event) => {
            setEvidence(event.target.value);
          }}
        />
      </label>
      <label>
        Reason
        <input
          data-testid="lead-reason"
          value={reason}
          onChange={(event) => {
            setReason(event.target.value);
          }}
        />
      </label>
      <label>
        Policy ID being superseded (optional)
        <input
          value={supersedes}
          onChange={(event) => {
            setSupersedes(event.target.value);
          }}
        />
      </label>
      <button
        type="button"
        data-testid="lead-publish"
        onClick={() => {
          if (!complete(effectiveFrom, evidence, reason)) {
            setMessage('Effective time, evidence and reason are required.');
            return;
          }
          const effective = iso(effectiveFrom);
          void publishLeadTimePolicy(context, {
            scopeKind: 'ORGANIZATION',
            productVariantId: null,
            supplierCode: null,
            routeCode: null,
            categoryCode: null,
            leadTimeDaysMin: Number(leadMin),
            leadTimeDaysMax: Number(leadMax),
            safetyDays: Number(safety),
            reason,
            evidenceReference: evidence,
            lastReviewedAt: new Date().toISOString(),
            effectiveFrom: effective,
            effectiveTo: null,
            fallbackOfId: null,
            supersedesPolicyId: supersedes === '' ? null : supersedes,
          }).then((outcome) => {
            if (outcome.ok) {
              setRetireId(outcome.value.id);
              setMessage(`Lead-time policy version ${String(outcome.value.version)} published.`);
            } else {
              setMessage(`Policy was refused (${outcome.failure.kind}).`);
            }
          });
        }}
      >
        Publish policy
      </button>
      <label>
        Policy ID to retire
        <input
          data-testid="policy-retire-id"
          value={retireId}
          onChange={(event) => {
            setRetireId(event.target.value);
          }}
        />
      </label>
      <button
        type="button"
        data-testid="policy-retire"
        onClick={() => {
          void retireAvailabilityPolicy(context, 'LEAD_TIME', retireId, reason, evidence).then(
            (outcome) => {
              setMessage(
                outcome.ok
                  ? `Policy ${outcome.value.status.toLowerCase()}.`
                  : `Policy retirement was refused (${outcome.failure.kind}).`,
              );
            },
          );
        }}
      >
        Retire policy
      </button>
      {message === undefined ? null : <p data-testid="policy-message">{message}</p>}
    </fieldset>
  );
}

function settleInbound(
  outcome: Awaited<ReturnType<typeof cancelInboundAttestation>>,
  setCurrent: (value: InboundAttestation) => void,
  setMessage: (value: string) => void,
): void {
  if (outcome.ok) {
    setCurrent(outcome.value);
    setMessage(`Inbound version ${String(outcome.value.versionNo)} accepted.`);
  } else {
    setMessage(`Inbound change was refused (${outcome.failure.kind}).`);
  }
}

function complete(...values: readonly string[]): boolean {
  return values.every((value) => value.trim() !== '');
}

function iso(value: string): string {
  return new Date(`${value}:00Z`).toISOString();
}

function localTime(value: string): string {
  return new Date(value).toISOString().slice(0, 16);
}
