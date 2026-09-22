import type { CodeLabels } from '../labels';

/**
 * Chinese text for the stockout and availability screens.
 *
 * Every backend code family these screens render has a table here, so no raw
 * enum ever reaches an operator as text. Codes missing from a table are shown
 * by the display atoms as the raw value with an "unrecognised" marker.
 */

/** Section titles and one-line hints. */
export const availabilityText = {
  queueTitle: '缺货与可售风险',
  queueEmpty: '当前范围内没有商品存在可售风险。队列为空不代表未被监控。',
  casesTitle: '可售风险责任工单',
  casesEmpty: '当前范围内没有需要处理的工单。“观察”级风险只在队列中显示，不会生成工单。',
  authorityTitle: '供应权限',
  authorityHint:
    '登记可追溯的入库证明和按生效时间管理的提前期策略。每次变更都会触发重新计算，不会调用任何电商平台接口。',
} as const;

/** Risk lanes. */
export const LANE_LABELS: CodeLabels = {
  HEALTHY: '健康',
  WATCH: '观察',
  HIGH: '高风险',
  CRITICAL: '严重',
  REVIEW: '待复核',
  UNRESOLVED: '无法判定',
};

/** What a calculated risk rests on. */
export const EVIDENCE_LABELS: CodeLabels = {
  CONFIRMED: '已确认',
  OPERATIONAL: '运营数据',
  PROVISIONAL: '暂定',
  CARRIED_FORWARD: '沿用上次',
  DATA_BLOCKED: '数据阻断',
  POLICY_BLOCKED: '策略阻断',
  CONFLICTED: '数据冲突',
  STALE: '数据过期',
  UNKNOWN: '未知',
};

/** Confidence of a child risk. */
export const RISK_CONFIDENCE_LABELS: CodeLabels = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
  UNUSABLE: '不可用',
};

/** Why somebody is needed. */
export const CAUSE_LABELS: CodeLabels = {
  CHANNEL_OUT_OF_STOCK: '渠道已无可售库存',
  CHANNEL_COVER_SHORT: '渠道库存将在覆盖周期内售罄',
  CHANNEL_NOT_SELLABLE: '商品当前无法购买',
  COMPANY_SUPPLY_SHORT: '公司库存将在提前期与安全期内售罄',
  COMPANY_INBOUND_LAPSED: '所依赖的入库已失效',
  STOCK_DATA_DEFECT: '库存数据缺失或矛盾',
  OWNERSHIP_UNDECLARED: '未证明平台库存与内部库存相互独立',
  LEAD_TIME_POLICY_MISSING: '缺少适用的提前期与安全库存策略',
  DEMAND_POLICY_MISSING: '没有生效的需求策略版本',
  DEMAND_UNOBSERVABLE: '无法观测需求',
  PROFIT_DATA_BLOCKED: '利润数据过期、不完整或冲突',
  RETURN_QUALITY_REVIEW: '退货与质量数据需要人工判断',
  NONE: '无需处理',
};

/** Fulfillment modes. */
export const FULFILLMENT_MODE_LABELS: CodeLabels = {
  MARKETPLACE_FULFILLED: '平台仓发货',
  SELLER_FULFILLED: '卖家自发货',
};

/** Marketplaces by their platform code; product names stay untranslated. */
export const PLATFORM_LABELS: CodeLabels = {
  OZON: 'Ozon',
  WILDBERRIES: 'Wildberries',
  WB: 'Wildberries',
};

/** Profit lanes of a child risk. */
export const PROFIT_LANE_LABELS: CodeLabels = {
  CONFIRMED_ELIGIBLE: '利润已确认',
  OPERATIONAL_ELIGIBLE: '利润（运营数据）',
  PROVISIONAL: '利润暂定',
  PROFIT_DATA_BLOCKED: '利润数据阻断',
  NOT_PROFITABLE: '不盈利',
  PROFIT_UNKNOWN: '利润未知',
};

