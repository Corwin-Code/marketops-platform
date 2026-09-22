/**
 * Chinese text for the calibration package page (校准包).
 *
 * Stage, step, blocker, category and failure names come from the shared
 * listing code labels, so the list, the detail and the dialogs call the same
 * thing by the same name.
 */

export const calibrationText = {
  intro:
    '校准包确定 Listing 决策使用的规则参数：内容与曝光的重要性触发条件、时效、额度维度、评估节点等。某个用途、某个范围没有生效中的校准包时，依赖它的动作一律判为「校准未解析」，系统不会替你补任何默认值。',
  introBoundary:
    '起草、校验、接受、启用只改变本系统的规则，不调用任何平台接口；每一步都需要近期重新登录验证身份，并记入审计。',
  helpTitle: '谁来做、按什么顺序',
  help: [
    '专业人员（例如运营负责人）起草校准包并校验：确认参数完整、组合有效。起草人可以自己校验。',
    'Owner 独立接受：接受人必须不同于起草人和校验人，由数据库强制执行。',
    'Owner 启用：此后该范围、该用途的动作使用这个包；同范围、同用途、时间重叠的旧包会整包退役。',
    '草稿提交后不能修改，也不能撤回；如需更正，请用新的版本号重新起草。',
    '所有用途读取原生范围时效时，都用生效中的「正式转化改善」校准包，因此它需要最先生效。',
    '同一用途有多级校准包时，按 店铺 > 平台 > 组织 取最具体的一级。',
  ],
  refresh: '刷新',
  draft: '起草校准包',
  draftDisabled: '你没有起草校准包的授权',
  currentTitle: '各用途当前生效',
  currentNone: '当前没有生效中的校准包，依赖该用途的动作会被判为校准未解析。',
  currentMore: (count: number) => `另有 ${String(count)} 个范围`,
  filterPurpose: '用途',
  filterStage: '阶段',
  filterScope: '范围',
  filterStore: '店铺',
  filterAll: '全部',
  stagePending: '进行中（待校验、待接受、待启用）',
  clearFilters: '清除筛选',
  inProgress: '进行中与生效中',
  history: '历史',
  historyHelp: '已到期、已退役的包，以及已有更新版本或已过有效期、不会再推进的草稿。',
  historyCount: (count: number) => `${String(count)} 个`,
  noPackages: '还没有任何校准包。',
  noMatches: '没有符合筛选条件的校准包。',
  noInProgress: '没有进行中或生效中的校准包。',
  noHistory: '暂无历史。',
  columnPackage: '校准包',
  columnPurpose: '用途',
  columnScope: '范围',
  columnStage: '阶段',
  columnEffective: '生效时间',
  columnGovernance: '起草 / 校验 / 接受 / 启用',
  columnIntegrity: '完整性',
  columnNext: '下一步',
  versionLabel: (version: number) => `第 ${String(version)} 版`,
  newerVersion: '已有更新版本',
  expiredDraft: '已过有效期',
  from: '起',
  until: '至',
  openEnded: '长期',
  organizationScope: '整个组织',
  storeUnknown: '未知店铺',
  unknownPerson: '未知',
  you: '（你）',
  missingCount: (count: number) => `缺 ${String(count)} 类`,
  failureCount: (count: number) => `${String(count)} 项组合问题`,
  digestChanged: '内容指纹不一致',
  complete: '完整',
  notEvaluated: '—',
  nextNone: '无',
  nextReady: '可执行',
  nextStepUp: '需重新登录',
  governanceDrafted: '起草',
  governanceValidated: '校验',
  governanceAccepted: '接受',
  governanceActivated: '启用',
  governanceRetired: '退役',

  // detail
  detailTitle: '校准包详情',
  deriveNew: '基于此版本新建',
  deriveNewDisabled: '你没有在该范围起草校准包的授权',
  sectionSummary: '概要',
  sectionIntegrity: '完整性检查',
  sectionValues: '参数值',
  sectionTimeline: '治理记录',
  labelPurpose: '用途',
  labelScope: '范围',
  labelStage: '阶段',
  labelEffective: '生效区间',
  labelEvidence: '包依据',
  labelRationale: '起草理由',
  labelImpact: '影响',
  labelDifferences: '与前一版的差异',
  labelReplaces: '替换',
  labelReplacedBy: '被替换为',
  labelNone: '无',
  openPackage: '查看',
  missingTitle: '缺少该用途必需的参数类别',
  missingHelp:
    '缺少必需类别的包不能校验，也不能启用；依赖该用途的动作仍然是校准未解析。草稿不能修改，请以新版本补齐后重新起草。',
  failuresTitle: '参数组合未通过数据库检查',
  failuresHelp: '校验和启用都会被数据库拒绝。草稿不能修改，请以新版本更正后重新起草。',
  digestTitle: '内容指纹与起草时不一致',
  digestHelp: '包的内容在起草后发生了变化，数据库不会接受任何一步。请联系技术支持核查。',
  integrityOk: '必需类别齐全，组合检查通过。',
  integrityNotEvaluated: '组合检查只对草稿进行；该包已不是草稿。',
  supersededNote: (latest: number) =>
    `该编码已有第 ${String(latest)} 版；这份草稿通常不会再推进，保留在历史中。`,
  expiredNote: '这份草稿的有效期已过，不能再接受或启用。',
  replacementPlanned: (name: string) => `启用时，将退役当前生效的 ${name}。`,
  replacementNone: '启用时不替换任何生效中的包。',
  replacementMismatch:
    '当前生效的包与起草时声明的替换对象不一致，这份草稿无法启用；请以新版本重新起草（替换对象会自动计算）。',
  replacementConflict: '同范围、同用途有多个时间重叠的生效包，无法确定替换对象，不能启用。',
  notYetEffective: (from: string) => `生效时间未到，${from} 之后才能启用。`,
  valueExample: '示例值，需按真实业务确认',
  valueNumeric: '数值',
  valueText: '文本',
  valueJson: 'JSON',
  valueUnit: '单位',
  valueWindow: '窗口',
  valueWindowDays: (days: number) => `${String(days)} 天`,
  valueNote: '适用说明',
  valueEvidence: '依据',
  groupMissing: (count: number) => `缺 ${String(count)} 类`,
  groupCount: (count: number) => `${String(count)} 类`,
  noValues: '该组没有参数。',
  eventBy: (name: string) => `操作人：${name}`,
  eventReference: '依据',
  eventDigest: '内容指纹',
  noEvents: '暂无记录。',
  technicalId: '校准包 ID',
  technicalCurrentDigest: '当前内容指纹',
  technicalDraftDigest: '起草指纹',
  technicalValidatedDigest: '校验指纹',
  technicalAcceptedDigest: '接受指纹',

  // steps
  stepTitle: {
    VALIDATE: '校验校准包',
    ACCEPT: '接受校准包',
    ACTIVATE: '启用校准包',
  } as Readonly<Record<string, string>>,
  stepConsequence: {
    VALIDATE:
      '确认内容完整、参数组合有效。校验以当前内容指纹为准；校验后等待 Owner 独立接受，在接受之前仍可再次校验。',
    ACCEPT:
      '以当前内容指纹接受这份经过专业校验的校准包。接受人必须不同于起草人和校验人，由数据库强制执行。接受后还需要启用才会生效。',
    ACTIVATE:
      '启用后，该范围、该用途的动作从此使用这个校准包。启用不能撤销；更正只能通过起草、校验、接受、启用新版本完成。',
  } as Readonly<Record<string, string>>,
  stepEvidence: {
    VALIDATE: '校验依据',
    ACCEPT: '接受依据',
    ACTIVATE: '启用依据',
  } as Readonly<Record<string, string>>,
  stepEvidencePlaceholder: '例如：2026-09 校准评审记录编号、会议纪要链接',
  stepEvidenceHelp: '会记入治理记录与审计。不要粘贴长串编码、密钥或内容指纹本身。',
  stepEvidenceRequired: '请填写依据',
  stepDone: {
    VALIDATE: '已校验，等待 Owner 接受',
    ACCEPT: '已接受，等待启用',
    ACTIVATE: '已启用',
  } as Readonly<Record<string, string>>,
  stepDigest: '本次确认的内容指纹',
  stepDigestHelp: '自动取自该包当前阶段应确认的指纹，与数据库计算的一致时才会被接受。',
  stepPackage: '校准包',
  stepDrafter: '起草人',
  stepValidator: '校验人',
  stepIndependence:
    '独立性：接受人不能是起草人或校验人。同一个人起草或校验过的包，需要另一位持有接受授权的人来接受。',
  stepOwnerWarning: '你持有接受授权。若由你校验这份包，之后需要另一位持有接受授权的人来接受它。',
  stepAcceptors: (names: string) => `可以接受此包的人：${names}`,
  stepNoAcceptors: '目前没有其他持有接受授权的人可以接受此包（起草人和校验人除外）。',
  stepUpTitle: '该操作需要近期重新登录验证身份',
  stepUpHelp: '重新登录后回到本页再提交；已填写的依据不会保存。',
  stepUpAction: '重新登录',
  stepBlocked: (reasons: string) => `现在不能执行：${reasons}`,
  stepNotGranted: {
    VALIDATE: '你没有校验该范围校准包的授权',
    ACCEPT: '只有持有接受授权的人（Owner）可以接受',
    ACTIVATE: '只有持有接受授权的人（Owner）可以启用',
  } as Readonly<Record<string, string>>,

  // draft
  draftTitle: '起草校准包',
  deriveTitle: (code: string, version: number) =>
    `起草新版本：${code} · 第 ${String(version)} 版之后`,
  stepBasics: '基本信息',
  stepValues: '参数值',
  stepReview: '核对提交',
  submit: '创建草稿',
  created: (code: string, version: number) =>
    `已创建草稿 ${code} · 第 ${String(version)} 版，等待校验`,
  exampleBanner:
    '表单已按所选用途预填本地合成的示例值（与本地走查验证过的一组参数一致），需按真实业务逐项确认后再提交。',
  purpose: '用途',
  purposeHelp: '决定需要哪些参数类别；缺任何一类都不能校验或启用。',
  scopeKind: '适用范围',
  store: '店铺',
  platform: '平台',
  pickStore: '选择店铺',
  pickPlatform: '选择平台',
  scopeOrganizationDisabled: '需要组织级起草授权',
  code: '包编码',
  codeHelp: '小写字母、数字和连字符，首尾不能是连字符，最长 63 个字符。',
  codeInvalid: '只能使用小写字母、数字和连字符，首尾不能是连字符，最长 63 个字符',
  version: '版本号',
  versionHelp: '同一编码的版本号在组织内唯一；默认取已有最高版本加一。',
  versionInvalid: '版本号须为正整数',
  versionTaken: '该编码的这个版本号已经存在',
  effectiveFrom: '生效时间',
  effectiveFromHelp: '莫斯科时间。启用时必须已到这个时间；默认 5 分钟前。',
  effectiveTo: '失效时间（可选）',
  effectiveToHelp: '留空表示长期有效。',
  effectiveToAfter: '失效时间必须晚于生效时间',
  replacement: '替换对象',
  replacementAuto: '按同范围、同用途、时间重叠的生效包自动计算，不能手工指定。',
  replacementWill: (name: string) => `将替换当前生效的 ${name}（启用时整包退役）`,
  replacementWillNone: '不替换任何生效中的包',
  replacementMany: '同范围、同用途有多个时间重叠的生效包，无法确定替换对象，不能提交',
  evidence: '包依据',
  evidencePlaceholder: '例如：校准评审记录编号、测算表链接',
  rationale: '起草理由',
  impact: '影响',
  differences: '与前一版的差异',
  differencesPlaceholder: (version: number) => `写明与第 ${String(version)} 版相比改了什么、为什么`,
  textRequired: (label: string) => `请填写${label}`,
  ownerDraftWarning:
    '你持有接受授权。若由你起草或校验这份包，之后需要另一位持有接受授权的人来接受它。',
  useExample: '恢复示例值',
  format: '格式化',
  fillEvidence: '所有参数沿用包依据',
  removeExtra: '移除',
  extraCategory: '非必需类别',
  extraHelp: '来源版本带有该用途不要求的类别，会原样带入；可以移除。',
  unitPlaceholder: '单位代码',
  windowPlaceholder: '选择窗口',
  numericPlaceholder: '例如 0.05',
  jsonPlaceholder: 'JSON',
  profitJson: '当期会计参照（可选 JSON）',
  profitJsonHelp:
    '可留空。填写时须为 {"currentAccountingComparisons": {...}}，键为本组织的 Listing ID。',
  scopeNote: '适用说明',
  reviewSummary: '草稿概要',
  reviewValues: '参数值',
  reviewFindings: '提交前检查',
  reviewErrors: '以下问题会被数据库拒绝，必须先更正',
  reviewWarnings: '以下问题数据库不会拒绝，但使用这个包的动作会判为未解析或不合格',
  reviewClean: '前端检查未发现问题。数据库在校验时会再完整检查一次。',
  reviewAcknowledge: '我已确认上述提示，仍要创建草稿',
  reviewAcknowledgeRequired: '请确认上述提示',
  reviewImmutable:
    '提交只创建草稿：内容不可再修改，也不能撤回；如需更正，须以新版本重新起草。不会调用任何平台接口。',
  reviewNextSteps: '创建后由专业人员校验，再由 Owner 独立接受并启用。',
  reviewColumnCategory: '类别',
  reviewColumnValue: '值',
  reviewColumnUnit: '单位',
  reviewColumnWindow: '窗口',
  catalogueMissing: '参数类别目录没有加载成功，暂时不能起草。',
} as const;

