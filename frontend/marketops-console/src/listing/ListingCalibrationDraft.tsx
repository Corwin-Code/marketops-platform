import {
  Alert,
  App,
  AutoComplete,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Flex,
  Form,
  Input,
  InputNumber,
  Radio,
  Select,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { FormRule } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ConsoleRequest } from '../api/console';
import type {
  CalibrationCatalogue,
  CalibrationDraft,
  CalibrationOverview,
  CalibrationPackageDetail,
  CalibrationPurpose,
  CalibrationScopeKind,
} from '../api/listingCalibrations';
import { CALIBRATION_PURPOSES, prepareCalibration } from '../api/listingCalibrations';
import {
  calibrationCategoryHelp,
  calibrationGroups,
  calibrationText as text,
  calibrationUnits,
  calibrationRules,
} from '../i18n/zh/listingCalibrations';
import { DateTime, FormDrawer, InfoTip, SectionCollapse } from '../ui';
import type { FormDrawerStep, SectionFlag, SubmitOutcome } from '../ui';
import type { Finding, ValueField, ValueFieldMap, ValueFields } from './calibrationValues';
import {
  ALLOWANCE_AXES,
  EXAMPLE_PACKAGE,
  EQUIVALENCE_RULES,
  GROUP_ORDER,
  WINDOW_DAYS,
  categorySpec,
  combinationFindings,
  draftValue,
  exampleFields,
  exampleValues,
  fieldsFromValue,
  isExample,
  looksSecret,
  parseJsonText,
  replacedPackages,
  valueFindings,
} from './calibrationValues';
import { packageName } from './ListingCalibrationDetail';
import { Code, InstantField, codeOptions, codeText } from './ListingCommon';

const CODE = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
const MAX_TEXT = 512;

interface DraftValues {
  readonly purposeCode?: CalibrationPurpose;
  readonly scopeKind?: CalibrationScopeKind;
  readonly storeId?: string;
  readonly platformCode?: string;
  readonly code?: string;
  readonly version?: number | null;
  readonly effectiveFrom?: string;
  readonly effectiveTo?: string;
  readonly evidenceReference?: string;
  readonly rationale?: string;
  readonly impact?: string;
  readonly differences?: string;
  readonly values?: ValueFieldMap;
  readonly replacement?: unknown;
  readonly combination?: unknown;
  readonly acknowledge?: boolean;
}

// ------------------------------------------------------------------ helpers

/** A form rule's answer: resolved when there is no problem, rejected with it otherwise. */
function settle(problem: string | undefined): Promise<void> {
  return problem === undefined ? Promise.resolve() : Promise.reject(new Error(problem));
}

function joined(findings: readonly Finding[], level: Finding['level']): string | undefined {
  const texts = [
    ...new Set(findings.filter((item) => item.level === level).map((item) => item.text)),
  ];
  return texts.length === 0 ? undefined : texts.slice(0, 3).join('；');
}

