import type {
  CalibrationDraftValue,
  CalibrationPackage,
  CalibrationPurpose,
  CalibrationValue,
  CalibrationValueShape,
} from '../api/listingCalibrations';
import { calibrationRules as rule } from '../i18n/zh/listingCalibrations';

/**
 * Calibration categories as the draft form edits them: their shape and units,
 * the local example values, conversion to and from the draft body, and the
 * checks the database and the consumers make.
 *
 * Checks marked as errors are the ones `ops.prepare_lc_calibration`,
 * `ops.lc_calibration_combination_failures` or a table CHECK refuse (V0001).
 * Warnings are the ones only the consumers check later, where a bad value
 * turns an action unresolved or unqualified. The database still checks
 * everything itself; these only spare a draft that could never be validated,
 * because a draft cannot be edited or withdrawn once it exists.
 */

// ------------------------------------------------------------------ categories

export type CategoryGroup =
  'trigger' | 'protection' | 'timing' | 'allowance' | 'evaluation' | 'description';

export const GROUP_ORDER: readonly CategoryGroup[] = [
  'trigger',
  'protection',
  'timing',
  'allowance',
  'evaluation',
  'description',
];

export interface CategorySpec {
  readonly shape: CalibrationValueShape;
  readonly group: CategoryGroup;
  /** Units offered, the suggested one first. */
  readonly units: readonly string[];
  /** Whether a unit outside `units` is refused by the database. */
  readonly unitEnforced: boolean;
  /** Whether consumers need the unit to be the first of `units`. */
  readonly unitExpected: boolean;
  /** Whether the value carries an evaluation window of 7, 14 or 30 days. */
  readonly window: boolean;
  /** A numeric value that may also carry a JSON document (the profit bound). */
  readonly optionalJson: boolean;
}

function spec(
  shape: CalibrationValueShape,
  group: CategoryGroup,
  units: readonly string[],
  options: { enforced?: boolean; expected?: boolean; window?: boolean; json?: boolean } = {},
): CategorySpec {
  return {
    shape,
    group,
    units,
    unitEnforced: options.enforced ?? false,
    unitExpected: options.expected ?? false,
    window: options.window ?? false,
    optionalJson: options.json ?? false,
  };
}

/** The 20 categories of `core.lc_calibration_category`. */
export const CATEGORY_SPECS: Readonly<Record<string, CategorySpec>> = {
  MATERIAL_IMPROVEMENT_BOUND: spec('NUMERIC', 'protection', ['RATIO'], { enforced: true }),
  NON_WORSENING_PROFIT_BOUND: spec('NUMERIC', 'protection', ['RATIO'], {
    expected: true,
    window: true,
    json: true,
  }),
  NON_WORSENING_RETURN_BOUND: spec('NUMERIC', 'protection', ['RATIO'], {
    enforced: true,
    window: true,
  }),
  CRITICAL_GROUP_RULE: spec('JSON', 'protection', ['RULE']),
  DEMAND_SCENARIO_SET: spec('JSON', 'protection', ['SCENARIOS']),
  FRESHNESS_RULE: spec('JSON', 'timing', ['SECONDS']),
  RESPONSIBILITY_SLO: spec('JSON', 'timing', ['SLO']),
  RESPONSIBILITY_COVERAGE: spec('JSON', 'timing', ['CALENDAR']),
  ORDINARY_TRIGGER_CONTENT: spec('JSON', 'trigger', ['CONDITIONS'], { enforced: true }),
  MATERIAL_TRIGGER_CONTENT: spec('JSON', 'trigger', ['CONDITIONS'], { enforced: true }),
  ORDINARY_TRIGGER_EXPOSURE: spec('NUMERIC', 'trigger', ['RATIO'], {
    enforced: true,
    window: true,
  }),
  MATERIAL_TRIGGER_EXPOSURE: spec('NUMERIC', 'trigger', ['RATIO'], {
    enforced: true,
    window: true,
  }),
  APPROVAL_VALIDITY: spec('NUMERIC', 'timing', ['DAYS', 'HOURS', 'MINUTES'], { enforced: true }),
  REPRESENTATION_EQUIVALENCE_RULE: spec('TEXT', 'description', ['RULE']),
  ALLOWANCE_AXES: spec('JSON', 'allowance', ['AXES']),
  ALLOWANCE_RESERVE: spec('JSON', 'allowance', ['AXIS_VALUE']),
  FORMAL_NODES: spec('JSON', 'evaluation', ['NODES']),
  STOP_RULE: spec('JSON', 'evaluation', ['RULE']),
  CROSS_PERIOD_WINDOW: spec('NUMERIC', 'protection', ['DAYS'], { enforced: true }),
  DESCRIPTION_LENGTH_RULE: spec('JSON', 'description', ['CHARACTERS']),
};

export function categorySpec(code: string): CategorySpec | undefined {
  return Object.hasOwn(CATEGORY_SPECS, code) ? CATEGORY_SPECS[code] : undefined;
}

export const EQUIVALENCE_RULES: readonly string[] = ['EXACT', 'WHITESPACE_NORMALIZED'];
export const WINDOW_DAYS: readonly number[] = [7, 14, 30];
export const ALLOWANCE_AXES: readonly string[] = [
  'CONCURRENT_LISTINGS',
  'AFFECTED_VARIANTS',
  'REVENUE_EXPOSURE',
  'CATEGORY_SHARE',
];
const REGISTERED_METHOD = 'EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1';

// ------------------------------------------------------------------ form values

/** One category as the form holds it; JSON documents are kept as the text typed. */
export interface ValueFields {
  readonly numeric?: string;
  readonly text?: string;
  readonly json?: string;
  readonly unitCode?: string;
  readonly windowDays?: number;
  readonly scopeNote?: string;
  readonly evidenceReference?: string;
}

export type ValueFieldMap = Readonly<Partial<Record<string, ValueFields>>>;

export type ValueField = 'numeric' | 'text' | 'json' | 'unitCode' | 'windowDays';

/** One problem: an error the database refuses, or a warning a consumer would. */
export interface Finding {
  readonly level: 'error' | 'warning';
  readonly category?: string;
  readonly field?: ValueField;
  readonly text: string;
}

// ------------------------------------------------------------------ examples

/**
 * The synthetic example values, identical to the four package bodies that
 * validated and activated in the local database walk-through. They are a
 * starting point, never a default the system applies: every one is shown as an
 * example that still has to be confirmed.
 */
