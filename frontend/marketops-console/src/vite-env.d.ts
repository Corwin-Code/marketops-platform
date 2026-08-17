/// <reference types="vite/client" />

/** Version of the console, replaced at build time. */
declare const __MARKETOPS_BUILD_VERSION__: string;

/** Commit the console was built from, replaced at build time. */
declare const __MARKETOPS_BUILD_COMMIT__: string;

interface ImportMetaEnv {
  /** Origin the console sends its requests to. */
  readonly VITE_MARKETOPS_API_BASE_URL?: string;
  /** Name of the environment the console is pointed at. */
  readonly VITE_MARKETOPS_ENVIRONMENT?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
