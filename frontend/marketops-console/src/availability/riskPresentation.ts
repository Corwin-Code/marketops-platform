import { codeLabel } from '../i18n/labels';
import {
  CAUSE_LABELS,
  FULFILLMENT_MODE_LABELS,
  LANE_LABELS,
  PLATFORM_LABELS,
} from '../i18n/zh/availability';

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
      label: '已确认',
      explanation: '证据新鲜、完整且无冲突，可作为定论。',
      establishedFact: true,
    },
    OPERATIONAL: {
      tone: 'confirmed',
      label: '运营数据',
      explanation: '证据新鲜完整，来自运营数据源而非结算数据源。',
      establishedFact: true,
    },
    PROVISIONAL: {
      tone: 'qualified',
      label: '暂定',
      explanation: '保守下限已证明存在风险，但全貌尚不清楚。',
      establishedFact: false,
    },
    CARRIED_FORWARD: {
      tone: 'qualified',
      label: '沿用上次',
      explanation: '观测中断期间，在限定时间内沿用最近一次有效结果。',
      establishedFact: false,
    },
    DATA_BLOCKED: {
      tone: 'blocked',
      label: '数据阻断',
      explanation: '缺少决定结论的关键数据，无法判断紧急程度。',
      establishedFact: false,
    },
    POLICY_BLOCKED: {
      tone: 'blocked',
      label: '策略阻断',
      explanation: '所需范围内没有可用的有效策略版本。',
      establishedFact: false,
    },
    CONFLICTED: {
      tone: 'blocked',
      label: '数据冲突',
      explanation: '两个可追溯的数据源结论不一致，且无法确定以哪个为准。',
      establishedFact: false,
    },
    STALE: {
      tone: 'blocked',
      label: '数据过期',
      explanation: '证据存在，但已超出新鲜度时限。',
      establishedFact: false,
    },
    UNKNOWN: {
      tone: 'blocked',
      label: '未知',
      explanation: '没有找到可追溯的证据。',
      establishedFact: false,
    },
  }),
);

/** The answer when a state arrives that this console does not recognise. */
const UNRECOGNISED: RiskPresentation = {
  tone: 'blocked',
  label: '未知',
  explanation: '没有找到可追溯的证据。',
  establishedFact: false,
};

const LANES = new Map<string, { readonly severity: number }>(
  Object.entries({
    HEALTHY: { severity: 0 },
    WATCH: { severity: 1 },
    HIGH: { severity: 2 },
    CRITICAL: { severity: 3 },
    REVIEW: { severity: 2 },
    UNRESOLVED: { severity: 2 },
  }),
);

/** The one place an evidence state becomes something a person reads. */
export function presentEvidence(state: string): RiskPresentation {
  return EVIDENCE.get(state) ?? UNRECOGNISED;
}

/** The operator-facing name of a lane. */
export function laneLabel(lane: string): string {
  return Object.hasOwn(LANE_LABELS, lane)
    ? codeLabel(LANE_LABELS, lane)
    : codeLabel(LANE_LABELS, 'UNRESOLVED');
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
    return '公司库存';
  }
  const platform =
    platformCode === null
      ? '渠道'
      : Object.hasOwn(PLATFORM_LABELS, platformCode)
        ? codeLabel(PLATFORM_LABELS, platformCode)
        : platformCode;
  const mode = fulfillmentModeCode === null ? '' : ` · ${modeLabel(fulfillmentModeCode)}`;
  return `${platform}${mode}`;
}

/** The operator-facing name of a fulfillment mode. */
export function modeLabel(code: string): string {
  return Object.hasOwn(FULFILLMENT_MODE_LABELS, code)
    ? codeLabel(FULFILLMENT_MODE_LABELS, code)
    : '未知发货模式';
}

/** Why somebody is needed, in words rather than a code. */
export function causeLabel(code: string): string {
  return codeLabel(CAUSE_LABELS, code);
}
