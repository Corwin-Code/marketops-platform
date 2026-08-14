import { describe, expect, it } from 'vitest';
import { REQUIRED_CONFIG_KEYS, resolveConfig } from '../config';

describe('console settings', () => {
  it('names both missing settings without inventing values', () => {
    expect(resolveConfig({})).toEqual({ ok: false, missingKeys: REQUIRED_CONFIG_KEYS });
  });

  it('treats blank values as missing', () => {
    const resolution = resolveConfig({
      VITE_MARKETOPS_API_BASE_URL: '   ',
      VITE_MARKETOPS_ENVIRONMENT: '',
    });

    expect(resolution).toEqual({ ok: false, missingKeys: REQUIRED_CONFIG_KEYS });
  });

  it('removes a trailing separator so a path can always be appended the same way', () => {
    expect(
      resolveConfig({
        VITE_MARKETOPS_API_BASE_URL: 'http://127.0.0.1:9000///',
        VITE_MARKETOPS_ENVIRONMENT: 'local',
      }),
    ).toEqual({
      ok: true,
      value: { apiBaseUrl: 'http://127.0.0.1:9000', environment: 'local' },
    });
  });

  it('keeps a configured origin and environment', () => {
    const resolution = resolveConfig({
      VITE_MARKETOPS_API_BASE_URL: '  http://127.0.0.1:9000  ',
      VITE_MARKETOPS_ENVIRONMENT: ' local ',
    });

    expect(resolution).toEqual({
      ok: true,
      value: { apiBaseUrl: 'http://127.0.0.1:9000', environment: 'local' },
    });
  });
});
