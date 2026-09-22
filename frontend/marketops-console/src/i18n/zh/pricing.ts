import type { CodeLabels } from '../labels';
import type { TagColor } from '../../ui/CodeTag';

/**
 * Chinese text for the pricing and profit loop: work list, SKU diagnosis, AI
 * explanation, diagnostic export, recommendation review and price commands.
 *
 * Every backend code family these screens render has a table here, so no raw
 * enum reaches an operator. An unknown code still falls back to the raw value
 * with a marker through `codeLabel`, never to blank.
 */

type Colors = Readonly<Record<string, TagColor>>;

/** Canonical metric codes (MetricCode, definition version 2). */
export const METRIC_LABELS: CodeLabels = {
  IMPRESSIONS: '曝光量',
  CLICKS: '点击量',
  CLICK_THROUGH_RATE: '点击率',
  CONVERSION_RATE: '转化率',
  COMPLETED_UNITS: '完成件数',
  COMPLETED_NET_SALES: '完成净销售额',
  RETAINED_UNITS: '留存件数',
  RETAINED_NET_SALES: '留存净销售额',
  SETTLED_NET_SALES: '已结算净销售额',
  SETTLED_UNITS: '已结算件数',
  RETURN_UNITS: '退货件数',
  RETURN_RATE: '退货率',
  PLATFORM_AVAILABLE_UNITS: '平台可售库存',
  INTERNAL_AVAILABLE_UNITS: '内部可用库存',
  STOCK_COVER_DAYS: '库存可售天数',
  AD_SPEND: '广告花费',
  AD_COST_OF_SALE: '广告销售成本比',
  UNIT_COST: '单位成本',
  PLATFORM_FEES: '平台费用',
  PLATFORM_FEES_PER_UNIT: '单件平台费用',
  RETURN_LOSS: '退货损失',
  RETURN_LOSS_PER_UNIT: '单件退货损失',
  AD_SPEND_PER_UNIT: '单件广告花费',
  VARIABLE_TAX_ESTIMATE: '变动税费估算',
  VARIABLE_TAX_PER_UNIT: '单件变动税费',
  REQUIRED_PROFIT_PER_UNIT: '单件目标利润',
  SAFETY_BUFFER_PER_UNIT: '单件安全缓冲',
  OPERATIONAL_CONTRIBUTION_PROFIT: '运营贡献利润',
  SETTLED_CONTRIBUTION_PROFIT: '已结算贡献利润',
  CONTRIBUTION_MARGIN: '贡献利润率',
  OBSERVED_SELLING_PRICE: '观测售价',
  BREAK_EVEN_PRICE: '保本价',
  MINIMUM_PRICE: '最低价',
  DATA_COMPLETENESS: '数据完整度',
};

/** Deterministic diagnosis rule codes (DiagnosisEngine, rule version 1). */
export const RULE_LABELS: CodeLabels = {
  DATA_BLOCKED: '数据质量阻断',
  NEGATIVE_MARGIN: '贡献利润为负',
  STOCKOUT_RISK: '断货风险',
  HIGH_RETURN: '退货率过高',
  LOW_IMPRESSION: '曝光不足',
  LOW_CLICK_THROUGH: '点击率偏低',
  LOW_CONVERSION: '转化率偏低',
  ADVERTISING_INEFFICIENT: '广告效率低',
  PRICE_BELOW_MINIMUM: '售价低于最低价',
};

/** What a rule concluded. */
export const FINDING_OUTCOME_LABELS: CodeLabels = {
  TRIGGERED: '已触发',
  CLEAR: '未发现问题',
  DECLINED: '无法判断',
};

export const FINDING_OUTCOME_COLORS: Colors = {
  TRIGGERED: 'error',
  CLEAR: 'success',
  DECLINED: 'warning',
};

/** How serious a triggered rule is. */
export const SEVERITY_LABELS: CodeLabels = {
  INFO: '提示',
  WARNING: '警告',
  CRITICAL: '严重',
};

export const SEVERITY_COLORS: Colors = {
  INFO: 'processing',
  WARNING: 'warning',
  CRITICAL: 'error',
};

