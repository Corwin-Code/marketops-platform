/**
 * Chinese text for the effect of executed actions (P10): the 效果跟踪 page and the effect section of
 * a price command's timeline.
 */

/** The verdict of a before/after comparison. */
export const VERDICT_LABELS: Record<string, string> = {
  IMPROVED: '改善',
  UNCHANGED: '无变化',
  REGRESSED: '变差',
  INDETERMINATE: '无法判断',
};

/** The leading signals taken together. */
export const SIGNAL_LABELS: Record<string, string> = {
  POSITIVE: '向好',
  NEGATIVE: '转弱',
  MIXED: '信号不一致',
  NONE: '无明显变化',
  UNAVAILABLE: '无法读取',
};

/** Why a verdict is what it is. */
export const REASON_LABELS: Record<string, string> = {
  INSUFFICIENT_COVERAGE: '下单数据覆盖不足（每个窗口至少要有 70% 的天数）',
  STOCKOUT_IN_WINDOW: '改动后出现断货或不可售',
  PROMOTION_IN_WINDOW: '改动后进入了卖家促销',
  PRICE_NOT_HELD: '价格没有保持在改动后的价格',
  OTHER_ACTION_IN_WINDOW: '窗口内该商品还有其它动作',
  NO_ORDERS_EITHER_SIDE: '改动前后都没有下单',
  ORDERS_APPEARED: '改动后开始有下单',
  ORDERS_STOPPED: '改动后不再有下单',
  ORDER_RATE_UP: '日均下单上升 20% 以上',
  ORDER_RATE_DOWN: '日均下单下降 20% 以上',
  ORDER_RATE_STEADY: '日均下单变化不到 20%',
  SMALL_SAMPLE: '样本少（前后合计不足 10 件）',
};

/** What was done. */
export const ACTION_LABELS: Record<string, string> = {
  PRICE_CHANGE: '调价',
  PROMOTION_JOINED: '参加促销',
  PROMOTION_LEFT: '退出促销',
};

/** Who did it. */
export const SOURCE_LABELS: Record<string, string> = {
  PRICE_COMMAND: '平台改价',
  PRICE_DECISION: '后台手工改价',
  PROMOTION_DECISION: '促销决定',
};

/** Ozon's price index classes. */
export const INDEX_LABELS: Record<string, string> = {
  GREEN: '绿（有竞争力）',
  YELLOW: '黄',
  RED: '红（偏贵）',
  WITHOUT_INDEX: '无指数',
};

export const outcomesText = {
  rules:
    '改动当天不计入。改动后第 9 天先给出 7 天对 7 天的初步读数，第 16 天给出 14 天对 14 天的结论（下单数据晚 2 天到）。下单件数决定结论；前后都没有下单时，看先行信号：搜索人数变化 20% 以上、Ozon 价格指数等级变化。改动后断货、进入促销、价格没有保持，或该商品还有其它动作时，判为无法判断。结论出来后，对应的调价建议自动关闭。',
  empty:
    '还没有需要跟踪的动作。平台改价成功、在后台手工改价并记录、或记录参加/退出促销后，会出现在这里。',
  columns: {
    listing: '商品',
    action: '动作',
    actedAt: '执行时间',
    progress: '进度',
    verdict: '结论',
    orders: '下单（前 → 后）',
    signal: '先行信号',
  },
  observing: (date: string) => `观察中，初步读数预计 ${date}`,
  preliminaryDone: (date: string) => `已有初步读数，结论预计 ${date}`,
  finalDone: '已出结论',
  preliminaryTag: '初步',
  stage: { PRELIMINARY: '初步读数（7 天对 7 天）', FINAL: '结论（14 天对 14 天）' } as Record<
    string,
    string
  >,
  windows: (
    baselineFrom: string,
    baselineTo: string,
    observationFrom: string,
    observationTo: string,
  ) => `改动前 ${baselineFrom} ~ ${baselineTo}，改动后 ${observationFrom} ~ ${observationTo}`,
  coverage: (before: number, after: number, days: number) =>
    `下单数据覆盖：前 ${String(before)}/${String(days)} 天，后 ${String(after)}/${String(days)} 天`,
  windowsLabel: '比较窗口',
  units: '下单件数',
  searchUsers: '搜索人数（窗口内最新一周）',
  priceIndex: '价格指数等级',
  buyerPrice: '改动后买家价',
  reasons: '依据',
  flags: '窗口内情况',
  stockout: '有断货或不可售',
  promotion: '有卖家促销',
  priceNotHeld: '价格没有保持',
  otherAction: '还有其它动作',
  noFlag: '无断货、促销或其它动作',
  missing: '—',
  arrow: ' → ',
  priceChange: (from: string, to: string) => `${from} → ${to}`,
  ruleVersion: (version: number) => `判定规则 v${String(version)}`,
  // The effect section of a price command's timeline.
  commandTitle: '执行后的效果',
  commandNotFollowed:
    '指令成功后，平台会自动开始跟踪效果：改动后第 9 天给出初步读数，第 16 天给出结论。',
  openOutcomes: '查看全部效果跟踪',
} as const;
