/**
 * A store's commercial inputs: the required profit and safety buffer per unit the price guardrail
 * adds to the unit cost. Amounts are decimal text in the store's currency.
 */

import type { ConsoleOutcome, ConsoleRequest } from './console';
import { request } from './console';

/** One version of an input. */
export interface CommercialInput {
  readonly inputId: string;
  /** REQUIRED_PROFIT_PER_UNIT or SAFETY_BUFFER_PER_UNIT. */
  readonly inputCode: string;
  /** STORE for the store's own, ORGANIZATION for the organization-wide one. */
  readonly scopeKind: string;
  readonly amount: string | null;
  readonly currencyCode: string | null;
  readonly effectiveFrom: string;
  readonly effectiveTo: string | null;
  /** ACTIVE, ENDED or CANCELLED. */
  readonly status: string;
  /** What the person who entered it said. */
  readonly note: string | null;
  readonly enteredByViewer: boolean;
}

/** A store's commercial inputs. */
export interface CommercialInputs {
  readonly storeId: string;
  readonly generatedAt: string;
  /** The store's currency, the one an entry is recorded in. */
  readonly currencyCode: string | null;
  /** The codes the store needs, in order. */
  readonly inputCodes: readonly string[];
  /** The version of each code in force now; a code without one is absent. */
  readonly inForce: Readonly<Record<string, CommercialInput>>;
  /** The versions that apply to the store, newest first. */
  readonly versions: readonly CommercialInput[];
}

/** Load the store's commercial inputs. */
export function fetchCommercialInputs(
  context: ConsoleRequest,
  storeId: string,
): Promise<ConsoleOutcome<CommercialInputs>> {
  return request(
    context,
    `/api/v1/console/intake/commercial-inputs?storeId=${encodeURIComponent(storeId)}`,
    parseCommercialInputs,
  );
}

/** Record one input for the store from now on; the answer is the new version's id. */
export function enterCommercialInput(
  context: ConsoleRequest,
  input: {
    readonly storeId: string;
    readonly inputCode: string;
    readonly amount: number;
    readonly reason: string;
  },
): Promise<ConsoleOutcome<string>> {
  return request(
    context,
    '/api/v1/console/intake/commercial-inputs',
    (body) => (isRecord(body) ? text(body.id) : undefined),
    { method: 'POST', body: JSON.stringify(input) },
  );
}

/** Validate an answer; anything that does not match the contract is `undefined`. */
export function parseCommercialInputs(body: unknown): CommercialInputs | undefined {
  if (
    !isRecord(body) ||
    !Array.isArray(body.inputCodes) ||
    !isRecord(body.inForce) ||
    !Array.isArray(body.versions)
  ) {
    return undefined;
  }
  const storeId = text(body.storeId);
  const generatedAt = text(body.generatedAt);
  const versions = body.versions.map(parseInput);
  const inForce: Record<string, CommercialInput> = {};
  for (const [code, value] of Object.entries(body.inForce)) {
    const parsed = parseInput(value);
    if (parsed === undefined) return undefined;
    inForce[code] = parsed;
  }
  if (
    storeId === undefined ||
    generatedAt === undefined ||
    versions.some((version) => version === undefined)
  ) {
    return undefined;
  }
  return {
    storeId,
    generatedAt,
    currencyCode: text(body.currencyCode) ?? null,
    inputCodes: body.inputCodes.filter((code): code is string => typeof code === 'string'),
    inForce,
    versions: versions.filter((version): version is CommercialInput => version !== undefined),
  };
}

function parseInput(value: unknown): CommercialInput | undefined {
  if (!isRecord(value)) return undefined;
  const inputId = text(value.inputId);
  const inputCode = text(value.inputCode);
  const scopeKind = text(value.scopeKind);
  const effectiveFrom = text(value.effectiveFrom);
  const status = text(value.status);
  if (
    inputId === undefined ||
    inputCode === undefined ||
    scopeKind === undefined ||
    effectiveFrom === undefined ||
    status === undefined ||
    typeof value.enteredByViewer !== 'boolean'
  ) {
    return undefined;
  }
  return {
    inputId,
    inputCode,
    scopeKind,
    amount: text(value.amount) ?? null,
    currencyCode: text(value.currencyCode) ?? null,
    effectiveFrom,
    effectiveTo: text(value.effectiveTo) ?? null,
    status,
    note: text(value.note) ?? null,
    enteredByViewer: value.enteredByViewer,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined;
}
