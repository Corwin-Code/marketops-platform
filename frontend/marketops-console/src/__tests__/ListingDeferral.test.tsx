import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ConsoleRequest } from '../api/console';
import { parseListingTaskDeferral } from '../api/listingConversion';
import { ListingDeferral } from '../listing/ListingDeferral';
import { LanguageProvider } from '../listing/i18n/language';
import { t } from '../listing/i18n/ui';

const deferred = {
  id: 'deferral-one',
  minutes: 60,
  reason: 'Finite review reason',
  requestedAt: '2026-09-13T00:00:00Z',
  expiresAt: '2026-09-13T01:00:00Z',
  state: 'ACTIVE',
  reviewHealthId: null,
};
const response = () => new Response(JSON.stringify(deferred), { status: 200 });

describe('finite Listing deferral', () => {
  it.each(['zh', 'ru'] as const)(
    'submits once explicitly and prevents extension in %s',
    async (language) => {
      const send = vi.fn<typeof fetch>(() => Promise.resolve(response()));
      const context: ConsoleRequest = {
        apiBaseUrl: 'http://localhost',
        accessToken: 'fixture',
        fetchImpl: send,
      };
      render(
        <LanguageProvider initial={language}>
          <ListingDeferral
            context={context}
            target={{ kind: 'ACTION', actionId: 'action-one' }}
            current={undefined}
          />
        </LanguageProvider>,
      );
      expect(send).not.toHaveBeenCalled();
      fireEvent.change(screen.getByLabelText(t('deferralMinutes', language)), {
        target: { value: '60' },
      });
      fireEvent.change(screen.getByLabelText(t('deferralReason', language)), {
        target: { value: deferred.reason },
      });
      fireEvent.click(screen.getByRole('button', { name: t('deferralSubmit', language) }));
      expect(await screen.findByText(t('deferralActive', language))).toBeInTheDocument();
      expect(screen.getByRole('button', { name: t('deferralSubmit', language) })).toBeDisabled();
      expect(send).toHaveBeenCalledTimes(1);
      expect(send.mock.calls[0]?.[0]).toBe(
        'http://localhost/api/v1/console/listing/actions/action-one/responsibility/deferrals',
      );
      expect(send.mock.calls[0]?.[1]?.body).toBe(
        JSON.stringify({ minutes: 60, reason: deferred.reason }),
      );
    },
  );

  it('discards the prior account response and uses the exact diagnostic route', async () => {
    let finish: ((value: Response) => void) | undefined;
    const pending = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    const send = vi.fn<typeof fetch>(() => pending);
    const old: ConsoleRequest = {
      apiBaseUrl: 'http://localhost',
      accessToken: 'old',
      fetchImpl: send,
    };
    const current: ConsoleRequest = { ...old, accessToken: 'current' };
    const component = (context: ConsoleRequest) => (
      <LanguageProvider initial="zh">
        <ListingDeferral
          context={context}
          target={{ kind: 'DIAGNOSTIC', listingId: 'listing-one', taskId: 'task-one' }}
          current={undefined}
        />
      </LanguageProvider>
    );
    const view = render(component(old));
    fireEvent.change(screen.getByLabelText(t('deferralMinutes', 'zh')), {
      target: { value: '60' },
    });
    fireEvent.change(screen.getByLabelText(t('deferralReason', 'zh')), {
      target: { value: deferred.reason },
    });
    fireEvent.click(screen.getByRole('button', { name: t('deferralSubmit', 'zh') }));
    await waitFor(() => {
      expect(send).toHaveBeenCalledTimes(1);
    });
    expect(send.mock.calls[0]?.[0]).toBe(
      'http://localhost/api/v1/console/listing/health/listings/listing-one/responsibilities/task-one/deferrals',
    );
    view.rerender(component(current));
    await act(async () => {
      finish?.(response());
      await pending;
    });
    expect(screen.queryByText(t('deferralActive', 'zh'))).not.toBeInTheDocument();
    expect(screen.getByLabelText(t('deferralReason', 'zh'))).toHaveValue('');
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('rejects malformed or unknown server deferral data', () => {
    expect(parseListingTaskDeferral(deferred)?.minutes).toBe(60);
    expect(parseListingTaskDeferral({ ...deferred, state: 'PAUSED' })).toBeUndefined();
    expect(parseListingTaskDeferral({ ...deferred, minutes: 0 })).toBeUndefined();
    expect(parseListingTaskDeferral({ ...deferred, minutes: 1.5 })).toBeUndefined();
    expect(parseListingTaskDeferral({ ...deferred, expiresAt: null })).toBeUndefined();
  });
});
