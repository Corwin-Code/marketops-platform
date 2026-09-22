import type { TagColor } from '../../ui/CodeTag';
import type { CodeLabels } from '../labels';
import { ERROR_CODE_LABELS } from './errorCodes';

/**
 * Chinese text for the advertising screens.
 *
 * Every backend code family the advertising pages render has a label map here,
 * named after the column or field it comes from. The vocabularies are taken from
 * the database check constraints and the backend enums, so a label names what
 * the backend means rather than what the code happens to spell.
 */

type Colors = Readonly<Record<string, TagColor>>;

/* ------------------------------------------------------------------ case */

/** Work lanes, in the order the product ranks them. */
export const LANE_LABELS: CodeLabels = {
  PROTECTION: '止损保护',
  DATA_REPAIR: '数据修复',
  OPTIMIZATION: '优化提效',
  WATCH: '观察',
};
export const LANE_COLORS: Colors = {
  PROTECTION: 'error',
  DATA_REPAIR: 'warning',
  OPTIMIZATION: 'processing',
  WATCH: 'default',
};

export const PROTECTION_TIER_LABELS: CodeLabels = {
  P0: 'P0 最高',
  P1: 'P1 高',
  P2: 'P2 中',
  P3: 'P3 低',
};
export const PROTECTION_TIER_COLORS: Colors = {
  P0: 'error',
  P1: 'warning',
  P2: 'processing',
  P3: 'default',
};

/** Why a case exists. */
export const CAUSE_LABELS: CodeLabels = {
  PROVEN_ADVERTISING_LOSS: '已证实的广告亏损',
  PROMOTED_VARIANT_NOT_SELLABLE: '推广商品不可售',
  PROMOTED_VARIANT_UNAVAILABLE: '推广商品缺货',
  CRITICAL_SALES_UNIT_AT_RISK: '关键销售单元有风险',
  ACTION_OUTCOME_REGRESSION: '操作后效果回退',
  OFFICIAL_AD_FACT_DEFECT: '官方广告数据缺陷',
  AFFECTED_SET_UNRESOLVED: '影响范围未确定',
  AD_LINKAGE_COVERAGE_INSUFFICIENT: '广告关联覆盖不足',
  ATTRIBUTION_GAP_MATERIAL: '归因缺口较大',
  PROFIT_ECONOMICS_BLOCKED: '利润核算受阻',
  DECISION_POLICY_UNRESOLVED: '决策策略未确定',
  NATIVE_SEMANTICS_UNKNOWN: '平台原生语义未知',
  OBJECT_NOT_INDEPENDENTLY_CONTROLLABLE: '对象无法单独控制',
  RECOVERABLE_ADVERTISING_PROFIT: '可挽回的广告利润',
  IMMATURE_SIGNAL: '信号尚不成熟',
  NONE: '无',
};

/** Accountable business roles. */
export const ROLE_LABELS: CodeLabels = {
  OWNER: '负责人（Owner）',
  OPERATIONS: '运营',
  FINANCE: '财务',
  READ_ONLY: '只读',
  MARKETPLACE_OPERATOR: '平台运营',
  PRODUCT_PROCUREMENT: '商品采购',
  TECH_DATA: '技术与数据',
  FINANCE_ANALYST: '财务分析',
  OPS_LEAD: '运营主管',
  RISK_AUTHORITY: '风控',
  AUDITOR: '审计',
};

/** The case's confidence band. */
export const AD_CONFIDENCE_LABELS: CodeLabels = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
  UNUSABLE: '不可用',
};
export const AD_CONFIDENCE_COLORS: Colors = {
  HIGH: 'success',
  MEDIUM: 'processing',
  LOW: 'warning',
  UNUSABLE: 'error',
};

export const OBJECT_KIND_LABELS: CodeLabels = {
  CAMPAIGN: '广告活动',
  AD_GROUP: '广告组',
  TARGET: '投放目标',
  KEYWORD: '关键词',
  PLACEMENT: '广告位',
  UNKNOWN: '未知类型',
  LISTING_VARIANT: '商品规格',
};

export const PLATFORM_LABELS: CodeLabels = {
  OZON: 'Ozon',
  WILDBERRIES: 'Wildberries',
  WB: 'Wildberries',
  UNKNOWN: '未知平台',
};

export const DISCLOSURE_LABELS: CodeLabels = {
  FULL: '完整可见',
  MASKED: '已遮蔽',
  UNRESOLVED: '未确定',
};
export const DISCLOSURE_COLORS: Colors = { FULL: 'success', MASKED: 'warning' };

export const BIDDING_MODE_LABELS: CodeLabels = {
  MANUAL_BID: '手动出价',
  AUTO_BID: '自动出价',
  MIXED: '混合出价',
  UNKNOWN: '未知',
};

export const CONTROL_GRANULARITY_LABELS: CodeLabels = {
  PROVEN_INDEPENDENT: '已证实可单独控制',
  NOT_INDEPENDENTLY_CONTROLLABLE: '无法单独控制',
  UNKNOWN: '未知',
};
export const CONTROL_GRANULARITY_COLORS: Colors = {
  PROVEN_INDEPENDENT: 'success',
  NOT_INDEPENDENTLY_CONTROLLABLE: 'error',
  UNKNOWN: 'warning',
};

export const PROFILE_VERIFICATION_LABELS: CodeLabels = {
  VERIFIED: '已验证',
  UNVERIFIED: '未验证',
  ENGINEERING_VERIFIED: '工程验证',
  REAL_ACCOUNT_VERIFIED: '真实账户验证',
  UNKNOWN: '未知',
  UNRESOLVED: '未确定',
};
export const PROFILE_VERIFICATION_COLORS: Colors = {
  VERIFIED: 'success',
  REAL_ACCOUNT_VERIFIED: 'success',
  ENGINEERING_VERIFIED: 'processing',
  UNVERIFIED: 'warning',
};

export const SOURCE_MATURITY_LABELS: CodeLabels = {
  OFFICIAL_VERIFIED: '官方来源（已验证）',
  OFFICIAL_UNVERIFIED: '官方来源（未验证）',
  SYNTHETIC_FIXTURE: '合成测试数据',
  UNRESOLVED: '未确定',
};
export const SOURCE_MATURITY_COLORS: Colors = {
  OFFICIAL_VERIFIED: 'success',
  OFFICIAL_UNVERIFIED: 'warning',
  SYNTHETIC_FIXTURE: 'error',
};

