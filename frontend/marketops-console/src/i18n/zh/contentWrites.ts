import type { CodeLabels } from '../labels';

/**
 * Chinese text for writing a listing's title and description to the marketplace (W2): the editor
 * in the content drawer, what became of a change, and the content-write switches.
 */

/** The editor in the content drawer. */
export const contentEditorText = {
  title: '修改描述并写入 Ozon',
  hint: '在这里改定最终的描述，确认后由平台写入 Ozon：先回读确认卡片仍是下面的当前文本，再写入、查询任务、回读核对。确认即审批，批准 24 小时内有效。',
  titleLocked:
    '标题暂时不能在这里修改：2026-10-02 灰度时 Ozon 任务显示已导入、商品卡审核通过，但标题没有变化（这个接口不接受标题修改）。平台只写描述，标题按卡片现有文本原样提交。',
  open: '编辑并写入',
  close: '收起',
  fieldTitle: '标题',
  fieldDescription: '描述',
  current: '当前',
  currentObservedAt: '当前文本来自目录快照',
  noCurrent: '还没有这张商品卡的标题或描述快照，暂时不能写入。',
  useDrafts: '用 Qwen 描述草稿填入',
  resetToCurrent: '恢复当前文本',
  changed: '已修改',
  unchanged: '未修改',
  locked: '暂不可改',
  nothingChanged: '描述和当前一致，没有要写入的内容。',
  descriptionShort: (n: number): string =>
    `描述现在是 ${String(n)} 个字符：Ozon 内容评分里“描述超过 500 字符”可得 25 分。`,
  titleLimit: (n: number): string => `标题最多 ${String(n)} 个字符（Ozon 官方限制）。`,
  confirm: '确认写入',
  confirmTitle: '确认写入 Ozon',
  confirmConsequence:
    '确认后，平台会在写入闸门放行时修改 Ozon 上这张商品卡的描述：写入前先回读，卡片若已被改动就不写；写入后查询任务并回读核对。标题按卡片现有文本原样提交。需要最近登录。',
  reason: '修改原因',
  reasonPlaceholder: '可不填，默认为“Owner 确认修改描述”',
  submitted: '已确认，等待写入',
  liveCommand: '这张商品卡还有一个未完成的修改，完成或关闭后才能再提交。',
  latest: '最近一次修改',
} as const;

/** One content command. */
export const contentCommandText = {
  state: '状态',
  offer: '货号',
  approvedAt: '确认时间',
  approvalExpiresAt: '批准有效至',
  nextActionAt: '下次处理',
  outcome: '结果',
  gate: '闸门',
  gateOpen: '已放行',
  task: 'Ozon 任务',
  titleChange: '标题',
  descriptionChange: '描述',
  before: '原文',
  after: '新文本',
  unchangedField: '不变',
  timeline: '执行记录',
  readback: '重新回读',
  readbackTitle: '重新回读这张商品卡',
  readbackConsequence:
    '平台会按回读计划重新读取 Ozon 上的标题和描述，与新文本比对。不会再次写入。需要最近登录。',
  closeCommand: '关闭',
  closeTitle: '关闭这个修改',
  closeConsequence:
    '尚未写入的修改会被撤回；已写入或结果未知的修改改由人工跟进，平台不再自动处理。需要最近登录。',
  reason: '原因',
  reasonRequired: '请填写原因',
  refreshed: '已更新',
  observedTitle: '回读到的标题',
  observedDescription: '回读到的描述',
} as const;

/** Command states. */
export const CONTENT_STATE_LABELS: CodeLabels = {
  PENDING: '等待写入',
  AWAITING_TASK: '等待 Ozon 处理',
  AWAITING_READBACK: '回读核对中',
  UNKNOWN_REQUIRES_READBACK: '结果未知，回读中',
  READBACK_MISMATCH: '回读不一致',
  SUCCEEDED: '已生效',
  FAILED_BEFORE_WRITE: '未写入',
  FAILED: '写入失败',
  CANCELLED: '已撤回',
  CLOSED: '已关闭',
};

/** Event kinds of the timeline. */
export const CONTENT_EVENT_LABELS: CodeLabels = {
  CREATED: '确认修改',
  GATE_CLOSED: '闸门未放行',
  PRE_READ: '写入前回读',
  APPLY_STARTED: '开始写入',
  APPLY: '写入',
  STATUS: '查询任务',
  READBACK: '回读',
  STATE: '状态变更',
  RESOLUTION: '人工处置',
};

