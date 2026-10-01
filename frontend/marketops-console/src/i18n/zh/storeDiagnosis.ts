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
  /** @param premiumPlus whether the store has Premium Plus, `null` until the rating summary is collected */
  conclusionsNote: (premiumPlus: boolean | null): string =>
    `有两类结论暂时无法判断：曝光低、点击率低、转化率低需要 Ozon 的曝光、点击和加购数据，只对 Premium Plus 订阅开放${
      premiumPlus === null
        ? ''
        : premiumPlus
          ? '（本店已订阅，但这些数据还没有接入）'
          : '（本店未订阅）'
    }；负利润、退货率、库存可售天数、广告效率需要实际订单和结算数据（本店还没有订单）。下面的结论依据预估单件经济、搜索、价格、内容和活动这些不依赖销售的数据。价格竞争力和搜索属于平台分析（C 级），只用于诊断，不会自动改价。`,
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

  aiSummaryTitle: 'AI 周诊断',
  aiSummaryHint:
    'AI 只根据上面的诊断结论和商品数据写解读，不会计算价格或利润，也不能批准任何操作。数字都照抄自数据；每周一采集完成后会自动生成一次，数据没变时沿用上次的解读。',
  aiGenerate: '生成解读',
  aiRegenerate: '重新生成',
  aiNone:
    '还没有生成过解读。点“生成解读”，AI 会基于诊断结论写出一句话结论、最多 3 个优先动作和不确定项。',
  aiWaiting: (seconds: number): string => `通常约 30 秒 · 已等待 ${String(seconds)} 秒`,
  aiHeadline: '结论',
  aiActions: '优先动作',
  aiFacts: '依据',
  aiUnknowns: '不确定项',
  aiExpectedEffect: '预期效果：',
  aiGeneratedAt: '生成于',
  aiReused: '数据没有变化，沿用此前的解读',
  aiInFlight: '已有一个生成请求正在进行，稍后点“重新加载”查看结果。',
  aiUnavailable: '暂时没有可用的解读：',
  aiUnaffected: '诊断结论和指标不受影响。',
  aiRejected: (count: number): string => `另有 ${String(count)} 条 AI 陈述未通过校验，已隐藏`,
  aiOlderEvidence: (count: number): string => `另引用 ${String(count)} 条证据`,
  aiEvidenceCount: (count: number): string => `引用 ${String(count)} 条证据`,
  aiReload: '重新加载',
  aiDrawerTitle: 'AI 解读',
  aiDrawerNone: '还没有生成过这个商品的解读。',
  aiReference: (conclusion: string, product: string): string => `${conclusion} · ${product}`,
} as const;

/** The home page's warning when scheduled collection has stopped or will soon stop. */
export const collectionHealthText = {
  titleStopped: '数据采集已停止',
  titleAttention: '数据采集需要处理',
  open: '查看数据采集',
  keyRefused: (jobs: string, status: string): string =>
    `Ozon 拒绝了当前的 key（${status}）：${jobs}已停止更新。key 可能已被停用或缺少权限，请在 Ozon 卖家后台检查并换上新 key，然后在数据采集页恢复任务。`,
  blocked: (jobs: string): string => `这些任务被卡住，需要人处理后才会继续：${jobs}。`,
  evidenceLapsed: (jobs: string): string =>
    `这些数据没有有效的核验证据（尚未核验或已过期），不会采集：${jobs}。需要探测，并由两位 Owner 核验。`,
  evidenceExpiring: (count: number): string => `${String(count)} 项数据的核验证据即将到期，最早于 `,
  credentialMissing: '没有生效的 Ozon 只读凭证，无法采集。',
  credentialExpiring: 'Ozon 只读凭证即将到期，到期时间：',
} as const;

/** The store's own standing on Ozon: subscription, penalty balance, ratings and warehouses. */
export const storeStandingText = {
  title: '店铺状态',
  hint: '来自 Ozon 卖家评级和仓库列表，每天采集一次。评级按 Ozon 自己的判定显示；没有订单时多数评级还没有数据。',
  notCollected: '还没有采集到店铺状态。',
  collectedAt: '数据时间',
  account: '账户',
  premium: 'Premium 订阅',
  premiumPlus: 'Premium Plus 订阅',
  subscribed: '已订阅',
  notSubscribed: '未订阅',
  penalty: '罚分',
  penaltyExceeded: '已超限',
  penaltyOk: '未超限',
  localization: '本地化指数',
  localizationEmpty: '近 14 天没有销售，Ozon 未计算',
  localizationValue: (percent: string): string => `${percent}%`,
  localizationAt: '计算于',
  unknown: '未知',
  ratings: '卖家评级',
  ratingColumn: '评级',
  statusColumn: 'Ozon 判定',
  valueColumn: '当前值',
  pastColumn: '上期值',
  noRatingData: '暂无数据',
  valueAsStated: '比例类评级按百分比显示（Ozon 以 0 到 1 的比例给出），其余按 Ozon 原样显示。',
  warehouses: '仓库',
  warehouseColumn: '仓库',
  firstMile: '首公里',
  workingDays: (days: number): string => `每周 ${String(days)} 天`,
  noPostingsLimit: '无订单上限',
  postingsLimit: (limit: number): string => `订单上限 ${String(limit)}`,
  paused: '已暂停，自',
  alertTitle: '店铺状态需要处理',
  alertPenalty: 'Ozon 罚分已超限，店铺可能被限制销售。请到 Ozon 卖家后台“评级”查看原因。',
  alertWarehouse: (key: string, status: string): string =>
    `仓库 ${key} 当前状态为“${status}”，从这个仓库发货的商品可能无法下单。`,
  alertRating: (names: string): string => `这些评级被 Ozon 判为严重：${names}。`,
  warningRating: (names: string): string => `这些评级需要注意：${names}。`,
} as const;

