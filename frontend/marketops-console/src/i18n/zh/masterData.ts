/**
 * Chinese text for the master-data review: which internal SKU each marketplace
 * listing is mapped to, and whether its marketplace cost is the internal cost.
 */

export const masterDataText = {
  noStore: '当前控制台没有配置店铺，无法核对映射。',
  empty: '这个店铺还没有采集到任何商品。',
  emptyFiltered: '没有符合筛选条件的商品。',
  summaryTitle: '概览',
  listTitle: '商品',
  generatedAt: '生成时间',
  refreshLabel: '重新加载',

  tileListings: '平台商品',
  tileMapped: '已映射',
  tileProposed: '待确认',
  tileConflicts: '冲突',
  tileUnmatched: '未匹配',
  tileCostAdopted: '成本已采用',
  tileCostToAdopt: '成本待采用',
  tileCostHidden: '成本',
  costHidden: '你没有录入内部成本的权限，这里不显示成本。',
  tileMappedHint:
    '已确认、正在生效的映射。只有映射后的商品才能参与 SKU 诊断、缺货风险和 Listing 辅助。',
  tileProposedHint: '匹配器给出的映射提议，需要人工确认后才生效。',
  tileConflictsHint: '找不到对应的内部商品、条码重复等情况；有冲突的商品会被当作未映射。',
  tileCostToAdoptHint: '已映射、Ozon 上填了成本价，但内部采购成本还没有采用它（或与它不同）。',

  filterLabel: '筛选',
  filterAll: '全部',
  filterProposed: '待确认',
  filterConflicts: '冲突',
  filterUnmatched: '未匹配',
  filterCostToAdopt: '成本待采用',

  runProposals: '生成映射提议',
  runProposalsDone: (examined: number): string => `匹配器检查了 ${String(examined)} 个未映射的商品`,
  confirmSelected: (count: number): string => `确认选中的映射（${String(count)}）`,
  confirmTitle: '确认映射',
  confirmConsequence:
    '确认后，每个平台商品从现在起对应所选的内部商品；诊断、成本和风险计算都会按这个对应关系进行。之后可以重新提议并确认新的对应关系，但不会改写已生效的历史。',
  confirmSummary: (count: number): string =>
    `将逐条确认 ${String(count)} 个映射提议（每条使用它当前的版本号）。`,
  confirmNothing: '选中的商品里没有待确认的映射提议。',
  confirmDone: (done: number, failed: number): string =>
    failed === 0
      ? `已确认 ${String(done)} 个映射`
      : `已确认 ${String(done)} 个映射，${String(failed)} 个失败（可能已被他人处理），列表已刷新`,
  adoptSelected: (count: number): string => `采用选中的 Ozon 成本价（${String(count)}）`,
  adoptTitle: '采用 Ozon 成本价作为采购成本',
  adoptConsequence:
    '所选商品会各新增一个采购成本版本：金额取卖家在 Ozon 后台填写的成本价，从 Ozon 给出该值的时间起生效，证据指向那次价格采集。之前生效的成本在同一时间结束。全部成功或全部不写。',
  adoptSummary: (count: number): string => `将为 ${String(count)} 个已映射商品采用 Ozon 成本价。`,
  adoptNothing: '选中的商品里没有需要采用成本价的（需已映射、Ozon 上有成本价且与当前成本不同）。',
  adoptDone: (adopted: number, unchanged: number): string =>
    unchanged === 0
      ? `已为 ${String(adopted)} 个商品采用 Ozon 成本价`
      : `已为 ${String(adopted)} 个商品采用 Ozon 成本价，${String(unchanged)} 个本来就一致`,
  reasonLabel: '原因',
  reasonPlaceholder: '例如：Owner 已确认 Ozon 成本价就是单件成本',
  reasonRequired: '请填写原因，它会记入审计',

  columnListing: '平台商品',
  columnState: '映射状态',
  columnInternal: '内部商品',
  columnSellerCost: 'Ozon 成本价',
  columnPurchaseCost: '当前采购成本',
  columnCostState: '成本状态',
  offerId: '货号',
  productId: 'Ozon 商品 ID',
  barcode: '条码',
  untitled: '（无标题）',
  since: '生效于',
  observedAt: '采集于',
  confidence: (value: string): string => `置信度 ${value}`,
  moreProposals: (count: number): string => `另有 ${String(count)} 个提议`,
  noInternal: '—',
  noCost: '—',

  stateMapped: '已映射',
  stateProposed: '待确认',
  stateConflict: '冲突',
  stateUnmatched: '未匹配',
  costAdopted: '已采用',
  costToAdopt: '待采用',
  sourceMarketplace: '来自 Ozon 成本价',
  sourceManual: '手工录入',
  sourceImport: '表格导入',

  note: '平台商品来自 Ozon 官方接口；内部商品由“生成内部商品”步骤按款式创建，编码取自货号。映射和成本的每次确认都会记入审计。',
} as const;

/** How a proposal was matched. */
export const MATCH_METHOD_LABELS: Readonly<Record<string, string>> = {
  BARCODE: '条码一致',
  NATIVE_SKU_KEY: '货号一致',
  NORMALIZED_TITLE: '标题相近',
  MANUAL: '人工指定',
  IMPORTED: '导入',
};

/** Why a listing is in conflict. */
export const CONFLICT_LABELS: Readonly<Record<string, string>> = {
  NO_CANDIDATE: '找不到对应的内部商品',
  MULTIPLE_CANDIDATES: '有多个可能的内部商品',
  DUPLICATE_BARCODE: '条码对应多个内部商品',
  CONFLICTING_CONFIRMATION: '确认结果相互矛盾',
  ARCHIVED_INTERNAL_VARIANT: '对应的内部商品已停用',
};
