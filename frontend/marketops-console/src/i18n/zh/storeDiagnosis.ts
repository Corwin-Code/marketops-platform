/**
 * Chinese text for the store diagnosis: why a store's listings do not sell.
 */

export const storeDiagnosisText = {
  title: '店铺诊断',
  summaryTitle: '概览',
  productsTitle: '商品',
  generatedAt: '生成时间',
  noStore: '当前控制台没有配置店铺，无法生成诊断。',
  empty: '这个店铺还没有采集到任何商品。',
  emptyFiltered: '没有符合筛选条件的商品。',

  tileProducts: '商品数',
  tileNotSellable: '买家不可见',
  tileNotSellableHint: '平台报告为不可售（被隐藏或已删除）的商品；未采集到可见性的另计。',
  tileWithoutStock: '无库存',
  tileWithoutStockHint: '最新库存快照里可售数量为 0 的商品，买家无法下单。',
  tilePriceRed: '价格指数 RED',
  tilePriceRedHint: 'Ozon 判定价格明显高于竞品（невыгодный）。YELLOW 为一般。',
  tileWithOrders: '近期有下单',
  tileSearchDemand: '有搜索需求',
  tileSearchDemandHint:
    '统计期间内有买家搜索过的商品。数据来自 Ozon 搜索分析（C 级 · 平台分析）：免费版只能查最近一个月，不含当天。',
  tileSearchNoOrders: '有搜索无下单',
  tileSearchNoOrdersHint:
    '统计期间内有买家搜索、但近期下单窗口里没有下单记录的商品：需求存在，却没有成交。',
  tileContent: '内容评分均值',
  tileContentHint: 'Ozon 给出的商品卡内容评分（0–100）。这里只展示分数，不设“偏低”阈值。',
  unknownSellability: (count: number): string => `另有 ${String(count)} 个未采集到可见性`,
  yellowCount: (count: number): string => `另有 ${String(count)} 个 YELLOW`,
  ordersWindow: (days: number, covered: number): string =>
    `近 ${String(days)} 天（有记录 ${String(covered)} 天）`,
  ordersNoWindow: '尚无下单数据',
  searchPeriod: (from: string, to: string): string => `${from} 至 ${to}`,
  searchNoPeriod: '尚无搜索数据',
  ratedCount: (count: number): string => `${String(count)} 个商品有评分`,

  filterLabel: '筛选',
  filterAll: '全部',
  filterProblems: '只看有问题的',
  filterNotSellable: '不可见',
  filterWithoutStock: '无库存',
  filterPriceRed: '价格 RED',
  filterWithOrders: '有下单',
  filterSearchNoOrders: '有搜索无下单',
  searchLabel: '按名称或货号搜索',
  searchPlaceholder: '名称 / 货号 / 商品 ID',
  refreshLabel: '重新加载诊断',

  columnProduct: '商品',
  columnVisibility: '可见性',
  columnStock: '可售库存',
  columnPrice: '价格（含促销）',
  columnCompetitiveness: '价格竞争力',
  columnContent: '内容评分',
  columnSearch: '搜索人数',
  columnSearchHint:
    '统计期间内搜索过该商品的买家人数（Ozon 去重统计，C 级 · 平台分析）。商品没有出现在 Ozon 的搜索分析结果里时显示“无记录”，不等于 0。',
  columnOrders: '近期下单',
  offerId: '货号',
  productId: 'Ozon 商品 ID',
  untitled: '（无标题）',
  noRecord: '无记录',
  notObserved: '未采集',

  sellableYes: '可见',
  sellableNo: '不可见',
  sellableUnknown: '未知',

  premium: (percent: string): string => `比 Ozon 最低竞品价贵 ${percent}`,
  cheaper: (percent: string): string => `比 Ozon 最低竞品价便宜 ${percent}`,
  noCompetitor: '无可比竞品',
  confidenceC: 'C 级 · 平台分析',
  confidenceCHint:
    '价格竞争力是 Ozon 自己匹配竞品得出的判断，属于平台分析数据（C 级）：用于诊断和趋势，不会驱动自动调价，也不进入利润计算。Ozon 匹配的竞品可能与本商品并不完全相同，调价前请人工核对。',

  drawerTitle: '商品信号',
  sectionIdentity: '商品',
  sectionVisibility: '可见性',
  sectionStock: '库存',
  sectionPrice: '价格与竞争力',
  sectionContent: '内容评分',
  sectionSearch: '搜索需求',
  sectionOrders: '下单',
  observedAt: '数据时间',
  nativeStatus: 'Ozon 状态',
  blockedReason: '隐藏原因',
  available: '可售',
  reserved: '已预留',
  fulfillmentModes: '履约方式',
  sellingPrice: '价格（不含促销）',
  discountPrice: '含卖家促销价',
  listPrice: '划线价',
  platformCompetitor: 'Ozon 站内竞品最低价',
  externalCompetitor: '其他平台竞品最低价',
  priceIndex: 'Ozon 价格指数',
  premiumBasis: '比较基准：含卖家促销价（没有时用不含促销价），且与竞品价币种相同时才计算。',
  rating: '评分',
  searchUsers: '搜索人数',
  searchRevenue: '搜索带来的销售额',
  searchPeriodLabel: '统计期间（UTC）',
  searchTerms: '主要搜索词',
  searchTerm: '搜索词',
  termSearchUsers: '搜索人数',
  termOrders: '下单数',
  noTerms: '没有搜索词记录',
  searchHint:
    '搜索词是 Ozon 返回的原文（俄语），不做翻译；每个商品列出搜索人数最多的 5 个。排名位置、曝光和转化需要 Premium 订阅，目前拿不到。',
  orderedUnits: '下单件数',
  daysWithRecords: '有记录的天数',
  ordersHint:
    'Ozon 只为有动静的商品返回分析数据；某天没有记录不等于下单 0，所以没有任何记录时显示“无记录”。',
  sourceNote: '所有数据来自 Ozon 官方接口，按采集时间取每类信号的最新一条事实。',
  conclusionsTitle: '诊断结论',
  conclusionsBasis: '近 7 天数据，截至',
  conclusionsCalculatedAt: '计算于',
  conclusionsNone: '还没有诊断计算结果，点“重新计算诊断”生成。',
  conclusionsEmpty: '最近一次计算没有发现问题。',
  conclusionsNote:
    '店铺还没有销售和结算数据，按已实现利润判断的规则（负利润、库存可售天数、转化率等）暂不适用；下面的结论依据预估单件经济、搜索、价格和内容这些不依赖销售的数据。价格竞争力和搜索属于平台分析（C 级），只用于诊断，不会自动改价。',
  recalculate: '重新计算诊断',
  recalculateDone: (count: number): string => `已重新计算 ${String(count)} 个商品`,
  recalculateHint: '按最近 7 天的数据重新计算指标和诊断结论；计算窗口截至上一个整点。',
  affectedProducts: (count: number): string => `${String(count)} 个商品`,
  nextStep: '下一步：',
  showAffected: '查看商品',
  clearRuleFilter: '显示全部商品',
  ruleFilterActive: (title: string): string => `只看：${title}`,
  columnFindings: '诊断',
  columnMargin: '预估利润率',
  noFindings: '无',
  sectionFindings: '诊断结论',
  sectionEconomics: '单件经济（预估）',
  economicsBuyerPrice: '买家价（含促销）',
  economicsUnitCost: '单件成本',
  economicsProfit: '预估单件利润',
  economicsMargin: '预估单件利润率',
  economicsBreakEven: '预估保本价',
  economicsTarget: '目标利润价',
  economicsCompetitor: 'Ozon 竞品最低价',
  economicsTerms: '计算条件',
  economicsTermsValue: (
    commission: string,
    logistics: string,
    acquiring: string,
    vat: string,
  ): string =>
    `佣金 ${commission}% · 物流（最高档）₽${logistics} · 收单 ₽${acquiring} · 增值税 ${vat}%（含在售价中）`,
  economicsUnavailable: '缺少费率、成本或售价，暂时算不出单件经济。',
  economicsNote:
    '按 Ozon 当前公布的费率和已采用的成本估算：物流取最高档，扣除售价中所含的增值税，不计其他税。出现真实结算后会校准。',
  targetMarginHint: (rate: string): string => `保住 ${rate} 单件利润率所需的最低售价`,
} as const;