export const RELATIONSHIP_KIND_LABELS: CodeLabels = {
  CONTAINS: '包含',
  PROMOTES: '推广',
  TARGETS: '投放',
  PARENT_CHILD: '上下级',
};

export const AFFECTED_SET_LABELS: CodeLabels = {
  COMPLETE: '完整',
  INCOMPLETE: '不完整',
  CONFLICTED: '有冲突',
  UNRESOLVED: '未确定',
};
export const AFFECTED_SET_COLORS: Colors = {
  COMPLETE: 'success',
  INCOMPLETE: 'warning',
  CONFLICTED: 'error',
  UNRESOLVED: 'warning',
};

/** Whether a figure exists at all. */
export const VALUE_STATE_LABELS: CodeLabels = {
  AVAILABLE: '有数值',
  NOT_AVAILABLE: '无法计算',
  UNDEFINED: '无意义',
  MASKED: '已遮蔽',
};

export const RANK_FACTOR_LABELS: CodeLabels = {
  CONFIRMED_PROFIT_LOSS_RATE: '已确认利润亏损率',
  CRITICAL_SALES_EXPOSURE: '关键销售暴露',
  OFFICIAL_SPEND_EXPOSURE: '官方花费暴露',
  RECOVERABLE_CONTRIBUTION_PROFIT: '可挽回贡献利润',
  EVIDENCE_MATURITY: '证据成熟度',
  CASE_AGE: '事项时长',
  CONFIDENCE_PENALTY: '可信度扣分',
  HUMAN_SLO_URGENCY: '人工响应紧迫度',
  BLOCKED_PROTECTION: '受阻的保护',
  BLAST_RADIUS: '影响半径',
  BLOCKED_WORK: '受阻的工作',
  DUAL_AXIS_GAP: '双轴差距',
  DUAL_AXIS_PER_RUB_GAP: '每卢布双轴差距',
  CRITICAL_SALES_HEADROOM: '关键销售余量',
};

/* -------------------------------------------------------------- workflow */

export const CANDIDATE_BASIS_LABELS: CodeLabels = {
  MAX_CPC_BOUNDED: '以最高 CPC 为上限',
  CAUSE_BOUND_PROTECTION_STEP: '按原因的保护性下调',
  UNRESOLVED: '依据未确定',
};

/** Recommendation lifecycle states a candidate can be in. */
export const CANDIDATE_STATE_LABELS: CodeLabels = {
  DRAFT: '草稿',
  VALIDATED: '已校验',
  READY_FOR_REVIEW: '待审批',
  TASK_ONLY: '仅任务',
  APPROVED: '已批准',
  POLICY_AUTHORIZED: '策略已授权',
  REJECTED: '已驳回',
  EXPIRED: '已过期',
  CANCELLED: '已取消',
  COMMAND_CREATED: '已创建指令',
  EXECUTION_TRACKING: '执行跟踪中',
  OUTCOME_OBSERVATION: '效果观察中',
  CLOSED: '已关闭',
};
export const CANDIDATE_STATE_COLORS: Colors = {
  DRAFT: 'default',
  VALIDATED: 'processing',
  READY_FOR_REVIEW: 'warning',
  APPROVED: 'success',
  POLICY_AUTHORIZED: 'success',
  REJECTED: 'error',
  EXPIRED: 'default',
  CANCELLED: 'default',
  COMMAND_CREATED: 'processing',
  EXECUTION_TRACKING: 'processing',
  OUTCOME_OBSERVATION: 'processing',
  CLOSED: 'success',
};

/** The candidate lifecycle as steps. */
export const CANDIDATE_STEPS = [
  { key: 'DRAFT', title: '选定' },
  { key: 'VALIDATED', title: '背书' },
  { key: 'READY_FOR_REVIEW', title: '审批' },
  { key: 'APPROVED', title: '创建指令' },
  { key: 'COMMAND_CREATED', title: '执行' },
] as const;

export const BID_UNIT_LABELS: CodeLabels = {
  CURRENCY_MAJOR: '主币单位',
  CURRENCY_MINOR: '辅币单位',
  UNKNOWN: '单位未知',
  UNRESOLVED: '单位未确定',
};

export const TASK_STATE_LABELS: CodeLabels = {
  OPEN: '待处理',
  ASSIGNED: '已分派',
  IN_PROGRESS: '处理中',
  DONE: '已完成',
  CANCELLED: '已取消',
  UNRESOLVED: '未确定',
};
export const TASK_STATE_COLORS: Colors = {
  OPEN: 'warning',
  ASSIGNED: 'processing',
  IN_PROGRESS: 'processing',
  DONE: 'success',
  CANCELLED: 'default',
};

export const DISPOSITION_LABELS: CodeLabels = {
  ACTION_REQUIRED: '需要处理',
  ACTION_IN_PROGRESS: '处理中',
  ACCEPTED_EXCEPTION_ACTIVE: '已接受例外',
  UNRESOLVED: '未确定',
};
export const DISPOSITION_COLORS: Colors = {
  ACTION_REQUIRED: 'warning',
  ACTION_IN_PROGRESS: 'processing',
  ACCEPTED_EXCEPTION_ACTIVE: 'default',
};

export const COVERAGE_LABELS: CodeLabels = {
  IN_COVERAGE: '值班时间内',
  OUT_OF_COVERAGE: '值班时间外',
  OUT_OF_COVERAGE_ACTIVE_HARM: '值班时间外（损失持续）',
  ACCEPTED_EXCEPTION_ACTIVE: '已接受例外',
  PROFILE_MISSING: '未配置响应规则',
  CALENDAR_MISSING: '未配置值班日历',
  UNRESOLVED: '未确定',
};
export const COVERAGE_COLORS: Colors = {
  IN_COVERAGE: 'success',
  OUT_OF_COVERAGE: 'default',
  OUT_OF_COVERAGE_ACTIVE_HARM: 'error',
  ACCEPTED_EXCEPTION_ACTIVE: 'default',
  PROFILE_MISSING: 'warning',
  CALENDAR_MISSING: 'warning',
};

/** Response timeliness, as derived by the console from the server snapshot. */
export const TIMELINESS_LABELS: CodeLabels = {
  BREACHED: '已超时',
  NOT_BREACHED: '未超时（截至本次响应）',
  UNRESOLVED: '未确定',
};
export const TIMELINESS_COLORS: Colors = { BREACHED: 'error', NOT_BREACHED: 'success' };

