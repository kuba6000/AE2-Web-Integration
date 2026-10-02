import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import { defineConfig } from 'eslint/config';

export default defineConfig([
    {
        files: ['src/main/typescript/**/*.ts', '*.config.mjs', 'tools/build-frontend.mjs', 'tools/ui-tests/**/*.cjs'],
        rules: { 'no-var': 'error', 'prefer-const': 'error' }
    },
    {
        files: ['src/main/typescript/**/*.ts'],
        extends: [tseslint.configs.recommended],
        languageOptions: { globals: globals.browser },
        rules: { '@typescript-eslint/no-explicit-any': 'error' }
    },
    {
        files: ['*.config.mjs', 'tools/build-frontend.mjs'],
        languageOptions: { globals: globals.node },
        rules: js.configs.recommended.rules
    },
    {
        files: ['tools/ui-tests/**/*.cjs'],
        // Playwright callbacks execute in the browser; the HTTP fixture executes in Node.
        languageOptions: { globals: { ...globals.node, ...globals.browser } },
        rules: js.configs.recommended.rules
    }
]);