/** Why a figure cannot be trusted yet. */
export const BLOCKER_LABELS: CodeLabels = {
  LEAD_TIME_POLICY_UNRESOLVED: '提前期策略未解析',
  PRIORITY_POLICY_UNRESOLVED: '优先级策略未解析',
  CHANNEL_QUANTITY_NOT_REPORTED: '渠道未上报库存数量',
  CHANNEL_OBSERVATION_STALE: '渠道库存观测已过期',
  CHANNEL_SELLABILITY_UNKNOWN: '渠道可售状态未知',
  COMPANY_SUPPLY_NOT_OBSERVED: '未观测到公司库存',
  COMPANY_DEMAND_CARRIED_FORWARD: '公司需求沿用上次结果',
  CHANNEL_DEMAND_PROVISIONAL: '渠道需求暂定',
  CHANNEL_DEMAND_CARRIED_FORWARD: '渠道需求沿用上次结果',
  CHANNEL_DEMAND_DATA_BLOCKED: '渠道需求数据阻断',
  CHANNEL_DEMAND_POLICY_BLOCKED: '渠道需求策略阻断',
  CHANNEL_DEMAND_CONFLICTED: '渠道需求数据冲突',
  CHANNEL_DEMAND_STALE: '渠道需求数据过期',
  CHANNEL_DEMAND_UNKNOWN: '渠道需求未知',
  COMPANY_DEMAND_PROVISIONAL: '公司需求暂定',
  COMPANY_DEMAND_DATA_BLOCKED: '公司需求数据阻断',
  COMPANY_DEMAND_POLICY_BLOCKED: '公司需求策略阻断',
  COMPANY_DEMAND_CONFLICTED: '公司需求数据冲突',
  COMPANY_DEMAND_STALE: '公司需求数据过期',
  COMPANY_DEMAND_UNKNOWN: '公司需求未知',
  COMPANY_SUPPLY_MIRRORS_INTERNAL_STOCK: '平台库存与内部库存重复',
  COMPANY_SUPPLY_OWNERSHIP_NOT_DECLARED: '库存归属未声明',
  COMPANY_SUPPLY_STALE_OBSERVATION: '库存观测已过期',
  COMPANY_SUPPLY_QUANTITY_NOT_REPORTED: '库存数量未上报',
};

/** Visible reasons behind a queue position. */
export const RANK_FACTOR_LABELS: CodeLabels = {
  TIME_TO_STOCKOUT: '距断货时间',
  CONTRIBUTION_PROFIT_AT_RISK: '受威胁的贡献利润',
  SALES_VELOCITY: '销售速度',
  LIFECYCLE_STRATEGY: '生命周期策略',
  CONFIDENCE_PENALTY: '置信度扣减',
};

/** Demand windows. */
export const DEMAND_WINDOW_LABELS: CodeLabels = {
  D7: '近 7 天',
  D14: '近 14 天',
  D30: '近 30 天',
};

/** Whether a demand window may be used. */
export const WINDOW_ELIGIBILITY_LABELS: CodeLabels = {
  ELIGIBLE: '可用',
  LOW_SAMPLE: '样本不足',
  CENSORED: '已删失',
  OUTLIER_REVIEW: '异常值待复核',
  WINDOW_CONFLICT: '窗口冲突',
  DATA_BLOCKED: '数据阻断',
};

/** Why a demand window was censored. */
export const CENSORING_REASON_LABELS: CodeLabels = {
  NOT_SELLABLE: '期间不可售',
  NO_STOCK: '期间无库存',
  SOURCE_STALE: '数据源过期',
  KNOWN_OUTAGE: '已知故障',
  PARTIAL_COVERAGE: '覆盖不完整',
  UNKNOWN: '原因未知',
};

/** Case states. */
export const CASE_STATE_LABELS: CodeLabels = {
  OPEN: '待处理',
  ASSIGNED: '已分派',
  IN_PROGRESS: '处理中',
  ACTION_RECORDED: '已记录行动',
  VERIFYING: '验证中',
  VERIFIED_SUCCESS: '已验证解决',
  REOPENED: '已重开',
  ESCALATED: '已升级',
  REWORK_REQUIRED: '需返工',
  ACCEPTED_RISK: '已接受风险',
  CANCELLED: '已取消',
};

