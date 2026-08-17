/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';
import { buildTimeConstants, ENV_PREFIX, frontendPackageVersion } from './vite.constants.ts';

const packageManifest: unknown = JSON.parse(
  readFileSync(new URL('./package.json', import.meta.url), 'utf8'),
);

/**
 * Bundler configuration for the operations console.
 *
 * Two decisions here carry a security consequence and are asserted by tests:
 * the environment prefix, which decides what may reach a public artefact, and
 * the set of replaced identifiers, which is exactly two.
 */
export default defineConfig({
  plugins: [react()],

  // Only variables named for this console reach the bundle.
  envPrefix: ENV_PREFIX,

  // Exactly two identifiers are replaced at build time. Anything else the
  // application needs is read at run time from the backend, where it can be
  // withheld; a value baked in here cannot.
  define: buildTimeConstants(process.env, frontendPackageVersion(packageManifest)),

  build: {
    outDir: 'dist',
    // A source map published beside the bundle hands the reader the original
    // sources. The map is produced for the build log and left out of the output.
    sourcemap: false,
    target: 'es2023',
    reportCompressedSize: false,
  },

  server: {
    // Loopback only, matching the backend. The console talks to an
    // unauthenticated backend, so neither may be reachable from the network.
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
  },

  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
  },

  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/__tests__/setup.ts'],
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    restoreMocks: true,
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'lcov'],
      include: ['src/**/*.ts', 'src/**/*.tsx'],
      exclude: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'src/__tests__/**', 'src/main.tsx'],
      thresholds: {
        lines: 80,
        branches: 70,
        functions: 80,
        statements: 80,
      },
    },
  },
});