export const ACTION_CLOCK_LABELS: CodeLabels = {
  STAGE_COMPLETED: '该阶段已完成',
  PAUSED: '已暂停',
  ACTIVE: '计时中',
  AWAITING_COVERAGE: '等待值班时间',
  UNRESOLVED: '未确定',
};
export const ACTION_CLOCK_COLORS: Colors = {
  STAGE_COMPLETED: 'success',
  PAUSED: 'default',
  ACTIVE: 'processing',
  AWAITING_COVERAGE: 'warning',
};

export const JOURNAL_EVENT_LABELS: CodeLabels = {
  RAISED: '已提出',
  VIEWED: '已查看',
  ACKNOWLEDGED: '已确认接手',
  ASSIGNED: '已分派',
  REASSIGNED: '已改派',
  ACTION_RECORDED: '已记录操作',
  OUTCOME_OBSERVED: '已观察到结果',
  REOPENED: '已重新打开',
  ESCALATED: '已升级',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  EXECUTION_OBSERVED: '已观察到执行',
  DEFERRED: '已延后',
  REASSESSMENT_REQUIRED: '需要重新评估',
  DEPENDENCY_HOLD_STARTED: '等待依赖',
  DEPENDENCY_RESUMED: '依赖已恢复',
  DEPENDENCY_HOLD_EXPIRED: '依赖等待已过期',
  DEPENDENCY_INVALIDATED: '依赖已失效',
  EXECUTION_PENDING: '等待执行',
  EXECUTION_FAILED: '执行失败',
  EXECUTION_COMPENSATED: '已恢复原值',
  QUALIFICATION_INVALIDATED: '资格已失效',
};

export const EXCEPTION_STATE_LABELS: CodeLabels = {
  REQUESTED: '已申请',
  ENDORSED: '已背书',
  ACTIVE: '生效中',
  ENDED: '已结束',
  INVALIDATED: '已失效',
  EXPIRED: '已过期',
};
export const EXCEPTION_STATE_COLORS: Colors = {
  REQUESTED: 'warning',
  ENDORSED: 'processing',
  ACTIVE: 'success',
  ENDED: 'default',
  INVALIDATED: 'error',
  EXPIRED: 'default',
};

/* ------------------------------------------------------------ manual work */

export const MANUAL_ACTION_KIND_LABELS: CodeLabels = {
  AD_BID_CHANGE: '人工改出价',
  AD_BUDGET_CHANGE: '人工改预算',
  AD_STATUS_CHANGE: '人工改状态',
};

export const MANUAL_PACKET_STATE_LABELS: CodeLabels = {
  MANUAL_PACKET_DRAFT: '草稿',
  MANUAL_PACKET_ENDORSED: '已背书',
  MANUAL_PACKET_ISSUED: '已下发',
  MANUAL_EXECUTION_IN_PROGRESS: '执行中',
  MANUAL_PACKET_REVOKED: '已撤销',
  ACTION_REPORTED_CONFIGURATION_UNVERIFIED: '已报告执行，配置未验证',
  MANUAL_CONFIGURATION_VERIFIED: '配置已验证',
  MANUAL_EXECUTION_UNCERTAIN: '执行结果不确定',
  MANUAL_PACKET_EXPIRED: '已过期',
};
export const MANUAL_PACKET_STATE_COLORS: Colors = {
  MANUAL_PACKET_DRAFT: 'default',
  MANUAL_PACKET_ENDORSED: 'processing',
  MANUAL_PACKET_ISSUED: 'processing',
  MANUAL_EXECUTION_IN_PROGRESS: 'processing',
  MANUAL_PACKET_REVOKED: 'default',
  ACTION_REPORTED_CONFIGURATION_UNVERIFIED: 'warning',
  MANUAL_CONFIGURATION_VERIFIED: 'success',
  MANUAL_EXECUTION_UNCERTAIN: 'error',
  MANUAL_PACKET_EXPIRED: 'default',
};

export const EVIDENCE_GRADE_LABELS: CodeLabels = {
  OFFICIAL_API_READBACK: '官方 API 回读',
  OFFICIAL_CONFIGURATION_EXPORT: '官方配置导出',
  INDEPENDENT_MANUAL_VERIFICATION: '他人独立核验',
  EXECUTOR_SELF_REPORT: '执行人自报',
  UNVERIFIED_MANUAL_EVIDENCE: '未验证的人工证据',
};
export const EVIDENCE_GRADE_COLORS: Colors = {
  OFFICIAL_API_READBACK: 'success',
  OFFICIAL_CONFIGURATION_EXPORT: 'success',
  INDEPENDENT_MANUAL_VERIFICATION: 'processing',
  EXECUTOR_SELF_REPORT: 'warning',
  UNVERIFIED_MANUAL_EVIDENCE: 'warning',
};

export const CONFLICT_STATE_LABELS: CodeLabels = {
  NONE: '无冲突',
  CONFLICTED: '有冲突',
  SUPERSEDED_BY_LATER_CHANGE: '已被后续修改覆盖',
};
export const CONFLICT_STATE_COLORS: Colors = {
  NONE: 'success',
  CONFLICTED: 'error',
  SUPERSEDED_BY_LATER_CHANGE: 'warning',
};

export const VERIFICATION_MODE_LABELS: CodeLabels = {
  INDEPENDENT_OR_OFFICIAL: '独立核验或官方证据',
  OFFICIAL_ONLY: '仅限官方证据',
  UNRESOLVED: '未确定',
};

export const FIELD_PATH_LABELS: CodeLabels = {
  targetBid: '出价',
  targetBudget: '预算',
  targetStatus: '状态',
};

export const OBSERVATION_SOURCE_LABELS: CodeLabels = {
  DIRECT_OFFICIAL_CONSOLE: '直接在官方后台查看',
  SCREENSHOT: '截图',
};

export const COMPLETENESS_LABELS: CodeLabels = {
  COMPLETE: '完整（对象、字段和数值均可见）',
  INCOMPLETE: '不完整',
};

export const MANUAL_ACTION_LABELS: CodeLabels = {
  ENDORSE: '背书',
  APPROVE: '批准',
  START: '开始人工执行',
  REPORT: '报告已执行（无证明）',
  INDEPENDENT_VERIFY: '记录独立核验',
  OFFICIAL_VERIFY: '官方证据核验',
  OBSERVE_EARLY_SAFETY: '观察早期销售安全',
};

/* ------------------------------------------------------ containment etc. */

export const CONTAINMENT_KIND_LABELS: CodeLabels = {
  EMERGENCY_ENTITY_HOLD: '对象紧急冻结',
  ACTION_OUTCOME_QUARANTINE: '操作效果隔离',
  AUTHORITY_VERSION_QUARANTINE: '授权版本隔离',
  CAPABILITY_QUARANTINED: '能力已隔离',
  KILL_SWITCH_ACTIVE: '总开关已关闭',
};

