import { App, Form, Input, Select, Typography } from 'antd';
import type { AvailabilityCase, InboundListItem } from '../api/availability';
import { escalateCase, fetchInboundAttestations, recordCaseAction } from '../api/availability';
import type { ConsoleRequest } from '../api/console';
import { formatStoreDate } from '../format';
import { codeLabel } from '../i18n';
import { INBOUND_STATUS_LABELS } from '../i18n/zh/availability';
import { caseActionText as text } from '../i18n/zh/availabilityCases';
import { ActionModal, PickOrType, idRules, useRemote } from '../ui';
import type { PickOption, SubmitOutcome } from '../ui';
import { ACTION_KINDS, actionKindLabel, attestationReference } from './casePresentation';
import { causeLabel } from './riskPresentation';

/** The three things a person may do to a case. */
export type CaseAction = 'RECORD_ACTION' | 'ESCALATE' | 'REQUEST_EXCEPTION';

const INBOUND = 'INBOUND_EVIDENCE_BOUND';
const TEXT_LIMIT = 500;
const EVIDENCE_LIMIT = 512;

interface RecordValues {
  readonly actionKind?: string;
  readonly evidenceReference?: string;
  readonly attestationId?: string;
  readonly reason?: string;
}

/**
 * Record accountable structured action on one case.
 *
 * The action is chosen from the closed set and backed by an artefact: for bound
 * inbound supply the artefact is one of the product's own attestations, picked
 * rather than typed, so the journal points at a real version. There is no way
 * to record "looked at it".
 */
