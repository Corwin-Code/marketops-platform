/**
 * Chinese text for the governance tab: stopping and re-enabling Listing
 * actions, bounded batches and the recalculation queue.
 *
 * Code labels stay in `listingCodes.ts` and shared listing words in
 * `listing.ts`; this file holds only the sentences the tab's views and dialogs
 * add.
 */

export const governanceText = {
  viewContainments: '遏制',
  viewBatches: '批次',
  viewRecalculation: '重算队列',
  stopTip: '一人一理由即可立即停止；恢复需要两名不同人员分别证明，由后端核对。',
  stopConsequenceListing: '将停止此 Listing 的行动，立即生效；恢复需要两名不同人员分别证明。',
  stopConsequenceOrganization:
    '未填写 Listing 编号：将停止整个组织的 Listing 行动，立即生效；恢复需要两名不同人员分别证明。',
  stopOk: '确认停止',
  reasonRequired: '请填写停止理由',
  attestRepairTitle: '提交修复证明：填写证据引用',
  consentTitle: '提交业务同意：填写证据引用',
  evidencePlaceholder: '证据引用',
  reenableTitle: '确认恢复？',
  reenableHelp: '需已有两名不同人员的证明，由后端核对。',
  stoppedAt: '停止时间',
  attestations: '已有证明',
  noAttestations: '暂无',
  createBatchConsequence: '创建后逐个加入操作；关闭后不能再加入成员。',
  codeRequired: '请填写批次代码',
  addMemberTitle: '加入批次：填写操作编号（UUID）',
  closeBatchTitle: '确认关闭此批次？',
  closeBatchHelp: '关闭后不能再加入成员。',
  memberCount: (count: number) => `${String(count)} 个`,
  noMembers: '还没有成员',
} as const;