/** Why a rule could not answer, plus the coded values a finding detail may carry. */
export const DIAGNOSIS_CODE_LABELS: CodeLabels = {
  ...METRIC_LABELS,
  BLOCKED_BY_EARLIER_RULE: '被前置规则阻断',
  REQUIRED_METRIC_UNAVAILABLE: '所需指标缺失',
  REQUIRED_METRIC_UNDEFINED: '所需指标无定义',
  MAPPING_UNRESOLVED: '商品映射未解决',
  THRESHOLD_NOT_CONFIGURED: '未配置阈值',
  INSUFFICIENT_SAMPLE: '样本量不足',
  NO_PLATFORM_STOCK: '平台无库存',
  COVER_NOT_COMPUTABLE: '无法计算可售天数',
};

/** Names of the fields a rule finding records. */
export const FINDING_DETAIL_LABELS: CodeLabels = {
  advertisingCostOfSale: '广告销售成本比',
  breakEvenPrice: '保本价',
  clickThroughRate: '点击率',
  clicks: '点击量',
  completedUnits: '完成件数',
  condition: '情形',
  conflictedMetrics: '冲突指标',
  conversionRate: '转化率',
  currencyCode: '币种',
  dataCompleteness: '数据完整度',
  impressions: '曝光量',
  internalAvailableUnits: '内部可用库存',
  metric: '指标',
  minimumImpressions: '最低曝光量',
  minimumReach: '最低触达',
  minimumUnits: '最低件数',
  observedSellingPrice: '观测售价',
  operationalContributionProfit: '运营贡献利润',
  platformAvailableUnits: '平台可售库存',
  reason: '原因',
  returnRate: '退货率',
  staleMetrics: '过期指标',
  stockCoverDays: '库存可售天数',
  stockCoverDaysFloor: '可售天数下限',
  threshold: '阈值',
};

/** Kinds of typed input edge behind a stored metric. */
export const EVIDENCE_REF_KIND_LABELS: CodeLabels = {
  FACT_PROVENANCE: '源数据记录',
  COST_VERSION: '成本版本',
  FINANCE_INPUT_VERSION: '财务输入版本',
  METRIC_VALUE: '上游指标',
  LISTING_MAPPING: '商品映射',
  ECONOMICS_PROFILE: '经营参数档案',
  ECONOMICS_COMPONENT: '经营参数组成项',
};

/** Where a source record came from. */
export const SOURCE_KIND_LABELS: CodeLabels = {
  MARKETPLACE_RAW: '平台原始数据',
  INTERNAL_IMPORT: '内部导入',
  MANUAL_ENTRY: '人工录入',
};

/** AI invocation states. */
export const AI_STATE_LABELS: CodeLabels = {
  PREPARED: '已准备',
  DISPATCHED: '已发送，等待结果',
  SUCCEEDED: '已完成',
  OUTPUT_REJECTED: '输出未通过校验',
  PARTIAL_OUTPUT_REJECTED: '部分输出未通过校验',
  PROVIDER_FAILED: '模型服务失败',
  PROVIDER_OUTCOME_UNKNOWN: '模型服务结果未知',
  REFUSED: '未调用模型',
};

export const AI_STATE_COLORS: Colors = {
  PREPARED: 'processing',
  DISPATCHED: 'processing',
  SUCCEEDED: 'success',
  OUTPUT_REJECTED: 'error',
  PARTIAL_OUTPUT_REJECTED: 'warning',
  PROVIDER_FAILED: 'error',
  PROVIDER_OUTCOME_UNKNOWN: 'warning',
  REFUSED: 'error',
};

/** Why a model claim was rejected by output validation. */
export const AI_REJECTION_LABELS: CodeLabels = {
  SCHEMA_INVALID: '格式不符合约定',
  UNKNOWN_FIELD: '包含未知字段',
  EVIDENCE_REFERENCE_UNRESOLVED: '引用的证据不存在',
  EVIDENCE_REFERENCE_MISSING: '未引用证据',
  METRIC_NOT_RECOGNISED: '指标无法识别',
  DERIVED_CALCULATION_NOT_PRODUCTIZED: '包含未产品化的推导计算',
  CAPABILITY_NOT_RECOGNISED: '能力无法识别',
  STATEMENT_TOO_LONG: '陈述过长',
  INSTRUCTION_LIKE_CONTENT: '包含类似指令的内容',
  SECRET_LIKE_CONTENT: '包含疑似密钥的内容',
  LISTING_ASSISTANCE_ACTION_OUT_OF_SCOPE: '超出商品辅助的操作范围',
};

