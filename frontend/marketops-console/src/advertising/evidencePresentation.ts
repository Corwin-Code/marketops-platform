/**
 * How advertising evidence is allowed to be shown.
 *
 * Ten states, and the point of the file is that none of them may be rendered as
 * any of the others. An operator deciding whether to lower a bid on a real
 * marketplace needs to know the difference between a number the platform
 * reported, a number this product computed, a number nobody could compute, and
 * a number that is currently under quarantine. A console that showed a single
 * "ok" badge would undo in the rendering exactly what the value states exist to
 * protect.
 *
 * The mapping is total over the API's vocabulary and the compiler checks it. A
 * state the backend adds and the console has not been taught about is a build
 * failure here rather than a silently neutral badge in front of somebody making
 * a spending decision.
 */

/** The advertising evidence states the API can send. */
export const EVIDENCE_STATES = [
  'CANONICAL_CONFIRMED',
  'OPERATIONAL',
  'PROVISIONAL_OR_ESTIMATED',
  'STALE',
  'INCOMPLETE',
  'CONFLICTED',
  'UNKNOWN',
  'NOT_AVAILABLE',
  'DATA_BLOCKED',
  'POLICY_BLOCKED',
  'PROFILE_UNRESOLVED',
  'BUNDLE_UNRESOLVED',
] as const;

/** One state the API can send. */
export type EvidenceState = (typeof EVIDENCE_STATES)[number];

/** How one state must be shown. */
export interface EvidencePresentation {
  /** Machine-readable marker every renderer puts on the element. */
  readonly tone: 'confirmed' | 'operational' | 'estimated' | 'stale' | 'unknown' | 'blocked';
  /** Short operator-facing label. */
  readonly label: string;
  /** What it means for a decision, in words somebody can act on. */
  readonly explanation: string;
  /** Whether a controlled write may consume a value in this state. */
  readonly writeGrade: boolean;
}

/**
 * The total mapping. Every key is required, so adding a state to
 * {@link EVIDENCE_STATES} without describing it will not compile.
 */
const PRESENTATIONS: Record<EvidenceState, EvidencePresentation> = {
  CANONICAL_CONFIRMED: {
    tone: 'confirmed',
    label: 'Confirmed',
    explanation: 'The marketplace reported it and the reporting window has closed.',
    writeGrade: true,
  },
  OPERATIONAL: {
    tone: 'operational',
    label: 'Operational',
    explanation: 'Recorded and usable, but the figure can still be restated.',
    writeGrade: true,
  },
  PROVISIONAL_OR_ESTIMATED: {
    tone: 'estimated',
    label: 'Estimated',
    explanation: 'Derived rather than reported. Not enough to change a real bid.',
    writeGrade: false,
  },
  STALE: {
    tone: 'stale',
    label: 'Stale',
    explanation: 'Older than the freshness this decision requires.',
    writeGrade: false,
  },
  INCOMPLETE: {
    tone: 'unknown',
    label: 'Incomplete',
    explanation: 'Part of the window is missing, so the total is not the total.',
    writeGrade: false,
  },
  CONFLICTED: {
    tone: 'unknown',
    label: 'Conflicted',
    explanation: 'Two sources disagree. Neither is being preferred here.',
    writeGrade: false,
  },
  UNKNOWN: {
    tone: 'unknown',
    label: 'Unknown',
    explanation: 'Nothing established this either way.',
    writeGrade: false,
  },
  NOT_AVAILABLE: {
    tone: 'unknown',
    label: 'Not available',
    explanation: 'No value exists. This is not a zero.',
    writeGrade: false,
  },
  DATA_BLOCKED: {
    tone: 'blocked',
    label: 'Data blocked',
    explanation: 'A required input is missing, so nothing downstream was computed.',
    writeGrade: false,
  },
  POLICY_BLOCKED: {
    tone: 'blocked',
    label: 'Policy blocked',
    explanation: 'A policy this decision needs has not been published.',
    writeGrade: false,
  },
  PROFILE_UNRESOLVED: {
    tone: 'blocked',
    label: 'Profile unresolved',
    explanation: "This platform's advertising semantics are not recorded.",
    writeGrade: false,
  },
  BUNDLE_UNRESOLVED: {
    tone: 'blocked',
    label: 'Bundle unresolved',
    explanation: 'No complete active policy bundle covers this decision.',
    writeGrade: false,
  },
};

/** How a value state must be shown, or `undefined` for a state we do not know. */
export function presentEvidence(state: string): EvidencePresentation | undefined {
  return PRESENTATIONS[state as EvidenceState];
}

/** The three value states a measure can be in, separately from its evidence. */
export const VALUE_STATES = ['AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED'] as const;

/** One value state. */
export type ValueState = (typeof VALUE_STATES)[number];

/** How an absent or undefined measure must read. */
const VALUE_LABELS: Record<ValueState, string> = {
  AVAILABLE: '',
  // Deliberately different words. "Not available" means nobody could compute
  // it; "undefined" means the arithmetic has no answer, as profit per
  // advertising rouble does not when nothing was spent. Rendering both as a
  // dash would tell an operator the same thing about two different situations.
  NOT_AVAILABLE: 'not available',
  UNDEFINED: 'undefined',
};

/**
 * A measure as it must read, given its state.
 *
 * Never a zero for an absent value, and never an empty cell. An operator
 * scanning a column has to be able to tell "nothing was spent" from "nobody
 * knows what was spent" at a glance, because one of those is a finding and the
 * other is a gap in the evidence.
 */
export function presentMeasure(
  state: string,
  value: number | undefined,
  format: (value: number) => string,
): string {
  if (state === 'AVAILABLE' && value !== undefined) {
    return format(value);
  }
  return VALUE_LABELS[state as ValueState];
}
