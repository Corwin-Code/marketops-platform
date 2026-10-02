/**
 * Chinese text for the operations review tab: the three readings as compact
 * tables, the listing detail beside them and the experience drawer.
 *
 * Code labels stay in `listingCodes.ts` and shared listing words in
 * `listing.ts`; this file holds only the sentences the tab adds.
 */

export const reviewText = {
  switchStore: '切换店铺',
  switchStoreTitle: '切换复盘店铺',
  storeLine: '店铺',
  laneColumn: '工作类型',
  healthColumn: '健康',
  responsibilitiesColumn: '责任',
  count: (count: number) => `${String(count)} 项`,
  openRow: (listing: string) => `查看 ${listing} 的复盘详情`,
  detailTitle: '复盘详情',
  targetRequired: '请选择目标 Listing',
  sourceRequired: '请选择来源行动与结果',
  evidenceRequired: '请填写目标适用性证据',
  historyTitle: '该目标已记录的经验',
  noHistory: '该目标还没有记录过经验',
} as const;
