import { Space, Typography } from 'antd';
import type { HTMLAttributes } from 'react';
import { formatDecimal } from '../format';
import { CodeTag } from '../ui/CodeTag';
import type { TagColor } from '../ui/CodeTag';
import { Money } from '../ui/Money';
import { REASON_LABELS } from '../i18n/zh/advertising';
import { absentMeasure } from './evidencePresentation';

/**
 * Small presentation helpers shared by the advertising screens.
 *
 * They stay in this folder because they only make sense for the advertising
 * payloads: several of them read loosely typed server records whose fields the
 * console must not reinterpret.
 */

/** A loosely typed server record. */
export type AdRow = Readonly<Record<string, unknown>>;

/** A string field of a server record, or `undefined`. */
export function field(row: AdRow | undefined, key: string): string | undefined {
  const value = row?.[key];
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

/** A string or number field as text, or `undefined`. */
export function fieldText(row: AdRow | undefined, key: string): string | undefined {
  const value = row?.[key];
  if (typeof value === 'string' && value.length > 0) return value;
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  return undefined;
}

/**
 * A decimal field as exact text, or `undefined`.
 *
 * Money arrives here as a JSON number or a string; either is written out in
 * plain digits and never used for arithmetic.
 */
export function decimalField(row: AdRow | undefined, key: string): string | undefined {
  const value = row?.[key];
  if (typeof value === 'number') {
    return plainDecimal(value);
  }
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : undefined;
}

/** A number the API already parsed, written out in plain digits for display only. */
export function plainDecimal(value: number | undefined): string | undefined {
  return value !== undefined && Number.isFinite(value)
    ? value.toLocaleString('en-US', { useGrouping: false, maximumFractionDigits: 20 })
    : undefined;
}

/** A list of strings from a server record field. */
export function stringList(row: AdRow | undefined, key: string): readonly string[] {
  const value = row?.[key];
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
}

/** `data-*` hooks for an antd row or cell, which types them as plain attributes. */
export function dataAttributes(
  values: Readonly<Record<string, string | number | boolean | undefined>>,
): HTMLAttributes<HTMLElement> {
  const attributes: Record<string, string> = {};
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined) attributes[key] = String(value);
  }
  return attributes;
}

/** A raw identifier, shown small and copyable, never as the primary label. */
export function IdText({
  value,
  prefix,
}: {
  readonly value: string | undefined;
  readonly prefix?: string;
}): React.JSX.Element {
  if (value === undefined || value === '') {
    return (
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        —
      </Typography.Text>
    );
  }
  return (
    <Typography.Text
      type="secondary"
      style={{ fontSize: 12, wordBreak: 'break-all' }}
      copyable={{ text: value }}
    >
      {prefix === undefined ? null : `${prefix} `}
      <Typography.Text type="secondary" code style={{ fontSize: 12 }}>
        {value.length > 12 ? `${value.slice(0, 8)}…` : value}
      </Typography.Text>
    </Typography.Text>
  );
}

/** Blocker, gap or reason codes, each as a Chinese tag. */
export function ReasonTags({
  codes,
  color = 'error',
  empty,
}: {
  readonly codes: readonly string[];
  readonly color?: TagColor;
  /** What to show when there is none; nothing by default. */
  readonly empty?: React.ReactNode;
}): React.JSX.Element | null {
  if (codes.length === 0) {
    return empty === undefined ? null : <>{empty}</>;
  }
  const colors: Record<string, TagColor> = {};
  for (const code of codes) colors[code] = color;
  return (
    <Space size={[4, 4]} wrap>
      {codes.map((code) => (
        <span key={code} data-blocker={code}>
          <CodeTag labels={REASON_LABELS} code={code} colors={colors} />
        </span>
      ))}
    </Space>
  );
}

/** Secondary text for a measure that has no number. */
export function AbsentValue({ label }: { readonly label: string }): React.JSX.Element {
  return (
    <Typography.Text type="secondary" data-state="absent">
      {label}
    </Typography.Text>
  );
}

/**
 * A measure with its value state: an amount, a plain decimal, or the honest
 * reason there is none.
 */
export function MeasureValue({
  state,
  value,
  currency,
  kind = 'decimal',
}: {
  readonly state: string;
  readonly value: string | undefined;
  readonly currency?: string | undefined;
  readonly kind?: 'money' | 'decimal' | 'count';
}): React.JSX.Element {
  const absent = absentMeasure(state, value);
  if (absent !== undefined) {
    return <AbsentValue label={absent} />;
  }
  if (kind === 'money') {
    return <Money value={value} currency={currency ?? null} />;
  }
  return (
    <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>
      {formatDecimal(value)}
    </Typography.Text>
  );
}

/** A plain decimal or count, or a secondary label when absent. */
export function DecimalValue({
  value,
  absent = '未测量',
}: {
  readonly value: string | number | undefined;
  readonly absent?: string;
}): React.JSX.Element {
  if (value === undefined) return <AbsentValue label={absent} />;
  const text = typeof value === 'number' ? String(value) : formatDecimal(value);
  return <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }}>{text}</Typography.Text>;
}
