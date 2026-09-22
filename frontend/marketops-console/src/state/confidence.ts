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

import { formatDecimal, formatMoney, isDecimal } from '../format';

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
    label: '已确认',
    explanation: '所有输入都来自已结算的源记录。',
    sufficientForWrite: true,
  },
  CANONICAL_PENDING_SETTLEMENT: {
    tone: 'qualified',
    label: '待结算',
    explanation: '平台尚未完成这些金额的结算，数值仍可能变化。',
    sufficientForWrite: false,
  },
  ESTIMATED_EXPLAINED: {
    tone: 'qualified',
    label: '估算',
    explanation: '包含明确的估算输入，不是实测数值。',
    sufficientForWrite: false,
  },
  STALE: {
    tone: 'qualified',
    label: '数据过期',
    explanation: '最新的输入数据也已超过该数值可依赖的时效。',
    sufficientForWrite: false,
  },
  INCOMPLETE: {
    tone: 'qualified',
    label: '数据不完整',
    explanation: '部分数据缺失，数值可能偏高或偏低。',
    sufficientForWrite: false,
  },
  CONFLICTED: {
    tone: 'qualified',
    label: '数据冲突',
    explanation: '两个来源的数据不一致，尚未确定采用哪一个。',
    sufficientForWrite: false,
  },
  UNKNOWN: {
    tone: 'qualified',
    label: '可信度未知',
    explanation: '没有信息说明该数值的可信程度。',
    sufficientForWrite: false,
  },
};

/** What is shown when there is no number at all. */
const ABSENT: Presentation = {
  tone: 'absent',
  label: '无数值',
  explanation: '没有产生数值。这表示缺失，不是零。',
  sufficientForWrite: false,
};

/**
 * Decide how one value must be presented.
 *
 * An unavailable value is absent regardless of its confidence: a figure that
 * does not exist cannot be confirmed, and showing a dash beside the word
 * "已确认" is exactly the confusion this product exists to prevent.
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
 * a cost of nothing lead to opposite decisions. A figure without a currency is
 * not money, so it is grouped but not padded to cents; a value that is not a
 * decimal at all is shown as received rather than hidden.
 */
export function formatAmount(
  value: string | null | undefined,
  currencyCode: string | null | undefined,
): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  if (!isDecimal(value)) {
    return value;
  }
  return currencyCode === null || currencyCode === undefined || currencyCode.trim() === ''
    ? formatDecimal(value)
    : formatMoney(value, currencyCode);
}

/** Render how old something is in words rather than as a bare number. */
export function formatFreshness(freshnessSeconds: number | null | undefined): string {
  if (freshnessSeconds === null || freshnessSeconds === undefined) {
    return '更新时间未知';
  }
  if (freshnessSeconds < 90) {
    return '刚刚更新';
  }
  const minutes = Math.round(freshnessSeconds / 60);
  if (minutes < 90) {
    return `${String(minutes)} 分钟前更新`;
  }
  const hours = Math.round(minutes / 60);
  return hours < 48 ? `${String(hours)} 小时前更新` : `${String(Math.round(hours / 24))} 天前更新`;
}
