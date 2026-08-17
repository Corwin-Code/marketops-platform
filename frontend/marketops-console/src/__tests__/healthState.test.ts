import { describe, expect, it } from 'vitest';
import type { MetaStatus, MetaStatusOutcome } from '../api/metaStatus';
import { HEALTH_STATE_NAMES, INITIALISING, toHealthState } from '../health/healthState';
import type { HealthStateName } from '../health/healthState';

const STATUS: MetaStatus = {
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

function ok(overrides: Partial<MetaStatus> = {}): MetaStatusOutcome {
  return { ok: true, value: { ...STATUS, ...overrides } };
}

describe('the seven console states', () => {
  it('names exactly seven', () => {
    expect(HEALTH_STATE_NAMES).toHaveLength(7);
    expect(new Set(HEALTH_STATE_NAMES).size).toBe(7);
  });

  it('starts in the state that has learned nothing yet', () => {
    expect(INITIALISING.name).toBe('initialising');
    expect(INITIALISING.usable).toBe(false);
  });

  it('reports a reachable, migrated platform as ready', () => {
    const state = toHealthState(ok());

    expect(state.name).toBe('ready');
    expect(state.usable).toBe(true);
    expect(state.status).toEqual(STATUS);
  });

  it('reports a running platform with a silent database as degraded', () => {
    for (const status of ['DOWN', 'UNKNOWN', '']) {
      const state = toHealthState(ok({ database: { status } }));

      expect(state.name, `database ${status}`).toBe('degraded');
      expect(state.usable).toBe(false);
    }
  });

  it('reports an unknown schema version as pending migration', () => {
    const state = toHealthState(ok({ migration: { currentVersion: 'UNKNOWN' } }));

    expect(state.name).toBe('pendingMigration');
    expect(state.usable).toBe(false);
  });

  it('ranks a silent database above an unknown schema version', () => {
    const state = toHealthState(
      ok({ database: { status: 'DOWN' }, migration: { currentVersion: 'UNKNOWN' } }),
    );

    // Fixing the connection reveals the version as a consequence, so the
    // connection is what the operator is told about.
    expect(state.name).toBe('degraded');
  });

  it('reports each request failure as its own state', () => {
    const cases: [MetaStatusOutcome, HealthStateName][] = [
      [{ ok: false, failure: { kind: 'unreachable', detail: 'TypeError' } }, 'unreachable'],
      [{ ok: false, failure: { kind: 'failing', status: 500 } }, 'failing'],
      [{ ok: false, failure: { kind: 'malformed', detail: 'missing product' } }, 'malformed'],
    ];

    for (const [outcome, expected] of cases) {
      const state = toHealthState(outcome);

      expect(state.name).toBe(expected);
      expect(state.usable).toBe(false);
      expect(state.status).toBeUndefined();
    }
  });

  it('gives every state a summary and an action', () => {
    const states = [
      INITIALISING,
      toHealthState(ok()),
      toHealthState(ok({ database: { status: 'DOWN' } })),
      toHealthState(ok({ migration: { currentVersion: 'UNKNOWN' } })),
      toHealthState({ ok: false, failure: { kind: 'unreachable', detail: 'TypeError' } }),
      toHealthState({ ok: false, failure: { kind: 'failing', status: 500 } }),
      toHealthState({ ok: false, failure: { kind: 'malformed', detail: 'missing product' } }),
    ];

    expect(new Set(states.map((state) => state.name))).toEqual(new Set(HEALTH_STATE_NAMES));
    for (const state of states) {
      expect(state.summary.length, state.name).toBeGreaterThan(0);
      expect(state.action.length, state.name).toBeGreaterThan(0);
    }
  });

  it('never reports a backend detail it was not given', () => {
    const state = toHealthState({ ok: false, failure: { kind: 'failing', status: 500 } });

    expect(state.summary).not.toContain('marketops_app');
    expect(state.summary).not.toContain('jdbc');
  });
});