export const CONTAINMENT_SCOPE_LABELS: CodeLabels = {
  ENTITY: '单个对象',
  AFFECTED_SET: '影响范围',
  AUTHORITY_VERSION: '授权版本',
  PLATFORM_STORE_CAPABILITY: '店铺广告能力',
  PLATFORM_ACCOUNT_CAPABILITY: '平台账户能力',
};

export const CAUSE_CLASS_LABELS: CodeLabels = {
  BUSINESS_HARM: '业务损失',
  OUTCOME_REGRESSION: '效果回退',
  EXECUTION_INTEGRITY: '执行完整性',
  AUTHORITY_VERSION_INVALID: '授权版本无效',
  PROVIDER_OR_READBACK_DEFECT: '平台或回读缺陷',
  CREDENTIAL_OR_SECURITY: '凭据或安全问题',
};

export const CONTAINMENT_STATE_LABELS: CodeLabels = {
  ACTIVE: '生效中',
  REENABLEMENT_REVIEW: '恢复评审中',
  REENABLED: '已恢复',
};
export const CONTAINMENT_STATE_COLORS: Colors = {
  ACTIVE: 'error',
  REENABLEMENT_REVIEW: 'warning',
  REENABLED: 'success',
};

export const CONTAINMENT_CONDITION_LABELS: CodeLabels = {
  ROOT_CAUSE_CLASSIFIED: '已确定根因',
  UNKNOWNS_RESOLVED: '未知状态已解决',
  AUTHORITIES_REPLACED: '授权已替换',
  RESULTS_RECONCILED: '结果已对账',
  CAPABILITY_EVIDENCE_CURRENT: '能力证据为最新',
  SECURITY_ATTESTATION_PRESENT: '已有安全证明',
  OPERATIONS_ENDORSEMENT: '运营背书',
};

/** Recovery actions: `ATTEST_<condition>` or `REENABLE`. */
export function recoveryActionLabel(action: string): string {
  if (action === 'REENABLE') {
    return '恢复投放';
  }
  if (action.startsWith('ATTEST_')) {
    const condition = action.slice('ATTEST_'.length);
    const name = CONTAINMENT_CONDITION_LABELS[condition];
    return name === undefined ? `证明条件（${condition}）` : `证明：${name}`;
  }
  return `${action}（未识别）`;
}

export const RESERVATION_KIND_LABELS: CodeLabels = {
  CONTROLLED_AD_BID_CHANGE: '受控改出价',
  CONFIRMED_MANUAL_PACKET: '已确认的人工操作',
  EXACT_PRIOR_BID_COMPENSATION: '恢复原出价',
};

export const DIRECTION_LABELS: CodeLabels = {
  PROTECTION_DECREASE: '保护性下调',
  OPTIMIZATION_INCREASE: '优化性上调',
  EXACT_PRIOR_BID_COMPENSATION: '恢复原出价',
};

export const RESERVATION_STATE_LABELS: CodeLabels = {
  ACTIVE: '占用中',
  RELEASED: '已释放',
};
export const RESERVATION_STATE_COLORS: Colors = { ACTIVE: 'processing', RELEASED: 'default' };

export const RELEASE_CONDITION_LABELS: CodeLabels = {
  CONFIGURATION_NOT_RESOLVED: '配置尚未确认',
  UNKNOWN_OR_MISMATCH_OPEN: '存在未知或不一致',
  EARLY_OBSERVATION_INCOMPLETE: '早期观察未完成',
  REGRESSION_OPEN: '存在效果回退',
};

export const EXPOSURE_AXIS_LABELS: CodeLabels = {
  activeInterventions: '进行中的干预数',
  associatedOfficialSpend: '关联官方花费',
  affectedRetainedSalesShare: '受影响留存销售占比',
  cumulativeBidChangeMajor: '累计出价变动',
  unresolvedTransmittedWrites: '未确认的已发送写入',
  reservedRecoveryHeadroom: '预留恢复余量',
};

export const AXIS_STATE_LABELS: CodeLabels = {
  AVAILABLE: '未超限',
  EXCEEDED: '已超限',
  UNKNOWN: '未知（余量未证实）',
};
export const AXIS_STATE_COLORS: Colors = {
  AVAILABLE: 'success',
  EXCEEDED: 'error',
  UNKNOWN: 'warning',
};

export const EXPOSURE_SCOPE_LABELS: CodeLabels = {
  ORGANIZATION: '组织',
  PLATFORM: '平台',
  STORE: '店铺',
};

export const EXPOSURE_STATUS_LABELS: CodeLabels = {
  MEASURED: '已测量',
  UNRESOLVED: '未确定',
  MASKED: '已遮蔽',
};
export const EXPOSURE_STATUS_COLORS: Colors = {
  MEASURED: 'success',
  UNRESOLVED: 'error',
  MASKED: 'warning',
};

/* ---------------------------------------------------------------- outcome */

export const OUTCOME_STAGE_LABELS: CodeLabels = {
  OPERATIONAL: '完成销售 · 运营观察',
  OPERATIONAL_REVISED: '完成销售 · 运营观察（重述）',
  RETAINED: '留存销售 · 30 天',
  RETAINED_REVISED: '留存销售 · 30 天（重述）',
  SETTLED: '已结算 · 成熟留存销售',
  SETTLED_REVISED: '已结算 · 成熟留存销售（重述）',
};

export const VERDICT_LABELS: CodeLabels = {
  IMPROVED: '改善',
  UNCHANGED: '无变化',
  REGRESSED: '回退',
  INDETERMINATE: '无法判定',
  NOT_YET_EVALUABLE: '尚不能评估',
};
export const VERDICT_COLORS: Colors = {
  IMPROVED: 'success',
  UNCHANGED: 'default',
  REGRESSED: 'error',
  INDETERMINATE: 'warning',
  NOT_YET_EVALUABLE: 'warning',
};

export const GUARD_STATE_LABELS: CodeLabels = {
  SATISFIED: '已满足',
  SALES_TOO_RECENT: '销售太新',
  COVERAGE_INSUFFICIENT: '覆盖不足',
  NOT_APPLICABLE: '不适用',
};
export const GUARD_STATE_COLORS: Colors = {
  SATISFIED: 'success',
  SALES_TOO_RECENT: 'warning',
  COVERAGE_INSUFFICIENT: 'warning',
};

/* ---------------------------------------------------------------- command */