/** What each conclusion means and what to do next, in the order the page shows them. */
export const CONCLUSION_TEXT: Readonly<
  Record<string, { readonly title: string; readonly meaning: string; readonly next: string }>
> = {
  WITHOUT_STOCK: {
    title: '无库存',
    meaning: '可售库存为 0，买家在搜索中看不到。',
    next: '补货；补货前，同款在售的其他尺码和颜色仍有搜索需求，可以优先照顾它们。',
  },
  LISTING_NOT_SELLABLE: {
    title: '买家不可购买',
    meaning: '平台状态为隐藏或已删除。',
    next: '到 Ozon 后台查看隐藏原因并处理。',
  },
  DEMAND_NOT_CONVERTING: {
    title: '有需求不成交',
    meaning: '近 7 天有不少于 1,000 人搜索、有库存，但没有下单。',
    next: '结合同一商品的价格和内容结论找不成交的原因。',
  },
  PRICE_GAP_STRUCTURAL: {
    title: '价格偏高（结构性）',
    meaning: '预估保本价已经高于 Ozon 竞品最低价：降到竞品价每卖一件都亏。',
    next: '降价解决不了：需要从成本、履约方式或差异化（内容、定位）入手。',
  },
  PRICE_GAP_PARTIAL: {
    title: '价格偏高（只能部分下调）',
    meaning: '降到竞品最低价不会亏本，但利润率会低于 15%。',
    next: '由你决定是否接受更低的利润换取成交。',
  },
  PRICE_GAP_REDUCIBLE: {
    title: '价格偏高（可降）',
    meaning: '比 Ozon 竞品最低价贵，但降到竞品价仍能保住 15% 利润率。',
    next: '可考虑把价格调到目标利润价和竞品价之间；改价需要审批。',
  },
  LOW_SEARCH_EXPOSURE: {
    title: '搜索曝光不足',
    meaning: '有库存，但每周搜索人数少于 200。',
    next: '检查标题、属性和类目是否覆盖了买家常用的搜索词。',
  },
  CONTENT_BELOW_TARGET: {
    title: '内容待提升',
    meaning: 'Ozon 内容评分低于 90。',
    next: '按评分组补齐图片、属性和描述；下一阶段会给出俄语草稿。',
  },
};

