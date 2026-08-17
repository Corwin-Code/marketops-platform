import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import {
  resolveBrowserSourceIdentity,
  SOURCE_HEAD_ENVIRONMENT_VARIABLE,
} from '../../tests/browser/sourceIdentity.ts';

const SOURCE_SHA = 'a'.repeat(40);
const repositoryRoot = resolve(process.cwd(), '../..');

describe('browser source identity', () => {
  it('uses an explicit full contributor source SHA in CI', () => {
    expect(
      resolveBrowserSourceIdentity(repositoryRoot, {
        CI: 'true',
        [SOURCE_HEAD_ENVIRONMENT_VARIABLE]: SOURCE_SHA,
      }),
    ).toBe(SOURCE_SHA);
  });

  it('fails closed when CI omits the contributor source SHA', () => {
    expect(() => resolveBrowserSourceIdentity(repositoryRoot, { CI: 'true' })).toThrow(
      SOURCE_HEAD_ENVIRONMENT_VARIABLE,
    );
  });

  it.each(['abcdef0', 'g'.repeat(40), 'A'.repeat(40)])(
    'rejects the invalid explicit source identity %s',
    (sourceIdentity) => {
      expect(() =>
        resolveBrowserSourceIdentity(repositoryRoot, {
          CI: 'true',
          [SOURCE_HEAD_ENVIRONMENT_VARIABLE]: sourceIdentity,
        }),
      ).toThrow('full lowercase hexadecimal');
    },
  );

  it('uses the full repository Head for a local run without an explicit identity', () => {
    const repositoryHead = execFileSync('git', ['rev-parse', 'HEAD'], {
      cwd: repositoryRoot,
      encoding: 'utf8',
    }).trim();

    expect(resolveBrowserSourceIdentity(repositoryRoot, {})).toBe(repositoryHead);
  });

  it('rejects an invalid repository Head rather than publishing it', () => {
    expect(() =>
      resolveBrowserSourceIdentity(repositoryRoot, {}, () => 'short-object-name'),
    ).toThrow('repository HEAD');
  });
});
