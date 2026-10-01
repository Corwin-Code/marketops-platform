/** Chinese texts of the price guardrail page. */

/** The economics projection profile section. */
export const economicsProfileText = {
  title: '经济性 profile',
  hint: '护栏用它估算调价后的单件利润：按 Ozon 返回的费率（佣金、物流、收单、售价中含的增值税），每项取全店各商品的最高值，收单按占买家价的比例计；仓储、促销、退货损失、广告标为不适用。一位 Owner 提交，另一位 Owner 批准后生效；费率会变，批准后 30 天需要重新生成。',
  notFbs: '店铺还没有声明为卖家自配送（FBS），不能生成 profile。',
  noProfile: '还没有生效的经济性 profile，护栏会以“缺少经济性 profile”拦下所有调价审批。',
  inForce: '生效中的 profile',
  version: '版本',
  effectiveFrom: '生效时间',
  verification: '核验',
  verificationExpiresAt: '核验到期',
  supportedPrices: '适用的买家价区间',
  supportedPricesValue: (lower: string, upper: string): string => `${lower} ~ ${upper}`,
  evidence: '依据',
  family: '成本族',
  component: '成本项',
  value: '取值',
  notApplicable: '不适用',
  generate: '按最新费率生成并提交',
  generateTitle: '生成经济性 profile 草稿',
  generateConsequence:
    '平台按最近 7 天各商品最新一次价格观测，每项费率取全店最高值生成草稿并提交（每项费率都要所有商品都有，否则不能生成）；需要另一位 Owner 批准后才生效，已有的待批准草稿会被替换。需要 COMMERCIAL_POLICY_MANAGE 授权，并且登录时间不能太久。',
  generated: '已提交草稿，请另一位 Owner 批准',
  drafts: '草稿',
  noDrafts: '还没有草稿',
  submittedAt: '提交时间',
  state: '状态',
  submitter: '提交人',
  submitterYou: '你',
  submitterOther: '另一位 Owner',
  waitingForOther: '需要另一位 Owner 批准',
  approve: '批准',
  approveTitle: '批准经济性 profile',
  approveConsequence:
    '批准后这份 profile 立即生效，旧的 profile 退役；护栏从此按它估算调价后的利润。需要 COMMERCIAL_POLICY_MANAGE 授权，并且登录时间不能太久。',
  verificationDays: '核验有效天数',
  note: '备注（可选）',
  approved: '已批准，profile 已生效',
  reject: '驳回',
  rejectTitle: '驳回草稿',
  withdraw: '撤回',
  withdrawTitle: '撤回自己的草稿',
  closeConsequence: '草稿不再等待批准；需要时可以重新生成。',
  reason: '原因',
  reasonRequired: '请填写原因',
  closed: '已处理',
  draftContent: (components: number, range: string): string =>
    `${String(components)} 个成本项 · 适用买家价 ${range}`,
} as const;

/** The families of costs a profile describes. */
export const FAMILY_LABELS: Readonly<Record<string, string>> = {
  COMMISSION: '佣金',
  FULFILLMENT_DELIVERY: '物流配送',
  STORAGE: '仓储',
  PROMOTION: '促销',
  OTHER_VARIABLE: '其他变动成本',
  RETURN_LOSS: '退货损失',
  ADVERTISING: '广告',
  VARIABLE_TAX: '增值税（含在售价中）',
};

/** The costs a generated profile names. */
export const COMPONENT_LABELS: Readonly<Record<string, string>> = {
  SALES_COMMISSION_FBS: 'FBS 销售佣金',
  FBS_FIRST_MILE: '首公里（最高档）',
  FBS_DIRECT_FLOW: '干线物流（最高档）',
  FBS_LAST_MILE: '末公里',
  ACQUIRING: '收单（占买家价）',
  VAT_IN_PRICE: '售价中含的增值税',
};

/** Where a draft stands. */
export const DRAFT_STATE_LABELS: Readonly<Record<string, string>> = {
  SUBMITTED: '待批准',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  SUPERSEDED: '已撤回或被替换',
};

/** How a profile was verified. */
export const VERIFICATION_LABELS: Readonly<Record<string, string>> = {
  REAL_ACCOUNT_VERIFIED: '真实账户数据，两位 Owner 核验',
  ENGINEERING_VERIFIED: '工程核验',
  UNVERIFIED: '未核验',
};

/** The guardrail page's own texts. */
export const guardrailsText = {
  freshnessNote: '数据新鲜度（8 个数据源）在“数据采集”页的“护栏数据新鲜度”里查看和声明。',
  openDataCollection: '打开“数据采集”',
} as const;

