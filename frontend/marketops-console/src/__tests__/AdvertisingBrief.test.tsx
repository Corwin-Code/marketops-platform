import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AdvertisingBriefView } from '../advertising/AdvertisingBriefView';
import { parseAdvertisingBrief, parseAdvertisingBriefSection } from '../api/advertising';
import type { ConsoleRequest } from '../api/console';

/**
 * The published brief, as a reader meets it.
 *
 * What these cases are about is the difference between a report and the truth it
 * reports. A brief links to canonical rows, keeps every reading that was ever
 * published, and says plainly when a topic had no source — because a reader who
 * cannot tell "nothing happened" from "nothing was checked" cannot act on either.
 */

const PERIOD = '2026-09-04';

/** A brief whose gate section has no canonical source, as the backend sends it. */
const BRIEF = {
  id: '11111111-1111-4111-8111-111111111111',
  briefKind: 'DAILY_ACTION_BRIEF',
  periodKey: PERIOD,
  asOf: '2026-09-04T06:00:00Z',
  revisionNo: 1,
  revisionKind: 'ORIGINAL',
  supersedesPublicationId: null,
  adjustmentReason: null,
  lateFactReference: null,
  gapCodes: ['NO_CANONICAL_GATE_EVIDENCE_SOURCE'],
  contentDigest: 'a'.repeat(64),
  publishedAt: '2026-09-04T06:00:01Z',
  restatement: false,
  fullyCovered: false,
  sections: [
    {
      sectionCode: 'IMMEDIATE_PROTECTION_AND_REGRESSION',
      ordinal: 1,
      itemCount: 1,
      coverageState: 'COMPLETE',
      blockerCodes: [],
      summaryNote: null,
      complete: true,
      items: [
        {
          subjectKind: 'AD_CASE',
          referenceId: '22222222-2222-4222-8222-222222222222',
          lane: 'PROTECTION',
          causeCode: 'PROVEN_ADVERTISING_LOSS',
          valueState: 'AVAILABLE',
          numericValue: '-4200.0000',
          currencyCode: 'RUB',
          evidenceState: 'CANONICAL_CONFIRMED',
          blockerCodes: [],
          observedAt: '2026-09-04T05:00:00Z',
        },
      ],
    },
    {
      sectionCode: 'WATCH',
      ordinal: 2,
      itemCount: 0,
      coverageState: 'COMPLETE',
      blockerCodes: [],
      summaryNote: null,
      complete: true,
      items: [],
    },
    {
      sectionCode: 'GATE_EVIDENCE',
      ordinal: 3,
      itemCount: 0,
      coverageState: 'NOT_AVAILABLE',
      blockerCodes: ['NO_CANONICAL_GATE_EVIDENCE_SOURCE'],
      summaryNote: 'this product holds no canonical source for this topic',
      complete: false,
      items: [],
    },
  ],
};

const REVISION = {
  ...BRIEF,
  id: '33333333-3333-4333-8333-333333333333',
  revisionNo: 2,
  revisionKind: 'REVISION',
  supersedesPublicationId: BRIEF.id,
  adjustmentReason: 'the facts underneath the period were restated',
  lateFactReference: 'fact://late/ad-case',
  contentDigest: 'b'.repeat(64),
  restatement: true,
};

function routes(latest: unknown, history: unknown): typeof fetch {
  return vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const body = url.endsWith('/history') ? history : latest;
    return Promise.resolve(
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  }) as unknown as typeof fetch;
}

function context(fetchImpl: typeof fetch): ConsoleRequest {
  return { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'token', fetchImpl };
}