/** Why an AI explanation is unavailable: before, during or after the model call. */
export const AI_FAILURE_LABELS: CodeLabels = {
  ...AI_STATE_LABELS,
  ...AI_REJECTION_LABELS,
  NOTHING_TO_EXPLAIN: '没有可解释的内容',
  NO_ELIGIBLE_PROVIDER: '尚未配置可用的模型服务',
  LISTING_INPUT_EXCEEDS_GATEWAY_BOUND: '输入内容超出上限',
  PROVIDER_NOT_ELIGIBLE: '模型服务未核验或已停用',
  CREDENTIAL_UNRESOLVABLE: '找不到模型服务的密钥',
  CREDENTIAL_MALFORMED: '模型服务密钥格式不正确',
  DESTINATION_POLICY_REFUSED: '出站策略拒绝了调用（白名单、域名解析或请求模板）',
  TRANSPORT_FAILED: '与模型服务的连接或传输失败',
  INTERRUPTED: '调用被中断',
  PROVIDER_CALL_FAILED: '调用模型服务时出错',
  PROVIDER_AUTH_REJECTED: '模型服务拒绝了密钥',
  PROVIDER_THROTTLED: '模型服务限流，请稍后再试',
  PROVIDER_REQUEST_REJECTED: '模型服务拒绝了请求内容',
  PROVIDER_UNAVAILABLE: '模型服务暂时不可用',
  PROVIDER_REFUSED: '模型服务拒绝请求',
  RESPONSE_DEADLINE_EXCEEDED: '模型服务响应超时',
  RESPONSE_INCOMPLETE: '模型返回不完整',
  RESPONSE_LIMIT_EXCEEDED: '模型返回超出大小上限',
  RESPONSE_NOT_READABLE: '模型返回无法解析',
  ANSWER_NOT_AT_RECORDED_POINTER: '模型返回中找不到答案',
  NO_CLAIM_PRODUCED: '模型没有给出任何结论',
  WORKER_INTERRUPTED_OR_DEADLINE_EXPIRED: '调用超时或中断，结果未知',
};

/** Colours of the four claim kinds. */
export const AI_CLAIM_KIND_COLORS: Readonly<
  Record<'FACT' | 'INFERENCE' | 'RECOMMENDATION' | 'UNKNOWN', TagColor>
> = {
  FACT: 'blue',
  INFERENCE: 'purple',
  RECOMMENDATION: 'cyan',
  UNKNOWN: 'default',
};

/** Diagnostic export states. */
export const EXPORT_STATE_LABELS: CodeLabels = {
  QUEUED: '排队中',
  RUNNING: '生成中',
  SUCCEEDED: '已就绪',
  FAILED: '失败',
  EXPIRED: '已过期',
};

export const EXPORT_STATE_COLORS: Colors = {
  QUEUED: 'warning',
  RUNNING: 'processing',
  SUCCEEDED: 'success',
  FAILED: 'error',
  EXPIRED: 'default',
};

export const EXPORT_FAILURE_LABELS: CodeLabels = {
  AUTHORIZATION_REVOKED: '授权已被撤销',
  LIMIT_EXCEEDED: '超出导出上限',
  STORAGE_UNAVAILABLE: '存储服务不可用',
  DATABASE_UNAVAILABLE: '数据库不可用',
  INVALID_SNAPSHOT: '数据快照无效',
  DEADLINE_EXCEEDED: '超出处理时限',
  RETRY_EXHAUSTED: '重试次数已用尽',
};

export const EXPORT_WINDOW_LABELS: CodeLabels = {
  D7: '近 7 天',
  D14: '近 14 天',
  D30: '近 30 天',
};

/** Recommendation lifecycle states. */
export const RECOMMENDATION_STATE_LABELS: CodeLabels = {
  DRAFT: '草稿',
  VALIDATED: '已校验',
  READY_FOR_REVIEW: '待审核',
  TASK_ONLY: '仅作任务',
  APPROVED: '已批准',
  POLICY_AUTHORIZED: '已按常设授权放行',
  REJECTED: '已驳回',
  EXPIRED: '已过期',
  CANCELLED: '已取消',
  COMMAND_CREATED: '已创建指令',
  EXECUTION_TRACKING: '执行跟踪中',
  OUTCOME_OBSERVATION: '效果观察中',
  CLOSED: '已关闭',
};