/** The commercial policy section. */
export const commercialPolicyText = {
  title: '商业策略',
  hint: '护栏按这里的限额检查每一次调价：数据完整度、输入数据时效、利润率和单件利润下限、单次与单日调价幅度、冷却期、最低可售库存。策略按版本发布，不能修改；发布新版本时，店铺原来的版本同时结束。',
  noPolicy: '还没有生效的商业策略，护栏会以“没有生效的定价策略”拦下所有调价审批。',
  widerScope: (scope: string, version: number): string =>
    `当前适用的是${scope}范围的策略（版本 ${String(version)}）；发布店铺策略后以店铺策略为准。`,
  inForce: '生效中的策略',
  version: '版本',
  scope: '范围',
  objective: '生命周期目标',
  effectiveFrom: '生效时间',
  publisher: '发布人',
  publisherYou: '你',
  publisherOther: '另一位 Owner',
  reason: '发布原因',
  limit: '限额',
  value: '取值',
  breach: '超出时的拦截原因',
  publish: '发布新版本',
  publishTitle: '发布店铺商业策略',
  publishConsequence:
    '发布后立即生效，店铺原来的策略版本同时结束；护栏从此按新的限额检查调价。需要 COMMERCIAL_POLICY_MANAGE 授权，并且登录时间不能太久。',
  defaultsNote:
    '初始值是 Owner 2026-10-01 的决定：数据完整度不低于 37.5%，利润率不低于 15%，单件利润不低于 0，单次和单日调价都不超过 15%，冷却期和输入数据时效都是 72 小时，至少 1 件可售。已有店铺策略时，初始值是当前版本的取值。',
  reasonPlaceholder: '例如：按 Owner 2026-10-01 的决定发布',
  reasonRequired: '请填写发布原因',
  valueRequired: '请填写取值',
  published: '已发布，新策略已生效',
  history: '版本记录',
  noHistory: '还没有店铺自己的策略版本',
  status: '状态',
  effectiveRange: '生效区间',
  until: '至今',
  hours: '小时',
  units: '件',
} as const;

/** The limits a policy configures. */
export const LIMIT_LABELS: Readonly<Record<string, string>> = {
  MIN_DATA_COMPLETENESS: '最低数据完整度',
  MAX_INPUT_AGE_SECONDS: '输入数据最长时效',
  MIN_CONTRIBUTION_MARGIN: '最低贡献利润率',
  MIN_UNIT_CONTRIBUTION_PROFIT: '最低单件贡献利润',
  MAX_SINGLE_CHANGE_RATE: '单次调价最大幅度',
  MAX_DAILY_CHANGE_RATE: '单日累计调价最大幅度',
  COOLDOWN_SECONDS: '调价冷却期',
  MIN_AVAILABLE_UNITS: '最低可售库存',
  MAX_POLICY_AUTHORIZED_CHANGE_RATE: '预授权最大调价幅度',
};

/** What a policy says it is trying to achieve. */
export const OBJECTIVE_LABELS: Readonly<Record<string, string>> = {
  HERO: '主推',
  GROWTH: '增长',
  MATURE: '成熟',
  REPAIR: '修复',
  EXIT: '退出',
};

/** What a policy applies to. */
export const POLICY_SCOPE_LABELS: Readonly<Record<string, string>> = {
  ORGANIZATION: '组织',
  PLATFORM: '平台',
  STORE: '店铺',
  PRODUCT_VARIANT: '单个商品',
};

/** Whether a policy version is in force. */
export const POLICY_STATUS_LABELS: Readonly<Record<string, string>> = {
  ACTIVE: '生效中',
  ENDED: '已结束',
  CANCELLED: '已取消',
};

/** The commercial inputs section. */
export const commercialInputsText = {
  title: '单件目标利润与安全缓冲',
  hint: '护栏算最低价时，在单件成本之上再加这两项。Owner 2026-10-01 决定两项都先设为 0，以 15% 的利润率下限把关。指标按整点窗口取值：录入后，过了下一个整点再到“店铺诊断”页点“重新计算诊断”，指标才会用上。',
  input: '输入项',
  value: '当前值',
  scope: '范围',
  effectiveFrom: '生效时间',
  note: '说明',
  notSet: '未设置',
  set: '设置',
  setTitle: (label: string): string => `设置${label}`,
  setConsequence:
    '按店铺的币种记录，立即生效，店铺原来的值同时结束；过了下一个整点，再到“店铺诊断”页点“重新计算诊断”，指标才会用上。需要 INTERNAL_FACT_INTAKE 授权。',
  amount: '金额',
  reasonPlaceholder: '例如：Owner 2026-10-01 决定先设为 0',
  reasonRequired: '请填写说明',
  amountRequired: '请填写金额',
  entered: '已记录；过了下一个整点再重新计算诊断即可用上',
  history: '录入记录',
  enteredBy: '录入人',
  enteredByYou: '你',
  enteredByOther: '其他人',
  status: '状态',
} as const;

/** The commercial inputs a store needs. */
export const INPUT_LABELS: Readonly<Record<string, string>> = {
  REQUIRED_PROFIT_PER_UNIT: '单件目标利润',
  SAFETY_BUFFER_PER_UNIT: '单件安全缓冲',
};
