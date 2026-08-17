import { execFileSync } from 'node:child_process';

/** Environment field carrying the contributor-authored source object name. */
export const SOURCE_HEAD_ENVIRONMENT_VARIABLE = 'MARKETOPS_SOURCE_HEAD_SHA';

const FULL_SOURCE_SHA = /^[0-9a-f]{40}$/;

type Environment = Readonly<Record<string, string | undefined>>;
type RepositoryHeadReader = (repositoryRoot: string) => string;

function enabled(value: string | undefined): boolean {
  return value !== undefined && !['', '0', 'false'].includes(value.toLowerCase());
}

function isContinuousIntegration(environment: Environment): boolean {
  return enabled(environment.CI) || enabled(environment.GITHUB_ACTIONS);
}

function readRepositoryHead(repositoryRoot: string): string {
  return execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
  }).trim();
}

function requireFullSourceSha(value: string, source: string): string {
  if (!FULL_SOURCE_SHA.test(value)) {
    throw new Error(`${source} must provide a full lowercase hexadecimal source object name`);
  }
  return value;
}

/**
 * Resolve the identity rendered by the browser build and asserted by its test.
 *
 * CI must identify contributor-authored source explicitly because its checkout
 * can be a temporary merge object. A local run has no such separate identity,
 * so it may use the current repository object when no explicit value is given.
 */
export function resolveBrowserSourceIdentity(
  repositoryRoot: string,
  environment: Environment = process.env,
  repositoryHeadReader: RepositoryHeadReader = readRepositoryHead,
): string {
  const explicit = environment[SOURCE_HEAD_ENVIRONMENT_VARIABLE];
  if (explicit !== undefined) {
    return requireFullSourceSha(explicit, SOURCE_HEAD_ENVIRONMENT_VARIABLE);
  }
  if (isContinuousIntegration(environment)) {
    throw new Error(`CI browser verification requires ${SOURCE_HEAD_ENVIRONMENT_VARIABLE}`);
  }
  return requireFullSourceSha(repositoryHeadReader(repositoryRoot), 'repository HEAD');
}
