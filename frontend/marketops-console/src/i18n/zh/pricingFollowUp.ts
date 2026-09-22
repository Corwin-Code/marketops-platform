/**
 * Chinese text for following up on pricing work: today's work list with its
 * commands needing attention, and one command's timeline.
 *
 * Code tables (states, failures, rules) stay in `pricing.ts`; this file holds
 * only the sentences and headings these two screens add.
 */

/** Today's work list. */
export const queueText = {
  regionLabel: '今日工作',
  refreshLabel: '刷新今日工作',
  subjectsTab: '待处理商品',
  commandsTab: '需关注的指令',
  subjectColumn: '商品',
  priorityColumn: '优先级',
  priorityHelp: '由后端确定性计算，列表按此排序，页面不会重新排序。',
  criticalColumn: '严重',
  warningColumn: '警告',
  declinedColumn: '无法判断',
  declinedHelp: '因数据不足等原因无法得出结论的规则数。',
  netSalesColumn: '净销售额',
  profitColumn: '贡献利润',
  writeColumn: '平台写入',
  writeHelp: '「已阻断」表示有规则阻止调价：该商品当前不能发起平台写入。悬停或点击可查看阻断规则。',
  blocked: (n: number): string => `已阻断 (${String(n)})`,
  blockingRulesTitle: '阻断调价的规则',
  writable: '可调价',
  emptySubjects: '当前店铺暂无需要处理的商品',
  openSubject: (name: string): string => `查看 ${name} 的诊断`,
  commandsHelp: '结果未知、回读不一致或转人工处理的调价指令，需要有人确认下一步。',
  emptyCommands: '当前没有需要关注的调价指令',
  commandsFailed: '需关注的指令加载失败',
} as const;

/** Columns of the commands-needing-attention table. */
export const attentionColumns = {
  subject: '商品',
  platform: '平台',
  price: '调价前 → 目标价',
  state: '状态',
  failure: '失败原因',
  open: '执行记录',
  openLink: '查看',
  openLabel: (name: string): string => `查看 ${name} 的执行记录`,
} as const;

/** One command's timeline. */
export const commandText = {
  regionLabel: '调价指令',
  title: '调价指令',
  refreshLabel: '刷新指令',
  subjectMissing: '商品信息暂缺',
  backToDiagnosis: '回到商品诊断',
  autoRefreshing: (time: string): string => `自动刷新中 · 更新于 ${time}`,
  watchingUnknown: (time: string): string =>
    `结果未知，需要关注 · 仍在等待自动回读 · 更新于 ${time}`,
  updatedAt: (time: string): string => `更新于 ${time}`,
  autoRefreshHelp: '指令仍在处理中，每 4 秒自动刷新一次；进入最终或异常状态后停止。',
  refreshFailed: '刷新失败，已停止自动刷新；下方显示的是上一次读取的内容。',
  unresolvedTitle: '此变更尚未确认',
  unresolvedDescription: '在回读确认之前，不要假定平台已是新价格。',
  priorPrice: '调价前价格',
  targetPrice: '目标价格',
  latestReadback: '平台当前价格（最新回读）',
  noReadback: '尚未回读，无法确认',
  fulfillmentMode: '履约模式',
  attempts: '已调用次数',
  failure: '失败原因',
  gateLabel: '写入闸门',
  gateClosed: '写入闸门关闭，指令当前不能发往平台',
  gateClosedNoReason: '后端未给出关闭原因',
  gateUnknown: '无法确认闸门状态',
  gateUnknownDescription: '闸门状态未知时，不能认为写入闸门已开启。',
  lifecycle: ['等待执行', '调用平台', '平台处理', '回读确认'] as const,
  lifecycleResult: '结果',
  attemptsTab: (n: number): string => `平台调用记录 (${String(n)})`,
  readbacksTab: (n: number): string => `回读记录 (${String(n)})`,
  noAttempts: '尚未调用平台',
  noReadbacks: '尚未回读，无法确认平台当前价格',
  attemptTitle: (no: number, purpose: string): string => `第 ${String(no)} 次 · ${purpose}`,
  nativeStatus: (status: string): string => `平台状态码：${status} · `,
  completedAt: '完成时间：',
  notCompleted: '尚未完成',
  unrecognizedState: (state: string): string => `指令处于未识别的状态（${state}）。`,
  unreadableReadback: '无法读取平台回复',
} as const;

/** Notices raised when a watched command settles while the page is open. */
export const commandNotice = {
  changedTitle: (state: string): string => `调价指令状态：${state}`,
} as const;