export const COMMAND_STATE_LABELS: CodeLabels = {
  PENDING: '待执行',
  LEASED: '已领取',
  EXECUTING: '执行中',
  PLATFORM_PENDING: '等待平台处理',
  READBACK_PENDING: '等待回读',
  READBACK_MATCHED: '回读一致',
  RETRY_WAIT: '等待重试',
  UNKNOWN_REQUIRES_READBACK: '结果未知，需回读',
  READBACK_MISMATCH: '回读不一致',
  LATER_CHANGE_OR_MISMATCH_INVESTIGATION: '后续变更或不一致调查中',
  MANUAL_RESOLUTION: '需人工处理',
  FAILED_FINAL: '最终失败',
  TERMINATED_WITHOUT_PROVIDER_CALL: '未调用平台即终止',
  COMPENSATION_PENDING: '等待恢复原值',
  COMPENSATED: '已恢复原值',
  COMPENSATION_FAILED: '恢复原值失败',
};
export const COMMAND_STATE_COLORS: Colors = {
  PENDING: 'processing',
  LEASED: 'processing',
  EXECUTING: 'processing',
  PLATFORM_PENDING: 'processing',
  READBACK_PENDING: 'processing',
  READBACK_MATCHED: 'success',
  RETRY_WAIT: 'warning',
  UNKNOWN_REQUIRES_READBACK: 'error',
  READBACK_MISMATCH: 'error',
  LATER_CHANGE_OR_MISMATCH_INVESTIGATION: 'warning',
  MANUAL_RESOLUTION: 'error',
  FAILED_FINAL: 'error',
  TERMINATED_WITHOUT_PROVIDER_CALL: 'default',
  COMPENSATION_PENDING: 'processing',
  COMPENSATED: 'success',
  COMPENSATION_FAILED: 'error',
};

export const ATTEMPT_PURPOSE_LABELS: CodeLabels = {
  APPLY: '写入',
  STATUS_ENQUIRY: '状态查询',
  READBACK: '回读',
  RESTORE: '恢复',
};

export const ATTEMPT_OUTCOME_LABELS: CodeLabels = {
  IN_FLIGHT: '进行中',
  ACCEPTED: '平台已接受',
  REJECTED: '平台已拒绝',
  RETRIABLE_ERROR: '可重试错误',
  TIMEOUT: '超时',
  UNKNOWN_STATE: '状态未知',
};
export const ATTEMPT_OUTCOME_COLORS: Colors = {
  IN_FLIGHT: 'processing',
  ACCEPTED: 'success',
  REJECTED: 'error',
  RETRIABLE_ERROR: 'warning',
  TIMEOUT: 'warning',
  UNKNOWN_STATE: 'error',
};

export const READBACK_MATCH_LABELS: CodeLabels = {
  MATCHES_TARGET: '与目标一致',
  MATCHES_PRIOR: '仍为原值',
  DIFFERENT: '与两者都不同',
  UNREADABLE: '无法读取',
};
export const READBACK_MATCH_COLORS: Colors = {
  MATCHES_TARGET: 'success',
  MATCHES_PRIOR: 'warning',
  DIFFERENT: 'error',
  UNREADABLE: 'error',
};

export const COMPENSATION_STATE_LABELS: CodeLabels = {
  ABSENT: '未发起',
  PREVIEWED: '已准备',
  ENDORSED: '已背书',
  APPROVED: '已批准',
  EXPIRED: '已过期',
  COMPENSATION_PENDING: '等待恢复',
};
export const COMPENSATION_STATE_COLORS: Colors = {
  PREVIEWED: 'processing',
  ENDORSED: 'processing',
  APPROVED: 'success',
  EXPIRED: 'default',
  COMPENSATION_PENDING: 'processing',
};

export const COMPENSATION_ACTION_LABELS: CodeLabels = {
  PREVIEW: '准备恢复原出价',
  ENDORSE: '背书恢复',
  APPROVE: '批准恢复',
};

/* ---------------------------------------------------------- orchestration */

export const ORCHESTRATION_STATE_LABELS: CodeLabels = {
  WITHIN_OBSERVED_BOUNDS: '在观测范围内',
  INCIDENT: '存在事故',
};
export const ORCHESTRATION_STATE_COLORS: Colors = {
  WITHIN_OBSERVED_BOUNDS: 'success',
  INCIDENT: 'error',
};

export const DISTRIBUTION_STATE_LABELS: CodeLabels = {
  MEASURED: '已测量',
  NO_CRITICAL_OBSERVATIONS: '无关键样本',
};

export const INCIDENT_LABELS: CodeLabels = {
  AD_OUTCOME_PLAN_DEADLINE_UNRESOLVED: '效果观察计划截止时间未确定',
  CRITICAL_P95_BREACHED: '关键延迟 P95 超限',
  HARD_BOUND_BREACHED: '延迟硬上限被突破',
  CLOCK_INCONSISTENT: '时钟不一致',
  TARGETED_FAILURE: '定向重算失败',
  BACKLOG_HARD_BOUND_BREACHED: '积压超过硬上限',
  HOURLY_RECONCILIATION_NOT_CURRENT: '每小时对账未按时完成',
  LATEST_RECONCILIATION_FAILED: '最近一次对账失败',
};

export const SWEEP_STATE_LABELS: CodeLabels = {
  COMPLETED: '已完成',
  RUNNING: '运行中',
  FAILED: '失败',
  NOT_ESTABLISHED: '尚无记录',
};
export const SWEEP_STATE_COLORS: Colors = {
  COMPLETED: 'success',
  RUNNING: 'processing',
  FAILED: 'error',
  NOT_ESTABLISHED: 'warning',
};

/* ------------------------------------------------------------------ brief */

export const BRIEF_KIND_LABELS: CodeLabels = {
  DAILY_ACTION_BRIEF: '每日行动简报',
  WEEKLY_EVIDENCE_REVIEW: '每周证据复盘',
};

