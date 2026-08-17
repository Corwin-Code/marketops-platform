/** Settings the console reads from its explicitly generated environment. */

/** Names of every value that must be present before the console makes a request. */
export const REQUIRED_CONFIG_KEYS = [
  'VITE_MARKETOPS_API_BASE_URL',
  'VITE_MARKETOPS_ENVIRONMENT',
] as const;

/** A public environment name understood by the console. */
export type ConsoleConfigKey = (typeof REQUIRED_CONFIG_KEYS)[number];

/** Raw public environment values supplied by Vite or a test. */
export type ConsoleEnvironment = Partial<Record<ConsoleConfigKey, string | undefined>>;

/** Resolved console settings. */
export interface ConsoleConfig {
  /** Origin every request is sent to. */
  readonly apiBaseUrl: string;
  /** Name of the environment the console is pointed at. */
  readonly environment: string;
}

/** Result of resolving public console configuration. */
export type ConsoleConfigResolution =
  | { readonly ok: true; readonly value: ConsoleConfig }
  | { readonly ok: false; readonly missingKeys: readonly ConsoleConfigKey[] };

/**
 * Resolve the settings from a set of environment values.
 *
 * A trailing separator is removed so a configured origin and a path can always
 * be joined the same way, whichever form the value was written in.
 */
export function resolveConfig(env: ConsoleEnvironment): ConsoleConfigResolution {
  const apiBaseUrl = env.VITE_MARKETOPS_API_BASE_URL?.trim() ?? '';
  const environment = env.VITE_MARKETOPS_ENVIRONMENT?.trim() ?? '';

  const missingKeys = REQUIRED_CONFIG_KEYS.filter((key) => (env[key]?.trim() ?? '') === '');
  if (missingKeys.length > 0) {
    return { ok: false, missingKeys };
  }

  return {
    ok: true,
    value: {
      apiBaseUrl: apiBaseUrl.replace(/\/+$/, ''),
      environment,
    },
  };
}