/** Characters as the database counts them. */
function length(value: string | undefined): number {
  return Array.from((value ?? '').trim()).length;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

const DECIMAL = /^-?\d+(\.\d+)?$/;

/**
 * A decimal with its point moved `places` digits to the right.
 *
 * A ratio is decided as a percentage but stored as the ratio itself, and the
 * two have to convert without the drift `0.07 * 100` would introduce: the value
 * is compared against the stored one to decide whether it is still the example.
 */
function shiftDecimal(textValue: string, places: number): string {
  const negative = textValue.startsWith('-');
  const body = negative ? textValue.slice(1) : textValue;
  const point = body.indexOf('.');
  const digits = body.replace('.', '');
  const target = (point < 0 ? digits.length : point) + places;
  const padded = target < 0 ? `${'0'.repeat(-target)}${digits}` : digits.padEnd(target, '0');
  const cut = Math.max(target, 0);
  const head = padded.slice(0, cut).replace(/^0+(?=\d)/, '');
  const tail = padded.slice(cut).replace(/0+$/, '');
  const shifted = `${head === '' ? '0' : head}${tail === '' ? '' : `.${tail}`}`;
  return negative && shifted !== '0' ? `-${shifted}` : shifted;
}

/** A unit's Chinese name, for a label that has to say what the number means. */
function unitName(unit: string | undefined): string {
  if (unit === undefined || unit === '') return text.decisionMaturityDaysUnit;
  return calibrationUnits[unit] ?? unit;
}

/** The allowance axes a document names, in the fixed order of the four. */
function chosenAxes(jsonText: string | undefined): readonly string[] {
  const parsed = parseJsonText(jsonText);
  if (!parsed.ok) return [];
  const listed = isRecord(parsed.value) ? parsed.value.axes : parsed.value;
  const named: readonly unknown[] = Array.isArray(listed) ? (listed as unknown[]) : [];
  return ALLOWANCE_AXES.filter((axis) => named.includes(axis));
}

function textRules(label: string): FormRule[] {
  return [
    { required: true, whitespace: true, message: text.textRequired(label) },
    {
      validator: (_: unknown, value: string | undefined): Promise<void> =>
        settle(length(value) > MAX_TEXT ? `最多 ${String(MAX_TEXT)} 个字符` : undefined),
    },
  ];
}

/** Five minutes ago, to the minute: activation needs the start to have passed. */
function defaultEffectiveFrom(): string {
  const minute = 60_000;
  return new Date(Math.floor((Date.now() - 5 * minute) / minute) * minute).toISOString();
}

function nextVersion(overview: CalibrationOverview, code: string | undefined): number {
  const versions = overview.packages
    .filter((item) => item.code === code)
    .map((item) => Math.max(item.version, item.latestVersionOfCode));
  return versions.length === 0 ? 1 : Math.max(...versions) + 1;
}

function requiredOf(
  catalogue: CalibrationCatalogue,
  purpose: CalibrationPurpose | undefined,
): readonly string[] {
  return purpose === undefined ? [] : (catalogue.requirements[purpose] ?? []);
}

function ordinalOf(catalogue: CalibrationCatalogue): (code: string) => number {
  const ordinal = new Map(
    catalogue.categories.map((category) => [category.code, category.ordinal]),
  );
  return (code) => ordinal.get(code) ?? 99;
}

/** Whether the caller may prepare in the chosen scope. */
function mayPrepare(
  overview: CalibrationOverview,
  scopeKind: CalibrationScopeKind | undefined,
  storeId: string | undefined,
): boolean {
  if (scopeKind === 'STORE') {
    return overview.stores.some((store) => store.storeId === storeId && store.rights.prepare);
  }
  return overview.organizationRights.prepare;
}

/** Whether the caller holds the accept grant where the draft would apply. */
function holdsAccept(
  overview: CalibrationOverview,
  scopeKind: CalibrationScopeKind | undefined,
  storeId: string | undefined,
): boolean {
  if (scopeKind === 'STORE') {
    return overview.stores.some((store) => store.storeId === storeId && store.rights.accept);
  }
  return overview.organizationRights.accept;
}

/** The categories a draft carries: what the purpose requires, then any kept from the source. */
function draftCategories(
  catalogue: CalibrationCatalogue,
  purpose: CalibrationPurpose | undefined,
  extras: readonly string[],
): readonly string[] {
  const ordinal = ordinalOf(catalogue);
  return [...new Set([...requiredOf(catalogue, purpose), ...extras])].sort(
    (a, b) => ordinal(a) - ordinal(b) || a.localeCompare(b),
  );
}

/** The body sent to the backend; only called on values that passed every rule. */
function buildDraft(
  values: DraftValues,
  overview: CalibrationOverview,
  catalogue: CalibrationCatalogue,
): CalibrationDraft {
  const purpose = values.purposeCode ?? 'LISTING_CONVERSION';
  const scopeKind = values.scopeKind ?? 'STORE';
  const effectiveFrom = values.effectiveFrom ?? '';
  const effectiveTo =
    values.effectiveTo === undefined || values.effectiveTo === '' ? undefined : values.effectiveTo;
  const replaced = replacedPackages(
    overview.packages,
    purpose,
    scopeKind,
    values.storeId,
    values.platformCode,
    effectiveFrom,
    effectiveTo,
  );
  const ordinal = ordinalOf(catalogue);
  const entries = Object.entries(values.values ?? {}).sort(
    ([a], [b]) => ordinal(a) - ordinal(b) || a.localeCompare(b),
  );
  return {
    code: (values.code ?? '').trim(),
    version: values.version ?? 1,
    purposeCode: purpose,
    scopeKind,
    ...(scopeKind === 'STORE' && values.storeId !== undefined ? { storeId: values.storeId } : {}),
    ...(scopeKind === 'PLATFORM' && values.platformCode !== undefined
      ? { platformCode: values.platformCode }
      : {}),
    effectiveFrom,
    ...(effectiveTo === undefined ? {} : { effectiveTo }),
    ...(replaced.length === 1 && replaced[0] !== undefined
      ? { replacesPackageId: replaced[0].id }
      : {}),
    evidenceReference: (values.evidenceReference ?? '').trim(),
    rationale: (values.rationale ?? '').trim(),
    impact: (values.impact ?? '').trim(),
    differences: (values.differences ?? '').trim(),
    values: entries.map(([code, fields]) => draftValue(code, fields ?? {})),
  };
}

/** Every problem of the draft as a whole: per category, between categories, and the secret guard. */
function draftFindings(
  values: DraftValues,
  overview: CalibrationOverview,
  catalogue: CalibrationCatalogue,
): Finding[] {
  const purpose = values.purposeCode;
  if (purpose === undefined) return [];
  const map = values.values ?? {};
  const out: Finding[] = [];
  for (const [code, fields] of Object.entries(map)) {
    out.push(...valueFindings(code, fields, purpose));
  }
  out.push(...combinationFindings(map, purpose));
  const replaced = replacedPackages(
    overview.packages,
    purpose,
    values.scopeKind,
    values.storeId,
    values.platformCode,
    values.effectiveFrom,
    values.effectiveTo,
  );
  if (replaced.length > 1) out.push({ level: 'error', text: calibrationRules.replacementMany });
  if (out.every((item) => item.level !== 'error')) {
    if (looksSecret(JSON.stringify(buildDraft(values, overview, catalogue)))) {
      out.push({ level: 'error', text: calibrationRules.secret });
    }
  }
  return out;
}

/** An input that holds no value; its Form.Item exists only to carry a check. */
function Check(): null {
  return null;
}

// ------------------------------------------------------------------ drawer

export interface CalibrationDraftDrawerProps {
  readonly context: ConsoleRequest;
  readonly open: boolean;
  /** The package a new version is drafted from; undefined starts from the examples. */
  readonly source: CalibrationPackageDetail | undefined;
  readonly overview: CalibrationOverview;
  readonly catalogue: CalibrationCatalogue;
  /** The store chosen in the header, offered first. */
  readonly storeId: string;
  readonly onClose: () => void;
  readonly onCreated: (packageId: string) => void;
}

/**
 * Drafting a calibration package in three steps: what it is and where it
 * applies, every value its purpose requires, and a review of what the database
 * will check. A draft cannot be changed once created, so problems are shown
 * before it is sent.
 */
export function CalibrationDraftDrawer({
  context,
  open,
  source,
  overview,
  catalogue,
  storeId,
  onClose,
  onCreated,
}: CalibrationDraftDrawerProps): React.JSX.Element {
  const { message } = App.useApp();
  const [removed, setRemoved] = useState<readonly string[]>([]);

  const extras = useMemo(() => {
    if (source === undefined) return [];
    const required = requiredOf(catalogue, source.summary.purposeCode);
    return source.values
      .map((value) => value.categoryCode)
      .filter((code) => !required.includes(code) && !removed.includes(code));
  }, [source, catalogue, removed]);

  const initialValues = useMemo(
    () => initialDraft(source, overview, catalogue, storeId),
    // Recomputed on each opening so the start time is fresh.
    [open, source, overview, catalogue, storeId],
  );

  const steps: readonly FormDrawerStep[] = [
    {
      key: 'basics',
      title: text.stepBasics,
      fields: [
        'purposeCode',
        'scopeKind',
        'storeId',
        'platformCode',
        'code',
        'version',
        'effectiveFrom',
        'effectiveTo',
        'replacement',
        'evidenceReference',
        'rationale',
        'impact',
        'differences',
      ],
      content: <BasicsStep overview={overview} source={source} />,
    },
    {
      key: 'values',
      title: text.stepValues,
      fields: [['values']],
      content: (
        <ValuesStep
          catalogue={catalogue}
          extras={extras}
          initialPurpose={initialValues.purposeCode}
          onRemoveExtra={(code) => {
            setRemoved((current) => [...current, code]);
          }}
        />
      ),
    },
    {
      key: 'review',
      title: text.stepReview,
      fields: ['combination', 'acknowledge'],
      content: <ReviewStep overview={overview} catalogue={catalogue} />,
    },
  ];

  return (
    <FormDrawer<DraftValues>
      open={open}
      onClose={onClose}
      onOpen={() => {
        setRemoved([]);
      }}
      size={960}
      title={
        source === undefined
          ? text.draftTitle
          : text.deriveTitle(source.summary.code, source.summary.version)
      }
      initialValues={initialValues}
      steps={steps}
      submitText={text.submit}
      onSubmit={async (values, form): Promise<SubmitOutcome> => {
        const draft = buildDraft(values, overview, catalogue);
        const outcome = await prepareCalibration(context, draft);
        if (!outcome.ok) {
          // The code and version are unique across the whole organization, but
          // the list only carries the scopes this person may see, so the taken
          // version can be one the form could not check.
          const failure = outcome.failure;
          if (failure.kind === 'refused' && failure.code === 'VERSION_CONFLICT') {
            form.setFields([{ name: 'version', errors: [text.versionTakenElsewhere] }]);
          }
          return failure;
        }
        void message.success(text.created(draft.code, draft.version));
        onCreated(outcome.value);
        return undefined;
      }}
    />
  );
}

/** Where the form starts: the source package's content, or the examples for a fitting purpose. */
function initialDraft(
  source: CalibrationPackageDetail | undefined,
  overview: CalibrationOverview,
  catalogue: CalibrationCatalogue,
  headerStore: string,
): DraftValues {
  const effectiveFrom = defaultEffectiveFrom();
  if (source !== undefined) {
    const summary = source.summary;
    const values: Record<string, ValueFields> = {};
    for (const value of source.values) values[value.categoryCode] = fieldsFromValue(value);
    const stillOpen =
      summary.effectiveTo !== null && Date.parse(summary.effectiveTo) > Date.now() + 60_000;
    return {
      purposeCode: summary.purposeCode,
      scopeKind: summary.scopeKind,
      ...(summary.storeId === null ? {} : { storeId: summary.storeId }),
      ...(summary.platformCode === null ? {} : { platformCode: summary.platformCode }),
      code: summary.code,
      version: nextVersion(overview, summary.code),
      effectiveFrom,
      ...(stillOpen ? { effectiveTo: summary.effectiveTo } : {}),
      evidenceReference: source.evidenceReference,
      rationale: source.rationale,
      impact: source.impact,
      differences: '',
      values,
    };
  }
  const active = new Set(
    overview.packages.filter((item) => item.stage === 'ACTIVE').map((item) => item.purposeCode),
  );
  const purpose = CALIBRATION_PURPOSES.find((item) => !active.has(item)) ?? 'LISTING_CONVERSION';
  const preparable = overview.stores.filter((store) => store.rights.prepare);
  const store = preparable.find((item) => item.storeId === headerStore) ?? preparable[0];
  const scope: DraftValues =
    store !== undefined
      ? { scopeKind: 'STORE', storeId: store.storeId }
      : overview.organizationRights.prepare
        ? { scopeKind: 'ORGANIZATION' }
        : { scopeKind: 'STORE' };
  const code = EXAMPLE_PACKAGE.code[purpose];
  const version = nextVersion(overview, code);
  return {
    purposeCode: purpose,
    ...scope,
    code,
    version,
    effectiveFrom,
    evidenceReference: EXAMPLE_PACKAGE.evidenceReference,
    rationale: EXAMPLE_PACKAGE.rationale[purpose],
    impact: EXAMPLE_PACKAGE.impact,
    // 「首个版本」 only holds for version 1; a later version has a predecessor to describe.
    differences: version === 1 ? EXAMPLE_PACKAGE.differences : '',
    values: exampleValues(requiredOf(catalogue, purpose), purpose),
  };
}

// ------------------------------------------------------------------ step 1

function BasicsStep({
  overview,
  source,
}: {
  readonly overview: CalibrationOverview;
  readonly source: CalibrationPackageDetail | undefined;
}): React.JSX.Element {
  const form = Form.useFormInstance<DraftValues>();
  const purpose = Form.useWatch('purposeCode', form);
  const scopeKind = Form.useWatch('scopeKind', form);
  const storeId = Form.useWatch('storeId', form);
  const platformCode = Form.useWatch('platformCode', form);
  const code = Form.useWatch('code', form);
  const effectiveFrom = Form.useWatch('effectiveFrom', form);
  const effectiveTo = Form.useWatch('effectiveTo', form);

  // A new code starts at the next free version; the example code follows the purpose.
  useEffect(() => {
    if (code !== undefined && CODE.test(code)) {
      const version = nextVersion(overview, code);
      form.setFieldValue('version', version);
      // The example wording claims there is no predecessor; from version 2 on
      // there is one, and the alert on this very step names it.
      if (version > 1 && form.getFieldValue('differences') === EXAMPLE_PACKAGE.differences) {
        form.setFieldValue('differences', '');
      }
    }
  }, [code, form, overview]);
  const lastPurpose = useRef<CalibrationPurpose | undefined>(undefined);
  useEffect(() => {
    if (purpose === undefined) return;
    const previous = lastPurpose.current;
    lastPurpose.current = purpose;
    if (previous === undefined || previous === purpose || source !== undefined) return;
    if (form.getFieldValue('code') === EXAMPLE_PACKAGE.code[previous]) {
      form.setFieldValue('code', EXAMPLE_PACKAGE.code[purpose]);
    }
    if (form.getFieldValue('rationale') === EXAMPLE_PACKAGE.rationale[previous]) {
      form.setFieldValue('rationale', EXAMPLE_PACKAGE.rationale[purpose]);
    }
  }, [purpose, form, source]);

  const replaced = replacedPackages(
    overview.packages,
    purpose,
    scopeKind,
    storeId,
    platformCode,
    effectiveFrom,
    effectiveTo,
  );
  const organization = overview.organizationRights.prepare;
  const preparableStores = overview.stores.filter((store) => store.rights.prepare);

  return (
    <>
      <Alert type="warning" showIcon title={text.exampleBanner} style={{ marginBottom: 16 }} />
      <Form.Item
        name="purposeCode"
        label={text.purpose}
        extra={text.purposeHelp}
        rules={[{ required: true, message: text.textRequired(text.purpose) }]}
      >
        <Select<CalibrationPurpose>
          style={{ maxWidth: 360 }}
          options={codeOptions('actionPurpose', CALIBRATION_PURPOSES).map((option) => ({
            ...option,
            value: option.value as CalibrationPurpose,
          }))}
        />
      </Form.Item>
      <Form.Item
        name="scopeKind"
        label={text.scopeKind}
        rules={[
          { required: true, message: text.textRequired(text.scopeKind) },
          ({ getFieldValue }) => ({
            validator: (_: unknown, value: CalibrationScopeKind | undefined): Promise<void> =>
              settle(
                value !== undefined &&
                  !mayPrepare(overview, value, getFieldValue('storeId') as string | undefined) &&
                  value !== 'STORE'
                  ? text.scopeOrganizationDisabled
                  : undefined,
              ),
          }),
        ]}
      >
        <Radio.Group
          options={[
            { value: 'STORE', label: text.store, disabled: preparableStores.length === 0 },
            { value: 'PLATFORM', label: text.platform, disabled: !organization },
            { value: 'ORGANIZATION', label: text.organizationScope, disabled: !organization },
          ]}
        />
      </Form.Item>
      {scopeKind === 'STORE' && (
        <Form.Item
          name="storeId"
          label={text.store}
          rules={[{ required: true, message: text.pickStore }]}
        >
          <Select
            placeholder={text.pickStore}
            style={{ maxWidth: 480 }}
            options={overview.stores.map((store) => ({
              value: store.storeId,
              label: [store.displayName, store.platformCode, store.currencyCode]
                .filter((part) => part !== null && part !== '')
                .join(' · '),
              disabled: !store.rights.prepare,
            }))}
          />
        </Form.Item>
      )}
      {scopeKind === 'PLATFORM' && (
        <Form.Item
          name="platformCode"
          label={text.platform}
          rules={[{ required: true, message: text.pickPlatform }]}
        >
          <Select
            placeholder={text.pickPlatform}
            style={{ maxWidth: 240 }}
            options={overview.platforms.map((platform) => ({ value: platform, label: platform }))}
          />
        </Form.Item>
      )}
      <Flex gap={16} wrap>
        <Form.Item
          name="code"
          label={text.code}
          extra={text.codeHelp}
          style={{ flex: '2 1 320px' }}
          rules={[
            { required: true, message: text.textRequired(text.code) },
            {
              validator: (_: unknown, value: string | undefined): Promise<void> =>
                settle(
                  value === undefined || value === '' || CODE.test(value)
                    ? undefined
                    : text.codeInvalid,
                ),
            },
          ]}
        >
          <Input maxLength={63} disabled={source !== undefined} />
        </Form.Item>
        <Form.Item
          name="version"
          label={text.version}
          extra={text.versionHelp}
          style={{ flex: '1 1 160px' }}
          dependencies={['code']}
          rules={[
            { required: true, message: text.textRequired(text.version) },
            ({ getFieldValue }) => ({
              validator: (_: unknown, value: number | null | undefined): Promise<void> => {
                if (value === null || value === undefined) return Promise.resolve();
                if (!Number.isInteger(value) || value < 1 || value > 2147483647) {
                  return settle(text.versionInvalid);
                }
                const current = getFieldValue('code') as string | undefined;
                return settle(
                  overview.packages.some((item) => item.code === current && item.version === value)
                    ? text.versionTaken
                    : undefined,
                );
              },
            }),
          ]}
        >
          <InputNumber min={1} precision={0} style={{ width: '100%' }} />
        </Form.Item>
      </Flex>
      <Flex gap={16} wrap>
        <Form.Item
          name="effectiveFrom"
          label={text.effectiveFrom}
          extra={text.effectiveFromHelp}
          style={{ flex: '1 1 280px' }}
          rules={[{ required: true, message: text.textRequired(text.effectiveFrom) }]}
        >
          <InstantField ariaLabel={text.effectiveFrom} />
        </Form.Item>
        <Form.Item
          name="effectiveTo"
          label={text.effectiveTo}
          extra={text.effectiveToHelp}
          style={{ flex: '1 1 280px' }}
          dependencies={['effectiveFrom']}
          rules={[
            ({ getFieldValue }) => ({
              validator: (_: unknown, value: string | undefined): Promise<void> => {
                const from = getFieldValue('effectiveFrom') as string | undefined;
                if (value === undefined || value === '' || from === undefined || from === '') {
                  return Promise.resolve();
                }
                return settle(
                  Date.parse(value) > Date.parse(from) ? undefined : text.effectiveToAfter,
                );
              },
            }),
          ]}
        >
          <InstantField ariaLabel={text.effectiveTo} />
        </Form.Item>
      </Flex>
      <Form.Item
        label={
          <span>
            {text.replacement}
            <InfoTip title={text.replacementAuto} />
          </span>
        }
      >
        <Alert
          type={replaced.length > 1 ? 'error' : 'info'}
          showIcon
          title={
            replaced.length > 1
              ? text.replacementMany
              : replaced[0] === undefined
                ? text.replacementWillNone
                : text.replacementWill(packageName(replaced[0]))
          }
        />
      </Form.Item>
      <Form.Item
        name="replacement"
        noStyle
        dependencies={[
          'purposeCode',
          'scopeKind',
          'storeId',
          'platformCode',
          'effectiveFrom',
          'effectiveTo',
        ]}
        rules={[
          ({ getFieldValue }) => ({
            validator: (): Promise<void> =>
              settle(
                replacedPackages(
                  overview.packages,
                  getFieldValue('purposeCode') as CalibrationPurpose | undefined,
                  getFieldValue('scopeKind') as string | undefined,
                  getFieldValue('storeId') as string | undefined,
                  getFieldValue('platformCode') as string | undefined,
                  getFieldValue('effectiveFrom') as string | undefined,
                  getFieldValue('effectiveTo') as string | undefined,
                ).length > 1
                  ? text.replacementMany
                  : undefined,
              ),
          }),
        ]}
      >
        <Check />
      </Form.Item>
      <Form.Item name="evidenceReference" label={text.evidence} rules={textRules(text.evidence)}>
        <Input maxLength={MAX_TEXT} placeholder={text.evidencePlaceholder} />
      </Form.Item>
      <Form.Item name="rationale" label={text.rationale} rules={textRules(text.rationale)}>
        <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} maxLength={MAX_TEXT} showCount />
      </Form.Item>
      <Form.Item name="impact" label={text.impact} rules={textRules(text.impact)}>
        <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} maxLength={MAX_TEXT} showCount />
      </Form.Item>
      <Form.Item name="differences" label={text.differences} rules={textRules(text.differences)}>
        <Input.TextArea
          autoSize={{ minRows: 2, maxRows: 4 }}
          maxLength={MAX_TEXT}
          showCount
          {...(source === undefined
            ? {}
            : { placeholder: text.differencesPlaceholder(source.summary.version) })}
        />
      </Form.Item>
      {holdsAccept(overview, scopeKind, storeId) && (
        <Alert type="warning" showIcon title={text.ownerDraftWarning} />
      )}
    </>
  );
}

