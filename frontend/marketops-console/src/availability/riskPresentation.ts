/**
 * How the availability surface is allowed to present a risk.
 *
 * This is the same rule the confidence module states for numbers, applied to
 * risk: nothing provisional, carried forward, blocked, stale, conflicted or
 * unknown may be rendered the way a confirmed result is. A card that showed a
 * lower-bound guess and a settled fact identically would teach an operator to
 * treat them as the same thing, and the first time that mattered it would cost
 * a real stockout.
 *
 * The mapping lives here rather than in each component so there is exactly one
 * answer. A second component that decided for itself which states are safe
 * would eventually decide differently.
 */

/** How urgent a calculated risk is. */
export type RiskLane = 'HEALTHY' | 'WATCH' | 'HIGH' | 'CRITICAL' | 'REVIEW' | 'UNRESOLVED';

/** What a calculated risk rests on. */
export type RiskEvidenceState =
  | 'CONFIRMED'
  | 'OPERATIONAL'
  | 'PROVISIONAL'
  | 'CARRIED_FORWARD'
  | 'DATA_BLOCKED'
  | 'POLICY_BLOCKED'
  | 'CONFLICTED'
  | 'STALE'
  | 'UNKNOWN';

/** How a risk must be shown. */
export interface RiskPresentation {
  /** Machine-readable marker every renderer puts on the element. */
  readonly tone: 'confirmed' | 'qualified' | 'blocked';
  /** Short operator-facing label, always rendered next to the lane. */
  readonly label: string;
  /** What the state means, in words an operator can act on. */
  readonly explanation: string;
  /** Whether this evidence may be read as an established fact. */
  readonly establishedFact: boolean;
}

const EVIDENCE = new Map<string, RiskPresentation>(
  Object.entries({
    CONFIRMED: {
      tone: 'confirmed',
      label: 'Confirmed',
      explanation: 'Fresh, complete and unconflicted evidence of record.',
      establishedFact: true,
    },
    OPERATIONAL: {
      tone: 'confirmed',
      label: 'Operational',
      explanation: 'Fresh and complete, from the operational rather than settled source.',
      establishedFact: true,
    },
    PROVISIONAL: {
      tone: 'qualified',
      label: 'Provisional',
      explanation:
        'A conservative lower bound already proves the danger. The full picture is not known.',
      establishedFact: false,
    },
    CARRIED_FORWARD: {
      tone: 'qualified',
      label: 'Carried forward',
      explanation:
        'The last eligible answer, carried for a bounded period while observation is lost.',
      establishedFact: false,
    },
    DATA_BLOCKED: {
      tone: 'blocked',
      label: 'Data blocked',
      explanation: 'A fact that decides the answer is missing. No urgency can be claimed.',
      establishedFact: false,
    },
    POLICY_BLOCKED: {
      tone: 'blocked',
      label: 'Policy blocked',
      explanation: 'No valid policy version resolves for the required scope.',
      establishedFact: false,
    },
    CONFLICTED: {
      tone: 'blocked',
      label: 'Conflicted',
      explanation: 'Two attributable sources disagree and neither wins deterministically.',
      establishedFact: false,
    },
    STALE: {
      tone: 'blocked',
      label: 'Stale',
      explanation: 'The evidence exists but is older than its freshness bound.',
      establishedFact: false,
    },
    UNKNOWN: {
      tone: 'blocked',
      label: 'Unknown',
      explanation: 'Nothing attributable was found.',
      establishedFact: false,
    },
  }),
);

/** The answer when a state arrives that this console does not recognise. */
const UNRECOGNISED: RiskPresentation = {
  tone: 'blocked',
  label: 'Unknown',
  explanation: 'Nothing attributable was found.',
  establishedFact: false,
};

const LANES = new Map<string, { readonly label: string; readonly severity: number }>(
  Object.entries({
    HEALTHY: { label: 'Healthy', severity: 0 },
    WATCH: { label: 'Watch', severity: 1 },
    HIGH: { label: 'High', severity: 2 },
    CRITICAL: { label: 'Critical', severity: 3 },
    REVIEW: { label: 'Review', severity: 2 },
    UNRESOLVED: { label: 'Unresolved', severity: 2 },
  }),
);

/** The one place an evidence state becomes something a person reads. */
export function presentEvidence(state: string): RiskPresentation {
  return EVIDENCE.get(state) ?? UNRECOGNISED;
}

/** The operator-facing name of a lane. */
export function laneLabel(lane: string): string {
  return LANES.get(lane)?.label ?? 'Unresolved';
}

/**
 * How severe a lane is.
 *
 * Review and Unresolved rank with High rather than below Watch. Not knowing
 * whether a profitable variant is about to run out deserves attention
 * comparable to knowing that it is.
 */
export function laneSeverity(lane: string): number {
  return LANES.get(lane)?.severity ?? 2;
}

/**
 * Whether a lane may be shown as a positive statement that supply is adequate.
 *
 * Only Healthy may, and only the caller's evidence state decides whether even
 * that is honest.
 */
export function laneIsSafe(lane: string): boolean {
  return lane === 'HEALTHY';
}

/** The operator-facing name of a child risk. */
export function childLabel(
  childKind: string,
  platformCode: string | null,
  fulfillmentModeCode: string | null,
): string {
  if (childKind === 'COMPANY') {
    return 'Company supply';
  }
  const platform = platformCode ?? 'Channel';
  const mode = fulfillmentModeCode === null ? '' : ` · ${modeLabel(fulfillmentModeCode)}`;
  return `${platform}${mode}`;
}

/** The operator-facing name of a fulfillment mode. */
export function modeLabel(code: string): string {
  switch (code) {
    case 'MARKETPLACE_FULFILLED':
      return 'Marketplace fulfilled';
    case 'SELLER_FULFILLED':
      return 'Seller fulfilled';
    default:
      return 'Unknown mode';
  }
}

/** Why somebody is needed, in words rather than a code. */
export function causeLabel(code: string): string {
  switch (code) {
    case 'CHANNEL_OUT_OF_STOCK':
      return 'Channel has nothing available';
    case 'CHANNEL_COVER_SHORT':
      return 'Channel runs out inside its horizon';
    case 'CHANNEL_NOT_SELLABLE':
      return 'Listing cannot be bought';
    case 'COMPANY_SUPPLY_SHORT':
      return 'Company runs out inside lead time and safety';
    case 'COMPANY_INBOUND_LAPSED':
      return 'Inbound the cover depended on has lapsed';
    case 'STOCK_DATA_DEFECT':
      return 'Stock evidence is missing or contradictory';
    case 'OWNERSHIP_UNDECLARED':
      return 'Platform and internal stock are not proven distinct';
    case 'LEAD_TIME_POLICY_MISSING':
      return 'No lead-time and safety policy resolves';
    case 'DEMAND_POLICY_MISSING':
      return 'No demand policy version is in force';
    case 'DEMAND_UNOBSERVABLE':
      return 'Demand cannot be observed';
    case 'PROFIT_DATA_BLOCKED':
      return 'Profit evidence is stale, incomplete or conflicted';
    case 'RETURN_QUALITY_REVIEW':
      return 'Return and quality evidence needs a judgement';
    case 'NONE':
      return 'No action needed';
    default:
      return code;
  }
}
