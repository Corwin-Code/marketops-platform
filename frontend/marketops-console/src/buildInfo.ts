/**
 * What this build of the console is.
 *
 * The two values are replaced by the bundler. They are read through a guard so
 * a test, which runs without the replacement, sees the same fallback an
 * unstamped build produces rather than a reference error.
 */

/** Reported when the build supplied no version. */
export const UNKNOWN_VERSION = 'UNKNOWN';

/** Reported when the build supplied no commit. */
export const UNKNOWN_COMMIT = 'unknown';

/** Identity of the running console. */
export interface BuildInfo {
  /** Version of the console. */
  readonly version: string;
  /** Commit the console was built from. */
  readonly commit: string;
}

function replaced(value: string | undefined, fallback: string): string {
  return value === undefined || value === '' ? fallback : value;
}

/** Return the identity of this build. */
export function buildInfo(): BuildInfo {
  const version =
    typeof __MARKETOPS_BUILD_VERSION__ === 'string' ? __MARKETOPS_BUILD_VERSION__ : undefined;
  const commit =
    typeof __MARKETOPS_BUILD_COMMIT__ === 'string' ? __MARKETOPS_BUILD_COMMIT__ : undefined;
  return {
    version: replaced(version, UNKNOWN_VERSION),
    commit: replaced(commit, UNKNOWN_COMMIT),
  };
}
