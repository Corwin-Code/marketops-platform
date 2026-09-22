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

import type { TagColor } from '../ui/CodeTag';

/** The advertising evidence states the API can send. */
export const EVIDENCE_STATES = [
  'MASKED',
  'UNVERIFIED',
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
  MASKED: {
    tone: 'blocked',
    label: '已遮蔽',
    explanation: '你当前的角色和授权范围无法查看完整影响范围的证据。',
    writeGrade: false,
  },
  UNVERIFIED: {
    tone: 'blocked',
    label: '未验证',
    explanation: '合成或未验证的平台语义不能授权生产写入。',
    writeGrade: false,
  },
  CANONICAL_CONFIRMED: {
    tone: 'confirmed',
    label: '已确认',
    explanation: '平台已报告，且报告周期已结束。',
    writeGrade: true,
  },
  OPERATIONAL: {
    tone: 'operational',
    label: '运营数据',
    explanation: '已记录可用，但数值仍可能被重述。',
    writeGrade: true,
  },
  PROVISIONAL_OR_ESTIMATED: {
    tone: 'estimated',
    label: '估算',
    explanation: '推算而非平台报告，不足以修改真实出价。',
    writeGrade: false,
  },
  STALE: {
    tone: 'stale',
    label: '已过期',
    explanation: '数据早于该决策要求的时效。',
    writeGrade: false,
  },
  INCOMPLETE: {
    tone: 'unknown',
    label: '不完整',
    explanation: '部分时段缺失，合计并非真实合计。',
    writeGrade: false,
  },
  CONFLICTED: {
    tone: 'unknown',
    label: '有冲突',
    explanation: '两个来源不一致，此处不偏向任何一方。',
    writeGrade: false,
  },
  UNKNOWN: {
    tone: 'unknown',
    label: '未知',
    explanation: '没有任何证据能确定这一点。',
    writeGrade: false,
  },
  NOT_AVAILABLE: {
    tone: 'unknown',
    label: '无数值',
    explanation: '没有数值，这不等于零。',
    writeGrade: false,
  },
  DATA_BLOCKED: {
    tone: 'blocked',
    label: '数据受阻',
    explanation: '缺少必需的输入，下游没有计算。',
    writeGrade: false,
  },
  POLICY_BLOCKED: {
    tone: 'blocked',
    label: '策略受阻',
    explanation: '该决策需要的策略尚未发布。',
    writeGrade: false,
  },
  PROFILE_UNRESOLVED: {
    tone: 'blocked',
    label: '语义未确定',
    explanation: '尚未记录该平台的广告语义。',
    writeGrade: false,
  },
  BUNDLE_UNRESOLVED: {
    tone: 'blocked',
    label: '策略包未确定',
    explanation: '没有完整生效的策略包覆盖该决策。',
    writeGrade: false,
  },
};

/** How a value state must be shown, or `undefined` for a state we do not know. */
export function presentEvidence(state: string): EvidencePresentation | undefined {
  return Object.hasOwn(PRESENTATIONS, state) ? PRESENTATIONS[state as EvidenceState] : undefined;
}

/** Tag colour for each evidence tone. */
export const EVIDENCE_TONE_COLORS: Readonly<Record<EvidencePresentation['tone'], TagColor>> = {
  confirmed: 'success',
  operational: 'processing',
  estimated: 'warning',
  stale: 'warning',
  unknown: 'default',
  blocked: 'error',
};

/** The three value states a measure can be in, separately from its evidence. */
export const VALUE_STATES = ['AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED', 'MASKED'] as const;

/** One value state. */
export type ValueState = (typeof VALUE_STATES)[number];

/** How an absent or undefined measure must read. */
const VALUE_LABELS: Record<ValueState, string> = {
  AVAILABLE: '无数值',
  MASKED: '已遮蔽',
  // Deliberately different words. "无法计算" means nobody could compute it;
  // "无意义" means the arithmetic has no answer, as profit per advertising
  // rouble does not when nothing was spent. Rendering both as a dash would tell
  // an operator the same thing about two different situations.
  NOT_AVAILABLE: '无法计算',
  UNDEFINED: '无意义',
};

/**
 * What a measure reads as when there is no number to show, or `undefined` when
 * there is one.
 *
 * Never a zero for an absent value, and never an empty cell. An operator
 * scanning a column has to be able to tell "nothing was spent" from "nobody
 * knows what was spent" at a glance, because one of those is a finding and the
 * other is a gap in the evidence.
 */
export function absentMeasure(state: string, value: string | undefined): string | undefined {
  if (state === 'AVAILABLE' && value !== undefined) {
    return undefined;
  }
  return Object.hasOwn(VALUE_LABELS, state)
    ? VALUE_LABELS[state as ValueState]
    : `未确定（${state || 'UNKNOWN'}）`;
}

/**
 * A measure as it must read, given its state. The value stays an exact decimal
 * string all the way to the formatter.
 */
export function presentMeasure(
  state: string,
  value: string | undefined,
  format: (value: string) => string,
): string {
  const absent = absentMeasure(state, value);
  return absent ?? format(value ?? '');
}
