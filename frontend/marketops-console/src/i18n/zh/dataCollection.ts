/**
 * Chinese text for scheduled data collection: the Owner's standing authorization, what each
 * scheduled job read last and reads next, and what the scheduler recorded.
 */

export const dataCollectionText = {
  noStore: '当前控制台没有配置店铺，无法查看数据采集。',
  refreshLabel: '重新加载',

  policyTitle: '定时采集授权',
  policyOff: '未启用：现在只有手动运行采集命令时才会读取 Ozon 数据。',
  policyOnTag: '已启用',
  policyOn: '平台按下面的计划自动读取 Ozon 只读数据，采集完成后自动重算诊断。',
  authorizedAt: '授权于',
  schedule: (dailyAtUtc: string): string =>
    `每天 UTC ${dailyAtUtc}（莫斯科时间加 3 小时，北京时间加 8 小时）起：商品目录、价格与费率、库存、商品状态、内容评分各读一次；下单件数读取前天（UTC）的数据，漏掉的天会补读，最多补 7 天；搜索数据每周三读取上周一至周日。`,
  scheduleRules:
    '只读、遵守各接口限流；每次调用仍由数据库按已核验的接口、凭证和授权逐次放行。被限流时等下一轮继续；同一目标最多尝试 3 次；核验过期的采集项会跳过，不会制造卡住的运行。采集告一段落后，等过了下一个整点再重算诊断（计算窗口截至整点）。',
  schedulerOff:
    '本环境没有开启采集定时器（marketops.data-collection.enabled），即使启用授权也不会自动采集。',
  enable: '启用定时采集',
  enableTitle: '启用定时采集',
  enableConsequence:
    '启用后，平台会按计划自动调用 Ozon 只读接口读取本店铺的数据并重算诊断，不再需要每次手动运行。可以随时停用，已采集的数据保留。',
  retire: '停用',
  retireTitle: '停用定时采集',
  retireConsequence: '停用后不再自动读取；已采集的数据和诊断保留，仍可手动采集。',
  enabled: '已启用定时采集',
  retired: '已停用定时采集',
  reasonLabel: '原因',
  reasonPlaceholder: '例如：按 2026-09-29 决定开启定时只读采集',
  reasonRequired: '请填写原因',

  jobsTitle: '采集项',
  columnDataset: '数据',
  columnCadence: '频率',
  columnLast: '最近成功',
  columnNow: '当前',
  columnNext: '下一次',
  columnEvidence: '核验有效期至',
  columnLastEvent: '最近记录',
  noJobs: '这个店铺还没有注册采集任务。',
  never: '从未成功',
  manualRun: '手动',
  scheduledRun: '定时',
  windowLabel: (from: string, to: string): string => `${from} 至 ${to}`,
  stateIdle: '空闲',
  dueNow: '本轮待采集',
  attempts: (count: number): string => `已尝试 ${String(count)} 次`,
  nextAttemptAt: '下次重试',
  evidenceMissing: '无有效核验',
  evidenceExpiresSoon: (days: number): string => `${String(days)} 天后到期`,
  evidenceHint:
    '采集能力的核验证据有效期最长 30 天。到期后该项不再采集，需要重新探测并由两位 Owner 完成核验（make ozon-probe、make ozon-verify）。',
  blockedHint:
    '运行被卡住，会一直占用这个采集任务。查明原因后用 make ozon-resolve 重试或关闭，定时采集才会继续。',

  calculationsTitle: '诊断重算',
  calculationsHint:
    '首页诊断用 7 天窗口，SKU 诊断用 30 天窗口。计算窗口截至整点，所以新采集的数据要到下一个整点之后的重算才会纳入。',
  calculationWindow: (window: string): string =>
    window === 'D7' ? '7 天窗口' : window === 'D30' ? '30 天窗口' : window,
  calculatedUntil: '数据截至',
  notCalculated: '尚未计算',

  eventsTitle: '执行记录',
  eventsEmpty: '还没有执行记录。',
  columnTime: '时间',
  columnEvent: '事件',
  columnTarget: '目标',
  columnDetail: '详情',
  factsRecorded: (count: string): string => `记下 ${count} 条事实`,
  pagesStored: (count: string): string => `${count} 页`,
  recalculatedDetail: (subjects: string, findings: string): string =>
    `${subjects} 个商品，${findings} 条规则结论`,
  masterData: '已运行主数据自动规则',
  masterDataFailed: '主数据自动规则运行失败',
  weeklySummary: 'AI 周诊断',
  summaryReused: '数据未变，沿用此前的解读',
} as const;

/** What each scheduled dataset is, in the order the scheduler reads them. */
export const DATASET_LABELS: Readonly<Record<string, string>> = {
  LISTING: '商品目录',
  PRICE: '价格与费率',
  STOCK: '库存',
  LISTING_HEALTH: '商品状态',
  LISTING_CONTENT: '内容评分',
  TRAFFIC: '下单件数',
  LISTING_SEARCH: '搜索汇总',
  LISTING_SEARCH_TERM: '搜索词',
};

export const CADENCE_LABELS: Readonly<Record<string, string>> = {
  DAILY_SNAPSHOT: '每天',
  DAILY_WINDOW: '每天（读前天）',
  WEEKLY_WINDOW: '每周三（读上周）',
};

export const RUN_STATE_LABELS: Readonly<Record<string, string>> = {
  QUEUED: '排队中',
  LEASED: '已认领',
  RUNNING: '运行中',
  RETRY_WAIT: '等待重试',
  BLOCKED: '已卡住',
  SUCCEEDED: '成功',
  FAILED_TERMINAL: '失败',
};

export const EVENT_LABELS: Readonly<Record<string, string>> = {
  COLLECTED: '已采集',
  WAITING: '等待重试',
  BLOCKED: '已卡住',
  FAILED: '失败',
  SKIPPED: '已跳过',
  NORMALIZATION_STOPPED: '标准化停止',
  RECALCULATED: '已重算诊断',
  RECALCULATION_FAILED: '重算失败',
  INTERPRETED: '已生成周诊断',
  INTERPRETATION_FAILED: '周诊断失败',
};

/** Why a step stopped or was skipped. */
export const REASON_LABELS: Readonly<Record<string, string>> = {
  EVIDENCE_NOT_CURRENT: '核验已过期或不存在',
  ATTEMPTS_EXHAUSTED: '同一目标已尝试 3 次，留给人处理',
  EXECUTION_FAILED: '执行出错',
  NORMALIZATION_FAILED: '标准化出错',
  NOTHING_TO_PROCESS: '已全部标准化',
  READ_RETRY: '读取被限流或中断，稍后重试',
  PAGE_FAILED: '读取出错，稍后重试',
  CALL_CEILING_REACHED: '单次运行的调用数已到上限，下一轮继续',
  NOT_CLAIMABLE: '运行暂时不能认领，下一轮再试',
  RETRY_BUDGET_EXHAUSTED: '重试次数用完',
  JOB_NOT_EXECUTABLE: '采集任务不可执行（任务、授权或服务账号已失效）',
  BLOCKED: '运行被卡住，需要人处理',
};
