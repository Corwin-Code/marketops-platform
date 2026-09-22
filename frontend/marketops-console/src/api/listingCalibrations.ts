import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/**
 * Calibration packages: the list with each package's stage and next governed
 * step, the category catalogue, one package in full, and the four writes
 * (draft, validate, accept, activate).
 *
 * Every decimal stays a string, and a JSON category value stays the JSON the
 * database holds. The next step, its digest and its blockers are computed by
 * the backend for the person asking; the page never decides who may act.
 */

const CALIBRATIONS = '/api/v1/console/listing/calibrations';

export type CalibrationPurpose =
  'LISTING_CONVERSION' | 'DESCRIPTION_CORRECTION' | 'BOUNDED_EXPLORATION' | 'PROMOTION';
export type CalibrationScopeKind = 'ORGANIZATION' | 'PLATFORM' | 'STORE';
export type CalibrationStage =
  'DRAFTED' | 'VALIDATED' | 'ACCEPTED' | 'ACTIVE' | 'ENDED' | 'RETIRED';
export type CalibrationStepCode = 'VALIDATE' | 'ACCEPT' | 'ACTIVATE';
export type CalibrationValueShape = 'NUMERIC' | 'TEXT' | 'JSON';

export const CALIBRATION_PURPOSES: readonly CalibrationPurpose[] = [
  'LISTING_CONVERSION',
  'DESCRIPTION_CORRECTION',
  'BOUNDED_EXPLORATION',
  'PROMOTION',
];
export const CALIBRATION_SCOPES: readonly CalibrationScopeKind[] = [
  'STORE',
  'PLATFORM',
  'ORGANIZATION',
];
export const CALIBRATION_STAGES: readonly CalibrationStage[] = [
  'DRAFTED',
  'VALIDATED',
  'ACCEPTED',
  'ACTIVE',
  'ENDED',
  'RETIRED',
];
const STEPS: readonly string[] = ['VALIDATE', 'ACCEPT', 'ACTIVATE'];
const SHAPES: readonly string[] = ['NUMERIC', 'TEXT', 'JSON'];

/** Whether the caller holds the prepare, validate and accept grants on one scope. */
export interface CalibrationRights {
  readonly prepare: boolean;
  readonly validate: boolean;
  readonly accept: boolean;
}

/** The person asking; the identifier only marks "you" on the page. */
export interface CalibrationViewer {
  readonly userId: string;
  readonly displayName: string;
  readonly stepUpSatisfied: boolean;
  readonly stepUpValidUntil: string | null;
}

/** A store a store-scoped package may name. */
export interface CalibrationStore {
  readonly storeId: string;
  readonly code: string;
  readonly displayName: string;
  readonly platformCode: string | null;
  readonly currencyCode: string | null;
  readonly rights: CalibrationRights;
}

/** Who took one lifecycle step. */
export interface CalibrationActor {
  readonly userId: string;
  readonly displayName: string | null;
  readonly at: string | null;
  readonly reference: string | null;
  readonly digest: string | null;
}

/** The step a package waits for, with the digest it must carry and what stops it. */
export interface CalibrationNextStep {
  readonly step: CalibrationStepCode;
  readonly digest: string;
  readonly blockers: readonly string[];
  readonly stepUpRequired: boolean;
}