export const BRIEF_SECTION_LABELS: CodeLabels = {
  DATA_HEALTH: '数据健康',
  IMMEDIATE_PROTECTION_AND_REGRESSION: '紧急保护与回退',
  DATA_REPAIR: '数据修复',
  QUALIFIED_OPTIMIZATION: '合格的优化',
  WATCH: '观察',
  HUMAN_RESPONSIBILITY: '人员责任',
  APPROVALS_AND_EXCEPTIONS: '审批与例外',
  EXECUTION_AND_AGGREGATE_EXPOSURE: '执行与总体暴露',
  UNKNOWN_MISMATCH_AND_MANUAL_VERIFICATION: '未知、不一致与人工核验',
  RECENT_OUTCOMES: '近期效果',
  SHADOW_DECISION_REASONS: '影子决策原因',
  GOVERNED_ACTIONS: '受控操作',
  CONFIGURATION_VERIFICATION: '配置核验',
  EARLY_GUARDS: '早期防护',
  OPERATIONAL_AND_SETTLED_TRANSITIONS: '运营与结算转换',
  REGRESSION_QUARANTINE_AND_COMPENSATION: '回退、隔离与恢复',
  EXCEPTIONS: '例外',
  SYSTEM_AND_HUMAN_SLO: '系统与人工响应时效',
  AGGREGATE_EXPOSURE: '总体暴露',
  POLICY_BUNDLE_MATURITY: '策略包成熟度',
  GATE_EVIDENCE: '闸门证据',
  DEFERRED_RELEASE_OBLIGATIONS: '延后的发布义务',
};

export const SECTION_COVERAGE_LABELS: CodeLabels = {
  COMPLETE: '完整',
  PARTIAL: '部分',
  NOT_AVAILABLE: '不可用',
  BLOCKED: '受阻',
};
export const SECTION_COVERAGE_COLORS: Colors = {
  COMPLETE: 'success',
  PARTIAL: 'warning',
  NOT_AVAILABLE: 'default',
  BLOCKED: 'error',
};

export const REVISION_KIND_LABELS: CodeLabels = {
  ORIGINAL: '原始版本',
  REVISION: '修订',
  DELTA: '增量更正',
};

export const SUBJECT_KIND_LABELS: CodeLabels = {
  AD_CASE: '广告事项',
  WORK_TASK: '工作任务',
  RECOMMENDATION: '建议',
  OUTCOME_OBSERVATION: '效果观察',
  SLO_OBSERVATION: '响应时效观察',
  CONTAINMENT: '管控',
  RESERVATION: '额度占用',
  BID_COMMAND: '出价指令',
  MANUAL_PACKET: '人工操作单',
  DECISION_BUNDLE: '决策策略包',
  METRIC_VALUE: '指标值',
};

/* ------------------------------------------------ blockers, gaps, reasons */

/**
 * Blockers, gaps and unresolved reasons.
 *
 * These arrive from many producers under one field, so the table is shared.
 * Stable error codes are folded in because a gate reason is often one of them.
 */