/** Structured actions that can satisfy the first stage. */
export const ACTION_KIND_LABELS: CodeLabels = {
  INBOUND_EVIDENCE_BOUND: '已绑定可证明的入库',
  CHANNEL_RESTORATION_REFERENCE: '已记录渠道恢复凭证',
  DATA_OR_MAPPING_REPAIR: '已修复库存、映射或归属问题',
  POLICY_VERSION_PUBLISHED: '已发布策略版本',
  QUALITY_DISPOSITION_RECORDED: '已记录退货或质量处置',
  OWNERSHIP_DECLARATION_PUBLISHED: '已发布库存归属声明',
};

/** Events in a case's history. */
export const CASE_EVENT_LABELS: CodeLabels = {
  ACTIVATED: '创建工单',
  ACTION_RECORDED: '记录行动',
  ESCALATED: '升级',
  EVIDENCE_APPENDED: '追加证据',
  EXCEPTION_APPLIED: '风险接受生效',
  EXCEPTION_INVALIDATED: '风险接受失效',
  REOPENED: '重开',
  VERIFICATION_STARTED: '开始验证',
  VERIFICATION_OBSERVED: '观测验证结果',
  VERIFIED_SUCCESS: '验证解决',
  CANCELLED: '取消',
};

/** How a verification came out. */
export const VERIFICATION_OUTCOME_LABELS: CodeLabels = {
  VERIFIED: '已验证',
  CONTINUING: '仍在持续',
  FAILED: '未通过',
  REGRESSED: '已恶化',
};

/** Acceptance states. */
export const EXCEPTION_STATE_LABELS: CodeLabels = {
  REQUESTED: '已申请',
  AUTHORITY_BLOCKED: '权限不足',
  ACTIVE: '已接受',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
  INVALIDATED: '已失效',
  WITHDRAWN: '已撤回',
};

/** Business reasons for accepting a risk. */
export const EXCEPTION_REASON_LABELS: CodeLabels = {
  PLANNED_DISCONTINUATION: '计划停售',
  SEASONAL_PAUSE: '季节性暂停',
  SUPPLIER_OUTAGE_ACCEPTED: '已接受供应商断供',
  COMMERCIALLY_IMMATERIAL: '商业影响可忽略',
  ALTERNATIVE_SUPPLY_ARRANGED: '已安排替代供应',
  KNOWN_DATA_LIMITATION_ACCEPTED: '已接受已知数据局限',
};

/** Authority a decision needs. */
export const AUTHORITY_LEVEL_LABELS: CodeLabels = {
  DOMAIN_LEAD: '领域负责人',
  OPS_LEAD: '运营负责人',
  RISK_AUTHORITY: '风险审批人',
};

/** Business roles that own a cause. */
export const ROLE_LABELS: CodeLabels = {
  OWNER: '所有者',
  OPERATIONS: '运营',
  FINANCE: '财务',
  READ_ONLY: '只读',
  MARKETPLACE_OPERATOR: '平台运营',
  PRODUCT_PROCUREMENT: '商品采购',
  TECH_DATA: '技术与数据',
  FINANCE_ANALYST: '财务分析',
  OPS_LEAD: '运营负责人',
  RISK_AUTHORITY: '风险审批人',
};

/** Business status of an inbound attestation. */
export const INBOUND_STATUS_LABELS: CodeLabels = {
  DRAFT: '草稿',
  REQUESTED: '已申请',
  SUPPLIER_CONFIRMED: '供应商已确认',
  IN_TRANSIT: '运输中',
  RECEIVED: '已收货',
  CANCELLED: '已取消',
  OVERDUE: '已逾期',
  CONFLICTED: '数据冲突',
  UNKNOWN: '未知',
};

/** Status of a managed availability policy. */
export const POLICY_STATUS_LABELS: CodeLabels = {
  ACTIVE: '生效中',
  RETIRED: '已停用',
  CANCELLED: '已取消',
  SUPERSEDED: '已被替代',
};
