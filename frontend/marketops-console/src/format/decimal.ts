/**
 * Decimal strings, handled as text.
 *
 * Money and ratios arrive from the backend as exact decimal strings. Turning one
 * into a JavaScript number would round it in binary floating point, and a figure
 * that is off in the last digit is a figure an operator can no longer reconcile
 * against the marketplace report. Every helper here therefore works on digits.
 */

const DECIMAL = /^[-+]?\d+(?:\.\d+)?$/;

/** Parts of a validated decimal string. */
interface DecimalParts {
  readonly negative: boolean;
  readonly integer: string;
  readonly fraction: string;
}

/** Whether a value is a plain decimal string such as `-1234.50`. */
export function isDecimal(s: string | null | undefined): s is string {
  return typeof s === 'string' && DECIMAL.test(s.trim());
}

function split(s: string): DecimalParts {
  const trimmed = s.trim();
  const negative = trimmed.startsWith('-');
  const unsigned = trimmed.replace(/^[-+]/, '');
  const [integerRaw = '0', fraction = ''] = unsigned.split('.');
  const integer = integerRaw.replace(/^0+(?=\d)/, '');
  return { negative, integer, fraction };
}

/** Whether every digit is zero, so a minus sign would only add noise. */
function isZero(parts: DecimalParts): boolean {
  return /^0*$/.test(parts.integer + parts.fraction);
}

/** Insert thousands separators into a run of integer digits: `1234567` → `1,234,567`. */
export function groupThousands(intPart: string): string {
  return intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** How many fraction digits a formatted decimal shows. */
export interface DecimalFormatOptions {
  /** Pad with zeros up to this many digits. */
  readonly minFractionDigits?: number;
  /**
   * Drop trailing zeros beyond this many digits. Non-zero digits beyond it are
   * kept: exactness wins over a tidy column.
   */
  readonly maxFractionDigits?: number;
}

/**
 * Format a decimal string with separators and a bounded fraction.
 *
 * Nothing is ever rounded. Only zeros are removed, so the shown figure always
 * equals the stored one. Invalid input renders as `—`.
 */
export function formatDecimal(
  s: string | null | undefined,
  { minFractionDigits = 0, maxFractionDigits }: DecimalFormatOptions = {},
): string {
  if (!isDecimal(s)) {
    return '—';
  }
  const parts = split(s);
  let fraction = parts.fraction;
  const keep = Math.max(maxFractionDigits ?? fraction.length, minFractionDigits);
  while (fraction.length > keep && fraction.endsWith('0')) {
    fraction = fraction.slice(0, -1);
  }
  fraction = fraction.padEnd(minFractionDigits, '0');
  const sign = parts.negative && !isZero(parts) ? '-' : '';
  const body = groupThousands(parts.integer);
  return fraction === '' ? `${sign}${body}` : `${sign}${body}.${fraction}`;
}

/**
 * Move the decimal point: `shiftDecimal('0.1234', 2)` → `12.34`.
 *
 * Used to turn a ratio into a percentage without multiplying a float. Trailing
 * fraction zeros are dropped. Invalid input renders as `—`.
 */
export function shiftDecimal(s: string | null | undefined, places: number): string {
  if (!isDecimal(s) || !Number.isInteger(places)) {
    return '—';
  }
  const parts = split(s);
  let digits = parts.integer + parts.fraction;
  let point = parts.integer.length + places;
  if (point < 0) {
    digits = '0'.repeat(-point) + digits;
    point = 0;
  }
  if (point > digits.length) {
    digits = digits.padEnd(point, '0');
  }
  const integer = digits.slice(0, point).replace(/^0+(?=\d)/, '') || '0';
  const fraction = digits.slice(point).replace(/0+$/, '');
  const shifted: DecimalParts = { negative: parts.negative, integer, fraction };
  const sign = shifted.negative && !isZero(shifted) ? '-' : '';
  return fraction === '' ? `${sign}${integer}` : `${sign}${integer}.${fraction}`;
}