/** One package in the list. */
export interface CalibrationPackage {
  readonly id: string;
  readonly code: string;
  readonly version: number;
  readonly purposeCode: CalibrationPurpose;
  readonly scopeKind: CalibrationScopeKind;
  readonly platformCode: string | null;
  readonly storeId: string | null;
  readonly storeName: string | null;
  readonly storePlatformCode: string | null;
  readonly status: string;
  readonly stage: CalibrationStage;
  readonly effectiveFrom: string;
  readonly effectiveTo: string | null;
  readonly replacesPackageId: string | null;
  readonly replacedByPackageId: string | null;
  readonly latestVersionOfCode: number;
  readonly superseded: boolean;
  readonly expired: boolean;
  readonly drafted: CalibrationActor | null;
  readonly validated: CalibrationActor | null;
  readonly accepted: CalibrationActor | null;
  readonly activated: CalibrationActor | null;
  readonly retired: CalibrationActor | null;
  readonly missingCategories: readonly string[];
  /** Evaluated for drafts only; null otherwise. */
  readonly combinationFailures: readonly string[] | null;
  readonly digestIntact: boolean;
  readonly activeOverlapIds: readonly string[];
  readonly rights: CalibrationRights;
  readonly nextStep: CalibrationNextStep | null;
}

/** Everything the list page shows. */
export interface CalibrationOverview {
  readonly asOf: string;
  readonly viewer: CalibrationViewer;
  readonly organizationRights: CalibrationRights;
  readonly stores: readonly CalibrationStore[];
  readonly platforms: readonly string[];
  readonly packages: readonly CalibrationPackage[];
}

export interface CalibrationCategory {
  readonly code: string;
  readonly displayName: string;
  readonly valueShape: CalibrationValueShape;
  readonly ordinal: number;
}

/** The closed category list and what each purpose requires. */
export interface CalibrationCatalogue {
  readonly categories: readonly CalibrationCategory[];
  readonly requirements: Readonly<Partial<Record<CalibrationPurpose, readonly string[]>>>;
}

/** One stored value; `json` is the stored JSON document, or null. */
export interface CalibrationValue {
  readonly categoryCode: string;
  readonly valueShape: CalibrationValueShape | null;
  readonly numeric: string | null;
  readonly text: string | null;
  readonly json: unknown;
  readonly unitCode: string;
  readonly windowDays: number | null;
  readonly scopeNote: string;
  readonly evidenceReference: string;
}

export interface CalibrationEvent {
  readonly id: string;
  readonly kind: string;
  readonly actorUserId: string;
  readonly actorName: string | null;
  readonly occurredAt: string;
  readonly packageDigest: string;
  readonly evidenceReference: string;
}

/** One package in full. */
export interface CalibrationPackageDetail {
  readonly summary: CalibrationPackage;
  readonly evidenceReference: string;
  readonly rationale: string;
  readonly impact: string;
  readonly differences: string;
  readonly currentDigest: string | null;
  readonly requiredCategories: readonly string[];
  readonly values: readonly CalibrationValue[];
  readonly events: readonly CalibrationEvent[];
  readonly eligibleAcceptors: readonly string[];
}

/** One value of a new draft; exactly one of numeric, text and json (profit bound: numeric plus json). */
export interface CalibrationDraftValue {
  readonly categoryCode: string;
  readonly unitCode: string;
  readonly scopeNote: string;
  readonly evidenceReference: string;
  /** A decimal as text, so nothing is rounded on the way. */
  readonly numeric?: string;
  readonly text?: string;
  readonly json?: unknown;
  readonly windowDays?: number;
}

/** A new draft as the professional enters it. */
export interface CalibrationDraft {
  readonly code: string;
  readonly version: number;
  readonly purposeCode: CalibrationPurpose;
  readonly scopeKind: CalibrationScopeKind;
  readonly platformCode?: string;
  readonly storeId?: string;
  readonly effectiveFrom: string;
  readonly effectiveTo?: string;
  readonly replacesPackageId?: string;
  readonly evidenceReference: string;
  readonly rationale: string;
  readonly impact: string;
  readonly differences: string;
  readonly values: readonly CalibrationDraftValue[];
}

/** One governed decision: the exact digest it confirms and its evidence. */
export interface CalibrationDecision {
  readonly digest: string;
  readonly evidenceReference: string;
}

type Row = Record<string, unknown>;

