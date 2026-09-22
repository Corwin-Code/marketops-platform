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
import { DateTime, FormDrawer, InfoTip } from '../ui';
import type { FormDrawerStep, SubmitOutcome } from '../ui';
import type { Finding, ValueField, ValueFieldMap, ValueFields } from './calibrationValues';
import {
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