/** Names of the values a finding compared. */
export const FINDING_DETAIL_LABELS: Readonly<Record<string, string>> = {
  buyerPrice: '买家价',
  platformCompetitorMinPrice: 'Ozon 竞品最低价',
  breakEvenPrice: '预估保本价',
  targetMarginPrice: '目标利润价',
  premiumOverCompetitor: '比竞品贵',
  minimumUnitMarginRate: '利润率下限',
  searchUsers: '近 7 天搜索人数',
  orderedUnits: '下单件数',
  platformAvailableUnits: '可售库存',
  demandSearchUsersFloor: '“有需求”阈值',
  lowExposureSearchUsers: '“曝光不足”阈值',
  contentRating: '内容评分',
  contentRatingFloor: '评分阈值',
  listingSellable: '是否可售',
};

/** Ozon's price index classes, as its own documentation names them. */
export const PRICE_INDEX_LABELS: Readonly<Record<string, string>> = {
  SUPER: 'SUPER · 超值',
  GREEN: 'GREEN · 有竞争力',
  YELLOW: 'YELLOW · 一般',
  RED: 'RED · 无竞争力',
  WITHOUT_INDEX: '无指数',
};

/** Internal fulfillment modes. */
export const FULFILLMENT_MODE_LABELS: Readonly<Record<string, string>> = {
  MARKETPLACE_FULFILLED: '平台仓发货',
  SELLER_FULFILLED: '卖家仓发货',
  UNKNOWN: '未知',
};
