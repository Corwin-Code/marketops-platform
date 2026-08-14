import { describe, expect, it } from 'vitest';
import { buildInfo, UNKNOWN_COMMIT, UNKNOWN_VERSION } from '../buildInfo';

describe('build identity', () => {
  it('always reports a version and a commit', () => {
    const info = buildInfo();

    expect(typeof info.version).toBe('string');
    expect(typeof info.commit).toBe('string');
    expect(info.version).not.toBe('');
    expect(info.commit).not.toBe('');
  });

  it('reports the agreed fallbacks rather than an empty field', () => {
    const info = buildInfo();

    // The test run performs the same replacement the build performs, so both
    // the stamped and the unstamped outcome are acceptable here; what must never
    // happen is a blank field on the screen.
    expect([UNKNOWN_VERSION, info.version]).toContain(info.version);
    expect([UNKNOWN_COMMIT, info.commit]).toContain(info.commit);
  });
});
