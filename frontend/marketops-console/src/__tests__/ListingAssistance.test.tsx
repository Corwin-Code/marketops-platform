import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ListingAssistancePanel } from '../listing/ListingAssistancePanel';
import { LanguageProvider } from '../listing/i18n/language';
import { t } from '../listing/i18n/ui';

const LISTING = '51111111-1111-4111-8111-111111111111';
const METRIC = '52222222-2222-4222-8222-222222222222';
const explanation = {
  invocationId: '53333333-3333-4333-8333-333333333333',
  subjectId: LISTING,
  outputSchemaVersion: 2,
  state: 'SUCCEEDED',
  failureCode: null,
  degraded: false,
  claims: (['FACT', 'INFERENCE', 'RECOMMENDATION', 'UNKNOWN'] as const).map((kind, ordinal) => ({
    claimId: `claim-${String(ordinal)}`,
    kind,
    ordinal: ordinal + 1,
    statement: `${kind} synthetic statement`,
    confidenceLabel: null,
    metricValueRefs: kind === 'FACT' ? [METRIC] : [],
    findingRefs: [],
    payload: {},
    accepted: true,
    rejectionCode: null,
  })),
};

describe('bounded listing assistance', () => {
  it.each(['zh', 'ru'] as const)(
    'requests only the selected purpose and displays four separate advisory kinds (%s)',
    async (language) => {
      const calls: { url: string; method: string; body: unknown }[] = [];
      const fetchImpl = vi
        .fn()
        .mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
          const url =
            typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
          calls.push({
            url,
            method: init?.method ?? 'GET',
            body: typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined,
          });
          return Promise.resolve(
            new Response(JSON.stringify(explanation), {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            }),
          );
        }) as unknown as typeof fetch;
      const context = { apiBaseUrl: 'http://127.0.0.1:8080', accessToken: 'synthetic', fetchImpl };
      render(
        <LanguageProvider initial={language}>
          <ListingAssistancePanel context={context} listingId={LISTING} />
        </LanguageProvider>,
      );
      expect(fetchImpl).not.toHaveBeenCalled();
      for (const label of [
        'assistanceHypotheses',
        'assistanceDescription',
        'assistancePromotion',
        'assistanceReview',
      ] as const)
        expect(screen.getByRole('option', { name: t(label, language) })).toBeInTheDocument();
      fireEvent.change(screen.getByRole('combobox', { name: t('assistancePurpose', language) }), {
        target: { value: 'RUSSIAN_DESCRIPTION' },
      });
      fireEvent.click(screen.getByRole('button', { name: t('assistanceRequest', language) }));
      await screen.findByText('RECOMMENDATION synthetic statement');
      for (const label of [
        'assistanceFact',
        'assistanceInference',
        'assistanceRecommendation',
        'assistanceUnknown',
      ] as const)
        expect(screen.getByRole('region', { name: t(label, language) })).toBeInTheDocument();
      expect(calls).toHaveLength(1);
      expect(calls[0]?.body).toEqual({ window: 'D14', purpose: 'RUSSIAN_DESCRIPTION' });
      expect(calls[0]?.url).toContain(`/listings/${LISTING}/assistance`);
      fireEvent.click(screen.getByRole('button', { name: t('assistanceRead', language) }));
      await screen.findByText('RECOMMENDATION synthetic statement');
      expect(calls[1]?.method).toBe('GET');
      expect(calls[1]?.url).toContain(explanation.invocationId);
      expect(calls.filter((call) => call.method === 'POST')).toHaveLength(1);
    },
  );
});
