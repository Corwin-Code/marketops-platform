import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  BUILD_COMMIT_KEY,
  BUILD_VERSION_KEY,
  buildTimeConstants,
  CONNECT_SOURCE_PLACEHOLDER,
  connectSourceDirective,
  connectSources,
  ENV_PREFIX,
  frontendPackageVersion,
  UNKNOWN_COMMIT,
} from '../../vite.constants';

const PACKAGE_VERSION = frontendPackageVersion({ version: '0.1.0' });

/**
 * The bundler decisions that decide what becomes public.
 *
 * A bundle is served to anyone who opens the console, so the prefix that
 * governs which variables are inlined, and the set of identifiers replaced at
 * build time, are asserted rather than reviewed.
 */
describe('bundle configuration', () => {
  it('publishes only variables named for this console', () => {
    expect(ENV_PREFIX).toBe('VITE_MARKETOPS_');
  });

  it('replaces exactly two identifiers', () => {
    const constants = buildTimeConstants({}, PACKAGE_VERSION);

    expect(Object.keys(constants).sort()).toEqual([BUILD_COMMIT_KEY, BUILD_VERSION_KEY].sort());
  });

  it('uses the package version and the agreed commit fallback', () => {
    const constants = buildTimeConstants(
      { MARKETOPS_BUILD_VERSION: 'pull-request-ref' },
      PACKAGE_VERSION,
    );

    expect(constants[BUILD_VERSION_KEY]).toBe(JSON.stringify('0.1.0'));
    expect(constants[BUILD_COMMIT_KEY]).toBe(JSON.stringify(UNKNOWN_COMMIT));
  });

  it('rejects a missing or non-semantic package version', () => {
    for (const manifest of [{}, { version: '' }, { version: 'branch/main' }, null]) {
      expect(() => frontendPackageVersion(manifest)).toThrow(
        'package.json must contain a valid semantic version',
      );
    }
  });

  it('publishes a commit only when it is an object name', () => {
    const accepted = buildTimeConstants(
      {
        MARKETOPS_BUILD_COMMIT: '3ecc72ae509664ff0550f80ece98d4f50dbb0bc0',
      },
      PACKAGE_VERSION,
    );
    expect(accepted[BUILD_COMMIT_KEY]).toBe(
      JSON.stringify('3ecc72ae509664ff0550f80ece98d4f50dbb0bc0'),
    );

    for (const rejected of [
      'not-a-commit',
      '<script>alert(1)</script>',
      '../../etc/passwd',
      '3ECC72AE509664FF0550F80ECE98D4F50DBB0BC0',
      '3ecc72',
    ]) {
      expect(
        buildTimeConstants({ MARKETOPS_BUILD_COMMIT: rejected }, PACKAGE_VERSION)[BUILD_COMMIT_KEY],
      ).toBe(JSON.stringify(UNKNOWN_COMMIT));
    }
  });

  it('carries no third identifier that a later change could smuggle a value into', () => {
    // The literal is held in a constant so this file states a variable name and
    // a marker separately, and never the two joined as an assignment.
    const withheld = 'must-not-appear';
    const constants = buildTimeConstants(
      {
        MARKETOPS_DB_APP_PASSWORD: withheld,
        VITE_UNRELATED_SETTING: withheld,
        MARKETOPS_BUILD_TIME: withheld,
      },
      PACKAGE_VERSION,
    );

    expect(JSON.stringify(constants)).not.toContain(withheld);
  });
});

describe('TC-UI-050 the page permits exactly the origins this deployment talks to', () => {
  it('permits only the console itself when nothing is configured', () => {
    expect(connectSourceDirective({})).toBe("'self'");
  });

  it('permits the backend it is pointed at', () => {
    expect(
      connectSourceDirective({ VITE_MARKETOPS_API_BASE_URL: 'https://api.example.test/v1' }),
    ).toBe("'self' https://api.example.test");
  });

  it('permits the identity provider so a sign-in can complete', () => {
    expect(
      connectSourceDirective({
        VITE_MARKETOPS_API_BASE_URL: 'https://api.example.test',
        VITE_MARKETOPS_OIDC_TOKEN_ENDPOINT: 'https://id.example.test/token',
        VITE_MARKETOPS_OIDC_AUTHORIZATION_ENDPOINT: 'https://id.example.test/authorize',
      }),
    ).toBe("'self' https://api.example.test https://id.example.test");
  });

  it('names an origin rather than a path', () => {
    expect(
      connectSources({ VITE_MARKETOPS_API_BASE_URL: 'https://api.example.test/deep/path' }),
    ).toEqual(['https://api.example.test']);
  });

  it('leaves a setting that is not a URL out of the policy entirely', () => {
    expect(connectSources({ VITE_MARKETOPS_API_BASE_URL: "not a url'; script-src *" })).toEqual([]);
  });

  it('resolves the placeholder the page carries', () => {
    const page = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8');
    const policy = /content="(default-src[^"]+)"/.exec(page)?.[1] ?? '';

    expect(policy).toContain(CONNECT_SOURCE_PLACEHOLDER);
    // A directive browsers ignore in a meta element reads as protection that
    // is not there; framing is refused by the serving layer instead.
    expect(policy).not.toContain('frame-ancestors');
  });
});
