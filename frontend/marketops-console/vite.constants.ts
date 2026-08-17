/**
 * Build-time constants shared by the bundler configuration and its test.
 *
 * Keeping them here means the test asserts the same values the build uses,
 * rather than a second copy that can drift.
 */

/**
 * Prefix a variable must carry to reach the browser bundle.
 *
 * Vite's default prefix is `VITE_`, which would publish every variable of the
 * shell that happens to start that way. Narrowing it to this project's own
 * prefix means a variable reaches the bundle only if it was named for it.
 */
export const ENV_PREFIX = 'VITE_MARKETOPS_';

/** Identifier replaced at build time with the console's version. */
export const BUILD_VERSION_KEY = '__MARKETOPS_BUILD_VERSION__';

/** Identifier replaced at build time with the commit the console was built from. */
export const BUILD_COMMIT_KEY = '__MARKETOPS_BUILD_COMMIT__';

/** Value reported when the build did not supply the commit. */
export const UNKNOWN_COMMIT = 'unknown';

/** A commit is a hexadecimal object name, or it is not reported at all. */
const COMMIT_PATTERN = /^[0-9a-f]{7,40}$/;

/** Accepted package versions follow npm's numeric semantic-version core. */
const PACKAGE_VERSION_PATTERN =
  /^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;

/** Read the console version from its canonical package manifest. */
export function frontendPackageVersion(manifest: unknown): string {
  const version =
    typeof manifest === 'object' && manifest !== null
      ? (manifest as { readonly version?: unknown }).version
      : undefined;
  if (typeof version !== 'string' || !PACKAGE_VERSION_PATTERN.test(version)) {
    throw new Error('package.json must contain a valid semantic version');
  }
  return version;
}

/**
 * Return the two identifiers the bundle may carry.
 *
 * The commit is validated rather than passed through. It arrives from a build
 * parameter and is rendered in a page that anyone with the console open can
 * read, so an unexpected string must not be published verbatim.
 */
export function buildTimeConstants(
  environment: Record<string, string | undefined>,
  packageVersion: string,
): {
  [BUILD_VERSION_KEY]: string;
  [BUILD_COMMIT_KEY]: string;
} {
  const commit = environment.MARKETOPS_BUILD_COMMIT ?? '';
  return {
    [BUILD_VERSION_KEY]: JSON.stringify(packageVersion),
    [BUILD_COMMIT_KEY]: JSON.stringify(COMMIT_PATTERN.test(commit) ? commit : UNKNOWN_COMMIT),
  };
}