export const RECOMMENDATION_STATE_COLORS: Colors = {
  DRAFT: 'default',
  VALIDATED: 'default',
  READY_FOR_REVIEW: 'warning',
  TASK_ONLY: 'default',
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

export const ACTION_KIND_LABELS: CodeLabels = {
  PRICE_CHANGE: '调价',
  AD_BID_CHANGE: '调整广告出价',
  RESOLVE_MAPPING: '处理商品映射',
  RESTOCK_REVIEW: '补货评估',
  LISTING_CONTENT_REVIEW: '商品内容评估',
  ADVERTISING_REVIEW: '广告评估',
  COST_DATA_REVIEW: '成本数据核对',
  LISTING_DESCRIPTION_CHANGE: '修改商品描述',
  LISTING_PROMOTION_ACTION: '商品促销操作',
};

export const ORIGIN_LABELS: CodeLabels = {
  DETERMINISTIC: '规则生成',
  AI_ASSISTED: 'AI 辅助生成',
};

export const RISK_LABELS: CodeLabels = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  UNKNOWN: '未知',
};

export const RISK_COLORS: Colors = {
  LOW: 'success',
  MEDIUM: 'warning',
  HIGH: 'error',
};

/** Names of the parameters a price proposal carries. */
export const PARAMETER_LABELS: CodeLabels = {
  targetPrice: '目标价格',
  fulfillmentModeCode: '履约模式',
  currencyCode: '币种',
};

export const FULFILLMENT_MODE_LABELS: CodeLabels = {
  MARKETPLACE_FULFILLED: '平台履约',
  SELLER_FULFILLED: '卖家履约',
  UNKNOWN: '未知',
};

/** Deterministic guardrail refusal reasons (GuardrailReason). */
export const GUARDRAIL_REASON_LABELS: CodeLabels = {
  NO_POLICY_IN_FORCE: '没有生效的定价策略',
  POLICY_LIMIT_NOT_CONFIGURED: '策略限额未配置',
  DATA_COMPLETENESS_BELOW_MINIMUM: '数据完整度低于下限',
  INPUT_TOO_STALE: '输入数据过期',
  INPUT_FRESHNESS_UNAVAILABLE: '无法确认输入数据时效',
  REQUIRED_METRIC_UNAVAILABLE: '所需指标缺失',
  METRIC_CONFIDENCE_INSUFFICIENT: '指标可信度不足',
  ECONOMICS_PROFILE_MISSING: '缺少经营参数档案',
  ECONOMICS_PROFILE_AMBIGUOUS: '经营参数档案不唯一',
  ECONOMICS_PROFILE_EXPIRED: '经营参数档案已过期',
  ECONOMICS_PROFILE_UNVERIFIED: '经营参数档案未核实',
  PROJECTED_ECONOMICS_UNAVAILABLE: '无法测算调价后的经营结果',
  CURRENCY_MISMATCH: '币种不一致',
  MARGIN_BELOW_MINIMUM: '利润率低于下限',
  UNIT_PROFIT_BELOW_MINIMUM: '单件利润低于下限',
  BELOW_BREAK_EVEN: '低于保本价',
  BELOW_MINIMUM_PRICE: '低于最低价',
  SINGLE_CHANGE_TOO_LARGE: '单次调价幅度过大',
  DAILY_CHANGE_EXCEEDED: '超出当日调价幅度',
  COOLDOWN_ACTIVE: '仍在调价冷却期内',
  INVENTORY_BELOW_MINIMUM: '库存低于下限',
  INVENTORY_EVIDENCE_UNAVAILABLE: '缺少库存证据',
  MAPPING_UNRESOLVED: '商品映射未解决',
  MAPPING_CONFLICT_OPEN: '存在未解决的映射冲突',
  DIAGNOSIS_BLOCKS_EXECUTION: '诊断结果阻断执行',
  CHANGE_EXCEEDS_POLICY_AUTHORIZATION: '超出常设授权范围',
  ENTITY_VERSION_CHANGED: '数据已变化，建议需重新评估',
  RECOMMENDATION_EXPIRED: '建议已过期',
  BID_MOVED_SINCE_CANDIDATE: '出价在生成建议后已变动',
  CURRENT_BID_NOT_OBSERVED: '未观测到当前出价',
  CONTROL_GRANULARITY_UNPROVEN: '控制粒度未经验证',
  RESERVATION_NOT_HELD: '未持有预留额度',
  AD_POLICY_BUNDLE_UNRESOLVED: '广告策略包未确定',
  EXPOSURE_ENVELOPE_EXHAUSTED: '风险敞口额度已用尽',
  ABOVE_MAX_CPC: '高于最高单次点击出价',
  MAX_CPC_UNAVAILABLE: '缺少最高单次点击出价',
  ADVERTISING_CASE_BLOCKED: '广告事项被阻断',
  NO_CHANGE_PROPOSED: '没有提出变更',
  APPROVAL_LEASE_POLICY_ABSENT: '缺少审批租约策略',
  CALIBRATION_UNRESOLVED: '校准未完成',
  AFFECTED_SET_INCOMPLETE: '受影响范围不完整',
  CURRENT_TEXT_MOVED: '当前文本已变化',
  LISTING_HEALTH_NECESSARY_FAILED: '商品健康必要检查未通过',
  SCOPE_CONTAINED: '范围受限',
  REVIEW_MISSING: '缺少审阅',
  TEXT_LENGTH_OUT_OF_BOUNDS: '文本长度超出范围',
  KIZ_MARKED_UNDECLARED: '标识码商品未申报',
  MATERIALITY_UNRESOLVED: '重要性未确定',
  LISTING_ACTION_BLOCKED: '商品操作被阻断',
};