/** Help for each category, shown beside its name. */
export const calibrationCategoryHelp: Readonly<Record<string, string>> = {
  MATERIAL_IMPROVEMENT_BOUND: '转化率相对提升达到多少算实质改善。0–1 的比例，单位 RATIO。',
  NON_WORSENING_PROFIT_BOUND:
    '利润允许下降的界限，不能为负；单位 RATIO，窗口 7、14 或 30 天，须与退货率界限同窗口。',
  NON_WORSENING_RETURN_BOUND: '退货率允许上升的界限，0–1 的比例；窗口须与利润界限相同。',
  CRITICAL_GROUP_RULE:
    '{"groups":[{"code":"大写编码","bound":0–1}]}；不设关键分组时写 {"groups":[]}。可选 protectionScopeBases。',
  DEMAND_SCENARIO_SET:
    'JSON 对象，supplyScenarios 与 economicScenarioBases 都可选；写 {} 能通过校验，但促销与探索的消费方会报告情景缺失。',
  FRESHNESS_RULE:
    'nativeScope（MANUAL_ENTRY / MARKETPLACE_RAW 的 maximumAgeSeconds）、materialityExposure 与 businessProtection 的两个最大年龄，均为正整数秒。',
  RESPONSIBILITY_SLO:
    'acknowledgementMinutes、actionMinutes（1–527040 分钟）与 outcomeMaturityDays（1–3660 天），整数；可选 necessaryRisk 同样三项。',
  RESPONSIBILITY_COVERAGE:
    'timezone（IANA 时区）、days（1–7，周一为 1）、startMinute 与 endMinute（0–1439，不相等；开始大于结束表示跨夜）。',
  ORDINARY_TRIGGER_CONTENT:
    '{"model":"LC_MEANING_CONDITIONS_1","description":[{"code","condition"}]}；促销用途放在 promotion 键下。每类最多 16 条，编码在两份文档之间不能重复。',
  MATERIAL_TRIGGER_CONTENT: '与普通触发条件同一格式；编码不能与普通触发条件重复。',
  ORDINARY_TRIGGER_EXPOSURE:
    '受影响变体留存销售额占比不高于该值时为普通曝光。0–1 的比例，须不大于重大界限；窗口与重大界限相同。',
  MATERIAL_TRIGGER_EXPOSURE:
    '占比不低于该值时为重大曝光；介于两个界限之间时重要性未确定。0–1 的比例。',
  APPROVAL_VALIDITY: '审批的有效期，正整数，单位 MINUTES、HOURS 或 DAYS。',
  REPRESENTATION_EQUIVALENCE_RULE: '回读文本与目标文本如何比较：完全一致，或忽略空白差异。',
  ALLOWANCE_AXES:
    '启动时占用哪些额度维度：["CONCURRENT_LISTINGS", ...]，或 {"axes":[...],"scopeComposition":"ALL_APPLICABLE"}。',
  ALLOWANCE_RESERVE:
    '每个额度维度要求的处置余量，{"维度":数值}，最多 4 位小数；须覆盖所有额度维度。',
  FORMAL_NODES:
    '1–8 个评估节点：nodeCode、maturityDays（7/14/30）、method、threshold；登记方法还需要 methodParameters 与 schedule。',
  STOP_RULE: '必须是 JSON 对象；{} 表示明确不启用。启用时须指向使用登记方法的节点。',
  CROSS_PERIOD_WINDOW: '跨期利润观察窗口，0–3660 的整数，单位 DAYS。',
  DESCRIPTION_LENGTH_RULE: '{"min":整数,"max":整数}，0 ≤ min ≤ max ≤ 65536，按 Unicode 码点计。',
};