// ------------------------------------------------------------------ step 2

/** The section key of the folded declarations. */
const DECLARATIONS = 'declarations';

/**
 * The fields a decision card edits itself.
 *
 * A problem on one of these is already in front of the operator, so it must not
 * be what opens the folded section.
 */
const DECIDED_FIELDS: Readonly<Partial<Record<string, readonly ValueField[]>>> = {
  ORDINARY_TRIGGER_EXPOSURE: ['numeric', 'windowDays'],
  MATERIAL_TRIGGER_EXPOSURE: ['numeric', 'windowDays'],
  APPROVAL_VALIDITY: ['numeric'],
};

function noteMissing(value: string | undefined): boolean {
  const count = length(value);
  return count === 0 || count > MAX_TEXT;
}

/**
 * What the folded section holds that the operator has not seen.
 *
 * Only an error opens it by itself: the seeded examples carry warnings of their
 * own (the existence of a Listing can only be confirmed by the database, a
 * purpose may want scenarios), and opening on those would leave the section
 * permanently unfolded, which is the state this step moved away from. A warning
 * is shown as a flag in the header instead, which stays visible while folded.
 *
 * A category the watched values do not carry yet is passed over, not called
 * incomplete: the step is rendered before the watch has read the seeded values,
 * and a new purpose adds its categories one render before their fields are
 * read back. Neither is a problem the operator could answer here.
 */
