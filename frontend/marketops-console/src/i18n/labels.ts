/** A map from a backend code to the Chinese text an operator reads. */
export type CodeLabels = Readonly<Record<string, string>>;

/**
 * Turn a backend code into something an operator can read.
 *
 * An unknown code is shown with its raw value and a marker instead of blank,
 * because a new backend state that silently rendered as nothing would be read
 * as "no state" — the opposite of what it is.
 */
export function codeLabel(labels: CodeLabels, code: string | null | undefined): string {
  if (code === null || code === undefined || code === '') {
    return '—';
  }
  return Object.hasOwn(labels, code) ? (labels[code] ?? code) : `${code}（未识别）`;
}
