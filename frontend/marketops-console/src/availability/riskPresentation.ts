import { codeLabel } from '../i18n/labels';
import {
  CAUSE_LABELS,
  DEMAND_WINDOW_LABELS,
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

/**
 * Where a case's child sits, in words: the company, or the marketplace, store
 * and fulfillment mode of a channel.
 */
export function subjectChannelLabel(subject: {
  readonly childKind: string | null;
  readonly platformCode: string | null;
  readonly storeCode: string | null;
  readonly fulfillmentModeCode: string | null;
}): string {
  if (subject.childKind === 'COMPANY') {
    return childLabel('COMPANY', null, null);
  }
  const platform =
    subject.platformCode === null
      ? '渠道'
      : Object.hasOwn(PLATFORM_LABELS, subject.platformCode)
        ? codeLabel(PLATFORM_LABELS, subject.platformCode)
        : subject.platformCode;
  return [
    platform,
    subject.storeCode,
    subject.fulfillmentModeCode === null ? null : modeLabel(subject.fulfillmentModeCode),
  ]
    .filter((part): part is string => part !== null && part !== '')
    .join(' · ');
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

/**
 * The demand selection reason in words, or undefined when the backend wrote one this table does not
 * know (the drawer then shows only the original text). The calculation writes these sentences in
 * English; the patterns follow the demand policy engine word for word.
 */
export function demandReasonText(reason: string): string | undefined {
  const exact: Readonly<Record<string, string>> = {
    'sustained recent acceleration: D7 exceeds D14 beyond the policy ratio':
      '近期持续加速：近 7 天日均高于近 14 天，超过策略比例，取近 7 天。',
    'sustained recent deceleration: D7 falls below D14 beyond the policy ratio':
      '近期持续放缓：近 7 天日均低于近 14 天，超过策略比例，取近 7 天。',
    'window conflict: a large recent step is not sustained across the longer window':
      '窗口冲突：近期的大幅变化没有在更长的窗口中延续，无法选定需求。',
    'carried forward: every recent window is materially censored':
      '沿用上次有效值：近期每个窗口的可观测时间都不足。',
    'carry-forward expired while observation remained censored': '沿用期已过，而窗口仍然观测不足。',
    'every recent window is materially censored and nothing eligible was ever observed':
      '近期每个窗口的可观测时间都不足，且从未有过可用的窗口。',
    'censoring is mixed with another ineligible state; carry-forward is forbidden':
      '观测不足与其他不可用状态并存，不能沿用上次结果。',
    'one day dominates every window; an unexplained outlier needs review':
      '每个窗口都由某一天主导，需要人工复核这个异常值。',
    'every window is below the policy minimum sample': '每个窗口的件数都低于策略的最小样本。',
    'no source answered for any window': '没有任何窗口取到数据。',
    'no active demand-observation policy version is in force': '没有生效的需求策略版本。',
  };
  const known = exact[reason];
  if (known !== undefined) return known;
  const baseline = /^stable baseline: longest eligible window (D7|D14|D30)$/.exec(reason);
  if (baseline !== null) {
    return `需求平稳：取最长的可用窗口（${codeLabel(DEMAND_WINDOW_LABELS, baseline[1])}）。`;
  }
  const warming = /^not observable yet: watching began (\S+), inside every window$/.exec(reason);
  if (warming?.[1] !== undefined) {
    const day = warming[1].slice(0, 10);
    return `需求尚在积累观察：${day} 才开始同时记录库存与可售状态，还没有窗口被观察到足够长的时间；观察满后自动判断。`;
  }
  return undefined;
}