function foldedState(
  categories: readonly string[],
  values: ValueFieldMap,
  purpose: CalibrationPurpose | undefined,
): { readonly failing: boolean; readonly doubtful: boolean } {
  let failing = false;
  let doubtful = false;
  for (const code of categories) {
    const fields = values[code];
    if (fields === undefined) continue;
    const decided = DECIDED_FIELDS[code] ?? [];
    for (const finding of valueFindings(code, fields, purpose)) {
      if (finding.field !== undefined && decided.includes(finding.field)) continue;
      if (finding.level === 'error') failing = true;
      else doubtful = true;
    }
    if (noteMissing(fields.scopeNote) || noteMissing(fields.evidenceReference)) failing = true;
  }
  return { failing, doubtful };
}

function ValuesStep({
  catalogue,
  extras,
  initialPurpose,
  onRemoveExtra,
}: {
  readonly catalogue: CalibrationCatalogue;
  readonly extras: readonly string[];
  readonly initialPurpose: CalibrationPurpose | undefined;
  readonly onRemoveExtra: (code: string) => void;
}): React.JSX.Element {
  const form = Form.useFormInstance<DraftValues>();
  const purpose = Form.useWatch('purposeCode', form);
  const seeded = useRef(initialPurpose);
  const categories = useMemo(
    () => draftCategories(catalogue, purpose, extras),
    [catalogue, purpose, extras],
  );
  const required = requiredOf(catalogue, purpose);
  const values = Form.useWatch((all: DraftValues) => all.values, form);
  const { failing, doubtful } = useMemo(
    () => foldedState(categories, values ?? {}, purpose),
    [categories, values, purpose],
  );
  const [openKeys, setOpenKeys] = useState<readonly string[]>([]);

  // A problem inside the folded section would otherwise look like a dead 下一步
  // button. It opens by itself when one appears, and stays where the operator
  // last left it afterwards.
  const wasFailing = useRef(false);
  useEffect(() => {
    if (failing && !wasFailing.current) {
      setOpenKeys((current) =>
        current.includes(DECLARATIONS) ? current : [...current, DECLARATIONS],
      );
    }
    wasFailing.current = failing;
  }, [failing]);

  // A new purpose brings its own examples; anything already edited is kept.
  useEffect(() => {
    if (purpose === undefined || purpose === seeded.current) return;
    const previous = seeded.current;
    seeded.current = purpose;
    const current = (form.getFieldValue('values') ?? {}) as ValueFieldMap;
    const next: Record<string, ValueFields> = {};
    for (const code of draftCategories(catalogue, purpose, extras)) {
      const fields = current[code];
      const untouched =
        fields === undefined || (previous !== undefined && isExample(code, previous, fields));
      next[code] = untouched ? (exampleFields(code, purpose) ?? fields ?? {}) : fields;
    }
    form.setFieldValue('values', next);
  }, [purpose, form, catalogue, extras]);

  // A removed category has to leave the draft, not only the screen: an
  // unmounted field keeps its initial value in the form store, and the draft is
  // built from that whole map. This runs after the editor's own unmount.
  // The watched purpose is undefined on the first render, when the category
  // list is empty but the seeded examples are already in the store: dropping
  // them here would empty the whole step.
  useEffect(() => {
    if (purpose === undefined) return;
    const current = (form.getFieldValue('values') ?? {}) as ValueFieldMap;
    const kept = Object.entries(current).filter(([code]) => categories.includes(code));
    if (kept.length === Object.keys(current).length) return;
    form.setFieldValue('values', Object.fromEntries(kept));
  }, [purpose, categories, form]);

  if (purpose === undefined) {
    return <Typography.Text type="secondary">{text.purposeHelp}</Typography.Text>;
  }

  return (
    <Flex vertical gap={12}>
      <Alert type="warning" showIcon title={text.exampleBanner} />
      <Flex justify="flex-end">
        <Button
          size="small"
          onClick={() => {
            const evidence = (form.getFieldValue('evidenceReference') ?? '') as string;
            const current = (form.getFieldValue('values') ?? {}) as ValueFieldMap;
            const next: Record<string, ValueFields> = {};
            for (const [code, fields] of Object.entries(current)) {
              next[code] = { ...(fields ?? {}), evidenceReference: evidence };
            }
            form.setFieldValue('values', next);
          }}
        >
          {text.fillEvidence}
        </Button>
      </Flex>
      <Decisions purpose={purpose} categories={categories} />
      <SectionCollapse
        size="small"
        openKeys={openKeys}
        onOpenChange={setOpenKeys}
        items={[
          {
            key: DECLARATIONS,
            title: text.declarationsTitle,
            summary: text.declarationsSummary(categories.length),
            flags: declarationFlags(failing, doubtful),
            forceRender: true,
            children: (
              <Flex vertical gap={12}>
                <Typography.Text type="secondary">{text.declarationsHelp}</Typography.Text>
                {GROUP_ORDER.map((group) => {
                  const codes = categories.filter(
                    (code) => (categorySpec(code)?.group ?? 'protection') === group,
                  );
                  if (codes.length === 0) return null;
                  return (
                    <Card key={group} size="small" title={calibrationGroups[group] ?? group}>
                      <Flex vertical gap={16}>
                        {codes.map((code) => (
                          <CategoryEditor
                            key={code}
                            code={code}
                            purpose={purpose}
                            extra={!required.includes(code)}
                            onRemove={() => {
                              onRemoveExtra(code);
                            }}
                          />
                        ))}
                      </Flex>
                    </Card>
                  );
                })}
              </Flex>
            ),
          },
        ]}
      />
    </Flex>
  );
}