export const REASON_LABELS: CodeLabels = {
  ...ERROR_CODE_LABELS,
  ACTION_EVIDENCE_AUTHORITY_CHANGED: '操作证据的授权已变化',
  ACTION_EVIDENCE_BLOCKERS_UNRESOLVED: '操作证据仍有未解决的阻断',
  ACTION_NOT_LAUNCHED: '操作尚未启动',
  ACTION_TIME_AFFECTED_SET_LINEAGE_MISMATCH: '操作时的影响范围与当前不一致',
  ACTIVE_INTERVENTIONS: '进行中的干预数超限',
  ADS_WRITE_CREDENTIAL_AUTHORITY_INVALID: '广告写入凭据授权无效',
  ADVERTISING_DECISION_POLICY_UNRESOLVED: '广告决策策略未确定',
  ADVERTISING_OBJECT_NOT_INDEPENDENTLY_CONTROLLABLE: '广告对象无法单独控制',
  AD_LINKED_CONVERSION_NOT_WRITE_GRADE: '广告关联转化不足以支持写入',
  AFFECTED_RETAINED_SALES_SHARE: '受影响留存销售占比超限',
  AFFECTED_SET_DIGEST_CHANGED: '影响范围已变化',
  AFFECTED_SET_INCOMPLETE: '影响范围不完整',
  AFFECTED_SET_NEVER_RESOLVED: '影响范围从未确定',
  AGGREGATE_ENVELOPE_BLOCKED: '总体暴露额度受阻',
  AGGREGATE_ENVELOPE_UNRESOLVED: '总体暴露额度未确定',
  ALLOWABLE_CPA_UNRESOLVED: '可接受 CPA 未确定',
  ALLOWANCE_AXES_UNRESOLVED: '额度维度未确定',
  ALLOWANCE_NOT_OCCUPIED: '未占用额度',
  ALLOWANCE_POLICY_UNRESOLVED: '额度策略未确定',
  APPROVAL_LEASE_EXPIRED: '审批有效期已过',
  APPROVAL_LEASE_POLICY_ABSENT: '缺少审批有效期策略',
  APPROVAL_MISSING: '缺少审批',
  ASSOCIATED_SPEND: '关联花费超限',
  ASSOCIATED_SPEND_UNRESOLVED: '关联花费未确定',
  AUTHORITY_PERMANENTLY_INVALIDATED: '授权已永久失效',
  AUTHORIZATION_INVALID_OR_EXPIRED: '授权无效或已过期',
  BASELINE_UNAVAILABLE: '缺少基线',
  BID_MOVED_SINCE_CANDIDATE: '候选生成后出价已变化',
  BINDING_EXPIRED: '绑定条件已过期',
  BLOCKER_PROJECTION_MISSING: '缺少阻断信息',
  BUNDLE_SCOPE_EXCEEDED: '超出策略包范围',
  BUNDLE_UNRESOLVED: '策略包未确定',
  CALIBRATION_NOT_CURRENT: '校准配置不是最新',
  CANDIDATE_BASIS_NOT_ENABLED: '该候选依据未启用',
  CANDIDATE_UNRESOLVED: '候选未确定',
  CANONICAL_CONFIDENCE_UNQUALIFIED: '标准数据可信度不足',
  CANONICAL_OUTCOME_BASELINE_AUTHORITY_INVALID: '效果基线授权无效',
  CANONICAL_VALUE_UNAVAILABLE: '缺少标准数值',
  CAPABILITY_NOT_AVAILABLE_FOR_STORE: '该店铺不可用此能力',
  CAPABILITY_NOT_VERIFIED: '能力未验证',
  CAPABILITY_SWITCH_DISABLED: '能力开关已关闭',
  CAUSE_BOUND_CAUSE_UNSUPPORTED: '该原因不支持保护性下调',
  COMMAND_AUTHORITY_MISMATCH: '指令授权不一致',
  COMPANY_AFFECTED_SCOPE_UNRESOLVED: '公司层面影响范围未确定',
  COMPENSATION_AFFECTED_SCOPE_EXPIRED: '恢复的影响范围已过期',
  COMPENSATION_GATE_SCOPE_ABSENT: '缺少恢复闸门范围',
  COMPENSATION_HARD_AUTHORITY_INVALID: '恢复的硬性授权无效',
  COMPENSATION_HARD_STOP_ACTIVE: '恢复硬性停止生效中',
  COMPENSATION_OWNER_AUTHORITY_EXPIRED: '恢复的 Owner 授权已过期',
  COMPLETE_AUTHORITY_SNAPSHOT_CHANGED: '完整授权快照已变化',
  CONTRADICTORY_NATIVE_TASK_RESULT: '平台任务结果相互矛盾',
  CONTROL_GRANULARITY_UNPROVEN: '控制粒度未证实',
  CONVERSION_NOT_WRITE_GRADE: '转化数据不足以支持写入',
  CONVERSION_ZERO: '转化为零',
  CRITICAL_SALES_AMOUNT: '关键销售额超限',
  CRITICAL_SALES_EXPOSURE_UNKNOWN: '关键销售暴露未知',
  CRITICAL_SALES_GUARD_EVIDENCE_UNRESOLVED: '关键销售防护证据未确定',
  CRITICAL_UNIT_DEFINITION_UNRESOLVED: '关键销售单元定义未确定',
  CUMULATIVE_BID_CHANGE: '累计出价变动超限',
  CUMULATIVE_BID_CHANGE_UNRESOLVED: '累计出价变动未确定',
  CUMULATIVE_CHANGE_UNKNOWN: '累计变动未知',
  CURRENT_BID_NOT_OBSERVED: '未观察到当前出价',
  CURRENT_HUMAN_AUTHORITY_REVOKED: '当前人工授权已撤销',
  CURRENT_VERIFICATION_UNAVAILABLE: '缺少当前核验',
  DIRECTION_NOT_ENABLED: '该调整方向未启用',
  EARLY_COMPANY_OR_CRITICAL_BASELINE_UNRESOLVED: '早期公司或关键基线未确定',
  EARLY_COMPLETED_SALES_EVIDENCE_UNRESOLVED: '早期完成销售证据未确定',
  EARLY_COMPLETED_SALES_NOT_DUE: '早期完成销售尚未到期',
  ECONOMIC_CAUSE_PROOF_UNRESOLVED: '经济原因证明未确定',
  ECONOMIC_CAUSE_PURPOSE_EVIDENCE_UNRESOLVED: '经济原因用途证据未确定',
  ECONOMIC_CAUSE_TARGET_POLICY_NOT_ACCEPTED: '经济原因目标策略未被接受',
  ENTITY_NOT_ALLOWLISTED: '对象不在允许名单内',
  EVALUATION_PLAN_BINDING_MISSING_OR_CHANGED: '评估计划绑定缺失或已变化',
  EXACT_COMPENSATION_APPROVAL_ABSENT_OR_STALE: '恢复审批缺失或已过期',
  EXACT_GATE_AUTHORITY_ABSENT_OR_EXCEEDED: '闸门授权缺失或超限',
  EXACT_MANAGEMENT_READBACK_UNPROVEN: '管理端回读未证实',
  EXECUTION_PASS_MISSING: '缺少执行许可',
  EXPOSURE_MEASUREMENT_POLICY_ABSENT: '缺少暴露测量策略',
  FIXED_COMPENSATION: '固定路由：恢复原值',
  FIXED_CRITICAL_PROTECTED_SALES_EXPOSURE: '固定路由：关键保护销售暴露',
  FIXED_QUARANTINE_OR_KILL: '固定路由：隔离或总开关',
  FIXED_REGRESSION_OR_UNKNOWN_EXECUTION: '固定路由：回退或执行未知',
  FIXED_UNKNOWN_DECISION_EVIDENCE: '固定路由：决策证据未知',
  GLOBAL_SWITCH_DISABLED: '全局开关已关闭',
  GUARDRAIL_BUNDLE_MISMATCH: '规则与策略包不一致',
  GUARDRAIL_NOT_PASSED: '未通过规则校验',
  KILL_SWITCH_ACTIVE: '总开关已关闭',
  LATEST_APPLY_COMPLETION_UNPROVEN: '最近一次写入完成未证实',
  MATERIALITY_UNRESOLVED: '重要程度未确定',
  NATIVE_ENUMERATION_INCOMPLETE: '平台对象枚举不完整',
  NATIVE_MEMBER_IDENTITY_MISSING: '缺少平台成员标识',
  NATIVE_MEMBER_OUTSIDE_CAPTURED_SCOPE: '平台成员超出已采集范围',
  NATIVE_SCOPE_CONFLICT: '平台范围冲突',
  NATIVE_SCOPE_SOURCE_STALE: '平台范围数据过期',
  NATIVE_SCOPE_UNPROVEN: '平台范围未证实',
  NATIVE_SCOPE_VERIFICATION_EXPIRED: '平台范围核验已过期',
  NO_CHANGE_PROPOSED: '未提出变更',
  NON_TARGET_FIELD_RISK: '可能影响非目标字段',
  OFFICIAL_AD_FACT_ABSENT: '缺少官方广告数据',
  OFFICIAL_AD_SPEND_NOT_CANONICAL_COMPLETE_CLOSED: '官方广告花费尚未完整结账',
  OFFICIAL_SPEND_EXPOSURE: '官方花费暴露',
  OFFICIAL_SPEND_UNKNOWN: '官方花费未知',
  OPERATION_AFTER_PACKET_AUTHORITY: '操作晚于操作单授权',
  OPERATION_BEFORE_PACKET_AUTHORITY: '操作早于操作单授权',
  OPTIMIZATION_RETAINED_BASELINE_UNRESOLVED: '优化的留存基线未确定',
  ORDINARY_ENVELOPE_EXCEEDED: '超出常规额度',
  ORDINARY_ROUTE_NOT_PROMOTED: '常规路由未启用',
  OUTCOME_AFFECTED_SCOPE_UNRESOLVED: '效果影响范围未确定',
  OUTCOME_BASELINE_INSUFFICIENT: '效果基线不足',
  OUTCOME_COVERAGE_INSUFFICIENT: '效果覆盖不足',
  OUTCOME_CURRENCY_CHANGED: '效果币种已变化',
  OUTCOME_DENOMINATOR_BELOW_POLICY_OR_UNKNOWN: '效果分母低于策略要求或未知',
  OUTCOME_DUAL_AXIS_POLICY_UNRESOLVED: '效果双轴策略未确定',
  OUTCOME_ORIGINAL_ACTION_IDENTITY_UNRESOLVED: '原操作身份未确定',
  OUTCOME_POLICY_INCOMPLETE: '效果策略不完整',
  OUTCOME_WINDOW_NOT_DUE: '效果观察窗口未到期',
  PROFILE_UNRESOLVED: '平台语义配置未确定',
  PROFILE_UNVERIFIED: '平台语义配置未验证',
  PROVIDER_TO_CANONICAL_ATTRIBUTION_GAP_MATERIAL: '平台归因与标准数据差距较大',
  PURPOSE_USE_BASIS_MISSING: '缺少用途依据',
  PURPOSE_USE_BASIS_MISSING_OR_CHANGED: '用途依据缺失或已变化',
  PURPOSE_USE_BINDING_OUTLIVES_USE: '用途绑定超过有效期',
  PURPOSE_USE_EXPIRED: '用途已过期',
  QUARANTINE_ACTIVE: '隔离生效中',
  RECOMMENDATION_EXPIRED: '建议已过期',
  RECOVERY_HEADROOM: '预留恢复余量不足',
  RELATIVE_BID_CHANGE: '相对出价变动超限',
  ABSOLUTE_BID_CHANGE: '绝对出价变动超限',
  AFFECTED_VARIANT_EXPOSURE: '受影响商品暴露',
  RESERVATION_CONFLICT: '额度占用冲突',
  RETAINED_SALES_SHARE_UNRESOLVED: '留存销售占比未确定',
  REVIEW_MISSING: '缺少评审',
  SCOPED_SWITCH_DISABLED: '范围开关已关闭',
  SEALED_AUTHORIZATION_MISSING_OR_EXPIRED: '封存授权缺失或已过期',
  SEALED_MATERIALITY_ROUTE_CHANGED_OR_UNRESOLVED: '封存的重要程度路由已变化或未确定',
  SETTLEMENT_ATTRIBUTION_UNRESOLVED: '结算归因未确定',
  SOURCE_LINEAGE_UNAVAILABLE: '缺少来源谱系',
  SOURCE_MATURITY_UNPROVEN: '来源成熟度未证实',
  STAGE_MISMATCH: '销售阶段不匹配',
  SYNCHRONOUS_NATIVE_COMPLETION_UNPROVEN: '平台同步完成未证实',
  TRAFFIC_BELOW_MINIMUM: '流量低于下限',
  TRAFFIC_BELOW_MINIMUM_OR_UNKNOWN: '流量低于下限或未知',
  TRAFFIC_UNAVAILABLE: '缺少流量数据',
  UNRESOLVED_TRANSMITTED_WRITES: '未确认的已发送写入超限',
  VARIANT_UNMAPPED: '商品规格未映射',
  MAPPING_CONFLICT_OPEN: '商品存在未解决的映射冲突',
};