export const EXAMPLE_EVIDENCE = 'synthetic-local:calibration-walkthrough';

interface Example {
  readonly unit: string;
  readonly numeric?: string;
  readonly text?: string;
  readonly json?: unknown;
  readonly windowDays?: number;
  readonly note: string;
}

const FRESHNESS_EXAMPLE = {
  nativeScope: { MANUAL_ENTRY: { maximumAgeSeconds: 1209600 } },
  materialityExposure: {
    maximumVerificationAgeSeconds: 1209600,
    maximumPeriodEndAgeSeconds: 1209600,
  },
  businessProtection: {
    maximumVerificationAgeSeconds: 1209600,
    maximumPeriodEndAgeSeconds: 1209600,
  },
};

const COMMON_EXAMPLES: Readonly<Record<string, Example>> = {
  NON_WORSENING_PROFIT_BOUND: {
    unit: 'RATIO',
    numeric: '0',
    windowDays: 30,
    note: '本地演示：利润不劣化界限为 0（不允许利润下降），D30 口径。',
  },
  NON_WORSENING_RETURN_BOUND: {
    unit: 'RATIO',
    numeric: '0.05',
    windowDays: 30,
    note: '本地演示：退货率不劣化界限为 0.05，D30 口径，与利润界限同窗口。',
  },
  CRITICAL_GROUP_RULE: { unit: 'RULE', json: { groups: [] }, note: '本地演示：不设关键分组。' },
  FRESHNESS_RULE: {
    unit: 'SECONDS',
    json: FRESHNESS_EXAMPLE,
    note: '本地演示：手工录入的原生范围、曝光与财务输入的时效均为 14 天（1209600 秒）。',
  },
  RESPONSIBILITY_SLO: {
    unit: 'SLO',
    json: {
      acknowledgementMinutes: 240,
      actionMinutes: 1440,
      outcomeMaturityDays: 30,
      necessaryRisk: { acknowledgementMinutes: 60, actionMinutes: 240, outcomeMaturityDays: 30 },
    },
    note: '本地演示：确认 240 分钟、处理 1440 分钟（在岗时间），结果成熟期 30 天。',
  },
  RESPONSIBILITY_COVERAGE: {
    unit: 'CALENDAR',
    json: {
      timezone: 'Europe/Moscow',
      days: [1, 2, 3, 4, 5, 6, 7],
      startMinute: 540,
      endMinute: 1260,
    },
    note: '本地演示：莫斯科时间每天 09:00–21:00 在岗。',
  },
  ORDINARY_TRIGGER_CONTENT: {
    unit: 'CONDITIONS',
    json: {
      model: 'LC_MEANING_CONDITIONS_1',
      description: [
        {
          code: 'WORDING_ONLY',
          condition:
            '仅调整措辞、语序、标点或错别字，不新增、删除或改变任何商品事实、功效、规格或承诺。',
        },
      ],
    },
    note: '本地演示：普通内容变更条件（仅措辞）。',
  },
  MATERIAL_TRIGGER_CONTENT: {
    unit: 'CONDITIONS',
    json: {
      model: 'LC_MEANING_CONDITIONS_1',
      description: [
        {
          code: 'CLAIM_CHANGE',
          condition: '新增、删除或改变商品规格、成分、功效、合规、保修或售后承诺等事实性表述。',
        },
      ],
    },
    note: '本地演示：重大内容变更条件（事实性表述变化）。',
  },
  ORDINARY_TRIGGER_EXPOSURE: {
    unit: 'RATIO',
    numeric: '0.05',
    windowDays: 30,
    note: '本地演示：受影响变体保留销售额占店铺比例 ≤5% 判为普通曝光（D30）。',
  },
  MATERIAL_TRIGGER_EXPOSURE: {
    unit: 'RATIO',
    numeric: '0.2',
    windowDays: 30,
    note: '本地演示：受影响变体保留销售额占店铺比例 ≥20% 判为重大曝光；介于 5% 与 20% 之间为未决（D30）。',
  },
  APPROVAL_VALIDITY: { unit: 'DAYS', numeric: '3', note: '本地演示：审批有效期 3 天。' },
  REPRESENTATION_EQUIVALENCE_RULE: {
    unit: 'RULE',
    text: 'EXACT',
    note: '本地演示：回读文本必须与目标文本完全一致。',
  },
  ALLOWANCE_AXES: {
    unit: 'AXES',
    json: ['CONCURRENT_LISTINGS', 'AFFECTED_VARIANTS'],
    note: '本地演示：占用并发 Listing 数与受影响变体数两个额度轴（单一范围）。',
  },
  ALLOWANCE_RESERVE: {
    unit: 'AXIS_VALUE',
    json: { CONCURRENT_LISTINGS: 0, AFFECTED_VARIANTS: 0 },
    note: '本地演示：两个额度轴的处置预留均为 0。',
  },
  CROSS_PERIOD_WINDOW: { unit: 'DAYS', numeric: '30', note: '本地演示：跨期利润观察窗口 30 天。' },
  DESCRIPTION_LENGTH_RULE: {
    unit: 'CHARACTERS',
    json: { min: 1, max: 6000 },
    note: '本地演示（合成值，非平台事实）：描述长度 1–6000 个字符（按 Unicode 码点计）。',
  },
  MATERIAL_IMPROVEMENT_BOUND: {
    unit: 'RATIO',
    numeric: '0.05',
    note: '本地演示：转化率相对提升 5% 视为实质改善。',
  },
  FORMAL_NODES: {
    unit: 'NODES',
    json: [
      {
        nodeCode: 'CONVERSION_D14',
        maturityDays: 14,
        method: REGISTERED_METHOD,
        threshold: 0.05,
        methodParameters: {
          samplingModel: 'INDEPENDENT_BERNOULLI_VISITS',
          familyAlpha: 0.05,
          nodeAlpha: 0.05,
          qualificationRef: 'synthetic-local:method-qualification',
        },
        schedule: {
          windowStartOffsetDays: 0,
          windowEndOffsetDays: 14,
          notBeforeOffsetDays: 28,
          lastOffsetDays: 35,
        },
      },
    ],
    note: '本地演示：一个 14 天成熟节点，精确二项 + Bonferroni 方法，族错误率 0.05。',
  },
  STOP_RULE: {
    unit: 'RULE',
    json: {},
    note: '本地演示：显式不启用无效停止规则（空对象）。',
  },
  DEMAND_SCENARIO_SET: {
    unit: 'SCENARIOS',
    json: {},
    note: '本地演示：未提供需求/供给情景（通过校验，但消费方会报告情景缺失）。',
  },
};

