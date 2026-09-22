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
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      '*.tsbuildinfo',
    ],
  },
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
    files: ['vite.config.ts', 'vite.constants.ts', 'eslint.config.js'],
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