function row(body: unknown): Row | undefined {
  return typeof body === 'object' && body !== null && !Array.isArray(body)
    ? (body as Row)
    : undefined;
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function optionalText(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

/** A decimal kept as text; a JSON number is accepted but never produced by the backend. */
function decimal(value: unknown): string | null {
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  return typeof value === 'string' ? value : null;
}

function integer(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) ? value : undefined;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T | undefined {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value)
    ? (value as T)
    : undefined;
}

function list<T>(
  value: unknown,
  parse: (body: unknown) => T | undefined,
): readonly T[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const out: T[] = [];
  for (const item of value) {
    const parsed = parse(item);
    if (parsed === undefined) return undefined;
    out.push(parsed);
  }
  return out;
}

/** A value that may be null: null stays null, anything else must parse. */
function nullable<T>(
  value: unknown,
  parse: (body: unknown) => T | undefined,
): T | null | undefined {
  return value === null || value === undefined ? null : parse(value);
}

function parseRights(body: unknown): CalibrationRights | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  return { prepare: r.prepare === true, validate: r.validate === true, accept: r.accept === true };
}

function parseViewer(body: unknown): CalibrationViewer | undefined {
  const r = row(body);
  const userId = text(r?.userId);
  if (r === undefined || userId === undefined) return undefined;
  return {
    userId,
    displayName: text(r.displayName) ?? '',
    stepUpSatisfied: r.stepUpSatisfied === true,
    stepUpValidUntil: optionalText(r.stepUpValidUntil),
  };
}

function parseStore(body: unknown): CalibrationStore | undefined {
  const r = row(body);
  const storeId = text(r?.storeId);
  const code = text(r?.code);
  const rights = parseRights(r?.rights);
  if (r === undefined || storeId === undefined || code === undefined || rights === undefined) {
    return undefined;
  }
  return {
    storeId,
    code,
    displayName: text(r.displayName) ?? code,
    platformCode: optionalText(r.platformCode),
    currencyCode: optionalText(r.currencyCode),
    rights,
  };
}

function parseActor(body: unknown): CalibrationActor | undefined {
  const r = row(body);
  const userId = text(r?.userId);
  if (r === undefined || userId === undefined) return undefined;
  return {
    userId,
    displayName: optionalText(r.displayName),
    at: optionalText(r.at),
    reference: optionalText(r.reference),
    digest: optionalText(r.digest),
  };
}

function parseNextStep(body: unknown): CalibrationNextStep | undefined {
  const r = row(body);
  const step = oneOf(r?.step, STEPS) as CalibrationStepCode | undefined;
  const digest = text(r?.digest);
  const blockers = list(r?.blockers, text);
  if (r === undefined || step === undefined || digest === undefined || blockers === undefined) {
    return undefined;
  }
  return { step, digest, blockers, stepUpRequired: r.stepUpRequired === true };
}