export function RecordActionModal({
  context,
  governed,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  /** The case, or nothing when the dialog is closed. */
  readonly governed: AvailabilityCase | undefined;
  readonly onClose: () => void;
  readonly onDone: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  const variantId = governed?.subject?.productVariantId;
  const attestations = useRemote(
    governed === undefined || variantId === undefined ? undefined : `${governed.id}:${variantId}`,
    () => fetchInboundAttestations(context, { productVariantId: variantId, limit: 50, offset: 0 }),
  );
  const items: readonly InboundListItem[] = attestations.value?.items ?? [];

  const submit = async (values: RecordValues): Promise<SubmitOutcome> => {
    if (governed === undefined) return undefined;
    const kind = values.actionKind ?? '';
    let evidenceReference = (values.evidenceReference ?? '').trim();
    if (kind === INBOUND) {
      const picked = (values.attestationId ?? '').trim();
      const listed = items.find((item) => item.id === picked);
      evidenceReference = attestationReference(picked, listed?.versionNo);
    }
    const outcome = await recordCaseAction(context, governed.id, {
      actionKind: kind,
      evidenceReference,
      reason: (values.reason ?? '').trim(),
    });
    if (!outcome.ok) return outcome.failure;
    void message.success(text.recorded);
    onDone();
    return undefined;
  };

  return (
    <ActionModal<RecordValues>
      open={governed !== undefined}
      onClose={onClose}
      title={text.recordTitle}
      consequence={text.recordConsequence}
      summary={
        governed === undefined ? undefined : (
          <Typography.Text type="secondary">{causeLabel(governed.causeCode)}</Typography.Text>
        )
      }
      okText={text.recordAction}
      initialValues={{ actionKind: defaultActionKind(governed) }}
      onSubmit={submit}
    >
      {(form) => (
        <>
          <Form.Item
            name="actionKind"
            label={text.actionKind}
            rules={[{ required: true, message: text.actionKindRequired }]}
          >
            <Select
              options={ACTION_KINDS.map((kind) => ({ value: kind, label: actionKindLabel(kind) }))}
            />
          </Form.Item>
          <Form.Item noStyle dependencies={['actionKind']}>
            {() =>
              form.getFieldValue('actionKind') === INBOUND ? (
                <Form.Item
                  name="attestationId"
                  label={text.attestation}
                  extra={text.attestationHelp}
                  rules={idRules(text.attestation)}
                >
                  <PickOrType
                    options={attestationOptions(items)}
                    loading={attestations.loading}
                    unavailable={attestations.failed}
                  />
                </Form.Item>
              ) : (
                <Form.Item
                  name="evidenceReference"
                  label={text.evidence}
                  extra={text.evidenceHelp}
                  rules={[{ required: true, whitespace: true, message: text.evidenceRequired }]}
                >
                  <Input maxLength={EVIDENCE_LIMIT} />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item
            name="reason"
            label={text.reason}
            rules={[{ required: true, whitespace: true, message: text.reasonRequired }]}
          >
            <Input.TextArea rows={3} maxLength={TEXT_LIMIT} showCount />
          </Form.Item>
        </>
      )}
    </ActionModal>
  );
}

/** The action a cause is usually repaired by, as a starting choice. */
function defaultActionKind(governed: AvailabilityCase | undefined): string {
  const cause: string = governed?.causeCode ?? '';
  switch (cause) {
    case 'COMPANY_SUPPLY_SHORT':
    case 'COMPANY_INBOUND_LAPSED':
      return INBOUND;
    case 'CHANNEL_OUT_OF_STOCK':
    case 'CHANNEL_COVER_SHORT':
    case 'CHANNEL_NOT_SELLABLE':
      return 'CHANNEL_RESTORATION_REFERENCE';
    case 'LEAD_TIME_POLICY_MISSING':
    case 'DEMAND_POLICY_MISSING':
      return 'POLICY_VERSION_PUBLISHED';
    case 'OWNERSHIP_UNDECLARED':
      return 'OWNERSHIP_DECLARATION_PUBLISHED';
    case 'RETURN_QUALITY_REVIEW':
      return 'QUALITY_DISPOSITION_RECORDED';
    default:
      return 'DATA_OR_MAPPING_REPAIR';
  }
}

/** The product's attestations as picker choices; cancelled ones cannot be bound. */
function attestationOptions(items: readonly InboundListItem[]): PickOption[] {
  return items.map((item) => ({
    value: item.id,
    search: `${item.externalReference} ${item.id}`,
    disabled: item.businessStatus === 'CANCELLED',
    label: `${text.attestationOption(item.externalReference, item.versionNo)} · ${codeLabel(
      INBOUND_STATUS_LABELS,
      item.businessStatus,
    )} · ${String(item.quantity)} 件 · ${formatStoreDate(item.expectedArrivalFrom)}–${formatStoreDate(
      item.expectedArrivalTo,
    )}`,
  }));
}

/**
 * Raise one case to a higher authority.
 *
 * The dialog says exactly what happens — one level up, at most three, state
 * 已升级, lane unchanged, nobody reassigned or notified — and the reason is
 * recorded with the person's name.
 */
export function EscalateModal({
  context,
  governed,
  onClose,
  onDone,
}: {
  readonly context: ConsoleRequest;
  readonly governed: AvailabilityCase | undefined;
  readonly onClose: () => void;
  readonly onDone: () => void;
}): React.JSX.Element {
  const { message } = App.useApp();
  return (
    <ActionModal<{ readonly reason?: string }>
      open={governed !== undefined}
      onClose={onClose}
      title={text.escalateTitle}
      consequence={
        governed === undefined ? undefined : text.escalateConsequence(governed.escalationLevel)
      }
      okText={text.escalate}
      onSubmit={async (values) => {
        if (governed === undefined) return undefined;
        const outcome = await escalateCase(context, governed.id, (values.reason ?? '').trim());
        if (!outcome.ok) return outcome.failure;
        void message.success(text.escalated);
        onDone();
        return undefined;
      }}
    >
      <Form.Item
        name="reason"
        label={text.escalateReason}
        rules={[{ required: true, whitespace: true, message: text.escalateReasonRequired }]}
      >
        <Input.TextArea rows={3} maxLength={TEXT_LIMIT} showCount autoFocus />
      </Form.Item>
    </ActionModal>
  );
}