/** Group names for the parameter sections. */
export const calibrationGroups: Readonly<Record<string, string>> = {
  trigger: '重要性触发',
  protection: '经营保护',
  timing: '时效与责任',
  allowance: '额度',
  evaluation: '评估计划',
  description: '描述规则',
};

/** Unit names; a unit not listed is shown as its code. */
export const calibrationUnits: Readonly<Record<string, string>> = {
  RATIO: '比例（小数，0.05 = 5%）',
  DAYS: '天',
  HOURS: '小时',
  MINUTES: '分钟',
  SECONDS: '秒',
  CONDITIONS: '条件文档',
  RULE: '规则',
  SLO: '时限',
  CALENDAR: '在岗日历',
  AXES: '额度维度',
  AXIS_VALUE: '维度数值',
  NODES: '节点',
  SCENARIOS: '情景',
  CHARACTERS: '字符',
};

/** Why a value or a combination is refused (error) or unusable (warning). */
export const calibrationRules = {
  numberFormat: '请输入数字：最多 12 位整数、6 位小数',
  ratioRange: '须在 0 到 1 之间（含）',
  notNegative: '不能为负数',
  positiveInteger: '须为正整数',
  daysRange: '须为 0–3660 的整数',
  unitFormat: '单位代码须为大写字母开头，只含大写字母、数字和下划线，最长 32 个字符',
  unitOneOf: (units: string) => `单位须为 ${units}`,
  unitRecommended: (unit: string) => `使用这个包的动作要求单位为 ${unit}`,
  windowRequired: '请选择窗口',
  windowAllowed: '窗口须为 7、14 或 30 天',
  textRequired: '请选择',
  jsonRequired: '请填写 JSON',
  jsonParse: (detail: string) => `JSON 格式错误：${detail}`,
  jsonTooLarge: '数字超出可精确表示的范围',
  mustBeObject: '须为 JSON 对象 {…}',
  mustBeArray: '须为 JSON 数组 […]',
  // content
  meaningModel: 'model 须为 "LC_MEANING_CONDITIONS_1"',
  meaningKeys: '只允许 model、description、promotion 三个键',
  meaningNeedsList: '至少需要 description 或 promotion 之一',
  meaningList: (key: string) => `${key} 须为数组，最多 16 条`,
  meaningRule: (key: string) =>
    `${key} 中每条须为 {"code","condition"}：code 为大写字母开头的 2–64 位编码，condition 为 1–2000 字`,
  meaningDuplicate: (key: string) => `${key} 中的 code 不能重复`,
  meaningPurposeKey: (key: string) => `该用途读取 ${key} 键下的条件，这里没有任何条件`,
  // formal nodes
  nodesCount: '须有 1–8 个节点',
  nodeShape: (index: number) =>
    `第 ${String(index)} 个节点不合格：nodeCode 为大写编码且不重复，maturityDays 为 7、14 或 30，method 非空，threshold 为非负小数`,
  nodeMethod: (index: number) => `第 ${String(index)} 个节点使用未登记的方法，评估时无法使用`,
  nodeThreshold: (index: number) => `第 ${String(index)} 个节点的 threshold 须大于 0 且不大于 1`,
  nodeParameters: (index: number) =>
    `第 ${String(index)} 个节点的 methodParameters 不合格：samplingModel 为 INDEPENDENT_BERNOULLI_VISITS，familyAlpha 与 nodeAlpha 在 0 与 1 之间，qualificationRef 非空`,
  nodeSchedule: (index: number) =>
    `第 ${String(index)} 个节点的 schedule 不合格：四个偏移为 0–3660 的整数，开始 < 结束，notBefore ≥ 结束 + 成熟天数，last ≥ notBefore`,
  nodeAlphaSum: '各节点 nodeAlpha 之和超过了 familyAlpha',
  nodeComparison: (index: number) =>
    `第 ${String(index)} 个节点的 protectionComparison 不合格：方法 CANONICAL_ACCOUNTING_CHANGE_V1、qualificationRef 非空、参考期为带时区的时间且已结束，长度等于评估窗口`,
  // stop rule
  stopRuleShape:
    '停止规则须为 {} 或 {"nodeCode","trigger":"QUALIFIED_FUTILITY","method":{"code","upperBoundRequired":true,"qualificationRef","minimumEffect"}}',
  stopRuleNode: '停止规则的 nodeCode 须指向使用登记方法的正式评估节点',
  // freshness
  freshnessNative:
    'nativeScope 须为 {"MANUAL_ENTRY"|"MARKETPLACE_RAW": {"maximumAgeSeconds": 正整数}}',
  freshnessNativeMissing: '缺少 nativeScope：所有用途的原生范围时效都从生效中的正式转化改善包读取',
  freshnessMateriality:
    'materialityExposure 须含 maximumVerificationAgeSeconds 与 maximumPeriodEndAgeSeconds 两个正整数',
  freshnessMaterialityMissing: '缺少 materialityExposure，曝光轴重要性无法判定',
  freshnessBusiness:
    'businessProtection 的 maximumVerificationAgeSeconds 与 maximumPeriodEndAgeSeconds 须为正整数',
  freshnessBusinessMissing: '缺少 businessProtection，当期财务输入的时效无法判定',
  // responsibility
  sloShape:
    '须含 acknowledgementMinutes、actionMinutes（1–527040 的整数）与 outcomeMaturityDays（1–3660 的整数）',
  sloRisk: 'necessaryRisk 须含同样三项整数',
  coverageShape:
    '须含 timezone（IANA 时区）、days（1–7 不重复的非空数组）、startMinute 与 endMinute（0–1439 且不相等）',
  // allowance
  axesShape: '须为 1–4 个不重复的额度维度数组，或 {"axes":[…],"scopeComposition":"ALL_APPLICABLE"}',
  reserveShape: '每个维度的余量须为不超过 4 位小数的非负数',
  reserveUnknownAxis: (axis: string) => `未知的额度维度 ${axis}`,
  reserveMissingAxis: (axes: string) => `处置余量缺少这些额度维度：${axes}`,
  // other JSON
  lengthShape: '须为 {"min":整数,"max":整数}，0 ≤ min ≤ max ≤ 65536',
  groupsShape: 'groups 须为数组，每项 code 为大写编码且不重复，bound 为 0–1 的数',
  protectionBases:
    'protectionScopeBases 不合格：每个 Listing 须有 evidenceReference、linkedProfitScopes 与 criticalReturnVariantIds，成员不重复',
  existenceNote: '引用的 Listing、变体或商品是否存在，只能在数据库校验时确认',
  scenariosMissing: '未提供需求或供给情景：促销与探索的消费方会报告情景缺失',
  supplyShape:
    'supplyScenarios 须为非空数组，每项含 code、productVariantId、evidenceReference、companyDailyFulfillmentUnits（≥0）与 coverageDays（正整数），同一变体的 code 不重复',
  economicShape:
    'economicScenarioBases 不合格：每个 Listing 须有依据、最低贡献利润、币种、利润依据、带时区的起止时间与 1–64 个保守情景',
  comparisonsShape:
    'currentAccountingComparisons 不合格：每个 Listing 须有 evidenceReference 与带时区的 periodStart < periodEnd',
  // combination
  exposureOrder: '普通曝光界限不能大于重大曝光界限',
  exposureWindows: '两个曝光界限的窗口须相同',
  contentDuplicate: '普通与重大内容条件的 code 不能重复',
  boundsWindows: '利润与退货率界限须使用相同的窗口',
  boundsRatio: '有会计参照或会计对比时，利润与退货率界限须为单位 RATIO、0–1 的比例',
  boundsUnits: '利润与退货率界限的单位须为 RATIO',
  secret:
    '内容疑似包含密钥或长串编码（例如 64 位以上的连续字母数字），会被拒绝；请改用简短的编号或链接',
  replacementMany: '同范围、同用途有多个时间重叠的生效包，无法确定替换对象',
} as const;
