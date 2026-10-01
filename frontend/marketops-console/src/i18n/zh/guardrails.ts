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
  policyComing: '商业策略和单件目标利润、安全缓冲的录入将在下一步加入本页。',
} as const;
