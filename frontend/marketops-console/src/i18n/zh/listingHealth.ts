/**
 * Chinese text for the listing health workbench: the health queue, one
 * listing's detail, recording observations, responsibility clocks, feedback
 * and AI assistance.
 *
 * Code tables stay in `listingCodes.ts` and shared wording in `listing.ts`;
 * this file holds only the sentences these screens add.
 */

/** The health queue. */
export const healthQueueText = {
  title: '健康队列',
  searchPlaceholder: '搜索商品名、标题、平台键或 SKU',
  searchLabel: '搜索 Listing',
  refreshLabel: '刷新健康队列',
  openListing: (name: string): string => `查看 ${name} 的健康详情`,
  totalUnknown: '共计未统计',
  total: (n: number): string => `共 ${String(n)} 个 Listing`,
  noMatch: '没有匹配的 Listing',
} as const;

/** One listing's detail. */
export const healthDetailText = {
  back: '返回健康队列',
  record: '记录观察',
  recordDescription: '当前描述',
  recordDisplay: '买家端展示',
  recordNativeScope: '原生范围',
  recordPromotion: '促销观察',
  recomputed: '已重新计算健康',
  tabHealth: '健康与责任',
  tabMeasurements: '转化度量',
  tabFeedback: '反馈',
  tabAssistance: 'AI 辅助',
  referenceOnly: '仅供参考',
  recentTitle: '最近记录',
  recentDescription: '管理端描述观察编号',
  recentDisplay: '买家端展示观察编号',
  recentNativeScope: '原生范围枚举编号',
  recentPromotion: '促销参与观察编号（尚未独立核验）',
  measured: '已计算转化度量',
  measureConsequence: '按所选窗口与证据路径计算一次转化度量，结果单独保存，不改变健康状态。',
  windowOrder: '窗口结束须晚于窗口开始',
  windowStartRequired: '请选择窗口开始',
  windowEndRequired: '请选择窗口结束',
  necessaryTitle: '必要条件',
  conditionEvidence: '依据',
  identityPending: '商品信息暂缺',
  variants: (n: number): string => `${String(n)} 个变体`,
  daysOption: (days: string): string => `${days} 天`,
} as const;

/** Recording observations from the detail header. */
export const recorderText = {
  descriptionTitle: '记录当前描述',
  descriptionSaved: '已记录当前描述',
  descriptionTextRequired: '请填写观察到的俄语描述',
  kizRequired: '请选择 KIZ 标识码声明',
  displayTitle: '记录买家端展示',
  displayConsequence: '记录一次买家端实际看到的展示情况，作为来源事实保存，不改变 Listing 内容。',
  displaySaved: '已记录买家端展示',
  displayStateRequired: '请选择展示状态',
  evidenceRequired: '请填写证据引用',
  nativeScopeSaved: '已记录原生范围枚举',
  promotionTitle: '记录促销参与观察',
  promotionSaved: '已记录促销参与观察',
  stepActivity: '活动',
  stepTerms: '条款',
  stepContext: '完整上下文',
  kindRequired: '请选择促销类型',
  observedAtRequired: '请选择实际观察时间',
  referenceRequired: '请填写本次观察的证据引用',
  contextNeedsTerms: '完整枚举需要本次已观察到完整商业条款（见「条款」一步）',
  contextTimeRequired: '请选择时间',
  contextStateRequired: '请选择状态',
  coverageOrder: '枚举覆盖结束须晚于覆盖开始',
  effectiveOrder: '活动生效结束须晚于生效开始',
  authorityRequired: '参与中时须填写原商业权威引用',
  authorityUntilRequired: '参与中时须填写原商业权威有效期',
  authorityOnlyParticipating: '仅在观察到参与中时填写原商业权威',
  axisPartial: '填写任一项时，数值、单位与证据须同时填写',
  submit: '保存观察',
} as const;

/** Responsibility clocks, deferrals and dependency holds. */
export const responsibilityText = {
  acknowledge: '承接',
  acknowledged: '已记录承接',
  acknowledgedTag: '已承接',
  busy: '正在处理中',
  diagnosticQuestion: '确认由你承接此必要条件责任？',
  diagnosticConsequence: '承接不表示原因已解除，风险继续计时。',
  actionQuestion: '确认由你承接此责任？',
  actionConsequence: '承接会被记录，原责任起点不会重置。',
  clockDetails: '时钟详情',
  dueSummary: '确认截止',
  actionDueSummary: '行动截止',
  ackBreached: '承接已逾期',
  actionBreached: '行动已逾期',
  continuousRisk: '连续计时',
  undeclared: '未声明',
  sloUndeclared: '时限政策未声明',
  coverageUndeclared: '覆盖日历未声明',
  defer: '暂缓',
  deferTitle: '有限暂缓',
  deferConsequence: '暂缓不重置原责任起点；到期或诊断变化后重新评估资格，不会自动执行。',
  deferred: '已记录有限暂缓',
  deferActive: '暂缓期内，不能再次暂缓',
  deferReviewDue: '等待资格重评，不能再次暂缓',
  deferReasonRequired: '请填写暂缓理由',
  minutesRequired: '请填写分钟数',
  minutesInvalid: '分钟数须为不小于 1 的整数',
  hold: '依赖暂停',
  holdTitle: '有限依赖暂停',
  holdConsequence: '仅暂停行动时钟；承接与原起点不重置，到期或依赖完成后自动恢复。',
  held: '已记录依赖暂停',
  holdActive: '依赖暂停生效中',
  holdTaskRequired: '请选择或填写依赖的原任务编号',
  holdTaskInvalid: '请填写完整的任务编号（UUID 格式）',
  holdTaskPlaceholder: '选择本 Listing 的责任任务，或粘贴任务编号',
  holdEvidenceRequired: '请填写依赖证据引用',
  holdTaskOption: (cause: string): string => `${cause} 的责任任务`,
  stateDetails: '记录详情',
  none: '暂无必要条件处置责任',
} as const;

/** Feedback themes and corrections. */
export const feedbackText = {
  period: '期间',
  last7: '近 7 天',
  last30: '近 30 天',
  last90: '近 90 天',
  custom: '自定义',
  load: '读取',
  captureConsequence: '引用已有 Raw 观察及准确 JSON 位置，只保存摘要和来源身份，不复制或改写原文。',
  captured: '已关联原始反馈',
  rawRequired: '请填写 Raw 观察编号',
  pointerRequired: '请填写字段路径',
  itemsTitle: '反馈明细',
  openItem: '查看反馈详情',
  detailTitle: '反馈详情',
  correct: '纠正分类',
  corrected: '已追加分类',
  correctConsequence: '追加一条人工分类修订，原文与分类历史保留不变。',
  themeRequired: '请填写主题代码',
  reasonRequired: '请填写分类或纠正依据',
  periodRequired: '请选择期间开始和结束',
  periodOrder: '期间结束须晚于期间开始',
} as const;

/** On-demand AI assistance. */
export const assistanceText = {
  request: '生成 AI 建议（仅供参考）',
  history: '历史记录',
  historyPlaceholder: '选择一次历史调用查看',
  historyEmpty: '暂无历史调用',
  historyUnavailable: '列表暂不可用，可手动输入编号',
  manual: '其他（手动输入）',
  manualTitle: '按调用记录编号读取',
  manualPlaceholder: '调用记录编号（UUID）',
  historyOption: (window: string, state: string, when: string): string =>
    `${when} · ${window} · ${state}`,
} as const;