/** Why the platform write gate is closed for a price command. */
export const GATE_REASON_LABELS: CodeLabels = {
  COMMAND_NOT_FOUND: '指令不存在',
  COMMAND_AUTHORITY_MISMATCH: '指令与授权不一致',
  CAPABILITY_NOT_VERIFIED: '平台调价能力尚未验证',
  CAPABILITY_NOT_AVAILABLE_FOR_STORE: '该店铺不具备调价能力',
  CAPABILITY_SWITCH_DISABLED: '调价能力开关已关闭',
  GLOBAL_SWITCH_DISABLED: '全局写入开关已关闭',
  SCOPED_SWITCH_DISABLED: '范围写入开关已关闭',
  ENTITY_NOT_ALLOWLISTED: '商品不在允许写入名单中',
  AUTHORIZATION_INVALID_OR_EXPIRED: '授权无效或已过期',
  RECOMMENDATION_STALE: '建议已过时',
  MAPPING_UNRESOLVED: '商品映射未解决',
  MAPPING_CONFLICT_OPEN: '存在未解决的映射冲突',
  GUARDRAIL_NOT_PASSED: '护栏校验未通过',
};

/** Price command states. */
export const COMMAND_STATE_LABELS: CodeLabels = {
  PENDING: '等待执行',
  LEASED: '已分配执行',
  EXECUTING: '执行中',
  PLATFORM_PENDING: '平台处理中',
  READBACK_PENDING: '等待回读',
  SUCCEEDED: '已确认生效',
  RETRY_WAIT: '等待重试',
  UNKNOWN_REQUIRES_READBACK: '结果未知，需回读',
  READBACK_MISMATCH: '回读不一致',
  MANUAL_RESOLUTION: '人工处理中',
  FAILED_FINAL: '最终失败',
  COMPENSATION_PENDING: '恢复原价中',
  COMPENSATED: '已恢复原价',
  COMPENSATION_FAILED: '恢复原价失败',
};

export const COMMAND_STATE_COLORS: Colors = {
  PENDING: 'warning',
  LEASED: 'processing',
  EXECUTING: 'processing',
  PLATFORM_PENDING: 'processing',
  READBACK_PENDING: 'processing',
  SUCCEEDED: 'success',
  RETRY_WAIT: 'warning',
  UNKNOWN_REQUIRES_READBACK: 'warning',
  READBACK_MISMATCH: 'error',
  MANUAL_RESOLUTION: 'warning',
  FAILED_FINAL: 'error',
  COMPENSATION_PENDING: 'processing',
  COMPENSATED: 'default',
  COMPENSATION_FAILED: 'error',
};

/** One-sentence meaning of each command state. */
export const COMMAND_STATE_DESCRIPTIONS: CodeLabels = {
  PENDING: '等待执行器处理，尚未调用平台。',
  LEASED: '执行器正在调用平台。',
  EXECUTING: '执行器正在调用平台。',
  PLATFORM_PENDING: '平台已接受请求，仍在处理。',
  READBACK_PENDING: '平台已回复，正在回读平台当前价格。',
  SUCCEEDED: '回读确认平台价格已是目标价，变更已生效。',
  RETRY_WAIT: '出现可重试的情况，稍后将再次调用。',
  UNKNOWN_REQUIRES_READBACK: '结果无法判定：既不能确认生效，也不能排除已生效。',
  READBACK_MISMATCH: '回读到的价格与目标价不一致。',
  MANUAL_RESOLUTION: '已转为人工处理，不再自动执行。',
  FAILED_FINAL: '变更未生效，且不会再重试。',
  COMPENSATION_PENDING: '已授权恢复原价，正在执行。',
  COMPENSATED: '已恢复原价并回读确认。',
  COMPENSATION_FAILED: '恢复原价未能完成，需要人工处理。',
};

