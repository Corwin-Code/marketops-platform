import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ConsoleRequest } from '../api/console';
import { parseListingResponsibility } from '../api/listingConversion';
import { ListingResponsibility } from '../listing/ListingResponsibility';
import { LanguageProvider } from '../listing/i18n/language';
import { t } from '../listing/i18n/ui';

const status = {
  taskId: 'original-task',
  calibrationPackageId: 'original-package',
  calibrationVersion: 1,
  basisDigest: 'original-basis',
  clockState: 'OUT_OF_COVERAGE',
  firstRaisedAt: '2026-09-04T14:30:00Z',
  acknowledgementDueAt: '2026-09-07T07:30:00Z',
  actionDueAt: '2026-09-07T08:30:00Z',
  outcomeMaturityDueAt: '2026-10-04T14:30:00Z',
  nextCoveredAt: '2026-09-07T06:00:00Z',
  acknowledgedAt: null,
  firstAttributableActionAt: null,
  acknowledgementBreached: false,
  actionBreached: false,
  wallClockAgeSeconds: 3600,
};
const response = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });

describe('original Listing responsibility', () => {
  it.each(['zh', 'ru'] as const)(
    'loads on demand and keeps acknowledgement distinct from action in %s',
    async (language) => {
      let acknowledged = false;
      const send = vi.fn((_url: unknown, init?: RequestInit) => {
        if (init?.method === 'POST') {
          acknowledged = true;
          return Promise.resolve(new Response(null, { status: 204 }));
        }
        return Promise.resolve(
          response({
            bound: true,
            status: { ...status, acknowledgedAt: acknowledged ? '2026-09-05T00:00:00Z' : null },
          }),
        );
      });
      const context: ConsoleRequest = {
        apiBaseUrl: 'http://localhost',
        accessToken: 'fixture',
        fetchImpl: send as typeof fetch,
      };
      render(
        <LanguageProvider initial={language}>
          <ListingResponsibility context={context} actionId="action-one" />
        </LanguageProvider>,
      );
      expect(send).not.toHaveBeenCalled();
      fireEvent.click(screen.getByRole('button', { name: t('responsibilityLoad', language) }));
      expect(await screen.findByText('original-task')).toBeInTheDocument();
      expect(screen.getByText(status.firstRaisedAt)).toBeInTheDocument();
      expect(screen.getByText(t('responsibilityOut', language))).toBeInTheDocument();
      expect(acknowledged).toBe(false);
      fireEvent.click(
        screen.getByRole('button', { name: t('responsibilityAcknowledge', language) }),
      );
      await waitFor(() => {
        expect(
          screen.getByRole('button', { name: t('responsibilityAcknowledge', language) }),
        ).toBeDisabled();
      });
      expect(await screen.findByText('2026-09-05T00:00:00Z')).toBeInTheDocument();
      expect(send.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
      expect(
        screen.getByText(t('responsibilityAction', language)).nextElementSibling,
      ).toHaveTextContent('—');
    },
  );

  it('preserves unknown policy deadlines and breaches instead of inventing a safe answer', () => {
    const parsed = parseListingResponsibility({
      bound: true,
      status: {
        ...status,
        clockState: 'SLO_UNRESOLVED',
        acknowledgementDueAt: null,
        actionDueAt: null,
        acknowledgementBreached: null,
        actionBreached: null,
      },
    });
    expect(parsed?.status?.actionDueAt).toBeUndefined();
    expect(parsed?.status?.actionBreached).toBeUndefined();
    expect(parseListingResponsibility({ bound: false })).toEqual({ bound: false });
    expect(parseListingResponsibility({ bound: true, status: {} })).toBeUndefined();
  });

  it('discards a late response after the current account or action changes', async () => {
    let finish: ((value: Response) => void) | undefined;
    const pending = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    const old: ConsoleRequest = {
      apiBaseUrl: 'http://localhost',
      accessToken: 'old',
      fetchImpl: vi.fn(() => pending),
    };
    const current: ConsoleRequest = {
      apiBaseUrl: 'http://localhost',
      accessToken: 'current',
      fetchImpl: vi.fn(() => Promise.resolve(response({ bound: false }))),
    };
    const component = (context: ConsoleRequest, actionId: string) => (
      <LanguageProvider initial="zh">
        <ListingResponsibility context={context} actionId={actionId} />
      </LanguageProvider>
    );
    const view = render(component(old, 'old-action'));
    fireEvent.click(screen.getByRole('button', { name: t('responsibilityLoad', 'zh') }));
    view.rerender(component(current, 'new-action'));
    await act(async () => {
      finish?.(response({ bound: true, status }));
      await pending;
    });
    expect(screen.queryByText('original-task')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: t('responsibilityLoad', 'zh') }));
    expect(await screen.findByText(t('responsibilityUnbound', 'zh'))).toBeInTheDocument();
  });
});
