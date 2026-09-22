import { formatDecimal, isDecimal, shiftDecimal } from './decimal';

/** Symbols for the currencies this console routinely shows. */
const CURRENCY_SYMBOLS: Readonly<Record<string, string>> = {
  RUB: '₽',
  CNY: '¥',
  USD: '$',
  EUR: '€',
};

/**
 * Render a monetary amount such as `₽12,345.67`.
 *
 * The amount stays a string throughout, and only trailing zeros beyond two
 * digits are dropped: `12.345` stays `12.345` rather than being rounded,
 * because a displayed figure that differs from the stored one cannot be
 * reconciled. A missing or malformed amount renders as `—`, never as zero.
 */
export function formatMoney(value: string | null | undefined, currency?: string | null): string {
  if (!isDecimal(value)) {
    return '—';
  }
  const formatted = formatDecimal(value, { minFractionDigits: 2, maxFractionDigits: 2 });
  const negative = formatted.startsWith('-');
  const magnitude = negative ? formatted.slice(1) : formatted;
  const code = currency?.trim().toUpperCase() ?? '';
  const prefix = code === '' ? '' : (CURRENCY_SYMBOLS[code] ?? `${code} `);
  return `${negative ? '-' : ''}${prefix}${magnitude}`;
}

/** Render a ratio string as a percentage: `0.1234` → `12.34%`. Invalid → `—`. */
export function formatPercent(ratio: string | null | undefined): string {
  if (!isDecimal(ratio)) {
    return '—';
  }
  return `${formatDecimal(shiftDecimal(ratio, 2), { maxFractionDigits: 2 })}%`;
}
