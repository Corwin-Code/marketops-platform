/**
 * Chinese text for supply authority: inbound attestations and lead-time and
 * safety policy versions.
 *
 * Code tables stay in `availability.ts`; this file holds only the sentences
 * these two tabs add.
 */

/** The authority page's tabs. */
export const authorityText = {
  tabInbound: '入库证明',
  tabLeadTime: '提前期与安全库存',
} as const;

/** Picking an internal variant. */
export const variantPickerText = {
  placeholder: '搜索 SKU 或商品名称',
  inactive: '已停用',
} as const;

/** Inbound attestations. */
export const inboundText = {
  productFilterPlaceholder: '全部商品',
  statusFilter: '业务状态',
  allStatuses: '全部状态',
  refreshLabel: '刷新入库证明',
  empty: '当前范围内没有入库证明。',
  noMatch: '没有符合条件的入库证明。',
  total: (n: number): string => `共 ${String(n)} 份`,
  totalUnknown: '共计未统计',
  columnProduct: '商品',
  columnReference: '外部单号',
  columnStatus: '业务状态',
  columnQuantity: '数量',
  columnArrival: '预计到货',
  columnVerified: '最近核验',
  columnVersion: '版本',
  columnAttestedBy: '记录人',
  columnActions: '操作',
  quantity: (n: number): string => `${String(n)} 件`,
  version: (n: number, kind: string): string => `第 ${String(n)} 版 · ${kind}`,
  noAttestRight: '你没有登记入库证明的授权',
  create: '登记入库证明',
  createTitle: '登记入库证明',
  createIntro:
    '登记一份可追溯的在途补货证明。它只在到货窗口的最晚端计入供应，永远不算当前在手；登记后会触发重新计算。',
  created: (version: number): string => `入库证明第 ${String(version)} 版已受理`,
  product: '商品',
  externalReference: '外部订单或发运单号',
  externalReferenceRequired: '请填写外部订单或发运单号',
  quantityLabel: '数量（件）',
  quantityRequired: '请填写数量',
  arrivalFrom: '预计到货开始',
  arrivalFromRequired: '请选择预计到货开始时间',
  arrivalTo: '预计到货结束',
  arrivalToRequired: '请选择预计到货结束时间',
  arrivalOrder: '预计到货结束不能早于开始',
  status: '业务状态',
  statusRequired: '请选择业务状态',
  evidence: '证据引用',
  evidenceRequired: '请填写证据引用',
  evidencePlaceholder: '采购单、供应商确认或运单的编号或链接',
  reason: '原因说明',
  reasonOptional: '原因说明（可选）',
  reasonRequired: '请填写原因说明',
  amend: '修订',
  amendTitle: '修订入库证明',
  amendIntro: (reference: string, version: number): string =>
    `修订 ${reference} 的第 ${String(version)} 版：会追加一个新版本，原版本保留；外部单号不能修改。修订后会触发重新计算。`,
  amendEvidenceHelp: (current: string): string => `当前版本的证据：${current}`,
  amended: (version: number): string => `入库证明已修订为第 ${String(version)} 版`,
  reverify: '重新核验',
  reverifyTitle: '重新核验入库证明',
  reverifyConsequence:
    '记录一次新的核验（追加新版本），数量与到货窗口不变，并刷新最近核验时间；会触发重新计算。',
  reverified: (version: number): string => `已记录重新核验（第 ${String(version)} 版）`,
  cancel: '取消',
  cancelTitle: '取消入库证明',
  cancelConsequence:
    '取消后这份入库不再计入可用供应，并会触发重新计算。取消会追加一个新版本，历史版本保留。',
  cancelled: (version: number): string => `入库证明已取消（第 ${String(version)} 版）`,
  alreadyCancelled: '已取消的入库证明不能再修改',
} as const;