/** What the folded header must keep in view: a blocking problem, or a prompt. */
function declarationFlags(failing: boolean, doubtful: boolean): SectionFlag[] {
  return [
    ...(failing ? [{ key: 'error', label: text.declarationsError, color: 'error' as const }] : []),
    ...(doubtful
      ? [{ key: 'warning', label: text.declarationsWarning, color: 'warning' as const }]
      : []),
  ];
}

/**
 * The few values the operator actually decides, ahead of the declarations.
 *
 * Every editor here writes the same form value its full editor in the folded
 * section writes; nothing is held twice. What is different is what the operator
 * is asked: a percentage instead of a ratio, a day count instead of a place in
 * a JSON document, the four axes instead of an array literal.
 */
function Decisions({
  purpose,
  categories,
}: {
  readonly purpose: CalibrationPurpose;
  readonly categories: readonly string[];
}): React.JSX.Element | null {
  const form = Form.useFormInstance<DraftValues>();
  const validityUnit = Form.useWatch(
    (values: DraftValues) => values.values?.APPROVAL_VALIDITY?.unitCode,
    form,
  );
  const has = (...codes: readonly string[]): boolean =>
    codes.every((code) => categories.includes(code));
  const exposure = has('ORDINARY_TRIGGER_EXPOSURE', 'MATERIAL_TRIGGER_EXPOSURE');
  const approval = has('APPROVAL_VALIDITY');
  const maturity = has('RESPONSIBILITY_SLO');
  const allowance = has('ALLOWANCE_AXES', 'ALLOWANCE_RESERVE');
  if (!exposure && !approval && !maturity && !allowance) return null;

  return (
    <Card size="small" title={text.decisionsTitle}>
      <Flex vertical gap={20}>
        <Typography.Text type="secondary">{text.decisionsHelp}</Typography.Text>
        {exposure && (
          <Decision
            title={text.decisionExposure}
            effect={text.decisionExposureEffect}
            codes={['ORDINARY_TRIGGER_EXPOSURE', 'MATERIAL_TRIGGER_EXPOSURE']}
            purpose={purpose}
          >
            <Flex gap={12} wrap align="flex-start">
              <Form.Item
                name={['values', 'ORDINARY_TRIGGER_EXPOSURE', 'numeric']}
                label={text.decisionExposureOrdinary}
                required
                style={{ flex: '1 1 180px', marginBottom: 0 }}
                rules={fieldRules('ORDINARY_TRIGGER_EXPOSURE', 'numeric')}
              >
                <PercentField id="decision-ordinary-exposure" />
              </Form.Item>
              <Form.Item
                name={['values', 'MATERIAL_TRIGGER_EXPOSURE', 'numeric']}
                label={text.decisionExposureMaterial}
                required
                style={{ flex: '1 1 180px', marginBottom: 0 }}
                rules={fieldRules('MATERIAL_TRIGGER_EXPOSURE', 'numeric')}
              >
                <PercentField id="decision-material-exposure" />
              </Form.Item>
              <Form.Item
                name={['values', 'ORDINARY_TRIGGER_EXPOSURE', 'windowDays']}
                label={text.decisionExposureWindow}
                required
                extra={text.decisionExposureWindowHelp}
                style={{ flex: '1 1 220px', marginBottom: 0 }}
                rules={fieldRules('ORDINARY_TRIGGER_EXPOSURE', 'windowDays')}
              >
                <ExposureWindowField id="decision-exposure-window" />
              </Form.Item>
            </Flex>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {text.decisionExposurePercentHelp}
            </Typography.Text>
          </Decision>
        )}
        {approval && (
          <Decision
            title={text.decisionApproval}
            effect={text.decisionApprovalEffect}
            codes={['APPROVAL_VALIDITY']}
            purpose={purpose}
          >
            <Form.Item
              name={['values', 'APPROVAL_VALIDITY', 'numeric']}
              label={text.decisionApprovalValue(unitName(validityUnit))}
              required
              style={{ maxWidth: 240, marginBottom: 0 }}
              rules={fieldRules('APPROVAL_VALIDITY', 'numeric')}
            >
              <CountField id="decision-approval-validity" suffix={unitName(validityUnit)} />
            </Form.Item>
          </Decision>
        )}
        {maturity && (
          <Decision
            title={text.decisionMaturity}
            effect={text.decisionMaturityEffect}
            codes={['RESPONSIBILITY_SLO']}
            purpose={purpose}
          >
            <Form.Item
              name={['values', 'RESPONSIBILITY_SLO', 'json']}
              style={{ marginBottom: 0 }}
              rules={fieldRules('RESPONSIBILITY_SLO', 'json')}
            >
              <MaturityField />
            </Form.Item>
          </Decision>
        )}
        {allowance && (
          <Decision
            title={text.decisionAllowance}
            effect={text.decisionAllowanceEffect}
            codes={['ALLOWANCE_AXES', 'ALLOWANCE_RESERVE']}
            purpose={purpose}
          >
            <Form.Item
              name={['values', 'ALLOWANCE_AXES', 'json']}
              label={text.decisionAllowanceAxes}
              required
              style={{ marginBottom: 12 }}
              rules={fieldRules('ALLOWANCE_AXES', 'json')}
            >
              <AxesField />
            </Form.Item>
            <Form.Item
              name={['values', 'ALLOWANCE_RESERVE', 'json']}
              label={text.decisionAllowanceReserve}
              required
              extra={text.decisionAllowanceReserveHelp}
              style={{ marginBottom: 0 }}
              rules={fieldRules('ALLOWANCE_RESERVE', 'json')}
            >
              <ReserveField />
            </Form.Item>
          </Decision>
        )}
      </Flex>
    </Card>
  );
}

/** One decision: its plain name, what the value causes, its raw category codes. */
function Decision({
  title,
  effect,
  codes,
  purpose,
  children,
}: {
  readonly title: string;
  readonly effect: string;
  readonly codes: readonly string[];
  readonly purpose: CalibrationPurpose;
  readonly children: React.ReactNode;
}): React.JSX.Element {
  const form = Form.useFormInstance<DraftValues>();
  // Only this decision's own categories: the whole map is serialized on every
  // keystroke to decide whether the watch changed, and it holds every document.
  const held = Form.useWatch((all: DraftValues) => codes.map((code) => all.values?.[code]), form);
  const examples = codes.map((code) => exampleFields(code, purpose));
  return (
    <Flex vertical gap={6} data-decision={codes[0]}>
      <Flex gap={8} align="center" wrap>
        <Typography.Text strong>{title}</Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {text.decisionCodes(codes)}
        </Typography.Text>
        {codes.every((code, index) => isExample(code, purpose, held[index])) && (
          <Tag color="gold" style={{ marginInlineEnd: 0 }}>
            {text.valueExample}
          </Tag>
        )}
        {examples.some((fields) => fields !== undefined) && (
          <Button
            size="small"
            type="link"
            style={{ marginInlineStart: 'auto' }}
            onClick={() => {
              codes.forEach((code, index) => {
                const fields = examples[index];
                if (fields !== undefined) form.setFieldValue(['values', code], fields);
              });
              void form
                .validateFields(
                  codes.map((code) => ['values', code]),
                  { recursive: true },
                )
                .catch(() => undefined);
            }}
          >
            {text.useExample}
          </Button>
        )}
      </Flex>
      <Typography.Text type="secondary">{effect}</Typography.Text>
      {children}
    </Flex>
  );
}

/** A form control over one value of the form; antd fills in value and onChange. */
interface ControlProps {
  readonly value?: string;
  readonly onChange?: (value: string) => void;
  readonly id?: string;
}