/** Why a platform call was made. */
export const ATTEMPT_PURPOSE_LABELS: CodeLabels = {
  APPLY: '提交调价',
  STATUS_ENQUIRY: '查询处理状态',
  READBACK: '回读价格',
  RESTORE: '恢复原价',
};

/** How a platform call ended. */
export const ATTEMPT_OUTCOME_LABELS: CodeLabels = {
  IN_FLIGHT: '进行中',
  ACCEPTED: '已接受',
  REJECTED: '被拒绝',
  RETRIABLE_ERROR: '可重试错误',
  TIMEOUT: '超时',
  UNKNOWN_STATE: '结果未知',
};

export const ATTEMPT_OUTCOME_COLORS: Colors = {
  IN_FLIGHT: 'processing',
  ACCEPTED: 'success',
  REJECTED: 'error',
  RETRIABLE_ERROR: 'warning',
  TIMEOUT: 'warning',
  UNKNOWN_STATE: 'warning',
};

/** What a readback observed. */
export const READBACK_MATCH_LABELS: CodeLabels = {
  MATCHES_TARGET: '与目标价一致',
  MATCHES_PRIOR: '仍是原价',
  DIFFERENT: '既非目标价也非原价',
  UNREADABLE: '无法读取平台回复',
};

export const READBACK_MATCH_COLORS: Colors = {
  MATCHES_TARGET: 'success',
  MATCHES_PRIOR: 'warning',
  DIFFERENT: 'error',
  UNREADABLE: 'warning',
};

/** Marketplace platforms, named as the platforms name themselves. */
export const PLATFORM_LABELS: CodeLabels = {
  OZON: 'Ozon',
  WILDBERRIES: 'Wildberries',
};

/** Command and attempt failure codes the console knows; others show raw with a marker. */
export const COMMAND_FAILURE_LABELS: CodeLabels = {
  FAILED_FINAL: '最终失败',
  RESPONSE_INCOMPLETE: '平台回复不完整',
  UNEXPECTED_CONTENT_TYPE: '平台回复格式异常',
  PROVIDER_RESPONSE: '平台返回错误',
};

/** How much weight the model puts on one of its own statements. */
export const AI_CONFIDENCE_LABELS: CodeLabels = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
};

/** Domains the diagnosis groups its measured values into. */
export const METRIC_GROUP_LABELS = {
  profit: '利润与价格',
  funnel: '流量与转化',
  sales: '销售',
  returns: '退货',
  inventory: '库存',
  advertising: '广告',
  cost: '成本与费用',
  other: '数据完整度与其他',
} as const;

/** Names of the values a guardrail verdict records beside its reasons. */
export const GUARDRAIL_DETAIL_LABELS: CodeLabels = {
  policyVersion: '策略版本',
  lifecycleObjective: '生命周期目标',
  missingLimit: '未配置的限额',
  changeRate: '调价幅度',
  breakEvenPrice: '保本价',
  minimumPrice: '最低价',
  currentPrice: '当前价格',
  proposedPrice: '建议价格',
  dataCompleteness: '数据完整度',
  unavailableMetrics: '缺失指标',
  lowConfidenceMetrics: '可信度不足的指标',
  freshnessUnavailableFeeds: '无法确认时效的数据源',
  inputAgeSeconds: '最旧输入的时长',
  projectedUnitProfit: '调价后单件利润',
  projectedMargin: '调价后利润率',
  cumulativeDailyChangeRate: '当日累计调价幅度',
  secondsSinceLastChange: '距上次调价',
  availableUnits: '可售库存',
  authorizationMaxChangeRate: '常设授权幅度上限',
  expectedCurrency: '期望币种',
  currencyMismatches: '币种不一致的数据',
  economicsResolution: '经营参数档案解析结果',
  projectionBlockingReasons: '无法测算的原因',
};

/**
 * What to do about each guardrail refusal, and who usually does it.
 *
 * The verdict itself is deterministic; these sentences only point the reviewer
 * at the fix so a refused proposal is not retried unchanged.
 */
