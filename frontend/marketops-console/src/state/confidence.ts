/**
 * How the console is allowed to present a number it is not sure about.
 *
 * This is the rule the whole surface rests on: nothing stale, estimated,
 * incomplete, conflicted or unknown may be rendered the way a confirmed value
 * is. An operator deciding whether to change a real price reads a figure in a
 * fraction of a second, and if the interface does not carry the doubt, the
 * doubt does not reach them.
 *
 * The mapping lives here rather than in each component so there is one answer.
 * A second component that decided for itself which states are safe would
 * eventually decide differently, and the difference would show up as a price
 * change nobody meant to approve.
 */

/** Whether a value was produced at all, and why not when it was not. */
export type ValueState = 'AVAILABLE' | 'NOT_AVAILABLE' | 'UNDEFINED';

/** How much weight a value can carry. */
export type ConfidenceState =
  | 'CANONICAL_CONFIRMED'
  | 'CANONICAL_PENDING_SETTLEMENT'
  | 'ESTIMATED_EXPLAINED'
  | 'STALE'
  | 'INCOMPLETE'
  | 'CONFLICTED'
  | 'UNKNOWN';

/** How a value must be shown. */
export interface Presentation {
  /** Machine-readable marker every renderer puts on the element. */
  readonly tone: 'confirmed' | 'qualified' | 'absent';
  /** Short operator-facing label, always rendered next to the value. */
  readonly label: string;
  /** What the state means, in words an operator can act on. */
  readonly explanation: string;
  /** Whether this value may support a platform write. */
  readonly sufficientForWrite: boolean;
}

/** The one place a confidence state becomes something a person reads. */
const PRESENTATIONS: Readonly<Record<ConfidenceState, Presentation>> = {
  CANONICAL_CONFIRMED: {
    tone: 'confirmed',
    label: 'Confirmed',
    explanation: 'Every input came from a settled source record.',
    sufficientForWrite: true,
  },
  CANONICAL_PENDING_SETTLEMENT: {
    tone: 'qualified',
    label: 'Awaiting settlement',
    explanation: 'The marketplace has not finished settling these amounts, so they can move.',
    sufficientForWrite: false,
  },
  ESTIMATED_EXPLAINED: {
    tone: 'qualified',
    label: 'Estimated',
    explanation: 'An explicit estimate contributed; this is not a measured figure.',
    sufficientForWrite: false,
  },
  STALE: {
    tone: 'qualified',
    label: 'Stale',
    explanation: 'The freshest contributing fact is older than this figure should rely on.',
    sufficientForWrite: false,
  },
  INCOMPLETE: {
    tone: 'qualified',
    label: 'Incomplete',
    explanation: 'Part of the picture is missing, so the figure understates or overstates.',
    sufficientForWrite: false,
  },
  CONFLICTED: {
    tone: 'qualified',
    label: 'Conflicted',
    explanation: 'Two sources disagree and neither has been chosen over the other.',
    sufficientForWrite: false,
  },
  UNKNOWN: {
    tone: 'qualified',
    label: 'Unknown',
    explanation: 'Nothing here says how much weight this figure can carry.',
    sufficientForWrite: false,
  },
};

/** What is shown when there is no number at all. */
const ABSENT: Presentation = {
  tone: 'absent',
  label: 'Not available',
  explanation: 'No value was produced. This is an absence, not a zero.',
  sufficientForWrite: false,
};

/**
 * Decide how one value must be presented.
 *
 * An unavailable value is absent regardless of its confidence: a figure that
 * does not exist cannot be confirmed, and showing a dash beside the word
 * "Confirmed" is exactly the confusion this product exists to prevent.
 */
export function presentationOf(
  valueState: ValueState | undefined,
  confidenceState: ConfidenceState | undefined,
): Presentation {
  if (valueState !== 'AVAILABLE') {
    return ABSENT;
  }
  return confidenceState === undefined ? PRESENTATIONS.UNKNOWN : PRESENTATIONS[confidenceState];
}

/**
 * Render an amount, or the honest absence of one.
 *
 * Absence renders as an em dash rather than as zero, because a missing cost and
 * a cost of nothing lead to opposite decisions.
 */
export function formatAmount(
  value: string | null | undefined,
  currencyCode: string | null | undefined,
): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  return currencyCode === null || currencyCode === undefined ? value : `${value} ${currencyCode}`;
}

/** Render how old something is in words rather than as a bare number. */
export function formatFreshness(freshnessSeconds: number | null | undefined): string {
  if (freshnessSeconds === null || freshnessSeconds === undefined) {
    return 'age unknown';
  }
  if (freshnessSeconds < 90) {
    return 'seconds old';
  }
  const minutes = Math.round(freshnessSeconds / 60);
  if (minutes < 90) {
    return `${String(minutes)} minutes old`;
  }
  const hours = Math.round(minutes / 60);
  return hours < 48 ? `${String(hours)} hours old` : `${String(Math.round(hours / 24))} days old`;
}
