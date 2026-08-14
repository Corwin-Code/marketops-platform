import js from '@eslint/js';
import prettier from 'eslint-config-prettier';
import tseslint from 'typescript-eslint';

/**
 * Lint configuration for the operations console.
 *
 * The rules that are switched on beyond the recommended sets are the ones whose
 * absence has a runtime consequence in this application: an unhandled promise
 * when the backend is unreachable, or a value rendered without being narrowed.
 */
export default tseslint.config(
  { ignores: ['dist/**', 'coverage/**', 'node_modules/**', '*.tsbuildinfo'] },
  js.configs.recommended,
  ...tseslint.configs.strictTypeChecked,
  ...tseslint.configs.stylisticTypeChecked,
  {
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': 'error',
      '@typescript-eslint/switch-exhaustiveness-check': 'error',
      '@typescript-eslint/consistent-type-imports': 'error',
      'no-console': 'error',
      eqeqeq: ['error', 'always'],
    },
  },
  {
    files: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'src/__tests__/**'],
    rules: {
      // A test may assert on a value the type system cannot narrow, which is
      // the point of the assertion.
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-dynamic-delete': 'off',
      '@typescript-eslint/no-unnecessary-type-assertion': 'off',
    },
  },
  {
    files: ['vite.config.ts', 'vite.constants.ts', 'eslint.config.js', 'scripts/**/*.mjs'],
    languageOptions: {
      parserOptions: {
        projectService: false,
        project: null,
      },
    },
    ...tseslint.configs.disableTypeChecked,
  },
  prettier,
);