/** A 0–1 ratio, decided as a percentage; the form keeps the ratio itself. */
function PercentField({ value, onChange, id }: ControlProps): React.JSX.Element {
  const trimmed = value?.trim() ?? '';
  return (
    <InputNumber<string>
      stringMode
      {...(id === undefined ? {} : { id })}
      style={{ width: '100%' }}
      value={DECIMAL.test(trimmed) ? shiftDecimal(trimmed, 2) : null}
      min="0"
      max="100"
      suffix="%"
      onChange={(next) => {
        if (next === null) {
          onChange?.('');
          return;
        }
        onChange?.(DECIMAL.test(next) ? shiftDecimal(next, -2) : next);
      }}
    />
  );
}

/** A whole count, with the unit it is counted in beside it. */
function CountField({
  value,
  onChange,
  id,
  suffix,
}: ControlProps & { readonly suffix: string }): React.JSX.Element {
  const trimmed = value?.trim() ?? '';
  return (
    <InputNumber<string>
      stringMode
      {...(id === undefined ? {} : { id })}
      style={{ width: '100%' }}
      value={DECIMAL.test(trimmed) ? trimmed : null}
      min="1"
      precision={0}
      suffix={suffix}
      onChange={(next) => {
        onChange?.(next ?? '');
      }}
    />
  );
}

/** The window both exposure thresholds are measured over: one choice sets both. */
function ExposureWindowField({
  value,
  onChange,
  id,
}: {
  readonly value?: number;
  readonly onChange?: (value: number) => void;
  readonly id?: string;
}): React.JSX.Element {
  const form = Form.useFormInstance<DraftValues>();
  const other = ['values', 'MATERIAL_TRIGGER_EXPOSURE', 'windowDays'];
  return (
    <Select<number>
      {...(id === undefined ? {} : { id })}
      {...(value === undefined ? {} : { value })}
      placeholder={text.windowPlaceholder}
      options={WINDOW_DAYS.map((days) => ({ value: days, label: text.valueWindowDays(days) }))}
      onChange={(next) => {
        onChange?.(next);
        form.setFieldValue(other, next);
        void form.validateFields([other]).catch(() => undefined);
      }}
    />
  );
}

/**
 * The outcome maturity of the responsibility SLO, one input per risk class.
 *
 * `ops.lc_description_outcome_maturity` (V0006) reads the document's own
 * `outcomeMaturityDays` to decide when a verified description change releases
 * the launch allowance it occupies, and `ListingResponsibilitySchedule` reads
 * the same key for the ordinary clock and `necessaryRisk.outcomeMaturityDays`
 * for the risk clock. Those are the keys edited here; every other key of the
 * document is carried over untouched.
 */
function MaturityField({ value, onChange }: ControlProps): React.JSX.Element {
  const parsed = parseJsonText(value);
  const document = parsed.ok && isRecord(parsed.value) ? parsed.value : undefined;
  if (document === undefined) {
    return <Alert type="warning" showIcon title={text.decisionMaturityUnreadable} />;
  }
  const riskValue = document.necessaryRisk;
  const risk = isRecord(riskValue) ? riskValue : undefined;
  const write = (next: Record<string, unknown>): void => {
    onChange?.(JSON.stringify(next, null, 2));
  };
  const withDays = (
    target: Record<string, unknown>,
    days: number | null,
  ): Record<string, unknown> => {
    const next = { ...target };
    if (days === null) delete next.outcomeMaturityDays;
    else next.outcomeMaturityDays = days;
    return next;
  };
  return (
    <Flex vertical gap={8}>
      <Flex gap={16} wrap align="flex-start">
        <MaturityDays
          label={text.decisionMaturityOrdinary}
          path="RESPONSIBILITY_SLO.outcomeMaturityDays"
          value={document.outcomeMaturityDays}
          onChange={(days) => {
            write(withDays(document, days));
          }}
        />
        {risk !== undefined && (
          <MaturityDays
            label={text.decisionMaturityRisk}
            path="RESPONSIBILITY_SLO.necessaryRisk.outcomeMaturityDays"
            value={risk.outcomeMaturityDays}
            onChange={(days) => {
              write({ ...document, necessaryRisk: withDays(risk, days) });
            }}
          />
        )}
      </Flex>
      {risk === undefined && (
        <Typography.Text type="warning">{text.decisionMaturityNoRisk}</Typography.Text>
      )}
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {text.decisionMaturityKeep}
      </Typography.Text>
    </Flex>
  );
}

/** One risk class's day count, with the document key it writes named beneath it. */
function MaturityDays({
  label,
  path,
  value,
  onChange,
}: {
  readonly label: string;
  readonly path: string;
  readonly value: unknown;
  readonly onChange: (days: number | null) => void;
}): React.JSX.Element {
  return (
    <Flex vertical gap={2} style={{ flex: '0 1 240px' }}>
      <Typography.Text>{label}</Typography.Text>
      <InputNumber
        aria-label={label}
        style={{ width: '100%' }}
        min={1}
        max={3660}
        precision={0}
        suffix={text.decisionMaturityDaysUnit}
        value={typeof value === 'number' ? value : null}
        onChange={onChange}
      />
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {text.decisionMaturityPath(path)}
      </Typography.Text>
    </Flex>
  );
}

/** Which allowance axes a launch occupies. */
function AxesField({ value, onChange }: ControlProps): React.JSX.Element {
  const parsed = parseJsonText(value);
  if (!parsed.ok) {
    return <Alert type="warning" showIcon title={text.decisionAllowanceUnreadable} />;
  }
  const document = parsed.value;
  return (
    <Checkbox.Group<string>
      value={[...chosenAxes(value)]}
      options={ALLOWANCE_AXES.map((axis) => ({
        value: axis,
        label: codeText('allowanceAxis', axis),
      }))}
      onChange={(next) => {
        const axes = ALLOWANCE_AXES.filter((axis) => next.includes(axis));
        onChange?.(JSON.stringify(isRecord(document) ? { ...document, axes } : axes, null, 2));
      }}
    />
  );
}

/** The disposal reserve of every axis the package occupies. */
function ReserveField({ value, onChange }: ControlProps): React.JSX.Element {
  const form = Form.useFormInstance<DraftValues>();
  const axesText = Form.useWatch(
    (values: DraftValues) => values.values?.ALLOWANCE_AXES?.json,
    form,
  );
  const parsed = parseJsonText(value);
  const document = parsed.ok && isRecord(parsed.value) ? parsed.value : undefined;
  if (document === undefined) {
    return <Alert type="warning" showIcon title={text.decisionAllowanceUnreadable} />;
  }
  const axes = chosenAxes(axesText);
  if (axes.length === 0) {
    return <Typography.Text type="secondary">{text.decisionAllowanceNoAxes}</Typography.Text>;
  }
  return (
    <Flex gap={16} wrap align="flex-start">
      {axes.map((axis) => {
        const amount = document[axis];
        const name = codeText('allowanceAxis', axis);
        return (
          <Flex key={axis} vertical gap={2} style={{ flex: '0 1 220px' }}>
            <Typography.Text>{name}</Typography.Text>
            <InputNumber
              aria-label={name}
              style={{ width: '100%' }}
              min={0}
              value={typeof amount === 'number' ? amount : null}
              onChange={(next) => {
                // Replaced where it already stands, so the document an operator
                // reads in 「口径声明」 keeps its order; cleared it leaves, and the
                // combination check names the axis that has lost its reserve.
                const entries = Object.entries(document).flatMap<[string, unknown]>(
                  ([key, held]) =>
                    key !== axis ? [[key, held]] : next === null ? [] : [[key, next]],
                );
                if (!Object.hasOwn(document, axis) && next !== null) entries.push([axis, next]);
                onChange?.(JSON.stringify(Object.fromEntries(entries), null, 2));
              }}
            />
          </Flex>
        );
      })}
    </Flex>
  );
}

/** Rules for one field of one category: its errors block, its warnings only show. */
function fieldRules(code: string, field: ValueField): FormRule[] {
  const findings = (
    getFieldValue: (name: (string | number)[] | string) => unknown,
    value: unknown,
  ): Finding[] => {
    const current = (getFieldValue(['values', code]) ?? {}) as ValueFields;
    const purpose = getFieldValue('purposeCode') as CalibrationPurpose | undefined;
    const fields = { ...current, [field]: value };
    return valueFindings(code, fields, purpose).filter((item) => item.field === field);
  };
  return [
    ({ getFieldValue }) => ({
      validator: (_: unknown, value: unknown): Promise<void> =>
        settle(joined(findings(getFieldValue, value), 'error')),
    }),
    ({ getFieldValue }) => ({
      warningOnly: true,
      validator: (_: unknown, value: unknown): Promise<void> =>
        settle(joined(findings(getFieldValue, value), 'warning')),
    }),
  ];
}