export const GUARDRAIL_REASON_HINTS: CodeLabels = {
  NO_POLICY_IN_FORCE: '由定价负责人发布该店铺的定价策略后再审核。',
  POLICY_LIMIT_NOT_CONFIGURED: '由定价负责人在定价策略中补齐缺失的限额。',
  DATA_COMPLETENESS_BELOW_MINIMUM: '等待数据同步补齐，或由数据负责人核对缺失的报表后重新检查。',
  INPUT_TOO_STALE: '等待下一次平台数据同步后重新检查；持续过期请联系数据负责人。',
  INPUT_FRESHNESS_UNAVAILABLE: '由数据负责人确认相关数据源的同步状态。',
  REQUIRED_METRIC_UNAVAILABLE: '补齐缺失指标的来源数据（成本、费用或平台报表）后重新检查。',
  METRIC_CONFIDENCE_INSUFFICIENT: '等待结算，或由数据负责人处理存疑指标后重新检查。',
  ECONOMICS_PROFILE_MISSING: '由财务或运营负责人为该商品建立经营参数档案。',
  ECONOMICS_PROFILE_AMBIGUOUS: '由财务或运营负责人合并或停用重复的经营参数档案。',
  ECONOMICS_PROFILE_EXPIRED: '由财务或运营负责人更新经营参数档案的有效期。',
  ECONOMICS_PROFILE_UNVERIFIED: '由财务负责人核实经营参数档案。',
  PROJECTED_ECONOMICS_UNAVAILABLE: '补齐成本与费用数据，使调价后的利润可以测算。',
  CURRENCY_MISMATCH: '由数据负责人核对各数据源的币种。',
  MARGIN_BELOW_MINIMUM: '建议价过低：驳回此建议，或由定价负责人评估策略下限。',
  UNIT_PROFIT_BELOW_MINIMUM: '建议价过低：驳回此建议，或由定价负责人评估策略下限。',
  BELOW_BREAK_EVEN: '建议价低于保本价，通常应驳回。',
  BELOW_MINIMUM_PRICE: '建议价低于最低价，通常应驳回。',
  SINGLE_CHANGE_TOO_LARGE: '幅度超过策略上限：驳回后分步调价，或由定价负责人调整上限。',
  DAILY_CHANGE_EXCEEDED: '当日累计调价已达上限，次日再处理。',
  COOLDOWN_ACTIVE: '仍在冷却期内，冷却结束后重新检查。',
  INVENTORY_BELOW_MINIMUM: '库存过低，先处理补货再考虑调价。',
  INVENTORY_EVIDENCE_UNAVAILABLE: '由数据负责人确认库存数据同步。',
  MAPPING_UNRESOLVED: '先在商品映射中解决该商品的对应关系。',
  MAPPING_CONFLICT_OPEN: '先解决未结的商品映射冲突。',
  DIAGNOSIS_BLOCKS_EXECUTION: '先处理诊断页上阻断平台写入的规则结论。',
  CHANGE_EXCEEDS_POLICY_AUTHORIZATION: '超出常设授权范围：改用人工批准，或由定价负责人调整授权。',
  ENTITY_VERSION_CHANGED: '数据已变化：重新加载建议后再检查。',
  RECOMMENDATION_EXPIRED: '建议已过期，等待系统生成新的建议。',
};

/** Words of the diagnosis page. */
export const diagnosisText = {
  aiButton: 'AI 解释（仅供参考）',
  back: '返回',
  recommendations: '调价建议',
  recommendationsEmpty: '该商品暂无待处理的建议',
  review: '审核',
  view: '查看',
  metrics: '平台测量值',
  metricsHelp:
    '平台确定性计算的指标，是官方口径。存疑、估算、过期或冲突的数值会明确标注；被规则结论引用的分组默认展开，其余分组收起时标题仍显示缺失与存疑数量。',
  metricsAbsentNote: '缺失显示为“—”，不是 0。',
  findings: '规则结论',
  viewEvidence: '查看证据',
  evidenceTitle: '指标证据',
  clearHidden: (n: number) => `另有 ${String(n)} 条规则未发现问题`,
  clearShown: '收起未发现问题的规则',
  blockingAlert: (n: number) => `${String(n)} 条规则结论阻断平台写入，当前不能调价`,
  declinedAlert: (n: number) => `${String(n)} 条规则因数据不足等原因无法判断，不代表没有问题`,
  noFindings: '暂无规则结论',
  noMetrics: '暂无指标',
  noProblems: '所有规则均未发现问题',
  flagMissing: (n: number) => `${String(n)} 项缺失`,
  flagEstimated: (n: number) => `${String(n)} 项估算`,
  flagStale: (n: number) => `${String(n)} 项过期`,
  flagConflicted: (n: number) => `${String(n)} 项冲突`,
  flagDoubtful: (n: number) => `${String(n)} 项存疑`,
  itemCount: (n: number) => `${String(n)} 项`,
} as const;