/** Ozon's verdict on a rating. */
export const RATING_STATUS_LABELS: Readonly<Record<string, string>> = {
  OK: '正常',
  WARNING: '需注意',
  CRITICAL: '严重',
  UNKNOWN_STATUS: '暂无数据',
};

/** The ratings Ozon returned for the pilot (2026-10-01), in Chinese; others show Ozon's own name. */
export const RATING_LABELS: Readonly<Record<string, string>> = {
  rating_review_avg_score_total: '商品评价均分',
  rating_shipment_delay_cb: '发货逾期率',
  rating_general_indicator_fbs_rfbs: 'FBS/rFBS 累进制评分',
  rating_delivery_complaints_fbo: 'FBO 投诉率',
  rating_delivery_complaints_fbs: 'FBS 投诉率',
  rating_delivery_complaints_rfbs_sd: 'rFBS 投诉率',
  rating_price_green: '价格指数绿色区商品占比',
  rating_price_yellow: '价格指数黄色区商品占比',
  rating_price_red: '价格指数红色区商品占比',
  rating_price_super: '价格指数超值区商品占比',
};

/** The order ratings are listed in: the seller's score, delivery, complaints, then prices. */
export const RATING_ORDER: readonly string[] = Object.keys(RATING_LABELS);

/** A warehouse's state as the seller back office names it (official mapping, /v1/warehouse/list). */
export const WAREHOUSE_STATUS_LABELS: Readonly<Record<string, string>> = {
  created: '启用',
  new: '启用中',
  disabled: '已归档',
  blocked: '已封禁',
  disabled_due_to_limit: '暂停（达到订单上限）',
  error: '出错',
};

/** How orders leave a warehouse for Ozon. */
export const FIRST_MILE_LABELS: Readonly<Record<string, string>> = {
  DROP_OFF: '自送到投放点',
  PICK_UP: 'Ozon 上门取件',
};

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
    next: '打开商品详情的“内容优化”：看 Ozon 评分组缺什么、点名要补哪些属性，并生成俄语草稿。',
  },
  PROMOTION_OPPORTUNITY: {
    title: '可参加活动',
    meaning: '有 Ozon 活动可以参加，按活动允许的最高价仍能保住 15% 利润率。',
    next: '打开“促销活动”页查看活动、逐个商品的测算和 AI 建议；在卖家后台参加后回来记录决定。',
  },
  PRICE_HEADROOM: {
    title: '有降价空间',
    meaning: '有不少人搜索、有库存，但没人下单；降价后仍能守住 15% 利润率。',
    next: '打开商品详情查看调价建议；在 Ozon 卖家后台调价后回来记录。',
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
  promotionMargin: '按最高活动价的利润率',
  searchUsers: '近 7 天搜索人数',
  orderedUnits: '下单件数',
  platformAvailableUnits: '可售库存',
  demandSearchUsersFloor: '“有需求”阈值',
  lowExposureSearchUsers: '“曝光不足”阈值',
  contentRating: '内容评分',
  contentRatingFloor: '评分阈值',
  listingSellable: '是否可售',
  projectedUnitMargin: '预估利润率',
  priceRoom: '守住利润率下限时最多可降',
};

/** The price suggestion of one product, in its detail drawer (P8). */
export const priceSuggestionText = {
  title: '调价建议',
  hint: '由诊断规则按确定性算法给出：不低于守住利润率下限的价格，单次降幅有上限。价格指买家价（含卖家促销）。平台不会自动改价。',
  basis: '依据',
  priceChange: '买家价',
  changeRate: (rate: string): string => `（${rate}）`,
  range: '可调区间',
  rangeValue: (lower: string, upper: string): string => `${lower} ~ ${upper}`,
  rangeHint: '下限是守住利润率下限的最低价，上限是现价；建议价已按单次最大降幅收敛。',
  margin: '预估利润率',
  marginChange: (now: string, target: string): string => `${now} → ${target}`,
  competitor: 'Ozon 竞品最低价',
  searchUsers: '近 7 天搜索人数',
  guardrail: '平台护栏',
  guardrailPassed: '已通过',
  guardrailFailed: '未通过，平台内暂不能审批改价：',
  guardrailNotYet: '尚未评估',
  guardrailHint:
    '写入前的护栏前提（商业策略、经济性 profile、数据新鲜度等）还没有就绪。认可这条建议的话，请在 Ozon 卖家后台手工调价，然后回来记录。',
  openReview: '打开审阅',
  applied: '已在 Ozon 后台改价',
  notApplied: '不采纳',
  appliedTitle: '记录：已在 Ozon 后台改价',
  appliedPrice: '实际设置的买家价',
  appliedPriceRequired: '请填写实际设置的买家价',
  notAppliedTitle: '记录：不采纳这条建议',
  note: '备注（可选）',
  reason: '原因（可选）',
  saved: '已记录',
  decidedApplied: (price: string): string => `已在 Ozon 后台改价为 ${price}`,
  decidedNotApplied: '已决定不采纳',
  decidedAt: '记录于',
  suggestionsGenerated: (proposed: number, refreshed: number, withdrawn: number): string =>
    [
      proposed > 0 ? `新增 ${String(proposed)} 条调价建议` : '',
      refreshed > 0 ? `更新 ${String(refreshed)} 条` : '',
      withdrawn > 0 ? `撤下 ${String(withdrawn)} 条` : '',
    ]
      .filter((part) => part !== '')
      .map((part, index) => (index === 0 ? `，${part}` : `、${part}`))
      .join(''),
  suggestionsFailed: '调价建议生成失败，稍后可重新计算再试。',
} as const;

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