/** Lead-time and safety policy versions. */
export const leadTimeText = {
  statusFilter: '状态',
  allStatuses: '全部',
  refreshLabel: '刷新提前期策略',
  empty:
    '当前范围内没有提前期与安全库存策略。没有适用策略时，风险计算会进入「策略阻断」，不会当作 0 天。',
  truncated: (shown: number, total: number): string =>
    `共 ${String(total)} 个版本，仅显示前 ${String(shown)} 个；可按状态筛选缩小范围。`,
  columnScope: '适用范围',
  columnVersion: '版本',
  columnLeadTime: '提前期',
  columnSafety: '安全库存',
  columnValidity: '生效期',
  columnLifecycle: '状态',
  columnOwner: '负责人',
  columnActions: '操作',
  version: (n: number): string => `第 ${String(n)} 版`,
  leadRange: (min: number, max: number): string =>
    min === max ? `${String(min)} 天` : `${String(min)}–${String(max)} 天`,
  days: (n: number): string => `${String(n)} 天`,
  openEnded: '长期有效',
  scopeOrganization: '组织默认',
  scopeSupplier: (supplier: string): string => `供应商 ${supplier}`,
  scopeCategory: (category: string): string => `品类 ${category}`,
  scopeRoute: (supplier: string, route: string): string => `供应商 ${supplier} · 路线 ${route}`,
  noPublishRight: '你没有发布提前期策略的授权',
  publish: '发布策略',
  publishTitle: '发布提前期与安全库存策略',
  publishIntro:
    '策略按范围回退：变体 + 供应商 + 路线 → 供应商或品类 → 组织默认。同一范围的生效期不能重叠；要替换当前版本，请在下方选择被替代的版本。',
  published: (version: number): string => `提前期策略第 ${String(version)} 版已发布`,
  stepScope: '范围',
  stepValues: '取值与生效',
  scopeKind: '范围类型',
  scopeKindRequired: '请选择范围类型',
  organizationOnly: '需要组织级策略管理授权',
  variant: '商品变体',
  supplierCode: '供应商代码',
  supplierRequired: '请填写供应商代码',
  routeCode: '路线代码',
  routeRequired: '请填写路线代码',
  categoryCode: '品类代码',
  categoryRequired: '请填写品类代码',
  codeInvalid: '代码只能包含字母、数字、点、下划线或连字符，且以字母或数字开头（最多 64 位）',
  leadMin: '最短提前期（天）',
  leadMinRequired: '请填写最短提前期',
  leadMax: '最长提前期（天）',
  leadMaxRequired: '请填写最长提前期',
  leadOrder: '最长提前期不能小于最短提前期',
  safety: '安全库存天数',
  safetyRequired: '请填写安全库存天数',
  effectiveFrom: '生效时间',
  effectiveFromRequired: '请选择生效时间',
  effectiveFromPast: '生效时间不能早于现在',
  effectiveTo: '失效时间（可选）',
  effectiveToOrder: '失效时间须晚于生效时间',
  lastReviewedAt: '最近评审时间',
  lastReviewedRequired: '请选择最近评审时间',
  lastReviewedOrder: '最近评审时间不能晚于生效时间',
  supersedes: '替代的版本（可选）',
  supersedesHelp:
    '只列出同一范围、仍有效且生效时间早于新版本的版本；被替代的版本在新版本生效时停用。',
  supersedesNone: '同一范围没有可替代的版本',
  supersedesOption: (version: number, from: string): string =>
    `第 ${String(version)} 版 · 自 ${from}`,
  evidence: '证据引用',
  evidenceRequired: '请填写证据引用',
  reason: '原因说明',
  reasonRequired: '请填写原因说明',
  retire: '停用',
  retireTitle: '停用提前期策略',
  retireCurrent:
    '停用后这个版本立即失效（生效期截止到现在），并触发重新计算；没有其他适用版本时，相关风险会进入「策略阻断」。',
  retireScheduled: '这个版本尚未生效，停用会直接取消它，并触发重新计算。',
  retired: '策略已停用',
  cancelledScheduled: '尚未生效的策略已取消',
} as const;