function parsePackage(body: unknown): CalibrationPackage | undefined {
  const r = row(body);
  if (r === undefined) return undefined;
  const id = text(r.id);
  const code = text(r.code);
  const version = integer(r.version);
  const purposeCode = oneOf(r.purposeCode, CALIBRATION_PURPOSES);
  const scopeKind = oneOf(r.scopeKind, CALIBRATION_SCOPES);
  const status = text(r.status);
  const stage = oneOf(r.stage, CALIBRATION_STAGES);
  const effectiveFrom = text(r.effectiveFrom);
  const rights = parseRights(r.rights);
  const missingCategories = list(r.missingCategories, text);
  const combinationFailures = nullable(r.combinationFailures, (value) => list(value, text));
  const activeOverlapIds = list(r.activeOverlapIds, text);
  const drafted = nullable(r.drafted, parseActor);
  const validated = nullable(r.validated, parseActor);
  const accepted = nullable(r.accepted, parseActor);
  const activated = nullable(r.activated, parseActor);
  const retired = nullable(r.retired, parseActor);
  const nextStep = nullable(r.nextStep, parseNextStep);
  if (
    id === undefined ||
    code === undefined ||
    version === undefined ||
    purposeCode === undefined ||
    scopeKind === undefined ||
    status === undefined ||
    stage === undefined ||
    effectiveFrom === undefined ||
    rights === undefined ||
    missingCategories === undefined ||
    combinationFailures === undefined ||
    activeOverlapIds === undefined ||
    drafted === undefined ||
    validated === undefined ||
    accepted === undefined ||
    activated === undefined ||
    retired === undefined ||
    nextStep === undefined
  ) {
    return undefined;
  }
  return {
    id,
    code,
    version,
    purposeCode,
    scopeKind,
    platformCode: optionalText(r.platformCode),
    storeId: optionalText(r.storeId),
    storeName: optionalText(r.storeName),
    storePlatformCode: optionalText(r.storePlatformCode),
    status,
    stage,
    effectiveFrom,
    effectiveTo: optionalText(r.effectiveTo),
    replacesPackageId: optionalText(r.replacesPackageId),
    replacedByPackageId: optionalText(r.replacedByPackageId),
    latestVersionOfCode: integer(r.latestVersionOfCode) ?? version,
    superseded: r.superseded === true,
    expired: r.expired === true,
    drafted,
    validated,
    accepted,
    activated,
    retired,
    missingCategories,
    combinationFailures,
    digestIntact: r.digestIntact === true,
    activeOverlapIds,
    rights,
    nextStep,
  };
}

function parseOverview(body: unknown): CalibrationOverview | undefined {
  const r = row(body);
  const asOf = text(r?.asOf);
  const viewer = parseViewer(r?.viewer);
  const organizationRights = parseRights(r?.organizationRights);
  const stores = list(r?.stores, parseStore);
  const platforms = list(r?.platforms, text);
  const packages = list(r?.packages, parsePackage);
  if (
    asOf === undefined ||
    viewer === undefined ||
    organizationRights === undefined ||
    stores === undefined ||
    platforms === undefined ||
    packages === undefined
  ) {
    return undefined;
  }
  return { asOf, viewer, organizationRights, stores, platforms, packages };
}

function parseCategory(body: unknown): CalibrationCategory | undefined {
  const r = row(body);
  const code = text(r?.code);
  const valueShape = oneOf(r?.valueShape, SHAPES) as CalibrationValueShape | undefined;
  if (r === undefined || code === undefined || valueShape === undefined) return undefined;
  return {
    code,
    displayName: text(r.displayName) ?? code,
    valueShape,
    ordinal: integer(r.ordinal) ?? 0,
  };
}

function parseCatalogue(body: unknown): CalibrationCatalogue | undefined {
  const r = row(body);
  const categories = list(r?.categories, parseCategory);
  const purposes = list(r?.purposes, (item) => {
    const p = row(item);
    const purposeCode = oneOf(p?.purposeCode, CALIBRATION_PURPOSES);
    const required = list(p?.requiredCategories, text);
    return purposeCode === undefined || required === undefined
      ? undefined
      : ([purposeCode, required] as const);
  });
  if (categories === undefined || purposes === undefined) return undefined;
  return {
    categories: [...categories].sort((a, b) => a.ordinal - b.ordinal),
    requirements: Object.fromEntries(purposes),
  };
}

function parseValue(body: unknown): CalibrationValue | undefined {
  const r = row(body);
  const categoryCode = text(r?.categoryCode);
  if (r === undefined || categoryCode === undefined) return undefined;
  return {
    categoryCode,
    valueShape: (oneOf(r.valueShape, SHAPES) as CalibrationValueShape | undefined) ?? null,
    numeric: decimal(r.numeric),
    text: optionalText(r.text),
    json: r.json ?? null,
    unitCode: text(r.unitCode) ?? '',
    windowDays: integer(r.windowDays) ?? null,
    scopeNote: text(r.scopeNote) ?? '',
    evidenceReference: text(r.evidenceReference) ?? '',
  };
}

