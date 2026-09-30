/**
 * Chinese text for the store's Ozon promotions: what each promotion is, which products can join
 * or take part, and the estimated unit margin at the promotion's prices.
 */

export const promotionsText = {
  noStore: '当前控制台没有配置店铺，无法查看促销活动。',
  reload: '重新加载',
  none: '还没有采集到 Ozon 活动。采集促销活动的能力登记并核验后，每天随定时采集读取一次。',
  generatedAt: '计算于',
  observedAt: '活动数据采集于',
  floor: (percent: string): string =>
    `判断标准：按活动价的预估利润率不低于 ${percent}（利润率下限）`,
  floorUnset: '未设置利润率下限，只区分盈利和亏损。',
  basis:
    '利润率按与店铺诊断相同的口径估算：Ozon 返回的佣金和最高档物流费、按商品税率扣增值税、映射的单件成本。参加前请在卖家后台核对活动价。',

  period: '活动时间',
  freezes: '冻结于',
  freezesHint: '冻结后只能降价、增加库存，不能退出或涨价。',
  discount: '折扣',
  counts: (candidates: string, participants: string): string =>
    `可参加 ${candidates} 个商品 · 已参加 ${participants} 个`,
  participating: '本店已参加',
  notParticipating: '本店未参加',
  voucher: '需要优惠码',
  targeted: '定向人群',
  itemsNone: '这个活动还没有读到候选或已参加的商品。',
  itemsStale: '候选和已参加商品还没有随这次活动快照更新。',
  summary: (keeps: number, below: number, loses: number, unknown: number): string =>
    `按最高活动价：保住下限 ${String(keeps)} 个 · 低于下限 ${String(below)} 个 · 亏损 ${String(loses)} 个` +
    (unknown > 0 ? ` · 无法测算 ${String(unknown)} 个` : ''),

  columnProduct: '商品',
  columnMembership: '状态',
  columnNow: '现价 / 利润率',
  columnMaxAction: '最高活动价 / 利润率',
  columnRecommended: '推荐活动价 / 利润率',
  columnBoost: '加成',
  columnVerdict: '按最高活动价',
  boostRange: (min: string, max: string): string => `${min}–${max}%`,
  boostCurrent: (current: string): string => `当前 ${current}%`,
  maxBoostPrice: '最大加成价',
  aboveRecommended: '高于推荐价，可能被移出活动',
  quarantined: '商品在隔离期',
  stockRule: (min: string, recommended: string): string =>
    `库存要求：至少 ${min} 件，推荐 ${recommended} 件`,
  breakEven: '保本价',
  missing: '缺少：',

  columnDecision: '人工决定',
  decisionNone: '未记录',
  decisionRecord: '记录决定',
  decisionTitle: '记录你在卖家后台的决定',
  decisionConsequence:
    '平台不会替你参加或退出活动。请先在 Ozon 卖家后台操作，再在这里记下决定、价格和原因，之后可以对照活动效果复盘。',
  decisionConfirm: '记录',
  decisionField: '决定',
  decisionPrice: '在活动里设置的价格',
  decisionPriceHint: '可选。只记录，不会改动 Ozon 上的价格。',
  decisionPriceInvalid: '价格需大于 0，最多 4 位小数',
  decisionPriceNoCurrency: 'Ozon 没有给出这个商品的币种，只能记录决定，不能记录价格。',
  decisionNote: '原因（可选）',
  decisionNotePlaceholder: '例如：按最高活动价低于利润率下限；想借活动换流量',
  decisionRecorded: '决定已记录',
  decidedAt: '记录于',
  decisionsUnavailable: '人工决定记录暂时读不到，活动测算不受影响。',
  summaryMaxAction: '最高活动价',
  summaryRecommended: '推荐活动价',
  summaryNow: '现价',

  aiTitle: 'AI 促销取舍建议',
  aiHint:
    'AI 根据各活动的最高活动价、推荐活动价下的预估利润率，以及商品近 7 天的搜索和订单，给出参加、保留、不参加或退出的建议。只作参考：测算以上面的表格为准，平台不会替你参加或退出活动。',
  aiNone: '还没有生成过促销建议。点击“生成解读”，AI 会按当前活动和测算结果给出取舍建议。',
  aiActions: '建议',
} as const;

/** Whether a product can join or already takes part. */
export const MEMBERSHIP_LABELS: Readonly<Record<string, string>> = {
  CANDIDATE: '可参加',
  PARTICIPANT: '已参加',
};

/** How a participant got into the promotion. */
export const ADD_MODE_LABELS: Readonly<Record<string, string>> = {
  AUTOMATIC: 'Ozon 自动加入',
  SELLER: '卖家加入',
};

/** What joining at the highest action price would mean. */
export const VERDICT_LABELS: Readonly<Record<string, string>> = {
  JOIN_KEEPS_FLOOR: '保住利润率下限',
  JOIN_BELOW_FLOOR: '盈利但低于下限',
  JOIN_LOSES: '亏损',
  UNKNOWN: '无法测算',
};

/** Inputs an estimate could not do without. */
export const MISSING_LABELS: Readonly<Record<string, string>> = {
  FULFILLMENT_SCHEME: '履约方式',
  MARKETPLACE_TARIFFS: 'Ozon 费率',
  UNIT_COST: '单件成本',
  UNIT_COST_CURRENCY: '成本币种一致',
  MAX_ACTION_PRICE: '最高活动价',
};

/** Ozon's words for its discount kinds, as the seller back office shows them. */
export const DISCOUNT_KIND_LABELS: Readonly<Record<string, string>> = {
  PERCENT: '按百分比',
  AMOUNT: '按金额',
};

/** A recorded decision as the table shows it. */
export const DECISION_LABELS: Readonly<Record<string, string>> = {
  JOINED: '已参加',
  SKIPPED: '不参加',
  LEFT: '已退出',
};

/** The choices offered when recording a decision. */
export const DECISION_OPTION_LABELS: Readonly<Record<string, string>> = {
  JOINED: '已在卖家后台参加（或保留参加）',
  SKIPPED: '决定不参加',
  LEFT: '已在卖家后台退出',
};