const PROMOTION_EXAMPLES: Readonly<Record<string, Example>> = {
  ORDINARY_TRIGGER_CONTENT: {
    unit: 'CONDITIONS',
    json: {
      model: 'LC_MEANING_CONDITIONS_1',
      promotion: [
        {
          code: 'TERMS_UNCHANGED',
          condition: '只参加平台已有活动，不改变折扣力度、活动期限或参与条件。',
        },
      ],
    },
    note: '本地演示：普通促销变更条件（条款不变）。',
  },
  MATERIAL_TRIGGER_CONTENT: {
    unit: 'CONDITIONS',
    json: {
      model: 'LC_MEANING_CONDITIONS_1',
      promotion: [
        {
          code: 'DISCOUNT_CHANGE',
          condition: '改变折扣力度、活动期限、参与条件或对买家的价格承诺。',
        },
      ],
    },
    note: '本地演示：重大促销变更条件（条款变化）。',
  },
};

function example(code: string, purpose: CalibrationPurpose): Example | undefined {
  if (purpose === 'PROMOTION' && Object.hasOwn(PROMOTION_EXAMPLES, code)) {
    return PROMOTION_EXAMPLES[code];
  }
  return Object.hasOwn(COMMON_EXAMPLES, code) ? COMMON_EXAMPLES[code] : undefined;
}

/** The package fields of the example, per purpose. */
export const EXAMPLE_PACKAGE = {
  code: {
    LISTING_CONVERSION: 'local-listing-conversion',
    DESCRIPTION_CORRECTION: 'local-description-correction',
    BOUNDED_EXPLORATION: 'local-bounded-exploration',
    PROMOTION: 'local-promotion',
  } as Readonly<Record<CalibrationPurpose, string>>,
  rationale: {
    LISTING_CONVERSION: '本地合成演示用Listing 转化校准包，仅用于走查，不代表生产口径。',
    DESCRIPTION_CORRECTION: '本地合成演示用描述纠错校准包，仅用于走查，不代表生产口径。',
    BOUNDED_EXPLORATION: '本地合成演示用有界探索校准包，仅用于走查，不代表生产口径。',
    PROMOTION: '本地合成演示用促销校准包，仅用于走查，不代表生产口径。',
  } as Readonly<Record<CalibrationPurpose, string>>,
  impact: '仅影响本地演示店铺的 Listing 决策链路；不连接任何平台写入。',
  differences: '首个版本，无前序校准包。',
  evidenceReference: EXAMPLE_EVIDENCE,
} as const;

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2);
}

/** The example of one category for a purpose, as form fields. */
export function exampleFields(code: string, purpose: CalibrationPurpose): ValueFields | undefined {
  const found = example(code, purpose);
  if (found === undefined) return undefined;
  return {
    unitCode: found.unit,
    scopeNote: found.note,
    evidenceReference: EXAMPLE_EVIDENCE,
    ...(found.numeric === undefined ? {} : { numeric: found.numeric }),
    ...(found.text === undefined ? {} : { text: found.text }),
    ...(found.json === undefined ? {} : { json: pretty(found.json) }),
    ...(found.windowDays === undefined ? {} : { windowDays: found.windowDays }),
  };
}

/** Example fields for every category given, those without an example left empty. */
export function exampleValues(
  categories: readonly string[],
  purpose: CalibrationPurpose,
): Record<string, ValueFields> {
  const out: Record<string, ValueFields> = {};
  for (const code of categories) {
    out[code] = exampleFields(code, purpose) ?? {};
  }
  return out;
}

function normalizedJson(textValue: string | undefined): string | undefined {
  if (textValue === undefined || textValue.trim() === '') return undefined;
  try {
    return JSON.stringify(JSON.parse(textValue));
  } catch {
    return textValue;
  }
}

function normalizedDecimal(value: string | undefined): string | undefined {
  const trimmed = value?.trim() ?? '';
  if (trimmed === '') return undefined;
  const scaledValue = scaled(trimmed);
  return scaledValue === undefined ? trimmed : scaledValue.toString();
}

/** Whether the fields still hold the example for this purpose, unchanged. */
export function isExample(
  code: string,
  purpose: CalibrationPurpose,
  fields: ValueFields | undefined,
): boolean {
  const reference = exampleFields(code, purpose);
  if (reference === undefined || fields === undefined) return false;
  return (
    normalizedDecimal(fields.numeric) === normalizedDecimal(reference.numeric) &&
    (fields.text ?? undefined) === reference.text &&
    normalizedJson(fields.json) === normalizedJson(reference.json) &&
    fields.unitCode === reference.unitCode &&
    fields.windowDays === reference.windowDays &&
    (fields.scopeNote ?? '').trim() === (reference.scopeNote ?? '') &&
    (fields.evidenceReference ?? '').trim() === (reference.evidenceReference ?? '')
  );
}

/** A stored value as form fields, for drafting the next version from it. */
export function fieldsFromValue(value: CalibrationValue): ValueFields {
  return {
    unitCode: value.unitCode,
    scopeNote: value.scopeNote,
    evidenceReference: value.evidenceReference,
    ...(value.numeric === null ? {} : { numeric: value.numeric }),
    ...(value.text === null ? {} : { text: value.text }),
    ...(value.json === null || value.json === undefined ? {} : { json: pretty(value.json) }),
    ...(value.windowDays === null ? {} : { windowDays: value.windowDays }),
  };
}

/** The draft body of one category; only called once the fields have passed their rules. */
export function draftValue(code: string, fields: ValueFields): CalibrationDraftValue {
  const found = categorySpec(code);
  const shape = found?.shape ?? 'JSON';
  const document = fields.json?.trim() ?? '';
  const withJson = shape === 'JSON' || (found?.optionalJson === true && document !== '');
  return {
    categoryCode: code,
    unitCode: (fields.unitCode ?? '').trim(),
    scopeNote: (fields.scopeNote ?? '').trim(),
    evidenceReference: (fields.evidenceReference ?? '').trim(),
    ...(shape === 'NUMERIC' ? { numeric: (fields.numeric ?? '').trim() } : {}),
    ...(shape === 'TEXT' ? { text: fields.text ?? '' } : {}),
    ...(withJson ? { json: parseDocument(document) } : {}),
    ...(found?.window === true && fields.windowDays !== undefined
      ? { windowDays: fields.windowDays }
      : {}),
  };
}