function parseEvent(body: unknown): CalibrationEvent | undefined {
  const r = row(body);
  const id = text(r?.id);
  const kind = text(r?.kind);
  const occurredAt = text(r?.occurredAt);
  if (r === undefined || id === undefined || kind === undefined || occurredAt === undefined) {
    return undefined;
  }
  return {
    id,
    kind,
    actorUserId: text(r.actorUserId) ?? '',
    actorName: optionalText(r.actorName),
    occurredAt,
    packageDigest: text(r.packageDigest) ?? '',
    evidenceReference: text(r.evidenceReference) ?? '',
  };
}

function parseDetail(body: unknown): CalibrationPackageDetail | undefined {
  const r = row(body);
  const summary = parsePackage(r?.summary);
  const requiredCategories = list(r?.requiredCategories, text);
  const values = list(r?.values, parseValue);
  const events = list(r?.events, parseEvent);
  const eligibleAcceptors = list(r?.eligibleAcceptors, text);
  if (
    r === undefined ||
    summary === undefined ||
    requiredCategories === undefined ||
    values === undefined ||
    events === undefined ||
    eligibleAcceptors === undefined
  ) {
    return undefined;
  }
  return {
    summary,
    evidenceReference: text(r.evidenceReference) ?? '',
    rationale: text(r.rationale) ?? '',
    impact: text(r.impact) ?? '',
    differences: text(r.differences) ?? '',
    currentDigest: optionalText(r.currentDigest),
    requiredCategories,
    values,
    events,
    eligibleAcceptors,
  };
}

function post(body: unknown): RequestInit {
  return { method: 'POST', body: JSON.stringify(body) };
}

/** The package status the write left behind, from the raw view it answers with. */
function writtenStatus(body: unknown): string | undefined {
  return text(row(row(body)?.package)?.status);
}

export function fetchCalibrations(
  context: ConsoleRequest,
): Promise<ConsoleOutcome<CalibrationOverview>> {
  return request(context, CALIBRATIONS, parseOverview);
}

export function fetchCalibrationCatalogue(
  context: ConsoleRequest,
): Promise<ConsoleOutcome<CalibrationCatalogue>> {
  return request(context, `${CALIBRATIONS}/catalogue`, parseCatalogue);
}

export function fetchCalibrationDetail(
  context: ConsoleRequest,
  packageId: string,
): Promise<ConsoleOutcome<CalibrationPackageDetail>> {
  return request(context, `${CALIBRATIONS}/${encodeURIComponent(packageId)}/detail`, parseDetail);
}

/** Create a draft; answers the new package's identifier. */
export function prepareCalibration(
  context: ConsoleRequest,
  draft: CalibrationDraft,
): Promise<ConsoleOutcome<string>> {
  return request(context, CALIBRATIONS, (body) => text(row(row(body)?.package)?.id), post(draft));
}

function decide(
  context: ConsoleRequest,
  packageId: string,
  step: 'validate' | 'accept' | 'activate',
  decision: CalibrationDecision,
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    `${CALIBRATIONS}/${encodeURIComponent(packageId)}/${step}`,
    writtenStatus,
    post(decision),
  );
}

export function validateCalibration(
  context: ConsoleRequest,
  packageId: string,
  decision: CalibrationDecision,
): Promise<ConsoleOutcome<string>> {
  return decide(context, packageId, 'validate', decision);
}

export function acceptCalibration(
  context: ConsoleRequest,
  packageId: string,
  decision: CalibrationDecision,
): Promise<ConsoleOutcome<string>> {
  return decide(context, packageId, 'accept', decision);
}

export function activateCalibration(
  context: ConsoleRequest,
  packageId: string,
  decision: CalibrationDecision,
): Promise<ConsoleOutcome<string>> {
  return decide(context, packageId, 'activate', decision);
}
