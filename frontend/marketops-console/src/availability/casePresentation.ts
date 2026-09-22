import { codeLabel } from '../i18n/labels';
import { ACTION_KIND_LABELS, EXCEPTION_STATE_LABELS } from '../i18n/zh/availability';

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
    OPEN: { tone: 'open', label: '待处理', explanation: '已创建，等待有人认领。' },
    ASSIGNED: { tone: 'open', label: '已分派', explanation: '已有负责人。' },
    IN_PROGRESS: { tone: 'open', label: '处理中', explanation: '负责人正在处理。' },
    ACTION_RECORDED: {
      tone: 'acting',
      label: '已记录行动',
      explanation: '已有结构化的行动证据，但风险可能仍然存在。',
    },
    VERIFYING: {
      tone: 'verifying',
      label: '验证中',
      explanation: '等待与原因对应的新证据，确认风险确实改善。',
    },
    VERIFIED_SUCCESS: {
      tone: 'succeeded',
      label: '已验证解决',
      explanation: '新证据显示风险已改善，这是唯一的成功状态。',
    },
    REOPENED: {
      tone: 'open',
      label: '已重开',
      explanation: '风险再次出现或证据过期，在同一工单上重开。',
    },
    ESCALATED: { tone: 'open', label: '已升级', explanation: '已按策略升级给更高权限。' },
    REWORK_REQUIRED: {
      tone: 'open',
      label: '需返工',
      explanation: '已采取的行动无效，需要换一种处理方式。',
    },
    ACCEPTED_RISK: {
      tone: 'accepted',
      label: '已接受风险',
      explanation: '一项受管控的风险接受正在生效，计算出的风险本身不变。',
    },
    CANCELLED: { tone: 'closed', label: '已取消', explanation: '未经验证结果即已撤销。' },
  }),
);

const UNRECOGNISED: CasePresentation = {
  tone: 'open',
  label: '未知',
  explanation: '控制台无法识别后端返回的状态。',
};

/** The one place a case state becomes something a person reads. */
export function presentCaseState(state: string): CasePresentation {
  return STATES.get(state) ?? UNRECOGNISED;
}

/** The operator-facing name of a structured action. */
export function actionKindLabel(kind: string): string {
  return codeLabel(ACTION_KIND_LABELS, kind);
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
  return codeLabel(EXCEPTION_STATE_LABELS, state);
}
