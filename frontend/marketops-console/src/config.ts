/** Settings the console reads from its explicitly generated environment. */

/** Names of every value that must be present before the console makes a request. */
export const REQUIRED_CONFIG_KEYS = [
  'VITE_MARKETOPS_API_BASE_URL',
  'VITE_MARKETOPS_ENVIRONMENT',
] as const;

/** A public environment name understood by the console. */
export type ConsoleConfigKey = (typeof REQUIRED_CONFIG_KEYS)[number];

/** Raw public environment values supplied by Vite or a test. */
export type ConsoleEnvironment = Partial<
  Record<
    | ConsoleConfigKey
    | 'VITE_MARKETOPS_OIDC_AUTHORIZATION_ENDPOINT'
    | 'VITE_MARKETOPS_OIDC_TOKEN_ENDPOINT'
    | 'VITE_MARKETOPS_OIDC_CLIENT_ID'
    | 'VITE_MARKETOPS_OIDC_AUDIENCE'
    | 'VITE_MARKETOPS_STORE_ID',
    string | undefined
  >
>;

/** Resolved console settings. */
export interface ConsoleConfig {
  /** Origin every request is sent to. */
  readonly apiBaseUrl: string;
  /** Name of the environment the console is pointed at. */
  readonly environment: string;
}

/** Names of every value the operating console needs beyond the required ones. */
export const OPERATING_CONFIG_KEYS = [
  'VITE_MARKETOPS_OIDC_AUTHORIZATION_ENDPOINT',
  'VITE_MARKETOPS_OIDC_TOKEN_ENDPOINT',
  'VITE_MARKETOPS_OIDC_CLIENT_ID',
  'VITE_MARKETOPS_OIDC_AUDIENCE',
  'VITE_MARKETOPS_STORE_ID',
] as const;

/** A setting the operating console needs. */
export type OperatingConfigKey = (typeof OPERATING_CONFIG_KEYS)[number];

/** Settings the operating console needs in order to sign anybody in. */
export interface OperatingConfig {
  /** Where the provider's authorization endpoint lives. */
  readonly authorizationEndpoint: string;
  /** Where codes are redeemed. */
  readonly tokenEndpoint: string;
  /** This console's registered public client identifier. */
  readonly clientId: string;
  /** What the token must be usable against. */
  readonly audience: string;
  /** Store this deployment's operators work in. */
  readonly storeId: string;
}

/**
 * Resolve the operating settings, if this deployment has them.
 *
 * Absence is a normal state rather than an error. A workstation has no identity
 * provider, and a console that refused to start there would make the
 * platform-state panel unreachable exactly where it is most used.
 */
export function resolveOperatingConfig(
  env: Partial<Record<OperatingConfigKey, string | undefined>>,
): OperatingConfig | undefined {
  const missing = OPERATING_CONFIG_KEYS.some((key) => (env[key]?.trim() ?? '') === '');
  if (missing) {
    return undefined;
  }
  return {
    authorizationEndpoint: env.VITE_MARKETOPS_OIDC_AUTHORIZATION_ENDPOINT?.trim() ?? '',
    tokenEndpoint: env.VITE_MARKETOPS_OIDC_TOKEN_ENDPOINT?.trim() ?? '',
    clientId: env.VITE_MARKETOPS_OIDC_CLIENT_ID?.trim() ?? '',
    audience: env.VITE_MARKETOPS_OIDC_AUDIENCE?.trim() ?? '',
    storeId: env.VITE_MARKETOPS_STORE_ID?.trim() ?? '',
  };
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