/** How a read-back field compares with the change. */
export const CONTENT_MATCH_LABELS: CodeLabels = {
  MATCHES_TARGET: '与新文本一致',
  MATCHES_PRIOR: '仍是原文',
  DIFFERENT: '与两者都不同',
};

/** Why a command ended or waits. */
export const CONTENT_OUTCOME_LABELS: CodeLabels = {
  readback_matched: '回读与新文本一致',
  already_applied: '卡片已是新文本，未重复写入',
  approval_expired: '批准已过期',
  prior_text_moved: '卡片已被改动，未写入',
  pre_read_failed: '写入前回读失败',
  platform_rejected: 'Ozon 拒绝了请求',
  platform_task_failed: 'Ozon 任务失败',
  platform_rate_limited: 'Ozon 限流，稍后重试',
  platform_error_after_dispatch: 'Ozon 服务端错误，结果未知',
  apply_outcome_unknown: '写入结果未知',
  apply_answer_lost_with_worker: '写入后进程中断，结果未知',
  task_key_missing: '响应缺少任务号',
  task_status_unresolved: '任务状态未确定，以回读为准',
  readback_still_prior: '回读多次仍是原文',
  readback_different: '回读到与新旧都不同的文本',
  readback_unavailable: '回读多次未成功',
  readback_requested: '已请求重新回读',
  closed_by_person: '已人工关闭',
  write_operation_not_verified: '写入能力未核验',
  credential_unresolvable: '写入凭证不可用',
  authentication_not_recorded: '认证配置缺失',
  request_could_not_be_built: '请求无法生成',
  rate_limit_window_exhausted: '接口限额已用完',
  transport_failed: '网络中断',
};

/** Why the gate stays closed, or what stops a store's content writes. */
export const CONTENT_GATE_LABELS: CodeLabels = {
  CAPABILITY_NOT_REGISTERED: '内容写入能力未登记',
  CAPABILITY_NOT_VERIFIED: '内容写入能力未核验',
  CAPABILITY_EVIDENCE_NOT_CURRENT: '能力证据已过期',
  CAPABILITY_EVIDENCE_EXPIRING: '能力证据 7 天内过期',
  CAPABILITY_NOT_AVAILABLE_FOR_STORE: '能力对本店未开放',
  CAPABILITY_SWITCH_DISABLED: '能力开关未开',
  GLOBAL_SWITCH_DISABLED: '总开关未开',
  SCOPED_SWITCH_DISABLED: '店铺或平台范围被关闭',
  ENTITY_NOT_ALLOWLISTED: '商品不在白名单',
  APPROVAL_EXPIRED: '批准已过期',
  PRODUCTION_WRITES_DISABLED: '部署未开启生产写入',
  WORKER_DISABLED: '本机未运行内容写入 worker',
  WRITE_CREDENTIAL_UNRESOLVED: '没有可用的内容写入凭证',
  COMMAND_NOT_FOUND: '指令不存在',
};

/** The content-write switches on the guardrails page. */
export const contentSwitchesText = {
  title: '内容写入开关与白名单（W2）',
  hint: '控制平台能否修改 Ozon 商品卡的标题和描述：部署级生产写入开关、内容写入开关（总开关与能力开关都要打开）和白名单。关闭随时可用；打开需要最近登录。',
  productionWrites: '部署生产写入',
  worker: '本机 worker',
  capability: '内容写入能力',
  capabilityNone: '未登记',
  evidenceUntil: '证据有效至',
  storeAvailability: '对本店开放',
  globalSwitch: '总开关',
  capabilitySwitch: '能力开关',
  readiness: '就绪情况',
  ready: '就绪',
  on: '开',
  off: '关',
  enable: '打开内容写入',
  enableTitle: '打开内容写入',
  enableConsequence: '打开能力开关和总开关后，已确认且在白名单内的修改会写入 Ozon。需要最近登录。',
  enableBlocked: '需要先开启部署生产写入并完成能力登记',
  disable: '关闭内容写入',
  disableTitle: '关闭内容写入',
  disableConsequence: '立即停止新的内容写入；已在执行中的指令不受影响，会继续回读。',
  moved: '已更新开关',
  allowlist: '白名单',
  grant: '加入白名单',
  grantTitle: '把商品加入内容写入白名单',
  grantConsequence: '在有效期内，这些商品的已确认修改可以写入 Ozon。需要最近登录。',
  granted: '已加入白名单',
  revokeConsequence: '撤销后，这个范围内的商品不会再被平台写入标题和描述。',
  recent: '最近的内容修改',
  noRecent: '还没有内容修改',
} as const;
