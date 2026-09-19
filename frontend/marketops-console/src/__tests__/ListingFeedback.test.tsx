import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ListingFeedbackPanel } from '../listing/ListingFeedbackPanel';
import { LanguageProvider } from '../listing/i18n/language';
import { t } from '../listing/i18n/ui';
import { parseFeedbackDetail, parseFeedbackOverview } from '../api/listingConversion';

const original = {
  id: 'item-1',
  listingId: 'listing-1',
  sourceIdentity: 'identity-1',
  rawObservationId: 'raw-1',
  originalPointer: '/items/0/text',
  originalDigest: 'a'.repeat(64),
  observedAt: '2026-08-10T00:00:00Z',
  acquiredAt: '2026-08-11T00:00:00Z',
};
const first = {
  id: 'label-1',
  itemId: original.id,
  revision: 0,
  themeCode: 'ODOR',
  qualificationState: 'UNCERTAIN',
  classifierVersion: 'HUMAN_V1',
  reason: 'Initial uncertain classification',
  classifiedBy: 'actor-1',
  classifiedAt: '2026-08-12T00:00:00Z',
};
const corrected = {
  ...first,
  id: 'label-2',
  revision: 1,
  themeCode: 'NO_ODOR',
  qualificationState: 'CONFIRMED',
  reason: 'Negation corrected from source wording',
  classifiedAt: '2026-08-13T00:00:00Z',
};
const overview = {
  asOf: '2026-08-14T00:00:00Z',
  periodStart: '2026-08-01T00:00:00Z',
  periodEnd: '2026-08-14T00:00:00Z',
  itemLimit: 50,
  themes: [{ themeCode: 'ODOR', qualificationState: 'UNCERTAIN', mentionCount: 1 }],
  items: [original],
};

describe('source-linked feedback', () => {
  it('rejects cross-item histories and invalid counts rather than inventing an empty result', () => {
    expect(parseFeedbackOverview(overview)?.themes[0]?.mentionCount).toBe(1);
    expect(
      parseFeedbackOverview({ ...overview, themes: [{ ...overview.themes[0], mentionCount: -1 }] }),
    ).toBeUndefined();
    expect(
      parseFeedbackDetail({ original, classifications: [{ ...first, itemId: 'another-item' }] }),
    ).toBeUndefined();
    expect(
      parseFeedbackDetail({ original, classifications: [first, corrected] })?.original
        .originalDigest,
    ).toBe(original.originalDigest);
  });

  it.each(['zh', 'ru'] as const)(
    'queries on demand and appends a correction while retaining the original and history (%s)',
    async (language) => {
      let revised = false;
      const writes: unknown[] = [];
      const fetchImpl = vi
        .fn()
        .mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
          const url =
            typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
          let body: unknown;
          if (init?.method === 'POST') {
            if (typeof init.body === 'string') writes.push(JSON.parse(init.body) as unknown);
            revised = true;
            body = { classificationId: corrected.id };
          } else if (url.endsWith('/feedback/item-1')) {
            body = { original, classifications: revised ? [first, corrected] : [first] };
          } else {
            body = revised
              ? {
                  ...overview,
                  themes: [
                    { themeCode: 'NO_ODOR', qualificationState: 'CONFIRMED', mentionCount: 1 },
                  ],
                }
              : overview;
          }
          return Promise.resolve(
            new Response(JSON.stringify(body), {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            }),
          );
        }) as unknown as typeof fetch;
      const context = { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'synthetic', fetchImpl };
      render(
        <LanguageProvider initial={language}>
          <ListingFeedbackPanel context={context} listingId="listing-1" />
        </LanguageProvider>,
      );
      expect(fetchImpl).not.toHaveBeenCalled();
      fireEvent.change(screen.getByLabelText(t('feedbackFrom', language)), {
        target: { value: '2026-08-01T00:00' },
      });
      fireEvent.change(screen.getByLabelText(t('feedbackTo', language)), {
        target: { value: '2026-08-14T00:00' },
      });
      fireEvent.click(screen.getByRole('button', { name: t('feedbackLoad', language) }));
      fireEvent.click(await screen.findByRole('button', { name: original.id }));
      await screen.findByText(first.reason, { exact: false });
      fireEvent.change(screen.getByLabelText(t('feedbackTheme', language)), {
        target: { value: 'NO_ODOR' },
      });
      fireEvent.change(screen.getByLabelText(t('feedbackQualification', language)), {
        target: { value: 'CONFIRMED' },
      });
      fireEvent.change(screen.getByLabelText(t('feedbackReason', language)), {
        target: { value: corrected.reason },
      });
      fireEvent.click(screen.getByRole('button', { name: t('feedbackCorrect', language) }));
      await waitFor(() => {
        expect(
          within(screen.getByRole('list', { name: t('feedbackHistory', language) })).getByText(
            corrected.reason,
            { exact: false },
          ),
        ).toBeInTheDocument();
      });
      expect(
        within(screen.getByRole('list', { name: t('feedbackHistory', language) })).getByText(
          first.reason,
          { exact: false },
        ),
      ).toBeInTheDocument();
      expect(screen.getByText(original.originalDigest)).toBeInTheDocument();
      expect(writes).toEqual([
        {
          themeCode: 'NO_ODOR',
          qualificationState: 'CONFIRMED',
          classifierVersion: 'HUMAN_V1',
          reason: corrected.reason,
        },
      ]);
    },
  );
});