function unitLabel(unit: string): string {
  const name = calibrationUnits[unit];
  return name === undefined ? unit : `${unit}（${name}）`;
}

function CategoryEditor({
  code,
  purpose,
  extra,
  onRemove,
}: {
  readonly code: string;
  readonly purpose: CalibrationPurpose;
  readonly extra: boolean;
  readonly onRemove: () => void;
}): React.JSX.Element {
  const form = Form.useFormInstance<DraftValues>();
  const fields = Form.useWatch((values: DraftValues) => values.values?.[code], form);
  const found = categorySpec(code);
  const shape = found?.shape ?? 'JSON';
  const help = calibrationCategoryHelp[code];
  const example = exampleFields(code, purpose);
  const path = (field: keyof ValueFields): (string | number)[] => ['values', code, field];
  const revalidate = (field: keyof ValueFields): void => {
    void form.validateFields([path(field)]).catch(() => undefined);
  };

  const formatJson = (): void => {
    const parsed = parseJsonText(form.getFieldValue(path('json')) as string | undefined);
    if (parsed.ok) {
      form.setFieldValue(path('json'), JSON.stringify(parsed.value, null, 2));
      revalidate('json');
    }
  };

  return (
    <Flex vertical gap={4} data-category={code}>
      <Flex gap={8} align="center" wrap>
        <Typography.Text strong>
          {codeText('calibrationCategory', code)}
          {help !== undefined && <InfoTip title={help} long />}
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {code}
        </Typography.Text>
        {isExample(code, purpose, fields) && (
          <Tag color="gold" style={{ marginInlineEnd: 0 }}>
            {text.valueExample}
          </Tag>
        )}
        {extra && (
          <Tag style={{ marginInlineEnd: 0 }}>
            {text.extraCategory}
            <InfoTip title={text.extraHelp} />
          </Tag>
        )}
        <Flex gap={4} style={{ marginInlineStart: 'auto' }}>
          {example !== undefined && (
            <Button
              size="small"
              type="link"
              onClick={() => {
                form.setFieldValue(['values', code], example);
                void form
                  .validateFields([['values', code]], { recursive: true })
                  .catch(() => undefined);
              }}
            >
              {text.useExample}
            </Button>
          )}
          {extra && (
            <Button size="small" type="link" danger onClick={onRemove}>
              {text.removeExtra}
            </Button>
          )}
        </Flex>
      </Flex>
      <Flex gap={12} wrap align="flex-start">
        {shape === 'NUMERIC' && (
          <Form.Item
            name={path('numeric')}
            label={text.valueNumeric}
            required
            style={{ flex: '1 1 200px', marginBottom: 8 }}
            rules={fieldRules(code, 'numeric')}
          >
            <Input inputMode="decimal" placeholder={text.numericPlaceholder} />
          </Form.Item>
        )}
        {shape === 'TEXT' && (
          <Form.Item
            name={path('text')}
            label={text.valueText}
            required
            style={{ flex: '1 1 240px', marginBottom: 8 }}
            rules={fieldRules(code, 'text')}
          >
            {code === 'REPRESENTATION_EQUIVALENCE_RULE' ? (
              <Select options={EQUIVALENCE_RULES.map((value) => ({ value, label: value }))} />
            ) : (
              <Input maxLength={MAX_TEXT} />
            )}
          </Form.Item>
        )}
        <Form.Item
          name={path('unitCode')}
          label={text.valueUnit}
          required
          style={{ flex: '1 1 200px', marginBottom: 8 }}
          rules={fieldRules(code, 'unitCode')}
        >
          {found?.unitEnforced === true ? (
            <Select
              options={found.units.map((unit) => ({ value: unit, label: unitLabel(unit) }))}
            />
          ) : (
            <AutoComplete
              placeholder={text.unitPlaceholder}
              options={(found?.units ?? []).map((unit) => ({
                value: unit,
                label: unitLabel(unit),
              }))}
            />
          )}
        </Form.Item>
        {found?.window === true && (
          <Form.Item
            name={path('windowDays')}
            label={text.valueWindow}
            required
            style={{ flex: '0 1 140px', marginBottom: 8 }}
            rules={fieldRules(code, 'windowDays')}
          >
            <Select
              placeholder={text.windowPlaceholder}
              options={WINDOW_DAYS.map((days) => ({
                value: days,
                label: text.valueWindowDays(days),
              }))}
            />
          </Form.Item>
        )}
      </Flex>
      {(shape === 'JSON' || found?.optionalJson === true) && (
        <Form.Item
          name={path('json')}
          label={
            <Flex gap={8} align="center">
              <span>{shape === 'JSON' ? text.valueJson : text.profitJson}</span>
              <Button size="small" type="link" onClick={formatJson} style={{ padding: 0 }}>
                {text.format}
              </Button>
            </Flex>
          }
          required={shape === 'JSON'}
          style={{ marginBottom: 8 }}
          {...(shape === 'JSON' ? {} : { extra: text.profitJsonHelp })}
          rules={fieldRules(code, 'json')}
        >
          <Input.TextArea
            autoSize={{ minRows: 3, maxRows: 16 }}
            spellCheck={false}
            placeholder={text.jsonPlaceholder}
            style={{
              fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
              fontSize: 12,
            }}
          />
        </Form.Item>
      )}
      <Flex gap={12} wrap align="flex-start">
        <Form.Item
          name={path('scopeNote')}
          label={text.scopeNote}
          style={{ flex: '2 1 360px', marginBottom: 0 }}
          rules={textRules(text.scopeNote)}
        >
          <Input.TextArea autoSize={{ minRows: 1, maxRows: 3 }} maxLength={MAX_TEXT} />
        </Form.Item>
        <Form.Item
          name={path('evidenceReference')}
          label={text.valueEvidence}
          style={{ flex: '1 1 240px', marginBottom: 0 }}
          rules={textRules(text.valueEvidence)}
        >
          <Input maxLength={MAX_TEXT} />
        </Form.Item>
      </Flex>
    </Flex>
  );
}

// ------------------------------------------------------------------ step 3

function shortValue(fields: ValueFields | undefined, code: string): string {
  const shape = categorySpec(code)?.shape ?? 'JSON';
  if (fields === undefined) return '—';
  if (shape === 'NUMERIC') {
    const document = fields.json?.trim() ?? '';
    return `${fields.numeric ?? '—'}${document === '' ? '' : ' + JSON'}`;
  }
  if (shape === 'TEXT') return fields.text ?? '—';
  const parsed = parseJsonText(fields.json);
  if (!parsed.ok) return '—';
  const compact = JSON.stringify(parsed.value);
  return compact.length > 80 ? `${compact.slice(0, 80)}…` : compact;
}