/** Words of the AI explanation drawer. */
export const aiText = {
  title: 'AI 解释 · 仅供参考',
  notice: 'AI 生成，仅供参考，不作为官方口径',
  noticeDetail: 'AI 结论不能批准变更，也不会创建平台指令；以诊断页的规则结论与测量值为准。',
  generate: '生成新解释',
  latest: '查看最新结果',
  none: '尚无已记录的解释。生成解释会调用模型服务。',
  lastRecorded: '最近一次记录的解释（结果中未包含生成时间）',
  justGenerated: '本次请求返回于',
  waiting: (seconds: number) => `通常约 30 秒 · 已等待 ${String(seconds)} 秒`,
  stopped:
    '已停止等待。请求无法撤回，可能仍会在后台完成并被记录，系统不会自动重试；可点“查看最新结果”重新读取。',
  failedNote: '请求可能已被记录，系统不会自动重试。',
  partial: '部分解释：有些 AI 陈述未通过校验，已通过的陈述仍仅供参考',
  pendingState: '调用尚在处理中，未假定任何结果',
  validated: '以下陈述均已通过输出校验，但仍是 AI 的陈述。',
  unavailable: '暂无解释',
  unaffected: '官方测量值与规则结论不受影响。',
  invocation: '调用编号',
  schemaVersion: '输出格式版本',
} as const;

/** Words of the review drawer. */
export const reviewText = {
  title: '审核建议',
  recheck: '重新检查',
  check: '检查调价影响',
  checking: '正在检查调价影响…',
  impact: '调价影响预估',
  verdictPassed: '规则校验通过',
  verdictFailed: '规则校验未通过，以下列出全部原因，可一次性处理',
  policyVersion: (v: number | null) => `策略版本 ${v === null ? '未记录' : String(v)}`,
  actual: '实际',
  threshold: '阈值',
  unknown: '未知',
  approve: '批准调价',
  policyApprove: '按常设授权批准',
  policyTitle: '按常设授权批准调价',
  reject: '驳回',
  rejectTitle: '驳回此建议',
  rejectConsequence: '驳回后该建议将关闭，不可撤销，也不会调用平台。',
  createCommand: '创建已授权指令',
  createTitle: '创建已授权的调价指令',
  openCommand: '打开已有指令',
  reload: '重新加载建议',
  consequence: '确认后将记录你的决定并创建调价指令，指令经写入闸门检查后才会调用平台。',
  createConsequence: '确认后将创建调价指令，指令经写入闸门检查后才会调用平台。',
  confirmPrice: (price: string) => `确认改价为 ${price}`,
  confirmPolicyPrice: (price: string) => `确认按常设授权改价为 ${price}`,
  decisionReason: '决策理由',
  createNote: '备注（可选，不会保存；授权理由已在批准时记录）',
  commandCreated: '已创建调价指令',
  commandFailed: '决定已记录，但创建指令失败；可稍后点“创建已授权指令”重试。',
  rejected: '已驳回该建议',
  notReviewable: (state: string) => `当前状态为“${state}”，不可审批`,
  previewMissing: '请先完成调价影响检查',
  previewRunning: '正在检查调价影响',
  verdictBlocked: '规则校验未通过，不能批准',
  busy: '正在处理，请稍候',
  alreadyAuthorized: '建议已获授权，不能再驳回',
  commandExistsNoReject: '已创建指令，不能再驳回',
  finished: '建议已结束，不能再驳回',
  noAction: '当前状态没有可执行的操作',
  subjectMismatch: '建议不属于当前商品',
  policyScope: (version: number | null, maxRate: string) =>
    `适用常设授权策略版本 ${version === null ? '未记录' : String(version)}，授权幅度上限 ${maxRate}。`,
  platform: '平台',
  subject: '商品',
  priceChange: '价格',
  changeRate: '变动幅度',
  projectedMargin: '调价后利润率',
  breakEven: '保本价',
} as const;
