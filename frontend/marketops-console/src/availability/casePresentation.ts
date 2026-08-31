/**
 * How accountable availability work is allowed to be presented.
 *
 * The distinctions the backend refuses to collapse are refused here too.
 * Recording an action is not verifying an outcome, verifying an outcome is not
 * accepting the risk, and a case that came back is not a new one. A console
 * that showed a single "done" badge would undo in the rendering exactly what
 * the state machine exists to protect.
 */

/** The closed set of actions that can satisfy the first stage. */
export const ACTION_KINDS = [
  'INBOUND_EVIDENCE_BOUND',
  'CHANNEL_RESTORATION_REFERENCE',
  'DATA_OR_MAPPING_REPAIR',
  'POLICY_VERSION_PUBLISHED',
  'QUALITY_DISPOSITION_RECORDED',
  'OWNERSHIP_DECLARATION_PUBLISHED',
] as const;

/** One of the actions the product will accept as action. */
export type ActionKind = (typeof ACTION_KINDS)[number];

/** How a case state must be shown. */
export interface CasePresentation {
  /** Machine-readable marker every renderer puts on the element. */
  readonly tone: 'open' | 'acting' | 'verifying' | 'accepted' | 'succeeded' | 'closed';
  /** Short operator-facing label. */
  readonly label: string;
  /** What the state means, in words an operator can act on. */
  readonly explanation: string;
}

const STATES = new Map<string, CasePresentation>(
  Object.entries({
    OPEN: {
      tone: 'open',
      label: 'Open',
      explanation: 'Raised and waiting for somebody to take it.',
    },
    ASSIGNED: { tone: 'open', label: 'Assigned', explanation: 'Somebody owns it.' },
    IN_PROGRESS: {
      tone: 'open',
      label: 'In progress',
      explanation: 'Somebody is working on it.',
    },
    ACTION_RECORDED: {
      tone: 'acting',
      label: 'Action recorded',
      explanation: 'Structured evidence of action exists. The risk may still be real.',
    },
    VERIFYING: {
      tone: 'verifying',
      label: 'Verifying',
      explanation: 'Waiting for fresh cause-specific evidence that the risk actually improved.',
    },
    VERIFIED_SUCCESS: {
      tone: 'succeeded',
      label: 'Verified',
      explanation: 'Fresh evidence showed the risk improved. The only success state.',
    },
    REOPENED: {
      tone: 'open',
      label: 'Reopened',
      explanation: 'The risk returned, or its evidence expired, on this same case.',
    },
    ESCALATED: {
      tone: 'open',
      label: 'Escalated',
      explanation: 'Raised to a higher authority under policy.',
    },
    REWORK_REQUIRED: {
      tone: 'open',
      label: 'Rework required',
      explanation: 'The action did not work. This needs different work.',
    },
    ACCEPTED_RISK: {
      tone: 'accepted',
      label: 'Accepted risk',
      explanation: 'A governed acceptance is in force. The calculated risk is unchanged.',
    },
    CANCELLED: {
      tone: 'closed',
      label: 'Cancelled',
      explanation: 'Withdrawn without a verified outcome.',
    },
  }),
);

const UNRECOGNISED: CasePresentation = {
  tone: 'open',
  label: 'Unknown',
  explanation: 'This console does not recognise the state the backend reported.',
};

/** The one place a case state becomes something a person reads. */
export function presentCaseState(state: string): CasePresentation {
  return STATES.get(state) ?? UNRECOGNISED;
}

/** The operator-facing name of a structured action. */
export function actionKindLabel(kind: string): string {
  switch (kind) {
    case 'INBOUND_EVIDENCE_BOUND':
      return 'Attested inbound bound to the shortfall';
    case 'CHANNEL_RESTORATION_REFERENCE':
      return 'Channel restoration reference recorded';
    case 'DATA_OR_MAPPING_REPAIR':
      return 'Stock, mapping or ownership defect repaired';
    case 'POLICY_VERSION_PUBLISHED':
      return 'Policy version published';
    case 'QUALITY_DISPOSITION_RECORDED':
      return 'Return or quality disposition recorded';
    case 'OWNERSHIP_DECLARATION_PUBLISHED':
      return 'Ownership declaration published';
    default:
      return kind;
  }
}

/**
 * Whether a deadline has passed, and how close it is.
 *
 * The two clocks are read through this same function but never merged into one
 * badge: an action that is late and an outcome that is late are different
 * failures with different owners, and one combined indicator would name
 * neither.
 */
export function dueTone(dueAt: string | null, now: Date): 'none' | 'overdue' | 'soon' | 'ok' {
  if (dueAt === null) {
    return 'none';
  }
  const due = Date.parse(dueAt);
  if (Number.isNaN(due)) {
    return 'none';
  }
  const remaining = due - now.getTime();
  if (remaining < 0) {
    return 'overdue';
  }
  return remaining < 3_600_000 ? 'soon' : 'ok';
}

/** The operator-facing name of an acceptance state. */
export function exceptionStateLabel(state: string): string {
  switch (state) {
    case 'REQUESTED':
      return 'Requested';
    case 'AUTHORITY_BLOCKED':
      return 'Authority blocked';
    case 'ACTIVE':
      return 'Accepted';
    case 'REJECTED':
      return 'Rejected';
    case 'EXPIRED':
      return 'Expired';
    case 'INVALIDATED':
      return 'Invalidated';
    case 'WITHDRAWN':
      return 'Withdrawn';
    default:
      return state;
  }
}