/** The four decisions said in plain language, for the review step to lead with. */
function decisionSummary(
  all: DraftValues,
): { key: string; label: string; children: React.ReactNode }[] {
  const values = all.values ?? {};
  const missing = text.reviewDecisionMissing;
  const percent = (fields: ValueFields | undefined): string => {
    const trimmed = fields?.numeric?.trim() ?? '';
    return DECIMAL.test(trimmed) ? `${shiftDecimal(trimmed, 2)}%` : missing;
  };
  const amount = (value: unknown): string => (typeof value === 'number' ? String(value) : missing);
  const window = values.ORDINARY_TRIGGER_EXPOSURE?.windowDays;
  const validity = values.APPROVAL_VALIDITY?.numeric?.trim() ?? '';
  const slo = parseJsonText(values.RESPONSIBILITY_SLO?.json);
  const document = slo.ok && isRecord(slo.value) ? slo.value : undefined;
  const riskValue = document === undefined ? undefined : document.necessaryRisk;
  const risk = isRecord(riskValue) ? riskValue : undefined;
  const axes = chosenAxes(values.ALLOWANCE_AXES?.json);
  const reserveParsed = parseJsonText(values.ALLOWANCE_RESERVE?.json);
  const reserve = reserveParsed.ok && isRecord(reserveParsed.value) ? reserveParsed.value : {};
  return [
    {
      key: 'exposure',
      label: text.decisionExposure,
      children: text.reviewDecisionExposure(
        percent(values.ORDINARY_TRIGGER_EXPOSURE),
        percent(values.MATERIAL_TRIGGER_EXPOSURE),
        window === undefined ? missing : text.valueWindowDays(window),
      ),
    },
    {
      key: 'approval',
      label: text.decisionApproval,
      children: text.reviewDecisionApproval(
        validity === '' ? missing : validity,
        unitName(values.APPROVAL_VALIDITY?.unitCode),
      ),
    },
    {
      key: 'maturity',
      label: text.decisionMaturity,
      children: [
        text.reviewDecisionMaturity(amount(document?.outcomeMaturityDays)),
        ...(risk === undefined
          ? []
          : [text.reviewDecisionMaturityRisk(amount(risk.outcomeMaturityDays))]),
      ].join(' · '),
    },
    {
      key: 'allowance',
      label: text.decisionAllowance,
      children:
        axes.length === 0
          ? text.decisionAllowanceNoAxes
          : axes
              .map((axis) =>
                text.reviewDecisionAllowanceAxis(
                  codeText('allowanceAxis', axis),
                  amount(reserve[axis]),
                ),
              )
              .join('、'),
    },
  ];
}

function ReviewStep({
  overview,
  catalogue,
}: {
  readonly overview: CalibrationOverview;
  readonly catalogue: CalibrationCatalogue;
}): React.JSX.Element {
  const form = Form.useFormInstance<DraftValues>();
  // useWatch answers undefined until the form is connected.
  const watched = Form.useWatch([], form) as DraftValues | undefined;
  const all = watched ?? {};
  const findings = all.purposeCode === undefined ? [] : draftFindings(all, overview, catalogue);
  const errors = [...new Set(findings.filter((item) => item.level === 'error').map(findingText))];
  const warnings = [
    ...new Set(findings.filter((item) => item.level === 'warning').map(findingText)),
  ];
  const ordinal = ordinalOf(catalogue);
  const entries = Object.entries(all.values ?? {}).sort(
    ([a], [b]) => ordinal(a) - ordinal(b) || a.localeCompare(b),
  );
  const replaced = replacedPackages(
    overview.packages,
    all.purposeCode,
    all.scopeKind,
    all.storeId,
    all.platformCode,
    all.effectiveFrom,
    all.effectiveTo,
  );
  const store = overview.stores.find((item) => item.storeId === all.storeId);
  const scopeName =
    all.scopeKind === 'STORE'
      ? (store?.displayName ?? text.storeUnknown)
      : all.scopeKind === 'PLATFORM'
        ? (all.platformCode ?? '—')
        : text.organizationScope;

  return (
    <Flex vertical gap={16}>
      {all.purposeCode !== undefined && (
        <Descriptions
          bordered
          size="small"
          title={text.reviewDecisions}
          column={1}
          items={decisionSummary(all)}
        />
      )}
      <Descriptions
        bordered
        size="small"
        title={text.reviewSummary}
        column={{ xs: 1, md: 2 }}
        items={[
          {
            key: 'purpose',
            label: text.purpose,
            children: <Code family="actionPurpose" code={all.purposeCode} />,
          },
          {
            key: 'scope',
            label: text.scopeKind,
            children: (
              <Flex gap={4} align="center" wrap>
                <Code family="allowanceScope" code={all.scopeKind} />
                <span>{scopeName}</span>
              </Flex>
            ),
          },
          {
            key: 'code',
            label: text.code,
            children: `${all.code ?? '—'} · ${text.versionLabel(all.version ?? 0)}`,
          },
          {
            key: 'effective',
            label: text.labelEffective,
            children: (
              <span>
                <DateTime value={all.effectiveFrom} /> {text.from} ·{' '}
                {all.effectiveTo === undefined || all.effectiveTo === '' ? (
                  text.openEnded
                ) : (
                  <>
                    {text.until} <DateTime value={all.effectiveTo} />
                  </>
                )}
              </span>
            ),
          },
          {
            key: 'replacement',
            label: text.replacement,
            span: 'filled',
            children:
              replaced.length > 1
                ? text.replacementMany
                : replaced[0] === undefined
                  ? text.replacementWillNone
                  : text.replacementWill(packageName(replaced[0])),
          },
          {
            key: 'evidence',
            label: text.evidence,
            span: 'filled',
            children: all.evidenceReference,
          },
          { key: 'rationale', label: text.rationale, span: 'filled', children: all.rationale },
          { key: 'impact', label: text.impact, span: 'filled', children: all.impact },
          {
            key: 'differences',
            label: text.differences,
            span: 'filled',
            children: all.differences,
          },
        ]}
      />
      <Flex vertical gap={8}>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {text.reviewValues}
        </Typography.Title>
        <Table
          size="small"
          rowKey={([code]) => code}
          pagination={false}
          scroll={{ x: 'max-content' }}
          dataSource={entries}
          columns={[
            {
              key: 'category',
              title: text.reviewColumnCategory,
              render: (_, [code, fields]) => (
                <Flex gap={6} align="center" wrap>
                  <Typography.Text>{codeText('calibrationCategory', code)}</Typography.Text>
                  {all.purposeCode !== undefined && isExample(code, all.purposeCode, fields) && (
                    <Tag color="gold" style={{ marginInlineEnd: 0 }}>
                      {text.valueExample}
                    </Tag>
                  )}
                </Flex>
              ),
            },
            {
              key: 'value',
              title: text.reviewColumnValue,
              render: (_, [code, fields]) => (
                <Typography.Text code style={{ fontSize: 12, wordBreak: 'break-all' }}>
                  {shortValue(fields, code)}
                </Typography.Text>
              ),
            },
            {
              key: 'unit',
              title: text.reviewColumnUnit,
              render: (_, [, fields]) => fields?.unitCode ?? '—',
            },
            {
              key: 'window',
              title: text.reviewColumnWindow,
              render: (_, [, fields]) =>
                fields?.windowDays === undefined ? '—' : text.valueWindowDays(fields.windowDays),
            },
          ]}
        />
      </Flex>
      <Flex vertical gap={8}>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {text.reviewFindings}
        </Typography.Title>
        {errors.length > 0 && (
          <Alert
            type="error"
            showIcon
            title={text.reviewErrors}
            description={
              <ul style={{ margin: 0, paddingInlineStart: 18 }}>
                {errors.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            }
          />
        )}
        {warnings.length > 0 && (
          <Alert
            type="warning"
            showIcon
            title={text.reviewWarnings}
            description={
              <ul style={{ margin: 0, paddingInlineStart: 18 }}>
                {warnings.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            }
          />
        )}
        {errors.length === 0 && warnings.length === 0 && (
          <Alert type="success" showIcon title={text.reviewClean} />
        )}
        <Form.Item
          name="combination"
          noStyle
          rules={[
            ({ getFieldsValue }) => ({
              validator: (): Promise<void> => {
                const values = getFieldsValue(true) as DraftValues;
                const problems = draftFindings(values, overview, catalogue).filter(
                  (item) => item.level === 'error',
                );
                return settle(problems.length === 0 ? undefined : text.reviewErrors);
              },
            }),
          ]}
        >
          <Check />
        </Form.Item>
        {warnings.length > 0 && (
          <Form.Item
            name="acknowledge"
            valuePropName="checked"
            rules={[
              {
                validator: (_: unknown, value: boolean | undefined): Promise<void> =>
                  settle(value === true ? undefined : text.reviewAcknowledgeRequired),
              },
            ]}
          >
            <Checkbox>{text.reviewAcknowledge}</Checkbox>
          </Form.Item>
        )}
      </Flex>
      <Alert type="info" showIcon title={text.reviewImmutable} description={text.reviewNextSteps} />
      {holdsAccept(overview, all.scopeKind, all.storeId) && (
        <Alert type="warning" showIcon title={text.ownerDraftWarning} />
      )}
    </Flex>
  );
}

function findingText(finding: Finding): string {
  return finding.category === undefined
    ? finding.text
    : `${codeText('calibrationCategory', finding.category)}：${finding.text}`;
}
