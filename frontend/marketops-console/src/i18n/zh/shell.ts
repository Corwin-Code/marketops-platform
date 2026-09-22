/**
 * Chinese text for the console shell: navigation, header, sign-in and the
 * platform-state panel.
 *
 * Domain screens keep their own wording beside their own code; this file holds
 * only what the frame around them says.
 */

/** Product identity. */
export const product = {
  name: 'MarketOps 运营控制台',
  tagline: '定价与利润、缺货风险、广告效率与 Listing 转化的日常运营工作台。',
} as const;

/** Business areas shown as menu groups and first breadcrumb level. */
export const areas = {
  pricing: '定价与利润',
  availability: '缺货风险',
  advertising: '广告效率',
  listing: 'Listing 转化',
  system: '系统',
} as const;

/** Menu entries and page titles. */
export const pages = {
  pricingQueue: '今日工作',
  pricingExport: '诊断导出',
  subject: '商品诊断',
  review: '审核调价建议',
  command: '执行记录',
  availabilityRisks: '风险队列',
  availabilityCases: '责任工单',
  availabilityAuthority: '供应权限',
  advertisingQueue: '工作队列',
  advertisingCase: '广告工单',
  advertisingOperations: '执行与止损',
  advertisingDaily: '每日简报',
  advertisingWeekly: '每周复盘',
  listing: '转化工作台',
  systemStatus: '系统状态',
  notFound: '页面不存在',
} as const;

/** One sentence per page saying what it is for. */
export const pageDescriptions = {
  pricingQueue: '按优先级排列今天最值得处理的商品，点击一行查看诊断证据与调价建议。',
  pricingExport: '导出本店铺的诊断数据文件，用于离线复核或存档。',
  subject: '查看该商品的指标证据、诊断结论与待审核的调价建议。',
  review: '核对建议的影响预估与护栏结果，决定批准或驳回。',
  command: '跟踪调价指令从提交、平台受理到回读核对的每一步，以及执行后的效果。',
  availabilityRisks: '按风险程度列出可能缺货的商品，优先处理最紧急的。',
  availabilityCases: '查看每个缺货问题的责任人、截止时间与已记录的处理动作。',
  availabilityAuthority: '登记在途补货凭证与按生效日期管理的交期策略；这里的操作不会调用平台接口。',
  advertisingQueue: '按优先级列出需要处理的广告工单，点击查看详情与处理建议。',
  advertisingCase: '查看广告工单的证据、建议与处理进展。',
  advertisingOperations: '查看广告当前的暂停、预算占用与阻止放行的原因，并在需要时处理。',
  advertisingDaily: '最新发布的每日广告行动简报。',
  advertisingWeekly: '最新发布的每周广告证据复盘。',
  listing: '诊断商品卡片的转化问题，并跟进内容与操作的处理。',
  systemStatus: '查看后端服务、数据库与数据结构版本的当前状态。',
} as const;

/** Header, navigation and session wording. */
export const shell = {
  mainNavigation: '主导航',
  breadcrumb: '当前位置',
  header: '页头',
  session: '会话',
  store: '店铺',
  storeCopied: '已复制店铺编号',
  signedInAs: '当前用户',
  anonymousUser: '已登录用户',
  signOut: '退出登录',
  expandMenu: '展开菜单',
  collapseMenu: '收起菜单',
  sessionExpiringTitle: '登录即将过期',
  sessionExpiringTag: (minutes: number) => `${String(minutes)} 分钟后登录过期`,
  sessionExpiringDescription: (minutes: number) =>
    `当前登录将在约 ${String(minutes)} 分钟后失效，请尽快保存正在进行的操作，过期后需要重新登录。`,
  notFoundDescription: '你访问的页面不存在，可能链接有误或页面已调整。',
  backHome: '返回今日工作',
  backToQueue: '返回今日工作',
  backToDiagnosis: '返回商品诊断',
  missingRecommendationTitle: '没有可审核的建议',
  missingRecommendationDescription:
    '请从商品诊断页面打开一条调价建议再进行审核（刷新页面后需要重新打开）。',
  completingSignIn: '正在完成登录…',
  reauthenticateNow: '立即重新登录',
  reauthenticateFailed: '无法开始重新登录，请稍后重试。',
} as const;

/** Sign-in screen wording. */
export const signIn = {
  region: '登录',
  explanation: '将跳转到你所在组织的身份认证服务完成登录，登录需要第二重验证。',
  button: '登录',
  startFailed: '无法开始登录，请稍后重试。',
  expired: '登录已过期，请重新登录',
  consoleRegion: '运营控制台',
  notConfiguredTitle: '未配置身份认证服务',
  notConfiguredDescription:
    '当前部署没有配置身份认证服务，无法在此登录运营控制台。下方仍会报告平台状态。',
} as const;

/** Why a sign-in did not produce a session. */
export const signInFailures = {
  denied: '身份认证服务拒绝了此次登录，未做任何更改。',
  stateMismatch: '此次登录不是从当前标签页发起的，请在本页重新登录。',
  exchangeFailed: '身份认证服务未能完成登录，请重试。',
  unreadable: '身份认证服务返回了控制台无法识别的内容。',
} as const;

/** Configuration error screen wording (developer-facing). */
export const configuration = {
  region: '配置错误',
  title: '控制台配置不完整',
  description: '需要提供以下环境变量后控制台才能启动：',
  hint: '生成本地环境文件后重启控制台。',
} as const;

/** Platform-state panel wording. */
export const health = {
  region: '平台状态',
  detailsRegion: '平台详情',
  title: '平台状态',
  detailsTitle: '平台上报的信息',
  usable: '平台可用',
  notUsable: '平台暂不可用',
  checkAgain: '再次检查',
  checking: '检查中…',
  application: '应用',
  environment: '环境',
  backendVersion: '后端版本',
  backendCommit: '后端提交',
  schemaVersion: '数据结构版本',
  database: '数据库',
  databaseUp: '正常',
  databaseDown: (raw: string) => `异常（${raw}）`,
  serverTime: '服务器时间',
  correlationId: '追踪编号',
  buildRegion: '控制台版本',
  build: (version: string, commit: string) => `控制台 ${version}（${commit}）`,
  pointedAt: (apiBaseUrl: string, environment: string) => `连接 ${apiBaseUrl} · ${environment}`,
} as const;

/** Known environment names, shown as a tag. */
const ENVIRONMENT_LABELS: Readonly<Record<string, string>> = {
  local: '本地环境',
  dev: '开发环境',
  development: '开发环境',
  test: '测试环境',
  ci: '测试环境',
  staging: '预发环境',
  pilot: '试点环境',
  prod: '生产环境',
  production: '生产环境',
};

/** Chinese name of an environment; an unknown name is shown as configured. */
export function environmentLabel(environment: string): string {
  const key = environment.trim().toLowerCase();
  return Object.hasOwn(ENVIRONMENT_LABELS, key)
    ? (ENVIRONMENT_LABELS[key] ?? environment)
    : `环境：${environment}`;
}

/** Whether an environment is production, so the header can warn in colour. */
export function isProductionEnvironment(environment: string): boolean {
  const key = environment.trim().toLowerCase();
  return key === 'prod' || key === 'production';
}

/** A long identifier shortened for a header or breadcrumb; the full value stays copyable. */
export function shortId(id: string, keep = 8): string {
  return id.length > keep + 2 ? `${id.slice(0, keep)}…` : id;
}