/* ------------------------------------------------------ evidence key names */

/**
 * Chinese names for the camelCase keys of disclosed evidence.
 *
 * A key missing here is shown as it arrived: guessing a translation for a field
 * the console does not know would put a meaning on it that nobody verified.
 */
export const EVIDENCE_KEY_LABELS: CodeLabels = {
  id: '编号',
  state: '状态',
  status: '状态',
  reason: '原因',
  reasons: '原因',
  version: '版本',
  expectedVersion: '期望版本',
  currencyCode: '币种',
  currency: '币种',
  bidUnitCode: '出价单位',
  unit: '单位',
  platformCode: '平台',
  storeId: '店铺编号',
  recommendationId: '建议编号',
  commandId: '指令编号',
  caseId: '事项编号',
  taskId: '任务编号',
  adNativeObjectId: '广告对象编号',
  affectedSetDigest: '影响范围摘要',
  direction: '调整方向',
  candidateBasis: '候选依据',
  materialityRoute: '重要程度路由',
  priorBidAmount: '原出价',
  targetBidAmount: '目标出价',
  currentBidAmount: '当前出价',
  observedBid: '观察到的出价',
  attemptNo: '尝试次数',
  retryBudgetRemaining: '剩余重试次数',
  failureCode: '失败代码',
  approvalExpiresAt: '审批到期时间',
  createdAt: '创建时间',
  updatedAt: '更新时间',
  terminalAt: '终结时间',
  startedAt: '开始时间',
  completedAt: '完成时间',
  observedAt: '观察时间',
  occurredAt: '发生时间',
  expiresAt: '到期时间',
  reviewDueAt: '评审截止时间',
  purpose: '用途',
  outcomeClass: '结果类别',
  nativeStatus: '平台状态',
  errorCode: '错误代码',
  matchState: '比对结果',
  verdict: '结论',
  passed: '是否通过',
  gateReasons: '闸门原因',
  unresolvedReasons: '未解决原因',
  evidenceReference: '证据引用',
  actorUserId: '操作人',
  actorRoleCode: '操作人角色',
  eventKind: '事件类型',
  disclosureState: '可见性',
  lane: '工作类型',
  causeCode: '原因',
  minimumStep: '最小步长',
  step: '步长',
  minimumBid: '最低出价',
  maximumBid: '最高出价',
  minBid: '最低出价',
  maxBid: '最高出价',
  precision: '精度',
  scale: '小数位数',
  readbackSemantics: '回读语义',
  propagationSemantics: '生效传播方式',
  idempotencySemantics: '幂等语义',
  correctionBehaviour: '更正方式',
  controlLevel: '控制层级',
  biddingMode: '出价方式',
  verificationState: '验证状态',
  sourceMaturity: '来源成熟度',
  semanticProfileId: '语义配置编号',
  verificationFieldPath: '核验字段',
  nativeObjectKey: '平台对象标识',
  targetBid: '目标出价',
  targetBudget: '目标预算',
  targetStatus: '目标状态',
  currentBid: '当前出价',
  observedValue: '观察值',
  instructions: '操作说明',
  sampleCount: '样本数',
  criticalSampleCount: '关键样本数',
  criticalP95Millis: '关键延迟 P95（毫秒）',
  maximumMillis: '最大延迟（毫秒）',
  hardBreachCount: '硬上限突破次数',
  clockDefectCount: '时钟异常次数',
  pendingRequests: '待处理重算',
  failedRequests: '失败重算',
  oldestFactAcceptedAt: '最早待处理数据时间',
  lastSweepState: '最近对账状态',
  lastSweepCompletedAt: '最近对账完成时间',
  windowHours: '窗口（小时）',
  incidents: '事故',
  distributionState: '分布状态',
  baseline: '基线',
  observed: '观察值',
  value: '数值',
  threshold: '阈值',
  limit: '上限',
  usage: '用量',
  amount: '金额',
};
