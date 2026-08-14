import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { StrictMode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { ConsoleConfig } from '../config';
import { HealthShell } from '../health/HealthShell';

const CONFIG: ConsoleConfig = {
  apiBaseUrl: 'http://127.0.0.1:8080',
  environment: 'local',
};

const COMPLETE = {
  product: 'MarketOps Russia',
  application: 'marketops-server',
  environment: 'local',
  buildVersion: '0.1.0-SNAPSHOT',
  gitCommit: 'unknown',
  serverTimeUtc: '2026-08-14T10:15:30Z',
  database: { status: 'UP' },
  migration: { currentVersion: '1' },
  correlationId: '00000000-0000-4000-8000-000000000000',
};

function respondWith(body: unknown, status = 200): typeof fetch {
  return vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  ) as unknown as typeof fetch;
}

async function stateOf(container: HTMLElement): Promise<string> {
  const section = container.querySelector('section[data-state]');
  await waitFor(() => {
    expect(section?.getAttribute('data-state')).not.toBe('initialising');
  });
  return section?.getAttribute('data-state') ?? '';
}

describe('the console screen', () => {
  it('reaches a settled state when StrictMode replays effects', async () => {
    const { container } = render(
      <StrictMode>
        <HealthShell config={CONFIG} fetchImpl={respondWith(COMPLETE)} />
      </StrictMode>,
    );

    expect(await stateOf(container)).toBe('ready');
  });

  it('shows the platform as ready and reports what it was told', async () => {
    const { container } = render(<HealthShell config={CONFIG} fetchImpl={respondWith(COMPLETE)} />);

    expect(await stateOf(container)).toBe('ready');
    expect(screen.getByText('marketops-server')).toBeInTheDocument();
    expect(screen.getByText('The platform is usable.')).toBeInTheDocument();
  });

  it('shows a silent database as degraded and withholds the reassuring summary', async () => {
    const { container } = render(
      <HealthShell
        config={CONFIG}
        fetchImpl={respondWith({ ...COMPLETE, database: { status: 'DOWN' } })}
      />,
    );

    expect(await stateOf(container)).toBe('degraded');
    expect(screen.getByText('The platform is not usable yet.')).toBeInTheDocument();
  });

  it('shows an unreachable backend without rendering any platform detail', async () => {
    const fetchImpl = vi
      .fn()
      .mockRejectedValue(new TypeError('connect ECONNREFUSED 10.0.0.7:8080'));

    const { container } = render(
      <HealthShell config={CONFIG} fetchImpl={fetchImpl as unknown as typeof fetch} />,
    );

    expect(await stateOf(container)).toBe('unreachable');
    expect(screen.queryByText('marketops-server')).not.toBeInTheDocument();
    expect(container.textContent).not.toContain('10.0.0.7');
  });

  it('shows a payload it cannot read as malformed', async () => {
    const { container } = render(
      <HealthShell config={CONFIG} fetchImpl={respondWith({ product: 'MarketOps Russia' })} />,
    );

    expect(await stateOf(container)).toBe('malformed');
  });

  it('shows an error status as failing', async () => {
    const { container } = render(<HealthShell config={CONFIG} fetchImpl={respondWith({}, 503)} />);

    expect(await stateOf(container)).toBe('failing');
  });

  it('asks again when the operator asks it to', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ ...COMPLETE, database: { status: 'DOWN' } }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(COMPLETE), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );

    const { container } = render(
      <HealthShell config={CONFIG} fetchImpl={fetchImpl as unknown as typeof fetch} />,
    );

    expect(await stateOf(container)).toBe('degraded');

    fireEvent.click(screen.getByRole('button', { name: /check again/i }));

    await waitFor(() => {
      expect(container.querySelector('section[data-state]')?.getAttribute('data-state')).toBe(
        'ready',
      );
    });
  });

  it('always names the console build and what it is pointed at', async () => {
    const { container } = render(<HealthShell config={CONFIG} fetchImpl={respondWith(COMPLETE)} />);

    await stateOf(container);
    const footer = container.querySelector('footer');
    expect(footer?.textContent).toContain('http://127.0.0.1:8080');
    expect(footer?.textContent).toContain('local');
  });
});