function parseDocument(textValue: string): unknown {
  try {
    return JSON.parse(textValue) as unknown;
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------ helpers

const NUMERIC = /^-?\d{1,12}(\.\d{1,6})?$/;
const UNIT = /^[A-Z][A-Z0-9_]{0,31}$/;
const ISO_OFFSET = /^[0-9]{4}-[0-9]{2}-[0-9]{2}T.*(Z|[+-][0-9]{2}:[0-9]{2})$/;
const SCALE = 1_000_000n;

/** A decimal as millionths, so ratios compare exactly. */
function scaled(value: string | undefined): bigint | undefined {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(value?.trim() ?? '');
  if (match === null) return undefined;
  const fraction = (match[3] ?? '').padEnd(6, '0').slice(0, 6);
  const magnitude = BigInt(`${match[2] ?? '0'}${fraction}`);
  return match[1] === '-' ? -magnitude : magnitude;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** An array whose items are still unchecked, or undefined for anything else. */
function asArray(value: unknown): readonly unknown[] | undefined {
  return Array.isArray(value) ? (value as unknown[]) : undefined;
}

function isInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value);
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function between(value: unknown, low: number, high: number): boolean {
  return isNumber(value) && value >= low && value <= high;
}

/** Length the way the database measures it (characters, not UTF-16 units), after trimming. */
function trimmedLength(value: string): number {
  return Array.from(value.trim()).length;
}

function reference(value: unknown, max = 512): boolean {
  return typeof value === 'string' && trimmedLength(value) >= 1 && trimmedLength(value) <= max;
}

function instant(value: unknown): number | undefined {
  if (typeof value !== 'string' || !ISO_OFFSET.test(value)) return undefined;
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? undefined : parsed;
}

/** The text PostgreSQL's `->>` gives for a JSON value. */
function pgText(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value;
  return JSON.stringify(value);
}

function validTimezone(zone: unknown): boolean {
  if (typeof zone !== 'string' || zone.trim() === '') return false;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

/** Whether JSON.parse kept every number exactly. */
function numbersSafe(value: unknown): boolean {
  if (typeof value === 'number') {
    return Number.isFinite(value) && Math.abs(value) <= Number.MAX_SAFE_INTEGER;
  }
  const items = asArray(value);
  if (items !== undefined) return items.every(numbersSafe);
  if (isRecord(value)) return Object.values(value).every(numbersSafe);
  return true;
}

type Parsed =
  { readonly ok: true; readonly value: unknown } | { readonly ok: false; readonly message: string };

/** Parse a JSON document the way the draft will send it. */
export function parseJsonText(textValue: string | undefined): Parsed {
  const trimmed = textValue?.trim() ?? '';
  if (trimmed === '') return { ok: false, message: rule.jsonRequired };
  try {
    const value = JSON.parse(trimmed) as unknown;
    return numbersSafe(value) ? { ok: true, value } : { ok: false, message: rule.jsonTooLarge };
  } catch (error) {
    return {
      ok: false,
      message: rule.jsonParse(error instanceof Error ? error.message : String(error)),
    };
  }
}

// ------------------------------------------------------------------ one category

interface Collector {
  readonly findings: Finding[];
  readonly error: (text: string) => void;
  readonly warning: (text: string) => void;
}

function collector(category: string, field: ValueField): Collector {
  const findings: Finding[] = [];
  return {
    findings,
    error: (text) => {
      findings.push({ level: 'error', category, field, text });
    },
    warning: (text) => {
      findings.push({ level: 'warning', category, field, text });
    },
  };
}

/** Every problem of one category's fields, each tagged with the field it belongs to. */
export function valueFindings(
  code: string,
  fields: ValueFields | undefined,
  purpose: CalibrationPurpose | undefined,
): Finding[] {
  const found = categorySpec(code);
  if (found === undefined || fields === undefined) return [];
  const out: Finding[] = [];

  const unit = collector(code, 'unitCode');
  const unitCode = (fields.unitCode ?? '').trim();
  if (!UNIT.test(unitCode)) {
    unit.error(rule.unitFormat);
  } else if (found.unitEnforced && !found.units.includes(unitCode)) {
    unit.error(rule.unitOneOf(found.units.join('、')));
  } else if (found.unitExpected && unitCode !== found.units[0]) {
    unit.warning(rule.unitRecommended(found.units[0] ?? ''));
  }
  out.push(...unit.findings);

  if (found.window) {
    const window = collector(code, 'windowDays');
    if (fields.windowDays === undefined) window.error(rule.windowRequired);
    else if (!WINDOW_DAYS.includes(fields.windowDays)) window.warning(rule.windowAllowed);
    out.push(...window.findings);
  }

  if (found.shape === 'NUMERIC') {
    out.push(...numericFindings(code, fields.numeric));
    if (found.optionalJson && (fields.json?.trim() ?? '') !== '') {
      out.push(...documentFindings(code, fields.json, purpose));
    }
  } else if (found.shape === 'TEXT') {
    const textCheck = collector(code, 'text');
    if (fields.text === undefined || fields.text === '') textCheck.error(rule.textRequired);
    else if (
      code === 'REPRESENTATION_EQUIVALENCE_RULE' &&
      !EQUIVALENCE_RULES.includes(fields.text)
    ) {
      textCheck.error(rule.textRequired);
    }
    out.push(...textCheck.findings);
  } else {
    out.push(...documentFindings(code, fields.json, purpose));
  }
  return out;
}

function numericFindings(code: string, value: string | undefined): Finding[] {
  const check = collector(code, 'numeric');
  const trimmed = value?.trim() ?? '';
  if (!NUMERIC.test(trimmed)) {
    check.error(rule.numberFormat);
    return check.findings;
  }
  const amount = scaled(trimmed) ?? 0n;
  const whole = amount % SCALE === 0n;
  switch (code) {
    case 'MATERIAL_IMPROVEMENT_BOUND':
    case 'NON_WORSENING_RETURN_BOUND':
    case 'ORDINARY_TRIGGER_EXPOSURE':
    case 'MATERIAL_TRIGGER_EXPOSURE':
      if (amount < 0n || amount > SCALE) check.error(rule.ratioRange);
      break;
    case 'NON_WORSENING_PROFIT_BOUND':
      if (amount < 0n) check.error(rule.notNegative);
      break;
    case 'APPROVAL_VALIDITY':
      if (amount <= 0n || !whole) check.error(rule.positiveInteger);
      break;
    case 'CROSS_PERIOD_WINDOW':
      if (amount < 0n || amount > 3660n * SCALE || !whole) check.error(rule.daysRange);
      break;
    default:
      break;
  }
  return check.findings;
}

function documentFindings(
  code: string,
  textValue: string | undefined,
  purpose: CalibrationPurpose | undefined,
): Finding[] {
  const check = collector(code, 'json');
  const parsed = parseJsonText(textValue);
  if (!parsed.ok) {
    check.error(parsed.message);
    return check.findings;
  }
  const value = parsed.value;
  switch (code) {
    case 'NON_WORSENING_PROFIT_BOUND':
      profitDocument(value, check);
      break;
    case 'CRITICAL_GROUP_RULE':
      criticalGroups(value, check);
      break;
    case 'DEMAND_SCENARIO_SET':
      demandScenarios(value, purpose, check);
      break;
    case 'FRESHNESS_RULE':
      freshness(value, purpose, check);
      break;
    case 'RESPONSIBILITY_SLO':
      if (!isRecord(value) || !sloValid(value)) check.warning(rule.sloShape);
      else if (
        value.necessaryRisk !== undefined &&
        !(isRecord(value.necessaryRisk) && sloValid(value.necessaryRisk))
      ) {
        check.warning(rule.sloRisk);
      }
      break;
    case 'RESPONSIBILITY_COVERAGE':
      if (!coverageValid(value)) check.warning(rule.coverageShape);
      break;
    case 'ORDINARY_TRIGGER_CONTENT':
    case 'MATERIAL_TRIGGER_CONTENT':
      meaningDocument(value, check);
      break;
    case 'ALLOWANCE_AXES':
      if (axesOf(value) === undefined) check.warning(rule.axesShape);
      break;
    case 'ALLOWANCE_RESERVE':
      reserve(value, check);
      break;
    case 'FORMAL_NODES':
      formalNodes(value, check);
      break;
    case 'STOP_RULE':
      if (!isRecord(value)) check.error(rule.mustBeObject);
      else if (Object.keys(value).length > 0 && !stopRuleShape(value))
        check.warning(rule.stopRuleShape);
      break;
    case 'DESCRIPTION_LENGTH_RULE':
      if (
        !isRecord(value) ||
        !isInteger(value.min) ||
        !isInteger(value.max) ||
        value.min < 0 ||
        value.min > value.max ||
        value.max > 65536
      ) {
        check.warning(rule.lengthShape);
      }
      break;
    default:
      break;
  }
  return check.findings;
}

function profitDocument(value: unknown, check: Collector): void {
  if (!isRecord(value)) {
    check.error(rule.mustBeObject);
    return;
  }
  const comparisons = value.currentAccountingComparisons;
  if (comparisons === undefined) return;
  if (!isRecord(comparisons)) {
    check.error(rule.comparisonsShape);
    return;
  }
  for (const item of Object.values(comparisons)) {
    const start = isRecord(item) ? instant(item.periodStart) : undefined;
    const end = isRecord(item) ? instant(item.periodEnd) : undefined;
    if (
      !isRecord(item) ||
      !reference(item.evidenceReference) ||
      start === undefined ||
      end === undefined ||
      start >= end
    ) {
      check.error(rule.comparisonsShape);
      return;
    }
  }
  check.warning(rule.existenceNote);
}

function criticalGroups(value: unknown, check: Collector): void {
  if (!isRecord(value)) {
    check.warning(rule.mustBeObject);
    return;
  }
  const groups = asArray(value.groups);
  const codes = new Set<string>();
  const groupsValid = groups?.every((group) => {
    if (!isRecord(group) || typeof group.code !== 'string') return false;
    if (!/^[A-Z][A-Z0-9_]{0,63}$/.test(group.code) || codes.has(group.code)) return false;
    codes.add(group.code);
    return between(group.bound, 0, 1);
  });
  if (!groupsValid) check.warning(rule.groupsShape);
  const bases = value.protectionScopeBases;
  if (bases === undefined) return;
  if (!isRecord(bases) || !Object.values(bases).every(protectionBasisValid)) {
    check.error(rule.protectionBases);
    return;
  }
  check.warning(rule.existenceNote);
}

function distinctStrings(value: unknown, nonEmpty: boolean): boolean {
  const items = asArray(value);
  if (items === undefined || (nonEmpty && items.length === 0)) return false;
  return items.every((item) => typeof item === 'string') && new Set(items).size === items.length;
}

function protectionBasisValid(basis: unknown): boolean {
  if (!isRecord(basis) || !reference(basis.evidenceReference)) return false;
  const linkedScopes = asArray(basis.linkedProfitScopes);
  if (linkedScopes === undefined || !distinctStrings(basis.criticalReturnVariantIds, false)) {
    return false;
  }
  const codes = new Set<string>();
  return linkedScopes.every((linked) => {
    if (!isRecord(linked) || typeof linked.code !== 'string') return false;
    if (!/^[A-Z][A-Z0-9_]{0,63}$/.test(linked.code) || codes.has(linked.code)) return false;
    codes.add(linked.code);
    return reference(linked.evidenceReference) && distinctStrings(linked.listingVariantIds, true);
  });
}

function demandScenarios(
  value: unknown,
  purpose: CalibrationPurpose | undefined,
  check: Collector,
): void {
  if (!isRecord(value)) {
    check.warning(rule.mustBeObject);
    return;
  }
  const supply = value.supplyScenarios;
  const economic = value.economicScenarioBases;
  if (supply !== undefined && !supplyValid(supply)) check.error(rule.supplyShape);
  if (economic !== undefined && !economicValid(economic)) check.error(rule.economicShape);
  if (supply === undefined && economic === undefined) {
    if (purpose === 'PROMOTION' || purpose === 'BOUNDED_EXPLORATION')
      check.warning(rule.scenariosMissing);
  } else {
    check.warning(rule.existenceNote);
  }
}

function supplyValid(value: unknown): boolean {
  const items = asArray(value);
  if (items === undefined || items.length === 0) return false;
  const identities = new Set<string>();
  return items.every((item) => {
    if (
      !isRecord(item) ||
      !reference(item.code) ||
      typeof item.productVariantId !== 'string' ||
      !reference(item.evidenceReference) ||
      !isNumber(item.companyDailyFulfillmentUnits) ||
      item.companyDailyFulfillmentUnits < 0 ||
      !isInteger(item.coverageDays) ||
      item.coverageDays < 1 ||
      item.coverageDays > 2147483647
    ) {
      return false;
    }
    const identity = JSON.stringify([item.productVariantId, item.code]);
    if (identities.has(identity)) return false;
    identities.add(identity);
    return true;
  });
}

function economicValid(value: unknown): boolean {
  if (!isRecord(value)) return false;
  return Object.values(value).every((basis) => {
    if (!isRecord(basis)) return false;
    const start = instant(basis.periodStart);
    const end = instant(basis.periodEnd);
    const scenarios = asArray(basis.necessaryScenarios);
    if (
      !reference(basis.evidenceReference) ||
      !between(basis.minimumContributionProfit, 0, 99999999999999) ||
      typeof basis.currencyCode !== 'string' ||
      !/^[A-Z]{3}$/.test(basis.currencyCode) ||
      !reference(basis.profitEvidenceReference) ||
      start === undefined ||
      end === undefined ||
      start >= end ||
      scenarios === undefined ||
      scenarios.length < 1 ||
      scenarios.length > 64
    ) {
      return false;
    }
    const codes = new Set<string>();
    return scenarios.every((item) => {
      if (!isRecord(item) || !reference(item.code, 64)) return false;
      const scenarioCode = item.code as string;
      if (codes.has(scenarioCode)) return false;
      codes.add(scenarioCode);
      return (
        item.conservative === true &&
        reference(item.evidenceReference) &&
        isInteger(item.quantity) &&
        item.quantity >= 0 &&
        item.quantity <= 99999999999999
      );
    });
  });
}

function positiveIntegers(value: unknown, keys: readonly string[]): boolean {
  return isRecord(value) && keys.every((key) => isInteger(value[key]) && value[key] > 0);
}

const AGE_KEYS = ['maximumVerificationAgeSeconds', 'maximumPeriodEndAgeSeconds'] as const;

function freshness(
  value: unknown,
  purpose: CalibrationPurpose | undefined,
  check: Collector,
): void {
  if (!isRecord(value)) {
    check.warning(rule.mustBeObject);
    return;
  }
  const native = value.nativeScope;
  if (native === undefined) {
    if (purpose === 'LISTING_CONVERSION') check.warning(rule.freshnessNativeMissing);
  } else if (
    !isRecord(native) ||
    Object.keys(native).length === 0 ||
    !Object.entries(native).every(
      ([key, item]) =>
        (key === 'MANUAL_ENTRY' || key === 'MARKETPLACE_RAW') &&
        isRecord(item) &&
        isInteger(item.maximumAgeSeconds) &&
        item.maximumAgeSeconds >= 1 &&
        item.maximumAgeSeconds <= 2147483647,
    )
  ) {
    check.warning(rule.freshnessNative);
  }
  if (value.materialityExposure === undefined) check.warning(rule.freshnessMaterialityMissing);
  else if (!positiveIntegers(value.materialityExposure, AGE_KEYS))
    check.warning(rule.freshnessMateriality);
  const business = value.businessProtection;
  if (business === undefined) {
    if (purpose === 'LISTING_CONVERSION' || purpose === 'PROMOTION')
      check.warning(rule.freshnessBusinessMissing);
  } else if (!positiveIntegers(business, AGE_KEYS)) {
    check.error(rule.freshnessBusiness);
  }
}

function sloValid(value: Record<string, unknown>): boolean {
  return (
    isInteger(value.acknowledgementMinutes) &&
    between(value.acknowledgementMinutes, 1, 527040) &&
    isInteger(value.actionMinutes) &&
    between(value.actionMinutes, 1, 527040) &&
    isInteger(value.outcomeMaturityDays) &&
    between(value.outcomeMaturityDays, 1, 3660)
  );
}

function coverageValid(value: unknown): boolean {
  if (!isRecord(value) || !validTimezone(value.timezone)) return false;
  const days = asArray(value.days);
  if (
    days === undefined ||
    days.length === 0 ||
    new Set(days).size !== days.length ||
    !days.every((day) => isInteger(day) && day >= 1 && day <= 7)
  ) {
    return false;
  }
  return (
    isInteger(value.startMinute) &&
    between(value.startMinute, 0, 1439) &&
    isInteger(value.endMinute) &&
    between(value.endMinute, 0, 1439) &&
    value.startMinute !== value.endMinute
  );
}

const MEANING_CODE = /^[A-Z][A-Z0-9_]{1,63}$/;

/** `core.lc_meaning_rule_document_valid`, with the reason. */
function meaningDocument(value: unknown, check: Collector): void {
  if (!isRecord(value)) {
    check.error(rule.mustBeObject);
    return;
  }
  if (value.model !== 'LC_MEANING_CONDITIONS_1') check.error(rule.meaningModel);
  if (
    Object.keys(value).some(
      (key) => key !== 'model' && key !== 'description' && key !== 'promotion',
    )
  ) {
    check.error(rule.meaningKeys);
  }
  if (!('description' in value) && !('promotion' in value)) check.error(rule.meaningNeedsList);
  for (const key of ['description', 'promotion'] as const) {
    if (!(key in value)) continue;
    const rules = asArray(value[key]);
    if (rules === undefined || rules.length > 16) {
      check.error(rule.meaningList(key));
      continue;
    }
    const codes = new Set<string>();
    for (const item of rules) {
      if (
        !isRecord(item) ||
        Object.keys(item).some((name) => name !== 'code' && name !== 'condition') ||
        typeof item.code !== 'string' ||
        !MEANING_CODE.test(item.code) ||
        typeof item.condition !== 'string' ||
        trimmedLength(item.condition) < 1 ||
        trimmedLength(item.condition) > 2000
      ) {
        check.error(rule.meaningRule(key));
        break;
      }
      if (codes.has(item.code)) {
        check.error(rule.meaningDuplicate(key));
        break;
      }
      codes.add(item.code);
    }
  }
}

/** The axes an ALLOWANCE_AXES value names, or undefined when its shape is not accepted. */
function axesOf(value: unknown): readonly string[] | undefined {
  let listed: unknown = value;
  if (isRecord(value)) {
    if (
      Object.keys(value).some((key) => key !== 'axes' && key !== 'scopeComposition') ||
      value.scopeComposition !== 'ALL_APPLICABLE'
    ) {
      return undefined;
    }
    listed = value.axes;
  }
  const axes = asArray(listed);
  if (
    axes === undefined ||
    axes.length < 1 ||
    axes.length > 4 ||
    new Set(axes).size !== axes.length
  ) {
    return undefined;
  }
  const names = axes.filter(
    (axis): axis is string => typeof axis === 'string' && ALLOWANCE_AXES.includes(axis),
  );
  return names.length === axes.length ? names : undefined;
}

function reserve(value: unknown, check: Collector): void {
  if (!isRecord(value)) {
    check.warning(rule.mustBeObject);
    return;
  }
  for (const [axis, amount] of Object.entries(value)) {
    if (!ALLOWANCE_AXES.includes(axis)) check.warning(rule.reserveUnknownAxis(axis));
    const textValue = isNumber(amount) ? pgText(amount) : null;
    if (
      textValue === null ||
      textValue.length > 19 ||
      !/^[0-9]+([.][0-9]{1,4})?$/.test(textValue)
    ) {
      check.warning(rule.reserveShape);
      return;
    }
  }
}

interface NodeFacts {
  readonly code: string;
  readonly registered: boolean;
}

/** The nodes of a FORMAL_NODES value that the database accepts, or undefined. */
function nodesOf(value: unknown): readonly NodeFacts[] | undefined {
  return asArray(value)
    ?.filter(isRecord)
    .map((node) => ({
      code: pgText(node.nodeCode) ?? '',
      registered: node.method === REGISTERED_METHOD,
    }));
}

function formalNodes(value: unknown, check: Collector): void {
  const nodes = asArray(value);
  if (nodes === undefined) {
    check.error(rule.mustBeArray);
    return;
  }
  if (nodes.length < 1 || nodes.length > 8) check.error(rule.nodesCount);
  const codes = new Set<string>();
  let alphaSum = 0;
  let familyAlpha = Number.POSITIVE_INFINITY;
  nodes.forEach((node, index) => {
    const position = index + 1;
    const record = isRecord(node) ? node : {};
    const code = pgText(record.nodeCode) ?? '';
    const maturity = pgText(record.maturityDays) ?? '';
    const method = pgText(record.method) ?? '';
    const threshold = pgText(record.threshold) ?? '';
    if (
      !isRecord(node) ||
      !/^[A-Z][A-Z0-9_]{1,62}$/.test(code) ||
      codes.has(code) ||
      !['7', '14', '30'].includes(maturity) ||
      method.trim() === '' ||
      !/^[0-9]+([.][0-9]+)?$/.test(threshold)
    ) {
      check.error(rule.nodeShape(position));
    }
    codes.add(code);
    if (record.protectionComparison !== undefined && !comparisonValid(record)) {
      check.error(rule.nodeComparison(position));
    }
    if (record.method !== REGISTERED_METHOD) {
      check.warning(rule.nodeMethod(position));
      return;
    }
    const thresholdValue = Number(threshold);
    if (!(thresholdValue > 0 && thresholdValue <= 1)) check.warning(rule.nodeThreshold(position));
    const parameters = record.methodParameters;
    if (
      !isRecord(parameters) ||
      parameters.samplingModel !== 'INDEPENDENT_BERNOULLI_VISITS' ||
      !isNumber(parameters.familyAlpha) ||
      !(parameters.familyAlpha > 0 && parameters.familyAlpha < 1) ||
      !isNumber(parameters.nodeAlpha) ||
      !(parameters.nodeAlpha > 0 && parameters.nodeAlpha < 1) ||
      !reference(parameters.qualificationRef)
    ) {
      check.warning(rule.nodeParameters(position));
    } else {
      alphaSum += parameters.nodeAlpha;
      familyAlpha = Math.min(familyAlpha, parameters.familyAlpha);
    }
    if (!scheduleValid(record.schedule, Number(maturity)))
      check.warning(rule.nodeSchedule(position));
  });
  if (alphaSum > familyAlpha + 1e-12) check.warning(rule.nodeAlphaSum);
}

function scheduleValid(schedule: unknown, maturity: number): boolean {
  if (!isRecord(schedule)) return false;
  const keys = [
    'windowStartOffsetDays',
    'windowEndOffsetDays',
    'notBeforeOffsetDays',
    'lastOffsetDays',
  ];
  if (!keys.every((key) => isInteger(schedule[key]) && between(schedule[key], 0, 3660)))
    return false;
  const start = schedule.windowStartOffsetDays as number;
  const end = schedule.windowEndOffsetDays as number;
  const notBefore = schedule.notBeforeOffsetDays as number;
  const last = schedule.lastOffsetDays as number;
  return start < end && notBefore >= end + maturity && last >= notBefore;
}

function comparisonValid(node: Record<string, unknown>): boolean {
  const comparison = node.protectionComparison;
  if (
    !isRecord(comparison) ||
    comparison.method !== 'CANONICAL_ACCOUNTING_CHANGE_V1' ||
    !reference(comparison.qualificationRef)
  ) {
    return false;
  }
  const start = instant(comparison.referencePeriodStart);
  const end = instant(comparison.referencePeriodEnd);
  const schedule = node.schedule;
  if (
    start === undefined ||
    end === undefined ||
    start >= end ||
    end > Date.now() ||
    !isRecord(schedule)
  ) {
    return false;
  }
  const windowStart = schedule.windowStartOffsetDays;
  const windowEnd = schedule.windowEndOffsetDays;
  return (
    isNumber(windowStart) &&
    isNumber(windowEnd) &&
    end - start === (windowEnd - windowStart) * 86_400_000
  );
}

function stopRuleShape(value: Record<string, unknown>): boolean {
  const method = value.method;
  return (
    typeof value.nodeCode === 'string' &&
    value.trigger === 'QUALIFIED_FUTILITY' &&
    isRecord(method) &&
    method.code === REGISTERED_METHOD &&
    method.upperBoundRequired === true &&
    reference(method.qualificationRef) &&
    between(method.minimumEffect, 0, 1)
  );
}

// ------------------------------------------------------------------ combinations

function documentOf(fields: ValueFields | undefined): unknown {
  const parsed = parseJsonText(fields?.json);
  return parsed.ok ? parsed.value : undefined;
}

function ratioOf(fields: ValueFields | undefined): bigint | undefined {
  const trimmed = fields?.numeric?.trim() ?? '';
  return NUMERIC.test(trimmed) ? scaled(trimmed) : undefined;
}

/**
 * Problems between categories: the ones the combination check refuses, and the
 * ones consumers need to line up. Categories that fail on their own are left
 * to their own findings.
 */
export function combinationFindings(values: ValueFieldMap, purpose: CalibrationPurpose): Finding[] {
  const out: Finding[] = [];
  const error = (text: string): void => {
    out.push({ level: 'error', text });
  };
  const warning = (text: string): void => {
    out.push({ level: 'warning', text });
  };

  // Exposure triggers: order is checked by the database, windows by consumers.
  const ordinary = values.ORDINARY_TRIGGER_EXPOSURE;
  const material = values.MATERIAL_TRIGGER_EXPOSURE;
  const ordinaryRatio = ratioOf(ordinary);
  const materialRatio = ratioOf(material);
  if (ordinaryRatio !== undefined && materialRatio !== undefined && ordinaryRatio > materialRatio) {
    error(rule.exposureOrder);
  }
  if (
    ordinary !== undefined &&
    material !== undefined &&
    ordinary.windowDays !== material.windowDays
  ) {
    warning(rule.exposureWindows);
  }

  // Content triggers: the purpose's key in both documents, codes unique across them.
  const key = purpose === 'PROMOTION' ? 'promotion' : 'description';
  const ordinaryDocument = documentOf(values.ORDINARY_TRIGGER_CONTENT);
  const materialDocument = documentOf(values.MATERIAL_TRIGGER_CONTENT);
  if (isRecord(ordinaryDocument) && isRecord(materialDocument)) {
    const ordinaryRules = asArray(ordinaryDocument[key]) ?? [];
    const materialRules = asArray(materialDocument[key]) ?? [];
    if (ordinaryRules.length === 0 || materialRules.length === 0) {
      error(rule.meaningPurposeKey(key));
    }
    const codes = [...ordinaryRules, ...materialRules]
      .map((item) => (isRecord(item) ? item.code : undefined))
      .filter((code): code is string => typeof code === 'string');
    if (new Set(codes).size !== codes.length) error(rule.contentDuplicate);
  }

  // Allowance: a reserve for every axis the package occupies.
  const axes = axesOf(documentOf(values.ALLOWANCE_AXES));
  const reserveDocument = documentOf(values.ALLOWANCE_RESERVE);
  if (axes !== undefined && isRecord(reserveDocument)) {
    const missing = axes.filter((axis) => !(axis in reserveDocument));
    if (missing.length > 0) warning(rule.reserveMissingAxis(missing.join('、')));
  }

  // Profit and return bounds: one window, RATIO; exact [0,1] once accounting references exist.
  const profit = values.NON_WORSENING_PROFIT_BOUND;
  const returns = values.NON_WORSENING_RETURN_BOUND;
  if (profit !== undefined && returns !== undefined) {
    if (profit.windowDays !== returns.windowDays) warning(rule.boundsWindows);
    const profitDocumentValue = (profit.json?.trim() ?? '') === '' ? undefined : documentOf(profit);
    const comparisons =
      isRecord(profitDocumentValue) &&
      profitDocumentValue.currentAccountingComparisons !== undefined;
    const nodes = asArray(documentOf(values.FORMAL_NODES)) ?? [];
    const protection = nodes.some(
      (node) => isRecord(node) && node.protectionComparison !== undefined,
    );
    const ratio = (fields: ValueFields): boolean => {
      const amount = ratioOf(fields);
      return fields.unitCode === 'RATIO' && amount !== undefined && amount >= 0n && amount <= SCALE;
    };
    if ((comparisons || protection) && (!ratio(profit) || !ratio(returns))) error(rule.boundsRatio);
    else if (profit.unitCode !== 'RATIO' || returns.unitCode !== 'RATIO') warning(rule.boundsUnits);
  }

  // Stop rule: must point at a node that uses the registered method.
  const stop = documentOf(values.STOP_RULE);
  if (isRecord(stop) && Object.keys(stop).length > 0) {
    const nodes = nodesOf(documentOf(values.FORMAL_NODES)) ?? [];
    if (!nodes.some((node) => node.registered && node.code === stop.nodeCode)) {
      warning(rule.stopRuleNode);
    }
  }
  return out;
}

// ------------------------------------------------------------------ secret guard

const SECRET_PATTERNS: readonly RegExp[] = [
  /[A-Za-z0-9+/=_-]{64,}/,
  /\bbearer\s+[A-Za-z0-9._~+/=-]{8,}/i,
  /-----BEGIN\s/,
  /\b(?:password|passwd|secret|token|api[_-]?key)\s*[:=]/i,
];

/** Whether the serialized draft would be refused by the backend's secret-material guard. */
export function looksSecret(serialized: string): boolean {
  return SECRET_PATTERNS.some((pattern) => pattern.test(serialized));
}

// ------------------------------------------------------------------ replacement

/** Whether two effective periods [from, to) overlap; a missing end is open. */
function overlaps(
  aFrom: string,
  aTo: string | null | undefined,
  bFrom: string,
  bTo: string | null | undefined,
): boolean {
  const aStart = Date.parse(aFrom);
  const bStart = Date.parse(bFrom);
  const aEnd =
    aTo === null || aTo === undefined || aTo === '' ? Number.POSITIVE_INFINITY : Date.parse(aTo);
  const bEnd =
    bTo === null || bTo === undefined || bTo === '' ? Number.POSITIVE_INFINITY : Date.parse(bTo);
  return aStart < bEnd && bStart < aEnd;
}

/**
 * The active packages a new draft would replace: same scope, same purpose, an
 * overlapping period. Activation requires exactly this package (or none) to be
 * the one named when drafting.
 */
export function replacedPackages(
  packages: readonly CalibrationPackage[],
  purpose: CalibrationPurpose | undefined,
  scopeKind: string | undefined,
  storeId: string | undefined,
  platformCode: string | undefined,
  effectiveFrom: string | undefined,
  effectiveTo: string | undefined,
): readonly CalibrationPackage[] {
  if (
    purpose === undefined ||
    scopeKind === undefined ||
    effectiveFrom === undefined ||
    effectiveFrom === ''
  ) {
    return [];
  }
  return packages.filter(
    (item) =>
      item.status === 'ACTIVE' &&
      item.purposeCode === purpose &&
      item.scopeKind === scopeKind &&
      (scopeKind !== 'STORE' || item.storeId === storeId) &&
      (scopeKind !== 'PLATFORM' || item.platformCode === platformCode) &&
      overlaps(item.effectiveFrom, item.effectiveTo, effectiveFrom, effectiveTo),
  );
}
