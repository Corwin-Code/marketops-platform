/**
 * How the console compares a title or description with another (W2): the same rule the platform
 * reads a card back with. Markup line breaks and runs of whitespace are layout, not a different
 * text; every other difference counts.
 */
export function normalizedContent(text: string): string {
  return text
    .replace(/<br\s*\/?>/giu, ' ')
    .replace(/\s+/gu, ' ')
    .trim();
}

/** Whether two texts are the same text in the sense above. */
export function sameContent(left: string, right: string): boolean {
  return normalizedContent(left) === normalizedContent(right);
}

/** Characters as the platform's length checks count them (UTF-16 units; Cyrillic is one each). */
export function characterCount(text: string): number {
  return text.length;
}