describe('the published brief', () => {
  it('TC-UI-BRIEF-001 shows every section, including the ones that found nothing', async () => {
    render(
      <AdvertisingBriefView
        context={context(routes(BRIEF, [BRIEF]))}
        briefKind="DAILY_ACTION_BRIEF"
        periodKey={PERIOD}
      />,
    );

    const brief = await screen.findByLabelText('Advertising brief');
    // A section that vanished when empty would make "we looked and there was
    // nothing" and "we never looked" the same page.
    expect(brief.querySelectorAll('[data-section]')).toHaveLength(3);
    expect(within(brief).getByLabelText('WATCH').textContent).toContain(
      'Nothing fell into this topic',
    );
  });

  it('TC-UI-BRIEF-002 says a topic had no source rather than showing it as empty', async () => {
    render(
      <AdvertisingBriefView
        context={context(routes(BRIEF, [BRIEF]))}
        briefKind="DAILY_ACTION_BRIEF"
        periodKey={PERIOD}
      />,
    );

    const gate = await screen.findByLabelText('GATE_EVIDENCE');
    expect(gate.getAttribute('data-coverage')).toBe('NOT_AVAILABLE');
    expect(gate.textContent).toContain('NO_CANONICAL_GATE_EVIDENCE_SOURCE');
    // Visibly different from the empty-but-covered section above it.
    expect(gate.textContent).toContain('No canonical source for this topic');
  });

  it('TC-UI-BRIEF-003 keeps the reading that was published on the day', async () => {
    render(
      <AdvertisingBriefView
        context={context(routes(REVISION, [BRIEF, REVISION]))}
        briefKind="DAILY_ACTION_BRIEF"
        periodKey={PERIOD}
      />,
    );

    const brief = await screen.findByLabelText('Advertising brief');
    // Somebody acted on the earlier reading. Replacing it silently would make
    // that decision impossible to understand afterwards.
    const earlier = within(brief).getByLabelText('Earlier readings');
    expect(within(earlier).getAllByRole('listitem')).toHaveLength(2);
    expect(brief.querySelector('[data-restatement="true"]')?.textContent).toContain(
      'fact://late/ad-case',
    );
  });

  it('TC-UI-BRIEF-004 an item shows its value state, never a bare number', async () => {
    render(
      <AdvertisingBriefView
        context={context(routes(BRIEF, [BRIEF]))}
        briefKind="DAILY_ACTION_BRIEF"
        periodKey={PERIOD}
      />,
    );

    const brief = await screen.findByLabelText('Advertising brief');
    const item = brief.querySelector('[data-subject="AD_CASE"]');
    expect(item?.getAttribute('data-value-state')).toBe('AVAILABLE');
    expect(item?.textContent).toContain('RUB');
  });

  it('TC-UI-BRIEF-005 a section claiming coverage while naming a blocker is not believed', () => {
    // Re-derived rather than trusted. A body that said COMPLETE and then listed
    // what stopped it would otherwise assert the coverage.
    const lying = { ...BRIEF.sections[2], coverageState: 'BLOCKED', complete: true };
    expect(parseAdvertisingBriefSection(lying)?.complete).toBe(false);
  });

  it('TC-UI-BRIEF-006 a reading that names no predecessor is not a restatement', () => {
    const claiming = { ...BRIEF, restatement: true, supersedesPublicationId: null };
    expect(parseAdvertisingBrief(claiming)?.restatement).toBe(false);
  });

  it('TC-UI-BRIEF-007 a body with no period or no fact cut is refused', () => {
    const withoutPeriod: Record<string, unknown> = { ...BRIEF };
    delete withoutPeriod.periodKey;
    const withoutCut: Record<string, unknown> = { ...BRIEF };
    delete withoutCut.asOf;
    expect(parseAdvertisingBrief(withoutPeriod)).toBeUndefined();
    expect(parseAdvertisingBrief(withoutCut)).toBeUndefined();
  });
});

describe('brief completeness cannot erase gaps', () => {
  it('an empty publication is never complete', () => {
    expect(
      parseAdvertisingBrief({
        id: 'test',
        briefKind: 'DAILY_ACTION_BRIEF',
        periodKey: '2026-09-04',
        asOf: '2026-09-04T00:00:00Z',
        revisionKind: 'ORIGINAL',
        gapCodes: [],
        sections: [],
      })?.fullyCovered,
    ).toBe(false);
  });
});
