import { describe, expect, it } from 'vitest';
import { formatAmount, formatFreshness, presentationOf } from '../state/confidence';
import type { ConfidenceState } from '../state/confidence';

/**
 * The rule that stops the console from dressing doubt as certainty.
 *
 * Asserted over the whole vocabulary rather than case by case, because the
 * guarantee is shaped like "nothing except this one state may look confirmed".
 * A test that checked the states it happened to think of would keep passing on
 * the day somebody adds a seventh.
 */
describe('TC-UI-001 only a confirmed value may look confirmed', () => {
  const everyState: readonly ConfidenceState[] = [
    'CANONICAL_CONFIRMED',
    'CANONICAL_PENDING_SETTLEMENT',
    'ESTIMATED_EXPLAINED',
    'STALE',
    'INCOMPLETE',
    'CONFLICTED',
    'UNKNOWN',
  ];

  it('marks exactly one state as confirmed', () => {
    const confirmed = everyState.filter(
      (state) => presentationOf('AVAILABLE', state).tone === 'confirmed',
    );

    expect(confirmed).toEqual(['CANONICAL_CONFIRMED']);
  });

  it('permits a platform write from exactly one state', () => {
    const sufficient = everyState.filter(
      (state) => presentationOf('AVAILABLE', state).sufficientForWrite,
    );

    expect(sufficient).toEqual(['CANONICAL_CONFIRMED']);
  });

  it('gives every qualified state its own words rather than a shared label', () => {
    const labels = everyState.map((state) => presentationOf('AVAILABLE', state).label);

    expect(new Set(labels).size).toBe(everyState.length);
  });

  it('treats an unavailable value as absent whatever its confidence says', () => {
    for (const state of everyState) {
      expect(presentationOf('NOT_AVAILABLE', state).tone).toBe('absent');
      expect(presentationOf('UNDEFINED', state).tone).toBe('absent');
    }
  });

  it('treats a missing confidence as unknown rather than as fine', () => {
    expect(presentationOf('AVAILABLE', undefined).tone).toBe('qualified');
    expect(presentationOf('AVAILABLE', undefined).sufficientForWrite).toBe(false);
  });
});

describe('TC-UI-002 an absence is never rendered as a zero', () => {
  it('renders a missing amount as a dash', () => {
    expect(formatAmount(null, 'RUB')).toBe('—');
    expect(formatAmount(undefined, 'RUB')).toBe('—');
    expect(formatAmount('', 'RUB')).toBe('—');
  });

  it('renders a zero as a zero', () => {
    expect(formatAmount('0.0000', 'RUB')).toBe('0.0000 RUB');
  });

  it('keeps the amount exactly as the platform sent it', () => {
    expect(formatAmount('1234.5678', 'RUB')).toBe('1234.5678 RUB');
  });

  it('says the age is unknown rather than implying freshness', () => {
    expect(formatFreshness(null)).toBe('age unknown');
    expect(formatFreshness(undefined)).toBe('age unknown');
  });

  it('describes age in units an operator reads', () => {
    expect(formatFreshness(30)).toBe('seconds old');
    expect(formatFreshness(600)).toBe('10 minutes old');
    expect(formatFreshness(7_200)).toBe('2 hours old');
    expect(formatFreshness(864_000)).toBe('10 days old');
  });
});
